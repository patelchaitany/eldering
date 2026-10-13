package com.eldering.safety.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eldering.safety.ui.theme.Eldering

/** Colors of a [DesignButton]. */
@Immutable
data class ButtonColors(val container: Color, val content: Color, val border: BorderStroke? = null)

object DesignButtons {
    /** Dark ink fill ("Last 1 hour", "Share trail"). */
    @Composable
    fun ink() = ButtonColors(Eldering.palette.ink, Eldering.palette.onInk)

    /** White with hairline border ("Call", "Directions", "Open in Maps"). */
    @Composable
    fun outline() = ButtonColors(Eldering.palette.surface, Eldering.palette.ink, BorderStroke(1.dp, Eldering.palette.hairline))

    /** Orange accident/SOS fill ("Call 112", "Send help now"). */
    @Composable
    fun alert() = ButtonColors(Eldering.palette.alert, Eldering.palette.onAlert)

    /** Green confirmation fill. */
    @Composable
    fun safe() = ButtonColors(Eldering.palette.safe, Color.White)

    /** Muted fill ("Pause 1 hour"). */
    @Composable
    fun muted() = ButtonColors(Eldering.palette.surfaceDim, Eldering.palette.ink, BorderStroke(1.dp, Eldering.palette.hairline))
}

/**
 * The design's rounded text button. Always at least 48 dp tall (accessibility), even where the mockup used
 * 44 px. Shows a spinner instead of the label while [loading].
 */
@Composable
fun DesignButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ButtonColors = DesignButtons.ink(),
    height: Dp = 52.dp,
    shape: Shape = RoundedCornerShape(16.dp),
    textStyle: TextStyle = Eldering.type.button,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    textAlign: TextAlign = TextAlign.Center,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp),
    contentDescription: String? = null,
) {
    val alpha = if (enabled) 1f else 0.45f
    Surface(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = shape,
        color = colors.container.copy(alpha = colors.container.alpha * alpha),
        contentColor = colors.content.copy(alpha = alpha),
        border = colors.border,
        modifier = modifier
            .heightIn(min = maxOf(height, 48.dp))
            .semantics {
                role = Role.Button
                if (contentDescription != null) this.contentDescription = contentDescription
            },
    ) {
        Box(
            contentAlignment = if (textAlign == TextAlign.Start) Alignment.CenterStart else Alignment.Center,
            modifier = Modifier.padding(contentPadding),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = colors.content,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (leadingIcon != null) {
                        Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                    Text(text = text, style = textStyle, textAlign = textAlign)
                }
            }
        }
    }
}

/** 48 dp round header button with a hairline border (design: notifications, sharing, back). */
@Composable
fun IconCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: Boolean = false,
) {
    Box(modifier = modifier) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = Eldering.palette.surface,
            contentColor = Eldering.palette.ink,
            border = BorderStroke(1.dp, Eldering.palette.hairline),
            modifier = Modifier
                .size(48.dp)
                .semantics { this.contentDescription = contentDescription },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        if (badge) {
            Surface(
                shape = CircleShape,
                color = Eldering.palette.alert,
                border = BorderStroke(2.dp, Eldering.palette.surface),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(14.dp),
            ) {}
        }
    }
}

/** Text-only button ("Not now", "Replay"), 48 dp tall. */
@Composable
fun TextLinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Eldering.palette.link,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        contentColor = color,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics {
                role = Role.Button
                if (contentDescription != null) this.contentDescription = contentDescription
            },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text(text, style = Eldering.type.buttonSmall)
        }
    }
}
