package com.eldering.safety.alert

import com.eldering.detection.DetectionEvent
import com.eldering.detection.EventKind
import com.eldering.detection.Mode
import com.eldering.safety.data.repository.AlertRecipient
import com.eldering.safety.network.IncidentKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The alert [AlertController] is handling, written through on every transition so that a process death (OEM
 * killer, low memory, a crash elsewhere) during the countdown, the send or the live hour does not silently drop
 * the alert. On the next start [AlertController.restore] picks it up again.
 *
 * Small and free of personal data apart from what the on-device detection log already holds and the family members'
 * display names (no trail, no phone numbers, no raw sensor window).
 */
@Serializable
data class PersistedAlert(
    /** [ActiveAlert.incidentKey]: also the outbox key, so a resumed send never creates a second incident. */
    val key: String,
    val phase: Phase,
    val kind: IncidentKind,
    val detectedAtMs: Long,
    val mode: String? = null,
    val countdownMs: Long,
    val countdownEndsAtMs: Long? = null,
    val event: PersistedEvent? = null,
    val contacts: Int = 0,
    /** Family members alerted in the app (names for the screen and the incident details). */
    val family: List<AlertRecipient> = emptyList(),
    /** Detection-log row, once stored. */
    val eventId: Long? = null,
    /** "I'm OK" arrived while sending: resolve as soon as it is out, no SMS after that. */
    val resolveWhenSent: Boolean = false,
    /** [SmsOutcome] name once the SMS step ran (a resumed send must not text the contacts again). */
    val sms: String? = null,
    /** When the alert went out (live updates run for an hour from here). */
    val sentAtMs: Long? = null,
) {
    enum class Phase { COUNTING, SENDING, SENT }

    val smsOutcome: SmsOutcome? get() = sms?.let { name -> SmsOutcome.entries.firstOrNull { it.name == name } }

    fun toActiveAlert(id: Long): ActiveAlert = ActiveAlert(
        id = id,
        kind = kind,
        detectedAtMs = detectedAtMs,
        mode = mode?.let { name -> Mode.entries.firstOrNull { it.name == name } },
        countdownMs = countdownMs,
        countdownEndsAtMs = countdownEndsAtMs,
        event = event?.toEvent(),
        emergencyContactCount = contacts,
        familyRecipients = family,
        incidentKey = key,
    )

    companion object {
        fun of(alert: ActiveAlert, phase: Phase): PersistedAlert = PersistedAlert(
            key = alert.incidentKey,
            phase = phase,
            kind = alert.kind,
            detectedAtMs = alert.detectedAtMs,
            mode = alert.mode?.name,
            countdownMs = alert.countdownMs,
            countdownEndsAtMs = alert.countdownEndsAtMs,
            event = alert.event?.let(PersistedEvent::of),
            contacts = alert.emergencyContactCount,
            family = alert.familyRecipients,
        )

        private val json = Json {
            ignoreUnknownKeys = true
            allowSpecialFloatingPointValues = true // detector features may be NaN
        }

        fun encode(alert: PersistedAlert): String = json.encodeToString(serializer(), alert)

        /** Null for anything unreadable (an older app version, a damaged file). */
        fun decode(text: String?): PersistedAlert? {
            if (text.isNullOrBlank()) return null
            return try {
                json.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

/** The detector's event, enough to rebuild the incident details after a restart. */
@Serializable
data class PersistedEvent(
    val kind: String,
    val confidence: Float,
    val triggeredAtNanos: Long,
    val features: Map<String, Float> = emptyMap(),
    val reason: String = "",
) {
    fun toEvent(): DetectionEvent? {
        val k = EventKind.entries.firstOrNull { it.name == kind } ?: return null
        return DetectionEvent(k, confidence, triggeredAtNanos, features, reason)
    }

    companion object {
        fun of(e: DetectionEvent) = PersistedEvent(e.kind.name, e.confidence, e.triggeredAtNanos, e.features, e.reason)
    }
}

/** Where [PersistedAlert] lives (SharedPreferences on the phone, memory in tests). Synchronous and cheap. */
interface AlertStore {
    fun load(): PersistedAlert?
    fun save(alert: PersistedAlert)
    fun clear()

    /** Keeps nothing (tests that do not care about restarts). */
    object None : AlertStore {
        override fun load(): PersistedAlert? = null
        override fun save(alert: PersistedAlert) = Unit
        override fun clear() = Unit
    }
}
