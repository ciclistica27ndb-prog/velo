package it.velo.traduttore.overlay

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min

/**
 * Legge dal fotogramma l'aspetto del testo originale:
 *  - lo sfondo attorno a ogni riga (tinta unita oppure una "toppa" che riproduce sfumature
 *    e immagini senza il testo);
 *  - il colore del testo e se è in grassetto.
 */
object ColorSampler {

    /** Sfondo di una riga: [patch] è null quando lo sfondo è in tinta unita. */
    class Background(val color: Int, val patch: Bitmap?)

    class TextLook(val color: Int, val bold: Boolean)

    private const val UNIFORM_DISTANCE = 28
    private const val UNIFORM_SHARE = 0.72f

    /**
     * @param area zona da coprire (già allargata rispetto al testo): i campioni vengono presi
     *             appena fuori da questa zona, dove non c'è testo.
     */
    fun background(bitmap: Bitmap, area: Rect): Background {
        val w = bitmap.width
        val h = bitmap.height
        val topY = (area.top - 2).coerceIn(0, h - 1)
        val bottomY = (area.bottom + 1).coerceIn(0, h - 1)
        val leftX = (area.left - 2).coerceIn(0, w - 1)
        val rightX = (area.right + 1).coerceIn(0, w - 1)

        val columns = (area.width() / 10).coerceIn(6, 48)
        val top = IntArray(columns)
        val bottom = IntArray(columns)
        for (j in 0 until columns) {
            val x = (area.left + (area.width() * (2 * j + 1)) / (2 * columns)).coerceIn(0, w - 1)
            top[j] = bitmap.getPixel(x, topY)
            bottom[j] = bitmap.getPixel(x, bottomY)
        }
        val sides = IntArray(8)
        for (k in 0 until 4) {
            val y = (area.top + (area.height() * (2 * k + 1)) / 8).coerceIn(0, h - 1)
            sides[k] = bitmap.getPixel(leftX, y)
            sides[4 + k] = bitmap.getPixel(rightX, y)
        }

        val all = top + bottom + sides
        val modal = modalColor(all)
        val close = all.count { distance(it, modal) < UNIFORM_DISTANCE * UNIFORM_DISTANCE }
        if (close >= all.size * UNIFORM_SHARE) return Background(modal, null)

        // Sfondo non uniforme (sfumatura, foto): toppa 2 righe x N colonne, interpolata.
        smooth(top)
        smooth(bottom)
        val patch = Bitmap.createBitmap(columns, 2, Bitmap.Config.ARGB_8888)
        for (j in 0 until columns) {
            patch.setPixel(j, 0, top[j] or OPAQUE)
            patch.setPixel(j, 1, bottom[j] or OPAQUE)
        }
        return Background(modal, patch)
    }

    /** Colore e peso del testo all'interno delle righe indicate. */
    fun text(bitmap: Bitmap, rects: List<Rect>, background: Int): TextLook {
        val w = bitmap.width
        val h = bitmap.height
        val samples = ArrayList<Int>(512)
        val perLine = max(60, 480 / max(1, rects.size))
        for (r in rects) {
            val rows = 5
            val cols = max(4, perLine / rows)
            for (i in 0 until rows) {
                val y = r.top + (r.height() * (2 * i + 1)) / (2 * rows)
                if (y !in 0 until h) continue
                for (j in 0 until cols) {
                    val x = r.left + (r.width() * (2 * j + 1)) / (2 * cols)
                    if (x !in 0 until w) continue
                    samples += bitmap.getPixel(x, y)
                }
            }
        }
        if (samples.isEmpty()) return TextLook(fallbackText(background), false)

        val distances = samples.map { distance(it, background) }
        val maxD = distances.max()
        if (maxD < 40 * 40) return TextLook(fallbackText(background), false)

        // Pixel di "inchiostro": abbastanza lontani dallo sfondo.
        val threshold = maxD / 4
        val ink = samples.indices.filter { distances[it] > threshold }
        val strongest = ink.sortedByDescending { distances[it] }.take(max(1, ink.size / 4))
        var r = 0
        var g = 0
        var b = 0
        for (i in strongest) {
            r += Color.red(samples[i])
            g += Color.green(samples[i])
            b += Color.blue(samples[i])
        }
        val n = strongest.size
        var color = Color.rgb(r / n, g / n, b / n)
        if (ColorUtils.calculateContrast(color, background or OPAQUE) < 3.0) color = fallbackText(background)

        // Il grassetto copre una quota maggiore del riquadro della riga.
        val inkShare = ink.size.toFloat() / samples.size
        return TextLook(color, inkShare > 0.30f)
    }

    private fun fallbackText(background: Int): Int =
        if (ColorUtils.calculateLuminance(background or OPAQUE) > 0.45) Color.rgb(17, 17, 17) else Color.rgb(245, 245, 245)

    /** Colore più frequente (istogramma a 5 bit per canale, poi media del gruppo vincente). */
    private fun modalColor(pixels: IntArray): Int {
        val counts = HashMap<Int, Int>()
        for (p in pixels) {
            val key = bucket(p)
            counts[key] = (counts[key] ?: 0) + 1
        }
        val best = counts.maxByOrNull { it.value }!!.key
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        for (p in pixels) {
            if (bucket(p) != best) continue
            r += Color.red(p)
            g += Color.green(p)
            b += Color.blue(p)
            n++
        }
        return Color.rgb(r / n, g / n, b / n)
    }

    private fun bucket(p: Int) = ((Color.red(p) shr 3) shl 10) or ((Color.green(p) shr 3) shl 5) or (Color.blue(p) shr 3)

    /** Mediana su finestra di 3: elimina i campioni "sporcati" da lettere vicine. */
    private fun smooth(values: IntArray) {
        if (values.size < 3) return
        val copy = values.copyOf()
        for (i in values.indices) {
            val a = copy[max(0, i - 1)]
            val b = copy[i]
            val c = copy[min(values.size - 1, i + 1)]
            values[i] = Color.rgb(
                median3(Color.red(a), Color.red(b), Color.red(c)),
                median3(Color.green(a), Color.green(b), Color.green(c)),
                median3(Color.blue(a), Color.blue(b), Color.blue(c)),
            )
        }
    }

    private fun median3(a: Int, b: Int, c: Int) = max(min(a, b), min(max(a, b), c))

    private fun distance(a: Int, b: Int): Int {
        val dr = Color.red(a) - Color.red(b)
        val dg = Color.green(a) - Color.green(b)
        val db = Color.blue(a) - Color.blue(b)
        return dr * dr + dg * dg + db * db
    }

    private const val OPAQUE = -0x1000000
}
