package com.eldering.safety.debug

import com.eldering.safety.R
import com.eldering.safety.data.db.EmergencyContact
import com.eldering.safety.network.AckState
import com.eldering.safety.network.Activity
import com.eldering.safety.network.GrantLevel
import com.eldering.safety.network.IncidentDetailDto
import com.eldering.safety.network.IncidentKind
import com.eldering.safety.network.IncidentStatus
import com.eldering.safety.network.IncidentSummaryDto
import com.eldering.safety.network.Resolution
import com.eldering.safety.network.SettingsDto
import com.eldering.safety.network.SharingState
import com.eldering.safety.network.TrailPointDto
import com.eldering.safety.ui.alert.AccidentAlertInfo
import com.eldering.safety.ui.alert.AccidentAlertUiState
import com.eldering.safety.ui.alert.AlertPhase
import com.eldering.safety.ui.alert.DeliveryLine
import com.eldering.safety.ui.alert.DeliveryState
import com.eldering.safety.ui.common.UiText
import com.eldering.safety.ui.contacts.ContactForm
import com.eldering.safety.ui.contacts.ContactsUiState
import com.eldering.safety.ui.familyalert.FamilyAlertUiState
import com.eldering.safety.ui.home.HomeUiState
import com.eldering.safety.ui.home.MeUi
import com.eldering.safety.ui.home.MemberUi
import com.eldering.safety.ui.home.MemberVisibility
import com.eldering.safety.ui.map.CameraRequest
import com.eldering.safety.ui.map.CameraTarget
import com.eldering.safety.ui.map.GeoPoint
import com.eldering.safety.ui.onboarding.InviteUiState
import com.eldering.safety.ui.platform.ProtectionStatus
import com.eldering.safety.ui.sharing.PersonRole
import com.eldering.safety.ui.sharing.SharingPerson
import com.eldering.safety.ui.sharing.SharingUiState
import com.eldering.safety.ui.theme.MemberColors
import com.eldering.safety.ui.trip.TrailSample
import com.eldering.safety.ui.trip.TrailSummarizer
import com.eldering.safety.ui.trip.TripEmpty
import com.eldering.safety.ui.trip.TripUiState
import com.eldering.safety.ui.trip.TripViewModel
import androidx.compose.ui.graphics.Color
import java.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Made-up data for the debug UI gallery: the family from the designs (Dad driving, Mom at home, Sister paused, Uncle
 * as an SMS-only contact) around a generic city centre. Nothing here is real, and nothing is sent anywhere.
 */
internal class GallerySamples(private val now: Long) {

    private val dadColor = Color(0xFF0E6B57)
    private val momColor = Color(0xFF7A3FB8)

    private val me = GeoPoint(18.5204, 73.8567)
    private val dadAt = GeoPoint(18.5352, 73.8668)
    private val momAt = GeoPoint(18.5281, 73.8762)

    /** Dad's last hour: office → fuel stop (4 min) → bridge → metro (slow) → impact, one point every 30 s. */
    private val route = listOf(
        Waypoint(GeoPoint(18.4985, 73.8302), 0, "Left office"),
        Waypoint(GeoPoint(18.5098, 73.8391), 9, "Fuel station, Ring Road"),
        Waypoint(GeoPoint(18.5098, 73.8391), 13, null),
        Waypoint(GeoPoint(18.5214, 73.8470), 23, "Crossed river bridge"),
        Waypoint(GeoPoint(18.5301, 73.8594), 40, "Passed metro station"),
        Waypoint(GeoPoint(18.5352, 73.8668), 60, "Near city hospital junction, Ring Road"),
    )
    private val impactMs = now - 3 * MINUTE
    private val tripStartMs = impactMs - 60 * MINUTE

    val dadTrail: List<TrailSample> = buildTrail()

    private val dadIncident = IncidentSummaryDto(
        id = INCIDENT_ID,
        userId = DAD,
        displayName = "Dad",
        kind = IncidentKind.VEHICLE_CRASH,
        status = IncidentStatus.ACTIVE,
        detectedAt = Instant.ofEpochMilli(impactMs),
        lat = dadAt.lat,
        lng = dadAt.lng,
    )

    // --- Home ---------------------------------------------------------------------------------------------------

    private val meUi = MeUi(
        name = "Asha",
        position = me,
        recordedAtMs = now - 20_000,
        activity = Activity.WALKING,
        batteryPct = 82,
        place = "City Park",
        sharing = SharingState.ON,
    )

    private val members = listOf(
        MemberUi(
            userId = DAD, name = "Dad", initials = "D", color = dadColor, visibility = MemberVisibility.LIVE,
            activity = Activity.VEHICLE, speedKmh = 38, position = dadAt, accuracyM = 8f, recordedAtMs = now - 10_000,
            batteryPct = 41, place = "Ring Road, heading north",
        ),
        MemberUi(
            userId = MOM, name = "Mom", initials = "M", color = momColor, visibility = MemberVisibility.LIVE,
            activity = Activity.STILL, position = momAt, accuracyM = 15f, recordedAtMs = now - 2 * MINUTE,
            batteryPct = 64, place = "Home",
        ),
        MemberUi(
            userId = SISTER, name = "Sister", initials = "S", color = MemberColors.unavailable,
            visibility = MemberVisibility.PAUSED,
        ),
    )

    val home = HomeUiState(
        loading = false,
        me = meUi,
        members = members,
        selectedId = DAD,
        protection = ProtectionStatus(running = true, activity = Activity.WALKING),
        nowMs = now,
        camera = CameraRequest(CameraTarget.Fit(listOf(me, dadAt, momAt)), animate = false),
    )

    val homeWithAlert = home.copy(
        members = members.map { if (it.userId == DAD) it.copy(activeIncidentId = INCIDENT_ID) else it },
        familyAlerts = listOf(dadIncident),
    )

    val homeLoading = HomeUiState(loading = true, me = MeUi(name = "Asha"), nowMs = now)

    val homeNoFamily = HomeUiState(
        loading = false,
        me = meUi,
        hasFamily = false,
        protection = ProtectionStatus(running = false, activity = null),
        nowMs = now,
        camera = CameraRequest(CameraTarget.Center(me), animate = false),
    )

    val homeOffline = home.copy(error = UiText.res(R.string.error_network))

    // --- Accident countdown ---------------------------------------------------------------------------------------

    fun countdownInfo(kind: IncidentKind): AccidentAlertInfo = AccidentAlertInfo(
        kind = kind,
        detectedAtMs = System.currentTimeMillis(),
        countdownEndsAtMs = System.currentTimeMillis() + AccidentAlertInfo.countdownFor(kind),
        activity = if (kind == IncidentKind.VEHICLE_CRASH) Activity.VEHICLE else Activity.WALKING,
        familyRecipients = listOf("Mom", "Dad"),
        emergencyContactCount = 1,
    )

    val sent = AccidentAlertUiState(
        info = AccidentAlertInfo(IncidentKind.PEDESTRIAN_IMPACT, impactMs, null, Activity.WALKING, listOf("Mom", "Dad"), 1),
        phase = AlertPhase.SENT,
        secondsLeft = 0,
        deliveries = listOf(
            DeliveryLine(UiText.res(R.string.alert_delivery_app, "Mom"), DeliveryState.SEEN),
            DeliveryLine(UiText.res(R.string.alert_delivery_app, "Dad"), DeliveryState.DELIVERED),
            DeliveryLine(UiText.res(R.string.alert_delivery_sms, "Mom, Dad"), DeliveryState.SENT),
            DeliveryLine(UiText.res(R.string.alert_delivery_contacts_sms), DeliveryState.DRY_RUN),
        ),
    )

    val cancelled = sent.copy(phase = AlertPhase.CANCELLED, deliveries = emptyList())

    // --- Trip to past -------------------------------------------------------------------------------------------

    val trip: TripUiState = run {
        val summary = TrailSummarizer.summarize(dadTrail, impactMs)
        val names = summary?.landmarks.orEmpty().mapNotNull { l -> nearestName(dadTrail[l.index])?.let { l.index to it } }.toMap()
        TripUiState(
            loading = false,
            personName = "Dad",
            points = dadTrail,
            summary = summary,
            placeNames = names,
            cursor = summary?.incidentIndex ?: dadTrail.lastIndex,
            incidentKind = IncidentKind.VEHICLE_CRASH,
        )
    }

    val tripEmpty = TripUiState(loading = false, empty = TripEmpty.NO_LOCAL_TRAIL)

    val tripNotShared = TripUiState(loading = false, personName = "Mom", empty = TripEmpty.NOT_SHARED)

    // --- What family sees -----------------------------------------------------------------------------------------

    private val dadDetail = IncidentDetailDto(
        id = INCIDENT_ID,
        userId = DAD,
        displayName = "Dad",
        kind = IncidentKind.VEHICLE_CRASH,
        status = IncidentStatus.ACTIVE,
        detectedAt = Instant.ofEpochMilli(impactMs),
        lat = dadAt.lat,
        lng = dadAt.lng,
        details = buildJsonObject {
            put("source", "detector")
            put("mode", "vehicle")
            put("also_alerted", "You, Sister, 1 contact")
            putJsonObject("features") {
                put("speed_before", 11.7f)
                put("speed_after", 0f)
            }
        },
        // The last 10 minutes before the impact, then the phone keeps reporting where it lies.
        trail = dadTrail.filter { it.tMs >= impactMs - 10 * MINUTE }.map {
            TrailPointDto(it.lat, it.lng, Instant.ofEpochMilli(it.tMs), accuracyM = 8f)
        } + TrailPointDto(dadAt.lat, dadAt.lng, Instant.ofEpochMilli(now - 6_000), accuracyM = 8f),
    )

    val familyAlert = FamilyAlertUiState(
        loading = false,
        detail = dadDetail,
        trail = TripViewModel.samplesOf(dadDetail),
        place = "Near city hospital junction, Ring Road",
        batteryPct = 38,
    )

    val familyAlertHandled = familyAlert.copy(
        detail = dadDetail.copy(status = IncidentStatus.RESOLVED, resolution = Resolution.HANDLED, myAck = AckState.ON_MY_WAY),
        handledByMe = true,
    )

    val familyAlertNoLocation = FamilyAlertUiState(
        loading = false,
        detail = dadDetail.copy(lat = null, lng = null, trail = emptyList(), kind = IncidentKind.SOS),
    )

    val familyAlertError = FamilyAlertUiState(loading = false, error = UiText.res(R.string.error_network))

    // --- Sharing & privacy, contacts, settings ---------------------------------------------------------------------

    val sharing = SharingUiState(
        loading = false,
        settings = SettingsDto(sharingState = SharingState.ON, alwaysAlertOnAccident = true),
        people = listOf(
            SharingPerson(MOM, "Mom", "M", momColor, PersonRole.ADMIN, GrantLevel.LIVE),
            SharingPerson(DAD, "Dad", "D", dadColor, PersonRole.MEMBER, GrantLevel.LIVE),
            SharingPerson(SISTER, "Sister", "S", MemberColors.unavailable, PersonRole.MEMBER, GrantLevel.EMERGENCY),
            SharingPerson(
                "contact:1", "Uncle", "U", MemberColors.emergencyContact, PersonRole.EMERGENCY_CONTACT, GrantLevel.EMERGENCY,
                maskedPhone = "+91 ••••• ••210",
            ),
        ),
    )

    val sharingError = SharingUiState(loading = false, error = UiText.res(R.string.error_network))

    private val uncle = EmergencyContact(id = 1, name = "Uncle Ravi", phone = "+910000000210", relation = "Uncle", createdAtMs = now)

    val contacts = ContactsUiState(loading = false, contacts = listOf(uncle))

    val contactsEmpty = ContactsUiState(loading = false)

    val contactsForm = ContactsUiState(
        loading = false,
        contacts = listOf(uncle),
        form = ContactForm(name = "Aunt", phone = "12", phoneError = R.string.contacts_error_phone),
    )

    val invite = InviteUiState(
        loading = false,
        familyName = "Rivera family",
        isAdmin = true,
        code = "ABCD1234",
        expiresAtMs = now + 48 * 60 * MINUTE,
    )

    // --- helpers --------------------------------------------------------------------------------------------------

    private data class Waypoint(val point: GeoPoint, val minute: Int, val name: String?)

    private fun buildTrail(): List<TrailSample> {
        val samples = mutableListOf<TrailSample>()
        var t = tripStartMs
        while (t <= impactMs) {
            val minute = (t - tripStartMs) / MINUTE.toDouble()
            val i = route.indexOfLast { it.minute <= minute }.coerceAtMost(route.lastIndex - 1)
            val a = route[i]
            val b = route[i + 1]
            val f = ((minute - a.minute) / (b.minute - a.minute)).coerceIn(0.0, 1.0)
            val lat = a.point.lat + (b.point.lat - a.point.lat) * f
            val lng = a.point.lng + (b.point.lng - a.point.lng) * f
            val previous = samples.lastOrNull()
            val speed = previous?.let {
                val d = com.eldering.safety.data.trail.Geo.distanceM(it.lat, it.lng, lat, lng)
                (d / ((t - it.tMs) / 1000.0)).toFloat()
            } ?: 0f
            samples += TrailSample(lat, lng, t, speedMps = speed, accuracyM = 8f, activity = Activity.VEHICLE)
            t += 30_000
        }
        return samples
    }

    /** The design's place name for the route waypoint closest to [sample]. */
    private fun nearestName(sample: TrailSample): String? = route.filter { it.name != null }
        .minByOrNull { com.eldering.safety.data.trail.Geo.distanceM(it.point.lat, it.point.lng, sample.lat, sample.lng) }
        ?.name

    companion object {
        const val DAD = "sample-dad"
        const val MOM = "sample-mom"
        const val SISTER = "sample-sister"
        const val INCIDENT_ID = "00000000-0000-4000-8000-000000000001"
        private const val MINUTE = 60_000L
    }
}
