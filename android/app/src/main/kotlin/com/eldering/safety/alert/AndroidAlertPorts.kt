package com.eldering.safety.alert

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.eldering.safety.R
import com.eldering.safety.data.db.DetectionOutcome
import com.eldering.safety.data.db.FeedbackLabel
import com.eldering.safety.data.repository.DetectionEventRepository
import com.eldering.safety.data.trail.TrailPoint
import com.eldering.safety.network.IncidentKind
import com.eldering.safety.service.ServiceNotifications
import com.eldering.safety.ui.platform.ExternalIntents

/** Android side of [AlertUi]: full-screen-intent notification, [AccidentAlertActivity], alarm sound, dialer. */
class AndroidAlertUi(private val context: Context) : AlertUi {
    private val alarm = AlarmPlayer(context)

    override fun showCountdown(alert: ActiveAlert) {
        // While our own UI is visible we may start the activity directly (and a heads-up would only cover it);
        // otherwise the full-screen intent opens it (locked / screen off) or the heads-up offers the actions.
        val opened = isAppVisible() && try {
            context.startActivity(AccidentAlertActivity.intent(context))
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not open the countdown screen directly: ${e.javaClass.simpleName}")
            false
        }
        AlertNotifications.showCountdown(context, alert, quiet = opened)
    }

    override fun showSent(alert: ActiveAlert) = AlertNotifications.showSent(context, alert)

    override fun dismiss(alert: ActiveAlert) = AlertNotifications.cancel(context)

    override fun startAlarm() = alarm.start()

    override fun stopAlarm() = alarm.stop()

    override fun dialEmergency() {
        ExternalIntents.dialEmergency(context) // ACTION_DIAL only: the user decides whether to call
    }

    private fun isAppVisible(): Boolean = try {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    } catch (e: RuntimeException) {
        false
    }

    private companion object {
        const val TAG = "AndroidAlertUi"
    }
}

/** The accident countdown notification (alarm channel; full-screen intent when allowed). */
object AlertNotifications {
    private const val RC_OPEN = 2001
    private const val RC_IM_OK = 2002
    private const val RC_SEND_NOW = 2003

    @SuppressLint("MissingPermission") // checked by canNotify()
    /** [quiet]: the countdown screen is already in front, so no heads-up / full-screen intent (actions stay). */
    fun showCountdown(ctx: Context, alert: ActiveAlert, quiet: Boolean = false) {
        if (!ServiceNotifications.canNotify(ctx)) {
            Log.w("AlertNotifications", "Notifications are off: the countdown can only be shown while the app is open")
            return
        }
        ServiceNotifications.ensureChannels(ctx)
        val open = openIntent(ctx)
        val channel = if (quiet) ServiceNotifications.CH_ALERT_STATUS else ServiceNotifications.CH_ALARM
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(ctx.getString(if (alert.kind == IncidentKind.SOS) R.string.alert_notif_title_sos else R.string.alert_notif_title_possible))
            .setContentText(ctx.getString(R.string.alert_notif_text_counting))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, ctx.getString(R.string.alert_notif_im_ok), action(ctx, AlertActionReceiver.ACTION_IM_OK, RC_IM_OK))
            .addAction(0, ctx.getString(R.string.alert_notif_send_now), action(ctx, AlertActionReceiver.ACTION_SEND_NOW, RC_SEND_NOW))
        alert.countdownEndsAtMs?.let { b.setWhen(it).setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true) }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!quiet && (Build.VERSION.SDK_INT < 34 || nm?.canUseFullScreenIntent() == true)) b.setFullScreenIntent(open, true)
        NotificationManagerCompat.from(ctx).notify(ServiceNotifications.ID_ALARM, b.build())
    }

    @SuppressLint("MissingPermission") // checked by canNotify()
    fun showSent(ctx: Context, alert: ActiveAlert) {
        if (!ServiceNotifications.canNotify(ctx)) return
        ServiceNotifications.ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, ServiceNotifications.CH_ALERT_STATUS)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(ctx.getString(R.string.alert_notif_title_sent))
            .setContentText(ctx.getString(R.string.alert_notif_text_sent))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setWhen(alert.detectedAtMs)
            .setShowWhen(true)
            .setContentIntent(openIntent(ctx))
            .addAction(0, ctx.getString(R.string.alert_notif_safe_now), action(ctx, AlertActionReceiver.ACTION_IM_OK, RC_IM_OK))
            .build()
        NotificationManagerCompat.from(ctx).notify(ServiceNotifications.ID_ALARM, n)
    }

    fun cancel(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ServiceNotifications.ID_ALARM)

    private fun openIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx,
        RC_OPEN,
        AccidentAlertActivity.intent(ctx).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(ctx: Context, action: String, rc: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        rc,
        Intent(ctx, AlertActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * Looping alarm sound (USAGE_ALARM, plays in silent mode) and vibration for the countdown. One instance per
 * process; [start] and [stop] are idempotent.
 */
class AlarmPlayer(private val context: Context) {
    private var ringtone: Ringtone? = null
    private var vibrating = false

    @Synchronized
    fun start() {
        if (ringtone != null || vibrating) return
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = uri?.let { RingtoneManager.getRingtone(context, it) }?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                isLooping = true
                play()
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Alarm sound unavailable: ${e.javaClass.simpleName}")
            ringtone = null
        }
        try {
            val vibrator = vibrator() ?: return
            if (!vibrator.hasVibrator()) return
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0)
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
            vibrating = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Vibration unavailable: ${e.javaClass.simpleName}")
        }
    }

    @Synchronized
    fun stop() {
        try {
            ringtone?.stop()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not stop the alarm sound", e)
        }
        ringtone = null
        if (vibrating) {
            try {
                vibrator()?.cancel()
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not stop vibration", e)
            }
            vibrating = false
        }
    }

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    private companion object {
        const val TAG = "AlarmPlayer"
    }
}

/** `eldering:Incident` partial wakelock (constant tag, bounded). */
class PowerWakeHold(context: Context) : WakeHold {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eldering:Incident")
        .apply { setReferenceCounted(false) }

    override fun acquire(timeoutMs: Long) = lock.acquire(timeoutMs)

    override fun release() {
        if (lock.isHeld) lock.release()
    }
}

/** [DetectionJournal] on the Room detection log (on-device only). */
class RoomDetectionJournal(
    private val events: DetectionEventRepository,
    private val position: suspend () -> TrailPoint?,
) : DetectionJournal {
    override suspend fun record(alert: ActiveAlert): Long? {
        val event = alert.event ?: return null
        val fix = runCatching { position() }.getOrNull()
        return events.record(event, alert.mode, alert.detectedAtMs, fix?.lat, fix?.lng, alert.windowJson)
    }

    override suspend fun setOutcome(eventId: Long, outcome: DetectionOutcome, incidentKey: String?) =
        events.setOutcome(eventId, outcome, incidentKey)

    override suspend fun label(eventId: Long, label: FeedbackLabel?, note: String?) = events.label(eventId, label, note)
}

/** Validated internet connectivity right now (landmarks are skipped offline). */
fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = try {
        cm.getNetworkCapabilities(cm.activeNetwork)
    } catch (e: SecurityException) {
        null
    } ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
