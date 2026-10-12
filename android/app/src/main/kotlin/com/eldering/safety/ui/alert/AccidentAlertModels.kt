package com.eldering.safety.ui.alert

import android.os.Bundle
import androidx.compose.runtime.Immutable
import com.eldering.safety.data.db.FeedbackLabel
import com.eldering.safety.data.repository.DetectionEventRepository
import com.eldering.safety.network.Activity
import com.eldering.safety.network.IncidentKind
import com.eldering.safety.ui.common.UiText

/**
 * What the accident countdown screen shows. Built by the alert pipeline (AlertController) and handed to
 * `AccidentAlertActivity`, e.g. through [toBundle] / [fromBundle] in the activity intent.
 */
@Immutable
data class AccidentAlertInfo(
    val kind: IncidentKind,
    /** Wall-clock time of the impact (or of the SOS). */
    val detectedAtMs: Long,
    /**
     * When the countdown ends and the alert goes out. Null when it has already been sent (SOS, or the screen was
     * reopened after sending): the screen then opens in the "sent" state.
     */
    val countdownEndsAtMs: Long?,
    /** Movement mode at detection ("…while you were walking"). */
    val activity: Activity? = null,
    /** Display names of family members who will get the app alert. */
    val familyRecipients: List<String> = emptyList(),
    /** Emergency contacts who will get the SMS. */
    val emergencyContactCount: Int = 0,
    /** `DetectionEventRepository` row for false-alarm feedback, if this came from the detector. */
    val detectionEventId: Long? = null,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_KIND, kind.name)
        putLong(KEY_DETECTED_AT, detectedAtMs)
        countdownEndsAtMs?.let { putLong(KEY_COUNTDOWN_ENDS, it) }
        activity?.let { putString(KEY_ACTIVITY, it.name) }
        putStringArrayList(KEY_FAMILY, ArrayList(familyRecipients))
        putInt(KEY_CONTACTS, emergencyContactCount)
        detectionEventId?.let { putLong(KEY_EVENT_ID, it) }
    }

    companion object {
        /** SPEC: 15 s when walking or for a fall, 20 s in a vehicle. */
        const val COUNTDOWN_WALKING_MS = 15_000L
        const val COUNTDOWN_VEHICLE_MS = 20_000L

        fun countdownFor(kind: IncidentKind): Long =
            if (kind == IncidentKind.VEHICLE_CRASH) COUNTDOWN_VEHICLE_MS else COUNTDOWN_WALKING_MS

        private const val KEY_KIND = "eldering.alert.kind"
        private const val KEY_DETECTED_AT = "eldering.alert.detected_at"
        private const val KEY_COUNTDOWN_ENDS = "eldering.alert.countdown_ends"
        private const val KEY_ACTIVITY = "eldering.alert.activity"
        private const val KEY_FAMILY = "eldering.alert.family"
        private const val KEY_CONTACTS = "eldering.alert.contacts"
        private const val KEY_EVENT_ID = "eldering.alert.event_id"

        /** Null if the bundle does not describe an alert. */
        fun fromBundle(bundle: Bundle?): AccidentAlertInfo? {
            if (bundle == null || !bundle.containsKey(KEY_KIND)) return null
            val kind = IncidentKind.entries.firstOrNull { it.name == bundle.getString(KEY_KIND) } ?: return null
            return AccidentAlertInfo(
                kind = kind,
                detectedAtMs = bundle.getLong(KEY_DETECTED_AT),
                countdownEndsAtMs = if (bundle.containsKey(KEY_COUNTDOWN_ENDS)) bundle.getLong(KEY_COUNTDOWN_ENDS) else null,
                activity = bundle.getString(KEY_ACTIVITY)?.let { name -> Activity.entries.firstOrNull { it.name == name } },
                familyRecipients = bundle.getStringArrayList(KEY_FAMILY).orEmpty(),
                emergencyContactCount = bundle.getInt(KEY_CONTACTS),
                detectionEventId = if (bundle.containsKey(KEY_EVENT_ID)) bundle.getLong(KEY_EVENT_ID) else null,
            )
        }
    }
}

enum class AlertPhase { COUNTING, SENT, CANCELLED }

/** Delivery state of one line in "Your family has been alerted". */
enum class DeliveryState { SENDING, SENT, DELIVERED, SEEN, ON_THE_WAY, DRY_RUN, NOT_SENT, FAILED }

@Immutable
data class DeliveryLine(val who: UiText, val state: DeliveryState)

/** "Help us get better. What happened?" */
enum class FeedbackChoice { DROPPED_PHONE, FELL_BUT_FINE, MINOR_ACCIDENT, SOMETHING_ELSE }

/** Where the false-alarm answer goes (on-device only). */
fun interface AlertFeedbackSink {
    suspend fun record(choice: FeedbackChoice)
}

/** Stores the answer on the detector event (SPEC: false-alarm feedback stored locally with the event window). */
class DetectionFeedbackSink(
    private val events: DetectionEventRepository,
    private val eventId: Long,
) : AlertFeedbackSink {
    override suspend fun record(choice: FeedbackChoice) {
        events.label(eventId, labelFor(choice), note = choice.name.lowercase())
    }

    companion object {
        fun labelFor(choice: FeedbackChoice): FeedbackLabel = when (choice) {
            FeedbackChoice.DROPPED_PHONE -> FeedbackLabel.FALSE_ALARM
            FeedbackChoice.FELL_BUT_FINE, FeedbackChoice.MINOR_ACCIDENT -> FeedbackLabel.REAL_INCIDENT
            FeedbackChoice.SOMETHING_ELSE -> FeedbackLabel.UNSURE
        }
    }
}
