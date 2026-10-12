package com.eldering.safety.data.repository

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
) {
    sealed interface SaveResult {
        data class Saved(val contact: EmergencyContact) : SaveResult

        data class Invalid(val problem: Problem) : SaveResult
    }

    enum class Problem { NAME_BLANK, NAME_TOO_LONG, PHONE_INVALID, DUPLICATE_PHONE, TOO_MANY, NOT_FOUND }

    /** Ordered by position. */
    val contacts: Flow<List<EmergencyContact>> = dao.observeAll()

    suspend fun all(): List<EmergencyContact> = dao.all()

    /** Contacts that should get the accident SMS, in order. */
    suspend fun smsRecipients(): List<EmergencyContact> = dao.all().filter { it.smsEnabled }

    suspend fun add(name: String, phone: String, relation: String? = null, smsEnabled: Boolean = true): SaveResult {
        val existing = dao.all()
        if (existing.size >= MAX_CONTACTS) return SaveResult.Invalid(Problem.TOO_MANY)
        val candidate = EmergencyContact(
            name = name.trim(),
            phone = PhoneNumbers.normalize(phone) ?: return SaveResult.Invalid(Problem.PHONE_INVALID),
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
        val candidate = stored.copy(
            name = contact.name.trim(),
            phone = PhoneNumbers.normalize(contact.phone) ?: return SaveResult.Invalid(Problem.PHONE_INVALID),
            relation = contact.relation?.trim()?.takeIf { it.isNotEmpty() },
            smsEnabled = contact.smsEnabled,
        )
        validate(candidate, dao.all().filter { it.id != contact.id })?.let { return SaveResult.Invalid(it) }
        dao.update(candidate)
        return SaveResult.Saved(candidate)
    }

    suspend fun remove(id: Long): Boolean = dao.delete(id) > 0

    /** Persists a new order (e.g. after drag-and-drop); ids not listed keep their position. */
    suspend fun reorder(idsInOrder: List<Long>) = dao.reorder(idsInOrder)

    private fun validate(contact: EmergencyContact, others: List<EmergencyContact>): Problem? = when {
        contact.name.isEmpty() -> Problem.NAME_BLANK
        contact.name.length > MAX_NAME_LENGTH -> Problem.NAME_TOO_LONG
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
}
