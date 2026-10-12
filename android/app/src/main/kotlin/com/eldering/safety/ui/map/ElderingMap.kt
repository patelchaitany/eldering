package com.eldering.safety.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.eldering.safety.ui.theme.Eldering
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The app's map: MapLibre Native with the OpenFreeMap style ([LocalMapStyleUrl]), trail [lines] drawn as
 * MapLibre line layers, and [markers] as Compose content pinned to coordinates (so they stay accessible buttons).
 *
 * Falls back to a drawn schematic map in previews, when [LocalMapRenderer] says so (tests), or if MapLibre's
 * native library cannot load on this device: the markers and lines stay usable either way.
 *
 * @param attributionBottomInset lifts the map attribution above an overlay card at the bottom of the map.
 */
@Composable
fun ElderingMap(
    lines: List<MapPolyline>,
    markers: List<MapMarker>,
    camera: CameraRequest?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(56.dp),
    attributionBottomInset: Dp = 0.dp,
) {
    val renderer = if (LocalInspectionMode.current) MapRenderer.Schematic else LocalMapRenderer.current
    Box(modifier.clipToBounds()) {
        when (renderer) {
            MapRenderer.MapLibre -> MapLibreMap(
                lines = lines,
                markers = markers,
                camera = camera,
                contentPadding = contentPadding,
                attributionBottomInset = attributionBottomInset,
                fallback = { SchematicMap(lines, markers, contentPadding) },
            )
            MapRenderer.Schematic -> SchematicMap(lines, markers, contentPadding)
        }
    }
}

/**
 * Coordinate → pixel mapping inside the map's bounds. Re-assigning [project] (even to the same function, e.g. on
 * every camera move) re-places the markers without recomposing them.
 */
@Stable
internal class MapProjector {
    var project: (GeoPoint) -> Offset? by mutableStateOf({ _ -> null }, neverEqualPolicy())

    fun invalidate() {
        project = project
    }
}

/** Places [markers] at their projected coordinates. Only the placement re-runs when the projection changes. */
@Composable
internal fun MarkerOverlay(markers: List<MapMarker>, projector: MapProjector, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    Layout(
        content = { markers.forEach { marker -> key(marker.key) { Box { marker.content() } } } },
        modifier = modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            val project = projector.project
            placeables.forEachIndexed { index, placeable ->
                val marker = markers.getOrNull(index) ?: return@forEachIndexed
                val point = project(marker.position) ?: return@forEachIndexed
                val anchorX = placeable.width * marker.anchor.x
                val anchorY = marker.anchor.yFromTop?.let { with(density) { it.toPx() } } ?: (placeable.height / 2f)
                placeable.place(
                    x = (point.x - anchorX).roundToInt(),
                    y = (point.y - anchorY).roundToInt(),
                    zIndex = marker.zIndex,
                )
            }
        }
    }
}

/** A drawn stand-in map: ground, a few decorative streets, the lines, and the markers fitted to the box. */
@Composable
internal fun SchematicMap(lines: List<MapPolyline>, markers: List<MapMarker>, contentPadding: PaddingValues) {
    val palette = Eldering.palette
    val ink = palette.ink
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(palette.mapGround),
    ) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val padLeft = with(density) { contentPadding.calculateLeftPadding(LayoutDirection.Ltr).toPx() }
        val padTop = with(density) { contentPadding.calculateTopPadding().toPx() }
        val padRight = with(density) { contentPadding.calculateRightPadding(LayoutDirection.Ltr).toPx() }
        val padBottom = with(density) { contentPadding.calculateBottomPadding().toPx() }
        val points = lines.flatMap { it.points } + markers.map { it.position }
        val projection = remember(points, widthPx, heightPx, padLeft, padTop, padRight, padBottom) {
            fitProjection(points, widthPx, heightPx, padLeft, padTop, padRight, padBottom)
        }
        val projector = remember(projection) { MapProjector().also { it.project = projection } }

        Canvas(
            Modifier
                .fillMaxSize()
                .clearAndSetSemantics {},
        ) {
            val road = palette.mapRoad
            drawRect(palette.mapPark, topLeft = Offset(size.width * 0.08f, size.height * 0.09f), size = androidx.compose.ui.geometry.Size(size.width * 0.28f, size.height * 0.24f))
            drawLine(road, Offset(0f, size.height * 0.44f), Offset(size.width, size.height * 0.35f), strokeWidth = 14.dp.toPx())
            drawLine(road, Offset(size.width * 0.48f, 0f), Offset(size.width * 0.54f, size.height), strokeWidth = 12.dp.toPx())
            drawLine(road, Offset(0f, size.height * 0.17f), Offset(size.width, size.height * 0.23f), strokeWidth = 6.dp.toPx())
            drawLine(road, Offset(size.width * 0.78f, 0f), Offset(size.width * 0.85f, size.height), strokeWidth = 6.dp.toPx())
            lines.forEach { line ->
                val projected = line.points.mapNotNull(projection)
                if (projected.size < 2) return@forEach
                val path = Path().apply {
                    moveTo(projected.first().x, projected.first().y)
                    projected.drop(1).forEach { lineTo(it.x, it.y) }
                }
                when (line.style) {
                    LineStyle.TRAIL -> {
                        drawPath(path, ink, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        drawPath(
                            path,
                            Color.White,
                            style = Stroke(
                                1.5.dp.toPx(),
                                cap = StrokeCap.Round,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx())),
                            ),
                        )
                    }
                    LineStyle.DOTTED -> drawPath(
                        path,
                        ink,
                        style = Stroke(
                            4.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 7.dp.toPx())),
                        ),
                    )
                }
            }
        }
        MarkerOverlay(markers, projector)
    }
}

/**
 * Equirectangular fit of [points] into the padded box, preserving aspect ratio (east-west distances scaled by
 * cos(latitude)). A single point (or none) is centred.
 */
internal fun fitProjection(
    points: List<GeoPoint>,
    width: Float,
    height: Float,
    padLeft: Float,
    padTop: Float,
    padRight: Float,
    padBottom: Float,
): (GeoPoint) -> Offset? {
    if (points.isEmpty() || width <= 0f || height <= 0f) return { null }
    val minLat = points.minOf { it.lat }
    val maxLat = points.maxOf { it.lat }
    val minLng = points.minOf { it.lng }
    val maxLng = points.maxOf { it.lng }
    val midLat = (minLat + maxLat) / 2
    val lngScale = cos(Math.toRadians(midLat)).coerceAtLeast(0.01)
    val spanX = max((maxLng - minLng) * lngScale, 1e-6)
    val spanY = max(maxLat - minLat, 1e-6)
    val usableW = max(width - padLeft - padRight, 1f)
    val usableH = max(height - padTop - padBottom, 1f)
    val scale = min(usableW / spanX, usableH / spanY)
    val single = (maxLat - minLat) < 1e-6 && (maxLng - minLng) < 1e-6
    val centerX = padLeft + usableW / 2
    val centerY = padTop + usableH / 2
    val midLng = (minLng + maxLng) / 2
    return { p ->
        if (single) {
            Offset(centerX, centerY)
        } else {
            Offset(
                (centerX + (p.lng - midLng) * lngScale * scale).toFloat(),
                (centerY - (p.lat - midLat) * scale).toFloat(),
            )
        }
    }
}
