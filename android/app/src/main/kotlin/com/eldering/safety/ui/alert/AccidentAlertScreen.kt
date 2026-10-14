package com.eldering.safety.ui.alert

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eldering.safety.R
import com.eldering.safety.network.Activity as Mode
import com.eldering.safety.network.IncidentKind
import com.eldering.safety.ui.common.asString
import com.eldering.safety.ui.common.rememberClock
import com.eldering.safety.ui.components.DesignButton
import com.eldering.safety.ui.components.ButtonColors
import com.eldering.safety.ui.components.ElderingIcons
import com.eldering.safety.ui.theme.AlertScreenColors
import com.eldering.safety.ui.theme.Eldering
import com.eldering.safety.ui.theme.ElderingTheme
import kotlinx.coroutines.delay

/**
 * The accident countdown ("Are you OK?"), for `AccidentAlertActivity` and the in-app SOS screen.
 *
 * `alert.AccidentAlertActivity` (the full-screen-intent target: `showWhenLocked`, `turnScreenOn`, no keyguard
 * dismiss) hosts it for detected accidents: `alert.AlertController` owns the countdown, supplies
 * [AccidentAlertInfo] (via `alert.AlertPresentation`) and implements `alert.AlertActions`. Debug builds also show
 * every state in the UI gallery (`debug.UiGalleryActivity`, pages `countdown`, `countdown-vehicle`, `sent`,
 * `cancelled`) with a logging AlertActions that sends nothing.
 *
 * Usage from an activity:
 * ```
 * val vm: AccidentAlertViewModel = viewModel(factory = AccidentAlertViewModel.factory(info, alertActions, sink))
 * setContent { ElderingTheme { AccidentAlertRoute(vm, onClose = ::finish) } }
 * ```
 * Always dark (design). Keeps the display on while counting and for two minutes once sent (not for the whole live
 * hour), closes the cancelled/feedback screen after a minute without interaction, and ignores Back while counting so
 * a pocket press cannot dismiss it.
 */
@Composable
fun AccidentAlertRoute(viewModel: AccidentAlertViewModel, onClose: () -> Unit, onAutoClose: () -> Unit = onClose) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val autoClose by rememberUpdatedState(onAutoClose)
    // "Glad you're OK" / "What happened?": closes itself after a minute without a choice, so a phone put back in a
    // pocket does not keep the alert screen up.
    LaunchedEffect(state.phase, state.feedback) {
        if (state.phase == AlertPhase.CANCELLED) {
            delay(AlertScreenTiming.AUTO_CLOSE_MS)
            autoClose()
        }
    }
    AccidentAlertScreen(
        state = state,
        onImOk = viewModel::imOk,
        onSendNow = viewModel::sendNow,
        onCallEmergency = viewModel::callEmergency,
        onFeedback = viewModel::giveFeedback,
        onClose = onClose,
    )
}

/** How long the alert screen keeps the display on and stays up (battery: the display is the largest draw). */
object AlertScreenTiming {
    /** After the alert went out the display may sleep again after this (the live updates do not need it). */
    const val SENT_SCREEN_ON_MS = 2 * 60_000L

    /** The cancelled/feedback screen closes itself after this long without interaction. */
    const val AUTO_CLOSE_MS = 60_000L

    /**
     * After the screen switches to "sent" (the countdown ran out: "I'm OK" becomes "Help is on the way"), taps
     * are ignored this long. A tap meant for "I'm OK" that lands just after expiry must not hit whatever button now
     * sits there (it once opened the dialer with 112). Not applied when the screen opens in a phase.
     */
    const val TAP_GUARD_MS = 1_000L

    /** Display on: always while counting, for [SENT_SCREEN_ON_MS] once sent, never afterwards. */
    fun keepScreenOn(phase: AlertPhase, msInPhase: Long): Boolean = when (phase) {
        AlertPhase.COUNTING -> true
        AlertPhase.SENT -> msInPhase < SENT_SCREEN_ON_MS
        AlertPhase.CANCELLED -> false
    }
}

@Composable
fun AccidentAlertScreen(
    state: AccidentAlertUiState,
    onImOk: () -> Unit,
    onSendNow: () -> Unit,
    onCallEmergency: () -> Unit,
    onFeedback: (FeedbackChoice) -> Unit,
    onClose: () -> Unit,
) {
    var sentScreenOnOver by remember(state.phase) { mutableStateOf(false) }
    LaunchedEffect(state.phase) {
        if (state.phase == AlertPhase.SENT) {
            delay(AlertScreenTiming.SENT_SCREEN_ON_MS)
            sentScreenOnOver = true
        }
    }
    LightSystemBars()
    KeepScreenOn(AlertScreenTiming.keepScreenOn(state.phase, if (sentScreenOnOver) AlertScreenTiming.SENT_SCREEN_ON_MS else 0L))
    // Counting: Back does nothing (the user must choose). Afterwards Back behaves like the close button.
    BackHandler(enabled = state.phase == AlertPhase.COUNTING) {}
    val guarded = rememberPhaseChangeTapGuard(state.phase)
    Box(Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .fillMaxSize()
                .background(AlertScreenColors.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 24.dp),
        ) {
            when (state.phase) {
                AlertPhase.COUNTING -> CountingContent(state, onImOk, onSendNow, onCallEmergency)
                AlertPhase.SENT -> SentContent(state, onImOk, onCallEmergency)
                AlertPhase.CANCELLED -> CancelledContent(state, onFeedback, onClose)
            }
        }
        if (guarded) TapShield()
    }
}

/**
 * True for [AlertScreenTiming.TAP_GUARD_MS] after the screen switched to "sent" while it was showing (the countdown
 * ran out, or "Send help now"), not when it opened in that phase. Keyed on the phase, so it is already true in the
 * first frame of the new phase. The cancelled screen needs none: only harmless feedback buttons sit there.
 */
@Composable
private fun rememberPhaseChangeTapGuard(phase: AlertPhase): Boolean {
    val openedIn = remember { phase }
    var guarded by remember(phase) { mutableStateOf(phase == AlertPhase.SENT && phase != openedIn) }
    LaunchedEffect(phase) {
        if (guarded) {
            delay(AlertScreenTiming.TAP_GUARD_MS)
            guarded = false
        }
    }
    return guarded
}

/** Swallows every touch on the screen (an overlay above the buttons, so nothing below sees the press). */
@Composable
private fun BoxScope.TapShield() {
    Box(
        Modifier
            .matchParentSize()
            .testTag(TAP_SHIELD_TAG)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
    )
}

/** Test tag of the touch shield shown right after a phase change. */
const val TAP_SHIELD_TAG = "alert-tap-shield"

@Composable
private fun ColumnScope.CountingContent(
    state: AccidentAlertUiState,
    onImOk: () -> Unit,
    onSendNow: () -> Unit,
    onCallEmergency: () -> Unit,
) {
    val clock = rememberClock()
    val info = state.info
    Column(
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState()),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Overline(stringResource(if (info.kind == IncidentKind.SOS) R.string.alert_overline_sos else R.string.alert_overline_possible), AlertScreenColors.accent)
            Title(stringResource(R.string.alert_are_you_ok))
            Text(
                stringResource(whatHappened(info.kind, info.activity), clock(info.detectedAtMs)),
                style = Eldering.type.body,
                color = AlertScreenColors.textMuted,
            )
        }
        CountdownRing(state.secondsLeft)
        AlertCard {
            Text(stringResource(R.string.alert_will_send), style = Eldering.type.bodySmallStrong, color = AlertScreenColors.text)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Bullet(stringResource(R.string.alert_will_send_location))
                Bullet(recipientsText(info))
                if (info.emergencyContactCount > 0) Bullet(stringResource(R.string.alert_will_send_sms_offline))
            }
        }
    }
    DesignButton(
        text = stringResource(R.string.alert_im_ok),
        onClick = onImOk,
        colors = ButtonColors(Color.White, AlertScreenColors.background),
        height = 72.dp,
        shape = RoundedCornerShape(20.dp),
        textStyle = Eldering.type.alertTitle.copy(fontSize = 22.sp),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DesignButton(
            text = stringResource(R.string.alert_send_now),
            onClick = onSendNow,
            colors = ButtonColors(AlertScreenColors.ring, Color.White),
            height = 56.dp,
            textStyle = Eldering.type.buttonLarge,
            modifier = Modifier.weight(1f),
        )
        DesignButton(
            text = stringResource(R.string.alert_call_emergency),
            onClick = onCallEmergency,
            colors = ButtonColors(Color.Transparent, Color.White, BorderStroke(2.dp, AlertScreenColors.accent)),
            height = 56.dp,
            textStyle = Eldering.type.buttonLarge,
            contentDescription = stringResource(R.string.alert_call_emergency_a11y),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ColumnScope.SentContent(state: AccidentAlertUiState, onImOk: () -> Unit, onCallEmergency: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState()),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Overline(stringResource(R.string.alert_overline_sent), AlertScreenColors.accent)
            Title(stringResource(R.string.alert_family_alerted))
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = AlertScreenColors.card,
            border = BorderStroke(1.dp, AlertScreenColors.cardBorder),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                state.deliveries.forEachIndexed { index, line ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .semantics(mergeDescendants = true) {},
                    ) {
                        Text(line.who.asString(), style = Eldering.type.body, color = AlertScreenColors.text, modifier = Modifier.weight(1f))
                        Text(
                            stringResource(deliveryLabel(line.state)),
                            style = Eldering.type.captionBold,
                            color = if (line.state == DeliveryState.FAILED || line.state == DeliveryState.NOT_SENT) AlertScreenColors.accent else AlertScreenColors.good,
                        )
                    }
                    if (index < state.deliveries.lastIndex) HorizontalDivider(color = AlertScreenColors.cardBorder)
                }
            }
        }
        Text(stringResource(R.string.alert_sharing_live), style = Eldering.type.bodySmall, color = AlertScreenColors.textMuted)
    }
    // Deliberately not the design's order (Call 112 above "I'm safe now"): every button keeps the meaning of the one
    // that was in its place during the countdown. A late "I'm OK" tap lands on "I'm safe now" (which asks first), and
    // a late "Send help now" tap lands on "Call 112" (the dialer only), never the other way round.
    var confirmSafe by rememberSaveable { mutableStateOf(false) }
    DesignButton(
        text = stringResource(R.string.alert_safe_now),
        onClick = { confirmSafe = true },
        colors = ButtonColors(Color.Transparent, Color.White, BorderStroke(2.dp, Color.White)),
        height = 64.dp,
        shape = RoundedCornerShape(18.dp),
        textStyle = Eldering.type.buttonLarge,
        modifier = Modifier.fillMaxWidth(),
    )
    DesignButton(
        text = stringResource(R.string.alert_call_emergency),
        onClick = onCallEmergency,
        colors = ButtonColors(AlertScreenColors.ring, Color.White),
        height = 64.dp,
        shape = RoundedCornerShape(18.dp),
        textStyle = Eldering.type.alertTitle.copy(fontSize = 20.sp),
        contentDescription = stringResource(R.string.alert_call_emergency_a11y),
        modifier = Modifier.fillMaxWidth(),
    )
    if (confirmSafe) {
        SafeNowConfirmation(
            onConfirm = {
                confirmSafe = false
                onImOk()
            },
            onDismiss = { confirmSafe = false },
        )
    }
}

/** "I'm safe now" ends the alert for everyone, so it asks once (a stray tap must not call off the help). */
@Composable
fun SafeNowConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.alert_safe_confirm_title)) },
        text = { Text(stringResource(R.string.alert_safe_confirm_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.alert_safe_confirm_yes)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ColumnScope.CancelledContent(state: AccidentAlertUiState, onFeedback: (FeedbackChoice) -> Unit, onClose: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState()),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Overline(stringResource(R.string.alert_overline_cancelled), AlertScreenColors.good)
            Title(stringResource(R.string.alert_glad_ok))
            Text(
                stringResource(if (state.info.kind == IncidentKind.SOS) R.string.alert_sos_resolved else R.string.alert_what_happened),
                style = Eldering.type.body,
                color = AlertScreenColors.textMuted,
            )
        }
        if (state.info.kind != IncidentKind.SOS) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FeedbackChoice.entries.forEach { choice ->
                val selected = state.feedback == choice
                Surface(
                    onClick = { onFeedback(choice) },
                    shape = RoundedCornerShape(16.dp),
                    color = AlertScreenColors.card,
                    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) AlertScreenColors.good else AlertScreenColors.cardBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .semantics { this.selected = selected },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            stringResource(feedbackLabel(choice)),
                            style = Eldering.type.bodyStrong.copy(fontSize = 16.sp),
                            color = AlertScreenColors.text,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 14.dp),
                        )
                        if (selected) Icon(ElderingIcons.Check, contentDescription = null, tint = AlertScreenColors.good, modifier = Modifier.size(20.dp))
                    }
                }
            }
            if (state.feedback != null) {
                Text(
                    stringResource(R.string.alert_feedback_thanks),
                    style = Eldering.type.caption,
                    color = AlertScreenColors.textMuted,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
    DesignButton(
        text = stringResource(R.string.alert_back_to_map),
        onClick = onClose,
        colors = ButtonColors(Color.White, AlertScreenColors.background),
        height = 56.dp,
        textStyle = Eldering.type.buttonLarge,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CountdownRing(secondsLeft: Int) {
    // Announce every 5 s, then every second for the last 5, so TalkBack is informative without a backlog.
    val announced = if (secondsLeft <= 5) secondsLeft else ((secondsLeft + 4) / 5) * 5
    val label = pluralStringResource(R.plurals.alert_timer_a11y, announced, announced)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .size(188.dp)
                .clip(CircleShape)
                .background(AlertScreenColors.card)
                .border(10.dp, AlertScreenColors.ring, CircleShape)
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    liveRegion = LiveRegionMode.Polite
                },
        ) {
            Text("$secondsLeft", style = Eldering.type.countdown, color = AlertScreenColors.text, textAlign = TextAlign.Center)
            Text(stringResource(R.string.alert_seconds), style = Eldering.type.caption, color = AlertScreenColors.textMuted)
        }
    }
}

@Composable
private fun AlertCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = AlertScreenColors.card,
        border = BorderStroke(1.dp, AlertScreenColors.cardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun Overline(text: String, color: Color) {
    Text(text.uppercase(), style = Eldering.type.overline, color = color)
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        style = Eldering.type.alertTitle,
        color = AlertScreenColors.text,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun Bullet(text: String) {
    Text("· $text", style = Eldering.type.bodySmall, color = AlertScreenColors.textMuted)
}

@Composable
private fun recipientsText(info: AccidentAlertInfo): String {
    val names = info.familyRecipients.joinToString(", ")
    val contacts = info.emergencyContactCount
    val contactsText = if (contacts > 0) pluralStringResource(R.plurals.alert_emergency_contacts, contacts, contacts) else ""
    return when {
        names.isNotEmpty() && contacts > 0 -> stringResource(R.string.alert_to_family_and_contacts, names, contactsText)
        names.isNotEmpty() -> stringResource(R.string.alert_to_family, names)
        contacts > 0 -> stringResource(R.string.alert_to_contacts, contactsText)
        else -> stringResource(R.string.alert_to_family_generic)
    }
}

private fun whatHappened(kind: IncidentKind, mode: Mode?): Int = when (kind) {
    IncidentKind.VEHICLE_CRASH -> R.string.alert_what_vehicle
    IncidentKind.PEDESTRIAN_IMPACT -> R.string.alert_what_walking
    IncidentKind.FALL -> if (mode == Mode.WALKING || mode == Mode.RUNNING) R.string.alert_what_walking else R.string.alert_what_fall
    IncidentKind.SOS -> R.string.alert_what_sos
}

private fun deliveryLabel(state: DeliveryState): Int = when (state) {
    DeliveryState.SENDING -> R.string.delivery_sending
    DeliveryState.SENT -> R.string.delivery_sent
    DeliveryState.DELIVERED -> R.string.delivery_delivered
    DeliveryState.SEEN -> R.string.delivery_seen
    DeliveryState.ON_THE_WAY -> R.string.delivery_on_the_way
    DeliveryState.DRY_RUN -> R.string.delivery_dry_run
    DeliveryState.NOT_SENT -> R.string.delivery_not_sent
    DeliveryState.FAILED -> R.string.delivery_failed
}

private fun feedbackLabel(choice: FeedbackChoice): Int = when (choice) {
    FeedbackChoice.DROPPED_PHONE -> R.string.feedback_dropped_phone
    FeedbackChoice.FELL_BUT_FINE -> R.string.feedback_fell_fine
    FeedbackChoice.MINOR_ACCIDENT -> R.string.feedback_minor_accident
    FeedbackChoice.SOMETHING_ELSE -> R.string.feedback_something_else
}

/** Light status/navigation bar icons on the dark alert background. */
@Composable
private fun LightSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (controller != null && previousStatus != null && previousNav != null) {
                controller.isAppearanceLightStatusBars = previousStatus
                controller.isAppearanceLightNavigationBars = previousNav
            }
        }
    }
}

/** Keeps the display on while [on]; the normal screen timeout applies otherwise. */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun CountdownPreview() {
    ElderingTheme {
        AccidentAlertScreen(
            AccidentAlertUiState(
                info = AccidentAlertInfo(IncidentKind.PEDESTRIAN_IMPACT, 0L, 15_000L, Mode.WALKING, listOf("Mom", "Dad"), 1),
                phase = AlertPhase.COUNTING,
                secondsLeft = 14,
                deliveries = emptyList(),
            ),
            {}, {}, {}, {}, {},
        )
    }
}
