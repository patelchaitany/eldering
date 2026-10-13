package com.eldering.safety.alert

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.eldering.safety.network.IncidentKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Result of handing one SMS to the platform. */
sealed interface SmsResult {
    /** Handed to the SMS manager in [parts] parts. */
    data class Queued(val parts: Int) : SmsResult

    /** Debug build with "Allow real SMS" off: the exact text was logged (tag `EldSms`), nothing was sent. */
    data object DryRun : SmsResult

    data object NoPermission : SmsResult

    /** No telephony / SMS on this device. */
    data object Unsupported : SmsResult

    data class Failed(val reason: String) : SmsResult
}

/** Sends one text. Implementations never throw (unit tests use a fake; nothing in tests may text anyone). */
fun interface SmsSender {
    suspend fun send(to: String, body: String): SmsResult
}

/**
 * Platform SMS (docs/research/android-background.md §9): a subscription-bound [SmsManager] (never a multi-SIM
 * dialog in the background), `divideMessage` + `sendMultipartTextMessage`, sent/delivered reports to
 * [SmsStatusReceiver].
 *
 * Test safety (SPEC): unless [realSmsAllowed] (release builds, or the debug "Allow real SMS" switch), nothing is
 * sent — the exact text is written to logcat under tag [LOG_TAG] instead.
 */
class AndroidSmsSender(
    private val context: Context,
    private val realSmsAllowed: suspend () -> Boolean,
) : SmsSender {
    private val ids = AtomicLong(System.currentTimeMillis() and 0xFFFFF)

    override suspend fun send(to: String, body: String): SmsResult {
        val id = ids.incrementAndGet()
        if (!realSmsAllowed()) {
            Log.i(LOG_TAG, "DRY-RUN SMS #$id to ${mask(to)} (${body.length} chars, nothing sent):\n$body")
            return SmsResult.DryRun
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(LOG_TAG, "SMS #$id to ${mask(to)} not sent: SEND_SMS not granted")
            return SmsResult.NoPermission
        }
        val feature = if (Build.VERSION.SDK_INT >= 33) PackageManager.FEATURE_TELEPHONY_MESSAGING else PackageManager.FEATURE_TELEPHONY
        if (!context.packageManager.hasSystemFeature(feature)) return SmsResult.Unsupported
        val sms = smsManager() ?: return SmsResult.Unsupported
        return try {
            val parts = sms.divideMessage(body)
            val sent = ArrayList<PendingIntent>(parts.size)
            val delivered = ArrayList<PendingIntent>(parts.size)
            parts.indices.forEach { i ->
                val rc = ((id and 0xFFFFF).toInt() shl 4) or (i and 0xF)
                fun status(action: String) = Intent(context, SmsStatusReceiver::class.java).setAction(action)
                    .putExtra(SmsStatusReceiver.EXTRA_MSG_ID, id).putExtra(SmsStatusReceiver.EXTRA_PART, i)
                    .putExtra(SmsStatusReceiver.EXTRA_PARTS, parts.size)
                sent += PendingIntent.getBroadcast(context, rc, status(SmsStatusReceiver.ACTION_SENT), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                // Delivery reports carry the PDU as fill-in extras: mutable, with an explicit component.
                delivered += PendingIntent.getBroadcast(context, rc, status(SmsStatusReceiver.ACTION_DELIVERED), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            if (Build.VERSION.SDK_INT >= 30) {
                sms.sendMultipartTextMessage(to, null, parts, sent, delivered, id)
            } else {
                sms.sendMultipartTextMessage(to, null, parts, sent, delivered)
            }
            Log.i(LOG_TAG, "SMS #$id to ${mask(to)} queued (${parts.size} parts)")
            SmsResult.Queued(parts.size)
        } catch (e: Exception) { // SecurityException, IllegalArgumentException, RuntimeException from the RIL
            Log.w(LOG_TAG, "SMS #$id to ${mask(to)} failed: ${e.javaClass.simpleName}")
            SmsResult.Failed(e.javaClass.simpleName)
        }
    }

    private fun smsManager(): SmsManager? {
        val subId = SmsManager.getDefaultSmsSubscriptionId()
        val valid = subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
        return if (Build.VERSION.SDK_INT >= 31) {
            val base = context.getSystemService(SmsManager::class.java) ?: return null
            if (valid) base.createForSubscriptionId(subId) else base
        } else {
            @Suppress("DEPRECATION")
            if (valid) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
    }

    companion object {
        const val LOG_TAG = "EldSms"

        /** Keeps the last two digits only (logs never carry a full phone number). */
        fun mask(number: String): String = if (number.length <= 2) "••" else "•".repeat(number.length - 2) + number.takeLast(2)
    }
}

/** Sent/delivered reports for [AndroidSmsSender] (not exported; PendingIntents only). Logged under `EldSms`. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_MSG_ID, -1)
        val part = intent.getIntExtra(EXTRA_PART, -1)
        val parts = intent.getIntExtra(EXTRA_PARTS, -1)
        when (intent.action) {
            ACTION_SENT -> {
                val ok = resultCode == Activity.RESULT_OK
                Log.i(AndroidSmsSender.LOG_TAG, "SMS #$id part ${part + 1}/$parts ${if (ok) "sent" else "failed (result $resultCode)"}")
            }
            ACTION_DELIVERED -> Log.i(AndroidSmsSender.LOG_TAG, "SMS #$id part ${part + 1}/$parts delivery report")
        }
    }

    companion object {
        const val ACTION_SENT = "com.eldering.safety.SMS_SENT"
        const val ACTION_DELIVERED = "com.eldering.safety.SMS_DELIVERED"
        const val EXTRA_MSG_ID = "msg_id"
        const val EXTRA_PART = "part"
        const val EXTRA_PARTS = "parts"
    }
}

/**
 * The emergency SMS. Plain ASCII apart from the person's name (GSM-7 keeps it to 1–2 parts), always with raw
 * coordinates and a maps link — the share link needs the server, the coordinates do not (offline fallback).
 */
object SmsText {
    fun build(
        name: String?,
        kind: IncidentKind,
        detectedAtMs: Long,
        lat: Double?,
        lng: Double?,
        accuracyM: Float?,
        shareUrl: String?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val who = name?.trim()?.takeIf { it.isNotEmpty() }?.take(40) ?: "The owner of this phone"
        val what = when (kind) {
            IncidentKind.VEHICLE_CRASH -> "may have been in a car crash"
            IncidentKind.PEDESTRIAN_IMPACT -> "may have been hit while walking"
            IncidentKind.FALL -> "may have fallen"
            IncidentKind.SOS -> "pressed SOS"
        }
        val time = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(zone).format(Instant.ofEpochMilli(detectedAtMs))
        return buildString {
            append("ELDERING ALERT: ").append(who).append(' ').append(what).append(" at ").append(time).append('.')
            if (shareUrl != null) append(" Live location: ").append(shareUrl)
            if (lat != null && lng != null) {
                append(" Last position: ").append(coord(lat)).append(',').append(coord(lng))
                accuracyM?.takeIf { it.isFinite() && it > 0f }?.let { append(" (+/-").append(Math.round(it)).append(" m)") }
                append(" https://maps.google.com/?q=").append(coord(lat)).append(',').append(coord(lng))
            } else {
                append(" Position unknown.")
            }
            append(" If you can't reach them, call 112.")
        }
    }

    private fun coord(v: Double): String = String.format(Locale.ROOT, "%.5f", v)
}
