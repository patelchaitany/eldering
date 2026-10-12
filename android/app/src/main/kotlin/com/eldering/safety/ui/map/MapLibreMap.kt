package com.eldering.safety.ui.map

import android.content.Context
import android.util.Log
import android.view.View
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.eldering.safety.data.trail.Geo
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val TAG = "ElderingMap"

/** Lines are drawn in the design's ink on the (light) OpenFreeMap style in both app themes. */
private const val LINE_INK = "#13201B"
private const val SOURCE_PREFIX = "eldering-line-"

/** Creating the MapView loads MapLibre's native library; on failure the caller shows the schematic map. */
private fun createMapView(context: Context): MapView? = try {
    MapLibre.getInstance(context.applicationContext)
    MapView(context).apply {
        // Not navigable by TalkBack; the screens list the same information, and markers are Compose nodes.
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
} catch (e: Throwable) { // UnsatisfiedLinkError is an Error, not an Exception
    Log.w(TAG, "MapLibre unavailable, using schematic map", e)
    null
}

@Composable
internal fun MapLibreMap(
    lines: List<MapPolyline>,
    markers: List<MapMarker>,
    camera: CameraRequest?,
    contentPadding: PaddingValues,
    attributionBottomInset: Dp,
    fallback: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val mapView = remember { createMapView(context) }
    if (mapView == null) {
        fallback()
        return
    }
    val styleUrl = LocalMapStyleUrl.current
    val density = LocalDensity.current
    val projector = remember { MapProjector() }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }

    MapViewLifecycle(mapView)

    DisposableEffect(mapView) {
        var disposed = false
        var attached: MapLibreMap? = null
        val onMove = MapLibreMap.OnCameraMoveListener { projector.invalidate() }
        val onIdle = MapLibreMap.OnCameraIdleListener { projector.invalidate() }
        val onLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> projector.invalidate() }
        mapView.addOnLayoutChangeListener(onLayout)
        mapView.getMapAsync { m ->
            if (disposed) return@getMapAsync
            attached = m
            m.uiSettings.apply {
                setRotateGesturesEnabled(false)
                setTiltGesturesEnabled(false)
                setCompassEnabled(false)
            }
            m.addOnCameraMoveListener(onMove)
            m.addOnCameraIdleListener(onIdle)
            projector.project = { p ->
                val screen = m.projection.toScreenLocation(LatLng(p.lat, p.lng))
                Offset(screen.x, screen.y)
            }
            map = m
            m.setStyle(Style.Builder().fromUri(styleUrl)) { loaded ->
                if (!disposed) {
                    style = loaded
                    projector.invalidate()
                }
            }
        }
        onDispose {
            disposed = true
            mapView.removeOnLayoutChangeListener(onLayout)
            attached?.removeOnCameraMoveListener(onMove)
            attached?.removeOnCameraIdleListener(onIdle)
        }
    }

    // Keep the attribution ("i") and logo above any overlay card at the bottom of the map.
    LaunchedEffect(map, attributionBottomInset) {
        val m = map ?: return@LaunchedEffect
        val bottom = with(density) { (attributionBottomInset + Dp(8f)).roundToPx() }
        val side = with(density) { Dp(8f).roundToPx() }
        m.uiSettings.setLogoMargins(side, 0, 0, bottom)
        m.uiSettings.setAttributionMargins(with(density) { Dp(96f).roundToPx() }, 0, 0, bottom)
    }

    LaunchedEffect(style, lines) {
        val s = style ?: return@LaunchedEffect
        if (!s.isFullyLoaded) return@LaunchedEffect
        try {
            applyLines(s, lines)
        } catch (e: RuntimeException) { // style swapped underneath us; next style load re-applies
            Log.w(TAG, "Could not update map lines", e)
        }
    }

    LaunchedEffect(map, camera) {
        val m = map ?: return@LaunchedEffect
        val request = camera ?: return@LaunchedEffect
        mapView.awaitLaidOut()
        val padding = with(density) {
            intArrayOf(
                contentPadding.calculateLeftPadding(LayoutDirection.Ltr).roundToPx(),
                contentPadding.calculateTopPadding().roundToPx(),
                contentPadding.calculateRightPadding(LayoutDirection.Ltr).roundToPx(),
                contentPadding.calculateBottomPadding().roundToPx(),
            )
        }
        val update = cameraUpdateFor(request.target, padding) ?: return@LaunchedEffect
        if (request.animate) m.animateCamera(update, 600) else m.moveCamera(update)
    }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        MarkerOverlay(markers, projector)
    }
}

private fun cameraUpdateFor(target: CameraTarget, padding: IntArray) = when (target) {
    is CameraTarget.Center -> CameraUpdateFactory.newLatLngZoom(target.point.toLatLng(), target.zoom)
    is CameraTarget.Fit -> {
        val points = target.points
        when {
            points.isEmpty() -> null
            spanMeters(points) < 80.0 -> CameraUpdateFactory.newLatLngZoom(points.last().toLatLng(), target.singlePointZoom)
            else -> {
                val bounds = LatLngBounds.Builder().includes(points.map { it.toLatLng() }).build()
                CameraUpdateFactory.newLatLngBounds(bounds, padding[0], padding[1], padding[2], padding[3])
            }
        }
    }
}

private fun spanMeters(points: List<GeoPoint>): Double {
    val minLat = points.minOf { it.lat }
    val maxLat = points.maxOf { it.lat }
    val minLng = points.minOf { it.lng }
    val maxLng = points.maxOf { it.lng }
    return Geo.distanceM(minLat, minLng, maxLat, maxLng)
}

private fun GeoPoint.toLatLng() = LatLng(lat, lng)

private fun applyLines(style: Style, lines: List<MapPolyline>) {
    val wanted = lines.associateBy { SOURCE_PREFIX + it.id }
    // Remove lines that are gone.
    style.sources.map { it.id }.filter { it.startsWith(SOURCE_PREFIX) && it !in wanted }.forEach { sourceId ->
        style.layers.map { it.id }.filter { it.startsWith("$sourceId-") }.forEach { style.removeLayer(it) }
        style.removeSource(sourceId)
    }
    wanted.forEach { (sourceId, line) ->
        val geometry = if (line.points.size >= 2) {
            FeatureCollection.fromFeatures(
                listOf(Feature.fromGeometry(LineString.fromLngLats(line.points.map { Point.fromLngLat(it.lng, it.lat) }))),
            )
        } else {
            FeatureCollection.fromFeatures(emptyList<Feature>())
        }
        val existing = style.getSourceAs<GeoJsonSource>(sourceId)
        if (existing != null) {
            existing.setGeoJson(geometry)
            return@forEach
        }
        style.addSource(GeoJsonSource(sourceId, geometry))
        when (line.style) {
            LineStyle.TRAIL -> {
                style.addLayer(
                    LineLayer("$sourceId-casing", sourceId).withProperties(
                        PropertyFactory.lineColor(LINE_INK),
                        PropertyFactory.lineWidth(5f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                style.addLayer(
                    LineLayer("$sourceId-dash", sourceId).withProperties(
                        PropertyFactory.lineColor("#FFFFFF"),
                        PropertyFactory.lineWidth(1.5f),
                        // Dash lengths are in line widths: 4 px on, 6 px off at 1.5 px wide (design).
                        PropertyFactory.lineDasharray(arrayOf(2.67f, 4f)),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    ),
                )
            }
            LineStyle.DOTTED -> style.addLayer(
                LineLayer("$sourceId-dots", sourceId).withProperties(
                    PropertyFactory.lineColor(LINE_INK),
                    PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineDasharray(arrayOf(0.25f, 1.75f)),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
        }
    }
}

/** Forwards the host lifecycle to the MapView and tears it down when it leaves the composition. */
@Composable
private fun MapViewLifecycle(mapView: MapView) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        var created = false
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> if (!created) {
                    mapView.onCreate(null)
                    created = true
                }
                Lifecycle.Event.ON_START -> if (!started) {
                    mapView.onStart()
                    started = true
                }
                Lifecycle.Event.ON_RESUME -> if (!resumed) {
                    mapView.onResume()
                    resumed = true
                }
                Lifecycle.Event.ON_PAUSE -> if (resumed) {
                    mapView.onPause()
                    resumed = false
                }
                Lifecycle.Event.ON_STOP -> if (started) {
                    mapView.onStop()
                    started = false
                }
                else -> Unit // ON_DESTROY: handled in onDispose (the composition is disposed with the activity)
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            if (created) mapView.onDestroy()
        }
    }
}

private suspend fun View.awaitLaidOut() {
    if (width > 0 && height > 0) return
    suspendCancellableCoroutine { cont ->
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (v.width > 0 && v.height > 0) {
                    v.removeOnLayoutChangeListener(this)
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
        addOnLayoutChangeListener(listener)
        cont.invokeOnCancellation { removeOnLayoutChangeListener(listener) }
    }
}
