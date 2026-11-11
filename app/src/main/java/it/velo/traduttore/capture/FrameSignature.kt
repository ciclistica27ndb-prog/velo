package it.velo.traduttore.capture

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs

/**
 * Impronta a bassa risoluzione di un fotogramma (griglia di luminosità), usata per capire
 * in pochi millisecondi se lo schermo è cambiato senza rifare l'OCR.
 */
class FrameSignature private constructor(
    private val values: IntArray,
    private val width: Int,
    private val height: Int,
) {

    /**
     * Frazione delle celle cambiate rispetto a [other].
     * @param skip celle da ignorare (es. zone con video o animazioni)
     * @param ignore area dello schermo da ignorare (la bolla di Velo)
     */
    fun diff(other: FrameSignature, skip: BooleanArray?, ignore: Rect?): Float {
        if (other.width != width || other.height != height) return 1f
        var counted = 0
        var changed = 0
        for (r in 0 until ROWS) {
            val y = cellY(r)
            for (c in 0 until COLS) {
                val i = r * COLS + c
                if (skip != null && skip[i]) continue
                if (ignore != null && ignore.contains(cellX(c), y)) continue
                counted++
                if (abs(values[i] - other.values[i]) > LUMA_DELTA) changed++
            }
        }
        return if (counted == 0) 0f else changed.toFloat() / counted
    }

    /** Segna in [into] (o in un nuovo array) le celle che differiscono da [other]. */
    fun changedCells(other: FrameSignature, into: BooleanArray?): BooleanArray {
        val out = into ?: BooleanArray(COLS * ROWS)
        if (other.width != width || other.height != height) return out
        for (i in values.indices) {
            if (abs(values[i] - other.values[i]) > LUMA_DELTA) out[i] = true
        }
        return out
    }

    private fun cellX(c: Int) = ((c * 2 + 1) * width) / (COLS * 2)
    private fun cellY(r: Int) = ((r * 2 + 1) * height) / (ROWS * 2)

    companion object {
        const val COLS = 48
        const val ROWS = 96
        private const val LUMA_DELTA = 22

        /** @param width larghezza utile (il bitmap può avere padding a destra) */
        fun of(bitmap: Bitmap, width: Int, height: Int): FrameSignature {
            val w = minOf(width, bitmap.width)
            val h = minOf(height, bitmap.height)
            val out = IntArray(COLS * ROWS)
            val row = IntArray(w)
            for (r in 0 until ROWS) {
                val y = ((r * 2 + 1) * h) / (ROWS * 2)
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                for (c in 0 until COLS) {
                    val p = row[((c * 2 + 1) * w) / (COLS * 2)]
                    val red = (p shr 16) and 0xFF
                    val green = (p shr 8) and 0xFF
                    val blue = p and 0xFF
                    out[r * COLS + c] = (red * 299 + green * 587 + blue * 114) / 1000
                }
            }
            return FrameSignature(out, w, h)
        }
    }
}
