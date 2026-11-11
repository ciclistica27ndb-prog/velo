package it.velo.traduttore

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

object Languages {

    /**
     * Lingue che ML Kit sa tradurre ma il cui alfabeto non è leggibile dal riconoscimento
     * del testo sul dispositivo (cirillico, arabo, greco, ebraico, thai, ecc.).
     */
    private val UNREADABLE_SCRIPTS = setOf(
        "ar", "be", "bg", "bn", "el", "fa", "gu", "he", "ka", "kn",
        "mk", "ru", "ta", "te", "th", "uk", "ur",
    )

    /** Lingue di origine selezionabili, ordinate per nome italiano. */
    fun sourceOptions(): List<String> =
        TranslateLanguage.getAllLanguages()
            .filter { it != TranslateLanguage.ITALIAN && it !in UNREADABLE_SCRIPTS }
            .sortedBy { displayName(it) }

    fun displayName(code: String): String {
        val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ITALIAN)
        if (name.isNullOrBlank()) return code
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ITALIAN) else it.toString() }
    }
}
