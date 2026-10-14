package com.eldering.safety.ui.platform

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale

/** Phone-number help from the platform (its bundled libphonenumber), so the app needs no extra library. */
object DevicePhoneNumbers {
    /**
     * [number] (digits, maybe a leading '+') in E.164 form, using the SIM's (or network's, or the locale's) country
     * for a national number: "098765 43210" on an Indian SIM → "+919876543210". Null when it is not a valid number
     * for that country (e.g. "5550100" without an area code).
     */
    fun toE164(context: Context, number: String): String? {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val iso = listOfNotNull(
            runCatching { tm?.simCountryIso }.getOrNull(),
            runCatching { tm?.networkCountryIso }.getOrNull(),
            Locale.getDefault().country,
        ).firstOrNull { it.isNotBlank() }?.uppercase(Locale.ROOT) ?: return null
        return try {
            PhoneNumberUtils.formatNumberToE164(number, iso)
        } catch (e: RuntimeException) {
            null
        }
    }
}
