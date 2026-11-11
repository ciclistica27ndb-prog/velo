package it.velo.traduttore.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import it.velo.traduttore.MainActivity
import it.velo.traduttore.Prefs
import it.velo.traduttore.R
import it.velo.traduttore.VeloApp
import it.velo.traduttore.VeloState
import it.velo.traduttore.capture.ScreenCapturer
import it.velo.traduttore.ocr.TextExtractor
import it.velo.traduttore.overlay.OverlayController
import it.velo.traduttore.pipeline.Pipeline
import it.velo.traduttore.translate.TranslationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Modalità "condivisione schermo" (MediaProjection): funziona da Android 8 senza attivare
 * l'Accessibilità, ma Android impone una leggera trasparenza alle traduzioni e chiede il
 * consenso a ogni avvio.
 */
class TranslateService : Service(), OverlayController.Callbacks, TranslationEngine.Listener {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val captureThread = HandlerThread("velo-capture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)

    private lateinit var prefs: Prefs
    private lateinit var overlay: OverlayController
    private lateinit var extractor: TextExtractor
    private lateinit var engine: TranslationEngine
    private lateinit var pipeline: Pipeline

    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private var stopping = false

    private val paused: Boolean get() = pipeline.paused

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // L'utente ha interrotto la condivisione dalla barra di sistema.
            mainScope.launch { stopEverything() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        overlay = OverlayController(this, prefs, this)
        extractor = TextExtractor()
        engine = TranslationEngine(applicationContext, this)
        pipeline = Pipeline(this, mainScope, overlay, prefs, extractor, engine)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_TOGGLE_PAUSE -> togglePause()
            ACTION_STOP -> mainScope.launch { stopEverything() }
            else -> if (projection == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (projection != null) pipeline.onDisplayChanged()
    }

    // --- Avvio e arresto ------------------------------------------------------------------

    private fun handleStart(intent: Intent) {
        if (projection != null) return
        stopping = false
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)

        // Android 14+: il servizio deve essere in primo piano PRIMA di ottenere la MediaProjection.
        VeloState.setMode(VeloState.Mode.PROJECTION)
        VeloState.setPhase(VeloState.Phase.STARTING)
        try {
            startInForeground()
        } catch (e: RuntimeException) {
            Log.e(TAG, "Impossibile avviare il servizio in primo piano", e)
            VeloState.setPhase(VeloState.Phase.OFF)
            stopSelf()
            return
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            mainScope.launch { stopEverything() }
            return
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "MediaProjection non disponibile", e)
            null
        }
        if (proj == null) {
            mainScope.launch { stopEverything() }
            return
        }
        projection = proj
        proj.registerCallback(projectionCallback, captureHandler)

        try {
            overlay.attach()
        } catch (e: RuntimeException) {
            Log.e(TAG, "Impossibile mostrare la sovrapposizione", e)
            mainScope.launch { stopEverything() }
            return
        }

        val cap = ScreenCapturer(this, proj, captureHandler)
        try {
            cap.start()
        } catch (e: Exception) {
            Log.e(TAG, "Impossibile avviare la cattura", e)
            mainScope.launch { stopEverything() }
            return
        }
        capturer = cap

        overlay.setPaused(false)
        pipeline.start(cap)
        VeloState.setPhase(VeloState.Phase.ACTIVE)
        updateNotification()
    }

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private suspend fun stopEverything() {
        if (stopping) return
        stopping = true
        pipeline.stop() // rilascia anche il capturer
        capturer = null
        overlay.detach()
        projection?.let {
            try {
                it.unregisterCallback(projectionCallback)
                it.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Errore chiudendo la MediaProjection", e)
            }
        }
        projection = null
        if (VeloState.mode.value == VeloState.Mode.PROJECTION) {
            VeloState.setPhase(VeloState.Phase.OFF)
            VeloState.setStatus(null)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Pulizia sincrona nel caso il sistema chiuda il servizio senza passare da stopEverything.
        mainScope.cancel()
        overlay.detach()
        capturer?.release()
        capturer = null
        projection?.let {
            try {
                it.unregisterCallback(projectionCallback)
                it.stop()
            } catch (_: Exception) {
            }
        }
        projection = null
        pipeline.dispose()
        extractor.close()
        engine.close()
        captureThread.quitSafely()
        if (VeloState.mode.value == VeloState.Mode.PROJECTION) {
            VeloState.setPhase(VeloState.Phase.OFF)
            VeloState.setStatus(null)
        }
        super.onDestroy()
    }

    private fun togglePause() {
        if (projection == null) return
        val newValue = !paused
        pipeline.setPaused(newValue)
        overlay.setPaused(newValue)
        VeloState.setPhase(if (newValue) VeloState.Phase.PAUSED else VeloState.Phase.ACTIVE)
        updateNotification()
    }

    // --- Callback --------------------------------------------------------------------------

    override fun onBubbleTap() = togglePause()

    override fun onBubbleLongPress() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Impossibile aprire l'app", e)
        }
    }

    override fun onBubbleMoved() = Unit

    override fun onModelReady(language: String) {
        pipeline.requestRefresh()
    }

    override fun onStatus(message: String?) {
        mainScope.launch {
            VeloState.setStatus(message)
            overlay.setStatus(message)
            updateNotification()
        }
    }

    // --- Notifica --------------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), immutable,
        )
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, TranslateService::class.java).setAction(ACTION_TOGGLE_PAUSE), immutable,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, TranslateService::class.java).setAction(ACTION_STOP), immutable,
        )
        val isPaused = ::pipeline.isInitialized && pipeline.paused
        val status = VeloState.status.value
        return NotificationCompat.Builder(this, VeloApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_velo)
            .setContentTitle(getString(if (isPaused) R.string.notif_paused_title else R.string.notif_active_title))
            .setContentText(
                status ?: getString(if (isPaused) R.string.notif_paused_text else R.string.notif_active_text),
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0,
                getString(if (isPaused) R.string.notif_resume else R.string.notif_pause),
                toggle,
            )
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    private fun updateNotification() {
        if (projection == null || stopping) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (e: SecurityException) {
            // Notifiche non consentite: il servizio continua comunque.
        }
    }

    companion object {
        private const val TAG = "VeloService"

        const val ACTION_START = "it.velo.traduttore.action.START"
        const val ACTION_STOP = "it.velo.traduttore.action.STOP"
        const val ACTION_TOGGLE_PAUSE = "it.velo.traduttore.action.TOGGLE_PAUSE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val NOTIFICATION_ID = 42

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, TranslateService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TranslateService::class.java).setAction(ACTION_STOP))
        }
    }
}
