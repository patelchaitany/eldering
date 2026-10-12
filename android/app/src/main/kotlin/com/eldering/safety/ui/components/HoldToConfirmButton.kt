package com.eldering.safety.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Press-and-hold button (design: "Hold 3 s for SOS"). A progress fill grows while the finger stays down; letting
 * go early cancels. [onConfirmed] fires once per completed hold.
 *
 * TalkBack: double-tap-and-hold passes through as a real hold; the long-press accessibility action (which is
 * itself a deliberate gesture) confirms immediately.
 */
@Composable
fun HoldToConfirmButton(
    idleLabel: String,
    holdingLabel: (secondsLeft: Int) -> String,
    accessibilityLabel: String,
    accessibilityActionLabel: String,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color,
    contentColor: Color = Color.White,
    holdMillis: Int = 3_000,
    enabled: Boolean = true,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val confirm by rememberUpdatedState(onConfirmed)
    var holding by remember { mutableStateOf(false) }

    val secondsLeft = ((1f - progress.value) * holdMillis / 1000f).let { kotlin.math.ceil(it).toInt() }.coerceAtLeast(1)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) color else color.copy(alpha = 0.45f))
            .semantics {
                role = Role.Button
                contentDescription = accessibilityLabel
                if (enabled) {
                    onLongClick(label = accessibilityActionLabel) {
                        confirm()
                        true
                    }
                }
            }
            .pointerInput(enabled, holdMillis) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    holding = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    var completed = false
                    val job: Job = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(durationMillis = holdMillis, easing = LinearEasing))
                        completed = true
                        holding = false
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        confirm()
                        progress.snapTo(0f)
                    }
                    waitForUpOrCancellation()
                    if (!completed) {
                        job.cancel()
                        holding = false
                        scope.launch { progress.animateTo(0f, tween(durationMillis = 180)) }
                    }
                }
            },
    ) {
        // Darker fill that grows left to right while holding.
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(progress.value.coerceIn(0f, 1f))
                .background(Color.Black.copy(alpha = 0.22f)),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(ElderingIcons.Warning, contentDescription = null, tint = contentColor, modifier = Modifier.size(22.dp))
            Text(
                if (holding) holdingLabel(secondsLeft) else idleLabel,
                style = com.eldering.safety.ui.theme.Eldering.type.sos,
                color = contentColor,
            )
        }
    }
}
