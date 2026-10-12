package com.eldering.safety.ui.permissions

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** One step of the permission walkthrough, in the order they are asked. */
enum class PermissionStep {
    /** Precise location while using the app (prerequisite for background location). */
    LOCATION,

    /** "Allow all the time", after the prominent disclosure. */
    BACKGROUND_LOCATION,

    /** Walking / driving detection (Activity Recognition transitions). */
    ACTIVITY_RECOGNITION,

    /** Android 13+: the accident countdown and family alerts are notifications. */
    NOTIFICATIONS,

    /** Accident SMS to emergency contacts. */
    SMS,

    /** Android 14+: show the countdown over the lock screen. */
    FULL_SCREEN_ALERTS,

    /** Exemption from battery optimization so detection keeps running. */
    BATTERY,

    /** OnePlus / OPPO / realme: background activity + auto launch (manual, guided). */
    OEM_BACKGROUND,
}

enum class StepStatus {
    DONE,
    TODO,

    /** Waiting for an earlier step (background location needs location first). */
    BLOCKED,
}

/** What the plan needs to know about the device; [AndroidPermissionChecker] in the app, fakes in tests. */
interface PermissionChecker {
    fun isGranted(permission: String): Boolean
    fun notificationsEnabled(): Boolean
    fun ignoresBatteryOptimizations(): Boolean
    fun canUseFullScreenIntent(): Boolean
}

/** Pure rules of the walkthrough: which steps apply on this phone and whether each is done. */
object PermissionPlan {

    fun steps(sdkInt: Int, hasSms: Boolean, oppoFamily: Boolean): List<PermissionStep> = PermissionStep.entries.filter { step ->
        when (step) {
            PermissionStep.NOTIFICATIONS -> sdkInt >= 33
            PermissionStep.SMS -> hasSms
            PermissionStep.FULL_SCREEN_ALERTS -> sdkInt >= 34
            PermissionStep.OEM_BACKGROUND -> oppoFamily
            else -> true
        }
    }

    /** Runtime permissions requested by a step's main button (empty for settings-page steps). */
    fun runtimePermissions(step: PermissionStep, sdkInt: Int): List<String> = when (step) {
        PermissionStep.LOCATION -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        PermissionStep.BACKGROUND_LOCATION -> listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        PermissionStep.ACTIVITY_RECOGNITION -> listOf(Manifest.permission.ACTIVITY_RECOGNITION)
        PermissionStep.NOTIFICATIONS -> if (sdkInt >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        PermissionStep.SMS -> listOf(Manifest.permission.SEND_SMS)
        PermissionStep.FULL_SCREEN_ALERTS, PermissionStep.BATTERY, PermissionStep.OEM_BACKGROUND -> emptyList()
    }

    fun status(step: PermissionStep, checker: PermissionChecker, oemGuideDone: Boolean): StepStatus {
        val done = when (step) {
            PermissionStep.LOCATION -> checker.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
            PermissionStep.BACKGROUND_LOCATION -> {
                if (!checker.isGranted(Manifest.permission.ACCESS_FINE_LOCATION) &&
                    !checker.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
                ) {
                    return StepStatus.BLOCKED
                }
                checker.isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            PermissionStep.ACTIVITY_RECOGNITION -> checker.isGranted(Manifest.permission.ACTIVITY_RECOGNITION)
            PermissionStep.NOTIFICATIONS -> checker.notificationsEnabled()
            PermissionStep.SMS -> checker.isGranted(Manifest.permission.SEND_SMS)
            PermissionStep.FULL_SCREEN_ALERTS -> checker.canUseFullScreenIntent()
            PermissionStep.BATTERY -> checker.ignoresBatteryOptimizations()
            PermissionStep.OEM_BACKGROUND -> oemGuideDone
        }
        return if (done) StepStatus.DONE else StepStatus.TODO
    }

    /** Steps without which accident detection cannot work at all (shown as "needed" rather than "recommended"). */
    fun isEssential(step: PermissionStep): Boolean = step in setOf(
        PermissionStep.LOCATION,
        PermissionStep.BACKGROUND_LOCATION,
        PermissionStep.ACTIVITY_RECOGNITION,
        PermissionStep.NOTIFICATIONS,
    )
}

class AndroidPermissionChecker(private val context: Context) : PermissionChecker {
    override fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun notificationsEnabled(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || isGranted(Manifest.permission.POST_NOTIFICATIONS))

    override fun ignoresBatteryOptimizations(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    override fun canUseFullScreenIntent(): Boolean =
        if (Build.VERSION.SDK_INT >= 34) {
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: false
        } else {
            true
        }

    companion object {
        fun hasSms(context: Context): Boolean = context.packageManager.hasSystemFeature(
            if (Build.VERSION.SDK_INT >= 33) PackageManager.FEATURE_TELEPHONY_MESSAGING else PackageManager.FEATURE_TELEPHONY,
        )
    }
}
