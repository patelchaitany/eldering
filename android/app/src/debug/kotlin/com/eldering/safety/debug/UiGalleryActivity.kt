package com.eldering.safety.debug

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eldering.safety.R
import com.eldering.safety.alert.AlertActions
import com.eldering.safety.core.appGraph
import com.eldering.safety.network.GrantLevel
import com.eldering.safety.network.IncidentKind
import com.eldering.safety.network.Session
import com.eldering.safety.network.SharingState
import com.eldering.safety.ui.alert.AccidentAlertRoute
import com.eldering.safety.ui.alert.AccidentAlertScreen
import com.eldering.safety.ui.alert.AccidentAlertViewModel
import com.eldering.safety.ui.contacts.EmergencyContactsScreen
import com.eldering.safety.ui.familyalert.FamilyAlertScreen
import com.eldering.safety.ui.home.HomeActions
import com.eldering.safety.ui.home.HomeScreen
import com.eldering.safety.ui.home.HomeUiState
import com.eldering.safety.ui.map.LocalMapStyleUrl
import com.eldering.safety.ui.navigation.Route
import com.eldering.safety.ui.onboarding.FamilySetupScreen
import com.eldering.safety.ui.onboarding.FamilySetupUiState
import com.eldering.safety.ui.onboarding.InviteScreen
import com.eldering.safety.ui.permissions.PermissionsRoute
import com.eldering.safety.ui.settings.SettingsScreen
import com.eldering.safety.ui.sharing.SharingActions
import com.eldering.safety.ui.sharing.SharingScreen
import com.eldering.safety.ui.theme.Eldering
import com.eldering.safety.ui.theme.ElderingTheme
import com.eldering.safety.ui.trip.TripScreen
import com.eldering.safety.ui.components.NavigationRow
import com.eldering.safety.ui.components.ScreenHeader
import java.time.Instant

/**
 * Debug builds only (declared in src/debug/AndroidManifest.xml): every screen of the app with made-up data, so the
 * UI can be checked against the designs on a device without an account or a server.
 *
 * Nothing here talks to the server, sends SMS or opens the dialer: the accident countdown runs the real
 * [AccidentAlertViewModel] against a logging [AlertActions]. Open a screen directly with
 * `adb shell am start -n com.eldering.safety/.debug.UiGalleryActivity --es screen countdown` (ids in [GalleryEntry]).
 */
class UiGalleryActivity : ComponentActivity() {
    /** The page asked for by the latest intent (singleTop: a new `am start` switches page in place). */
    private val requested = mutableStateOf<GalleryRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            ElderingTheme {
                CompositionLocalProvider(LocalMapStyleUrl provides appGraph.config.mapStyleUrl) {
                    UiGallery(requested.value, onExit = ::finish)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val entry = intent.getStringExtra(EXTRA_SCREEN)?.let { id -> GalleryEntry.entries.firstOrNull { it.id == id } } ?: return
        requested.value = GalleryRequest(entry, System.nanoTime())
    }

    companion object {
        const val EXTRA_SCREEN = "screen"

        fun intent(context: Context): Intent = Intent(context, UiGalleryActivity::class.java)
    }
}

/** A page requested by an intent; [seq] makes asking for the same page again re-open it. */
internal data class GalleryRequest(val entry: GalleryEntry, val seq: Long)

/** Gallery pages; [id] is what `--es screen <id>` takes. */
internal enum class GalleryEntry(val id: String, @StringRes val title: Int, @StringRes val section: Int) {
    HOME("home", R.string.gallery_home, R.string.gallery_section_main),
    HOME_ALERT("home-alert", R.string.gallery_home_alert, R.string.gallery_section_main),
    HOME_LOADING("home-loading", R.string.gallery_home_loading, R.string.gallery_section_main),
    HOME_NO_FAMILY("home-no-family", R.string.gallery_home_no_family, R.string.gallery_section_main),
    HOME_OFFLINE("home-offline", R.string.gallery_home_offline, R.string.gallery_section_main),
    COUNTDOWN("countdown", R.string.gallery_countdown, R.string.gallery_section_crash),
    COUNTDOWN_VEHICLE("countdown-vehicle", R.string.gallery_countdown_vehicle, R.string.gallery_section_crash),
    SENT("sent", R.string.gallery_sent, R.string.gallery_section_crash),
    CANCELLED("cancelled", R.string.gallery_cancelled, R.string.gallery_section_crash),
    TRIP("trip", R.string.gallery_trip, R.string.gallery_section_trip),
    TRIP_EMPTY("trip-empty", R.string.gallery_trip_empty, R.string.gallery_section_trip),
    TRIP_NOT_SHARED("trip-not-shared", R.string.gallery_trip_not_shared, R.string.gallery_section_trip),
    FAMILY_ALERT("family-alert", R.string.gallery_family_alert, R.string.gallery_section_family),
    FAMILY_ALERT_HANDLED("family-alert-handled", R.string.gallery_family_alert_handled, R.string.gallery_section_family),
    FAMILY_ALERT_NO_LOCATION("family-alert-no-location", R.string.gallery_family_alert_no_location, R.string.gallery_section_family),
    FAMILY_ALERT_ERROR("family-alert-error", R.string.gallery_family_alert_error, R.string.gallery_section_family),
    SHARING("sharing", R.string.gallery_sharing, R.string.gallery_section_sharing),
    SHARING_ERROR("sharing-error", R.string.gallery_sharing_error, R.string.gallery_section_sharing),
    CONTACTS("contacts", R.string.gallery_contacts, R.string.gallery_section_other),
    CONTACTS_EMPTY("contacts-empty", R.string.gallery_contacts_empty, R.string.gallery_section_other),
    CONTACTS_FORM("contacts-form", R.string.gallery_contacts_form, R.string.gallery_section_other),
    SETTINGS("settings", R.string.gallery_settings, R.string.gallery_section_other),
    INVITE("invite", R.string.gallery_invite, R.string.gallery_section_other),
    FAMILY_SETUP("family-setup", R.string.gallery_family_setup, R.string.gallery_section_other),
    PERMISSIONS("permissions", R.string.gallery_permissions, R.string.gallery_section_other),
}

@Composable
private fun UiGallery(request: GalleryRequest?, onExit: () -> Unit) {
    // A tiny back stack of gallery pages (screens link to each other, e.g. Home → Trip).
    var stack by rememberSaveable { mutableStateOf(listOfNotNull(request?.entry?.id)) }
    LaunchedEffect(request) {
        if (request != null && stack != listOf(request.entry.id)) stack = listOf(request.entry.id)
    }
    val current = stack.lastOrNull()?.let { id -> GalleryEntry.entries.firstOrNull { it.id == id } }
    fun open(entry: GalleryEntry) {
        stack = stack + entry.id
    }
    fun back() {
        if (stack.isEmpty()) onExit() else stack = stack.dropLast(1)
    }
    BackHandler(enabled = stack.isNotEmpty()) { back() }
    if (current == null) {
        GalleryIndex(onOpen = ::open)
    } else {
        // key: a page opened again starts fresh (e.g. a new countdown).
        key(current, stack.size, request?.seq) { GalleryPage(current, onBack = ::back, onOpen = ::open) }
    }
}

@Composable
private fun GalleryIndex(onOpen: (GalleryEntry) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Eldering.palette.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ScreenHeader(
            title = stringResource(R.string.gallery_title),
            subtitle = stringResource(R.string.gallery_subtitle),
            onBack = null,
            backLabel = stringResource(R.string.action_back),
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            GalleryEntry.entries.groupBy { it.section }.forEach { (section, entries) ->
                item(key = "section-$section") {
                    Text(
                        stringResource(section),
                        style = Eldering.type.sectionTitle,
                        color = Eldering.palette.ink,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
                    )
                }
                items(entries, key = { it.id }) { entry ->
                    NavigationRow(title = stringResource(entry.title), subtitle = entry.id, onClick = { onOpen(entry) })
                }
            }
        }
    }
}

@Composable
internal fun GalleryPage(entry: GalleryEntry, onBack: () -> Unit, onOpen: (GalleryEntry) -> Unit) {
    val context = LocalContext.current
    val samples = remember { GallerySamples(System.currentTimeMillis()) }
    val actions = remember(context) { LoggingAlertActions(context.applicationContext) }
    val navigate: (Route) -> Unit = { route -> galleryEntryFor(route)?.let(onOpen) ?: log("navigate to $route (not in the gallery)") }
    when (entry) {
        GalleryEntry.HOME -> GalleryHome(samples.home, navigate, onOpen)
        GalleryEntry.HOME_ALERT -> GalleryHome(samples.homeWithAlert, navigate, onOpen)
        GalleryEntry.HOME_LOADING -> GalleryHome(samples.homeLoading, navigate, onOpen)
        GalleryEntry.HOME_NO_FAMILY -> GalleryHome(samples.homeNoFamily, navigate, onOpen)
        GalleryEntry.HOME_OFFLINE -> GalleryHome(samples.homeOffline, navigate, onOpen)
        GalleryEntry.COUNTDOWN, GalleryEntry.COUNTDOWN_VEHICLE -> {
            // A fresh countdown every time the page opens; the real ViewModel, a logging AlertActions.
            val kind = if (entry == GalleryEntry.COUNTDOWN) IncidentKind.PEDESTRIAN_IMPACT else IncidentKind.VEHICLE_CRASH
            val opened = rememberSaveable { System.currentTimeMillis() }
            val viewModel: AccidentAlertViewModel = viewModel(
                key = "gallery-countdown-${entry.id}-$opened",
                factory = AccidentAlertViewModel.factory(samples.countdownInfo(kind), actions),
            )
            AccidentAlertRoute(viewModel, onClose = onBack)
        }
        GalleryEntry.SENT -> AccidentAlertScreen(samples.sent, actions::imOk, {}, actions::callEmergency, {}, onBack)
        GalleryEntry.CANCELLED -> {
            var state by remember { mutableStateOf(samples.cancelled) }
            AccidentAlertScreen(state, {}, {}, {}, { state = state.copy(feedback = it) }, onBack)
        }
        GalleryEntry.TRIP, GalleryEntry.TRIP_EMPTY, GalleryEntry.TRIP_NOT_SHARED -> {
            var state by remember {
                mutableStateOf(
                    when (entry) {
                        GalleryEntry.TRIP -> samples.trip
                        GalleryEntry.TRIP_EMPTY -> samples.tripEmpty
                        else -> samples.tripNotShared
                    },
                )
            }
            TripScreen(
                state,
                onBack = onBack,
                onCursor = { index -> state = state.copy(cursor = index.coerceIn(0, (state.points.size - 1).coerceAtLeast(0))) },
                onRetry = {},
            )
        }
        GalleryEntry.FAMILY_ALERT, GalleryEntry.FAMILY_ALERT_HANDLED, GalleryEntry.FAMILY_ALERT_NO_LOCATION,
        GalleryEntry.FAMILY_ALERT_ERROR,
        -> {
            var state by remember {
                mutableStateOf(
                    when (entry) {
                        GalleryEntry.FAMILY_ALERT -> samples.familyAlert
                        GalleryEntry.FAMILY_ALERT_HANDLED -> samples.familyAlertHandled
                        GalleryEntry.FAMILY_ALERT_NO_LOCATION -> samples.familyAlertNoLocation
                        else -> samples.familyAlertError
                    },
                )
            }
            FamilyAlertScreen(
                state = state,
                nowMs = { System.currentTimeMillis() },
                onBack = onBack,
                onSeeTrail = { onOpen(GalleryEntry.TRIP) },
                onMyWay = { state = state.copy(detail = state.detail?.copy(myAck = com.eldering.safety.network.AckState.ON_MY_WAY)) },
                onMarkSafe = { state = samples.familyAlertHandled },
                onDismissError = { state = state.copy(error = null) },
                onRetry = { log("retry (gallery: no server)") },
            )
        }
        GalleryEntry.SHARING, GalleryEntry.SHARING_ERROR -> GallerySharing(
            if (entry == GalleryEntry.SHARING) samples.sharing else samples.sharingError,
            onBack = onBack,
            onOpen = onOpen,
        )
        GalleryEntry.CONTACTS, GalleryEntry.CONTACTS_EMPTY, GalleryEntry.CONTACTS_FORM -> {
            val state = when (entry) {
                GalleryEntry.CONTACTS -> samples.contacts
                GalleryEntry.CONTACTS_EMPTY -> samples.contactsEmpty
                else -> samples.contactsForm
            }
            EmergencyContactsScreen(
                state = state,
                onBack = onBack,
                onAdd = { onOpen(GalleryEntry.CONTACTS_FORM) },
                onPick = { log("pick contact") },
                onEdit = { onOpen(GalleryEntry.CONTACTS_FORM) },
                onSms = { _, _ -> },
                onRemove = {},
                onName = {},
                onPhone = {},
                onRelation = {},
                onSave = { log("save contact (gallery: not stored)") },
                onCancel = onBack,
            )
        }
        GalleryEntry.SETTINGS -> SettingsScreen(
            session = Session("sample-me", "asha@example.com", "Asha", accessToken = "", accessExpiresAtMs = 0, refreshToken = ""),
            signingOut = false,
            onBack = onBack,
            onNavigate = navigate,
            onSignOut = { log("sign out (gallery: ignored)") },
        )
        GalleryEntry.INVITE -> InviteScreen(samples.invite, onboarding = false, onBack = onBack, onContinue = onBack, onNewCode = {}, onRetry = {})
        GalleryEntry.FAMILY_SETUP -> {
            var state by remember { mutableStateOf(FamilySetupUiState()) }
            FamilySetupScreen(
                state = state,
                onBack = onBack,
                onFamilyName = { state = state.copy(familyName = it, nameError = null) },
                onCode = { state = state.copy(code = it.uppercase(), codeError = null) },
                onCreate = { if (state.familyName.isBlank()) state = state.copy(nameError = R.string.family_error_name_blank) },
                onJoin = { state = state.copy(codeError = R.string.family_error_code_format) },
            )
        }
        GalleryEntry.PERMISSIONS -> PermissionsRoute(onboarding = false, onFinished = onBack, onBack = onBack, startProtection = false)
    }
}

@Composable
private fun GalleryHome(initial: HomeUiState, navigate: (Route) -> Unit, onOpen: (GalleryEntry) -> Unit) {
    var state by remember(initial) { mutableStateOf(initial) }
    var cameraSeq by remember { mutableIntStateOf(100) }
    HomeScreen(
        state = state,
        actions = HomeActions(
            onSelect = { id -> state = state.copy(selectedId = if (state.selectedId == id) null else id) },
            onCenterOnMe = {
                val me = state.me.position ?: return@HomeActions
                state = state.copy(camera = com.eldering.safety.ui.map.CameraRequest(com.eldering.safety.ui.map.CameraTarget.Center(me), id = ++cameraSeq))
            },
            onNavigate = navigate,
            // Hold-to-SOS only shows the "sent" page here; nothing is raised.
            onSos = { onOpen(GalleryEntry.SENT) },
            onResolveOwn = {},
            onRetry = { state = state.copy(error = null) },
        ),
    )
}

@Composable
private fun GallerySharing(initial: com.eldering.safety.ui.sharing.SharingUiState, onBack: () -> Unit, onOpen: (GalleryEntry) -> Unit) {
    var state by remember(initial) { mutableStateOf(initial) }
    fun settings(transform: (com.eldering.safety.network.SettingsDto) -> com.eldering.safety.network.SettingsDto) {
        state.settings?.let { state = state.copy(settings = transform(it)) }
    }
    SharingScreen(
        state = state,
        actions = SharingActions(
            onBack = onBack,
            onShareLive = { on ->
                val s = if (on) SharingState.ON else SharingState.OFF
                settings { it.copy(sharingState = s, effectiveSharingState = s, pausedUntil = null) }
            },
            onPauseHour = {
                settings {
                    it.copy(
                        sharingState = SharingState.PAUSED,
                        effectiveSharingState = SharingState.PAUSED,
                        pausedUntil = Instant.ofEpochMilli(System.currentTimeMillis() + 3_600_000),
                    )
                }
            },
            onPauseUntilResumed = {
                settings { it.copy(sharingState = SharingState.PAUSED, effectiveSharingState = SharingState.PAUSED, pausedUntil = null) }
            },
            onAlwaysAlert = { on -> settings { it.copy(alwaysAlertOnAccident = on) } },
            onLevel = { key, level: GrantLevel ->
                state = state.copy(people = state.people.map { if (it.key == key) it.copy(level = level) else it })
            },
            onAddContact = { onOpen(GalleryEntry.CONTACTS) },
            onSettings = { onOpen(GalleryEntry.SETTINGS) },
            onRetry = { log("retry (gallery: no server)") },
            onDismissError = { state = state.copy(error = null) },
        ),
    )
}

private fun galleryEntryFor(route: Route): GalleryEntry? = when (route) {
    is Route.Trip -> if (route.userId == null) GalleryEntry.TRIP_EMPTY else GalleryEntry.TRIP
    is Route.FamilyAlert -> GalleryEntry.FAMILY_ALERT
    Route.Sharing -> GalleryEntry.SHARING
    Route.Contacts -> GalleryEntry.CONTACTS
    Route.Settings -> GalleryEntry.SETTINGS
    is Route.Invite -> GalleryEntry.INVITE
    is Route.FamilySetup -> GalleryEntry.FAMILY_SETUP
    is Route.Permissions -> GalleryEntry.PERMISSIONS
    Route.Sos -> GalleryEntry.SENT
    else -> null
}

private const val TAG = "UiGallery"

private fun log(message: String) {
    Log.i(TAG, message)
}

/** The gallery's countdown never alerts anyone and never opens the dialer: it only logs. */
private class LoggingAlertActions(private val context: Context) : AlertActions {
    override fun imOk() = log("AlertActions.imOk (gallery: nothing to cancel)")

    override fun sendNow() = log("AlertActions.sendNow (gallery: dry run, nothing sent)")

    override fun callEmergency() {
        log("AlertActions.callEmergency (gallery: dialer not opened)")
        Toast.makeText(context, R.string.gallery_dialer_toast, Toast.LENGTH_SHORT).show()
    }
}
