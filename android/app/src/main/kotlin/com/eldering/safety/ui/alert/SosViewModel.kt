package com.eldering.safety.ui.alert

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eldering.safety.R
import com.eldering.safety.alert.AlertActions
import com.eldering.safety.alert.SmsOutcome
import com.eldering.safety.alert.SosTrigger
import com.eldering.safety.data.db.OwnIncident
import com.eldering.safety.data.db.OwnIncidentStatus
import com.eldering.safety.data.repository.IncidentRepository
import com.eldering.safety.network.AckState
import com.eldering.safety.network.ApiResult
import com.eldering.safety.network.Resolution
import com.eldering.safety.ui.common.UiText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Hold-to-SOS: raises the SOS once through [SosTrigger] and reports who was alerted. Acts as the [AlertActions] of
 * the alert screen shown afterwards ("I'm safe now" resolves the incident; "Call 112" opens the dialer).
 *
 * The "already triggered" flag lives in [SavedStateHandle], so recreating the screen after process death never
 * raises a second SOS.
 */
class SosViewModel(
    private val savedState: SavedStateHandle,
    private val trigger: SosTrigger,
    private val incidents: IncidentRepository,
    private val dialEmergency: () -> Unit,
    /** Ends the alert pipeline's SOS too (AlertController.imOk: stops live updates and its notification). */
    private val endAlert: () -> Unit = {},
    private val pollIntervalMs: Long = 15_000L,
) : ViewModel(), AlertActions {

    private val lines = MutableStateFlow<List<DeliveryLine>>(listOf(familyLine(DeliveryState.SENDING)))

    /** "Who was alerted", for [AccidentAlertViewModel]. */
    val deliveries: StateFlow<List<DeliveryLine>> = lines.asStateFlow()

    private var incidentKey: String? = savedState[KEY_INCIDENT]
    private var sms: SmsOutcome? = savedState.get<String>(KEY_SMS)?.let { name -> SmsOutcome.entries.firstOrNull { it.name == name } }
    private var resolveWhenKnown = false
    private var acks: List<Pair<String, AckState>> = emptyList()

    init {
        if (savedState.get<Boolean>(KEY_TRIGGERED) != true) {
            savedState[KEY_TRIGGERED] = true
            viewModelScope.launch {
                val outcome = trigger.triggerSos()
                sms = outcome.sms
                savedState[KEY_SMS] = outcome.sms.name
                incidentKey = when (val r = outcome.report) {
                    is IncidentRepository.ReportResult.Sent -> r.incidentKey
                    is IncidentRepository.ReportResult.Queued -> r.incidentKey
                    is IncidentRepository.ReportResult.Rejected -> r.incidentKey
                    IncidentRepository.ReportResult.NotSignedIn -> null
                }
                savedState[KEY_INCIDENT] = incidentKey
                if (outcome.report == IncidentRepository.ReportResult.NotSignedIn) {
                    lines.value = buildLines(null)
                }
                if (resolveWhenKnown) imOk()
                observe()
            }
        } else {
            observe()
        }
    }

    /** Polls acknowledgements (owner sees everyone's) while the screen is visible. */
    suspend fun pollAcks() {
        while (true) {
            val serverId = incidentKey?.let { incidents.ownIncident(it)?.serverId }
            if (serverId != null) {
                val detail = incidents.detail(serverId)
                if (detail is ApiResult.Success) {
                    acks = detail.value.acks.map { it.displayName to it.state }
                    lines.value = buildLines(incidentKey?.let { incidents.ownIncident(it) })
                }
            }
            delay(pollIntervalMs)
        }
    }

    override fun imOk() {
        endAlert()
        val key = incidentKey
        if (key == null) {
            resolveWhenKnown = true
            return
        }
        viewModelScope.launch { incidents.resolveOwn(key, Resolution.USER_OK) }
    }

    /** Already sent: holding the SOS button was the confirmation. */
    override fun sendNow() = Unit

    override fun callEmergency() = dialEmergency()

    private fun observe() {
        val key = incidentKey ?: return
        viewModelScope.launch { incidents.observeOwn(key).collect { own -> lines.value = buildLines(own) } }
    }

    private fun buildLines(own: OwnIncident?): List<DeliveryLine> = buildList {
        val appState = when (own?.status) {
            null -> if (incidentKey == null) DeliveryState.NOT_SENT else DeliveryState.SENDING
            OwnIncidentStatus.QUEUED -> DeliveryState.SENDING
            OwnIncidentStatus.ACTIVE, OwnIncidentStatus.RESOLVED -> DeliveryState.DELIVERED
            OwnIncidentStatus.CANCELLED -> DeliveryState.NOT_SENT
            OwnIncidentStatus.FAILED -> DeliveryState.FAILED
        }
        if (acks.isEmpty()) {
            add(familyLine(appState))
        } else {
            acks.forEach { (name, ack) ->
                add(DeliveryLine(UiText.res(R.string.alert_delivery_app, name), if (ack == AckState.ON_MY_WAY) DeliveryState.ON_THE_WAY else DeliveryState.SEEN))
            }
        }
        when (sms) {
            SmsOutcome.SENT -> add(DeliveryLine(UiText.res(R.string.alert_delivery_contacts_sms), DeliveryState.SENT))
            SmsOutcome.DRY_RUN -> add(DeliveryLine(UiText.res(R.string.alert_delivery_contacts_sms), DeliveryState.DRY_RUN))
            SmsOutcome.FAILED -> add(DeliveryLine(UiText.res(R.string.alert_delivery_contacts_sms), DeliveryState.FAILED))
            SmsOutcome.NO_PERMISSION, SmsOutcome.NOT_SUPPORTED, SmsOutcome.CANCELLED -> add(DeliveryLine(UiText.res(R.string.alert_delivery_contacts_sms), DeliveryState.NOT_SENT))
            SmsOutcome.NO_CONTACTS, null -> Unit
        }
    }

    private fun familyLine(state: DeliveryState) = DeliveryLine(UiText.res(R.string.alert_delivery_family_app), state)

    /** The alert screen's input for an SOS that is already on its way. */
    fun alertInfo(nowMs: Long): AccidentAlertInfo = AccidentAlertInfo(
        kind = com.eldering.safety.network.IncidentKind.SOS,
        detectedAtMs = savedState.get<Long>(KEY_STARTED_AT) ?: nowMs.also { savedState[KEY_STARTED_AT] = it },
        countdownEndsAtMs = null,
    )

    private companion object {
        const val KEY_TRIGGERED = "sos_triggered"
        const val KEY_INCIDENT = "sos_incident_key"
        const val KEY_SMS = "sos_sms"
        const val KEY_STARTED_AT = "sos_started_at"
    }
}
