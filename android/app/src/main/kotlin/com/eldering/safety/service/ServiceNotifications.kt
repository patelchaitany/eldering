package com.eldering.safety.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.eldering.safety.MainActivity
import com.eldering.safety.R
import com.eldering.safety.network.IncidentKind
import com.eldering.safety.network.IncidentSummaryDto
import com.eldering.safety.ui.platform.ExternalIntents

/** Foreground-service types we may legally claim right now (docs/research/android-background.md §2.3). */
object FgsTypes {
    /**
     * @param fromForeground started while one of our activities is visible: only then may a `location` FGS be
     *   created without background-location access (while-in-use rule, Android 14+).
     */
    fun current(ctx: Context, fromForeground: Boolean): Int {
        var types = 0
        if (locationAllowed(ctx, fromForeground)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (Build.VERSION.SDK_INT >= 34) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        return types
    }

    fun locationAllowed(ctx: Context, fromForeground: Boolean): Boolean {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return false
        val fg = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        val bg = granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        return fg && LocationManagerCompat.isLocationEnabled(lm) && (bg || fromForeground)
    }

    fun describe(types: Int): String = buildList {
        if (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0) add("location")
        if (Build.VERSION.SDK_INT >= 34 && types and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0) add("specialUse")
    }.ifEmpty { listOf("none") }.joinToString("|")

    private fun granted(ctx: Context, permission: String) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
}

/** Notification channels and the service's own notifications (ongoing, paused, family alerts). */
object ServiceNotifications {
    const val CH_PROTECTION = "protection"
    const val CH_PAUSED = "protection_paused"

    /** Channel settings are immutable after creation: bump the suffix to change them. */
    const val CH_ALARM = "accident_alarm_v1"

    /** The same alert without heads-up: used while the countdown screen itself is in front, and once sent. */
    const val CH_ALERT_STATUS = "accident_status_v1"
    /** v2: bypasses Do Not Disturb (with DND access) and sounds like an alarm; v1 is deleted. */
    const val CH_FAMILY = "family_alerts_v2"
    private const val CH_FAMILY_V1 = "family_alerts_v1"

    const val ID_ONGOING = 1001
    const val ID_PAUSED = 1002
    const val ID_ALARM = 1003
    private const val ID_FAMILY_BASE = 20_000

    private const val RC_OPEN_APP = 3001
    private const val RC_BATTERY = 3002
    private const val RC_RESUME = 3003
    private const val RC_ALARM_CHANNEL = 3004
    private const val RC_FAMILY_CALL_112 = 3005
    private const val RC_FAMILY_CALL_BASE = 5000

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_PROTECTION, ctx.getString(R.string.ch_protection_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = ctx.getString(R.string.ch_protection_desc)
                    setShowBadge(false)
                },
                NotificationChannel(CH_PAUSED, ctx.getString(R.string.ch_paused_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = ctx.getString(R.string.ch_paused_desc)
                },
                NotificationChannel(CH_ALARM, ctx.getString(R.string.ch_alarm_name), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = ctx.getString(R.string.ch_alarm_desc)
                    // Silent channel: the looping alarm sound and vibration come from AlarmPlayer (one source, stoppable).
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setBypassDnd(true) // honoured only with DND access; harmless otherwise
                },
                NotificationChannel(CH_ALERT_STATUS, ctx.getString(R.string.ch_alert_status_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = ctx.getString(R.string.ch_alert_status_desc)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
                NotificationChannel(CH_FAMILY, ctx.getString(R.string.ch_family_name), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = ctx.getString(R.string.ch_family_desc)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    // A family member's accident is the reason the app exists: heard in silent mode, through DND
                    // where the user granted DND access.
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    setBypassDnd(true)
                },
            ),
        )
        if (nm.getNotificationChannel(CH_FAMILY_V1) != null) nm.deleteNotificationChannel(CH_FAMILY_V1)
    }

    /** The low-key ongoing notification of [SafetyService]. */
    fun ongoing(ctx: Context, modeLabel: String?, signedIn: Boolean?): Notification {
        ensureChannels(ctx)
        val alarmBlocked = !channelEnabled(ctx, CH_ALARM)
        val text = when {
            alarmBlocked -> ctx.getString(R.string.svc_alarm_channel_off)
            signedIn == false -> ctx.getString(R.string.svc_protection_signed_out)
            modeLabel != null -> ctx.getString(R.string.svc_protection_mode, modeLabel)
            else -> null
        }
        val b = NotificationCompat.Builder(ctx, CH_PROTECTION)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(ctx.getString(R.string.svc_protection_on))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(if (alarmBlocked) alarmChannelSettingsIntent(ctx) else openAppIntent(ctx))
        if (!ignoresBatteryOptimizations(ctx)) {
            b.addAction(0, ctx.getString(R.string.svc_allow_background), batteryExemptionIntent(ctx))
        }
        return b.build()
    }

    /** "Protection paused — tap to resume": tapping opens the app, which restarts the service from the foreground. */
    @SuppressLint("MissingPermission") // checked by canNotify()
    fun showPaused(ctx: Context) {
        if (!canNotify(ctx)) return
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_PAUSED)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(ctx.getString(R.string.svc_paused_title))
            .setContentText(ctx.getString(R.string.svc_paused_text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(
                PendingIntent.getActivity(
                    ctx,
                    RC_RESUME,
                    Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_PAUSED, n)
    }

    fun cancelPaused(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_PAUSED)

    /**
     * A family member's accident or SOS (from the heartbeat's `pending_alerts`). Opens the "What family sees" screen;
     * full-screen intent where allowed (a heads-up that stays when the phone is in use), and actions to call the
     * person ([phone], when this phone knows their number) or 112 (both ACTION_DIAL: the user presses call).
     */
    @SuppressLint("MissingPermission") // checked by canNotify()
    fun showFamilyAlert(ctx: Context, alert: IncidentSummaryDto, phone: String? = null) {
        if (!canNotify(ctx)) return
        ensureChannels(ctx)
        val name = alert.displayName.ifBlank { ctx.getString(R.string.family_notif_public) }
        val title = ctx.getString(
            when (alert.kind) {
                IncidentKind.VEHICLE_CRASH, IncidentKind.PEDESTRIAN_IMPACT -> R.string.incident_headline_accident
                IncidentKind.FALL -> R.string.incident_headline_fall
                IncidentKind.SOS -> R.string.incident_headline_sos
            },
            name,
        )
        val open = PendingIntent.getActivity(
            ctx,
            alert.id.hashCode(),
            MainActivity.familyAlertIntent(ctx, alert.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val publicVersion = NotificationCompat.Builder(ctx, CH_FAMILY)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(ctx.getString(R.string.family_notif_public))
            .build()
        val b = NotificationCompat.Builder(ctx, CH_FAMILY)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(title)
            .setContentText(ctx.getString(R.string.family_notif_text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setWhen(alert.detectedAt.toEpochMilli())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, ctx.getString(R.string.family_notif_open), open)
        if (phone != null) {
            b.addAction(0, ctx.getString(R.string.family_notif_call_person, name), dialIntent(ctx, phone, RC_FAMILY_CALL_BASE + (alert.id.hashCode() and 0xFFF)))
        }
        b.addAction(0, ctx.getString(R.string.family_notif_call_emergency), dialIntent(ctx, ExternalIntents.EMERGENCY_NUMBER, RC_FAMILY_CALL_112))
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT < 34 || nm?.canUseFullScreenIntent() == true) b.setFullScreenIntent(open, true)
        NotificationManagerCompat.from(ctx).notify(familyNotificationId(alert.id), b.build())
    }

    /** ACTION_DIAL only (never places the call). */
    private fun dialIntent(ctx: Context, number: String, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        ctx,
        requestCode,
        ExternalIntents.dialIntent(number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun familyNotificationId(incidentId: String): Int = ID_FAMILY_BASE + (incidentId.hashCode() and 0xFFFF)

    fun canNotify(ctx: Context): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** False if the user blocked channel [id] (importance "none"); a channel not created yet counts as enabled. */
    fun channelEnabled(ctx: Context, id: String): Boolean =
        NotificationManagerCompat.from(ctx).getNotificationChannelCompat(id)?.importance != NotificationManagerCompat.IMPORTANCE_NONE

    /** The accident countdown can reach the user: notifications allowed and the alarm channel not blocked. */
    fun canAlarm(ctx: Context): Boolean = canNotify(ctx) && channelEnabled(ctx, CH_ALARM)

    fun ignoresBatteryOptimizations(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false

    private fun openAppIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx,
        RC_OPEN_APP,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun alarmChannelSettingsIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx,
        RC_ALARM_CHANNEL,
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, CH_ALARM)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * The system "stop optimizing battery usage?" dialog. Google lists safety apps as an acceptable use of
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (docs/research/android-background.md §4.4): without the exemption Android
     * may refuse to restart protection from the background and defers its alarms in Doze.
     */
    @SuppressLint("BatteryLife")
    private fun batteryExemptionIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx,
        RC_BATTERY,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", ctx.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
