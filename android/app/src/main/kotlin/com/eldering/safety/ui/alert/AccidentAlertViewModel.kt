package com.eldering.safety.ui.alert

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eldering.safety.R
import com.eldering.safety.alert.AlertActions
import com.eldering.safety.core.TimeSource
import com.eldering.safety.ui.common.UiText
import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccidentAlertUiState(
    val info: AccidentAlertInfo,
    val phase: AlertPhase,
    val secondsLeft: Int,
    val deliveries: List<DeliveryLine>,
    val feedback: FeedbackChoice? = null,
)

/**
 * State of the accident countdown screen. It never sends anything itself: every decision goes to [actions]
 * (implemented by the alert pipeline).
 *
 * The on-screen countdown is derived from [AccidentAlertInfo.countdownEndsAtMs] and the clock, so it stays right
 * after rotation or when the screen is reopened. When it reaches zero the screen switches to "sent" and calls
 * [AlertActions.sendNow] once, as a backstop to the pipeline's own timer (implementations are idempotent).
 *
 * @param deliveries live "who was alerted" lines from the pipeline; without them the screen shows each planned
 *   recipient as "Sending…".
 * @param externalPhase phase changes decided elsewhere (e.g. the pipeline sent the alert, or "I'm OK" was tapped
 *   on the notification).
 */
class AccidentAlertViewModel(
    info: AccidentAlertInfo,
    private val actions: AlertActions,
    private val feedbackSink: AlertFeedbackSink? = null,
    deliveries: Flow<List<DeliveryLine>>? = null,
    externalPhase: Flow<AlertPhase>? = null,
    private val time: TimeSource = TimeSource.System,
    private val tickMs: Long = 200L,
) : ViewModel() {

    private val state = MutableStateFlow(
        AccidentAlertUiState(
            info = info,
            phase = if (info.countdownEndsAtMs == null) AlertPhase.SENT else AlertPhase.COUNTING,
            secondsLeft = secondsLeft(info.countdownEndsAtMs),
            deliveries = plannedDeliveries(info),
        ),
    )
    val uiState: StateFlow<AccidentAlertUiState> = state.asStateFlow()

    private var sendRequested = false

    init {
        if (state.value.phase == AlertPhase.COUNTING) {
            viewModelScope.launch { runCountdown() }
        }
        deliveries?.let { flow -> viewModelScope.launch { flow.collect { lines -> state.update { it.copy(deliveries = lines) } } } }
        externalPhase?.let { flow -> viewModelScope.launch { flow.collect(::onExternalPhase) } }
    }

    /** "I'm OK" (counting) or "I'm safe now — tell family" (sent). */
    fun imOk() {
        if (state.value.phase == AlertPhase.CANCELLED) return
        actions.imOk()
        state.update { it.copy(phase = AlertPhase.CANCELLED) }
    }

    fun sendNow() {
        if (state.value.phase != AlertPhase.COUNTING) return
        requestSend()
    }

    fun callEmergency() = actions.callEmergency()

    fun giveFeedback(choice: FeedbackChoice) {
        if (state.value.feedback == choice) return
        state.update { it.copy(feedback = choice) }
        val sink = feedbackSink ?: return
        viewModelScope.launch { sink.record(choice) }
    }

    private suspend fun runCountdown() {
        while (state.value.phase == AlertPhase.COUNTING) {
            val left = secondsLeft(state.value.info.countdownEndsAtMs)
            if (left <= 0) {
                requestSend()
                return
            }
            state.update { it.copy(secondsLeft = left) }
            delay(tickMs)
        }
    }

    private fun requestSend() {
        state.update { it.copy(phase = AlertPhase.SENT, secondsLeft = 0) }
        if (!sendRequested) {
            sendRequested = true
            actions.sendNow()
        }
    }

    private fun onExternalPhase(phase: AlertPhase) {
        val current = state.value.phase
        when {
            phase == current -> Unit
            // The pipeline sent it (its timer fired first): no need to ask again.
            phase == AlertPhase.SENT && current == AlertPhase.COUNTING -> {
                sendRequested = true
                state.update { it.copy(phase = AlertPhase.SENT, secondsLeft = 0) }
            }
            phase == AlertPhase.CANCELLED -> state.update { it.copy(phase = AlertPhase.CANCELLED) }
            else -> Unit // never go back to counting
        }
    }

    private fun secondsLeft(endsAtMs: Long?): Int =
        if (endsAtMs == null) 0 else ceil((endsAtMs - time.nowMillis()) / 1000.0).toInt().coerceAtLeast(0)

    companion object {
        /** Before the pipeline reports anything: one line per planned recipient, all "Sending…". */
        fun plannedDeliveries(info: AccidentAlertInfo): List<DeliveryLine> = buildList {
            info.familyRecipients.forEach { add(DeliveryLine(UiText.res(R.string.alert_delivery_app, it), DeliveryState.SENDING)) }
            if (info.familyRecipients.isEmpty()) add(DeliveryLine(UiText.res(R.string.alert_delivery_family_app), DeliveryState.SENDING))
            if (info.emergencyContactCount > 0) {
                add(
                    DeliveryLine(
                        UiText.res(R.string.alert_delivery_sms, UiText.Plural(R.plurals.alert_emergency_contacts, info.emergencyContactCount)),
                        DeliveryState.SENDING,
                    ),
                )
            }
        }

        fun factory(
            info: AccidentAlertInfo,
            actions: AlertActions,
            feedbackSink: AlertFeedbackSink? = null,
            deliveries: Flow<List<DeliveryLine>>? = null,
            externalPhase: Flow<AlertPhase>? = null,
            time: TimeSource = TimeSource.System,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { AccidentAlertViewModel(info, actions, feedbackSink, deliveries, externalPhase, time) }
        }
    }
}
