package com.eldering.safety.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.eldering.safety.R
import com.eldering.safety.network.IncidentSummaryDto
import com.eldering.safety.network.SharingState
import com.eldering.safety.ui.common.Formats
import com.eldering.safety.ui.common.ageText
import com.eldering.safety.ui.common.appViewModel
import com.eldering.safety.ui.common.asString
import com.eldering.safety.ui.common.distanceText
import com.eldering.safety.ui.common.incidentHeadline
import com.eldering.safety.ui.common.rememberClock
import com.eldering.safety.ui.components.Avatar
import com.eldering.safety.ui.components.DesignButton
import com.eldering.safety.ui.components.DesignButtons
import com.eldering.safety.ui.components.ElderingIcons
import com.eldering.safety.ui.components.ErrorBanner
import com.eldering.safety.ui.components.HoldToConfirmButton
import com.eldering.safety.ui.components.IconCircleButton
import com.eldering.safety.ui.components.StatusChip
import com.eldering.safety.ui.components.initialsOf
import com.eldering.safety.ui.map.ElderingMap
import com.eldering.safety.ui.map.MapMarker
import com.eldering.safety.ui.map.MarkerAnchor
import com.eldering.safety.ui.navigation.Route
import com.eldering.safety.ui.platform.ExternalIntents
import com.eldering.safety.ui.theme.Eldering
import com.eldering.safety.ui.theme.MemberColors

@Composable
fun HomeRoute(onNavigate: (Route) -> Unit) {
    val viewModel = appViewModel {
        HomeViewModel(
            auth = it.authRepository,
            families = it.familyRepository,
            locations = it.locationRepository,
            incidents = it.incidentRepository,
            contacts = it.contactsRepository,
            myLocation = it.myLocation,
            protection = it.protectionStatus,
            battery = it.batteryLevel,
            placeNamer = it.placeNamer,
            time = it.time,
            resolveOwn = it::resolveOwnIncident,
            phoneBook = it.familyPhoneBook,
            refreshRecipients = { it.alertRecipients.refreshIfStale(RECIPIENTS_MAX_AGE_MS) },
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    // Poll only while Home is visible: repeatOnLifecycle cancels the loop on STOP and restarts it on START.
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.pollWhileVisible() }
    }
    HomeScreen(
        state = state,
        actions = HomeActions(
            onSelect = viewModel::focus,
            onCenterOnMe = viewModel::centerOnMe,
            onNavigate = onNavigate,
            onSos = { onNavigate(Route.Sos) },
            onResolveOwn = viewModel::resolveOwnIncident,
            onRetry = viewModel::retry,
            onPhonePicked = viewModel::rememberPhone,
        ),
    )
}

class HomeActions(
    val onSelect: (String) -> Unit,
    val onCenterOnMe: () -> Unit,
    val onNavigate: (Route) -> Unit,
    val onSos: () -> Unit,
    val onResolveOwn: () -> Unit,
    val onRetry: () -> Unit,
    /** "Call" had no number: the user picked [phone] for member [userId] from the address book. */
    val onPhonePicked: (userId: String, phone: String) -> Unit = { _, _ -> },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(state: HomeUiState, actions: HomeActions) {
    val palette = Eldering.palette
    var showAlerts by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        BoxWithConstraints(Modifier.weight(1f)) {
            val mapHeight = minOf(300.dp, maxHeight * 0.42f)
            LazyColumn(
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item("header") {
                    HomeHeader(
                        state = state,
                        onAlerts = {
                            val alerts = state.familyAlerts
                            if (alerts.size == 1) actions.onNavigate(Route.FamilyAlert(alerts.first().id)) else showAlerts = true
                        },
                        onSharing = { actions.onNavigate(Route.Sharing) },
                    )
                }
                item("chips") { StatusChips(state, actions.onNavigate) }
                state.error?.let { error ->
                    item("error") {
                        ErrorBanner(
                            error.asString(),
                            actionLabel = stringResource(R.string.action_retry),
                            onAction = actions.onRetry,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
                state.ownIncident?.let {
                    item("own-incident") { OwnIncidentBanner(onResolve = actions.onResolveOwn) }
                }
                items(state.familyAlerts, key = { "alert-" + it.id }) { alert ->
                    FamilyAlertBanner(alert, onOpen = { actions.onNavigate(Route.FamilyAlert(alert.id)) })
                }
                item("map") {
                    FamilyMapCard(state, mapHeight, actions)
                }
                item("members-title") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 12.dp, top = 4.dp),
                    ) {
                        Text(
                            stringResource(R.string.home_members),
                            style = Eldering.type.sectionTitle,
                            color = palette.ink,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { heading() },
                        )
                        DesignButton(
                            text = stringResource(R.string.home_invite),
                            onClick = { actions.onNavigate(Route.Invite()) },
                            colors = DesignButtons.outline(),
                            height = 36.dp,
                            shape = RoundedCornerShape(18.dp),
                            textStyle = Eldering.type.captionStrong,
                            contentDescription = stringResource(R.string.home_invite_a11y),
                        )
                    }
                }
                item("me") { MeCard(state, actions) }
                items(state.members, key = { it.userId }) { member ->
                    MemberCard(member, state.nowMs, selected = state.selectedId == member.userId, actions = actions)
                }
                if (state.loading && state.members.isEmpty()) {
                    item("loading") { MembersLoading() }
                }
                if (!state.loading && state.members.isEmpty()) {
                    item("empty") {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(
                                stringResource(if (state.hasFamily) R.string.home_no_members else R.string.home_no_family),
                                style = Eldering.type.caption,
                                color = palette.inkMuted,
                            )
                            if (!state.hasFamily) {
                                DesignButton(
                                    stringResource(R.string.home_no_family_action),
                                    { actions.onNavigate(Route.FamilySetup(canGoBack = true)) },
                                    colors = DesignButtons.outline(),
                                    height = 48.dp,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(palette.ground),
        ) {
            HorizontalDivider(color = palette.divider)
            val resources = LocalResources.current
            HoldToConfirmButton(
                idleLabel = stringResource(R.string.home_sos_idle),
                holdingLabel = { resources.getString(R.string.home_sos_holding, it) },
                accessibilityLabel = stringResource(R.string.home_sos_a11y),
                accessibilityActionLabel = stringResource(R.string.home_sos_a11y_action),
                onConfirmed = actions.onSos,
                color = palette.alert,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 18.dp),
            )
        }
    }
    if (showAlerts) {
        ModalBottomSheet(onDismissRequest = { showAlerts = false }, containerColor = palette.surface) {
            AlertsSheet(state.familyAlerts) {
                showAlerts = false
                actions.onNavigate(Route.FamilyAlert(it))
            }
        }
    }
}

@Composable
private fun HomeHeader(state: HomeUiState, onAlerts: () -> Unit, onSharing: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.home_title),
                style = Eldering.type.screenTitle,
                color = Eldering.palette.ink,
                modifier = Modifier.semantics { heading() },
            )
            // Unknown until the first refresh: "0 of 1 members sharing" would be wrong, so say nothing yet.
            if (!state.loading || state.members.isNotEmpty()) {
                Text(
                    stringResource(R.string.home_sharing_count, state.sharingCount, state.totalCount),
                    style = Eldering.type.caption,
                    color = Eldering.palette.inkMuted,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconCircleButton(
                icon = ElderingIcons.Bell,
                contentDescription = if (state.familyAlerts.isEmpty()) {
                    stringResource(R.string.home_notifications)
                } else {
                    pluralStringResource(R.plurals.home_notifications_active, state.familyAlerts.size, state.familyAlerts.size)
                },
                onClick = onAlerts,
                badge = state.familyAlerts.isNotEmpty(),
            )
            IconCircleButton(icon = ElderingIcons.Shield, contentDescription = stringResource(R.string.home_sharing_settings), onClick = onSharing)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusChips(state: HomeUiState, onNavigate: (Route) -> Unit) {
    val palette = Eldering.palette
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
    ) {
        val protection = state.protection
        when {
            protection == null -> Unit
            protection.running -> StatusChip(
                text = protection.activity?.takeIf { it != com.eldering.safety.network.Activity.UNKNOWN }
                    ?.let { stringResource(R.string.home_protection_on_activity, stringResource(Formats.activityLabel(it))) }
                    ?: stringResource(R.string.home_protection_on),
                container = palette.safeContainer,
                content = palette.safeText,
                dotColor = palette.safe,
            )
            else -> StatusChip(
                text = stringResource(R.string.home_protection_off),
                container = palette.alertContainer,
                content = palette.alertText,
                dotColor = palette.alert,
                onClick = { onNavigate(Route.Permissions(onboarding = false)) },
            )
        }
        when (state.me.sharing) {
            SharingState.ON -> StatusChip(
                stringResource(R.string.home_sharing_on),
                palette.meContainer,
                palette.meText,
                icon = ElderingIcons.MapPin,
                onClick = { onNavigate(Route.Sharing) },
            )
            SharingState.PAUSED -> StatusChip(
                stringResource(R.string.home_sharing_paused),
                palette.surfaceMuted,
                palette.inkSoft,
                icon = ElderingIcons.MapPin,
                onClick = { onNavigate(Route.Sharing) },
            )
            SharingState.OFF -> StatusChip(
                stringResource(R.string.home_sharing_off),
                palette.surfaceMuted,
                palette.inkSoft,
                icon = ElderingIcons.MapPin,
                onClick = { onNavigate(Route.Sharing) },
            )
            null -> Unit
        }
        if (protection?.running == true) {
            StatusChip(
                stringResource(
                    R.string.home_battery_use,
                    stringResource(if (batteryUseFor(state.me.activity) == BatteryUse.LOW) R.string.home_battery_low else R.string.home_battery_moderate),
                ),
                palette.surfaceMuted,
                palette.inkSoft,
            )
        }
    }
}

@Composable
private fun FamilyMapCard(state: HomeUiState, height: androidx.compose.ui.unit.Dp, actions: HomeActions) {
    val palette = Eldering.palette
    val meName = stringResource(R.string.home_you)
    val markers = buildList {
        state.me.position?.let { position ->
            add(memberMarker(HomeUiState.ME, position, meName, stringResource(R.string.home_me_initials), MemberColors.me, state.selectedId == HomeUiState.ME, stringResource(R.string.home_marker_a11y, meName), actions))
        }
        state.members.forEach { m ->
            val position = m.position ?: return@forEach
            add(memberMarker(m.userId, position, m.name, m.initials, m.color, state.selectedId == m.userId, stringResource(R.string.home_marker_a11y, m.name), actions))
        }
    }
    Box(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, palette.hairline, RoundedCornerShape(20.dp)),
    ) {
        ElderingMap(
            lines = emptyList(),
            markers = markers,
            camera = state.camera,
            // Room for the name label under each avatar, the "center on me" button and the map logo.
            contentPadding = PaddingValues(start = 48.dp, top = 48.dp, end = 64.dp, bottom = 80.dp),
            modifier = Modifier.fillMaxSize(),
        )
        val centerLabel = stringResource(R.string.home_center_on_me)
        Surface(
            onClick = actions.onCenterOnMe,
            shape = RoundedCornerShape(12.dp),
            color = palette.surface,
            contentColor = palette.ink,
            shadowElevation = 2.dp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .size(48.dp)
                .semantics { contentDescription = centerLabel },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(ElderingIcons.Crosshair, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        if (markers.isEmpty() && !state.loading) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = palette.surface,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(10.dp),
            ) {
                Text(
                    stringResource(R.string.home_map_empty),
                    style = Eldering.type.caption,
                    color = palette.inkMuted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private fun memberMarker(
    key: String,
    position: com.eldering.safety.ui.map.GeoPoint,
    name: String,
    initials: String,
    color: androidx.compose.ui.graphics.Color,
    selected: Boolean,
    a11yLabel: String,
    actions: HomeActions,
): MapMarker {
    val avatarSize = if (selected) 48.dp else 40.dp
    val ring = if (selected) 10.dp else 0.dp // outline (3) + offset (2) on each side, see Avatar
    return MapMarker(
        key = key,
        position = position,
        anchor = MarkerAnchor(yFromTop = (avatarSize + ring) / 2),
        zIndex = if (selected) 3f else 2f,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = a11yLabel) { actions.onSelect(key) }
                .semantics(mergeDescendants = true) {
                    contentDescription = a11yLabel
                    this.selected = selected
                }
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Avatar(initials, color, size = avatarSize, selectedColor = if (selected) color else null)
            Surface(shape = RoundedCornerShape(999.dp), color = androidx.compose.ui.graphics.Color.White, shadowElevation = 1.dp) {
                Text(
                    name,
                    style = Eldering.type.microBold,
                    color = com.eldering.safety.ui.theme.Ink,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun MeCard(state: HomeUiState, actions: HomeActions) {
    val me = state.me
    val selected = state.selectedId == HomeUiState.ME
    // Design: "Driving · 38 km/h" while moving.
    val movement = me.activity?.let { activity ->
        val label = stringResource(Formats.activityLabel(activity))
        val speed = me.speedKmh?.takeIf { it >= Formats.MIN_SPEED_TO_SHOW_KMH && activity != com.eldering.safety.network.Activity.STILL }
        if (speed != null) stringResource(R.string.home_activity_speed, label, speed) else label
    }
    val status = when (me.sharing) {
        SharingState.PAUSED -> stringResource(R.string.home_me_paused)
        SharingState.OFF -> stringResource(R.string.home_me_not_sharing)
        SharingState.ON -> movement ?: stringResource(R.string.home_me_sharing)
        // Not loaded yet: show movement if known, never claim "Sharing".
        null -> movement ?: "—"
    }
    val place = me.place?.let { stringResource(R.string.home_near_place, it) }
        ?: if (me.position == null) stringResource(R.string.home_me_no_position) else stringResource(R.string.home_location_known)
    MemberCardFrame(
        selected = selected,
        dimmed = false,
        name = stringResource(R.string.home_you),
        initials = stringResource(R.string.home_me_initials),
        color = MemberColors.me,
        status = status,
        statusColor = Eldering.palette.ink,
        place = place,
        updated = me.recordedAtMs?.let { ageText(it, state.nowMs) } ?: "—",
        battery = me.batteryPct?.let { stringResource(R.string.percent, it) },
        onClick = { actions.onSelect(HomeUiState.ME) },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            DesignButton(
                stringResource(R.string.home_sharing_short),
                { actions.onNavigate(Route.Sharing) },
                colors = DesignButtons.outline(),
                height = 44.dp,
                shape = RoundedCornerShape(12.dp),
                textStyle = Eldering.type.buttonSmall,
                modifier = Modifier.weight(1f),
            )
            DesignButton(
                stringResource(R.string.home_last_hour),
                { actions.onNavigate(Route.Trip()) },
                height = 44.dp,
                shape = RoundedCornerShape(12.dp),
                textStyle = Eldering.type.buttonSmall,
                modifier = Modifier.weight(1.4f),
            )
        }
    }
}

@Composable
private fun MemberCard(member: MemberUi, nowMs: Long, selected: Boolean, actions: HomeActions) {
    val context = LocalContext.current
    val callPerson = com.eldering.safety.ui.platform.rememberCallPerson { phone -> actions.onPhonePicked(member.userId, phone) }
    val palette = Eldering.palette
    val status = when (member.visibility) {
        MemberVisibility.LIVE, MemberVisibility.LIVE_NO_FIX -> {
            val label = stringResource(Formats.activityLabel(member.activity))
            val speed = member.speedKmh?.takeIf { it >= Formats.MIN_SPEED_TO_SHOW_KMH && member.activity != com.eldering.safety.network.Activity.STILL }
            if (member.visibility == MemberVisibility.LIVE_NO_FIX) {
                stringResource(R.string.home_member_no_fix)
            } else if (speed != null) {
                stringResource(R.string.home_activity_speed, label, speed)
            } else {
                label
            }
        }
        MemberVisibility.PAUSED -> stringResource(R.string.home_member_paused)
        MemberVisibility.EMERGENCY_ONLY -> stringResource(R.string.home_member_emergency_only)
        MemberVisibility.NOT_SHARING -> stringResource(R.string.home_member_not_sharing)
    }
    val place = when (member.visibility) {
        MemberVisibility.LIVE -> member.place?.let { stringResource(R.string.home_near_place, it) }
            ?: member.distanceM?.let { stringResource(R.string.home_distance_away, distanceText(it)) }
            ?: stringResource(R.string.home_location_known)
        MemberVisibility.LIVE_NO_FIX -> stringResource(R.string.home_member_no_fix_detail)
        MemberVisibility.PAUSED -> stringResource(R.string.home_member_paused_detail)
        MemberVisibility.EMERGENCY_ONLY -> stringResource(R.string.home_member_emergency_only_detail)
        MemberVisibility.NOT_SHARING -> stringResource(R.string.home_member_not_sharing_detail)
    }
    val live = member.visibility == MemberVisibility.LIVE
    val updated = when {
        !live || member.recordedAtMs == null -> "—"
        nowMs - member.recordedAtMs < LIVE_THRESHOLD_MS -> stringResource(R.string.home_updated_live)
        else -> ageText(member.recordedAtMs, nowMs)
    }
    MemberCardFrame(
        selected = selected,
        dimmed = !member.isSharingLive,
        name = member.name,
        initials = member.initials,
        color = member.color,
        status = status,
        statusColor = when {
            member.activeIncidentId != null -> palette.alert
            !member.isSharingLive -> palette.inkMuted
            member.activity == com.eldering.safety.network.Activity.VEHICLE -> palette.safeText
            else -> palette.ink
        },
        place = place,
        updated = updated,
        battery = member.batteryPct?.let { stringResource(R.string.percent, it) },
        onClick = { actions.onSelect(member.userId) },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            DesignButton(
                stringResource(R.string.action_call),
                { callPerson(member.phone) },
                colors = DesignButtons.outline(),
                height = 44.dp,
                shape = RoundedCornerShape(12.dp),
                textStyle = Eldering.type.buttonSmall,
                contentDescription = stringResource(R.string.action_call_person, member.name),
                modifier = Modifier.weight(1f),
            )
            DesignButton(
                stringResource(R.string.action_directions),
                { member.position?.let { ExternalIntents.directions(context, it.lat, it.lng, member.name) } },
                colors = DesignButtons.outline(),
                height = 44.dp,
                shape = RoundedCornerShape(12.dp),
                textStyle = Eldering.type.buttonSmall,
                enabled = member.position != null,
                contentDescription = stringResource(R.string.action_directions_to, member.name),
                modifier = Modifier.weight(1f),
            )
            DesignButton(
                stringResource(R.string.home_last_hour),
                { actions.onNavigate(Route.Trip(userId = member.userId, incidentId = member.activeIncidentId, name = member.name)) },
                height = 44.dp,
                shape = RoundedCornerShape(12.dp),
                textStyle = Eldering.type.buttonSmall,
                contentDescription = stringResource(R.string.home_last_hour_of, member.name),
                modifier = Modifier.weight(1.4f),
            )
        }
    }
}

@Composable
private fun MemberCardFrame(
    selected: Boolean,
    dimmed: Boolean,
    name: String,
    initials: String,
    color: androidx.compose.ui.graphics.Color,
    status: String,
    statusColor: androidx.compose.ui.graphics.Color,
    place: String,
    updated: String,
    battery: String?,
    onClick: () -> Unit,
    actionsRow: @Composable () -> Unit,
) {
    val palette = Eldering.palette
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (dimmed) palette.surfaceDim else palette.surface,
        border = if (selected) BorderStroke(2.dp, palette.ink) else BorderStroke(1.dp, palette.divider),
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(onClickLabel = stringResource(if (selected) R.string.home_collapse else R.string.home_expand), onClick = onClick)
                    .semantics(mergeDescendants = true) { this.selected = selected }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Avatar(initials, color, size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = Eldering.type.name, color = palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(status, style = Eldering.type.captionStrong, color = statusColor)
                    Text(place, style = Eldering.type.micro, color = palette.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(updated, style = Eldering.type.micro, color = palette.inkMuted)
                    if (battery != null) Text(battery, style = Eldering.type.micro, color = palette.inkMuted)
                }
            }
            if (selected && !dimmed) actionsRow()
        }
    }
}

@Composable
private fun MembersLoading() {
    val label = stringResource(R.string.home_loading_members)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        CircularProgressIndicator(color = Eldering.palette.safe, strokeWidth = 2.5.dp, modifier = Modifier.size(20.dp))
        Text(label, style = Eldering.type.caption, color = Eldering.palette.inkMuted)
    }
}

@Composable
private fun FamilyAlertBanner(alert: IncidentSummaryDto, onOpen: () -> Unit) {
    val palette = Eldering.palette
    val clock = rememberClock()
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        color = palette.alert,
        contentColor = palette.onAlert,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Icon(ElderingIcons.Warning, contentDescription = null, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(incidentHeadline(alert.kind, alert.displayName), style = Eldering.type.bodySmallStrong)
                Text(clock(alert.detectedAt.toEpochMilli()), style = Eldering.type.micro)
            }
            Text(stringResource(R.string.action_open), style = Eldering.type.buttonSmall)
        }
    }
}

@Composable
private fun OwnIncidentBanner(onResolve: () -> Unit) {
    val palette = Eldering.palette
    var confirm by rememberSaveable { mutableStateOf(false) }
    if (confirm) {
        com.eldering.safety.ui.alert.SafeNowConfirmation(
            onConfirm = {
                confirm = false
                onResolve()
            },
            onDismiss = { confirm = false },
        )
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = palette.alertContainer,
        border = BorderStroke(1.dp, palette.alert.copy(alpha = 0.3f)),
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.home_own_incident_title), style = Eldering.type.bodySmallStrong, color = palette.alertText)
            DesignButton(
                stringResource(R.string.alert_safe_now),
                { confirm = true },
                colors = DesignButtons.ink(),
                height = 48.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AlertsSheet(alerts: List<IncidentSummaryDto>, onOpen: (String) -> Unit) {
    val clock = rememberClock()
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
    ) {
        Text(
            stringResource(R.string.home_alerts_title),
            style = Eldering.type.headerTitle,
            color = Eldering.palette.ink,
            modifier = Modifier.semantics { heading() },
        )
        if (alerts.isEmpty()) {
            Text(stringResource(R.string.home_alerts_empty), style = Eldering.type.body, color = Eldering.palette.inkMuted)
        }
        alerts.forEach { alert ->
            Surface(
                onClick = { onOpen(alert.id) },
                shape = RoundedCornerShape(14.dp),
                color = Eldering.palette.alertContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 12.dp),
                ) {
                    Avatar(initialsOf(alert.displayName), MemberColors.forUser(alert.userId), size = 36.dp, ring = false)
                    Column(Modifier.weight(1f)) {
                        Text(incidentHeadline(alert.kind, alert.displayName), style = Eldering.type.bodySmallStrong, color = Eldering.palette.alertText)
                        Text(clock(alert.detectedAt.toEpochMilli()), style = Eldering.type.micro, color = Eldering.palette.alertText)
                    }
                }
            }
        }
    }
}

private const val LIVE_THRESHOLD_MS = 90_000L

/** Home refreshes the alert-recipient cache at most this often while it is open. */
private const val RECIPIENTS_MAX_AGE_MS = 10 * 60_000L
