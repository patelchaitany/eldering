package com.eldering.safety.ui.sharing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eldering.safety.R
import com.eldering.safety.network.GrantLevel
import com.eldering.safety.network.SharingState
import com.eldering.safety.ui.common.appViewModel
import com.eldering.safety.ui.common.asString
import com.eldering.safety.ui.common.rememberClock
import com.eldering.safety.ui.components.Avatar
import com.eldering.safety.ui.components.DesignButton
import com.eldering.safety.ui.components.DesignButtons
import com.eldering.safety.ui.components.DesignCard
import com.eldering.safety.ui.components.DesignSwitch
import com.eldering.safety.ui.components.ElderingIcons
import com.eldering.safety.ui.components.ErrorBanner
import com.eldering.safety.ui.components.LevelOption
import com.eldering.safety.ui.components.LevelPicker
import com.eldering.safety.ui.components.LoadingBox
import com.eldering.safety.ui.components.NavigationRow
import com.eldering.safety.ui.components.ScreenHeader
import com.eldering.safety.ui.theme.Eldering

@Composable
fun SharingRoute(onBack: () -> Unit, onContacts: () -> Unit, onSettings: () -> Unit) {
    val viewModel = appViewModel { SharingViewModel(it.sharingRepository, it.familyRepository, it.contactsRepository, it.time) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SharingScreen(
        state = state,
        actions = SharingActions(
            onBack = onBack,
            onShareLive = viewModel::setShareLive,
            onPauseHour = viewModel::pauseOneHour,
            onPauseUntilResumed = viewModel::pauseUntilResumed,
            onAlwaysAlert = viewModel::setAlwaysAlert,
            onLevel = viewModel::setLevel,
            onAddContact = onContacts,
            onSettings = onSettings,
            onRetry = viewModel::load,
            onDismissError = viewModel::dismissError,
        ),
    )
}

class SharingActions(
    val onBack: () -> Unit,
    val onShareLive: (Boolean) -> Unit,
    val onPauseHour: () -> Unit,
    val onPauseUntilResumed: () -> Unit,
    val onAlwaysAlert: (Boolean) -> Unit,
    val onLevel: (String, GrantLevel) -> Unit,
    val onAddContact: () -> Unit,
    val onSettings: () -> Unit,
    val onRetry: () -> Unit,
    val onDismissError: () -> Unit,
)

@Composable
fun SharingScreen(state: SharingUiState, actions: SharingActions) {
    val palette = Eldering.palette
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ScreenHeader(stringResource(R.string.sharing_title), actions.onBack, stringResource(R.string.action_back_to_map))
        if (state.loading && state.settings == null) {
            LoadingBox(Modifier.weight(1f))
            return@Column
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 20.dp),
        ) {
            state.error?.let {
                ErrorBanner(it.asString(), actionLabel = stringResource(R.string.action_retry), onAction = {
                    actions.onDismissError()
                    actions.onRetry()
                })
            }
            ShareLiveCard(state, actions)
            AlwaysAlertCard(state, actions)
            WhoCanSeeMe(state, actions)
            Surface(shape = RoundedCornerShape(16.dp), color = palette.infoContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.sharing_trail_title),
                        style = Eldering.type.sectionTitle,
                        color = palette.meText,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(stringResource(R.string.sharing_trail_body), style = Eldering.type.caption, color = palette.infoText)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                RoleLegend(stringResource(R.string.role_admin), stringResource(R.string.sharing_legend_admin))
                RoleLegend(stringResource(R.string.role_member), stringResource(R.string.sharing_legend_member))
                RoleLegend(stringResource(R.string.role_emergency), stringResource(R.string.sharing_legend_emergency))
            }
            NavigationRow(
                title = stringResource(R.string.sharing_more_settings),
                subtitle = stringResource(R.string.sharing_more_settings_hint),
                icon = ElderingIcons.Settings,
                onClick = actions.onSettings,
            )
        }
    }
}

@Composable
private fun ShareLiveCard(state: SharingUiState, actions: SharingActions) {
    val clock = rememberClock()
    val settings = state.settings
    val on = settings?.effectiveSharingState == SharingState.ON
    val alertsStillWork = settings?.alwaysAlertOnAccident != false
    val hint = when (settings?.effectiveSharingState) {
        SharingState.ON -> stringResource(R.string.sharing_hint_on)
        SharingState.PAUSED -> {
            val tail = stringResource(if (alertsStillWork) R.string.sharing_hint_alerts_work else R.string.sharing_hint_alerts_off)
            val until = settings.pausedUntil
            if (until != null) stringResource(R.string.sharing_hint_paused_until, clock(until.toEpochMilli()), tail) else stringResource(R.string.sharing_hint_paused, tail)
        }
        SharingState.OFF -> stringResource(
            R.string.sharing_hint_off,
            stringResource(if (alertsStillWork) R.string.sharing_hint_alerts_work else R.string.sharing_hint_alerts_off),
        )
        null -> ""
    }
    DesignCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.sharing_live_title),
                    style = Eldering.type.cardTitle,
                    color = Eldering.palette.ink,
                    modifier = Modifier.semantics { heading() },
                )
                Text(hint, style = Eldering.type.caption, color = Eldering.palette.inkMuted)
            }
            DesignSwitch(
                checked = on,
                onCheckedChange = actions.onShareLive,
                contentDescription = stringResource(R.string.sharing_live_title),
                enabled = settings != null && !state.savingSettings,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesignButton(
                stringResource(R.string.sharing_pause_hour),
                actions.onPauseHour,
                colors = DesignButtons.muted(),
                height = 48.dp,
                shape = RoundedCornerShape(12.dp),
                textStyle = Eldering.type.buttonSmall,
                enabled = settings != null && !state.savingSettings,
                modifier = Modifier.weight(1f),
            )
            DesignButton(
                stringResource(R.string.sharing_pause_until_resume),
                actions.onPauseUntilResumed,
                colors = DesignButtons.muted(),
                height = 48.dp,
                shape = RoundedCornerShape(12.dp),
                textStyle = Eldering.type.buttonSmall,
                enabled = settings != null && !state.savingSettings,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AlwaysAlertCard(state: SharingUiState, actions: SharingActions) {
    val settings = state.settings
    DesignCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.sharing_always_alert_title),
                    style = Eldering.type.cardTitle,
                    color = Eldering.palette.ink,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.sharing_always_alert_body), style = Eldering.type.caption, color = Eldering.palette.inkMuted)
            }
            DesignSwitch(
                checked = settings?.alwaysAlertOnAccident ?: true,
                onCheckedChange = actions.onAlwaysAlert,
                contentDescription = stringResource(R.string.sharing_always_alert_title),
                enabled = settings != null && !state.savingSettings,
            )
        }
    }
}

@Composable
private fun WhoCanSeeMe(state: SharingUiState, actions: SharingActions) {
    val palette = Eldering.palette
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                stringResource(R.string.sharing_who_title),
                style = Eldering.type.sectionTitle,
                color = palette.ink,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            Text(stringResource(R.string.sharing_who_hint), style = Eldering.type.micro, color = palette.inkMuted)
        }
        if (state.people.isEmpty()) {
            Text(stringResource(R.string.sharing_nobody), style = Eldering.type.caption, color = palette.inkMuted, modifier = Modifier.padding(horizontal = 4.dp))
        }
        val live = stringResource(R.string.level_live)
        val emergency = stringResource(R.string.level_emergency)
        val off = stringResource(R.string.level_off)
        state.people.forEach { person ->
            DesignCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalSpacing = 10.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatar(person.initials, person.color, size = 36.dp, ring = false)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(person.name, style = Eldering.type.name, color = palette.ink)
                        Text(
                            if (person.role == PersonRole.EMERGENCY_CONTACT) {
                                stringResource(R.string.sharing_note_sms, person.maskedPhone.orEmpty())
                            } else {
                                stringResource(R.string.sharing_note_app)
                            },
                            style = Eldering.type.micro,
                            color = palette.inkMuted,
                        )
                    }
                    RoleBadge(person.role)
                }
                LevelPicker(
                    options = listOf(
                        LevelOption(GrantLevel.LIVE, live, enabled = person.role != PersonRole.EMERGENCY_CONTACT),
                        LevelOption(GrantLevel.EMERGENCY, emergency),
                        LevelOption(GrantLevel.OFF, off),
                    ),
                    selected = person.level,
                    onSelect = { actions.onLevel(person.key, it) },
                    groupLabel = stringResource(R.string.sharing_group_label, person.name),
                    enabled = !person.saving,
                )
            }
        }
        val dashColor = palette.outline
        Surface(
            onClick = actions.onAddContact,
            shape = RoundedCornerShape(14.dp),
            color = Color.Transparent,
            contentColor = palette.ink,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .drawBehind {
                    drawRoundRect(
                        color = dashColor,
                        cornerRadius = CornerRadius(14.dp.toPx()),
                        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                    )
                },
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(12.dp)) {
                Text(stringResource(R.string.sharing_add_contact), style = Eldering.type.bodySmallStrong)
            }
        }
    }
}

@Composable
private fun RoleBadge(role: PersonRole) {
    val palette = Eldering.palette
    val (bg, fg, label) = when (role) {
        PersonRole.ADMIN -> Triple(palette.adminContainer, palette.adminText, R.string.role_admin)
        PersonRole.MEMBER -> Triple(palette.surfaceMuted, palette.inkSoft, R.string.role_member)
        PersonRole.EMERGENCY_CONTACT -> Triple(palette.emergencyContainer, palette.emergencyText, R.string.role_emergency)
    }
    Text(
        stringResource(label),
        style = Eldering.type.microBold,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun RoleLegend(role: String, description: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Eldering.palette.ink)) { append(role) }
            append(" — ")
            append(description)
        },
        style = Eldering.type.micro,
        color = Eldering.palette.inkMuted,
    )
}
