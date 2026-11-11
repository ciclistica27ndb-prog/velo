package it.velo.traduttore.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import it.velo.traduttore.pipeline.FrameSource
import java.nio.ByteBuffer

/**
 * Specchia lo schermo in un [ImageReader] e tiene sempre in memoria l'ultimo fotogramma.
 *
 * Non è thread-safe: va usato da un solo thread (il ciclo del servizio).
 */
class ScreenCapturer(
    private val context: Context,
    private val projection: MediaProjection,
    private val handler: Handler,
) : FrameSource {
    override var width = 0
        private set
    override var height = 0
        private set
    override val continuous = true
    private var densityDpi = 0

    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    /** Ultimo fotogramma. Può essere più largo di [width] per il padding delle righe. */
    override var frame: Bitmap? = null
        private set
    private var scratch: ByteBuffer? = null

    override suspend fun capture(): Boolean = poll()

    override fun onDisplayChanged() {
        ensureSize()
    }

    fun start() {
        readMetrics()
        val newReader = createReader()
        reader = newReader
        virtualDisplay = projection.createVirtualDisplay(
            "velo-capture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            newReader.surface,
            null,
            handler,
        )
    }

    /**
     * Da chiamare dopo una rotazione. Su Android 14+ una MediaProjection può creare un solo
     * VirtualDisplay, quindi lo ridimensioniamo invece di ricrearlo.
     * @return true se le dimensioni sono cambiate.
     */
    fun ensureSize(): Boolean {
        val metrics = ScreenMetrics.real(context)
        if (metrics.widthPixels == width && metrics.heightPixels == height) return false
        readMetrics()
        val old = reader
        val newReader = createReader()
        reader = newReader
        virtualDisplay?.resize(width, height, densityDpi)
        virtualDisplay?.setSurface(newReader.surface)
        old?.close()
        frame?.recycle()
        frame = null
        scratch = null
        return true
    }

    /**
     * Copia nel buffer interno il fotogramma più recente, se ne è arrivato uno nuovo.
     * Android invia fotogrammi solo quando lo schermo cambia: false = schermo invariato.
     */
    fun poll(): Boolean {
        val image = try {
            reader?.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return false
        try {
            copyToBitmap(image)
        } finally {
            image.close()
        }
        return true
    }

    /** Copia indipendente del fotogramma corrente, ritagliata alle dimensioni reali. */
    override fun snapshot(): Bitmap? {
        val source = frame ?: return null
        val w = minOf(width, source.width)
        val h = minOf(height, source.height)
        return Bitmap.createBitmap(source, 0, 0, w, h)
    }

    override fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        frame?.recycle()
        frame = null
        scratch = null
    }

    private fun readMetrics() {
        val metrics = ScreenMetrics.real(context)
        width = metrics.widthPixels
        height = metrics.heightPixels
        densityDpi = metrics.densityDpi
    }

    private fun createReader(): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

    private fun copyToBitmap(image: Image) {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val bufferWidth = rowStride / pixelStride
        val imageHeight = image.height

        var bitmap = frame
        if (bitmap == null || bitmap.width != bufferWidth || bitmap.height != imageHeight) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(bufferWidth, imageHeight, Bitmap.Config.ARGB_8888)
            frame = bitmap
        }

        val source = plane.buffer
        source.rewind()
        val needed = bitmap.byteCount
        if (source.remaining() >= needed) {
            bitmap.copyPixelsFromBuffer(source)
        } else {
            // Alcuni dispositivi omettono il padding dell'ultima riga: completiamo con zeri.
            val tmp: ByteBuffer = scratch?.takeIf { it.capacity() == needed }
                ?: ByteBuffer.allocateDirect(needed).also { scratch = it }
            tmp.clear()
            tmp.put(source)
            while (tmp.hasRemaining()) tmp.put(0.toByte())
            tmp.rewind()
            bitmap.copyPixelsFromBuffer(tmp)
        }
    }
}
