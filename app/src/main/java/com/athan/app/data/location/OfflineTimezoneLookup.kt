package com.athan.app.data.location

import com.athan.app.core.astronomy.PrayerTimeFormatter
import com.athan.app.util.InputValidator
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lightweight, 100% offline timezone resolution mechanism.
 *
 * Maps geographic coordinates (latitude, longitude) to canonical IANA timezone IDs
 * using local anchor points across worldwide regions without requiring internet,
 * web services, or external database files.
 */
object OfflineTimezoneLookup {

    /**
     * Maximum distance in kilometers from a known reference anchor for terrestrial resolution.
     * Coordinates in deep oceans or unmapped extremes exceeding this threshold return null,
     * indicating offline timezone resolution is unavailable for that coordinate.
     */
    const val MAX_TERRESTRIAL_LOOKUP_DISTANCE_KM = 1200.0

    data class TimezoneAnchor(
        val latitude: Double,
        val longitude: Double,
        val timezoneId: String,
        val maxRadiusKm: Double = MAX_TERRESTRIAL_LOOKUP_DISTANCE_KM
    )

    private val ANCHORS: List<TimezoneAnchor> by lazy {
        val list = mutableListOf<TimezoneAnchor>()

        // 1. Incorporate bundled cities as primary anchors
        for (city in BundledCities.CITIES) {
            list.add(TimezoneAnchor(city.latitude, city.longitude, city.timezoneId))
        }

        // 2. Additional global reference anchors for comprehensive regional coverage
        val additional = listOf(
            // Sri Lanka & South Asia
            TimezoneAnchor(6.9271, 79.8612, "Asia/Colombo"),
            TimezoneAnchor(7.2906, 80.6337, "Asia/Colombo"),
            TimezoneAnchor(27.7172, 85.3240, "Asia/Kathmandu"),
            TimezoneAnchor(34.5553, 69.2075, "Asia/Kabul"),
            TimezoneAnchor(4.1755, 73.5093, "Indian/Maldives"),

            // North America
            TimezoneAnchor(42.3601, -71.0589, "America/New_York"),   // Boston
            TimezoneAnchor(33.7490, -84.3880, "America/New_York"),   // Atlanta
            TimezoneAnchor(25.7617, -80.1918, "America/New_York"),   // Miami
            TimezoneAnchor(39.9526, -75.1652, "America/New_York"),   // Philadelphia
            TimezoneAnchor(44.9778, -93.2650, "America/Chicago"),    // Minneapolis
            TimezoneAnchor(38.6270, -90.1994, "America/Chicago"),    // St. Louis
            TimezoneAnchor(29.9511, -90.0715, "America/Chicago"),    // New Orleans
            TimezoneAnchor(39.7392, -104.9903, "America/Denver"),    // Denver (Mountain)
            TimezoneAnchor(40.7608, -111.8910, "America/Denver"),    // Salt Lake City
            TimezoneAnchor(33.4484, -112.0740, "America/Phoenix"),   // Phoenix (MST, no DST)
            TimezoneAnchor(47.6062, -122.3321, "America/Los_Angeles"), // Seattle
            TimezoneAnchor(45.5152, -122.6784, "America/Los_Angeles"), // Portland
            TimezoneAnchor(36.1699, -115.1398, "America/Los_Angeles"), // Las Vegas
            TimezoneAnchor(61.2181, -149.9003, "America/Anchorage"), // Alaska
            TimezoneAnchor(21.3069, -157.8583, "Pacific/Honolulu"),  // Hawaii
            TimezoneAnchor(19.4326, -99.1332, "America/Mexico_City"),// Mexico
            TimezoneAnchor(8.9824, -79.5199, "America/Panama"),      // Panama

            // South America
            TimezoneAnchor(4.7110, -74.0721, "America/Bogota"),      // Colombia
            TimezoneAnchor(-12.0464, -77.0428, "America/Lima"),      // Peru
            TimezoneAnchor(-33.4489, -70.6693, "America/Santiago"),  // Chile
            TimezoneAnchor(10.4806, -66.9036, "America/Caracas"),    // Venezuela

            // Europe
            TimezoneAnchor(53.3498, -6.2603, "Europe/Dublin"),       // Ireland
            TimezoneAnchor(38.7223, -9.1393, "Europe/Lisbon"),       // Portugal
            TimezoneAnchor(37.9838, 23.7275, "Europe/Athens"),       // Greece
            TimezoneAnchor(52.2297, 21.0122, "Europe/Warsaw"),       // Poland
            TimezoneAnchor(50.0755, 14.4378, "Europe/Prague"),       // Czech Republic
            TimezoneAnchor(47.4979, 19.0402, "Europe/Budapest"),     // Hungary
            TimezoneAnchor(50.4501, 30.5234, "Europe/Kyiv"),         // Ukraine
            TimezoneAnchor(44.4268, 26.1025, "Europe/Bucharest"),    // Romania

            // East & Southeast Asia
            TimezoneAnchor(13.7563, 100.5018, "Asia/Bangkok"),       // Thailand
            TimezoneAnchor(21.0285, 105.8542, "Asia/Bangkok"),       // Vietnam
            TimezoneAnchor(14.5995, 120.9842, "Asia/Manila"),        // Philippines
            TimezoneAnchor(22.3193, 114.1694, "Asia/Hong_Kong"),     // Hong Kong
            TimezoneAnchor(25.0330, 121.5654, "Asia/Taipei"),        // Taiwan
            TimezoneAnchor(-8.6705, 115.2126, "Asia/Makassar"),      // Bali / Central Indonesia

            // Australia
            TimezoneAnchor(-27.4698, 153.0251, "Australia/Brisbane"), // Brisbane
            TimezoneAnchor(-34.9285, 138.6007, "Australia/Adelaide"), // Adelaide
            TimezoneAnchor(-31.9505, 115.8605, "Australia/Perth"),    // Perth
            TimezoneAnchor(-12.4634, 130.8456, "Australia/Darwin"),   // Darwin

            // Africa
            TimezoneAnchor(9.0338, 38.7400, "Africa/Addis_Ababa"),   // Ethiopia
            TimezoneAnchor(5.6037, -0.1870, "Africa/Accra"),         // Ghana
            TimezoneAnchor(14.7167, -17.4677, "Africa/Dakar"),       // Senegal
            TimezoneAnchor(-6.7924, 39.2083, "Africa/Dar_es_Salaam") // Tanzania
        )
        list.addAll(additional)
        list
    }

    /**
     * Determines the appropriate IANA timezone identifier for the given [latitude] and [longitude].
     *
     * Returns a valid canonical IANA timezone ID if the coordinate is within the terrestrial
     * reach of a known reference anchor, or null if resolution fails (e.g., coordinates in deep
     * oceans far from land anchors or invalid values).
     */
    fun lookup(latitude: Double, longitude: Double): String? {
        if (!InputValidator.isValidLatitude(latitude) || !InputValidator.isValidLongitude(longitude)) {
            return null
        }

        var closestAnchor: TimezoneAnchor? = null
        var minDistanceKm = Double.MAX_VALUE

        for (anchor in ANCHORS) {
            val dist = calculateDistanceKm(latitude, longitude, anchor.latitude, anchor.longitude)
            if (dist < minDistanceKm) {
                minDistanceKm = dist
                closestAnchor = anchor
            }
        }

        val anchor = closestAnchor ?: return null
        if (minDistanceKm <= anchor.maxRadiusKm) {
            return anchor.timezoneId
        }

        // Distance exceeds terrestrial limit; timezone resolution failed
        return null
    }

    /**
     * Computes great-circle distance between two points using the Haversine formula.
     */
    fun calculateDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)

        val a = sin(dLat / 2.0).pow(2.0) +
                cos(rLat1) * cos(rLat2) * sin(dLon / 2.0).pow(2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return 6371.0 * c
    }
}
