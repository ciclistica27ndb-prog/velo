package it.velo.traduttore.ocr

import androidx.annotation.StringRes
import it.velo.traduttore.R

/** Alfabeti leggibili dal riconoscimento del testo di ML Kit sul dispositivo. */
enum class Script(@StringRes val label: Int, val joinsWithoutSpaces: Boolean) {
    LATIN(R.string.script_latin, false),
    CHINESE(R.string.script_chinese, true),
    JAPANESE(R.string.script_japanese, true),
    KOREAN(R.string.script_korean, false),
    DEVANAGARI(R.string.script_devanagari, false);

    companion object {
        fun fromName(name: String?): Script =
            entries.firstOrNull { it.name == name } ?: LATIN

        fun forLanguage(code: String): Script = when (code) {
            "zh" -> CHINESE
            "ja" -> JAPANESE
            "ko" -> KOREAN
            "hi", "mr" -> DEVANAGARI
            else -> LATIN
        }
    }
}
