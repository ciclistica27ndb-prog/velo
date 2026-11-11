package it.velo.traduttore.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import it.velo.traduttore.BuildConfig
import it.velo.traduttore.translate.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Aggiornamento dall'app: legge `update.json` dall'ultima release GitHub, scarica il nuovo APK,
 * ne verifica integrità e firma e lo installa sopra la versione attuale (dati conservati).
 */
class UpdateManager(context: Context) {

    private val app = context.applicationContext

    data class UpdateInfo(val versionCode: Long, val versionName: String, val notes: String, val apkUrl: String, val sha256: String?)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val info: UpdateInfo) : State
        data class Downloading(val info: UpdateInfo, val progress: Float) : State
        data object Installing : State
        data class Failed(val message: String) : State
    }

    val isConfigured: Boolean get() = BuildConfig.UPDATE_REPO.isNotBlank()

    private val baseUrl: String get() = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest/download/"

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        set(State.Checking)
        try {
            val json = JSONObject(Http.get(baseUrl + "update.json"))
            val info = UpdateInfo(
                versionCode = json.getLong("versionCode"),
                versionName = json.optString("versionName", ""),
                notes = json.optString("notes", ""),
                apkUrl = baseUrl + json.optString("apk", "velo.apk"),
                sha256 = json.optString("sha256", "").takeIf { it.length == 64 },
            )
            if (info.versionCode > currentVersionCode()) {
                set(State.Available(info))
                info
            } else {
                set(State.UpToDate)
                null
            }
        } catch (e: Exception) {
            set(State.Failed(friendly(e)))
            null
        }
    }

    /** Scarica, verifica e avvia l'installazione. */
    suspend fun downloadAndInstall(info: UpdateInfo) {
        try {
            val apk = withContext(Dispatchers.IO) { download(info) }
            withContext(Dispatchers.IO) { verify(apk, info) }
            set(State.Installing)
            withContext(Dispatchers.IO) { install(apk) }
        } catch (e: Exception) {
            set(State.Failed(friendly(e)))
        }
    }

    fun canInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    private fun download(info: UpdateInfo): File {
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "velo-${info.versionCode}.apk")
        val conn = openFollowingRedirects(URL(info.apkUrl))
        try {
            val total = conn.contentLengthLong
            var done = 0L
            var lastReported = -1
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastReported) {
                                lastReported = pct
                                set(State.Downloading(info, done.toFloat() / total))
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return target
    }

    private fun openFollowingRedirects(start: URL): HttpURLConnection {
        var url = start
        repeat(6) {
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location == null) throw IOException("Redirect senza destinazione")
                url = URL(url, location)
            } else if (code in 200..299) {
                return conn
            } else {
                conn.disconnect()
                throw IOException("Download non riuscito (HTTP $code)")
            }
        }
        throw IOException("Troppi reindirizzamenti")
    }

    private fun verify(apk: File, info: UpdateInfo) {
        info.sha256?.let { expected ->
            val digest = MessageDigest.getInstance("SHA-256")
            apk.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) throw IOException("File scaricato danneggiato, riprova")
        }

        val pm = app.packageManager
        val archive = archiveInfo(pm, apk) ?: throw IOException("Il file scaricato non è un'app valida")
        if (archive.packageName != app.packageName) throw IOException("Il file scaricato non è Velo")
        if (PackageInfoCompat.getLongVersionCode(archive) <= currentVersionCode()) {
            throw IOException("La versione scaricata non è più recente")
        }
        // Stessa firma della versione installata (Android lo verificherebbe comunque).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val newSigners = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
            val installed = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val oldSigners = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
            if (!newSigners.isNullOrEmpty() && !oldSigners.isNullOrEmpty() && newSigners != oldSigners) {
                throw IOException("Firma dell'aggiornamento non riconosciuta")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
        return pm.getPackageArchiveInfo(apk.absolutePath, flags)
    }

    private fun install(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Dopo il primo aggiornamento fatto da Velo, Android può non chiedere più conferma.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("velo.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
            val intent = Intent(app, InstallResultReceiver::class.java).setAction(InstallResultReceiver.ACTION)
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or mutable
            val pending = PendingIntent.getBroadcast(app, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    private fun currentVersionCode(): Long = BuildConfig.VERSION_CODE.toLong()

    private fun friendly(e: Exception): String = when (e) {
        is Http.HttpException -> if (e.code == 404) "Nessuna versione pubblicata" else "Server non raggiungibile (${e.code})"
        is java.net.UnknownHostException -> "Nessuna connessione a internet"
        is java.net.SocketTimeoutException -> "Connessione troppo lenta, riprova"
        else -> e.message ?: "Errore sconosciuto"
    }

    companion object {
        private val _state = MutableStateFlow<State>(State.Idle)
        val state: StateFlow<State> = _state.asStateFlow()

        internal fun set(value: State) {
            _state.value = value
        }
    }
}
