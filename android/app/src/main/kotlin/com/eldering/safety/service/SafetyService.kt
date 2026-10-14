package com.eldering.safety.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.eldering.detection.DetectionEvent
import com.eldering.detection.Mode
import com.eldering.safety.alert.AlertState
import com.eldering.safety.core.appGraph
import com.eldering.safety.core.catchingNonCancellation
import com.eldering.safety.core.elapsedRealtimeNanosToWallMillis
import com.eldering.safety.data.trail.TrailPoint
import com.eldering.safety.data.trail.TrailSamplingPolicy
import com.eldering.safety.data.trail.isMockFix
import com.eldering.safety.data.trail.toTrailPoint
import com.eldering.safety.ui.common.Formats
import com.eldering.safety.ui.platform.hasLocationPermission
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Background accident protection (SPEC "SafetyService"): a foreground service (`location|specialUse`) that
 * - feeds accelerometer/gyroscope/(barometer) batches per movement mode into the live `AccidentDetector`
 *   ([DetectionEngine]), switching to full rate on a fresh possible accident (not for every running stride) until
 *   the detector's verdict;
 * - records an activity-adaptive Fused-location trail into `TrailStore` (location callbacks also drain the sensor
 *   FIFOs while the screen is off; in STILL significant motion and the keep-alive alarm do);
 * - sends the location heartbeat while sharing is on, polls family alerts when it is not, and raises a
 *   family-alert notification for them;
 * - hands detections to `AlertController` (countdown → IncidentSender);
 * - housekeeping: trail ring buffer, retention of the detection log and own incidents, outbox rescue.
 *
 * Detection keeps running when a session expires (uploads wait for the next sign-in; SMS still go out). An explicit
 * sign-out stops the service and disarms protection (`AppGraph`), and the app only starts it while someone is
 * signed in. Everything here runs on the main thread except the detector, which has its own thread.
 */
class SafetyService : LifecycleService(), SafetyControl {

    private val graph by lazy { appGraph }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var engine: DetectionEngine
    private lateinit var heartbeat: HeartbeatController
    private val modes = ModeResolver()
    private val governor = PossibleAccidentGovernor()
    private val walkingFixes = WalkingFixMonitor()
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }

    private var begun = false
    private var fgsTypes = 0
    private var startReason = "?"
    private var startedAtElapsedMs = 0L
    private var mode: Mode = Mode.UNKNOWN
    private var modeSource = ModeResolver.Source.NONE
    private var batterySaver = false
    /** Null until the session store has answered. */
    private var signedIn: Boolean? = null
    private var alertActive = false
    private var alertSensing = false
    private var locationPlan: LocationPlan? = null
    private var locationError: String? = null
    private var lastFix: TrailPoint? = null
    private val fixes = AtomicInteger()
    private val stored = AtomicInteger()
    private var arRegistered: Boolean? = null

    /** Elapsed-realtime ms until which significant motion keeps STILL out of the STILL tier. */
    private var motionHintUntilMs = Long.MIN_VALUE
    private var lastLocationDrainAtMs = Long.MIN_VALUE
    private var keepAliveDueAtMs = Long.MIN_VALUE
    private var keepAlivePeriodMs = 0L
    private var keepAliveJob: Job? = null
    private var modeCheckAtMs = Long.MIN_VALUE
    private var lastRetentionAtMs = Long.MIN_VALUE
    private var lastGovernorDecision = "-"

    private val holdLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eldering:PossibleAccident").apply { setReferenceCounted(false) }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) = onLocations(result)
    }

    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            batterySaver = powerManager.isPowerSaveMode
            replan("battery saver ${if (batterySaver) "on" else "off"}")
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        ServiceNotifications.ensureChannels(this)
        batterySaver = powerManager.isPowerSaveMode
        if (graph.config.isDebug) modes.forced = ProtectionPrefs.forcedMode(this)
        ProtectionPrefs.recognizedMode(this)?.let { (m, atNanos) ->
            modes.onRecognized(m, if (atNanos > 0) elapsedRealtimeNanosToWallMillis(atNanos) else graph.time.nowMillis())
        }
        engine = DetectionEngine(
            sensorManager = getSystemService(SensorManager::class.java),
            powerManager = powerManager,
            verboseCandidates = graph.config.isDebug,
            onEvent = ::onDetection,
            onPossibleAccident = { t -> main.post { onPossibleAccident(t) } },
            onCandidatesSettled = { emitted -> main.post { onCandidatesSettled(emitted) } },
            onSignificantMotion = { main.post { onSignificantMotion() } },
            onStillness = { still -> main.post { onSensorStillness(still) } },
        )
        heartbeat = HeartbeatController(
            signedIn = { graph.sessionStore.current() != null },
            settings = { graph.meStore.me.value?.settings },
            refreshSettings = { graph.authRepository.refreshMe() },
            send = { point, battery -> graph.locationRepository.sendHeartbeat(point, battery) },
            battery = { graph.batteryLevel.percent() },
            onPendingAlerts = ::onPendingAlerts,
            pollAlerts = { graph.incidentRepository.active() },
            time = graph.time,
            hasFamily = { graph.meStore.me.value?.families?.isNotEmpty() },
        )
        ContextCompat.registerReceiver(
            this,
            powerSaveReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        graph.safetyRuntime.attach(this)
        // An alert or "I'm safe now" stored but never handed to WorkManager (the process died mid-send).
        lifecycleScope.launch(Dispatchers.IO) { runCatching { graph.incidentRepository.scheduleIfPending() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val fromForeground = intent?.getBooleanExtra(EXTRA_FROM_FOREGROUND, false) ?: false
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: "restart"
        val types = FgsTypes.current(this, fromForeground)
        try {
            // Must happen within seconds of startForegroundService().
            ServiceCompat.startForeground(this, ServiceNotifications.ID_ONGOING, ongoingNotification(), types)
        } catch (e: Exception) {
            // API 31+: ForegroundServiceStartNotAllowedException (background start without an exemption);
            // API 34+: SecurityException / InvalidForegroundServiceTypeException.
            Log.w(TAG, "Could not start in the foreground ($reason): ${e.javaClass.simpleName}")
            ProtectionPrefs.setLastPauseReason(this, "${e.javaClass.simpleName} ($reason)")
            ServiceNotifications.showPaused(this)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        fgsTypes = types
        ProtectionPrefs.setLastPauseReason(this, null)
        ServiceNotifications.cancelPaused(this)
        Log.i(TAG, "Foreground ($reason, types=${FgsTypes.describe(types)})")
        if (!begun) {
            begun = true
            startReason = reason
            startedAtElapsedMs = SystemClock.elapsedRealtime()
            begin()
        } else {
            replan("restart: $reason") // e.g. location may be allowed now that the app is in front
        }
        return START_STICKY
    }

    private fun begin() {
        val (m, source) = modes.resolve(graph.time.nowMillis())
        mode = m
        modeSource = source
        engine.start(sensingPlan(), mode)
        applyLocation()
        updateKeepAliveAlarm()
        updateModeCheckAlarm()

        lifecycleScope.launch {
            graph.alertController.state.collect { s -> onAlertState(s) }
        }
        lifecycleScope.launch {
            graph.authRepository.isSignedIn.collect { value ->
                signedIn = value
                refreshNotification()
                if (!value && graph.sessionStore.dataOwner() == null) stopSignedOut()
            }
        }
        lifecycleScope.launch {
            // Sharing switched on in the app (or loaded): send a heartbeat right away; off: nothing more is sent.
            graph.meStore.me.map { it?.settings }.distinctUntilChanged().collect { settings ->
                if (settings == null) return@collect
                heartbeat.onSettingsLoaded()
                if (HeartbeatController.isSharingOn(settings, graph.time.nowMillis()) == true) sendHeartbeat(force = true)
            }
        }
        lifecycleScope.launch {
            arRegistered = TransitionRegistrar.register(this@SafetyService)
        }
        lifecycleScope.launch {
            // Backstop for heartbeats, mode expiry and housekeeping when location callbacks are sparse (stalls in
            // deep sleep; the alarms cover that).
            while (true) {
                delay(TICK_MS)
                updateMode("tick")
                replanIfStale()
                sendHeartbeat(force = false)
                housekeeping()
            }
        }
    }

    /**
     * Nobody is signed in and nobody owns the data on this phone: an explicit sign-out (or one made by an older app
     * version, which left protection armed). A signed-out phone records no trail; the next sign-in starts again.
     * An expired session keeps its owner and does not end up here.
     */
    private fun stopSignedOut() {
        Log.i(TAG, "Nobody signed in: protection stops until the next sign-in")
        ProtectionPrefs.setArmed(this, false)
        stopSelf()
    }

    private suspend fun housekeeping() {
        withContext(Dispatchers.IO) {
            catchingNonCancellation { graph.trailStore.prune() }
            // Outbox rows a racing WorkManager KEEP dropped (or left behind by a killed process) get scheduled again.
            catchingNonCancellation { graph.incidentRepository.scheduleIfPending() }
            // Who my accident alert reaches ("To Mom, Dad (app)" on the countdown): one small request per 30 min.
            if (signedIn == true) catchingNonCancellation { graph.alertRecipients.refreshIfStale() }
            val now = SystemClock.elapsedRealtime()
            if (lastRetentionAtMs == Long.MIN_VALUE || now - lastRetentionAtMs >= RETENTION_EVERY_MS) {
                lastRetentionAtMs = now
                // Documented retention: detection log 30/180 days, own incidents and dead uploads a week.
                val events = catchingNonCancellation { graph.detectionEventRepository.prune() } ?: 0
                val incidents = catchingNonCancellation { graph.incidentRepository.prune() } ?: 0
                if (events + incidents > 0) Log.i(TAG, "Retention: removed $events detection events, $incidents incident rows")
            }
        }
    }

    override fun onDestroy() {
        graph.safetyRuntime.detach(this)
        try {
            unregisterReceiver(powerSaveReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Power-save receiver was not registered")
        }
        fused.removeLocationUpdates(locationCallback)
        HeartbeatAlarmReceiver.cancel(this)
        HeartbeatAlarmReceiver.cancelModeCheck(this)
        engine.stop()
        if (holdLock.isHeld) holdLock.release()
        main.removeCallbacksAndMessages(null)
        Log.i(TAG, "Stopped")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ detection

    /** Detector thread. */
    private fun onDetection(event: DetectionEvent, atMode: Mode, windowJson: String) {
        graph.alertController.onDetection(event, atMode, windowJson)
        main.post { engine.drainNow() }
    }

    /** Main thread. [t0Nanos] = the candidate's peak (sensor time, the elapsedRealtimeNanos base). */
    private fun onPossibleAccident(t0Nanos: Long) {
        val nowMs = SystemClock.elapsedRealtime()
        val t0Ms = t0Nanos / 1_000_000L
        val maxAge = if (mode == Mode.VEHICLE) PossibleAccidentGovernor.VEHICLE_MAX_AGE_MS else PossibleAccidentGovernor.DEFAULT_MAX_AGE_MS
        val decision = governor.onCandidate(nowMs, t0Ms, maxAge)
        lastGovernorDecision = "$decision (candidate ${nowMs - t0Ms} ms old)"
        if (decision != PossibleAccidentGovernor.Decision.ENTER) {
            if (graph.config.isDebug) Log.d(TAG, "Possible accident not escalated: $lastGovernorDecision")
            return
        }
        Log.i(TAG, "Possible accident: full-rate sensing until the verdict (at most ${HOLD_MS / 1000} s)")
        holdLock.acquire(HOLD_MS + 5_000)
        replan("possible accident")
        main.removeCallbacks(holdEnded)
        main.postDelayed(holdEnded, HOLD_MS + 100)
    }

    private val holdEnded = Runnable { endHold("possible-accident hold ended") }

    /** The detector delivered its verdict and nothing is pending: the high rate is no longer needed. */
    private fun onCandidatesSettled(emitted: Boolean) {
        if (emitted) return // an accident: the countdown's own alert tier takes over; the hold just runs out
        if (!governor.isHolding(SystemClock.elapsedRealtime())) return
        main.removeCallbacks(holdEnded)
        endHold("candidate rejected")
    }

    private fun endHold(why: String) {
        governor.endHold(SystemClock.elapsedRealtime())
        if (holdLock.isHeld) holdLock.release()
        replan(why)
    }

    /** STILL tier only: the phone started moving. Drained already; leave STILL sensing for a while. */
    private fun onSignificantMotion() {
        Log.i(TAG, "Significant motion: walking-tier sensing for ${MOTION_HINT_MS / 1000} s")
        motionHintUntilMs = SystemClock.elapsedRealtime() + MOTION_HINT_MS
        main.removeCallbacks(motionHintEnded)
        main.postDelayed(motionHintEnded, MOTION_HINT_MS + 100)
        updateMode("significant motion")
        replan("significant motion")
    }

    private val motionHintEnded = Runnable { replan("motion hint over") }

    private fun motionHintActive(): Boolean = SystemClock.elapsedRealtime() < motionHintUntilMs

    private fun onSensorStillness(still: Boolean) {
        modes.onSensorStill(still)
        updateMode(if (still) "sensors: still" else "sensors: moving")
    }

    private fun onAlertState(state: AlertState) {
        val active = state.isActive
        val sensing = state is AlertState.Counting || state is AlertState.Sending
        if (active != alertActive || sensing != alertSensing) {
            alertActive = active
            alertSensing = sensing
            replan("alert ${state.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ mode & plans

    private fun updateMode(why: String) {
        val (m, source) = modes.resolve(graph.time.nowMillis())
        modeSource = source
        updateModeCheckAlarm()
        if (m == mode) return
        Log.i(TAG, "Mode $mode → $m ($source, $why)")
        mode = m
        engine.setMode(m)
        replan("mode $m")
        refreshNotification()
    }

    private fun isAlerting(): Boolean = alertSensing || governor.isHolding(SystemClock.elapsedRealtime())

    /** The mode the sensing tier follows: STILL counts as moving for a while after significant motion. */
    private fun sensingMode(): Mode = if (mode == Mode.STILL && motionHintActive()) Mode.UNKNOWN else mode

    private fun sensingPlan(): SensingConfig = SensingPlans.sensing(
        mode = sensingMode(),
        alerting = isAlerting(),
        batterySaver = batterySaver,
        hasGyro = engine.pipeline.hasGyro,
        hasPressure = engine.pipeline.hasPressure,
    )

    /**
     * Location: 1 s while a possible accident is held or an alert counts down / is sent; 5 s while a sent alert's
     * live updates run (pushed every 15 s); the mode's plan otherwise, also for an alert without a live session.
     */
    private fun wantedLocationPlan(): LocationPlan {
        val mode = sensingMode()
        val alerting = isAlerting()
        val live = alertActive && !alertSensing
        val now = SystemClock.elapsedRealtime()
        walkingFixes.setActive(SensingPlans.usesWalkingFixes(mode) && !alerting && !live, now)
        return SensingPlans.location(mode, alerting, batterySaver, live, gnss = walkingFixes.wantsGnss(now))
    }

    private fun replan(why: String) {
        if (!begun) return
        engine.applySensing(sensingPlan())
        applyLocation()
        if (isAlerting()) engine.drainNow()
        updateKeepAliveAlarm()
        Log.d(TAG, "Replanned ($why): ${sensingPlan().tier}, location every ${locationPlan?.intervalMs ?: 0} ms")
    }

    /** Holds and hints end on uptime timers, which stall while the CPU sleeps: re-check on every tick. */
    private fun replanIfStale() {
        if (!begun) return
        val sensingStale = engine.pipeline.config?.let { it != sensingPlan() } ?: false
        val locationStale = hasLocationPermission(this) && locationPlan != wantedLocationPlan()
        if (sensingStale || locationStale) replan("tick")
    }

    @SuppressLint("MissingPermission") // FgsTypes / hasLocationPermission checked first
    private fun applyLocation() {
        if (!hasLocationPermission(this)) {
            if (locationPlan != null) fused.removeLocationUpdates(locationCallback)
            locationPlan = null
            locationError = "no location permission"
            return
        }
        val plan = wantedLocationPlan()
        if (plan == locationPlan) return
        val request = LocationRequest.Builder(plan.priority, plan.intervalMs)
            .setMinUpdateIntervalMillis(plan.minIntervalMs)
            .setMaxUpdateDelayMillis(plan.maxDelayMs)
            .setMinUpdateDistanceMeters(plan.minDistanceM)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            // Same callback: replaces the previous request.
            fused.requestLocationUpdates(request, ContextCompat.getMainExecutor(this), locationCallback)
                .addOnFailureListener { e -> locationError = e.javaClass.simpleName }
            locationPlan = plan
            locationError = null
        } catch (e: SecurityException) {
            locationError = "SecurityException"
        }
    }

    private fun onLocations(result: LocationResult) {
        // Signed out explicitly (disarmed, stop pending): record nothing more for the account that just left.
        if (!ProtectionPrefs.isArmed(this)) return
        drainForLocation()
        val alerting = alertActive || isAlerting()
        for (location in result.locations) {
            if (location.isMockFix && !graph.config.isDebug) continue
            fixes.incrementAndGet()
            modes.onSpeed(if (location.hasSpeed()) location.speed else null, graph.time.nowMillis())
            if (location.hasSpeed() && location.elapsedRealtimeNanos > 0) engine.onSpeed(location.speed, location.elapsedRealtimeNanos)
            val point = location.toTrailPoint(mode.toActivity())
            walkingFixes.onFix(SystemClock.elapsedRealtime(), point.accuracyM)
            lastFix = point
            val policy = if (alerting) TrailSamplingPolicy.ALERTING else TrailSamplingPolicy.forActivity(point.activity)
            lifecycleScope.launch(Dispatchers.IO) {
                if (runCatching { graph.trailStore.record(point, policy) }.getOrDefault(false)) stored.incrementAndGet()
                if (alertActive) graph.incidentSender.pokeLive()
            }
        }
        updateMode("speed")
        // Walking fixes good again (or still coarse): switch between balanced and high accuracy right away.
        if (hasLocationPermission(this) && locationPlan != wantedLocationPlan()) replan("walking fix quality ${walkingFixes.state}")
        sendHeartbeat(force = false)
    }

    /**
     * The location wake-up paces sensor FIFO drains while the screen is off, but no more often than the tier
     * batches (1 s fixes during an alert would otherwise flush and wakelock every second for nothing: the alert
     * tier is unbatched and its wakelocks keep the CPU up).
     */
    private fun drainForLocation() {
        val cfg = engine.pipeline.config ?: return
        if (cfg.maxLatencyUs == 0) return
        val now = SystemClock.elapsedRealtime()
        if (lastLocationDrainAtMs != Long.MIN_VALUE && now - lastLocationDrainAtMs < cfg.maxLatencyUs / 1_000L) return
        lastLocationDrainAtMs = now
        engine.drainNow()
    }

    private fun sendHeartbeat(force: Boolean) {
        lifecycleScope.launch {
            val point = lastFix ?: withContext(Dispatchers.IO) { runCatching { graph.trailStore.latest() }.getOrNull() }
            heartbeat.maybeSend(point, mode, batterySaver, force)
        }
    }

    private fun onPendingAlerts(alerts: List<com.eldering.safety.network.IncidentSummaryDto>) {
        val now = graph.time.nowMillis()
        alerts.forEach { alert ->
            if (ProtectionPrefs.markAlertNotified(this, alert.id, now)) {
                Log.i(TAG, "Family alert ${alert.kind} from a family member: notifying")
                val phone = catchingNonCancellation { graph.familyPhoneBook.phoneFor(alert.userId, alert.displayName, emptyList()) }
                ServiceNotifications.showFamilyAlert(this, alert, phone)
            }
        }
    }

    /**
     * The STILL tier has no location wake-ups: the keep-alive alarm drains the accelerometer FIFO at ≤ ½ its depth
     * (unless a wake-up accelerometer does that by itself) and paces the still heartbeat / family-alert poll.
     * Null outside the STILL tier.
     */
    private fun keepAlivePeriod(): Long? {
        if (SensingPlans.tierFor(sensingMode(), isAlerting()) != SensingConfig.Tier.STILL) return null
        // The family-alert poll (2 min) paces the alarm; the 5-min still heartbeat goes out on every few of them.
        val alertPollMs = HeartbeatPolicy().alertPollIntervalMs(Mode.STILL, batterySaver)
        return if (engine.pipeline.hasWakeUpAccel) alertPollMs else minOf(alertPollMs, engine.pipeline.stillDrainIntervalMs)
    }

    /** (Re)schedules the keep-alive alarm; [fired] = it just went off. Leaves a pending alarm alone otherwise. */
    private fun updateKeepAliveAlarm(fired: Boolean = false) {
        val period = keepAlivePeriod()
        if (period == null) {
            if (keepAliveDueAtMs != Long.MIN_VALUE) {
                HeartbeatAlarmReceiver.cancel(this)
                keepAliveDueAtMs = Long.MIN_VALUE
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!fired && keepAliveDueAtMs != Long.MIN_VALUE && period == keepAlivePeriodMs && keepAliveDueAtMs > now) return
        HeartbeatAlarmReceiver.schedule(this, period)
        keepAliveDueAtMs = now + period
        keepAlivePeriodMs = period
    }

    /** While the speed rule holds VEHICLE, an alarm at its staleness deadline (indoors no callback may come). */
    private fun updateModeCheckAlarm() {
        val staleAt = if (modeSource == ModeResolver.Source.SPEED) modes.speedStaleAtMs() else null
        if (staleAt == null) {
            if (modeCheckAtMs != Long.MIN_VALUE) {
                HeartbeatAlarmReceiver.cancelModeCheck(this)
                modeCheckAtMs = Long.MIN_VALUE
            }
            return
        }
        if (modeCheckAtMs != Long.MIN_VALUE && kotlin.math.abs(staleAt - modeCheckAtMs) < MODE_CHECK_SLACK_MS) return
        HeartbeatAlarmReceiver.scheduleModeCheck(this, staleAt - graph.time.nowMillis() + 1_000)
        modeCheckAtMs = staleAt
    }

    // ------------------------------------------------------------------ notification

    private fun ongoingNotification() = ServiceNotifications.ongoing(
        this,
        modeLabel = mode.takeIf { it != Mode.UNKNOWN }?.let { getString(Formats.activityLabel(it.toActivity())) },
        signedIn = signedIn,
    )

    @SuppressLint("MissingPermission") // notify() is a no-op without POST_NOTIFICATIONS
    private fun refreshNotification() {
        if (!begun) return
        try {
            NotificationManagerCompat.from(this).notify(ServiceNotifications.ID_ONGOING, ongoingNotification())
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot update the protection notification")
        }
    }

    // ------------------------------------------------------------------ SafetyControl

    override fun onModeRecognized(mode: Mode) {
        main.post {
            modes.onRecognized(mode, graph.time.nowMillis())
            updateMode("activity recognition")
        }
    }

    override fun forceMode(mode: Mode?) {
        main.post {
            modes.forced = mode
            ProtectionPrefs.setForcedMode(this, mode)
            Log.i(TAG, "Debug: mode ${mode?.let { "forced to $it" } ?: "back to automatic"}")
            updateMode("debug")
        }
    }

    override fun inject(request: DetectionEngine.InjectRequest) = engine.inject(request)

    /**
     * Main thread (alarm receiver). Never ties the broadcast to network I/O: a bounded wakelock covers the drain and
     * the heartbeat, which runs on [lifecycleScope] (the running foreground service keeps the process alive).
     */
    override fun onKeepAliveAlarm() {
        val wake = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eldering:Heartbeat").apply {
            setReferenceCounted(false)
            acquire(KEEP_ALIVE_WAKE_MS)
        }
        engine.drainNow()
        updateMode("keep-alive")
        updateKeepAliveAlarm(fired = true)
        if (keepAliveJob?.isActive == true) {
            releaseAfterDrain(wake) // the previous heartbeat is still waiting on the network
            return
        }
        keepAliveJob = lifecycleScope.launch {
            try {
                val point = lastFix ?: withContext(Dispatchers.IO) { runCatching { graph.trailStore.latest() }.getOrNull() }
                heartbeat.maybeSend(point, mode, batterySaver)
            } finally {
                releaseAfterDrain(wake)
            }
        }
    }

    /** Releases [wake] behind the drain request on the detector thread, so the CPU stays up until the drain holds its own lock. */
    private fun releaseAfterDrain(wake: PowerManager.WakeLock) {
        val posted = engine.handler.post { if (wake.isHeld) wake.release() }
        if (!posted && wake.isHeld) wake.release()
    }

    override fun onModeCheckAlarm() {
        modeCheckAtMs = Long.MIN_VALUE
        updateMode("speed staleness check")
    }

    override suspend fun statusLines(): List<String> {
        val now = graph.time.nowMillis()
        val trail = withTimeoutOrNull(2_000) {
            withContext(Dispatchers.IO) { graph.trailStore.count() to graph.trailStore.latest() }
        }
        val settings = graph.meStore.me.value?.settings
        val alert = graph.alertController.state.value
        val plan = locationPlan
        return listOf(
            "service: running=true foreground=${FgsTypes.describe(fgsTypes)} reason=$startReason " +
                "uptime=${(SystemClock.elapsedRealtime() - startedAtElapsedMs) / 1000}s armed=${ProtectionPrefs.isArmed(this)} " +
                "batterySaver=$batterySaver ignoringBatteryOptimizations=${ServiceNotifications.ignoresBatteryOptimizations(this)} " +
                "notifications=${ServiceNotifications.canNotify(this)}",
            "mode: $mode source=$modeSource recognized=${modes.recognized ?: "-"} forced=${modes.forced ?: "-"} " +
                "sensorStill=${engine.sensorStill} motionHint=${motionHintActive()} " +
                "alerting=${isAlerting()} activityRecognition=${when (arRegistered) { true -> "registered"; false -> "unavailable"; null -> "pending" }}",
            "governor: last=$lastGovernorDecision holding=${governor.isHolding(SystemClock.elapsedRealtime())} " +
                "budgetUsed=${governor.usedMs(SystemClock.elapsedRealtime()) / 1000}s " +
                "keepAlive=${if (keepAliveDueAtMs == Long.MIN_VALUE) "off" else "every ${keepAlivePeriodMs / 1000}s"} " +
                "modeCheck=${if (modeCheckAtMs == Long.MIN_VALUE) "off" else Instant.ofEpochMilli(modeCheckAtMs).toString()}",
            *engine.statusLines().toTypedArray(),
            "location: ${plan?.let { "priority=${it.priority} every=${it.intervalMs}ms" } ?: "off"} walkingFix=${walkingFixes.state} error=${locationError ?: "-"} " +
                "fixes=${fixes.get()} stored=${stored.get()} lastFix=${lastFix?.let { describe(it, now) } ?: "-"}",
            "trail: size=${trail?.first ?: "?"} latest=${trail?.second?.let { describe(it, now) } ?: "-"}",
            "account: signedIn=$signedIn sharing=${settings?.effectiveSharingState ?: "unknown"} " +
                "heartbeat=${heartbeat.lastOutcome} last=${heartbeat.last?.let { Instant.ofEpochMilli(it.atMs) } ?: "-"} " +
                "alertPoll=${heartbeat.lastPollOutcome}",
            "alert: ${alert.javaClass.simpleName}${alert.alert?.let { " ${it.kind} id=${it.id}" } ?: ""} live=${graph.incidentSender.liveIncidents.size}",
        )
    }

    private fun describe(p: TrailPoint, now: Long): String = String.format(
        Locale.ROOT, "%.4f,%.4f ±%sm age=%ds act=%s", p.lat, p.lng, p.accuracyM?.let { Math.round(it) } ?: "?",
        (now - p.recordedAtMs) / 1000, p.activity ?: "-",
    )

    companion object {
        private const val TAG = "SafetyService"
        private const val EXTRA_REASON = "com.eldering.safety.extra.REASON"
        private const val EXTRA_FROM_FOREGROUND = "com.eldering.safety.extra.FROM_FOREGROUND"
        private const val TICK_MS = 30_000L
        private const val HOLD_MS = 30_000L

        /** After significant motion in STILL, walking-tier sensing (location callbacks pace drains) this long. */
        private const val MOTION_HINT_MS = 2 * 60_000L

        /** Drain + heartbeat after a keep-alive alarm; released as soon as the heartbeat is done. */
        private const val KEEP_ALIVE_WAKE_MS = 60_000L

        private const val MODE_CHECK_SLACK_MS = 30_000L
        private const val RETENTION_EVERY_MS = 60 * 60_000L

        /**
         * Starts (or refreshes) protection. [fromForeground] = called while one of our activities is visible, which
         * also allows the `location` FGS type without background-location access. A background start that Android
         * refuses posts "Protection paused — tap to resume" instead of crashing.
         */
        fun start(ctx: Context, reason: String, fromForeground: Boolean = false): Boolean {
            val intent = Intent(ctx, SafetyService::class.java)
                .putExtra(EXTRA_REASON, reason)
                .putExtra(EXTRA_FROM_FOREGROUND, fromForeground)
            return try {
                ContextCompat.startForegroundService(ctx, intent)
                true
            } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException extends ISE
                Log.w(TAG, "Start refused ($reason): ${e.javaClass.simpleName}")
                ProtectionPrefs.setLastPauseReason(ctx, "${e.javaClass.simpleName} ($reason)")
                ServiceNotifications.showPaused(ctx)
                false
            } catch (e: SecurityException) {
                Log.w(TAG, "Start refused ($reason): SecurityException")
                false
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SafetyService::class.java))
        }

    }
}
