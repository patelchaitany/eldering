package com.eldering.safety.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.eldering.detection.Mode
import com.eldering.safety.core.appGraph
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/** Activity Recognition Transition API registration (docs/research/android-background.md §5). */
object TransitionRegistrar {
    private const val ACTION = "com.eldering.safety.ACTIVITY_TRANSITION"
    private const val REQ = 1001
    private const val TAG = "TransitionRegistrar"

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(ctx: Context): PendingIntent {
        // Explicit component (Android 14+ rejects mutable PendingIntents wrapping implicit intents). Mutable because
        // Play services fills the ActivityTransitionResult into the intent extras.
        val intent = Intent(ctx, ActivityTransitionReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(ctx, REQ, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    /** Idempotent (same PendingIntent). Returns false if not permitted or Play services refused. */
    @SuppressLint("MissingPermission") // checked right here; a revoke in between surfaces as SecurityException below
    suspend fun register(ctx: Context): Boolean {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return try {
            withTimeout(8_000) { registerPermitted(ctx) }
            true
        } catch (e: Exception) { // ApiException (no Play services), SecurityException, timeout
            Log.w(TAG, "Activity transition registration failed: ${e.javaClass.simpleName}")
            false
        }
    }

    @RequiresPermission(Manifest.permission.ACTIVITY_RECOGNITION)
    private suspend fun registerPermitted(ctx: Context) {
        val types = intArrayOf(
            DetectedActivity.IN_VEHICLE, DetectedActivity.ON_BICYCLE, DetectedActivity.RUNNING,
            DetectedActivity.WALKING, DetectedActivity.STILL,
        )
        val transitions = types.flatMap { t ->
            listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
                ActivityTransition.Builder().setActivityType(t).setActivityTransition(it).build()
            }
        }
        ActivityRecognition.getClient(ctx)
            .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(ctx))
            .await()
    }

    fun modeFor(activityType: Int): Mode = when (activityType) {
        DetectedActivity.IN_VEHICLE -> Mode.VEHICLE
        DetectedActivity.ON_BICYCLE -> Mode.CYCLING
        DetectedActivity.RUNNING -> Mode.RUNNING
        DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> Mode.WALKING
        DetectedActivity.STILL -> Mode.STILL
        else -> Mode.UNKNOWN
    }
}

/**
 * Activity Recognition transitions (PendingIntent from Play services; not exported). Stores the mode and passes
 * it to a running [SafetyService]; otherwise starts it — a transition is a documented exemption from the
 * background foreground-service start restriction, and it must be used inside `onReceive`.
 */
class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val events = ActivityTransitionResult.extractResult(intent)?.transitionEvents.orEmpty()
        val enter = events.lastOrNull { it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER } ?: return
        val mode = TransitionRegistrar.modeFor(enter.activityType)
        handleRecognizedMode(ctx, mode, enter.elapsedRealTimeNanos, reason = "ar:$mode")
    }

    companion object {
        /** Shared with the debug `DEBUG_TRANSITION`-style paths: same handling as a real transition. */
        fun handleRecognizedMode(ctx: Context, mode: Mode, elapsedNanos: Long, reason: String) {
            ProtectionPrefs.putRecognizedMode(ctx, mode, elapsedNanos)
            val control = ctx.appGraph.safetyRuntime.control
            if (control != null) {
                control.onModeRecognized(mode)
            } else if (ProtectionPrefs.isArmed(ctx)) {
                SafetyService.start(ctx, reason)
            }
        }
    }
}

/**
 * Restarts protection after a reboot or an app update (both are documented exemptions from the background FGS
 * start restriction) and re-registers activity transitions. Exported only for these protected system actions.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val armed = ProtectionPrefs.isArmed(ctx)
        Log.i("BootReceiver", "$action: protection ${if (armed) "armed, starting" else "not armed"}")
        if (!armed) return
        SafetyService.start(ctx, reason = if (action == Intent.ACTION_BOOT_COMPLETED) "boot" else "updated")
        if (!TransitionRegistrar.hasPermission(ctx)) return
        val pending = goAsync() // ~10 s budget for the Play services call
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                TransitionRegistrar.register(ctx)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Keep-alive heartbeat while still (inexact allow-while-idle alarm; docs/research/android-background.md §4.4),
 * so a still viewer still learns about a family member's accident through the heartbeat's `pending_alerts`.
 */
class HeartbeatAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val control = ctx.appGraph.safetyRuntime.control ?: return
        val pending = goAsync()
        control.onKeepAliveAlarm { pending.finish() }
    }

    companion object {
        const val ACTION = "com.eldering.safety.HEARTBEAT_ALARM"
        private const val RC = 4001

        private fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx,
            RC,
            Intent(ctx, HeartbeatAlarmReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Inexact, allowed while idle (≈ every 9 min in Doze without the battery-optimization exemption). */
        @SuppressLint("MissingPermission") // inexact alarms need no permission
        fun schedule(ctx: Context, delayMs: Long) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pendingIntent(ctx))
        }

        fun cancel(ctx: Context) {
            ctx.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(ctx))
        }
    }
}
