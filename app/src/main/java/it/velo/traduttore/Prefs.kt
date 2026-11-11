package it.velo.traduttore

import android.content.Context
import it.velo.traduttore.ocr.Script

/** Motori di traduzione disponibili. */
enum class EngineKind { OFFLINE, DEEPL, CLAUDE }

/** Impostazioni di traduzione lette una volta per ogni schermata analizzata. */
data class TranslationSettings(
    val sourceLanguage: String,
    val engine: EngineKind,
    val deeplKey: String,
    val claudeKey: String,
    val allowMobileData: Boolean,
)

class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("velo", Context.MODE_PRIVATE)

    /** Codice lingua ML Kit (es. "en") oppure [AUTO]. */
    var sourceLanguage: String
        get() = sp.getString(KEY_SOURCE, AUTO) ?: AUTO
        set(value) = sp.edit().putString(KEY_SOURCE, value).apply()

    /** Alfabeto da leggere quando la lingua è in rilevamento automatico. */
    var autoScript: Script
        get() = Script.fromName(sp.getString(KEY_SCRIPT, null))
        set(value) = sp.edit().putString(KEY_SCRIPT, value.name).apply()

    var allowMobileData: Boolean
        get() = sp.getBoolean(KEY_MOBILE_DATA, false)
        set(value) = sp.edit().putBoolean(KEY_MOBILE_DATA, value).apply()

    var engine: EngineKind
        get() = EngineKind.entries.firstOrNull { it.name == sp.getString(KEY_ENGINE, null) } ?: EngineKind.OFFLINE
        set(value) = sp.edit().putString(KEY_ENGINE, value.name).apply()

    var deeplKey: String
        get() = sp.getString(KEY_DEEPL, "") ?: ""
        set(value) = sp.edit().putString(KEY_DEEPL, value.trim()).apply()

    var claudeKey: String
        get() = sp.getString(KEY_CLAUDE, "") ?: ""
        set(value) = sp.edit().putString(KEY_CLAUDE, value.trim()).apply()

    var bubbleOnRight: Boolean
        get() = sp.getBoolean(KEY_BUBBLE_RIGHT, true)
        set(value) = sp.edit().putBoolean(KEY_BUBBLE_RIGHT, value).apply()

    /** Posizione verticale della bolla, in frazione dell'altezza dello schermo. */
    var bubbleY: Float
        get() = sp.getFloat(KEY_BUBBLE_Y, 0.42f)
        set(value) = sp.edit().putFloat(KEY_BUBBLE_Y, value).apply()

    /** La traduzione via Accessibilità era attiva: riprende da sola dopo un aggiornamento. */
    var accessibilityRunning: Boolean
        get() = sp.getBoolean(KEY_A11Y_RUNNING, false)
        set(value) = sp.edit().putBoolean(KEY_A11Y_RUNNING, value).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong(KEY_UPDATE_CHECK, 0L)
        set(value) = sp.edit().putLong(KEY_UPDATE_CHECK, value).apply()

    /** L'alfabeto da usare davvero: se la lingua è fissata, deriva da quella. */
    fun effectiveScript(): Script {
        val source = sourceLanguage
        return if (source == AUTO) autoScript else Script.forLanguage(source)
    }

    fun snapshot() = TranslationSettings(
        sourceLanguage = sourceLanguage,
        engine = engine,
        deeplKey = deeplKey,
        claudeKey = claudeKey,
        allowMobileData = allowMobileData,
    )

    companion object {
        const val AUTO = "auto"
        private const val KEY_SOURCE = "source_language"
        private const val KEY_SCRIPT = "auto_script"
        private const val KEY_MOBILE_DATA = "allow_mobile_data"
        private const val KEY_ENGINE = "engine"
        private const val KEY_DEEPL = "deepl_key"
        private const val KEY_CLAUDE = "claude_key"
        private const val KEY_BUBBLE_RIGHT = "bubble_right"
        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val KEY_A11Y_RUNNING = "a11y_running"
        private const val KEY_UPDATE_CHECK = "last_update_check"
    }
}
