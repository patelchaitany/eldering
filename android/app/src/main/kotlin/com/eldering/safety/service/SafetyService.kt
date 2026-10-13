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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Background accident protection (SPEC "SafetyService"): a foreground service (`location|specialUse`) that
 * - feeds accelerometer/gyroscope/(barometer) batches per movement mode into the live `AccidentDetector`
 *   ([DetectionEngine]), switching to full rate on a possible accident;
 * - records an activity-adaptive Fused-location trail into `TrailStore` (location callbacks also drain the sensor
 *   FIFOs while the screen is off);
 * - sends the location heartbeat while sharing is on and raises a family-alert notification for its
 *   `pending_alerts`;
 * - hands detections to `AlertController` (countdown → IncidentSender).
 *
 * Detection runs whether or not anyone is signed in (uploads are then skipped; SMS still go out). Everything
 * here runs on the main thread except the detector, which has its own thread.
 */
class SafetyService : LifecycleService(), SafetyControl {

    private val graph by lazy { appGraph }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var engine: DetectionEngine
    private lateinit var heartbeat: HeartbeatController
    private val modes = ModeResolver()
    private val governor = PossibleAccidentGovernor()
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
        ProtectionPrefs.recognizedMode(this)?.let { (m, _) -> modes.onRecognized(m) }
        engine = DetectionEngine(
            sensorManager = getSystemService(SensorManager::class.java),
            powerManager = powerManager,
            verboseCandidates = graph.config.isDebug,
            onEvent = ::onDetection,
            onPossibleAccident = { main.post { onPossibleAccident() } },
        )
        heartbeat = HeartbeatController(
            signedIn = { graph.sessionStore.current() != null },
            settings = { graph.meStore.me.value?.settings },
            refreshSettings = { graph.authRepository.refreshMe() },
            send = { point, battery -> graph.locationRepository.sendHeartbeat(point, battery) },
            battery = { graph.batteryLevel.percent() },
            onPendingAlerts = ::onPendingAlerts,
            time = graph.time,
        )
        ContextCompat.registerReceiver(
            this,
            powerSaveReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        graph.safetyRuntime.attach(this)
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

        lifecycleScope.launch {
            graph.alertController.state.collect { s -> onAlertState(s) }
        }
        lifecycleScope.launch {
            graph.authRepository.isSignedIn.collect { value ->
                signedIn = value
                refreshNotification()
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
            // Backstop for heartbeats and housekeeping when location callbacks are sparse (stalls in deep sleep).
            while (true) {
                delay(TICK_MS)
                sendHeartbeat(force = false)
                withContext(Dispatchers.IO) {
                    runCatching { graph.trailStore.prune() }
                }
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

    private fun onPossibleAccident() {
        val nowMs = SystemClock.elapsedRealtime()
        if (!governor.onCandidate(nowMs)) return
        Log.i(TAG, "Possible accident: full-rate sensing for ${HOLD_MS / 1000} s")
        holdLock.acquire(HOLD_MS + 5_000)
        replan("possible accident")
        main.postDelayed({ replan("possible-accident hold ended") }, HOLD_MS + 100)
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
        if (m == mode) return
        Log.i(TAG, "Mode $mode → $m ($source, $why)")
        mode = m
        engine.setMode(m)
        replan("mode $m")
        refreshNotification()
        updateKeepAliveAlarm()
    }

    private fun isAlerting(): Boolean = alertSensing || governor.isHolding(SystemClock.elapsedRealtime())

    private fun sensingPlan(): SensingConfig = SensingPlans.sensing(
        mode = mode,
        alerting = isAlerting(),
        batterySaver = batterySaver,
        hasGyro = engine.pipeline.hasGyro,
        hasPressure = engine.pipeline.hasPressure,
    )

    private fun replan(why: String) {
        if (!begun) return
        engine.applySensing(sensingPlan())
        applyLocation()
        if (isAlerting()) engine.drainNow()
        Log.d(TAG, "Replanned ($why): ${sensingPlan().tier}, location every ${locationPlan?.intervalMs ?: 0} ms")
    }

    @SuppressLint("MissingPermission") // FgsTypes / hasLocationPermission checked first
    private fun applyLocation() {
        if (!hasLocationPermission(this)) {
            if (locationPlan != null) fused.removeLocationUpdates(locationCallback)
            locationPlan = null
            locationError = "no location permission"
            return
        }
        val plan = SensingPlans.location(mode, alerting = alertActive || isAlerting(), batterySaver = batterySaver)
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
        engine.drainNow() // the location wake-up paces sensor FIFO drains (screen off)
        val alerting = alertActive || isAlerting()
        for (location in result.locations) {
            if (location.isMockFix && !graph.config.isDebug) continue
            fixes.incrementAndGet()
            modes.onSpeed(if (location.hasSpeed()) location.speed else null, graph.time.nowMillis())
            if (location.hasSpeed() && location.elapsedRealtimeNanos > 0) engine.onSpeed(location.speed, location.elapsedRealtimeNanos)
            val point = location.toTrailPoint(mode.toActivity())
            lastFix = point
            val policy = if (alerting) TrailSamplingPolicy.ALERTING else TrailSamplingPolicy.forActivity(point.activity)
            lifecycleScope.launch(Dispatchers.IO) {
                if (runCatching { graph.trailStore.record(point, policy) }.getOrDefault(false)) stored.incrementAndGet()
                if (alertActive) graph.incidentSender.pokeLive()
            }
        }
        updateMode("speed")
        sendHeartbeat(force = false)
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
                ServiceNotifications.showFamilyAlert(this, alert)
            }
        }
    }

    private fun updateKeepAliveAlarm() {
        if (mode == Mode.STILL) {
            HeartbeatAlarmReceiver.schedule(this, HeartbeatPolicy().intervalMs(Mode.STILL, batterySaver))
        } else {
            HeartbeatAlarmReceiver.cancel(this)
        }
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
            modes.onRecognized(mode)
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

    override fun onKeepAliveAlarm(done: () -> Unit) {
        main.post {
            val wake = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eldering:Heartbeat").apply { acquire(10_000) }
            engine.drainNow()
            lifecycleScope.launch {
                try {
                    val point = lastFix ?: withContext(Dispatchers.IO) { runCatching { graph.trailStore.latest() }.getOrNull() }
                    heartbeat.maybeSend(point, mode, batterySaver)
                } finally {
                    updateKeepAliveAlarm()
                    if (wake.isHeld) wake.release()
                    done()
                }
            }
        }
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
                "alerting=${isAlerting()} activityRecognition=${when (arRegistered) { true -> "registered"; false -> "unavailable"; null -> "pending" }}",
            *engine.statusLines().toTypedArray(),
            "location: ${plan?.let { "priority=${it.priority} every=${it.intervalMs}ms" } ?: "off"} error=${locationError ?: "-"} " +
                "fixes=${fixes.get()} stored=${stored.get()} lastFix=${lastFix?.let { describe(it, now) } ?: "-"}",
            "trail: size=${trail?.first ?: "?"} latest=${trail?.second?.let { describe(it, now) } ?: "-"}",
            "account: signedIn=$signedIn sharing=${settings?.effectiveSharingState ?: "unknown"} " +
                "heartbeat=${heartbeat.lastOutcome} last=${heartbeat.last?.let { Instant.ofEpochMilli(it.atMs) } ?: "-"}",
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
