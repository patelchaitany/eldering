package com.eldering.safety.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.eldering.safety.R
import com.eldering.safety.network.Activity
import com.eldering.safety.network.IncidentKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** "Dad may have been in an accident" / "Dad may have fallen" / "Dad pressed SOS". */
@Composable
fun incidentHeadline(kind: IncidentKind, name: String): String = stringResource(
    when (kind) {
        IncidentKind.VEHICLE_CRASH, IncidentKind.PEDESTRIAN_IMPACT -> R.string.incident_headline_accident
        IncidentKind.FALL -> R.string.incident_headline_fall
        IncidentKind.SOS -> R.string.incident_headline_sos
    },
    name,
)

/** What the phone detected, for the "Detected" row ("Hard impact while driving"). */
@StringRes
fun detectedLabel(kind: IncidentKind, mode: Activity?): Int = when (kind) {
    IncidentKind.VEHICLE_CRASH -> R.string.detected_vehicle_crash
    IncidentKind.PEDESTRIAN_IMPACT -> R.string.detected_pedestrian_impact
    IncidentKind.FALL -> if (mode == Activity.WALKING || mode == Activity.RUNNING) R.string.detected_fall_walking else R.string.detected_fall
    IncidentKind.SOS -> R.string.detected_sos
}

/** Reads the detector fields the alert pipeline stores in `details` (see `IncidentDetails.fromDetection`). */
object IncidentDetailsReader {
    fun mode(details: JsonObject): Activity? = (details["mode"] as? JsonPrimitive)?.content?.let { value ->
        Activity.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }

    /** GPS speed before the impact, m/s (`features.speed_before`). */
    fun speedBeforeMps(details: JsonObject): Float? = feature(details, "speed_before")

    /** Highest speed after the impact, m/s (`features.speed_after`). */
    fun speedAfterMps(details: JsonObject): Float? = feature(details, "speed_after")

    /** Optional summary the sender may add, e.g. "Mom, Dad (app) and 1 emergency contact (SMS)". */
    fun alsoAlerted(details: JsonObject): String? =
        (details["also_alerted"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.take(120)

    /** Family members the sender's phone alerted in the app (`alerted_family`: user id → name). */
    fun alertedFamily(details: JsonObject): List<Pair<String, String>> = try {
        (details["alerted_family"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = (o["user_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.take(80).orEmpty()
            id to name
        }
    } catch (e: IllegalArgumentException) {
        emptyList()
    }

    /** Number of emergency contacts texted (`sms_contacts`). */
    fun smsContacts(details: JsonObject): Int =
        (details["sms_contacts"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, 99) ?: 0

    /**
     * The design's "Also alerted: You, Sister, 1 contact" from the viewer's point of view ([myUserId] reads "You",
     * listed first). Falls back to the sender's own `also_alerted` text (older app versions), null if neither.
     */
    fun alsoAlertedFor(details: JsonObject, myUserId: String?, you: String, contacts: (Int) -> String): String? {
        val family = alertedFamily(details)
        val sms = smsContacts(details)
        if (family.isEmpty() && sms == 0) return alsoAlerted(details)
        val names = family.sortedBy { (id, _) -> if (id == myUserId) 0 else 1 }
            .map { (id, name) -> if (id == myUserId) you else name }
            .filter { it.isNotBlank() }
        return (names + listOfNotNull(sms.takeIf { it > 0 }?.let(contacts))).joinToString(", ").take(160)
    }

    private fun feature(details: JsonObject, key: String): Float? = try {
        details["features"]?.jsonObject?.get(key)?.jsonPrimitive?.floatOrNull?.takeIf { it.isFinite() && it >= 0f }
    } catch (e: IllegalArgumentException) { // not an object / not a primitive
        null
    }
}
