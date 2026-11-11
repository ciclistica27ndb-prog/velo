package it.velo.traduttore.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Motore di traduzione su internet. Lancia un'eccezione se non riesce: si ripiega sull'offline. */
interface OnlineTranslator {
    val name: String
    suspend fun translate(texts: List<String>): List<String>
}

/** DeepL API (chiave gratuita fino a 500.000 caratteri al mese). */
class DeepLTranslator(private val key: String) : OnlineTranslator {

    override val name = "DeepL"

    override suspend fun translate(texts: List<String>): List<String> = withContext(Dispatchers.IO) {
        val host = if (key.trim().endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        val out = ArrayList<String>(texts.size)
        for (chunk in texts.chunked(50)) {
            val body = JSONObject()
                .put("text", JSONArray(chunk))
                .put("target_lang", "IT")
                .put("preserve_formatting", true)
                .put("context", "Testi dell'interfaccia di un'app o di una pagina web visualizzata su uno smartphone.")
            val response = Http.postJson(
                "https://$host/v2/translate",
                body.toString(),
                mapOf("Authorization" to "DeepL-Auth-Key ${key.trim()}"),
            )
            val arr = JSONObject(response).getJSONArray("translations")
            if (arr.length() != chunk.size) throw IOException("DeepL: risposta incompleta")
            for (i in 0 until arr.length()) out += arr.getJSONObject(i).getString("text")
        }
        out
    }
}

/**
 * Claude (API Anthropic): riceve tutti i testi della schermata insieme, quindi traduce tenendo
 * conto del contesto e con terminologia coerente.
 */
class ClaudeTranslator(private val key: String) : OnlineTranslator {

    override val name = "Claude"

    override suspend fun translate(texts: List<String>): List<String> = withContext(Dispatchers.IO) {
        val out = ArrayList<String>(texts.size)
        for (chunk in texts.chunked(80)) out += translateChunk(chunk)
        out
    }

    private fun translateChunk(texts: List<String>): List<String> {
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 4096)
            .put("system", SYSTEM)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", JSONArray(texts).toString()),
                ),
            )
        val response = Http.postJson(
            "https://api.anthropic.com/v1/messages",
            body.toString(),
            mapOf("x-api-key" to key.trim(), "anthropic-version" to "2023-06-01"),
            readTimeoutMs = 45_000,
        )
        val content = JSONObject(response).getJSONArray("content")
        val text = buildString {
            for (i in 0 until content.length()) {
                val part = content.getJSONObject(i)
                if (part.optString("type") == "text") append(part.optString("text"))
            }
        }
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) throw IOException("Claude: risposta non valida")
        val arr = JSONArray(text.substring(start, end + 1))
        if (arr.length() != texts.size) throw IOException("Claude: numero di traduzioni errato")
        return List(arr.length()) { i -> arr.optString(i, texts[i]) }
    }

    private companion object {
        const val MODEL = "claude-haiku-5-5"
        const val SYSTEM =
            "Sei un traduttore professionista verso l'italiano. Ricevi un array JSON di testi letti " +
                "con OCR dallo schermo di uno smartphone, nell'ordine in cui compaiono: insieme formano " +
                "un'unica schermata, usala come contesto. Traduci ogni elemento in italiano naturale e " +
                "idiomatico. Regole: 1) la traduzione verrà disegnata al posto dell'originale, quindi " +
                "mantienila di lunghezza simile e, per pulsanti e menu, usa i termini standard delle app " +
                "italiane; 2) conserva maiuscole, punteggiatura e numeri dell'originale; 3) non tradurre " +
                "nomi propri, marchi, nomi utente, URL, codici; 4) correggi in silenzio gli evidenti errori " +
                "di OCR; 5) se un elemento è già in italiano o non va tradotto, restituiscilo identico. " +
                "Rispondi SOLO con un array JSON di stringhe, con esattamente lo stesso numero di elementi, " +
                "senza alcun altro testo."
    }
}
