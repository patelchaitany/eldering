package com.eldering.safety.alert

import com.eldering.safety.R
import com.eldering.safety.data.repository.IncidentRepository
import com.eldering.safety.network.AckDto
import com.eldering.safety.network.AckState
import com.eldering.safety.service.toActivity
import com.eldering.safety.ui.alert.AccidentAlertInfo
import com.eldering.safety.ui.alert.AccidentAlertViewModel
import com.eldering.safety.ui.alert.AlertPhase
import com.eldering.safety.ui.alert.DeliveryLine
import com.eldering.safety.ui.alert.DeliveryState
import com.eldering.safety.ui.common.UiText

/** Maps [AlertController] state to what the countdown screen shows. */
object AlertPresentation {

    /** The screen's input for [alert] in [state] (no countdown once it is being sent). */
    fun info(alert: ActiveAlert, state: AlertState, eventId: Long? = null): AccidentAlertInfo = AccidentAlertInfo(
        kind = alert.kind,
        detectedAtMs = alert.detectedAtMs,
        countdownEndsAtMs = if (state is AlertState.Counting) alert.countdownEndsAtMs else null,
        activity = alert.mode?.toActivity(),
        familyRecipients = alert.familyRecipients.map { it.name },
        emergencyContactCount = alert.emergencyContactCount,
        detectionEventId = eventId,
    )

    /** The phase of [alertId] in [state], or null if [state] is about another alert (or none). */
    fun phase(state: AlertState, alertId: Long): AlertPhase? {
        if (state.alert?.id != alertId) return null
        return when (state) {
            is AlertState.Counting -> AlertPhase.COUNTING
            is AlertState.Sending -> if (state.resolveWhenSent) AlertPhase.CANCELLED else AlertPhase.SENT
            is AlertState.Sent -> AlertPhase.SENT
            is AlertState.Finished -> if (state.ending == Ending.ENDED) AlertPhase.SENT else AlertPhase.CANCELLED
            AlertState.Idle -> null
        }
    }

    /**
     * "Who was alerted" lines for [state]. [acks] (the owner's view of `GET /v1/incidents/{id}`, polled while the
     * screen is open) turn "Delivered" into "Seen" / "On the way" per family member: delivered only means the
     * server has the alert, not that anybody looked at it.
     */
    fun deliveries(state: AlertState, acks: List<AckDto> = emptyList()): List<DeliveryLine> {
        val alert = state.alert ?: return emptyList()
        val contacts = alert.emergencyContactCount
        return when (state) {
            is AlertState.Counting, AlertState.Idle -> AccidentAlertViewModel.plannedDeliveries(info(alert, state))
            is AlertState.Sending -> buildList {
                addAll(familyLines(alert, DeliveryState.SENDING, emptyList()))
                if (contacts > 0) add(DeliveryLine(UiText.res(R.string.alert_delivery_contacts_sms), DeliveryState.SENDING))
            }
            is AlertState.Sent -> lines(alert, state.result, acks)
            is AlertState.Finished -> state.result?.let { lines(alert, it, acks) } ?: AccidentAlertViewModel.plannedDeliveries(info(alert, state))
        }
    }

    /**
     * One line for the family: "Mom, Dad · app" (or "Family · app" when the names are unknown). Once anybody has
     * acknowledged, one line per person, so it is clear who has seen it.
     */
    private fun familyLines(alert: ActiveAlert, app: DeliveryState, acks: List<AckDto>): List<DeliveryLine> {
        val names = alert.familyRecipients
        if (acks.isEmpty() || app != DeliveryState.DELIVERED) {
            val who = if (names.isEmpty()) UiText.res(R.string.alert_delivery_family_app) else UiText.res(R.string.alert_delivery_app, names.joinToString(", ") { it.name })
            return listOf(DeliveryLine(who, app))
        }
        val byUser = acks.associateBy { it.userId }
        val people = names.map { it.userId to it.name } +
            acks.filter { ack -> names.none { it.userId == ack.userId } }.map { it.userId to it.displayName.ifBlank { "?" } }
        return people.map { (userId, name) ->
            val state = when (byUser[userId]?.state) {
                AckState.ON_MY_WAY -> DeliveryState.ON_THE_WAY
                AckState.SEEN -> DeliveryState.SEEN
                null -> DeliveryState.DELIVERED
            }
            DeliveryLine(UiText.res(R.string.alert_delivery_app, name), state)
        }
    }

    private fun lines(alert: ActiveAlert, result: DispatchResult, acks: List<AckDto>): List<DeliveryLine> = buildList {
        val app = when (result.report) {
            is IncidentRepository.ReportResult.Sent -> DeliveryState.DELIVERED
            is IncidentRepository.ReportResult.Queued -> DeliveryState.SENDING
            is IncidentRepository.ReportResult.Rejected -> DeliveryState.FAILED
            IncidentRepository.ReportResult.NotSignedIn -> DeliveryState.NOT_SENT
        }
        addAll(familyLines(alert, app, acks))
        val sms = when (result.sms) {
            SmsOutcome.SENT -> DeliveryState.SENT
            SmsOutcome.DRY_RUN -> DeliveryState.DRY_RUN
            SmsOutcome.FAILED -> DeliveryState.FAILED
            SmsOutcome.NO_PERMISSION, SmsOutcome.NOT_SUPPORTED, SmsOutcome.CANCELLED -> DeliveryState.NOT_SENT
            SmsOutcome.NO_CONTACTS -> null
        }
        if (sms != null) add(DeliveryLine(UiText.res(R.string.alert_delivery_contacts_sms), sms))
    }
}

/**
 * When opening the app should bring the alert screen to the front (`MainActivity.onStart`): always while the
 * countdown runs (the user must be able to say "I'm OK"), and once per alert while it is being sent or after it was
 * sent (so "I'm safe now" is reachable without trapping the user on that screen every time they open the app).
 */
object AlertAutoOpen {
    @Volatile
    private var lastOpenedAlertId: Long? = null

    fun shouldOpen(state: AlertState): Boolean {
        val alert = state.alert ?: return false
        val open = when (state) {
            is AlertState.Counting -> true
            // An SOS is already on screen: it was raised from the app's own SOS screen.
            is AlertState.Sending, is AlertState.Sent -> !alert.isSos && alert.id != lastOpenedAlertId
            AlertState.Idle, is AlertState.Finished -> false
        }
        if (open) lastOpenedAlertId = alert.id
        return open
    }
}
