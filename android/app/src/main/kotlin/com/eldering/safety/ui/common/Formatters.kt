package com.eldering.safety.ui.common

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.eldering.safety.R
import com.eldering.safety.network.Activity
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Pure formatting rules shared by screens and tests. */
object Formats {
    /** m/s → whole km/h; null for missing or nonsense values. */
    fun speedKmh(mps: Float?): Int? = mps?.takeIf { it.isFinite() && it >= 0f }?.let { (it * 3.6f).roundToInt() }

    /** Below this speed a "Driving · 0 km/h" label is noise, so the speed is left out. */
    const val MIN_SPEED_TO_SHOW_KMH = 3

    /** Whole minutes between two instants, never negative. */
    fun minutesBetween(fromMs: Long, toMs: Long): Long = ((toMs - fromMs).coerceAtLeast(0) / 60_000L)

    /** Compass direction for a bearing (0 = north), as one of 8 sectors. */
    fun compassSector(bearingDeg: Double): Int = Math.floorMod(((bearingDeg + 22.5) / 45.0).toInt(), 8)

    @StringRes
    fun activityLabel(activity: Activity?): Int = when (activity) {
        Activity.STILL -> R.string.activity_still
        Activity.WALKING -> R.string.activity_walking
        Activity.RUNNING -> R.string.activity_running
        Activity.CYCLING -> R.string.activity_cycling
        Activity.VEHICLE -> R.string.activity_vehicle
        Activity.UNKNOWN, null -> R.string.activity_unknown
    }

    @StringRes
    fun directionLabel(sector: Int): Int = when (sector) {
        0 -> R.string.direction_north
        1 -> R.string.direction_northeast
        2 -> R.string.direction_east
        3 -> R.string.direction_southeast
        4 -> R.string.direction_south
        5 -> R.string.direction_southwest
        6 -> R.string.direction_west
        else -> R.string.direction_northwest
    }

    /** The user's 12/24-hour preference, e.g. "14:32" or "2:32 PM". */
    fun clockTime(context: Context, epochMs: Long): String = DateFormat.getTimeFormat(context).format(Date(epochMs))

    /** "18 km", "2.4 km", "350 m". */
    fun distance(context: Context, meters: Double): String = when {
        meters >= 10_000 -> context.getString(R.string.distance_km_whole, (meters / 1000).roundToInt())
        meters >= 1_000 -> context.getString(R.string.distance_km_decimal, String.format(Locale.getDefault(), "%.1f", meters / 1000))
        else -> context.getString(R.string.distance_m, (meters / 10).roundToInt() * 10)
    }
}

/** Remembered clock formatter honoring the 12/24-hour setting. */
@Composable
fun rememberClock(): (Long) -> String {
    val context = LocalContext.current
    val format = remember(context) { DateFormat.getTimeFormat(context) }
    return remember(format) { { ms: Long -> format.format(Date(ms)) } }
}

/** Weekday, date and time ("Wed, 15 Oct, 14:32"), honoring the 12/24-hour setting. For times beyond today. */
@Composable
fun dateTimeText(epochMs: Long): String {
    val context = LocalContext.current
    return remember(context, epochMs) {
        DateUtils.formatDateTime(
            context,
            epochMs,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or
                DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME,
        )
    }
}

/** "Now", "2 min ago", "3 h ago". */
@Composable
fun ageText(thenMs: Long, nowMs: Long): String {
    val minutes = Formats.minutesBetween(thenMs, nowMs)
    return when {
        minutes < 1 -> stringResource(R.string.age_now)
        minutes < 60 -> pluralStringResource(R.plurals.age_minutes, minutes.toInt(), minutes.toInt())
        else -> {
            val hours = (minutes / 60).toInt()
            pluralStringResource(R.plurals.age_hours, hours, hours)
        }
    }
}

@Composable
fun distanceText(meters: Double): String = Formats.distance(LocalContext.current, meters)
