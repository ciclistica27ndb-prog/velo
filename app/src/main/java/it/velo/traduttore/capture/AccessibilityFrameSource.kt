package it.velo.traduttore.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import it.velo.traduttore.pipeline.FrameSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Cattura lo schermo con lo screenshot del servizio di Accessibilità (Android 11+).
 * Nessuna conferma di condivisione a ogni avvio; Android limita però la frequenza degli scatti.
 */
@RequiresApi(Build.VERSION_CODES.R)
class AccessibilityFrameSource(private val service: AccessibilityService) : FrameSource {

    override var width = 0
        private set
    override var height = 0
        private set
    override var frame: Bitmap? = null
        private set
    override val continuous = false

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "velo-shot") }
    private var lastShot = 0L

    override suspend fun capture(): Boolean {
        val wait = MIN_INTERVAL_MS - (SystemClock.uptimeMillis() - lastShot)
        if (wait > 0) delay(wait)
        lastShot = SystemClock.uptimeMillis()

        val shot = suspendCancellableCoroutine<Bitmap?> { cont ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        val software = try {
                            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                            copy
                        } catch (e: RuntimeException) {
                            Log.w(TAG, "Conversione screenshot non riuscita", e)
                            null
                        } finally {
                            buffer.close()
                        }
                        if (cont.isActive) cont.resume(software) else software?.recycle()
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.d(TAG, "Screenshot non riuscito: $errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        } ?: return false

        frame?.recycle()
        frame = shot
        width = shot.width
        height = shot.height
        return true
    }

    override fun snapshot(): Bitmap? {
        val source = frame ?: return null
        return source.copy(Bitmap.Config.ARGB_8888, false)
    }

    override fun onDisplayChanged() {
        // Ogni screenshot ha già le dimensioni correnti.
    }

    override fun release() {
        executor.shutdown()
        frame?.recycle()
        frame = null
    }

    private companion object {
        const val TAG = "VeloShot"
        /** Android rifiuta screenshot più ravvicinati di circa 1/3 di secondo. */
        const val MIN_INTERVAL_MS = 360L
    }
}
