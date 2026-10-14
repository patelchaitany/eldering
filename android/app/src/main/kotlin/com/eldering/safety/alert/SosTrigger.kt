package com.eldering.safety.alert

import com.eldering.safety.core.TimeSource
import com.eldering.safety.data.repository.IncidentDetails
import com.eldering.safety.data.repository.IncidentDraft
import com.eldering.safety.data.repository.IncidentRepository
import com.eldering.safety.data.trail.TrailStore
import com.eldering.safety.network.IncidentKind

/**
 * Starts a manual SOS (Home: "Hold 3 s for SOS"). Holding the button for three seconds is the confirmation, so
 * there is no countdown: the alert goes out immediately.
 *
 * `AppGraph.sosTrigger` holds the implementation: [ControllerSosTrigger], which goes through the same alert path as
 * an accident (AlertController → IncidentSender: upload, SMS to emergency contacts, live updates) and reports the
 * SMS part through [SosOutcome.sms]. [ServerOnlySosTrigger] (server only, no SMS) remains for tests and tools.
 */
fun interface SosTrigger {
    suspend fun triggerSos(): SosOutcome
}

data class SosOutcome(
    val report: IncidentRepository.ReportResult,
    val sms: SmsOutcome,
)

/** What happened to the SMS part of an alert, for the "who was alerted" list. */
enum class SmsOutcome {
    /** Handed to the SMS manager. */
    SENT,

    /** Debug build with "Allow real SMS" off: logged only, nothing sent. */
    DRY_RUN,

    /** No emergency contacts with SMS enabled. */
    NO_CONTACTS,

    /** SEND_SMS not granted. */
    NO_PERMISSION,

    /** This [SosTrigger] does not send SMS. */
    NOT_SUPPORTED,

    FAILED,

    /** The user said they were OK before the SMS went out, so none was sent. */
    CANCELLED,
}

/**
 * Raises an SOS incident with the last hour of trail through [IncidentRepository] (queued and retried if offline).
 * Sends no SMS ([SmsOutcome.NOT_SUPPORTED]).
 */
class ServerOnlySosTrigger(
    private val trailStore: TrailStore,
    private val incidents: IncidentRepository,
    private val time: TimeSource = TimeSource.System,
) : SosTrigger {
    override suspend fun triggerSos(): SosOutcome {
        val now = time.nowMillis()
        val latest = trailStore.latest()
        val trail = trailStore.snapshot(now).map { it.toDto() }
        val report = incidents.report(
            IncidentDraft(
                kind = IncidentKind.SOS,
                detectedAtMs = now,
                lat = latest?.lat,
                lng = latest?.lng,
                details = IncidentDetails.sos(),
                trail = trail,
            ),
        )
        return SosOutcome(report, SmsOutcome.NOT_SUPPORTED)
    }
}
