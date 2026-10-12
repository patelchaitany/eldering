package com.eldering.safety.data.trail

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Small geodesy helpers (spherical Earth; plenty for a one-hour trail). */
object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres. */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    fun distanceM(a: TrailPoint, b: TrailPoint): Double = distanceM(a.lat, a.lng, b.lat, b.lng)
}

/**
 * Local equirectangular projection around [originLat]/[originLng] to metres (x east, y north). Distortion is
 * negligible over the tens of kilometres a one-hour trail covers.
 */
internal class LocalProjection(private val originLat: Double, private val originLng: Double) {
    private val metresPerDegLat = Math.toRadians(1.0) * Geo.EARTH_RADIUS_M
    private val metresPerDegLng = metresPerDegLat * cos(Math.toRadians(originLat))

    fun x(lng: Double): Double {
        var d = lng - originLng
        if (d > 180) d -= 360 else if (d < -180) d += 360 // antimeridian
        return d * metresPerDegLng
    }

    fun y(lat: Double): Double = (lat - originLat) * metresPerDegLat
}
