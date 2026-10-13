package com.eldering.safety.alert

import com.eldering.safety.R
import com.eldering.safety.data.repository.IncidentRepository
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
        familyRecipients = emptyList(),
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

    /** "Who was alerted" lines for [state]. */
    fun deliveries(state: AlertState): List<DeliveryLine> {
        val alert = state.alert ?: return emptyList()
        val contacts = alert.emergencyContactCount
        return when (state) {
            is AlertState.Counting, AlertState.Idle -> AccidentAlertViewModel.plannedDeliveries(info(alert, state))
            is AlertState.Sending -> buildList {
                add(DeliveryLine(UiText.res(R.string.alert_delivery_family_app), DeliveryState.SENDING))
                if (contacts > 0) add(DeliveryLine(UiText.res(R.string.alert_delivery_contacts_sms), DeliveryState.SENDING))
            }
            is AlertState.Sent -> lines(state.result)
            is AlertState.Finished -> state.result?.let(::lines) ?: AccidentAlertViewModel.plannedDeliveries(info(alert, state))
        }
    }

    private fun lines(result: DispatchResult): List<DeliveryLine> = buildList {
        val app = when (result.report) {
            is IncidentRepository.ReportResult.Sent -> DeliveryState.DELIVERED
            is IncidentRepository.ReportResult.Queued -> DeliveryState.SENDING
            is IncidentRepository.ReportResult.Rejected -> DeliveryState.FAILED
            IncidentRepository.ReportResult.NotSignedIn -> DeliveryState.NOT_SENT
        }
        add(DeliveryLine(UiText.res(R.string.alert_delivery_family_app), app))
        val sms = when (result.sms) {
            SmsOutcome.SENT -> DeliveryState.SENT
            SmsOutcome.DRY_RUN -> DeliveryState.DRY_RUN
            SmsOutcome.FAILED -> DeliveryState.FAILED
            SmsOutcome.NO_PERMISSION, SmsOutcome.NOT_SUPPORTED -> DeliveryState.NOT_SENT
            SmsOutcome.NO_CONTACTS -> null
        }
        if (sms != null) add(DeliveryLine(UiText.res(R.string.alert_delivery_contacts_sms), sms))
    }
}
