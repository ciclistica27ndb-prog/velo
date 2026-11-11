package it.velo.traduttore.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Una riga di testo così come appare sullo schermo. */
data class Line(val text: String, val rect: Rect)

/**
 * Un'unità di significato da tradurre (paragrafo, titolo, voce di menu, pulsante) e le righe
 * dello schermo che occupa. La traduzione verrà ridistribuita esattamente su queste righe.
 */
data class Segment(val text: String, val lines: List<Line>) {
    val bounds: Rect = Rect(lines.first().rect).also { r -> lines.forEach { r.union(it.rect) } }
    val lineHeight: Float = lines.map { it.rect.height() }.sorted()[lines.size / 2].toFloat()
}

class TextExtractor : Closeable {

    private val clients = HashMap<Script, TextRecognizer>()

    suspend fun extract(bitmap: Bitmap, script: Script): List<Segment> {
        val result = client(script).process(InputImage.fromBitmap(bitmap, 0)).await()

        val paragraphs = ArrayList<List<Line>>()
        for (block in result.textBlocks) {
            val lines = block.lines.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val text = line.text.trim()
                if (text.isEmpty() || box.width() <= 0 || box.height() <= 0) null else Line(text, Rect(box))
            }.sortedBy { it.rect.top }
            if (lines.isNotEmpty()) paragraphs += splitBlock(lines, script)
        }

        return mergeParagraphs(paragraphs, script)
            .map { Segment(joinLines(it.map(Line::text), script), it) }
            .filter { worthTranslating(it.text) }
    }

    // --- Raggruppamento -------------------------------------------------------------------

    /**
     * ML Kit a volte mette nello stesso blocco cose diverse (voci di menu, titolo + testo).
     * Separiamo le righe quando cambiano dimensione, rientro o distanza.
     */
    private fun splitBlock(lines: List<Line>, script: Script): List<List<Line>> {
        if (lines.size == 1) return listOf(lines)

        // Elenco di voci brevi una sotto l'altra: ognuna è un elemento a sé.
        val avgLength = lines.sumOf { it.text.length }.toDouble() / lines.size
        val shortLimit = if (script.joinsWithoutSpaces) 7 else 18
        val endsLikeSentence = lines.dropLast(1).any { it.text.last() in ".!?;:" }
        if (avgLength < shortLimit && !endsLikeSentence) return lines.map { listOf(it) }

        val out = ArrayList<List<Line>>()
        var current = arrayListOf(lines.first())
        for (i in 1 until lines.size) {
            val prev = lines[i - 1]
            val line = lines[i]
            if (continuesParagraph(prev, line)) {
                current += line
            } else {
                out += current
                current = arrayListOf(line)
            }
        }
        out += current
        return out
    }

    private fun continuesParagraph(prev: Line, next: Line): Boolean {
        val h = max(prev.rect.height(), next.rect.height()).toFloat()
        val sizeRatio = next.rect.height().toFloat() / prev.rect.height()
        if (sizeRatio < 0.78f || sizeRatio > 1.28f) return false
        val gap = next.rect.top - prev.rect.bottom
        if (gap > h * 1.1f || gap < -h * 0.5f) return false
        val sameLeft = abs(next.rect.left - prev.rect.left) < h * 1.2f
        val sameCenter = abs(next.rect.centerX() - prev.rect.centerX()) < h * 0.8f
        val sameRight = abs(next.rect.right - prev.rect.right) < h * 0.8f
        return sameLeft || sameCenter || sameRight
    }

    /** Ricuce paragrafi che ML Kit ha spezzato in blocchi diversi. */
    private fun mergeParagraphs(input: List<List<Line>>, script: Script): List<List<Line>> {
        val sorted = input.sortedWith(compareBy({ it.first().rect.top }, { it.first().rect.left }))
            .map { ArrayList(it) }
            .toMutableList()
        var i = 0
        while (i < sorted.size) {
            val a = sorted[i]
            var merged = false
            for (j in i + 1 until sorted.size) {
                val b = sorted[j]
                if (shouldMerge(a, b, script)) {
                    a += b
                    sorted.removeAt(j)
                    merged = true
                    break
                }
            }
            if (!merged) i++
        }
        return sorted
    }

    private fun shouldMerge(a: List<Line>, b: List<Line>, script: Script): Boolean {
        val last = a.last()
        val first = b.first()
        if (last.text.last() in ".!?:") return false
        if (a.size == 1 && b.size == 1 && last.text.length < 18 && !script.joinsWithoutSpaces) return false
        if (!continuesParagraph(last, first)) return false
        // Devono sovrapporsi in orizzontale (stessa colonna).
        val overlap = min(last.rect.right, first.rect.right) - max(last.rect.left, first.rect.left)
        return overlap > min(last.rect.width(), first.rect.width()) * 0.5f
    }

    /** Ricompone un paragrafo spezzato su più righe, gestendo le parole sillabate. */
    private fun joinLines(lines: List<String>, script: Script): String {
        if (script.joinsWithoutSpaces) return lines.joinToString("")
        val sb = StringBuilder()
        for (line in lines) {
            if (sb.isEmpty()) {
                sb.append(line)
                continue
            }
            val hyphenated = sb.endsWith("-") && sb.length > 1 && sb[sb.length - 2].isLetter() &&
                line.firstOrNull()?.isLowerCase() == true
            if (hyphenated) {
                sb.setLength(sb.length - 1)
                sb.append(line)
            } else {
                sb.append(' ').append(line)
            }
        }
        return sb.toString()
    }

    // --- Filtri ---------------------------------------------------------------------------

    private fun worthTranslating(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        if (letters < 2) return false
        val compact = text.trim()
        if (URL.matches(compact) || EMAIL.matches(compact)) return false
        if (HANDLE.matches(compact)) return false
        // Prevalentemente numeri, orari, prezzi, codici.
        if (compact.length > 4 && letters.toFloat() / compact.length < 0.3f) return false
        return true
    }

    private fun client(script: Script): TextRecognizer = clients.getOrPut(script) {
        when (script) {
            Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            Script.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        }
    }

    override fun close() {
        clients.values.forEach { it.close() }
        clients.clear()
    }

    private companion object {
        val URL = Regex("""^(https?://|www\.)\S+$|^[\w-]+(\.[\w-]+)+(/\S*)?$""", RegexOption.IGNORE_CASE)
        val EMAIL = Regex("""^[\w.+-]+@[\w-]+(\.[\w-]+)+$""")
        val HANDLE = Regex("""^[@#][\w.]+$""")
    }
}
