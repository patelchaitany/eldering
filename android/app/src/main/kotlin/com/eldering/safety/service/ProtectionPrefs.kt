package com.eldering.safety.service

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.eldering.detection.Mode

/**
 * Small synchronous state for the background runtime (SharedPreferences, not DataStore): broadcast receivers
 * (boot, activity transitions, alarms) must decide within `onReceive` whether to start [SafetyService].
 * Not backed up (see the backup rules: the whole app opts out).
 */
object ProtectionPrefs {
    private const val FILE = "protection"
    private const val ARMED = "armed"
    private const val AR_MODE = "ar_mode"
    private const val AR_MODE_AT = "ar_mode_elapsed_ns"
    private const val FORCED_MODE = "debug_forced_mode"
    private const val NOTIFIED_ALERTS = "notified_alerts"
    private const val LAST_PAUSE_REASON = "last_pause_reason"

    private fun prefs(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Protection is armed once the user has been through the permission walkthrough: from then on the service is
     * (re)started by the app, at boot, after an update and by activity transitions. An explicit sign-out disarms it
     * (the next sign-in arms it again); an expired session does not (protection keeps running, uploads wait).
     */
    fun isArmed(ctx: Context): Boolean = prefs(ctx).getBoolean(ARMED, false)

    fun setArmed(ctx: Context, armed: Boolean) = prefs(ctx).edit { putBoolean(ARMED, armed) }

    /** Last Activity Recognition transition (mode + `elapsedRealtimeNanos` of the transition). */
    fun recognizedMode(ctx: Context): Pair<Mode, Long>? {
        val p = prefs(ctx)
        val mode = parseMode(p.getString(AR_MODE, null)) ?: return null
        return mode to p.getLong(AR_MODE_AT, 0L)
    }

    fun putRecognizedMode(ctx: Context, mode: Mode, elapsedNanos: Long) = prefs(ctx).edit {
        putString(AR_MODE, mode.name)
        putLong(AR_MODE_AT, elapsedNanos)
    }

    /** Debug builds: a mode forced with `DEBUG_MODE` (the emulator has no real activity recognition). */
    fun forcedMode(ctx: Context): Mode? = parseMode(prefs(ctx).getString(FORCED_MODE, null))

    fun setForcedMode(ctx: Context, mode: Mode?) = prefs(ctx).edit {
        if (mode == null) remove(FORCED_MODE) else putString(FORCED_MODE, mode.name)
    }

    fun lastPauseReason(ctx: Context): String? = prefs(ctx).getString(LAST_PAUSE_REASON, null)

    fun setLastPauseReason(ctx: Context, reason: String?) = prefs(ctx).edit {
        if (reason == null) remove(LAST_PAUSE_REASON) else putString(LAST_PAUSE_REASON, reason.take(200))
    }

    /** Sign-out: forget per-account state (which family alerts were already notified). */
    fun clearAccountState(ctx: Context) = prefs(ctx).edit { remove(NOTIFIED_ALERTS) }

    /**
     * Records that a family-alert notification was shown for [incidentId]. Returns false if it already was
     * (so a viewer is notified once per incident). Entries older than [retentionMs] are dropped.
     */
    @Synchronized
    fun markAlertNotified(ctx: Context, incidentId: String, nowMs: Long, retentionMs: Long = 72L * 3600_000): Boolean {
        val p = prefs(ctx)
        val entries = p.getStringSet(NOTIFIED_ALERTS, emptySet()).orEmpty()
            .mapNotNull { e -> e.split('|').takeIf { it.size == 2 }?.let { (id, at) -> at.toLongOrNull()?.let { id to it } } }
            .filter { (_, at) -> nowMs - at < retentionMs }
            .toMap()
        if (incidentId in entries) return false
        val updated = entries + (incidentId to nowMs)
        p.edit { putStringSet(NOTIFIED_ALERTS, updated.map { (id, at) -> "$id|$at" }.toSet()) }
        return true
    }
}
