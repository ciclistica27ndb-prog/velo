package it.velo.traduttore.overlay

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.DisplayMetrics
import android.util.TypedValue
import it.velo.traduttore.ocr.Line
import it.velo.traduttore.ocr.Segment
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Zona da coprire con lo sfondo originale. */
class Cover(val rect: RectF, val color: Int, val patch: Bitmap?)

/** Una riga di traduzione già posizionata (x e linea di base in coordinate schermo). */
class PlacedLine(val text: String, val x: Float, val baseline: Float)

class RenderedBlock(val covers: List<Cover>, val lines: List<PlacedLine>, val paint: TextPaint)

/**
 * Impagina una traduzione sulle righe del testo originale: stessa posizione, stessa linea di
 * base, stessa dimensione del carattere (ridotta solo se la traduzione è più lunga),
 * stesso allineamento, stesso colore e peso.
 */
class BlockLayout(metrics: DisplayMetrics) {

    private val minTextPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 8.5f, metrics)
    private val maxTextPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 60f, metrics)
    private val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    private enum class Align { LEFT, CENTER, RIGHT }

    /** Geometria tipografica stimata di una riga originale. */
    private class LineMetrics(val line: Line, val textSize: Float, val baseline: Float)

    fun layout(frame: Bitmap, segment: Segment, translation: String, screenWidth: Int): RenderedBlock {
        val metrics = segment.lines.map(::measure)
        val baseSize = metrics.map { it.textSize }.sorted()[metrics.size / 2]
        val align = detectAlign(segment, translation)

        // Aspetto del testo originale (colore, grassetto) rispetto allo sfondo della prima riga.
        val firstBg = ColorSampler.background(frame, padded(segment.lines.first().rect, segment.lineHeight).toRect())
        val look = ColorSampler.text(frame, segment.lines.map { it.rect }, firstBg.color)

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = if (look.bold) bold else regular
            color = look.color
        }

        val words = translation.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val maxWidth = segment.lines.maxOf { it.rect.width() }.toFloat()
        val single = segment.lines.size == 1
        val capacity = (if (single) maxWidth * 1.08f else maxWidth * 1.04f)
            .coerceAtMost(screenWidth - 8f)

        // Cerchiamo la dimensione più grande (fino a quella originale) che fa stare tutto nelle righe.
        var size = baseSize
        val floor = max(minTextPx, baseSize * 0.62f)
        var rows: List<String>? = null
        while (size >= floor) {
            paint.textSize = size
            rows = fill(words, paint, capacity, segment.lines.size, allowOverflow = false)
            if (rows != null) break
            size *= 0.94f
        }
        if (rows == null) {
            size = floor
            paint.textSize = size
            rows = fill(words, paint, capacity, Int.MAX_VALUE, allowOverflow = true)!!
        }

        // Posizionamento: le prime righe ricalcano quelle originali, le eventuali eccedenze
        // proseguono sotto con lo stesso passo.
        val pitch = if (metrics.size > 1) {
            (metrics.last().baseline - metrics.first().baseline) / (metrics.size - 1)
        } else {
            size * 1.3f
        }
        val placed = ArrayList<PlacedLine>(rows.size)
        val textRects = ArrayList<RectF>(rows.size)
        for ((i, row) in rows.withIndex()) {
            val ref = metrics[min(i, metrics.size - 1)]
            val baseline = if (i < metrics.size) ref.baseline else ref.baseline + pitch * (i - metrics.size + 1)
            val width = paint.measureText(row)
            val r = ref.line.rect
            var x = when (align) {
                Align.LEFT -> r.left.toFloat()
                Align.CENTER -> r.exactCenterX() - width / 2f
                Align.RIGHT -> r.right - width
            }
            x = x.coerceIn(2f, max(2f, screenWidth - width - 2f))
            placed += PlacedLine(row, x, baseline)
            textRects += RectF(x, baseline - size * 0.95f, x + width, baseline + size * 0.26f)
        }

        // Coperture: ogni riga originale (anche se la traduzione ne usa meno) più le righe extra.
        val covers = ArrayList<Cover>(max(rows.size, segment.lines.size))
        val count = max(rows.size, segment.lines.size)
        for (i in 0 until count) {
            val area = RectF()
            if (i < segment.lines.size) area.set(padded(segment.lines[i].rect, segment.lineHeight))
            if (i < textRects.size) {
                val t = textRects[i]
                val pad = size * 0.12f
                val textArea = RectF(t.left - pad, t.top, t.right + pad, t.bottom)
                if (area.isEmpty) area.set(textArea) else area.union(textArea)
            }
            val bg = ColorSampler.background(frame, area.toRect())
            covers += Cover(area, bg.color, bg.patch)
        }

        paint.textSize = size
        return RenderedBlock(covers, placed, paint)
    }

    /**
     * Distribuisce le parole sulle righe disponibili.
     * @return le righe, oppure null se non entrano in [maxRows] righe senza superare [capacity]
     */
    private fun fill(
        words: List<String>,
        paint: TextPaint,
        capacity: Float,
        maxRows: Int,
        allowOverflow: Boolean,
    ): List<String>? {
        val rows = ArrayList<String>()
        var current = StringBuilder()
        for (word in words) {
            if (current.isEmpty()) {
                if (paint.measureText(word) > capacity && !allowOverflow) return null
                current.append(word)
                continue
            }
            val candidate = "$current $word"
            if (paint.measureText(candidate) <= capacity) {
                current = StringBuilder(candidate)
            } else {
                rows += current.toString()
                if (rows.size >= maxRows) return null
                if (paint.measureText(word) > capacity && !allowOverflow) return null
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) rows += current.toString()
        if (rows.size > maxRows) return null
        return rows
    }

    /**
     * Stima dimensione del carattere e linea di base dalla scatola della riga: la scatola va
     * dalla cima delle lettere più alte al fondo delle discendenti, quindi dipende da quali
     * lettere contiene.
     */
    private fun measure(line: Line): LineMetrics {
        val text = line.text
        val h = line.rect.height().toFloat()
        val cjk = text.any { Character.isIdeographic(it.code) || it in '぀'..'ヿ' || it in '가'..'힯' }
        val top: Float
        val bottom: Float
        when {
            cjk -> {
                top = 0.88f
                bottom = 0.10f
            }
            else -> {
                val tall = text.any { it.isUpperCase() || it.isDigit() || it in ASCENDERS }
                val descends = text.any { it in DESCENDERS }
                top = if (tall) 0.72f else 0.53f
                bottom = if (descends) 0.22f else 0f
            }
        }
        val size = (h / (top + bottom)).coerceIn(minTextPx, maxTextPx)
        return LineMetrics(line, size, line.rect.bottom - bottom * size)
    }

    private fun detectAlign(segment: Segment, translation: String): Align {
        val lines = segment.lines
        val tolerance = segment.lineHeight * 0.6f
        if (lines.size >= 2) {
            val lefts = lines.map { it.rect.left }
            val centers = lines.map { it.rect.exactCenterX() }
            val rights = lines.map { it.rect.right }
            if (lefts.max() - lefts.min() <= tolerance) return Align.LEFT
            if (centers.max() - centers.min() <= tolerance) return Align.CENTER
            if (rights.max() - rights.min() <= tolerance) return Align.RIGHT
            return Align.LEFT
        }
        // Una sola riga: le etichette brevi (pulsanti, schede) sono di norma centrate.
        val wordsCount = maxOf(segment.text.split(WHITESPACE).size, translation.split(WHITESPACE).size)
        return if (wordsCount <= 3 && lines.first().text.length <= 22) Align.CENTER else Align.LEFT
    }

    private fun padded(rect: Rect, lineHeight: Float): RectF {
        val h = max(rect.height().toFloat(), lineHeight * 0.6f)
        val padH = max(2f, h * 0.14f)
        val padV = max(1.5f, h * 0.10f)
        return RectF(rect.left - padH, rect.top - padV, rect.right + padH, rect.bottom + padV)
    }

    private fun RectF.toRect() = Rect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val ASCENDERS = "bdfhklt"
        const val DESCENDERS = "gjpqy"
    }
}
