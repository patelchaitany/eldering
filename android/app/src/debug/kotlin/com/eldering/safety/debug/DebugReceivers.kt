package com.eldering.safety.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.eldering.detection.SyntheticTraces
import com.eldering.safety.core.appGraph
import com.eldering.safety.service.DetectionEngine
import com.eldering.safety.service.ProtectionPrefs
import com.eldering.safety.service.SafetyControl
import com.eldering.safety.service.SafetyService
import com.eldering.safety.service.parseMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Debug builds only (declared in src/debug/AndroidManifest.xml, protected by android.permission.DUMP so only adb
 * and the system can send them). Results go to logcat tag EldDebug. Use explicit components:
 *
 *   adb shell am broadcast -n com.eldering.safety/.debug.DebugInjectReceiver -a com.eldering.safety.DEBUG_INJECT \
 *       --es scenario fall [--es mode walking] [--ef speed 13.9] [--ef pace 1.0] [--el seed 0]
 *   adb shell am broadcast -n com.eldering.safety/.debug.DebugStatusReceiver -a com.eldering.safety.DEBUG_STATUS
 *   adb shell am broadcast -n com.eldering.safety/.debug.DebugModeReceiver -a com.eldering.safety.DEBUG_MODE --es mode vehicle|walking|still|auto
 *   adb shell am broadcast -n com.eldering.safety/.debug.DebugProtectionReceiver -a com.eldering.safety.DEBUG_START
 *   adb shell am broadcast -n com.eldering.safety/.debug.DebugProtectionReceiver -a com.eldering.safety.DEBUG_STOP
 *
 * A broadcast from adb is not an exemption from Android's background foreground-service start rules: before
 * DEBUG_START (or a DEBUG_INJECT that has to start the service) run
 * `adb shell cmd deviceidle whitelist +com.eldering.safety` (battery-optimization exemption) or open the app.
 */

internal const val DEBUG_TAG = "EldDebug"

/** Waits up to [timeoutMs] for a running SafetyService, starting it if protection may run. */
private suspend fun awaitService(context: Context, start: Boolean, timeoutMs: Long = 6_000): SafetyControl? {
    val runtime = context.appGraph.safetyRuntime
    runtime.control?.let { return it }
    if (!start) return null
    ProtectionPrefs.setArmed(context, true)
    if (!SafetyService.start(context, reason = "debug")) return null
    return withTimeoutOrNull(timeoutMs) {
        runtime.running.first { it }
        runtime.control
    }
}

private fun BroadcastReceiver.async(context: Context, block: suspend () -> Unit) {
    val pending = goAsync()
    context.appGraph.applicationScope.launch {
        try {
            block()
        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "Debug command failed", e)
        } finally {
            pending.finish()
        }
    }
}

/**
 * `DEBUG_INJECT`: replays a `SyntheticTraces` scenario through the live detector (see `DetectionEngine.inject`).
 * Extras: `scenario` (required), `mode` (override the trace's mode), `speed` (GPS speed in m/s, overrides the
 * trace's speed fixes), `pace` (1 = real time, 0 = all at once), `seed`.
 */
class DebugInjectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val scenario = intent.getStringExtra("scenario")
        if (scenario.isNullOrBlank()) {
            Log.w(DEBUG_TAG, "DEBUG_INJECT needs --es scenario <name>. Known: ${SyntheticTraces.ids.joinToString()}")
            return
        }
        val modeExtra = intent.getStringExtra("mode")
        val mode = parseMode(modeExtra)
        if (modeExtra != null && mode == null) Log.w(DEBUG_TAG, "DEBUG_INJECT: unknown mode '$modeExtra', using the trace's modes")
        val request = DetectionEngine.InjectRequest(
            scenario = scenario.trim(),
            mode = mode,
            speedMps = floatExtra(intent, "speed"),
            pace = floatExtra(intent, "pace")?.toDouble() ?: 1.0,
            seed = longExtra(intent, "seed") ?: 0L,
        )
        async(context) {
            val control = awaitService(context, start = true)
            if (control == null) {
                Log.w(DEBUG_TAG, "DEBUG_INJECT: SafetyService is not running and could not be started " +
                    "(whitelist the app: cmd deviceidle whitelist +${context.packageName})")
                return@async
            }
            Log.i(DEBUG_TAG, "DEBUG_INJECT $request")
            control.inject(request)
        }
    }

    companion object {
        const val ACTION = "com.eldering.safety.DEBUG_INJECT"
    }
}

/** `DEBUG_STATUS`: logs the service state, mode, sensor rates, samples per sensor, trail size, last heartbeat. */
class DebugStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        async(context) {
            val control = context.appGraph.safetyRuntime.control
            val lines = if (control != null) {
                control.statusLines()
            } else {
                val graph = context.appGraph
                val trail = withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { graph.trailStore.count() }.getOrNull() }
                listOf(
                    "service: running=false armed=${ProtectionPrefs.isArmed(context)} lastPauseReason=${ProtectionPrefs.lastPauseReason(context) ?: "-"}",
                    "mode: forced=${ProtectionPrefs.forcedMode(context) ?: "-"} recognized=${ProtectionPrefs.recognizedMode(context)?.first ?: "-"}",
                    "trail: size=${trail ?: "?"}",
                    "alert: ${graph.alertController.state.value.javaClass.simpleName}",
                )
            }
            Log.i(DEBUG_TAG, "---- DEBUG_STATUS ----")
            lines.forEach { Log.i(DEBUG_TAG, it) }
            Log.i(DEBUG_TAG, "----------------------")
        }
    }

    companion object {
        const val ACTION = "com.eldering.safety.DEBUG_STATUS"
    }
}

/** `DEBUG_MODE --es mode <mode|auto>`: forces the movement mode (the emulator has no activity recognition). */
class DebugModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val raw = intent.getStringExtra("mode")?.trim()
        val auto = raw == null || raw.equals("auto", true) || raw.equals("none", true) || raw.isEmpty()
        val mode = if (auto) null else parseMode(raw)
        if (!auto && mode == null) {
            Log.w(DEBUG_TAG, "DEBUG_MODE: unknown mode '$raw' (still|walking|running|cycling|vehicle|unknown|auto)")
            return
        }
        ProtectionPrefs.setForcedMode(context, mode)
        val control = context.appGraph.safetyRuntime.control
        control?.forceMode(mode)
        Log.i(DEBUG_TAG, "DEBUG_MODE: ${mode ?: "automatic"}${if (control == null) " (service not running; applies when it starts)" else ""}")
    }

    companion object {
        const val ACTION = "com.eldering.safety.DEBUG_MODE"
    }
}

/** `DEBUG_START` arms and starts protection; `DEBUG_STOP` stops the service (`--ez disarm true` also disarms). */
class DebugProtectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> async(context) {
                val control = awaitService(context, start = true)
                Log.i(DEBUG_TAG, "DEBUG_START: ${if (control != null) "SafetyService running" else "could not start SafetyService (see SafetyService logs)"}")
            }
            ACTION_STOP -> {
                if (intent.getBooleanExtra("disarm", false)) ProtectionPrefs.setArmed(context, false)
                SafetyService.stop(context)
                Log.i(DEBUG_TAG, "DEBUG_STOP: stop requested (armed=${ProtectionPrefs.isArmed(context)})")
            }
        }
    }

    companion object {
        const val ACTION_START = "com.eldering.safety.DEBUG_START"
        const val ACTION_STOP = "com.eldering.safety.DEBUG_STOP"
    }
}

/** Accepts `--ef`, `--ei`, `--el` and `--es` for numeric extras. */
@Suppress("DEPRECATION") // Bundle.get: the extra's type depends on how adb was called
private fun floatExtra(intent: Intent, key: String): Float? {
    val value = intent.extras?.get(key) ?: return null
    return when (value) {
        is Number -> value.toFloat()
        is String -> value.trim().toFloatOrNull()
        else -> null
    }?.takeIf { it.isFinite() }
}

@Suppress("DEPRECATION")
private fun longExtra(intent: Intent, key: String): Long? {
    val value = intent.extras?.get(key) ?: return null
    return when (value) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }
}
