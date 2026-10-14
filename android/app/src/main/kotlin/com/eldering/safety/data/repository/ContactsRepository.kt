package com.eldering.safety.data.repository

import com.eldering.safety.alert.EmergencyNumbers
import com.eldering.safety.core.TimeSource
import com.eldering.safety.data.db.EmergencyContact
import com.eldering.safety.data.db.EmergencyContactDao
import kotlinx.coroutines.flow.Flow

/**
 * Emergency SMS contacts. Device-only by design (SPEC): nothing here is ever sent to the server, and the database
 * is excluded from backups. Sending SMS is not done here (see `alert.IncidentSender`, which dry-runs in debug).
 */
class ContactsRepository(
    private val dao: EmergencyContactDao,
    private val time: TimeSource = TimeSource.System,
    /**
     * Emergency services (112, 911, …) are never SMS contacts: every expired countdown, including a false alarm
     * nobody cancelled, would text them automatically. The app only ever opens the dialer for them.
     */
    private val isEmergencyNumber: (String) -> Boolean = EmergencyNumbers::isStaticEmergencyNumber,
    /**
     * The number in international form (+country code), or null if that cannot be worked out. The accident SMS
     * may be sent while roaming, so a number without a country code is not good enough (the form's hint asks for
     * it). On the phone a national number is converted with the SIM's country (see `AppGraph`).
     */
    private val toInternational: (String) -> String? = PhoneNumbers::international,
) {
    sealed interface SaveResult {
        data class Saved(val contact: EmergencyContact) : SaveResult

        data class Invalid(val problem: Problem) : SaveResult
    }

    enum class Problem { NAME_BLANK, NAME_TOO_LONG, PHONE_INVALID, PHONE_NO_COUNTRY_CODE, EMERGENCY_NUMBER, DUPLICATE_PHONE, TOO_MANY, NOT_FOUND }

    /** Ordered by position. */
    val contacts: Flow<List<EmergencyContact>> = dao.observeAll()

    suspend fun all(): List<EmergencyContact> = dao.all()

    /** Contacts that should get the accident SMS, in order (never an emergency number saved by an older version). */
    suspend fun smsRecipients(): List<EmergencyContact> = dao.all().filter { it.smsEnabled && !isEmergencyNumber(it.phone) }

    suspend fun add(name: String, phone: String, relation: String? = null, smsEnabled: Boolean = true): SaveResult {
        val existing = dao.all()
        if (existing.size >= MAX_CONTACTS) return SaveResult.Invalid(Problem.TOO_MANY)
        val number = checkedPhone(phone)
        val candidate = EmergencyContact(
            name = name.trim(),
            phone = number.phone ?: return SaveResult.Invalid(number.problem!!),
            relation = relation?.trim()?.takeIf { it.isNotEmpty() },
            position = dao.maxPosition() + 1,
            smsEnabled = smsEnabled,
            createdAtMs = time.nowMillis(),
        )
        validate(candidate, existing)?.let { return SaveResult.Invalid(it) }
        return SaveResult.Saved(candidate.copy(id = dao.insert(candidate)))
    }

    suspend fun update(contact: EmergencyContact): SaveResult {
        val stored = dao.get(contact.id) ?: return SaveResult.Invalid(Problem.NOT_FOUND)
        // A number saved before country codes were required stays as it is until someone edits it.
        val number = if (PhoneNumbers.normalize(contact.phone) == stored.phone) CheckedPhone(stored.phone, null) else checkedPhone(contact.phone)
        val candidate = stored.copy(
            name = contact.name.trim(),
            phone = number.phone ?: return SaveResult.Invalid(number.problem!!),
            relation = contact.relation?.trim()?.takeIf { it.isNotEmpty() },
            smsEnabled = contact.smsEnabled,
        )
        validate(candidate, dao.all().filter { it.id != contact.id })?.let { return SaveResult.Invalid(it) }
        dao.update(candidate)
        return SaveResult.Saved(candidate)
    }

    suspend fun remove(id: Long): Boolean = dao.delete(id) > 0

    /** Sign-out / another account on this phone: the next user must not text this user's contacts. */
    suspend fun clear(): Int = dao.clear()

    /** Persists a new order (e.g. after drag-and-drop); ids not listed keep their position. */
    suspend fun reorder(idsInOrder: List<Long>) = dao.reorder(idsInOrder)

    private class CheckedPhone(val phone: String?, val problem: Problem?)

    /** The international number, or the [Problem] with it. */
    private fun checkedPhone(raw: String): CheckedPhone {
        val normalized = PhoneNumbers.normalize(raw) ?: return CheckedPhone(null, Problem.PHONE_INVALID)
        // Emergency services are refused as such later (clearer than "add the country code" for 112).
        if (isEmergencyNumber(normalized)) return CheckedPhone(normalized, null)
        val international = toInternational(normalized) ?: return CheckedPhone(null, Problem.PHONE_NO_COUNTRY_CODE)
        return CheckedPhone(international, null)
    }

    private fun validate(contact: EmergencyContact, others: List<EmergencyContact>): Problem? = when {
        contact.name.isEmpty() -> Problem.NAME_BLANK
        contact.name.length > MAX_NAME_LENGTH -> Problem.NAME_TOO_LONG
        isEmergencyNumber(contact.phone) -> Problem.EMERGENCY_NUMBER
        others.any { it.phone == contact.phone } -> Problem.DUPLICATE_PHONE
        else -> null
    }

    companion object {
        /** Each accident SMS goes to every contact; keep it short so they all go out quickly. */
        const val MAX_CONTACTS = 10
        const val MAX_NAME_LENGTH = 80
    }
}

/** Phone number clean-up for SMS recipients (no libphonenumber: we only need a dialable string). */
object PhoneNumbers {
    private val separators = setOf(' ', '-', '.', '(', ')', '/', ' ')

    /**
     * Strips separators and keeps digits with an optional leading '+' ("00" international prefix becomes '+').
     * Returns null for anything that is not a plausible phone number (3–17 digits, no letters).
     */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val plus = trimmed.startsWith("+")
        val body = (if (plus) trimmed.substring(1) else trimmed).filterNot { it in separators }
        if (body.isEmpty() || !body.all { it in '0'..'9' }) return null
        val normalized = when {
            plus -> "+$body"
            body.startsWith("00") && body.length > 4 -> "+" + body.substring(2)
            else -> body
        }
        val digits = normalized.count { it.isDigit() }
        return normalized.takeIf { digits in 3..17 }
    }

    /**
     * [normalized] if it already is international ("+" and 8–15 digits, E.164), else null. Converting a national
     * number needs the country (see `DevicePhoneNumbers`).
     */
    fun international(normalized: String): String? {
        if (!normalized.startsWith("+")) return null
        val digits = normalized.length - 1
        return normalized.takeIf { digits in 8..15 && normalized.drop(1).all { it in '0'..'9' } }
    }
}
