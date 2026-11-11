package it.velo.traduttore.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import it.velo.traduttore.Prefs
import it.velo.traduttore.capture.FrameSignature
import it.velo.traduttore.ocr.TextExtractor
import it.velo.traduttore.overlay.BlockLayout
import it.velo.traduttore.overlay.OverlayController
import it.velo.traduttore.overlay.RenderedBlock
import it.velo.traduttore.translate.TranslationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Il cuore di Velo, comune alle due modalità (Accessibilità e MediaProjection):
 *  1. capisce quando il contenuto dello schermo è cambiato e si è fermato;
 *  2. legge il testo (OCR) da un fotogramma "pulito", cioè catturato senza traduzioni sopra;
 *  3. traduce e impagina ogni blocco sopra l'originale.
 *
 * I metodi pubblici vanno chiamati dal thread principale.
 */
class Pipeline(
    context: Context,
    private val scope: CoroutineScope,
    private val overlay: OverlayController,
    private val prefs: Prefs,
    private val extractor: TextExtractor,
    private val engine: TranslationEngine,
) {
    private val layout = BlockLayout(context.resources.displayMetrics)
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "velo-loop") }.asCoroutineDispatcher()

    private var job: Job? = null
    private var source: FrameSource? = null

    @Volatile var paused = false
        private set
    @Volatile private var invalidated = false
    @Volatile private var dirty = false
    @Volatile private var refreshRequested = false
    @Volatile private var displayChanged = false

    val isRunning: Boolean get() = job != null

    fun start(frameSource: FrameSource) {
        if (job != null) return
        source = frameSource
        paused = false
        job = scope.launch(dispatcher) { runLoop(frameSource) }
    }

    /** Ferma il ciclo e libera la fonte dei fotogrammi. */
    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        val s = source
        source = null
        if (s != null) withContext(dispatcher) { s.release() }
        overlay.clear()
    }

    /** Da chiamare alla distruzione definitiva (chiude il thread del ciclo). */
    fun dispose() {
        dispatcher.close()
    }

    fun setPaused(value: Boolean) {
        paused = value
        if (value) overlay.clear()
    }

    /** Lo schermo è sicuramente cambiato (scorrimento, cambio app): nascondi subito. */
    fun invalidateNow() {
        if (job == null || paused) return
        overlay.clear()
        invalidated = true
    }

    /** Lo schermo potrebbe essere cambiato: verificalo alla prossima occasione. */
    fun markDirty() {
        dirty = true
    }

    fun requestRefresh() {
        refreshRequested = true
    }

    fun onDisplayChanged() {
        displayChanged = true
    }

    // --- Ciclo ---------------------------------------------------------------------------

    private enum class Mode { SETTLING, SHOWING }

    private suspend fun runLoop(src: FrameSource) {
        var mode = Mode.SETTLING
        var previous: FrameSignature? = null   // ultimo fotogramma "pulito" mentre aspettiamo
        var baseline: FrameSignature? = null   // schermo con le traduzioni attuali
        var settleStart = SystemClock.uptimeMillis()
        var lastCheck = 0L
        var volatileCells: BooleanArray? = null // zone sempre in movimento (video, animazioni)
        var wasPaused = false

        fun resetToSettling() {
            mode = Mode.SETTLING
            previous = null
            baseline = null
            settleStart = SystemClock.uptimeMillis()
        }

        while (currentCoroutineContext().isActive) {
            val interval = when {
                mode == Mode.SETTLING -> if (src.continuous) 220L else 60L
                src.continuous -> 400L
                else -> 150L
            }
            delay(interval)

            if (paused) {
                wasPaused = true
                continue
            }
            if (wasPaused) {
                wasPaused = false
                volatileCells = null
                resetToSettling()
            }
            if (displayChanged) {
                displayChanged = false
                ui {
                    overlay.clear()
                    overlay.onScreenSizeChanged()
                }
                src.onDisplayChanged()
                volatileCells = null
                resetToSettling()
                continue
            }
            if (invalidated) {
                invalidated = false
                resetToSettling()
                delay(OVERLAY_HIDE_MS)
                continue
            }
            if (refreshRequested) {
                refreshRequested = false
                if (mode == Mode.SHOWING) {
                    ui { overlay.clear() }
                    resetToSettling()
                    delay(OVERLAY_HIDE_MS)
                    continue
                }
            }

            // Con l'Accessibilità ogni cattura è uno screenshot: mentre le traduzioni sono
            // visibili controlliamo solo se un evento lo suggerisce, o ogni tanto per sicurezza.
            if (mode == Mode.SHOWING && !src.continuous) {
                val due = dirty || SystemClock.uptimeMillis() - lastCheck > IDLE_CHECK_MS
                if (!due) continue
            }
            dirty = false

            val fresh = src.capture()
            if (!fresh && !src.continuous) continue
            lastCheck = SystemClock.uptimeMillis()
            val frame = src.frame ?: continue
            val ignore = overlay.bubbleBounds
            val sig = FrameSignature.of(frame, src.width, src.height)

            when (mode) {
                Mode.SHOWING -> {
                    val base = baseline
                    if (base == null) {
                        baseline = sig
                        continue
                    }
                    val d = sig.diff(base, volatileCells, ignore)
                    if (d > CHANGE_THRESHOLD) {
                        if (d > BIG_CHANGE_THRESHOLD) volatileCells = null
                        ui { overlay.clear() }
                        resetToSettling()
                        delay(OVERLAY_HIDE_MS)
                    }
                }

                Mode.SETTLING -> {
                    val prev = previous
                    if (prev == null) {
                        previous = sig
                        continue
                    }
                    val stable = sig.diff(prev, volatileCells, ignore) < STABLE_THRESHOLD
                    val timedOut = SystemClock.uptimeMillis() - settleStart > SETTLE_TIMEOUT_MS
                    if (!stable && !timedOut) {
                        previous = sig
                        continue
                    }
                    if (!stable) {
                        // Tempo scaduto. Se si muove gran parte dello schermo (scorrimento
                        // continuo) aspettiamo ancora; se si muove solo una zona (video,
                        // animazione) la ignoriamo d'ora in poi e traduciamo il resto.
                        if (sig.diff(prev, null, ignore) > MAX_VOLATILE_FRACTION) {
                            previous = sig
                            settleStart = SystemClock.uptimeMillis()
                            continue
                        }
                        val mask = sig.changedCells(prev, volatileCells)
                        volatileCells = if (mask.count { it } > mask.size * MAX_VOLATILE_FRACTION) null else mask
                    }
                    val done = try {
                        processFrame(src, sig, volatileCells, ignore)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Analisi dello schermo non riuscita", e)
                        true
                    }
                    if (done) {
                        mode = Mode.SHOWING
                        baseline = null
                        delay(OVERLAY_SHOW_MS)
                    } else {
                        resetToSettling()
                    }
                }
            }
        }
    }

    /**
     * Legge, traduce e mostra il testo del fotogramma corrente.
     * @return false se lo schermo è cambiato nel frattempo (i risultati sarebbero sfasati).
     */
    private suspend fun processFrame(
        src: FrameSource,
        signature: FrameSignature,
        volatileCells: BooleanArray?,
        ignore: Rect?,
    ): Boolean {
        val snapshot: Bitmap = src.snapshot() ?: return false
        ui { overlay.setWorking(true) }
        try {
            val segments = extractor.extract(snapshot, prefs.effectiveScript())
                .filter { seg -> ignore == null || !Rect.intersects(seg.bounds, ignore) }
            if (segments.isEmpty()) {
                ui { overlay.clear() }
                return true
            }

            val translations = engine.translate(segments.map { it.text }, prefs.snapshot())

            // Se lo schermo è cambiato durante l'elaborazione, scartiamo il risultato.
            if (src.continuous || dirty) {
                if (src.capture() || src.continuous) {
                    val now = src.frame?.let { FrameSignature.of(it, src.width, src.height) }
                    if (now != null && now.diff(signature, volatileCells, ignore) > CHANGE_THRESHOLD) return false
                }
            }
            if (paused || invalidated) return true

            val blocks = ArrayList<RenderedBlock>(segments.size)
            for ((segment, translated) in segments.zip(translations)) {
                val text = translated?.trim()
                if (text.isNullOrEmpty() || text.equals(segment.text.trim(), ignoreCase = true)) continue
                try {
                    blocks += layout.layout(snapshot, segment, text, src.width)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Impaginazione non riuscita", e)
                }
            }
            ui { overlay.show(blocks) }
            return true
        } finally {
            snapshot.recycle()
            ui { overlay.setWorking(false) }
        }
    }

    private suspend fun ui(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    private companion object {
        const val TAG = "VeloPipeline"

        const val OVERLAY_HIDE_MS = 120L
        const val OVERLAY_SHOW_MS = 200L
        const val SETTLE_TIMEOUT_MS = 2200L
        const val IDLE_CHECK_MS = 1500L

        /** Oltre questa frazione di celle cambiate lo schermo è considerato "cambiato". */
        const val CHANGE_THRESHOLD = 0.012f
        /** Sotto questa frazione due fotogrammi consecutivi sono considerati "fermi". */
        const val STABLE_THRESHOLD = 0.004f
        /** Cambio radicale (altra app, altra schermata): dimentica le zone in movimento. */
        const val BIG_CHANGE_THRESHOLD = 0.30f
        /** Oltre questa frazione le zone "in movimento" non sono un video ma uno scorrimento. */
        const val MAX_VOLATILE_FRACTION = 0.35f
    }
}
