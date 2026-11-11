package it.velo.traduttore.translate

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import it.velo.traduttore.EngineKind
import it.velo.traduttore.Languages
import it.velo.traduttore.Prefs
import it.velo.traduttore.R
import it.velo.traduttore.TranslationSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Decide cosa tradurre (riconosce la lingua, salta l'italiano) e lo traduce con il motore
 * scelto. Il motore offline (ML Kit) è sempre disponibile come riserva.
 */
class TranslationEngine(
    private val context: Context,
    private val listener: Listener,
) : Closeable {

    interface Listener {
        /** Un nuovo pacchetto lingua è pronto: conviene rianalizzare lo schermo. */
        fun onModelReady(language: String)
        fun onStatus(message: String?)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val identifier: LanguageIdentifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build(),
    )
    private val modelManager = RemoteModelManager.getInstance()
    private val translators = ConcurrentHashMap<String, Translator>()
    private val ready: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val downloading: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val cache = LruCache<String, String>(1500)
    private var lastOnlineWarning = 0L

    @Volatile
    private var downloadedCache: Set<String>? = null

    /**
     * @return per ogni testo la traduzione, o null se non va mostrata (già in italiano,
     *         lingua non riconosciuta o pacchetto non ancora pronto)
     */
    suspend fun translate(texts: List<String>, settings: TranslationSettings): List<String?> {
        if (texts.isEmpty()) return emptyList()
        val languages: List<String?> =
            if (settings.sourceLanguage == Prefs.AUTO) detect(texts) else List(texts.size) { settings.sourceLanguage }

        val online = onlineTranslator(settings)
        if (online != null) {
            try {
                return translateOnline(online, texts, languages)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${online.name} non disponibile, uso l'offline", e)
                warnOnlineFailure(online.name)
            }
        }
        return translateOffline(texts, languages, settings.allowMobileData)
    }

    private fun onlineTranslator(settings: TranslationSettings): OnlineTranslator? = when (settings.engine) {
        EngineKind.OFFLINE -> null
        EngineKind.DEEPL -> settings.deeplKey.takeIf { it.isNotBlank() }?.let(::DeepLTranslator)
        EngineKind.CLAUDE -> settings.claudeKey.takeIf { it.isNotBlank() }?.let(::ClaudeTranslator)
    }

    // --- Online ---------------------------------------------------------------------------

    private suspend fun translateOnline(
        engine: OnlineTranslator,
        texts: List<String>,
        languages: List<String?>,
    ): List<String?> {
        val result = arrayOfNulls<String>(texts.size)
        val pending = ArrayList<Int>()
        for (i in texts.indices) {
            if (languages[i] == TranslateLanguage.ITALIAN) continue
            val cached = cache.get(key(engine.name, texts[i]))
            if (cached != null) result[i] = cached else pending += i
        }
        if (pending.isNotEmpty()) {
            val prepared = pending.map { CaseStyle.of(texts[it]) }
            val translated = engine.translate(prepared.map { it.normalized })
            for ((n, i) in pending.withIndex()) {
                val value = prepared[n].restore(translated[n])
                cache.put(key(engine.name, texts[i]), value)
                result[i] = value
            }
        }
        return result.toList()
    }

    private fun warnOnlineFailure(name: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastOnlineWarning < 60_000) return
        lastOnlineWarning = now
        listener.onStatus(context.getString(R.string.status_online_failed, name))
        scope.launch {
            kotlinx.coroutines.delay(5_000)
            listener.onStatus(null)
        }
    }

    // --- Offline (ML Kit) -----------------------------------------------------------------

    private suspend fun translateOffline(
        texts: List<String>,
        languages: List<String?>,
        allowMobileData: Boolean,
    ): List<String?> {
        val usable = languages.filterNotNull()
            .filter { it != TranslateLanguage.ITALIAN }
            .toSet()
            .filter { ensureReady(it, allowMobileData) }
            .toSet()

        return coroutineScope {
            texts.indices.map { i ->
                async {
                    val lang = languages[i]
                    if (lang == null || lang !in usable) null else translateOffline(lang, texts[i])
                }
            }.awaitAll()
        }
    }

    /**
     * ML Kit rende meglio frase per frase e con le maiuscole normali: dividiamo il paragrafo
     * in frasi e normalizziamo il TESTO TUTTO MAIUSCOLO prima di tradurlo.
     */
    private suspend fun translateOffline(lang: String, text: String): String? {
        val cacheKey = key("offline:$lang", text)
        cache.get(cacheKey)?.let { return it }
        val style = CaseStyle.of(text)
        val sentences = SENTENCE_SPLIT.split(style.normalized).filter { it.isNotBlank() }
        val parts = ArrayList<String>(sentences.size)
        for (sentence in sentences) {
            val part = try {
                translator(lang).translate(sentence).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Traduzione non riuscita ($lang)", e)
                ready.remove(lang)
                downloadedCache = null
                return null
            }
            parts += part
        }
        val value = style.restore(parts.joinToString(" "))
        cache.put(cacheKey, value)
        return value
    }

    private suspend fun ensureReady(lang: String, allowMobileData: Boolean): Boolean {
        if (lang in ready) return true
        val downloaded = downloadedModels()
        val sourceOk = lang == TranslateLanguage.ENGLISH || lang in downloaded
        val targetOk = TranslateLanguage.ITALIAN in downloaded
        if (sourceOk && targetOk) {
            ready += lang
            return true
        }
        startDownload(lang, allowMobileData)
        return false
    }

    private suspend fun downloadedModels(): Set<String> {
        downloadedCache?.let { return it }
        return try {
            modelManager.getDownloadedModels(TranslateRemoteModel::class.java).await()
                .map { it.language }
                .toSet()
                .also { downloadedCache = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun startDownload(lang: String, allowMobileData: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if ((retryAfter[lang] ?: 0L) > now) return
        if (!downloading.add(lang)) return

        val name = Languages.displayName(lang)
        if (!allowMobileData && !isOnUnmeteredNetwork()) {
            downloading.remove(lang)
            retryAfter[lang] = now + RETRY_MS
            listener.onStatus(context.getString(R.string.status_need_wifi, name))
            return
        }

        listener.onStatus(context.getString(R.string.status_downloading, name))
        scope.launch {
            val conditions = if (allowMobileData) {
                DownloadConditions.Builder().build()
            } else {
                DownloadConditions.Builder().requireWifi().build()
            }
            try {
                translator(lang).downloadModelIfNeeded(conditions).await()
                downloadedCache = null
                ready += lang
                retryAfter.remove(lang)
                listener.onStatus(null)
                listener.onModelReady(lang)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download del modello $lang non riuscito", e)
                retryAfter[lang] = SystemClock.elapsedRealtime() + RETRY_MS
                listener.onStatus(context.getString(R.string.status_download_failed, name))
            } finally {
                downloading.remove(lang)
            }
        }
    }

    private fun translator(lang: String): Translator = translators.getOrPut(lang) {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(lang)
                .setTargetLanguage(TranslateLanguage.ITALIAN)
                .build(),
        )
    }

    // --- Lingua ---------------------------------------------------------------------------

    private suspend fun detect(texts: List<String>): List<String?> {
        // La lingua "dominante" dello schermo vale per i testi troppo brevi da riconoscere.
        val dominant = identify(texts.joinToString(" ").take(2000))
        return texts.map { text ->
            if (text.length >= 28) identify(text) ?: dominant else dominant
        }
    }

    private suspend fun identify(text: String): String? {
        val tag = try {
            identifier.identifyLanguage(CaseStyle.of(text).normalized).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        if (tag == LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG) return null
        return TranslateLanguage.fromLanguageTag(tag)
    }

    private fun isOnUnmeteredNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun key(engine: String, text: String) = "$engine\u0000$text"

    override fun close() {
        scope.cancel()
        translators.values.forEach { it.close() }
        translators.clear()
        identifier.close()
    }

    /** Gestisce i testi TUTTO MAIUSCOLO: si traducono in minuscolo e si rimettono in maiuscolo. */
    private class CaseStyle(val normalized: String, private val upper: Boolean) {
        fun restore(translated: String): String =
            if (upper) translated.uppercase(Locale.ITALIAN) else translated

        companion object {
            fun of(text: String): CaseStyle {
                val letters = text.filter { it.isLetter() }
                val upper = letters.length >= 2 && letters.any { it.isUpperCase() } && letters.none { it.isLowerCase() }
                if (!upper) return CaseStyle(text, false)
                val lower = text.lowercase(Locale.ROOT)
                return CaseStyle(lower.replaceFirstChar { it.titlecase(Locale.ROOT) }, true)
            }
        }
    }

    private companion object {
        const val TAG = "VeloTranslate"
        const val RETRY_MS = 30_000L
        val SENTENCE_SPLIT = Regex("""(?<=[.!?…])\s+(?=[\p{Lu}\d"“'(¿¡])""")
    }
}
