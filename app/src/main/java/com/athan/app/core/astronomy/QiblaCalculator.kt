package com.athan.app.core.astronomy

import androidx.annotation.VisibleForTesting
import kotlin.math.*

object QiblaCalculator {
    // Exact coordinates of the Kaaba in Mecca
    const val MAKKAH_LATITUDE = 21.4225
    const val MAKKAH_LONGITUDE = 39.8262
    private const val EARTH_RADIUS_KM = 6371.0

    /**
     * Calculates the Qibla direction (bearing in degrees clockwise from True North, [0..360))
     * from any geographic coordinate using the Great Circle forward azimuth formula.
     *
     * Returns 0.0 if inputs are non-finite or at the exact Kaaba coordinates.
     */
    fun calculateBearing(latitude: Double, longitude: Double): Double {
        if (!latitude.isFinite() || !longitude.isFinite()) {
            return 0.0
        }
        val safeLat = latitude.coerceIn(-90.0, 90.0)
        val safeLon = longitude.coerceIn(-180.0, 180.0)

        // Special case: identically at the Kaaba
        if (abs(safeLat - MAKKAH_LATITUDE) < 1e-7 && abs(safeLon - MAKKAH_LONGITUDE) < 1e-7) {
            return 0.0
        }

        val phi1 = Math.toRadians(safeLat)
        val phi2 = Math.toRadians(MAKKAH_LATITUDE)
        val deltaLambda = Math.toRadians(MAKKAH_LONGITUDE - safeLon)

        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)

        val rawBearing = Math.toDegrees(atan2(y, x))
        val bearing = if (rawBearing.isFinite()) {
            (rawBearing % 360.0 + 360.0) % 360.0
        } else {
            0.0
        }
        return if (bearing >= 360.0 || bearing < 0.0) 0.0 else bearing
    }

    /**
     * Calculates distance to Makkah in kilometers using the Haversine formula.
     * Guarantees finite numerical bounds and prevents NaN returns across all coordinates.
     */
    fun calculateDistanceKm(latitude: Double, longitude: Double): Double {
        if (!latitude.isFinite() || !longitude.isFinite()) {
            return 0.0
        }
        val safeLat = latitude.coerceIn(-90.0, 90.0)
        val safeLon = longitude.coerceIn(-180.0, 180.0)

        // Special case: identically at the Kaaba
        if (abs(safeLat - MAKKAH_LATITUDE) < 1e-7 && abs(safeLon - MAKKAH_LONGITUDE) < 1e-7) {
            return 0.0
        }

        val dLat = Math.toRadians(MAKKAH_LATITUDE - safeLat)
        val dLon = Math.toRadians(MAKKAH_LONGITUDE - safeLon)
        val lat1 = Math.toRadians(safeLat)
        val lat2 = Math.toRadians(MAKKAH_LATITUDE)

        val a = sin(dLat / 2.0).pow(2) + sin(dLon / 2.0).pow(2) * cos(lat1) * cos(lat2)
        return computeDistanceKmFromA(a)
    }

    /**
     * Computes spherical distance from the intermediate Haversine value [a],
     * strictly clamping [a] to [0.0, 1.0] to eliminate floating-point rounding NaNs.
     */
    @VisibleForTesting
    internal fun computeDistanceKmFromA(a: Double): Double {
        if (!a.isFinite()) {
            return 0.0
        }
        val safeA = a.coerceIn(0.0, 1.0)
        val c = 2.0 * atan2(sqrt(safeA), sqrt(1.0 - safeA))
        val distance = EARTH_RADIUS_KM * c
        return if (distance.isFinite()) distance else 0.0
    }
}
