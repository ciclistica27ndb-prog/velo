package it.velo.traduttore

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stato condiviso tra i servizi e la schermata principale (stesso processo). */
object VeloState {

    enum class Phase { OFF, STARTING, ACTIVE, PAUSED }

    /** Come viene letto lo schermo. */
    enum class Mode { ACCESSIBILITY, PROJECTION }

    private val _phase = MutableStateFlow(Phase.OFF)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _mode = MutableStateFlow(Mode.PROJECTION)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    /** Messaggio temporaneo (es. download di un pacchetto lingua). */
    val status: StateFlow<String?> = _status.asStateFlow()

    internal fun setPhase(value: Phase) {
        _phase.value = value
    }

    internal fun setMode(value: Mode) {
        _mode.value = value
    }

    internal fun setStatus(value: String?) {
        _status.value = value
    }
}
