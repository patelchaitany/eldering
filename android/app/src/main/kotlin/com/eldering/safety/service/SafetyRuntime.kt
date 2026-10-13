package com.eldering.safety.service

import com.eldering.detection.Mode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What receivers and debug tools may ask of a running [SafetyService] (same process). */
interface SafetyControl {
    /** An Activity Recognition transition arrived. */
    fun onModeRecognized(mode: Mode)

    /** Debug builds: force a movement mode (null = back to automatic). */
    fun forceMode(mode: Mode?)

    /** Debug builds: replay a synthetic trace through the live detector. */
    fun inject(request: DetectionEngine.InjectRequest)

    /** One line per aspect of the service's state, for `DEBUG_STATUS`. */
    suspend fun statusLines(): List<String>

    /** The still-mode keep-alive alarm fired; call [done] when finished (the receiver holds `goAsync`). */
    fun onKeepAliveAlarm(done: () -> Unit)
}

/**
 * Process-wide handle on the running [SafetyService] (held by `AppGraph`, not a static field). [running] lets the
 * UI and the debug receiver wait for the service to come up.
 */
class SafetyRuntime {
    @Volatile
    var control: SafetyControl? = null
        private set

    private val runningState = MutableStateFlow(false)
    val running: StateFlow<Boolean> = runningState.asStateFlow()

    fun attach(control: SafetyControl) {
        this.control = control
        runningState.value = true
    }

    fun detach(control: SafetyControl) {
        if (this.control === control) {
            this.control = null
            runningState.value = false
        }
    }
}
