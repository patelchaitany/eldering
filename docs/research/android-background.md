# Android 16 + OnePlus: background execution for continuous safety sensing

Scope: `:app` pieces in SPEC.md, namely `SafetyService`, activity-transition receiver, boot receiver, `TrailStore` sampling, `AlertController` countdown and `IncidentSender` (Geocoder, SMS).
Target: minSdk 29, target/compile 36, test device OnePlus CPH2717.
As of 2026-10-13.

**Legend**
- **[doc]** Verified on developer.android.com, developers.google.com or Play Console Help in Oct 2026.
- **[src]** Verified in AOSP `main` source.
- **[dev]** Verified read-only on the connected CPH2717 (build `CPH2717_16.0.3.500(EX01)`, Android 16 / SDK 36, OxygenOS 16.0.3, patch 2026-02-01).
- **[I]** My inference.
- **[U]** Unverified. Test it before relying on it.

---

## 0. Decisions (TL;DR)

| Topic | Decision |
|---|---|
| FGS type | Manifest: `location\|specialUse`. At runtime pass `LOCATION\|SPECIAL_USE` when location is allowed for the current start path, otherwise `SPECIAL_USE` only (§2). Plan B if Play rejects `specialUse`: `health`. Its prerequisite is met by `HIGH_SAMPLING_RATE_SENSORS`. |
| Who starts the FGS | Starts come from 4 places: the UI (foreground), `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED`, an Activity-Recognition transition `PendingIntent`, or a tap on a notification action. All four are documented exemptions (§3). Every start is wrapped in try/catch. On failure, post a "Protection paused — tap to resume" notification. |
| CPU sleep | Accel and gyro on this phone are **non-wake-up**. With the screen off the AP suspends and the FIFO wraps, so samples are lost (§4). There is no continuous wakelock (the Play vitals limit is 2 h per 24 h). Instead, **pace FIFO drains with location callbacks**: take a short wakelock, then call `flush()`. In STILL mode, re-arm `TYPE_SIGNIFICANT_MOTION` (a wake-up sensor). |
| Battery-optimization exemption | Request it. Google lists "Safety app" as an acceptable use. It unlocks background FGS starts, unrestricted allow-while-idle alarms, and wakelocks/network in Doze (§4.4). |
| OnePlus | Guide the user through App info → Battery usage → **Allow background activity** + **Auto launch**, and recommend locking the app in Recents. OnePlus' own auto-launch screens are **signature-protected**, so don't deep-link to them. Use the standard intents with fallbacks (§11). |
| androidx.core | **Pin `1.18.0`.** `1.19.x` needs compileSdk 37 and AGP 9.1, which breaks SPEC's compileSdk 36 (§1). |
| SMS | Get the manager with `getSystemService(SmsManager)` → `createForSubscriptionId(getDefaultSmsSubscriptionId())`, then `divideMessage` and `sendMultipartTextMessage`. Sent PIs are immutable. Delivered PIs are mutable and explicit. Debug builds dry-run (§9). |

Suggested SPEC tweaks. The SPEC is otherwise followed; these are proposals for the build agents.
1. **Walking location.** Request every 20 s with `minUpdateDistance=0`, and apply the 20 m filter in `TrailStore`. A 20 m request-side filter suppresses callbacks, and those callbacks pace sensor drains.
2. **Vehicle location.** Use `HIGH_ACCURACY` every 5 s instead of `BALANCED`. The detector's speed-drop feature needs GNSS speed. Measure the battery cost.
3. **Heartbeat and alert polling in STILL.** The SPEC sends no heartbeat in STILL, so a still viewer never polls `pending_alerts`. Add an inexact allow-while-idle alarm, for example every 5 min (§4.4).
4. **Timers.** In-process timers stall while the AP sleeps. Coroutine `delay` and `Handler.postDelayed` run on a monotonic or uptime clock. Drive heartbeats from location callbacks or alarms, not loops.

---

## 1. Dependencies (Google Maven / Maven Central, checked 2026-10-13)

```kotlin
// android/app/build.gradle.kts
dependencies {
    implementation("com.google.android.gms:play-services-location:21.4.0")   // FLP + Activity Recognition; minSdk 23
    implementation("androidx.core:core-ktx:1.18.0")        // ServiceCompat, NotificationCompat. NOT 1.19.x (minCompileSdk=37, AGP>=9.1)
    implementation("androidx.work:work-runtime-ktx:2.12.0")  // minCompileSdk 35
    implementation("androidx.lifecycle:lifecycle-service:2.11.0") // LifecycleService; minCompileSdk 34
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0") // Task.await(); pulls kotlin-stdlib 2.2.20
}
```

AAR metadata, from `aar-metadata.properties` [dev, downloaded]:

| Artifact | minCompileSdk | AGP floor |
|---|---|---|
| `core:1.19.1` | 37 | 9.1.0 |
| `core:1.18.0` | 36 | 8.9.1 |
| `work-runtime:2.12.0` | 35 | — |
| `lifecycle-service:2.11.0` | 34 | — |

The local SDK has only platforms 35 and 36.

**Compile check (2026-10-13).** All Kotlin snippets in this file, plus the §2.1 manifest, were extracted into a scratch Android library and built with stubs for SPEC types. Toolchain: AGP 9.4.1 (built-in Kotlin, KGP 2.2.10), Gradle 9.8.1, JDK 21, compileSdk 36, minSdk 29, with the dependencies above.
- `compileDebugKotlin` and `processDebugManifest`: **BUILD SUCCESSFUL**.
- `lintDebug`: **0 errors**. Warnings are only `BatteryLife`, `HighSamplingRate` and newer-version nags.
- Gotcha: `android.location.LocationRequest` (API 31) clashes with the GMS one under wildcard imports. Import `com.google.android.gms.location.LocationRequest` explicitly.

---

## 2. Manifest and the FGS type

### 2.1 Manifest (`src/main/AndroidManifest.xml`)

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- Location -->
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
    <!-- Activity recognition (runtime, API 29+) -->
    <uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
    <!-- >200 Hz motion sensors; protection=normal [dev]; also satisfies the health FGS prerequisite -->
    <uses-permission android:name="android.permission.HIGH_SAMPLING_RATE_SENSORS" />
    <!-- Foreground service -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <!-- Alerts -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />  <!-- normal|appop [dev]; user-granted for non-alarm apps -->
    <uses-permission android:name="android.permission.VIBRATE" />
    <!-- SMS: Play "Physical safety/emergency alerts" exception -->
    <uses-permission android:name="android.permission.SEND_SMS" />
    <uses-feature android:name="android.hardware.telephony" android:required="false" />
    <!-- Lifecycle -->
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application ...>
        <service
            android:name=".service.SafetyService"
            android:exported="false"
            android:foregroundServiceType="location|specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Continuous on-device accident detection (vehicle crash, fall, pedestrian impact) from accelerometer/gyroscope while the user has protection switched on; on detection it shows a cancellable countdown and alerts the user's chosen emergency contacts and family. Interruption means a crash or fall goes unreported." />
        </service>

        <!-- PendingIntent target for Play-services Activity Recognition; PI runs as our uid, so not exported -->
        <receiver android:name=".service.ActivityTransitionReceiver" android:exported="false" />

        <!-- Only protected system actions; shell/apps cannot send BOOT_COMPLETED (verified [dev]: SecurityException uid=2000) -->
        <receiver android:name=".service.BootReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>

        <receiver android:name=".alert.AlertActionReceiver" android:exported="false" />
        <receiver android:name=".alert.SmsStatusReceiver" android:exported="false" />

        <activity
            android:name=".alert.CountdownActivity"
            android:exported="false"
            android:showWhenLocked="true"
            android:turnScreenOn="true"
            android:launchMode="singleTask"
            android:taskAffinity="com.eldering.safety.alert"
            android:excludeFromRecents="true" />
    </application>
</manifest>
```

Notes:
- **Undeclared types.** Passing a type to `startForeground()` that the manifest doesn't declare throws `IllegalArgumentException`. A missing type in the manifest throws `MissingForegroundServiceTypeException`. A missing `FOREGROUND_SERVICE_<TYPE>` permission throws `SecurityException` [doc: fgs/launch, fgs/declare].
- **specialUse appop.** `FOREGROUND_SERVICE_SPECIAL_USE` is `normal|appop` on this device. The appop `FOREGROUND_SERVICE_SPECIAL_USE` exists, so the system or OEM can deny it [dev]. Check with `appops get`.
- **Debug injector.** `DebugInjectReceiver` lives only in `src/debug/AndroidManifest.xml`, per SPEC.
- **Lint (AGP 9.4.1) warnings on this manifest and code, both expected:**
  - `BatteryLife` on `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Justified by "Safety app".
  - `HighSamplingRate`. Kept as the plan-B `health` prerequisite and for an optional 400 Hz ALERT mode. If neither is needed, drop it.

  Suppress each one at its use site with a justification comment. Don't add a blanket suppression.

### 2.2 Type prerequisites (Android 14+ / API 34+) [doc: fgs/service-types]

| Type | Permission | Runtime prerequisite | Play "intended use" |
|---|---|---|---|
| `location` | `FOREGROUND_SERVICE_LOCATION` | Location services ON, plus `ACCESS_COARSE_LOCATION` or `ACCESS_FINE_LOCATION`. It can't be created from the background without `ACCESS_BACKGROUND_LOCATION`. | "user-initiated location sharing" (friend-finding, vehicle tracking), navigation |
| `health` | `FOREGROUND_SERVICE_HEALTH` | One of: `HIGH_SAMPLING_RATE_SENSORS` declared, or granted `READ_HEART_RATE` / `READ_SKIN_TEMPERATURE` / `READ_OXYGEN_SATURATION` / `ACTIVITY_RECOGNITION`. `BODY_SENSORS` counts only on API ≤ 35. On API 36, body-sensor use from the background needs `READ_HEALTH_DATA_IN_BACKGROUND`. | "Syncing of health data for apps in the fitness category" (step counters, exercise trackers) |
| `specialUse` | `FOREGROUND_SERVICE_SPECIAL_USE` | None. Needs the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property, which reviewers read. | "limited scenarios" that fit no other type, and "subject to review" |

- None of these three types has a run-time limit. The 6 h / 24 h cap applies only to `dataSync` and `mediaProcessing` [doc: A15].
- Play publishes no ruling on crash or fall detection [doc: Play 13392821 / 16965181]. Choosing `specialUse` over `health` is [I]: `health` is documented as fitness data sync, and one forum report describes a rejection for "Health – Other" when it was "not required".
- `systemExempted` requires `ROLE_EMERGENCY`, exact-alarm permission, or similar. It is not a sane choice for a Play app [I].
- **Play Console checklist.**
  - FGS declarations, one per type, each with a description, the user impact if interrupted, and a video.
  - Background-location declaration with a ≤30 s video and a prominent disclosure before the prompt.
  - SMS Permissions Declaration under "Physical safety/emergency alerts".
  - Full-screen intent declaration. We are not a calling or alarm app, so there is no default grant and we must "gracefully degrade if denied".
  - Health apps declaration: [U] whether fall detection triggers it.

### 2.3 Starting and promoting `SafetyService`

```kotlin
// service/FgsTypes.kt
object FgsTypes {
    /** Types we may legally claim right now. `fromForeground` = started while an Activity of ours is visible. */
    fun current(ctx: Context, fromForeground: Boolean): Int {   // minSdk 29: FGS types always exist
        val lm = ctx.getSystemService(LocationManager::class.java)
        val fg = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PERMISSION_GRANTED ||
                 ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PERMISSION_GRANTED
        val bg = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PERMISSION_GRANTED
        // PermissionChecker returns GRANTED even in background ([doc] restrictions-bg-start) -> decide on bg perm ourselves.
        var types = 0
        if (fg && LocationManagerCompat.isLocationEnabled(lm) && (bg || fromForeground)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 34) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        return types
    }
}

// service/SafetyService.kt (excerpt)
class SafetyService : LifecycleService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val fromFg = intent?.getBooleanExtra(EXTRA_FROM_FOREGROUND, false) ?: false
        val types = FgsTypes.current(this, fromFg)
        try {
            // Call immediately: the system expects startForeground within seconds of startForegroundService().
            ServiceCompat.startForeground(this, NOTIF_ID_ONGOING, ongoingNotification(), types)
        } catch (e: Exception) {
            // API 31+: ForegroundServiceStartNotAllowedException (bg start, no exemption)
            // API 34+: SecurityException (while-in-use type from bg / missing perm), InvalidForegroundServiceTypeException
            ProtectionStatus.recordPaused(this, reason = e.javaClass.simpleName)
            ProtectionNotifications.showPausedTapToResume(this) // tapping = user interaction exemption
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // ... arm sensors / location per mode
        return START_STICKY // [I] sticky restarts can hit ForegroundServiceStartNotAllowedException unless an exemption
                            // (e.g. battery-optimization exemption) applies -> handled by the catch above.
    }

    /** Call from the Activity's onStart() to upgrade to LOCATION when bg-location is not granted. */
    fun upgradeTypesFromForeground() =
        ServiceCompat.startForeground(this, NOTIF_ID_ONGOING, ongoingNotification(), FgsTypes.current(this, true))

    companion object {
        fun start(ctx: Context, reason: String, fromForeground: Boolean = false) {
            val i = Intent(ctx, SafetyService::class.java)
                .putExtra(EXTRA_REASON, reason).putExtra(EXTRA_FROM_FOREGROUND, fromForeground)
            try { ContextCompat.startForegroundService(ctx, i) }
            catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException extends ISE
                ProtectionNotifications.showPausedTapToResume(ctx)
            }
        }
    }
}
```

Ongoing notification:
- Channel `IMPORTANCE_LOW`.
- Call `setOngoing(true)` and `setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)`. Without it, display can be deferred by about 10 s [doc: Notification#FOREGROUND_SERVICE_IMMEDIATE, API 31].
- On Android 14+ the user *can* swipe it away. It stays non-dismissible only while the phone is locked [doc: A14 all-apps].
- If `POST_NOTIFICATIONS` is denied, the FGS still runs and appears only in the Task Manager [doc: notification-permission].

---

## 3. Starting an FGS from the background (Android 12 → 16)

**Exemptions relevant to us.** Apps targeting 31+ cannot start an FGS from the background except in these cases [doc: restrictions-bg-start]:
- Leaving a user-visible state (an Activity).
- User action on a notification, widget or bubble.
- **"Your app receives a geofencing or activity recognition transition event."**
- **`ACTION_BOOT_COMPLETED`, `ACTION_LOCKED_BOOT_COMPLETED`, `ACTION_MY_PACKAGE_REPLACED`.**
- `ACTION_TIMEZONE_CHANGED`, `ACTION_TIME_CHANGED`, `ACTION_LOCALE_CHANGED`.
- A **high-priority FCM** message. Check `RemoteMessage.getPriority()`, because a message can be downgraded. We don't use FCM; the backend is self-hosted.
- An exact alarm for a user-requested action.
- **"The user turns off battery optimizations for your app."**
- `SYSTEM_ALERT_WINDOW`. When targeting 35+, a visible overlay is also required, so this is useless for us.
- Companion Device Manager permissions, device owner, and similar.

**While-in-use trap** [doc: restrictions-bg-start]
- When targeting 34+, creating a `location` (or camera/mic) FGS from the background throws `SecurityException`.
- The background-start exemptions above do **not** grant while-in-use access. Boot and AR transitions are not in the while-in-use exemption list, which covers only system component, widget, notification, visible-app PI and a few others.
- ⇒ From boot or AR, `LOCATION` is legal only with `ACCESS_BACKGROUND_LOCATION`. With it, a `location` FGS "can access location all the time".
- `specialUse` has no while-in-use restriction. Neither has `health` via `ACTIVITY_RECOGNITION` or `HIGH_SAMPLING_RATE_SENSORS`; only `BODY_SENSORS` is affected.
- Logcat shows: `Foreground service started from background can not have location/camera/microphone access`.

**Boot-time type restrictions.** When targeting 35+, `BOOT_COMPLETED` receivers can't launch `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection` or `microphone` FGS (`ForegroundServiceStartNotAllowedException`) [doc: A15]. `location`, `specialUse` and `health` are allowed.

**Force-stop.** On Android 15+, "the system cancels all an app's pending intents when the app is force-stopped" [doc: A15 all-apps].
- That includes the AR transition PI.
- After a force-stop (OnePlus swipe-kill, Settings → Force stop, Task Manager stop), nothing restarts us until the user opens the app [I: stopped-state apps also miss `BOOT_COMPLETED`].
- Detect it on next launch (§11.4).

**Android 16 (all apps)** [doc: A16 behavior-changes-all]
- JobScheduler/WorkManager runtime quotas now apply even in the active bucket, to jobs started while visible, and to **jobs running alongside an FGS**.
- `setImportantWhileForeground()` is ignored.
- New stop reason `STOP_REASON_TIMEOUT_ABANDONED`. WorkManager users are unaffected.
- **Rule for us:** do incident POST, SMS and live updates **inside `SafetyService`**. Use WorkManager only for offline retry, via `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)` plus a `NetworkType.CONNECTED` constraint. Debug with `WorkInfo.getStopReason()` and `JobScheduler#getPendingJobReasonsHistory`.

**Android 16 (target 36)** [doc: A16 behavior-changes-16]
- `BODY_SENSORS` is replaced by `android.permission.health.*`. This doesn't affect us, since we don't use HR or body sensors.
- Predictive back is on: `onBackPressed` is not called.
- No edge-to-edge opt-out.
- None of these change FGS start rules.

**Android 17 (API 37, released; QPR1/QPR2 betas listed)** [doc: A17 pages, 2026-10-01]. Forward-compat notes:
- **Background audio hardening (all apps).** Audio APIs called outside a valid lifecycle fail silently. When **targeting 37**, the FGS also needs while-in-use capability, *or* exact-alarm permission plus `USAGE_ALARM` streams. The countdown alarm sound must start from a WIU-capable FGS or the visible `CountdownActivity`.
- **Memory limiter.** Exit reason `REASON_OTHER` with `"MemoryLimiter:AnonSwap"`.
- **BAL hardening for `IntentSender`.**
- **SMS OTP 3-hour delay.** Affects reading SMS only, not sending.
- core 1.19 already requires compileSdk 37.

---

## 4. Keeping sensing alive with the screen off (CPU suspend, Doze, wakelocks)

### 4.1 The device facts [dev: `dumpsys sensorservice`]

| Sensor | Vendor | Rate | FIFO (max, reserved) | Wake-up |
|---|---|---|---|---|
| accelerometer | `bmi2xy acc` (Bosch) | 5–400 Hz | (4500, 3000) | **non-wake-up** (no wake-up variant) |
| gyroscope | `bmi2xy gyro` | 5–400 Hz | (4500, 3000) | non-wake-up |
| magnetometer | `mmc5603` | ≤ 50 Hz | no batching | non-wake-up |
| significant_motion | mtk | one-shot | — | **wake-up** |
| step_detector / step_counter | mtk | — | (4500, 100) / none | non-wake-up; need `ACTIVITY_RECOGNITION` |
| pressure | **absent** | | | |

- Vendor-only extras: `qti.sensor.free_fall` (wake-up, special-trigger), `pickup`, `pocket`, `oplus_act_recog`. These are non-public types [U]. Don't depend on them.
- **Batching semantics** [doc: source.android.com batching]:
  - "When a non-wake-up FIFO fills up, it must wrap around … overwriting older events."
  - "`max_report_latency` has no impact on non-wake-up FIFOs while in suspend mode."
  - "As soon as the AP comes out of suspend mode, a batch is produced with the content of all FIFOs."
- ⇒ With the screen off and no wakelock, samples older than the FIFO depth are **lost**. Samples are processed only when *something* wakes the AP.
- **Conservative FIFO depth** = `min(reserved/rate_i, max/Σrates)`:
  - accel 25 Hz alone: min(120, 180) = **120 s**
  - accel + gyro at 50 Hz: min(60, 45) = **45 s**
  - accel + gyro at 100 Hz: min(30, 22.5) = **22 s**

  Whether accel and gyro share the 4500 slots is [U]. Measure with the probe in §12.

### 4.2 Wakelock budget (Play enforcement since 2026-03-01)

- "Excessive" means all partial wakelocks add up to **≥ 2 h in a 24 h period**. It counts while the app is in the background **or running an FGS**. The threshold is crossed when that happens in **> 5 % of sessions over 28 days**. The penalty is a store-listing warning and exclusion from discovery [doc: vitals/excessive-wakelock, updated 2026-09-21; Android Dev Blog 2026-03].
- **Exempt:** wakelocks created by audio, **location** and user-initiated-job APIs. Google's advice for sensors:
  - Use "maxReportLatencyUs of 30 seconds or more".
  - "Process sensor readings during the brief wake-up the system already provides for location updates."
- ⇒ No always-held wakelock in WALKING or VEHICLE.
- Wakelock tag: use a constant string, no PII and no counters [doc: wakelock best-practices]. Tags: `"eldering:SensorDrain"`, `"eldering:Incident"`.
- [U] Whether time spent charging counts toward the metric. If it doesn't, holding a wakelock in VEHICLE while charging is free.

### 4.3 Recommended pipeline: location-paced drain

Location callbacks wake the AP, and that wake is exempt from vitals. On each callback:
1. Take a short wakelock.
2. Call `SensorManager.flush()` so the FIFO batch is delivered and processed.
3. Release the wakelock in `onFlushCompleted`.

Rule: **location interval ≤ ½ × FIFO depth.** Keep `maxUpdateDelayMillis == interval` so FLP doesn't batch the wakes away.

```kotlin
// service/SensorPipeline.kt
class SensorPipeline(
    private val sm: SensorManager,
    pm: PowerManager,
    private val handler: Handler,                    // HandlerThread looper, NOT main
    private val onSample: (SensorSample) -> Unit,
) : SensorEventListener2 {
    private val drainLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eldering:SensorDrain")
        .apply { setReferenceCounted(false) }
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)   // includes gravity, m/s²
    private val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)        // rad/s
    private var registered = 0
    private var pendingFlushes = 0

    val accelMaxRange: Float get() = accel?.maximumRange ?: 0f            // -> AccidentDetector.setAccelMaxRange

    fun apply(cfg: SensingConfig) {
        sm.unregisterListener(this)
        registered = 0
        accel?.let { if (sm.registerListener(this, it, cfg.accelPeriodUs, cfg.maxLatencyUs, handler)) registered++ }
        if (cfg.gyroPeriodUs > 0) gyro?.let {
            if (sm.registerListener(this, it, cfg.gyroPeriodUs, cfg.maxLatencyUs, handler)) registered++
        }
    }

    /** Call on every location callback / significant-motion trigger. */
    fun drainNow() {
        if (registered == 0) return
        drainLock.acquire(3_000)                       // hard timeout; typical hold is tens of ms
        pendingFlushes = registered
        if (!sm.flush(this)) { pendingFlushes = 0; drainLock.release() }
    }

    override fun onFlushCompleted(sensor: Sensor) {   // one per registered sensor
        if (--pendingFlushes <= 0 && drainLock.isHeld) drainLock.release()
    }

    override fun onSensorChanged(e: SensorEvent) {
        val kind = if (e.sensor.type == Sensor.TYPE_GYROSCOPE) SensorKind.GYRO else SensorKind.ACCEL
        // e.timestamp is on the elapsedRealtimeNanos() base -> same base as Location.elapsedRealtimeNanos
        onSample(SensorSample(e.timestamp, kind, e.values[0], e.values[1], e.values[2]))
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    fun stop() { sm.unregisterListener(this); if (drainLock.isHeld) drainLock.release() }
}
```

**Significant-motion wake for STILL mode.** It is one-shot, so re-arm it after each trigger:

```kotlin
private val sigMotion = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
private val sigListener = object : TriggerEventListener() {
    override fun onTrigger(event: TriggerEvent) { pipeline.drainNow(); modeController.onMotionHint(); armSigMotion() }
}
fun armSigMotion() { sigMotion?.let { sm.requestTriggerSensor(sigListener, it) } }
fun disarmSigMotion() { sigMotion?.let { sm.cancelTriggerSensor(sigListener, it) } }
```

- **Possible-accident path.** When the detector's cheap trigger fires:
  1. Acquire `"eldering:Incident"` with a 5-min timeout.
  2. Switch to full rate with latency 0 and request high-accuracy location at 1 s.
  3. Run the window analysis.
  4. Keep the lock through the countdown and the send.

  Both the countdown and the send rely on timers and sockets, which stall without the lock.
- **Inherent screen-off detection latency** is about the pacing interval: ≤ 5 s VEHICLE, ≤ 20 s WALKING. A fall during STILL is seen when significant motion, an AR transition or the screen wakes the AP. Data survives if the event is within the last ~120 s [I].

### 4.4 Doze and allowlist facts (what the battery-optimization exemption buys)

| Mechanism | FGS only | FGS + user battery-opt exemption |
|---|---|---|
| Partial wakelocks in Doze | Kept. Wakelocks are disabled only if the uid is not allowlisted **and** procState > `BOUND_FOREGROUND_SERVICE` [src: `PowerManagerService.setWakeLockDisabledStateLocked`]. | kept |
| Network in Doze / Battery Saver | Allowed: `procState <= BOUND_FOREGROUND_SERVICE` [src: `NetworkPolicyManager.isProcStateAllowedWhileIdleOrPowerSaveMode`] | allowed |
| `setAndAllowWhileIdle` (inexact) | "Compat" quota of **7 per hour** [src: `AlarmManagerService` `DEFAULT_ALLOW_WHILE_IDLE_COMPAT_QUOTA=7`]. Docs still say at most once per 9 min [doc]. | **`FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED`**, no Doze timing limit [src: `isUidPowerSaveUserExempt`] |
| `setExactAndAllowWhileIdle` | Needs `SCHEDULE_EXACT_ALARM` (user grant, denied by default on 14+). 72 per hour. | **Allowed without `SCHEDULE_EXACT_ALARM`**, because allowlisted apps are exempt [src: `isExemptFromExactAlarmPermissionNoLock` → `isAppOnWhitelist`]. Unrestricted in Doze. |
| Background FGS start | Only listed triggers | **Always** (exemption) [doc] |
| JobScheduler / WorkManager in Doze | Deferred to maintenance windows [doc] | (quota still applies on 16) |

- The OnePlus layer can still freeze or kill independently of AOSP rules (§11) [I].
- **STILL-mode alert poll / heartbeat.** Use `AlarmManager.setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, now+5 min, pi)` to a non-exported receiver. In the receiver: `goAsync()`, then a wakelock, then `POST /v1/location/heartbeat`. Without the exemption this degrades to roughly every 9 min in Doze.
- **Calling exact-alarm APIs.** Use them only behind `AlarmManager.canScheduleExactAlarms()`. Otherwise fall back to the inexact call. A `SecurityException` is thrown when the app is neither permitted nor allowlisted [src].

### 4.5 Per-mode configuration (SPEC battery table + the FIFO rule)

```kotlin
data class SensingConfig(val accelPeriodUs: Int, val gyroPeriodUs: Int /*0=off*/, val maxLatencyUs: Int)

val STILL   = SensingConfig(accelPeriodUs = 40_000, gyroPeriodUs = 0,      maxLatencyUs = 30_000_000) // 25 Hz; FIFO ≈120 s; sig-motion armed
val WALKING = SensingConfig(accelPeriodUs = 20_000, gyroPeriodUs = 20_000, maxLatencyUs = 5_000_000)  // 50 Hz; FIFO ≈45 s -> pace ≤20 s
val VEHICLE = SensingConfig(accelPeriodUs = 10_000, gyroPeriodUs = 10_000, maxLatencyUs = 2_000_000)  // 100 Hz; FIFO ≈22 s -> pace ≤10 s
val ALERT   = SensingConfig(accelPeriodUs = 5_000,  gyroPeriodUs = 5_000,  maxLatencyUs = 0)          // 200 Hz = cap w/o permission
```

- **Rate cap.** When targeting 31+, `registerListener` is capped at **200 Hz** and a `SensorDirectChannel` at `RATE_NORMAL` (~50 Hz) unless `HIGH_SAMPLING_RATE_SENSORS` is declared. A higher request without the permission throws `SecurityException`. If the user's **microphone privacy toggle** is off, motion sensors are capped regardless [doc: sensors_overview].
- **AOSP cap constant** `SENSOR_SERVICE_CAPPED_SAMPLING_PERIOD_NS = 5 ms` [src].
- **Sampling period gotcha.** `samplingPeriodUs` values 0–3 are the `SENSOR_DELAY_*` *constants*:

  | Constant | Value | Period |
  |---|---|---|
  | `FASTEST` | 0 | 0 µs |
  | `GAME` | 1 | 20,000 µs |
  | `UI` | 2 | 66,667 µs |
  | `NORMAL` | 3 | 200,000 µs |

  Pass microseconds ≥ 4 [src: `SensorManager.getDelay`]. The period "is only a hint… Usually events are received faster" [doc], so compute dt from `event.timestamp`.
- `registerListener(…, maxReportLatencyUs)`: "Once one of the events in the FIFO needs to be reported, all of the events in the FIFO are reported sequentially". If `fifoMaxEventCount == 0`, this is equivalent to no batching. Don't use it for `TYPE_SIGNIFICANT_MOTION`; use `requestTriggerSensor` [doc: SensorManager].
- `flush()`: "asynchronous and returns immediately… `onFlushCompleted` is called after all the events in the batch at the time of calling… If the hardware doesn't support flush, it still returns true" [doc].
- **Apply a new config on every mode change.** Unregister, then register with the new config. Mode changes come from AR transitions and FLP speed.

---

## 5. Activity Recognition Transition API

Requirements:
- `play-services-location ≥ 12.0.0`; use 21.4.0.
- Runtime permission `android.permission.ACTIVITY_RECOGNITION` on API 29+.
- Supported types: `IN_VEHICLE`, `ON_BICYCLE`, `RUNNING`, `STILL`, `WALKING` × `ENTER`/`EXIT`. Events arrive in chronological order. "The latency of event detection might vary by device" [doc: transitions].

```kotlin
// service/TransitionRegistrar.kt
object TransitionRegistrar {
    private const val ACTION = "com.eldering.safety.ACTIVITY_TRANSITION"
    private const val REQ = 1001

    fun pendingIntent(ctx: Context): PendingIntent {
        // EXPLICIT component: Android 14+ throws on a mutable PI wrapping an implicit intent [doc A14].
        // MUTABLE: Play services fills ActivityTransitionResult into the intent extras (official sample uses FLAG_MUTABLE).
        val intent = Intent(ctx, ActivityTransitionReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(ctx, REQ, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    @RequiresPermission(Manifest.permission.ACTIVITY_RECOGNITION)
    suspend fun register(ctx: Context) {
        val types = intArrayOf(DetectedActivity.IN_VEHICLE, DetectedActivity.ON_BICYCLE,
                               DetectedActivity.RUNNING, DetectedActivity.WALKING, DetectedActivity.STILL)
        val transitions = types.flatMap { t ->
            listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
                ActivityTransition.Builder().setActivityType(t).setActivityTransition(it).build()
            }
        }
        ActivityRecognition.getClient(ctx)
            .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(ctx)).await()
    }

    @RequiresPermission(Manifest.permission.ACTIVITY_RECOGNITION)   // lint MissingPermission: remove* also needs it
    suspend fun unregister(ctx: Context) {
        // NB: official sample wrongly calls removeActivityUpdates(); use the transition variant.
        ActivityRecognition.getClient(ctx).removeActivityTransitionUpdates(pendingIntent(ctx)).await()
    }
}

// service/BootReceiver.kt
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // Check the action (lint UnsafeProtectedBroadcastReceiver): the receiver is exported.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!ProtectionPrefs.isEnabled(ctx)) return               // SharedPreferences mirror: synchronous, no DataStore here
        SafetyService.start(ctx, reason = intent.action!!)       // exempt start; LOCATION only if bg-location granted
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) != PERMISSION_GRANTED) return
        val pending = goAsync()                                  // ~10 s budget for the Play-services call
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try { withTimeout(8_000) { TransitionRegistrar.register(ctx) } }
            catch (e: Exception) { Log.w("BootReceiver", "AR re-register failed", e) }
            finally { pending.finish() }
        }
    }
}

// service/ActivityTransitionReceiver.kt
class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val events = ActivityTransitionResult.extractResult(intent)?.transitionEvents.orEmpty()
        val enter = events.lastOrNull { it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER } ?: return
        val mode = when (enter.activityType) {
            DetectedActivity.IN_VEHICLE -> Mode.VEHICLE
            DetectedActivity.ON_BICYCLE -> Mode.CYCLING
            DetectedActivity.RUNNING -> Mode.RUNNING
            DetectedActivity.WALKING -> Mode.WALKING
            DetectedActivity.STILL -> Mode.STILL
            else -> Mode.UNKNOWN
        }
        ModeStore.put(ctx, mode, enter.elapsedRealTimeNanos)   // SharedPreferences, synchronous & cheap
        if (ProtectionPrefs.isEnabled(ctx)) {
            // Exempt background FGS start; must happen inside onReceive (exemption is short-lived) [I].
            SafetyService.start(ctx, reason = "ar:$mode")
        }
    }
}
```

- **Re-register** on `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` and every app launch. Google advises this for the Sleep API on the same client [doc]; applying it to transitions is [I]. Re-registering is idempotent with the same PI.
- **Dynamically registered receivers** must pass `RECEIVER_EXPORTED` or `RECEIVER_NOT_EXPORTED` when targeting 34+ [doc A14]. We use a manifest receiver, so this doesn't apply.
- **Testing.** No adb command injects real transitions. Add a debug-only action, e.g. `DEBUG_TRANSITION --es activity IN_VEHICLE`, that calls the same handler. Shell broadcasts are *not* an FGS-start exemption, so first run `cmd deviceidle tempwhitelist -d 60000 <pkg>` (§12).

---

## 6. Fused location (`FusedLocationProviderClient`)

From the `LocationRequest.Builder` reference [doc]:
- `setMinUpdateDistanceMeters`: an update less than the distance from the previous one "will not be delivered". Default 0.
- `setMaxUpdateDelayMillis`: allows batching if ≥ 2 × interval. FLP normalises it to `max(delay, interval)`. `flushLocations()` forces delivery.
- `setMinUpdateIntervalMillis`: the default `IMPLICIT_MIN_UPDATE_INTERVAL` equals interval / 2.
- `setWaitForAccurateLocation`: default true for `HIGH_ACCURACY`.

Priorities [doc]:

| Priority | Value | Meaning |
|---|---|---|
| `PRIORITY_HIGH_ACCURACY` | 100 | |
| `PRIORITY_BALANCED_POWER_ACCURACY` | 102 | |
| `PRIORITY_LOW_POWER` | 104 | |
| `PRIORITY_PASSIVE` | 105 | "only receive 'free' locations calculated on behalf of other clients" |

```kotlin
// location/LocationPlans.kt
fun locationRequestFor(mode: Mode, alerting: Boolean): LocationRequest = when {
    alerting -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000)
        .setMinUpdateIntervalMillis(1_000).setWaitForAccurateLocation(false).build()
    mode == Mode.VEHICLE || mode == Mode.CYCLING -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000) // [I] GNSS speed for speed-drop; SPEC says BALANCED 5–10 s
        .setMinUpdateIntervalMillis(2_500).setMaxUpdateDelayMillis(5_000).setWaitForAccurateLocation(false).build()
    mode == Mode.WALKING || mode == Mode.RUNNING -> LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 20_000)
        .setMinUpdateIntervalMillis(10_000).setMinUpdateDistanceMeters(0f)   // 20 m filter applied in TrailStore
        .setMaxUpdateDelayMillis(20_000).build()                             // no batching: callbacks pace sensor drains
    else /* STILL, UNKNOWN */ -> LocationRequest.Builder(Priority.PRIORITY_PASSIVE, 60_000)
        .setMinUpdateIntervalMillis(30_000).build()
}

// in SafetyService
private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
private val locCallback = object : LocationCallback() {
    override fun onLocationResult(r: LocationResult) {
        pipeline.drainNow()                                  // §4.3
        r.locations.forEach { trailStore.offer(it) }         // Location.elapsedRealtimeNanos aligns with SensorEvent.timestamp
        heartbeat.maybeSend(r.lastLocation)                  // piggy-back; take a ≤10 s wakelock for the HTTP call
    }
}
@SuppressLint("MissingPermission") // checked by FgsTypes / permission gate
fun applyLocation(mode: Mode, alerting: Boolean) {
    fused.requestLocationUpdates(locationRequestFor(mode, alerting), locationExecutor, locCallback) // same callback -> replaces previous request
}

// One-shot fix at incident time
@SuppressLint("MissingPermission")
suspend fun freshFix(): Location? = fused.getCurrentLocation(
    CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY)
        .setDurationMillis(10_000).setMaxUpdateAgeMillis(5_000).build(), null).await()
```

- **Approximate-only grant.** Since Android 12 the user may grant only `COARSE`. Handle it by keeping the trail and lowering expectations in the UI.
- **Background location request flow.** Request `FINE`/`COARSE` first, then `ACCESS_BACKGROUND_LOCATION` separately. On API 30+ the second request sends the user to Settings ("Allow all the time"). Show the Play prominent disclosure before it [doc: Play 9799150].
- **Device.** FLP is provided by GMS on the CPH2717 and has an `activity_recognition_provider` attribution tag [dev].

---

## 7. Geocoder (landmarks for about 10 key trail points)

- `Geocoder.getFromLocation(lat, lng, maxResults, GeocodeListener)` was added in **API 33**. The `List<Address>` blocking overload is deprecated in 33.
- `GeocodeListener.onGeocode(List<Address>)` "May return an empty list". `onError(String?)` was added in 33.
- `isPresent()` "true… still no guarantee that any individual geocoding attempt will succeed".
- The docs warn: "**Do NOT use this API for any safety-critical or regulatory compliance purposes.**" [doc]. So landmarks are decoration only. SMS and the server always carry raw coordinates.
- The device has a working backend (`dumpsys location` → "Geocoder Record: RGC succeed") [dev].

```kotlin
// alert/Landmarks.kt
suspend fun Geocoder.landmark(lat: Double, lng: Double, timeoutMs: Long = 5_000): String? {
    if (!Geocoder.isPresent()) return null
    val addresses = withTimeoutOrNull(timeoutMs) {
        if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine<List<Address>> { cont ->
            getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses) }
                override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(emptyList()) }
            })
        } else withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            runCatching { getFromLocation(lat, lng, 1) }.getOrNull().orEmpty()   // blocking; IOException offline
        }
    } ?: return null
    val a = addresses.firstOrNull() ?: return null
    return listOfNotNull(a.featureName?.takeIf { it != a.subThoroughfare }, a.thoroughfare, a.subLocality ?: a.locality)
        .distinct().joinToString(", ").ifBlank { null }
}
```

- **Fallback chain.**
  1. Platform Geocoder (async on 33+), with a 5 s timeout per point, run sequentially.
  2. If that yields nothing, `landmark = null`. Send the point anyway: the server field is nullable and the share page shows coordinates.
  3. Optional later: server-side reverse geocoding. Nominatim's public instance has a strict usage policy, so self-host or skip it [I].
- **Cache** results by lat/lng rounded to 4 decimals (about 11 m) [I].
- **Key points:** the detection point, the oldest point, ≥ 60 s dwell (stop) points, large bearing changes, then fill to about 10 by time spacing [I].

---

## 8. Full-screen-intent countdown (alarm pattern)

Facts:
- `NotificationManager.canUseFullScreenIntent()` (API 34). If permission is denied, the "notification will show up as an expanded heads up notification on lockscreen".
- Request access with `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` (`"android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT"`, data `package:<pkg>`) [doc].
- On the device it resolves to `com.android.settings.AppManageFullScreenIntent` (exported, no permission) [dev].
- While the user is actively using the device, the system shows a heads-up notification instead of launching the activity [doc: time-sensitive].
- Play: default grant goes only to calling and alarm apps, so we must request it and degrade gracefully [doc: Play 13392821].

```kotlin
// alert/AlertNotifications.kt
const val CH_ALARM = "accident_alarm_v1"   // channel settings are immutable after creation -> version the id

fun NotificationManager.ensureAlarmChannel(ctx: Context) {
    val ch = NotificationChannel(CH_ALARM, ctx.getString(R.string.ch_alarm_name), NotificationManager.IMPORTANCE_HIGH).apply {
        setSound(Settings.System.DEFAULT_ALARM_ALERT_URI, AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        enableVibration(true); vibrationPattern = longArrayOf(0, 800, 400, 800)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        setBypassDnd(true)   // [I] honoured only if the app has DND (notification-policy) access; harmless otherwise
    }
    createNotificationChannel(ch)
}

fun showCountdown(ctx: Context, deadlineEpochMs: Long, title: String, text: String) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    val open = PendingIntent.getActivity(ctx, 2001,
        Intent(ctx, CountdownActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun action(a: String, rc: Int) = PendingIntent.getBroadcast(ctx, rc,
        Intent(ctx, AlertActionReceiver::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
    val b = NotificationCompat.Builder(ctx, CH_ALARM)
        .setSmallIcon(R.drawable.ic_alert).setContentTitle(title).setContentText(text)
        .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true)       // sound once; ringing is done by AlarmPlayer
        .setWhen(deadlineEpochMs).setUsesChronometer(true).setChronometerCountDown(true)
        .setContentIntent(open)
        .addAction(0, ctx.getString(R.string.im_ok), action(ACTION_IM_OK, 2002))
        .addAction(0, ctx.getString(R.string.send_help_now), action(ACTION_SEND_NOW, 2003))
    if (Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()) b.setFullScreenIntent(open, true)
    nm.notify(NOTIF_ID_ALARM, b.build())
}

fun fullScreenIntentSettings(ctx: Context): Intent =
    if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:${ctx.packageName}".toUri())
    else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)

// alert/CountdownActivity.kt — manifest already sets showWhenLocked/turnScreenOn (API 27+); belt & braces:
class CountdownActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Do NOT requestDismissKeyguard(): "I'm OK" must work without unlocking.
        // setContent { CountdownScreen(...) }  — state comes from AlertController in SafetyService
    }
}
```

Rules:
- **The countdown timer is owned by `SafetyService`, not the Activity.** Hold the `"eldering:Incident"` wakelock and time against `SystemClock.elapsedRealtime()`. The Activity, the notification actions and the heads-up all only *signal* `AlertController`.
- **Alarm sound.** Play a looping `USAGE_ALARM` ringtone from the FGS for the countdown and stop it on any response. Vibrate with `VibrationAttributes.USAGE_ALARM` (API 33). The countdown shows when the screen is off or the phone is locked. When the user is active, the heads-up with actions is used.
- **If `POST_NOTIFICATIONS` is denied,** neither the full-screen intent nor the heads-up can appear, and there is no legal background Activity launch. Proceed to send after the countdown and warn about this during onboarding [I].
- **Android 17 + target 37:** background audio needs a WIU-capable FGS. See §3.

---

## 9. SMS (`SmsManager`, API 31+)

API facts [doc: SmsManager]:
- `getDefault()` is deprecated in 31. Use `Context.getSystemService(SmsManager.class)`.
- It is "safer to use `getSmsManagerForSubscriptionId(int)`" in the background. On multi-SIM devices without a default, the background operation fails with `RESULT_ERROR_GENERIC_FAILURE` and extra `"noDefault"=true`.
- `createForSubscriptionId(int)` (API 31) "will never cause an SMS disambiguation dialog".
- `SmsManager.getDefaultSmsSubscriptionId()` returns the user default, or the single active sub, or `INVALID_SUBSCRIPTION_ID`.
- `sendMultipartTextMessage(..., List<PendingIntent>, List<PendingIntent>, long messageId)` is API 30. Use one sent PI per part. The result code is `Activity.RESULT_OK` or `RESULT_ERROR_*` / `RESULT_RIL_*`, with optional `"errorCode"` extra.
- The delivery PI carries `"pdu"` (and format) in extras.
- Non-default-SMS apps' sends are auto-written to the SMS provider, so they are visible in Messages.
- The device has `android.hardware.telephony.messaging` [dev].

```kotlin
// alert/SmsSender.kt
class SmsSender(private val ctx: Context, private val realSmsAllowed: () -> Boolean) {

    sealed interface Result { data object DryRun : Result; data object Unsupported : Result; data class Queued(val parts: Int) : Result }

    fun send(to: String, body: String, messageId: Long): Result {
        if (!realSmsAllowed()) {                       // debug builds: false unless "Allow real SMS" is on (SPEC)
            Log.i(TAG, "DRY-RUN SMS id=$messageId to=${to.takeLast(2).padStart(to.length, '•')} len=${body.length}")
            return Result.DryRun
        }
        val feature = if (Build.VERSION.SDK_INT >= 33) PackageManager.FEATURE_TELEPHONY_MESSAGING else PackageManager.FEATURE_TELEPHONY
        if (!ctx.packageManager.hasSystemFeature(feature)) return Result.Unsupported
        val sms = smsManager() ?: return Result.Unsupported
        val parts = sms.divideMessage(body)            // 160 GSM-7 / 70 UCS-2 chars per part; keep text ASCII
        val sent = ArrayList<PendingIntent>(parts.size)
        val delivered = ArrayList<PendingIntent>(parts.size)
        parts.indices.forEach { i ->
            val rc = ((messageId and 0xFFFFF).toInt() shl 4) or (i and 0xF)        // unique per (message, part)
            fun status(action: String) = Intent(ctx, SmsStatusReceiver::class.java).setAction(action)
                .putExtra(EXTRA_MSG_ID, messageId).putExtra(EXTRA_PART, i).putExtra(EXTRA_PARTS, parts.size)
            // resultCode is delivered regardless of mutability -> immutable is enough for "sent"
            sent += PendingIntent.getBroadcast(ctx, rc, status(ACTION_SMS_SENT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            // delivery report data ("pdu","format") arrives as fill-in extras -> MUTABLE + explicit component (A14 rule)
            delivered += PendingIntent.getBroadcast(ctx, rc, status(ACTION_SMS_DELIVERED),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        if (Build.VERSION.SDK_INT >= 30) sms.sendMultipartTextMessage(to, null, parts, sent, delivered, messageId)
        else sms.sendMultipartTextMessage(to, null, parts, sent, delivered)
        return Result.Queued(parts.size)
    }

    /** Subscription-bound manager: never shows the multi-SIM disambiguation dialog in background. minSdk 29 aware. */
    private fun smsManager(): SmsManager? {
        val subId = SmsManager.getDefaultSmsSubscriptionId()           // API 22
        val valid = subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
        return if (Build.VERSION.SDK_INT >= 31) {
            val base = ctx.getSystemService(SmsManager::class.java) ?: return null
            if (valid) base.createForSubscriptionId(subId) else base
        } else {
            @Suppress("DEPRECATION")
            if (valid) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
    }
}

class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_MSG_ID, -1); val part = intent.getIntExtra(EXTRA_PART, -1)
        when (intent.action) {
            ACTION_SMS_SENT -> SmsLog.sent(ctx, id, part, ok = resultCode == Activity.RESULT_OK, code = resultCode)
            ACTION_SMS_DELIVERED -> {
                val pdu = intent.getByteArrayExtra("pdu"); val fmt = intent.getStringExtra("format")
                val status = pdu?.let { SmsMessage.createFromPdu(it, fmt)?.status }
                SmsLog.delivered(ctx, id, part, status)
            }
        }
    }
}
```

Test safety (SPEC):
- Unit tests use a fake `SmsSender`.
- Instrumented and manual tests run debug builds, where `realSmsAllowed` defaults to false.
- **Never** run `pm grant … SEND_SMS` together with the real-SMS toggle on a test run.
- Use `ACTION_DIAL` only, never `ACTION_CALL`. `ACTION_CALL` "cannot be used to call emergency numbers".

---

## 10. Permission walkthrough order (onboarding)

1. `POST_NOTIFICATIONS` (API 33+).
2. `ACCESS_FINE_LOCATION` (+ COARSE).
3. Prominent disclosure, then `ACCESS_BACKGROUND_LOCATION` (opens Settings).
4. `ACTIVITY_RECOGNITION`.
5. `SEND_SMS`, with an explainer.
6. Full-screen intent: `canUseFullScreenIntent()` → `fullScreenIntentSettings()`.
7. Battery optimization: `PowerManager.isIgnoringBatteryOptimizations(pkg)` → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with `package:` data. "Safety app… Acceptable" [doc: doze-standby]. Needs `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
8. OEM step for OnePlus/OPPO/realme (§11).
9. Show status checks on the Sharing & privacy screen:
   - `ActivityManager.isBackgroundRestricted()`: true means the user set "Restricted", so FGS can't start from the background.
   - `UsageStatsManager.appStandbyBucket`.
   - `NotificationManager.areNotificationsEnabled()`.
   - Alarm channel importance.

---

## 11. OnePlus / OxygenOS 16 (CPH2717)

### 11.1 What exists on the device [dev]

- **Packages present:** `com.oplus.battery` (Battery: power, auto-launch), `com.oplus.securitypermission`, `com.oplus.safecenter`, `com.oplus.athena`, `com.oplus.phonemanager`.
- **Legacy components from older guides are not present:** `com.coloros.safecenter/.startupapp.StartupAppListActivity` and `com.oneplus.security/.chainlaunch...`.
- **OnePlus screens are exported but signature-protected.** A third-party app gets `SecurityException`, so do not deep-link to them:

  | Component | Protection |
  |---|---|
  | `com.oplus.battery/com.oplus.startupapp.view.StartupAppListActivity` (Auto launch list) | `oplus.permission.OPLUS_COMPONENT_SAFE` = `signature` |
  | `…startupapp.view.AssociateStartActivity` | same |
  | `…PreventRecordActivity` ("Block records") | same |
  | `…OptimizationAutoStartActivity` | same |
  | `com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerConsumptionActivity` (Battery main) | `OPLUS_COMPONENT_SAFE` |
  | `…PowerUsageModelActivity` (`oplus.intent.action.power_control`) | `com.oplus.permission.safe.SETTINGS` = `signature\|privileged` |
  | `com.oplus.securitypermission/…AppControlCenterActivity` | `com.oplus.permission.safe.SECURITY` = `signature` |

- **Openable by us** (exported Settings activities without a permission):

  | Action | Data | Resolves to |
  |---|---|---|
  | `android.settings.APPLICATION_DETAILS_SETTINGS` | `package:` | `InstalledAppDetails` |
  | `android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL` | `package:` | `fuelgauge.AdvancedPowerUsageDetailActivity` (per-app battery usage page). This is a **hidden** AOSP action, not in the public SDK, but it resolved on the device. |
  | `android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS` | — | `Settings$AppBatteryUsageActivity` |
  | `android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | `package:` | `fuelgauge.RequestIgnoreBatteryOptimizations` |
  | `android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT` | `package:` | |
  | `android.settings.APP_NOTIFICATION_SETTINGS` | | |
  | `android.intent.action.POWER_USAGE_SUMMARY` | | |

- **User-facing labels in this build** (English strings in `com.oplus.battery` / Settings):
  - "Allow background activity"
  - "Allow all background activity for "%1$s"?"
  - "Auto launch"
  - "Auto launch on boot and auto launch in background allowed/disallowed"
  - "Block associated launches to save power…"
  - "Optimize battery use" / "Don't optimize"
  - "Sleep standby optimization"
  - "App Quick Freeze"
  - "Disable lock for recent apps", which implies the Recents lock exists
  - AOSP "Allow background usage", "Unrestricted"
  - The exact menu nesting on 16.0.3 is [U]: the screen was dozing, and nothing was tapped.
- **No OEM "confirm SMS sending" strings** were found in `com.oplus.securitypermission` (global EX01 build). [U] Whether ColorOS-CN-style SMS confirmation exists elsewhere.

### 11.2 Behaviour to expect

- dontkillmyapp rates OnePlus 5/5 (worst). Its guidance:
  - Lock the app in Recents.
  - Battery optimization → Don't optimize.
  - Disable "Deep optimization / Adaptive Battery" and "Sleep standby optimization", which cuts network during learned sleep.
  - Check App auto-launch.

  It says "No known solution on the developer end" [doc: dontkillmyapp.com/oneplus]. Third-party OxygenOS 12/14 guides give the path Settings → Apps → App management → app → **Battery usage** → allow background (and foreground) activity, **Allow auto launch** [web: DriveQuant PDFs].
- [I] "Auto launch" disallowed probably blocks `BOOT_COMPLETED` delivery and "associated" process starts. That likely includes Play-services `PendingIntent` broadcasts into a dead process, which would break AR restarts. **Test:** kill the process, trigger a transition, then check the Battery → Auto launch → Block records screen and `logcat | grep -iE "athena|hans|startup"`.
- [I] Swipe-from-Recents may force-stop. On Android 15+ that cancels all our PendingIntents (§3). Recommend locking in Recents.

### 11.3 Deep links with safe fallbacks

```kotlin
// onboarding/OemBattery.kt
object OemBattery {
    val isOplusFamily: Boolean get() = Build.MANUFACTURER.lowercase() in setOf("oneplus", "oppo", "realme")

    private fun pkgUri(ctx: Context) = "package:${ctx.packageName}".toUri()

    /** Ordered: most specific first. All are public/AOSP; none are OEM signature-protected. */
    fun backgroundActivityIntents(ctx: Context) = listOf(
        Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", pkgUri(ctx)),       // per-app "Battery usage" (hidden AOSP action)
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(ctx)),              // App info -> Battery usage
    )
    fun ignoreOptimizationIntents(ctx: Context) = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri(ctx)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(ctx)),
    )

    /** Try each; never resolveActivity() (package-visibility filtering) — just catch. */
    fun Context.startFirst(intents: List<Intent>): Boolean {
        for (i in intents) try {
            startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true
        } catch (_: ActivityNotFoundException) { } catch (_: SecurityException) { }
        return false
    }
}
```

The OnePlus guidance screen gives checklist copy plus a button for each step:
1. "Battery usage → turn on **Allow background activity** and **Auto launch**" (button: `backgroundActivityIntents`).
2. "Battery optimization → **Don't optimize**" (button: `ignoreOptimizationIntents`).
3. "Open Recents, long-press Eldering → **Lock**" (text only).
4. "Battery → turn off **Sleep standby optimization**" if alerts are late (text only; no public intent).

Re-check `isIgnoringBatteryOptimizations` on return. The rest can't be read back [I]. After a **real kill test** from the debug screen, ask the user to confirm.

### 11.4 Detect that protection was killed

```kotlin
fun lastDeath(ctx: Context): ApplicationExitInfo? {
    if (Build.VERSION.SDK_INT < 30) return null                    // minSdk 29; API 30+ only
    return ctx.getSystemService(ActivityManager::class.java)
        .getHistoricalProcessExitReasons(null, 0, 5)
        .firstOrNull { it.processName == ctx.packageName }
}
// REASON_USER_REQUESTED (force-stop / Task Manager stop), REASON_USER_STOPPED (API 35+) [U mapping on OxygenOS],
// REASON_LOW_MEMORY, REASON_OTHER + description contains "MemoryLimiter" (A17), REASON_EXCESSIVE_RESOURCE_USAGE.
```

Combine it with a persisted "protection-on" heartbeat timestamp, written on each drain. On launch, show a banner like "Protection was off from 14:02 to 16:40 (stopped by system)". Re-register AR and restart the FGS from the foreground.

---

## 12. adb cookbook (CPH2717)

Syntax was checked against the device's `pm`/`am`/`appops`/`deviceidle` help, and all op names are valid [dev]. Our app isn't installed yet, so none of the grants have been run.

```bash
ADB=/opt/homebrew/share/android-commandlinetools/platform-tools/adb
PKG=com.eldering.safety            # adjust if debug uses applicationIdSuffix

# Runtime permissions (grant FINE before BACKGROUND)
$ADB shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
$ADB shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION
$ADB shell pm grant $PKG android.permission.ACCESS_BACKGROUND_LOCATION
$ADB shell pm grant $PKG android.permission.ACTIVITY_RECOGNITION
$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS
$ADB shell pm grant $PKG android.permission.SEND_SMS      # debug build stays dry-run unless "Allow real SMS" is ON

# Special access
$ADB shell appops set $PKG USE_FULL_SCREEN_INTENT allow   # op exists [dev]; canUseFullScreenIntent() -> true
$ADB shell cmd deviceidle whitelist +$PKG                 # = user battery-optimization exemption (persists)
$ADB shell cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow
$ADB shell am set-standby-bucket $PKG active
#  OnePlus "Allow background activity" / "Auto launch": no adb equivalent known -> set by hand.

# Inspect
$ADB shell dumpsys package $PKG | grep -E "permission.*granted="
$ADB shell appops get $PKG
$ADB shell cmd deviceidle whitelist | grep $PKG          # expect "user,$PKG,<uid>"
$ADB shell dumpsys activity services $PKG                 # isForeground=true, foregroundServiceType=0x...
$ADB shell dumpsys sensorservice | grep -B1 -A2 $PKG      # active connections, sampling/batching periods
$ADB shell dumpsys location | grep -A3 $PKG               # FLP requests
$ADB shell dumpsys power | grep -i eldering               # held wakelocks (tags eldering:*)
$ADB shell dumpsys notification --noredact | grep -A25 "pkg=$PKG"
$ADB shell am get-standby-bucket $PKG; $ADB shell am get-bg-restriction-level $PKG
$ADB shell dumpsys batterystats --reset   # ...run scenario...   then:
$ADB shell dumpsys batterystats $PKG | grep -iE "wake|sensor|gps|fg"

# Doze / standby
$ADB shell dumpsys battery unplug
$ADB shell dumpsys deviceidle force-idle deep    # or: step deep / force-idle light
$ADB shell dumpsys deviceidle unforce; $ADB shell dumpsys battery reset
$ADB shell am set-inactive $PKG true             # app standby

# Background-start path tests (shell broadcasts are NOT an FGS exemption):
$ADB shell cmd deviceidle tempwhitelist -d 60000 $PKG   # [U] grants temporary FGS-start allowance
$ADB shell am broadcast -n $PKG/.debug.DebugInjectReceiver -a com.eldering.safety.DEBUG_INJECT --es scenario vehicle_crash
#  BOOT_COMPLETED cannot be sent from shell ([dev]: SecurityException uid=2000) -> use `adb reboot` or a debug action.

# Negative tests
$ADB shell pm revoke $PKG android.permission.ACCESS_BACKGROUND_LOCATION   # start from bg -> SPECIAL_USE only
$ADB shell appops set $PKG USE_FULL_SCREEN_INTENT deny                    # heads-up fallback
$ADB shell cmd deviceidle whitelist -$PKG
$ADB shell am force-stop $PKG     # Android 15+: cancels ALL our PendingIntents (AR registration gone)

# Android 16 job-quota behaviour
$ADB shell am compat enable OVERRIDE_QUOTA_ENFORCEMENT_TO_FGS_JOBS $PKG
$ADB shell am compat enable OVERRIDE_QUOTA_ENFORCEMENT_TO_TOP_STARTED_JOBS $PKG

# Mock location for trail tests (app must call fused.setMockMode(true) in debug)
$ADB shell appops set $PKG android:mock_location allow
```

**Sensor FIFO probe** (debug screen; recommended before tuning §4.5):
1. Register accel+gyro at the mode's rates with latency = 60 s.
2. Screen off, unplugged, for T ∈ {15, 30, 60, 120} s.
3. Call `flush()`.
4. Compare the first and last `event.timestamp` with T and look for gaps > 2 × period. This yields the real FIFO depth per mode on CPH2717.

---

## 13. Open items / unverified

- [U] Play's verdict on `specialUse` versus `health` for crash and fall detection. Nothing official was found. Keep both code paths (constant + manifest) ready.
- [U] Whether `FLAG_MUTABLE` is strictly required for the AR PI. It is consistent with the official sample, and immutable would drop the fill-in extras. Verify on the device.
- [U] Whether a `START_STICKY` restart may call `startForeground` from the background without the battery-opt exemption. This is handled defensively.
- [U] OnePlus Auto-launch's effect on Play-services PendingIntent broadcasts and on `BOOT_COMPLETED`. Run the kill-and-transition test in §11.2.
- [U] Shared versus per-sensor FIFO on the MTK hub. Run the probe in §12.
- [U] Whether the vitals wakelock metric counts time while charging.
- [U] Exact OxygenOS 16.0.3 menu path. The labels are verified; the nesting is not.

## 14. Sources

- **FGS types:** https://developer.android.com/develop/background-work/services/fgs/service-types
- **Background start restrictions:** https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- **Launch / declare FGS:** https://developer.android.com/develop/background-work/services/fgs/launch · …/fgs/declare
- **Android 14:** https://developer.android.com/about/versions/14/behavior-changes-14 · …/14/behavior-changes-all
- **Android 15:** https://developer.android.com/about/versions/15/behavior-changes-15 · …/15/behavior-changes-all
- **Android 16:** https://developer.android.com/about/versions/16/behavior-changes-all · …/16/behavior-changes-16
- **Android 17:** https://developer.android.com/about/versions/17/behavior-changes-all · …/17/behavior-changes-17
- **Play FGS & FSI:** https://support.google.com/googleplay/android-developer/answer/13392821 · https://support.google.com/googleplay/android-developer/answer/16965181
- **Play wakelock vitals:** https://developer.android.com/google/play/vitals/excessive-wakelock · https://android-developers.googleblog.com/2026/03/battery-technical-quality-enforcement.html · https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/best-practices
- **Doze:** https://developer.android.com/training/monitoring-device-state/doze-standby
- **Sensors:** https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview · https://developer.android.com/reference/android/hardware/SensorManager · https://source.android.com/docs/core/interaction/sensors/batching
- **Activity Recognition:** https://developer.android.com/develop/sensors-and-location/location/transitions · https://developers.google.com/android/reference/com/google/android/gms/location/ActivityRecognitionClient · sample: https://github.com/android/platform-samples/tree/main/samples/location/src/main/java/com/example/platform/location/useractivityrecog
- **Location:** https://developers.google.com/android/reference/com/google/android/gms/location/LocationRequest.Builder · …/Priority
- **Geocoder:** https://developer.android.com/reference/android/location/Geocoder · …/Geocoder.GeocodeListener
- **Notifications:** https://developer.android.com/reference/android/app/NotificationManager#canUseFullScreenIntent() · https://developer.android.com/reference/android/provider/Settings · https://developer.android.com/develop/ui/views/notifications/time-sensitive · https://developer.android.com/develop/ui/views/notifications/notification-permission
- **SMS:** https://developer.android.com/reference/android/telephony/SmsManager
- **AOSP sources** (main):
  - `services/core/java/com/android/server/power/PowerManagerService.java`
  - `core/java/android/net/NetworkPolicyManager.java`
  - `apex/jobscheduler/service/java/com/android/server/alarm/AlarmManagerService.java`
  - `core/java/android/hardware/SensorManager.java`
  - `services/sensorservice/SensorService.h`
- **OEM:** https://dontkillmyapp.com/oneplus · DriveQuant OxygenOS 12/14 setup PDFs (info.drivequant.com)
- **Versions:** https://dl.google.com/android/maven2/ (maven-metadata.xml + AAR `aar-metadata.properties`) · https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-play-services/
