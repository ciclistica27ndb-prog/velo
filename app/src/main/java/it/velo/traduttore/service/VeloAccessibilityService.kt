package it.velo.traduttore.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import it.velo.traduttore.MainActivity
import it.velo.traduttore.Prefs
import it.velo.traduttore.VeloState
import it.velo.traduttore.capture.AccessibilityFrameSource
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
 * Modalità consigliata (Android 11+). Rispetto alla condivisione dello schermo:
 *  - le traduzioni sono completamente opache e i tocchi passano comunque all'app sotto;
 *  - non serve confermare la condivisione a ogni avvio;
 *  - Android avvisa quando l'utente scorre o cambia schermata, quindi le traduzioni spariscono
 *    subito e si consuma meno batteria.
 *
 * Velo non legge né conserva il contenuto delle finestre: usa solo gli eventi "qualcosa è
 * cambiato" e lo screenshot, elaborato sul telefono.
 */
class VeloAccessibilityService : AccessibilityService(), OverlayController.Callbacks, TranslationEngine.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: Prefs
    private lateinit var overlay: OverlayController
    private lateinit var extractor: TextExtractor
    private lateinit var engine: TranslationEngine
    private lateinit var pipeline: Pipeline
    private var ready = false

    val isTranslating: Boolean get() = ready && pipeline.isRunning

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        overlay = OverlayController(this, prefs, this, accessibilityOverlay = true)
        extractor = TextExtractor()
        engine = TranslationEngine(applicationContext, this)
        pipeline = Pipeline(this, scope, overlay, prefs, extractor, engine)
        ready = true
        instance = this
        // Dopo un aggiornamento dell'app o un riavvio, riprende da dov'era.
        if (prefs.accessibilityRunning) startTranslating()
    }

    fun startTranslating() {
        if (!ready || pipeline.isRunning) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // Una sola modalità alla volta.
        if (VeloState.mode.value == VeloState.Mode.PROJECTION && VeloState.phase.value != VeloState.Phase.OFF) {
            try {
                TranslateService.stop(this)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Impossibile fermare la modalità condivisione", e)
            }
        }
        try {
            overlay.attach()
        } catch (e: RuntimeException) {
            Log.e(TAG, "Impossibile mostrare la sovrapposizione", e)
            return
        }
        overlay.setPaused(false)
        pipeline.start(AccessibilityFrameSource(this))
        prefs.accessibilityRunning = true
        VeloState.setMode(VeloState.Mode.ACCESSIBILITY)
        VeloState.setPhase(VeloState.Phase.ACTIVE)
    }

    fun stopTranslating() {
        if (!ready) return
        prefs.accessibilityRunning = false
        scope.launch {
            pipeline.stop()
            overlay.detach()
            VeloState.setPhase(VeloState.Phase.OFF)
            VeloState.setStatus(null)
        }
    }

    fun togglePause() {
        if (!isTranslating) return
        val paused = !pipeline.paused
        pipeline.setPaused(paused)
        overlay.setPaused(paused)
        VeloState.setPhase(if (paused) VeloState.Phase.PAUSED else VeloState.Phase.ACTIVE)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isTranslating) return
        if (event.packageName == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            -> pipeline.invalidateNow()
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> pipeline.markDirty()
        }
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isTranslating) pipeline.onDisplayChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance === this) instance = null
        if (!ready) return
        ready = false
        overlay.detach()
        scope.cancel()
        pipeline.dispose()
        extractor.close()
        engine.close()
        if (VeloState.mode.value == VeloState.Mode.ACCESSIBILITY) {
            VeloState.setPhase(VeloState.Phase.OFF)
            VeloState.setStatus(null)
        }
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
        scope.launch { pipeline.requestRefresh() }
    }

    override fun onStatus(message: String?) {
        scope.launch {
            VeloState.setStatus(message)
            overlay.setStatus(message)
        }
    }

    companion object {
        private const val TAG = "VeloA11y"

        @Volatile
        var instance: VeloAccessibilityService? = null
            private set

        /** true se l'utente ha attivato Velo nelle impostazioni di Accessibilità. */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, VeloAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
