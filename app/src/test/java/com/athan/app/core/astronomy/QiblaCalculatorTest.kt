package com.athan.app.core.astronomy

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class QiblaCalculatorTest {

    // A. Antipodal points (exact opposite of Mecca):
    // Latitude: -21.4225, Longitude: -140.1738 (39.8262 - 180.0)
    // Verify distance is approximately 20,015 km and is strictly finite (!isNaN() and isFinite()).
    @Test
    fun testAntipodalPointsAvoidNaNAndReturnApprox20015Km() {
        val antipodalLat = -QiblaCalculator.MAKKAH_LATITUDE
        val antipodalLon = QiblaCalculator.MAKKAH_LONGITUDE - 180.0

        val distance = QiblaCalculator.calculateDistanceKm(antipodalLat, antipodalLon)
        assertFalse("Antipodal distance must not be NaN", distance.isNaN())
        assertTrue("Antipodal distance must be finite", distance.isFinite())
        assertEquals("Distance to antipode should be ~20,015 km", 20015.08, distance, 10.0)

        val bearing = QiblaCalculator.calculateBearing(antipodalLat, antipodalLon)
        assertFalse("Antipodal bearing must not be NaN", bearing.isNaN())
        assertTrue("Antipodal bearing must be finite", bearing.isFinite())
        assertTrue("Bearing must be within [0, 360)", bearing in 0.0..<360.0)
    }

    // B. Synthetic floating-point overshoot:
    // Test coordinates or boundary conditions where floating-point math causes "a > 1.0" or "a < 0.0",
    // verifying that no NaN is produced.
    @Test
    fun testSyntheticFloatingPointOvershootDoesNotProduceNaN() {
        // Slightly greater than 1.0 due to rounding
        val distOvershoot1 = QiblaCalculator.computeDistanceKmFromA(1.0000000000000002)
        assertFalse("Distance with a > 1.0 must not be NaN", distOvershoot1.isNaN())
        assertTrue("Distance with a > 1.0 must be finite", distOvershoot1.isFinite())
        assertEquals(6371.0 * Math.PI, distOvershoot1, 1e-3)

        // Substantially greater than 1.0
        val distOvershoot2 = QiblaCalculator.computeDistanceKmFromA(1.05)
        assertFalse(distOvershoot2.isNaN())
        assertTrue(distOvershoot2.isFinite())
        assertEquals(6371.0 * Math.PI, distOvershoot2, 1e-3)

        // Slightly negative due to underflow
        val distUnderflow1 = QiblaCalculator.computeDistanceKmFromA(-1e-16)
        assertFalse("Distance with a < 0.0 must not be NaN", distUnderflow1.isNaN())
        assertTrue("Distance with a < 0.0 must be finite", distUnderflow1.isFinite())
        assertEquals(0.0, distUnderflow1, 1e-6)

        // Substantially negative
        val distUnderflow2 = QiblaCalculator.computeDistanceKmFromA(-0.5)
        assertFalse(distUnderflow2.isNaN())
        assertTrue(distUnderflow2.isFinite())
        assertEquals(0.0, distUnderflow2, 1e-6)

        // Non-finite 'a'
        val distNaN = QiblaCalculator.computeDistanceKmFromA(Double.NaN)
        assertFalse(distNaN.isNaN())
        assertEquals(0.0, distNaN, 1e-6)

        val distInf = QiblaCalculator.computeDistanceKmFromA(Double.POSITIVE_INFINITY)
        assertFalse(distInf.isNaN())
        assertEquals(0.0, distInf, 1e-6)
    }

    // C. Exact Kaaba coordinates:
    // Latitude: 21.4225, Longitude: 39.8262
    // Verify distance is exactly 0.0 km (or within 1e-6 km) and bearing is 0.0.
    @Test
    fun testExactKaabaCoordinatesReturnZeroDistanceAndDefaultBearing() {
        val distance = QiblaCalculator.calculateDistanceKm(
            QiblaCalculator.MAKKAH_LATITUDE,
            QiblaCalculator.MAKKAH_LONGITUDE
        )
        val bearing = QiblaCalculator.calculateBearing(
            QiblaCalculator.MAKKAH_LATITUDE,
            QiblaCalculator.MAKKAH_LONGITUDE
        )

        assertEquals("Distance at Kaaba must be 0.0 km", 0.0, distance, 1e-6)
        assertEquals("Bearing at Kaaba must be deterministic 0.0 deg", 0.0, bearing, 1e-6)
        assertFalse(distance.isNaN())
        assertFalse(bearing.isNaN())
    }

    // D. Poles:
    // North Pole (90.0, 0.0) and South Pole (-90.0, 0.0).
    // Verify distance and bearing are finite and valid.
    @Test
    fun testPolesReturnFiniteAndValidOutputs() {
        val northDistance = QiblaCalculator.calculateDistanceKm(90.0, 0.0)
        val northBearing = QiblaCalculator.calculateBearing(90.0, 0.0)

        assertTrue(northDistance.isFinite())
        assertFalse(northDistance.isNaN())
        assertTrue("Distance from North Pole should be positive", northDistance > 0.0)
        assertTrue(northBearing.isFinite())
        assertFalse(northBearing.isNaN())
        assertTrue("Bearing from North Pole must be in [0, 360)", northBearing in 0.0..<360.0)

        val southDistance = QiblaCalculator.calculateDistanceKm(-90.0, 0.0)
        val southBearing = QiblaCalculator.calculateBearing(-90.0, 0.0)

        assertTrue(southDistance.isFinite())
        assertFalse(southDistance.isNaN())
        assertTrue("Distance from South Pole should be positive", southDistance > 0.0)
        assertTrue(southBearing.isFinite())
        assertFalse(southBearing.isNaN())
        assertTrue("Bearing from South Pole must be in [0, 360)", southBearing in 0.0..<360.0)
    }

    // E. Known reference cities:
    // - London (51.5074, -0.1278) -> Bearing ~118.9°, Distance ~4,780 km
    // - New York (40.7128, -74.0060) -> Bearing ~58.5°, Distance ~10,290 km
    // - Tokyo (35.6762, 139.6503) -> Bearing ~293.0°, Distance ~9,460 km
    // - Sydney (-33.8688, 151.2093) -> Bearing ~277.5°, Distance ~13,160 km
    @Test
    fun testKnownReferenceCitiesMatchExpectedValues() {
        // London
        val londonBearing = QiblaCalculator.calculateBearing(51.5074, -0.1278)
        val londonDist = QiblaCalculator.calculateDistanceKm(51.5074, -0.1278)
        assertEquals(118.9, londonBearing, 1.0)
        assertEquals(4780.0, londonDist, 50.0)

        // New York
        val nyBearing = QiblaCalculator.calculateBearing(40.7128, -74.0060)
        val nyDist = QiblaCalculator.calculateDistanceKm(40.7128, -74.0060)
        assertEquals(58.5, nyBearing, 1.0)
        assertEquals(10290.0, nyDist, 50.0)

        // Tokyo
        val tokyoBearing = QiblaCalculator.calculateBearing(35.6762, 139.6503)
        val tokyoDist = QiblaCalculator.calculateDistanceKm(35.6762, 139.6503)
        assertEquals(293.0, tokyoBearing, 1.0)
        assertEquals(9460.0, tokyoDist, 50.0)

        // Sydney
        val sydneyBearing = QiblaCalculator.calculateBearing(-33.8688, 151.2093)
        val sydneyDist = QiblaCalculator.calculateDistanceKm(-33.8688, 151.2093)
        assertEquals(277.5, sydneyBearing, 1.0)
        assertEquals(13160.0, sydneyDist, 100.0)
    }

    // F. Boundary coordinates:
    // Latitude at -90.0, 90.0; Longitude at -180.0, 180.0.
    // Verify all outputs are finite and non-NaN.
    @Test
    fun testBoundaryCoordinatesAreFiniteAndNonNaN() {
        val boundaryPoints = listOf(
            Pair(-90.0, -180.0),
            Pair(-90.0, 180.0),
            Pair(90.0, -180.0),
            Pair(90.0, 180.0),
            Pair(0.0, -180.0),
            Pair(0.0, 180.0),
            Pair(-90.0, 0.0),
            Pair(90.0, 0.0)
        )

        for ((lat, lon) in boundaryPoints) {
            val dist = QiblaCalculator.calculateDistanceKm(lat, lon)
            val bearing = QiblaCalculator.calculateBearing(lat, lon)

            assertFalse("Distance at ($lat, $lon) must not be NaN", dist.isNaN())
            assertTrue("Distance at ($lat, $lon) must be finite", dist.isFinite())
            assertTrue("Distance at ($lat, $lon) must be non-negative", dist >= 0.0)

            assertFalse("Bearing at ($lat, $lon) must not be NaN", bearing.isNaN())
            assertTrue("Bearing at ($lat, $lon) must be finite", bearing.isFinite())
            assertTrue("Bearing at ($lat, $lon) must be in [0, 360)", bearing in 0.0..<360.0)
        }
    }

    // G. Corrupt input protection:
    // Pass NaN or Infinite values; verify the calculator returns a safe fallback
    // rather than throwing uncaught exceptions or propagating NaNs into UI state.
    @Test
    fun testCorruptInputProtectionReturnsSafeFallback() {
        val corruptValues = listOf(
            Pair(Double.NaN, 0.0),
            Pair(0.0, Double.NaN),
            Pair(Double.NaN, Double.NaN),
            Pair(Double.POSITIVE_INFINITY, 0.0),
            Pair(0.0, Double.POSITIVE_INFINITY),
            Pair(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY),
            Pair(Double.NaN, Double.POSITIVE_INFINITY)
        )

        for ((lat, lon) in corruptValues) {
            val dist = QiblaCalculator.calculateDistanceKm(lat, lon)
            val bearing = QiblaCalculator.calculateBearing(lat, lon)

            assertFalse("Corrupt inputs must not produce NaN distance", dist.isNaN())
            assertTrue("Corrupt inputs must produce finite distance", dist.isFinite())
            assertEquals("Corrupt inputs should return 0.0 distance fallback", 0.0, dist, 1e-6)

            assertFalse("Corrupt inputs must not produce NaN bearing", bearing.isNaN())
            assertTrue("Corrupt inputs must produce finite bearing", bearing.isFinite())
            assertEquals("Corrupt inputs should return 0.0 bearing fallback", 0.0, bearing, 1e-6)
        }

        // Out-of-physical-bounds coordinates (e.g. 120.0 lat, 250.0 lon) coerced safely
        val outOfBoundsDist = QiblaCalculator.calculateDistanceKm(120.0, 250.0)
        val outOfBoundsBearing = QiblaCalculator.calculateBearing(120.0, 250.0)

        assertFalse(outOfBoundsDist.isNaN())
        assertTrue(outOfBoundsDist.isFinite())
        assertFalse(outOfBoundsBearing.isNaN())
        assertTrue(outOfBoundsBearing.isFinite())
        assertTrue(outOfBoundsBearing in 0.0..<360.0)
    }
}
