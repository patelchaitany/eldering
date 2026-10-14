package com.eldering.safety.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.eldering.safety.data.db.EmergencyContact

/**
 * Phone numbers for family members, kept on this phone only. The server stores no phone numbers (SPEC data model),
 * so "Call Dad" needs a number from here: one the user picked from their address book the first time they tapped
 * "Call", or else an emergency contact with the same name.
 */
class FamilyPhoneBook(private val store: Store) {

    /** Synchronous key-value storage (SharedPreferences on the phone, memory in tests). */
    interface Store {
        fun get(userId: String): String?
        fun put(userId: String, phone: String)
        fun clear()

        class Memory : Store {
            private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
            override fun get(userId: String): String? = map[userId]
            override fun put(userId: String, phone: String) {
                map[userId] = phone
            }

            override fun clear() = map.clear()
        }
    }

    /** The number to dial for [userId]; [name] matches an emergency contact as a fallback. Null if none is known. */
    fun phoneFor(userId: String, name: String, contacts: List<EmergencyContact>): String? =
        store.get(userId) ?: contacts.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }?.phone

    /** Remembers [phone] for [userId]; returns the cleaned-up number, or null if it is not a phone number. */
    fun remember(userId: String, phone: String): String? {
        val normalized = PhoneNumbers.normalize(phone) ?: return null
        store.put(userId, normalized)
        return normalized
    }

    /** Sign-out: these numbers belong to the account's family. */
    fun clear() = store.clear()
}

/** [FamilyPhoneBook.Store] in a private SharedPreferences file (excluded from backups like the rest of the app). */
class PrefsFamilyPhoneStore(context: Context) : FamilyPhoneBook.Store {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun get(userId: String): String? = prefs.getString(userId, null)

    override fun put(userId: String, phone: String) {
        prefs.edit().putString(userId, phone).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE = "family_phones"
    }
}
