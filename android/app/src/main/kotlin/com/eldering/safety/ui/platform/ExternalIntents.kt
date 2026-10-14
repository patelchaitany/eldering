package com.eldering.safety.ui.platform

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.eldering.safety.service.ServiceNotifications
import java.util.Locale

/**
 * Intents the screens fire at other apps. Everything is best effort: a missing handler is caught and reported as
 * `false` so the caller can show a fallback, never a crash.
 *
 * Calls only ever use [Intent.ACTION_DIAL] (the dialer opens with the number filled in; the user presses call).
 * Nothing in the app uses ACTION_CALL.
 */
object ExternalIntents {
    private const val TAG = "ExternalIntents"

    /** The emergency number on the accident screens (design: "Call 112"; 112 also works on GSM phones abroad). */
    const val EMERGENCY_NUMBER = "112"

    fun dialIntent(number: String?): Intent =
        Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + (number?.let(Uri::encode) ?: "")))

    /** Opens the dialer pre-filled with [number] (or empty). */
    fun dial(context: Context, number: String?): Boolean = start(context, dialIntent(number))

    fun dialEmergency(context: Context): Boolean = dial(context, EMERGENCY_NUMBER)

    /** Turn-by-turn directions to a point, in whatever maps app the user has. */
    fun directions(context: Context, lat: Double, lng: Double, label: String?): Boolean {
        val navigation = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${coord(lat)},${coord(lng)}"))
        return start(context, navigation, quiet = true) || showOnMap(context, lat, lng, label)
    }

    /** Shows a pinned point in a maps app (falls back to the browser). */
    fun showOnMap(context: Context, lat: Double, lng: Double, label: String?): Boolean {
        val query = "${coord(lat)},${coord(lng)}" + (label?.takeIf { it.isNotBlank() }?.let { "(${Uri.encode(it)})" } ?: "")
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${coord(lat)},${coord(lng)}?q=$query"))
        return start(context, geo, quiet = true) || start(context, Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl(lat, lng))))
    }

    fun mapsUrl(lat: Double, lng: Double): String = "https://maps.google.com/?q=${coord(lat)},${coord(lng)}"

    fun shareText(context: Context, text: String, chooserTitle: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return start(context, Intent.createChooser(send, chooserTitle))
    }

    fun openAppDetails(context: Context): Boolean = start(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )

    fun openNotificationSettings(context: Context): Boolean = start(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    ) || openAppDetails(context)

    /** The accident alarm channel's settings (blocked channel), or the app's notification settings if all are off. */
    fun openAlarmChannelSettings(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return openNotificationSettings(context)
        return start(
            context,
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, ServiceNotifications.CH_ALARM),
        ) || openNotificationSettings(context)
    }

    /** Android 14+: the per-app "full screen notifications" page (needed for the accident countdown over the lock screen). */
    fun openFullScreenIntentSettings(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 34) {
            start(
                context,
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", context.packageName, null)),
            ) || openAppDetails(context)
        } else {
            openAppDetails(context)
        }

    /**
     * Asks to exempt the app from battery optimization (the system dialog), falling back to the global list.
     * A safety app that must react to accidents in the background is an accepted use of this request.
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean = start(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null)),
        quiet = true,
    ) || start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) || openAppDetails(context)

    /**
     * OnePlus / OPPO / realme (ColorOS-based OxygenOS) "Auto launch" / startup manager. These screens are not public
     * API and move between OS versions, so known components are tried in order, then App info.
     */
    fun openOemAutoLaunchSettings(context: Context): Boolean {
        val candidates = listOf(
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
        )
        return candidates.any { start(context, Intent().setComponent(it), quiet = true) } || openAppDetails(context)
    }

    /** Manufacturers whose battery managers are known to stop background safety apps unless the user allows it. */
    fun isOppoFamilyDevice(manufacturer: String = Build.MANUFACTURER): Boolean =
        manufacturer.lowercase(Locale.ROOT) in setOf("oneplus", "oppo", "realme")

    private fun coord(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

    private fun start(context: Context, intent: Intent, quiet: Boolean = false): Boolean {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            if (!quiet) Log.w(TAG, "No activity for ${intent.action ?: intent.component}")
            false
        } catch (e: SecurityException) { // non-exported OEM screens
            if (!quiet) Log.w(TAG, "Not allowed to open ${intent.component}", e)
            false
        }
    }
}
