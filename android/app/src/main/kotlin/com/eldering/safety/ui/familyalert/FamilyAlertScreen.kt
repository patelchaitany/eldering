package com.eldering.safety.ui.familyalert

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.eldering.safety.R
import com.eldering.safety.network.AckState
import com.eldering.safety.network.IncidentKind
import com.eldering.safety.network.IncidentStatus
import com.eldering.safety.network.Resolution
import com.eldering.safety.ui.common.Formats
import com.eldering.safety.ui.common.IncidentDetailsReader
import com.eldering.safety.ui.common.appViewModel
import com.eldering.safety.ui.common.asString
import com.eldering.safety.ui.common.detectedLabel
import com.eldering.safety.ui.common.incidentHeadline
import com.eldering.safety.ui.common.rememberClock
import com.eldering.safety.ui.components.Avatar
import com.eldering.safety.ui.components.DesignButton
import com.eldering.safety.ui.components.DesignButtons
import com.eldering.safety.ui.components.DetailRow
import com.eldering.safety.ui.components.ElderingIcons
import com.eldering.safety.ui.components.ErrorBanner
import com.eldering.safety.ui.components.LoadingBox
import com.eldering.safety.ui.components.ScreenHeader
import com.eldering.safety.ui.components.SuccessBanner
import com.eldering.safety.ui.components.initialsOf
import com.eldering.safety.ui.map.CameraRequest
import com.eldering.safety.ui.map.CameraTarget
import com.eldering.safety.ui.map.ElderingMap
import com.eldering.safety.ui.map.GeoPoint
import com.eldering.safety.ui.map.LineStyle
import com.eldering.safety.ui.map.MapMarker
import com.eldering.safety.ui.map.MapPolyline
import com.eldering.safety.ui.navigation.Route
import com.eldering.safety.ui.platform.ExternalIntents
import com.eldering.safety.ui.theme.Eldering
import com.eldering.safety.ui.theme.MemberColors
import kotlinx.coroutines.delay

@Composable
fun FamilyAlertRoute(incidentId: String, onBack: () -> Unit, onNavigate: (Route) -> Unit) {
    val viewModel = appViewModel(key = "family-alert-$incidentId") {
        FamilyAlertViewModel(incidentId, it.incidentRepository, it.locationRepository, it.contactsRepository, it.placeNamer, it.time)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.pollWhileVisible() }
    }
    FamilyAlertScreen(
        state = state,
        nowMs = { viewModel.nowMs() },
        onBack = onBack,
        onSeeTrail = { d -> onNavigate(Route.Trip(userId = d.userId, incidentId = d.id, name = d.displayName)) },
        onMyWay = viewModel::onMyWay,
        onMarkSafe = viewModel::markSafe,
        onDismissError = viewModel::dismissError,
        onRetry = viewModel::retry,
    )
}

@Composable
fun FamilyAlertScreen(
    state: FamilyAlertUiState,
    nowMs: () -> Long,
    onBack: () -> Unit,
    onSeeTrail: (com.eldering.safety.network.IncidentDetailDto) -> Unit,
    onMyWay: () -> Unit,
    onMarkSafe: () -> Unit,
    onDismissError: () -> Unit,
    onRetry: () -> Unit,
) {
    val palette = Eldering.palette
    val detail = state.detail
    if (detail == null) {
        Column(
            Modifier
                .fillMaxSize()
                .background(palette.ground)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            ScreenHeader(stringResource(R.string.family_alert_title_generic), onBack, stringResource(R.string.action_back_to_map))
            if (state.loading) {
                LoadingBox(Modifier.weight(1f))
            } else {
                state.error?.let {
                    ErrorBanner(
                        it.asString(),
                        Modifier.padding(12.dp),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = onRetry,
                    )
                }
            }
        }
        return
    }
    val context = LocalContext.current
    val clock = rememberClock()
    // Ticks every second for "updated 12 s ago".
    val now by produceState(nowMs()) {
        while (true) {
            value = nowMs()
            delay(1_000)
        }
    }
    var confirmSafe by rememberSaveable { mutableStateOf(false) }
    val name = detail.displayName
    val latest = state.latest
    val minutesAgo = Formats.minutesBetween(detail.detectedAt.toEpochMilli(), now).toInt()

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.ground),
    ) {
        // Orange banner (design: FamilyAlert header), drawn under the status bar.
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(if (state.isActive) palette.alert else palette.safe)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(
                    onClick = onBack,
                    shape = CircleShape,
                    color = Color.Transparent,
                    contentColor = Color.White,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(ElderingIcons.Back, contentDescription = stringResource(R.string.action_back_to_map), modifier = Modifier.size(22.dp))
                    }
                }
                Icon(ElderingIcons.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(if (detail.kind == IncidentKind.SOS) R.string.family_alert_overline_sos else R.string.family_alert_overline).uppercase(),
                    style = Eldering.type.overline,
                    color = Color.White,
                )
            }
            Text(
                incidentHeadline(detail.kind, name),
                style = Eldering.type.bannerTitle,
                color = Color.White,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .semantics { heading() },
            )
            Text(
                listOfNotNull(
                    clock(detail.detectedAt.toEpochMilli()),
                    stringResource(R.string.family_alert_minutes_ago, minutesAgo).takeIf { state.isActive },
                    statusLine(detail.status, detail.resolution, detail.myAck, name),
                ).joinToString(" · "),
                style = Eldering.type.bodySmall,
                color = palette.onAlert.copy(alpha = 0.9f),
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp),
        ) {
            state.error?.let {
                ErrorBanner(it.asString(), Modifier.padding(horizontal = 12.dp), stringResource(R.string.action_dismiss), onDismissError)
            }
            Box(
                Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, palette.hairline, RoundedCornerShape(20.dp)),
            ) {
                val points = state.trail.map { GeoPoint(it.lat, it.lng) }
                val camera = latest?.let {
                    CameraRequest(CameraTarget.Fit(points.takeLast(40)), id = 0, animate = false)
                }
                val markers = latest?.let { p ->
                    listOf(
                        MapMarker("halo", GeoPoint(p.lat, p.lng), zIndex = 1f) {
                            Box(
                                Modifier
                                    .size(68.dp)
                                    .clip(CircleShape)
                                    .background(palette.alert.copy(alpha = 0.16f)),
                            )
                        },
                        MapMarker("person", GeoPoint(p.lat, p.lng), zIndex = 2f) {
                            Avatar(initialsOf(name), MemberColors.forUser(detail.userId), size = 40.dp)
                        },
                    )
                }.orEmpty()
                ElderingMap(
                    lines = listOf(MapPolyline("incident", points, LineStyle.DOTTED)),
                    markers = markers,
                    camera = camera,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 96.dp),
                    attributionBottomInset = 76.dp,
                    modifier = Modifier.fillMaxSize(),
                )
                if (latest == null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = palette.surface,
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                    ) {
                        Text(
                            stringResource(R.string.family_alert_no_location, name),
                            style = Eldering.type.bodySmall,
                            color = palette.inkMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
                if (latest != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = palette.surface,
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(10.dp)
                            .fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                state.place ?: stringResource(R.string.family_alert_last_known),
                                style = Eldering.type.bodySmallStrong,
                                color = palette.ink,
                            )
                            val seconds = ((now - latest.tMs) / 1000).coerceAtLeast(0)
                            Text(
                                listOfNotNull(
                                    if (state.isActive) stringResource(R.string.family_alert_live) else null,
                                    if (seconds < 120) {
                                        stringResource(R.string.family_alert_updated_seconds, seconds.toInt())
                                    } else {
                                        stringResource(R.string.family_alert_updated_minutes, (seconds / 60).toInt())
                                    },
                                    latest.accuracyM?.let { stringResource(R.string.family_alert_accuracy, it.toInt().coerceAtLeast(1)) },
                                    state.batteryPct?.let { stringResource(R.string.family_alert_battery, it) },
                                ).joinToString(" · "),
                                style = Eldering.type.micro,
                                color = palette.inkMuted,
                            )
                        }
                    }
                }
            }

            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DesignButton(
                        stringResource(R.string.family_alert_call_person, name),
                        { ExternalIntents.dial(context, state.phone) },
                        height = 56.dp,
                        textStyle = Eldering.type.buttonLarge,
                        modifier = Modifier.weight(1f),
                    )
                    DesignButton(
                        stringResource(R.string.alert_call_emergency),
                        { ExternalIntents.dialEmergency(context) },
                        colors = DesignButtons.alert(),
                        height = 56.dp,
                        textStyle = Eldering.type.buttonLarge,
                        contentDescription = stringResource(R.string.alert_call_emergency_a11y),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DesignButton(
                        stringResource(R.string.action_directions),
                        { latest?.let { ExternalIntents.directions(context, it.lat, it.lng, name) } },
                        colors = DesignButtons.outline(),
                        enabled = latest != null,
                        contentDescription = stringResource(R.string.action_directions_to, name),
                        modifier = Modifier.weight(1f),
                    )
                    DesignButton(
                        stringResource(R.string.family_alert_see_last_hour),
                        { onSeeTrail(detail) },
                        colors = DesignButtons.outline(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = palette.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.divider),
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    val mode = IncidentDetailsReader.mode(detail.details)
                    val before = Formats.speedKmh(IncidentDetailsReader.speedBeforeMps(detail.details))
                    val after = Formats.speedKmh(IncidentDetailsReader.speedAfterMps(detail.details))
                    val also = IncidentDetailsReader.alsoAlerted(detail.details)
                    DetailRow(
                        stringResource(R.string.family_alert_detected),
                        stringResource(detectedLabel(detail.kind, mode)),
                        showDivider = before != null || also != null,
                    )
                    if (before != null) {
                        DetailRow(
                            stringResource(R.string.family_alert_speed_before),
                            stringResource(R.string.family_alert_speed_change, before, after ?: 0),
                            showDivider = also != null,
                        )
                    }
                    if (also != null) DetailRow(stringResource(R.string.family_alert_also_alerted), also, showDivider = false)
                }
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 18.dp),
        ) {
            when {
                !state.isActive -> SuccessBanner(
                    stringResource(
                        when {
                            state.handledByMe || detail.resolution == Resolution.HANDLED -> R.string.family_alert_resolved_handled
                            detail.status == IncidentStatus.CANCELLED -> R.string.family_alert_resolved_cancelled
                            else -> R.string.family_alert_resolved_ok
                        },
                        name,
                    ),
                )
                else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val onWay = detail.myAck == AckState.ON_MY_WAY
                    DesignButton(
                        stringResource(if (onWay) R.string.family_alert_on_my_way_done else R.string.family_alert_on_my_way),
                        onMyWay,
                        colors = if (onWay) DesignButtons.safe() else DesignButtons.outline(),
                        enabled = !onWay && !state.acting,
                        leadingIcon = if (onWay) ElderingIcons.Check else null,
                        modifier = Modifier.weight(1f),
                    )
                    DesignButton(
                        stringResource(R.string.family_alert_is_safe, name),
                        { confirmSafe = true },
                        colors = DesignButtons.outline(),
                        enabled = !state.acting,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (confirmSafe) {
        AlertDialog(
            onDismissRequest = { confirmSafe = false },
            title = { Text(stringResource(R.string.family_alert_confirm_safe_title, name)) },
            text = { Text(stringResource(R.string.family_alert_confirm_safe_body, name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSafe = false
                    onMarkSafe()
                }) { Text(stringResource(R.string.family_alert_confirm_safe_yes)) }
            },
            dismissButton = { TextButton(onClick = { confirmSafe = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun statusLine(status: IncidentStatus, resolution: Resolution?, myAck: AckState?, name: String): String? = when {
    status == IncidentStatus.ACTIVE && myAck == AckState.ON_MY_WAY -> stringResource(R.string.family_alert_status_you_on_way)
    status == IncidentStatus.ACTIVE -> stringResource(R.string.family_alert_status_no_response, name)
    resolution == Resolution.USER_OK || resolution == Resolution.FALSE_ALARM -> stringResource(R.string.family_alert_status_ok, name)
    resolution == Resolution.HANDLED -> stringResource(R.string.family_alert_status_handled)
    else -> null
}
