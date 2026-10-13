package com.eldering.safety.core

import android.content.Context
import android.os.Build
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.eldering.safety.BuildConfig
import com.eldering.safety.ElderingApp
import com.eldering.safety.alert.AlertController
import com.eldering.safety.alert.AndroidAlertUi
import com.eldering.safety.alert.AndroidSmsSender
import com.eldering.safety.alert.ControllerSosTrigger
import com.eldering.safety.alert.IncidentSender
import com.eldering.safety.alert.LandmarkResolver
import com.eldering.safety.alert.PowerWakeHold
import com.eldering.safety.alert.RoomDetectionJournal
import com.eldering.safety.alert.SmsSender
import com.eldering.safety.alert.SosTrigger
import com.eldering.safety.alert.TrailFreezer
import com.eldering.safety.alert.isOnline
import com.eldering.safety.data.db.ElderingDatabase
import com.eldering.safety.data.outbox.OutboxProcessor
import com.eldering.safety.data.outbox.OutboxScheduler
import com.eldering.safety.data.outbox.WorkManagerOutboxScheduler
import com.eldering.safety.data.repository.AuthRepository
import com.eldering.safety.data.repository.ContactsRepository
import com.eldering.safety.data.repository.DetectionEventRepository
import com.eldering.safety.data.repository.FamilyRepository
import com.eldering.safety.data.repository.IncidentRepository
import com.eldering.safety.data.repository.LocationRepository
import com.eldering.safety.data.repository.MeStore
import com.eldering.safety.data.repository.SharingRepository
import com.eldering.safety.data.session.AesGcmCipher
import com.eldering.safety.data.session.AndroidKeystoreKeys
import com.eldering.safety.data.session.SessionStore
import com.eldering.safety.data.settings.AppPreferences
import com.eldering.safety.data.trail.TrailStore
import com.eldering.safety.network.ElderingApi
import com.eldering.safety.network.ElderingNetwork
import com.eldering.safety.network.NetworkModule
import com.eldering.safety.service.SafetyRuntime
import com.eldering.safety.ui.platform.BatteryLevelSource
import com.eldering.safety.ui.platform.DeviceBatteryLevel
import com.eldering.safety.ui.platform.DeviceMyLocation
import com.eldering.safety.ui.platform.DeviceProtectionStatus
import com.eldering.safety.ui.platform.GeocoderPlaceNamer
import com.eldering.safety.ui.platform.MyLocationSource
import com.eldering.safety.ui.platform.PlaceNamer
import com.eldering.safety.ui.platform.ProtectionStatusSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency-injection root (SPEC: "Manual DI (`AppGraph`)").
 *
 * One instance per process, created by [ElderingApp]. Everything is a `by lazy` singleton so nothing is built
 * until first use: the SafetyService process must start fast, and constructing this class must not touch disk,
 * the Keystore or the network (nor load MapLibre).
 */
class AppGraph(
    context: Context,
    val config: AppConfig = AppConfig.fromBuildConfig(),
) {
    val appContext: Context = context.applicationContext

    val time: TimeSource = TimeSource.System

    /** Process-lifetime scope for work that must outlive a screen (never cancelled). */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // --- Storage ----------------------------------------------------------------------------------------------

    val database: ElderingDatabase by lazy { ElderingDatabase.build(appContext) }

    val sessionStore: SessionStore by lazy {
        SessionStore(
            dataStore = PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { appContext.preferencesDataStoreFile(SESSION_DATASTORE) },
            ),
            cipher = AesGcmCipher(
                keyProvider = { AndroidKeystoreKeys.getOrCreateAesKey(SessionStore.KEY_ALIAS) },
                associatedData = SessionStore.ASSOCIATED_DATA,
            ),
        )
    }

    val trailStore: TrailStore by lazy { TrailStore(database.trailDao(), time) }

    /** Device preferences, including the debug-only "Allow real SMS" switch (see [AppPreferences.allowRealSms]). */
    val appPreferences: AppPreferences by lazy {
        AppPreferences(
            dataStore = PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { appContext.preferencesDataStoreFile(AppPreferences.FILE_NAME) },
            ),
            isDebugBuild = config.isDebug,
        )
    }

    // --- Network ----------------------------------------------------------------------------------------------

    val network: ElderingNetwork by lazy {
        ElderingNetwork(
            baseUrl = config.baseUrl,
            sessions = sessionStore,
            time = time,
            baseClient = NetworkModule.baseClient(debugLogging = config.isDebug, userAgent = userAgent()),
        )
    }

    val api: ElderingApi get() = network.api

    // --- Repositories -----------------------------------------------------------------------------------------

    val meStore: MeStore by lazy { MeStore() }

    val authRepository: AuthRepository by lazy {
        AuthRepository(
            api, sessionStore, meStore, time,
            onAccountDataDiscarded = { userId ->
                incidentRepository.discardAccountData(userId)
                locationRepository.clear()
            },
            // Uploads queued before a session expired resume once the same account signs in again.
            onSignedIn = { outboxScheduler.schedule() },
        )
    }

    val familyRepository: FamilyRepository by lazy { FamilyRepository(api, meStore, sessionStore) }

    val sharingRepository: SharingRepository by lazy { SharingRepository(api, meStore) }

    val locationRepository: LocationRepository by lazy { LocationRepository(api, time) }

    val outboxScheduler: OutboxScheduler by lazy { WorkManagerOutboxScheduler(appContext) }

    val outboxProcessor: OutboxProcessor by lazy { OutboxProcessor(api, database, sessionStore, time) }

    val incidentRepository: IncidentRepository by lazy {
        IncidentRepository(api, database, sessionStore, outboxProcessor, outboxScheduler, time)
    }

    val contactsRepository: ContactsRepository by lazy { ContactsRepository(database.emergencyContactDao(), time) }

    val detectionEventRepository: DetectionEventRepository by lazy {
        DetectionEventRepository(database.detectionEventDao(), time)
    }

    // --- Alerts -------------------------------------------------------------------------------------------------

    /** The running SafetyService, if any (receivers and debug tools reach it through this). */
    val safetyRuntime: SafetyRuntime = SafetyRuntime()

    /**
     * Accident SMS. Debug builds only log the exact text (tag `EldSms`) unless "Allow real SMS" is switched on in
     * Settings; release builds always send (SPEC "Test safety").
     */
    val smsSender: SmsSender by lazy { AndroidSmsSender(appContext) { appPreferences.realSmsAllowed() } }

    val incidentSender: IncidentSender by lazy {
        IncidentSender(
            trailStore = trailStore,
            incidents = incidentRepository,
            recipients = { contactsRepository.smsRecipients() },
            sms = smsSender,
            landmarks = LandmarkResolver { lat, lng -> placeNamer.nameFor(lat, lng) },
            isOnline = { isOnline(appContext) },
            senderName = { sessionStore.current()?.displayName },
            scope = applicationScope,
            time = time,
        )
    }

    /** Countdown → IncidentSender; implements the countdown screen's AlertActions. One per process. */
    val alertController: AlertController by lazy {
        AlertController(
            scope = applicationScope,
            ui = AndroidAlertUi(appContext),
            dispatcher = incidentSender,
            journal = RoomDetectionJournal(detectionEventRepository) { trailStore.latest() },
            freezer = TrailFreezer { atMs -> trailStore.snapshot(atMs) },
            contactCount = { contactsRepository.smsRecipients().size },
            wake = PowerWakeHold(appContext),
            time = time,
            wallFromElapsedNanos = { elapsedRealtimeNanosToWallMillis(it) },
        )
    }

    /** Hold-to-SOS on Home: the same path as an accident (upload, SMS, live updates), without the countdown. */
    val sosTrigger: SosTrigger by lazy { ControllerSosTrigger(alertController) }

    // --- UI support (device state the screens show) ------------------------------------------------------------

    val placeNamer: PlaceNamer by lazy { GeocoderPlaceNamer(appContext) }

    val myLocation: MyLocationSource by lazy { DeviceMyLocation(appContext, trailStore, time) }

    val protectionStatus: ProtectionStatusSource by lazy { DeviceProtectionStatus(appContext, trailStore, time) }

    val batteryLevel: BatteryLevelSource by lazy { DeviceBatteryLevel(appContext) }

    private fun userAgent(): String =
        "Eldering/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; SDK ${Build.VERSION.SDK_INT})"
            .filter { it in ' '..'~' } // header values must be printable ASCII

    private companion object {
        const val SESSION_DATASTORE = "session"
    }
}

/** The process-wide [AppGraph]. */
val Context.appGraph: AppGraph
    get() = (applicationContext as ElderingApp).graph
