package com.eldering.safety.ui.trip

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eldering.safety.R
import com.eldering.safety.data.trail.TrailDownsampler
import com.eldering.safety.network.IncidentKind
import com.eldering.safety.ui.common.Formats
import com.eldering.safety.ui.common.appViewModel
import com.eldering.safety.ui.common.asString
import com.eldering.safety.ui.common.distanceText
import com.eldering.safety.ui.common.rememberClock
import com.eldering.safety.ui.components.DesignButton
import com.eldering.safety.ui.components.DesignButtons
import com.eldering.safety.ui.components.ElderingIcons
import com.eldering.safety.ui.components.EmptyState
import com.eldering.safety.ui.components.ErrorBanner
import com.eldering.safety.ui.components.LoadingBox
import com.eldering.safety.ui.components.ScreenHeader
import com.eldering.safety.ui.map.CameraRequest
import com.eldering.safety.ui.map.CameraTarget
import com.eldering.safety.ui.map.ElderingMap
import com.eldering.safety.ui.map.GeoPoint
import com.eldering.safety.ui.map.LineStyle
import com.eldering.safety.ui.map.MapMarker
import com.eldering.safety.ui.map.MapPolyline
import com.eldering.safety.ui.platform.ExternalIntents
import com.eldering.safety.ui.theme.Eldering
import kotlin.math.roundToInt

@Composable
fun TripRoute(userId: String?, incidentId: String?, name: String?, onBack: () -> Unit) {
    val viewModel = appViewModel(key = "trip-$userId-$incidentId") {
        TripViewModel(userId, incidentId, name, it.trailStore, it.incidentRepository, it.placeNamer, it.time)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TripScreen(state, onBack = onBack, onCursor = viewModel::moveCursor, onRetry = viewModel::load)
}

@Composable
fun TripScreen(state: TripUiState, onBack: () -> Unit, onCursor: (Int) -> Unit, onRetry: () -> Unit) {
    val palette = Eldering.palette
    val clock = rememberClock()
    val who = state.personName ?: stringResource(R.string.home_you)
    val summary = state.summary
    val subtitle = summary?.let {
        listOfNotNull(
            stringResource(R.string.trip_range, clock(it.startMs), clock(it.endMs)),
            it.dominantActivity?.let { a -> stringResource(Formats.activityLabel(a)) },
            distanceText(it.distanceM),
        ).joinToString(" · ")
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ScreenHeader(
            title = stringResource(R.string.trip_title, who),
            subtitle = subtitle,
            onBack = onBack,
            backLabel = stringResource(R.string.action_back_to_map),
        )
        when {
            state.loading -> LoadingBox(Modifier.weight(1f))
            state.error != null -> Column(Modifier.weight(1f).padding(12.dp)) {
                ErrorBanner(state.error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = onRetry)
            }
            state.empty != null || summary == null -> EmptyState(
                title = when (state.empty) {
                    TripEmpty.NOT_SHARED -> stringResource(R.string.trip_not_shared_title, who)
                    else -> stringResource(R.string.trip_no_trail_title)
                },
                body = when (state.empty) {
                    TripEmpty.NOT_SHARED -> stringResource(R.string.trip_not_shared_body)
                    else -> stringResource(R.string.trip_no_trail_body)
                },
                icon = ElderingIcons.Clock,
                modifier = Modifier.weight(1f),
            )
            else -> TripContent(state, summary, onCursor, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TripContent(state: TripUiState, summary: TripSummary, onCursor: (Int) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val palette = Eldering.palette
    val clock = rememberClock()
    val points = state.points
    val cursor = state.cursorPoint ?: points.last()
    val impactSuffix = stringResource(if (state.incidentKind == IncidentKind.SOS) R.string.trip_sos_suffix else R.string.trip_impact_suffix)
    val incidentTitle = stringResource(
        when (state.incidentKind) {
            IncidentKind.SOS -> R.string.trip_landmark_sos
            IncidentKind.FALL -> R.string.trip_landmark_fall
            else -> R.string.trip_landmark_incident
        },
    )
    val cursorLabel = clock(cursor.tMs) + if (state.cursor == summary.incidentIndex) " · $impactSuffix" else ""

    val line = remember(points) {
        val simplified = TrailDownsampler.downsample(points, 600, 1.0, true, { it.lat }, { it.lng }, { it.tMs })
        MapPolyline("trail", simplified.map { GeoPoint(it.lat, it.lng) }, LineStyle.TRAIL)
    }
    val camera = remember(points) { CameraRequest(CameraTarget.Fit(points.map { GeoPoint(it.lat, it.lng) }), animate = false) }
    val numbers = numberLandmarks(summary)
    val pinMarkers = summary.landmarks.map { landmark ->
        val p = points[landmark.index]
        val title = if (landmark.kind == LandmarkKind.INCIDENT) incidentTitle else state.landmarkTitle(landmark) ?: defaultTitle(landmark)
        val label = stringResource(R.string.trip_pin_a11y, title, clock(p.tMs))
        MapMarker(key = "pin-${landmark.index}", position = GeoPoint(p.lat, p.lng), zIndex = 2f) {
            LandmarkPin(numbers.getValue(landmark.index), landmark.kind == LandmarkKind.INCIDENT, label) { onCursor(landmark.index) }
        }
    }
    val cursorMarker = MapMarker(key = "cursor", position = GeoPoint(cursor.lat, cursor.lng), zIndex = 3f) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(palette.me.copy(alpha = 0.18f))
                .border(3.dp, palette.me, CircleShape)
                .clearAndSetSemantics { },
        )
    }

    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val mapHeight = minOf(320.dp, maxWidth * 0.85f)
            Box(
                Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .height(mapHeight)
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, palette.hairline, RoundedCornerShape(20.dp)),
            ) {
                ElderingMap(lines = listOf(line), markers = pinMarkers + cursorMarker, camera = camera, modifier = Modifier.fillMaxSize())
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.White,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp),
                ) {
                    Text(
                        cursorLabel,
                        style = Eldering.type.captionBold,
                        color = com.eldering.safety.ui.theme.Ink,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.trip_scrub_label), style = Eldering.type.captionStrong, color = palette.inkMuted)
            if (points.size > 1) {
                Slider(
                    value = state.cursor.toFloat(),
                    onValueChange = { onCursor(it.roundToInt()) },
                    valueRange = 0f..points.lastIndex.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = palette.ink,
                        activeTrackColor = palette.ink,
                        inactiveTrackColor = palette.hairline,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = context.getString(R.string.trip_scrub_label)
                            stateDescription = cursorLabel
                        },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock(summary.startMs), style = Eldering.type.micro, color = palette.inkMuted)
                Text(clock(summary.endMs), style = Eldering.type.micro, color = palette.inkMuted)
            }
        }
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .semantics { contentDescription = context.getString(R.string.trip_landmarks_a11y) },
        ) {
            items(summary.landmarks, key = { it.index }) { landmark ->
                LandmarkRow(
                    number = numbers.getValue(landmark.index),
                    landmark = landmark,
                    title = state.landmarkTitle(landmark)
                        ?: if (landmark.kind == LandmarkKind.INCIDENT) incidentTitle else defaultTitle(landmark),
                    incidentTitle = incidentTitle,
                    time = clock(points[landmark.index].tMs),
                    selected = state.cursor == landmark.index,
                    onClick = { onCursor(landmark.index) },
                )
            }
        }
        HorizontalDivider(color = palette.divider)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 18.dp)) {
            DesignButton(
                stringResource(R.string.trip_open_in_maps),
                { ExternalIntents.showOnMap(context, cursor.lat, cursor.lng, state.personName) },
                colors = DesignButtons.outline(),
                modifier = Modifier.weight(1f),
            )
            val shareTitle = stringResource(R.string.trip_share)
            val last = points.last()
            val person = state.personName
            val shareUrl = state.shareUrl
            val range = listOf(clock(summary.startMs), clock(summary.endMs), distanceText(summary.distanceM), clock(last.tMs), ExternalIntents.mapsUrl(last.lat, last.lng))
            val shareText = when {
                shareUrl != null && person == null -> stringResource(R.string.trip_share_text_link_me, shareUrl)
                shareUrl != null -> stringResource(R.string.trip_share_text_link, person.orEmpty(), shareUrl)
                person == null -> stringResource(R.string.trip_share_text_me, *range.toTypedArray())
                else -> stringResource(R.string.trip_share_text, person, *range.toTypedArray())
            }
            DesignButton(
                shareTitle,
                { ExternalIntents.shareText(context, shareText, shareTitle) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** "1", "2", … in trail order; the impact is "!". */
internal fun numberLandmarks(summary: TripSummary): Map<Int, String> {
    var n = 0
    return summary.landmarks.associate { l -> l.index to if (l.kind == LandmarkKind.INCIDENT) "!" else (++n).toString() }
}

@Composable
private fun defaultTitle(landmark: Landmark): String = stringResource(
    when (landmark.kind) {
        LandmarkKind.START -> R.string.trip_landmark_start
        LandmarkKind.STOP -> R.string.trip_landmark_stop
        LandmarkKind.WAYPOINT -> R.string.trip_landmark_waypoint
        LandmarkKind.NAMED -> R.string.trip_landmark_waypoint
        LandmarkKind.END -> R.string.trip_landmark_end
        LandmarkKind.INCIDENT -> R.string.trip_landmark_incident
    },
)

@Composable
private fun landmarkDetail(landmark: Landmark, title: String): String {
    val minutes = (landmark.durationMs / 60_000L).toInt()
    return when (landmark.kind) {
        LandmarkKind.START -> stringResource(R.string.trip_detail_start)
        LandmarkKind.STOP -> stringResource(R.string.trip_detail_stopped, minutes.coerceAtLeast(1))
        LandmarkKind.WAYPOINT, LandmarkKind.NAMED -> {
            val bearing = landmark.bearingDeg
            val speed = Formats.speedKmh(landmark.speedMps)
            when {
                landmark.durationMs >= TrailSummarizer.STOP_MIN_MS -> stringResource(R.string.trip_detail_stopped, minutes)
                bearing != null && speed != null && speed >= Formats.MIN_SPEED_TO_SHOW_KMH -> stringResource(
                    R.string.trip_detail_heading_speed,
                    stringResource(Formats.directionLabel(Formats.compassSector(bearing))),
                    speed,
                )
                bearing != null -> stringResource(R.string.trip_detail_heading, stringResource(Formats.directionLabel(Formats.compassSector(bearing))))
                else -> stringResource(R.string.trip_detail_passed)
            }
        }
        LandmarkKind.END -> stringResource(R.string.trip_detail_end)
        LandmarkKind.INCIDENT -> stringResource(R.string.trip_detail_end)
    }
}

@Composable
private fun LandmarkPin(number: String, alert: Boolean, label: String, onClick: () -> Unit) {
    val palette = Eldering.palette
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp) // touch target; the visible pin is 28 dp
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .shadow(3.dp, CircleShape)
                .clip(CircleShape)
                .background(if (alert) palette.alert else com.eldering.safety.ui.theme.Ink)
                .border(2.dp, Color.White, CircleShape),
        ) {
            Text(number, style = Eldering.type.captionBold, color = Color.White)
        }
    }
}

@Composable
private fun LandmarkRow(
    number: String,
    landmark: Landmark,
    title: String,
    incidentTitle: String,
    time: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = Eldering.palette
    val alert = landmark.kind == LandmarkKind.INCIDENT
    val displayTitle = if (alert) incidentTitle else title
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) palette.surface else Color.Transparent,
        border = if (selected) BorderStroke(2.dp, palette.ink) else BorderStroke(1.dp, Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (alert) palette.alert else palette.ink),
            ) { Text(number, style = Eldering.type.captionBold, color = if (alert) Color.White else palette.onInk) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(displayTitle, style = Eldering.type.name, color = palette.ink)
                val detail = if (alert) title.takeIf { it != incidentTitle } else null
                Text(
                    if (alert) {
                        detail?.let { stringResource(R.string.trip_detail_incident_place, it) } ?: stringResource(R.string.trip_detail_end)
                    } else {
                        landmarkDetail(landmark, title)
                    },
                    style = Eldering.type.micro,
                    color = palette.inkMuted,
                )
            }
            Text(time, style = Eldering.type.captionBold, color = palette.inkSoft)
        }
    }
}
