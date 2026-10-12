package com.eldering.safety.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp

/** A WGS84 coordinate. */
@Immutable
data class GeoPoint(val lat: Double, val lng: Double)

/** How a polyline is drawn. */
enum class LineStyle {
    /** Trip to past: 5 px ink line with a thin white dashed centre. */
    TRAIL,

    /** Family alert: dotted ink line (4 px, round dots). */
    DOTTED,
}

@Immutable
data class MapPolyline(val id: String, val points: List<GeoPoint>, val style: LineStyle)

/**
 * Where a marker's content is pinned to its coordinate: [x] is a fraction of the content width; [yFromTop] is
 * the distance from the content's top (null = vertical centre). An avatar-with-label marker pins the avatar
 * centre, not the label.
 */
@Immutable
data class MarkerAnchor(val x: Float = 0.5f, val yFromTop: Dp? = null)

/**
 * Compose content pinned to a coordinate (avatars, numbered landmark pins, the scrubber cursor). Markers are real
 * Compose nodes, so they can be buttons with TalkBack labels and ≥48 dp touch targets.
 */
class MapMarker(
    val key: String,
    val position: GeoPoint,
    val anchor: MarkerAnchor = MarkerAnchor(),
    val zIndex: Float = 0f,
    val content: @Composable () -> Unit,
)

/** What the camera should show. */
@Immutable
sealed interface CameraTarget {
    /** Fit all [points] (falls back to [singlePointZoom] around a single point). */
    data class Fit(val points: List<GeoPoint>, val singlePointZoom: Double = 15.0, val maxZoom: Double = 16.5) : CameraTarget

    data class Center(val point: GeoPoint, val zoom: Double = 15.0) : CameraTarget
}

/** A camera move; a new [id] re-applies it (e.g. each tap on "Center on me"). */
@Immutable
data class CameraRequest(val target: CameraTarget, val id: Int = 0, val animate: Boolean = true)

/** Which map implementation [ElderingMap] uses. */
enum class MapRenderer {
    /** MapLibre Native with the OpenFreeMap style. */
    MapLibre,

    /** A plain drawn map (previews, Robolectric tests, or when MapLibre cannot load). */
    Schematic,
}

val LocalMapRenderer = staticCompositionLocalOf { MapRenderer.MapLibre }

/** OpenFreeMap "liberty" style (SPEC); the app provides `AppConfig.mapStyleUrl` at the root. */
val LocalMapStyleUrl = staticCompositionLocalOf { "https://tiles.openfreemap.org/styles/liberty" }
