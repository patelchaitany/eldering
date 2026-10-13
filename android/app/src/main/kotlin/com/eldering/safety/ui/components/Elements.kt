package com.eldering.safety.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.eldering.safety.ui.theme.Eldering

/** Rounded status pill ("Protection on · Walking"). */
@Composable
fun StatusChip(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    val body: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            if (dotColor != null) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
            }
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
            }
            Text(text, style = Eldering.type.captionStrong, color = content)
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(999.dp),
            color = container,
            modifier = modifier.heightIn(min = 32.dp),
        ) { Box(contentAlignment = Alignment.CenterStart) { body() } }
    } else {
        Surface(shape = RoundedCornerShape(999.dp), color = container, modifier = modifier) { body() }
    }
}

/**
 * Initials avatar. [ring] draws the map-marker style white border + shadow; [selectedColor] adds the outer
 * selection outline from the design (3 px outline, 2 px offset).
 */
@Composable
fun Avatar(
    initials: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    ring: Boolean = true,
    selectedColor: Color? = null,
) {
    val outer = if (selectedColor != null) {
        modifier
            .border(3.dp, selectedColor, CircleShape)
            .padding(5.dp)
    } else {
        modifier
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = outer
            .size(size)
            .then(if (ring) Modifier.shadow(3.dp, CircleShape) else Modifier)
            .clip(CircleShape)
            .background(color)
            .then(if (ring) Modifier.border(3.dp, Color.White, CircleShape) else Modifier),
    ) {
        Text(initials, style = Eldering.type.initials, color = Color.White, maxLines = 1)
    }
}

/** First letters of up to two words ("Asha Rivera" → "AR"; "Dad" → "D"). */
fun initialsOf(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/** The design's 52×32 switch with a 48 dp touch target and switch semantics for TalkBack. */
@Composable
fun DesignSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val track by animateColorAsState(if (checked) Eldering.palette.safe else Eldering.palette.outline, label = "track")
    val knobOffset by animateDpAsState(if (checked) 20.dp else 0.dp, label = "knob")
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 48.dp)
            .width(56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (enabled) track else track.copy(alpha = 0.5f))
                .padding(3.dp),
        ) {
            Box(
                Modifier
                    .offset { IntOffset(knobOffset.roundToPx(), 0) }
                    .size(26.dp)
                    .shadow(1.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

/** One option of a [LevelPicker]. */
@Immutable
data class LevelOption<T>(val value: T, val label: String, val enabled: Boolean = true)

/**
 * Segmented "Live / Emergency only / Off" control (design: Sharing). Exposed to TalkBack as a radio group
 * named [groupLabel].
 */
@Composable
fun <T> LevelPicker(
    options: List<LevelOption<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    groupLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Eldering.palette.surfaceMuted)
            .padding(4.dp)
            .semantics { contentDescription = groupLabel }
            .selectableGroup(),
    ) {
        options.forEach { option ->
            val isSelected = option.value == selected
            val optionEnabled = enabled && option.enabled
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSelected) Eldering.palette.ink else Color.Transparent)
                    .selectable(
                        selected = isSelected,
                        enabled = optionEnabled,
                        role = Role.RadioButton,
                        onClick = { if (!isSelected) onSelect(option.value) },
                    )
                    .padding(horizontal = 4.dp),
            ) {
                Text(
                    option.label,
                    style = Eldering.type.captionBold,
                    textAlign = TextAlign.Center,
                    color = when {
                        isSelected -> Eldering.palette.onInk
                        !optionEnabled -> Eldering.palette.inkDisabled
                        else -> Eldering.palette.ink
                    },
                )
            }
        }
    }
}

/** White rounded card with the design's hairline border. */
@Composable
fun DesignCard(
    modifier: Modifier = Modifier,
    color: Color = Eldering.palette.surface,
    border: BorderStroke? = BorderStroke(1.dp, Eldering.palette.divider),
    contentPadding: PaddingValues = PaddingValues(14.dp),
    verticalSpacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = color, border = border, modifier = modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
            modifier = Modifier.padding(contentPadding),
            content = content,
        )
    }
}

/** Sub-screen header: back button + title (+ subtitle). */
@Composable
fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)?,
    backLabel: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 10.dp),
    ) {
        if (onBack != null) {
            IconCircleButton(icon = ElderingIcons.Back, contentDescription = backLabel, onClick = onBack)
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = Eldering.type.headerTitle,
                color = Eldering.palette.ink,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(subtitle, style = Eldering.type.caption, color = Eldering.palette.inkMuted)
            }
        }
        actions()
    }
}

/** Section heading inside a screen ("Members", "Who can see me"). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
    ) {
        Text(
            text,
            style = Eldering.type.sectionTitle,
            color = Eldering.palette.ink,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        trailing()
    }
}

/** Two-column detail row ("Detected · Hard impact while driving"). */
@Composable
fun DetailRow(label: String, value: String, modifier: Modifier = Modifier, showDivider: Boolean = true) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(vertical = 6.dp),
        ) {
            Text(label, style = Eldering.type.bodySmall, color = Eldering.palette.inkMuted)
            Spacer(Modifier.width(12.dp))
            Text(
                value,
                style = Eldering.type.bodySmallStrong,
                color = Eldering.palette.ink,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 1.dp, max = 1.dp)
                    .background(Eldering.palette.dividerSoft),
            )
        }
    }
}

/** A tappable settings-style row with a chevron. */
@Composable
fun NavigationRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    tint: Color = Eldering.palette.ink,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Eldering.palette.surface,
        border = BorderStroke(1.dp, Eldering.palette.divider),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = Eldering.type.name, color = tint)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = Eldering.type.micro,
                        color = Eldering.palette.inkMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(ElderingIcons.ChevronRight, contentDescription = null, tint = Eldering.palette.inkMuted, modifier = Modifier.size(20.dp))
        }
    }
}
