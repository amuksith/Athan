package com.athan.app.core.astronomy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QiblaSensorManagerTest {

    private lateinit var context: Context
    private lateinit var sensorManager: QiblaSensorManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sensorManager = QiblaSensorManager(context)
    }

    // A. First reading snap:
    // Simulate the first sensor event with an azimuth of 180.0f (or 240.0f).
    // Verify that "deviceAzimuth.value" immediately equals 180.0f (or 240.0f), NOT a smoothed fraction from 0.0f.
    @Test
    fun testFirstReadingSnapsImmediatelyWithoutLagFromZero() {
        sensorManager.startListening()
        assertFalse("Before any reading, hasInitialHeading must be false", sensorManager.hasInitialHeading)

        // Feed first sensor reading at 180.0f
        sensorManager.updateSmoothedAzimuth(180.0f)

        assertTrue("After first reading, hasInitialHeading must be true", sensorManager.hasInitialHeading)
        assertEquals("Initial azimuth must immediately snap to 180.0f", 180.0f, sensorManager.deviceAzimuth.value, 1e-4f)
        assertEquals("Smoothed internal azimuth must equal 180.0f", 180.0f, sensorManager.smoothedAzimuth, 1e-4f)

        // Reset and test another angle (240.0f)
        val freshManager = QiblaSensorManager(context)
        freshManager.startListening()
        freshManager.updateSmoothedAzimuth(240.0f)
        assertEquals("Initial azimuth must immediately snap to 240.0f", 240.0f, freshManager.deviceAzimuth.value, 1e-4f)
    }

    // B. Subsequent reading smoothing:
    // After the first reading at 180.0f, feed 190.0f.
    // Verify that the new azimuth is smoothed according to the 0.15f factor (approx 181.5f),
    // proving smoothing operates after initialization.
    @Test
    fun testSubsequentReadingsApplyExponentialSmoothing() {
        sensorManager.startListening()

        // First reading snaps to 180.0f
        sensorManager.updateSmoothedAzimuth(180.0f)
        assertEquals(180.0f, sensorManager.deviceAzimuth.value, 1e-4f)

        // Second reading at 190.0f
        // Expected: 180.0f + 0.15f * (190.0f - 180.0f) = 181.5f
        sensorManager.updateSmoothedAzimuth(190.0f)
        assertEquals("Azimuth should be smoothed with 0.15 factor to ~181.5f", 181.5f, sensorManager.deviceAzimuth.value, 1e-3f)

        // Third reading at 190.0f
        // Expected: 181.5f + 0.15f * (190.0f - 181.5f) = 181.5f + 1.275f = 182.775f
        sensorManager.updateSmoothedAzimuth(190.0f)
        assertEquals(182.775f, sensorManager.deviceAzimuth.value, 1e-3f)
    }

    // C. 360° / 0° wrap-around smoothing:
    // Feed heading near the boundary (e.g. 355.0f, then 5.0f).
    // Verify that smoothing takes the shortest angular distance (+10° clockwise rather than -350° counterclockwise)
    // and does not jump or produce negative/out-of-bounds angles.
    @Test
    fun testWrapAroundSmoothingAcross360And0Boundary() {
        sensorManager.startListening()

        // 1. Clockwise wrap: 355.0f -> 5.0f (+10° difference)
        sensorManager.updateSmoothedAzimuth(355.0f)
        assertEquals(355.0f, sensorManager.deviceAzimuth.value, 1e-4f)

        // diff = 5 - 355 = -350 -> normalized to +10°
        // newSmoothed = (355 + 0.15 * 10) = 356.5f
        sensorManager.updateSmoothedAzimuth(5.0f)
        assertEquals("Clockwise wrap should smooth to 356.5f", 356.5f, sensorManager.deviceAzimuth.value, 1e-3f)
        assertTrue("Azimuth must remain within [0, 360)", sensorManager.deviceAzimuth.value in 0f..<360f)

        // 2. Counter-clockwise wrap: 5.0f -> 355.0f (-10° difference)
        val freshManager = QiblaSensorManager(context)
        freshManager.startListening()
        freshManager.updateSmoothedAzimuth(5.0f)
        assertEquals(5.0f, freshManager.deviceAzimuth.value, 1e-4f)

        // diff = 355 - 5 = 350 -> normalized to -10°
        // newSmoothed = (5 + 0.15 * (-10)) = 3.5f
        freshManager.updateSmoothedAzimuth(355.0f)
        assertEquals("Counter-clockwise wrap should smooth to 3.5f", 3.5f, freshManager.deviceAzimuth.value, 1e-3f)
        assertTrue("Azimuth must remain within [0, 360)", freshManager.deviceAzimuth.value in 0f..<360f)
    }

    // D. Lifecycle stop and restart:
    // 1. Start listening, feed 90.0f (snaps to 90.0f).
    // 2. Stop listening.
    // 3. Start listening again, feed 270.0f.
    // 4. Verify that the new session immediately snaps to 270.0f and does not drag or smooth from the old 90.0f reading.
    @Test
    fun testLifecycleStopAndRestartSnapsToNewHeadingWithoutStaleDrag() {
        // Step 1: Start listening and snap to 90.0f
        sensorManager.startListening()
        sensorManager.updateSmoothedAzimuth(90.0f)
        assertEquals(90.0f, sensorManager.deviceAzimuth.value, 1e-4f)
        assertTrue(sensorManager.hasInitialHeading)

        // Step 2: Stop listening
        sensorManager.stopListening()
        assertFalse("stopListening must reset hasInitialHeading", sensorManager.hasInitialHeading)
        assertFalse("stopListening must reset hasGravity", sensorManager.hasGravity)
        assertFalse("stopListening must reset hasGeomagnetic", sensorManager.hasGeomagnetic)

        // Step 3: Start listening again
        sensorManager.startListening()
        assertFalse("startListening must ensure hasInitialHeading is false", sensorManager.hasInitialHeading)

        // Step 4: Feed 270.0f in new session
        sensorManager.updateSmoothedAzimuth(270.0f)
        assertEquals("New session must snap immediately to 270.0f rather than smoothing from 90.0f", 270.0f, sensorManager.deviceAzimuth.value, 1e-4f)
        assertTrue(sensorManager.hasInitialHeading)
    }

    // E. Fallback sensor synchronization (Accelerometer + Magnetometer):
    // Verify that if only accelerometer arrives, azimuth is not updated.
    // Azimuth is only updated once both accelerometer and magnetometer readings are present.
    @Test
    fun testFallbackSensorSynchronizationRequiresBothSensors() {
        sensorManager.startListening()
        val initialAzimuth = sensorManager.deviceAzimuth.value

        // Feed only accelerometer data
        val gravityData = floatArrayOf(0.0f, 9.8f, 0.0f)
        sensorManager.handleAccelerometerData(gravityData)

        assertTrue("Accelerometer reading received", sensorManager.hasGravity)
        assertFalse("Magnetometer reading not yet received", sensorManager.hasGeomagnetic)
        assertFalse("Initial heading should not be marked valid before both sensors arrive", sensorManager.hasInitialHeading)
        assertEquals("Azimuth must NOT be updated when only accelerometer is available", initialAzimuth, sensorManager.deviceAzimuth.value, 1e-4f)

        // Now feed magnetometer data
        val magneticData = floatArrayOf(0.0f, 20.0f, -40.0f)
        sensorManager.handleMagneticData(magneticData)

        assertTrue("Both gravity and geomagnetic are now present", sensorManager.hasGravity && sensorManager.hasGeomagnetic)
        assertTrue("Initial heading should now be established", sensorManager.hasInitialHeading)
        assertTrue("Azimuth should be updated to a valid heading", sensorManager.deviceAzimuth.value in 0f..<360f)

        // Test that stopListening clears both flags so a stale sensor doesn't carry over
        sensorManager.stopListening()
        assertFalse("Gravity must be cleared after stopListening", sensorManager.hasGravity)
        assertFalse("Geomagnetic must be cleared after stopListening", sensorManager.hasGeomagnetic)
    }

    @Test
    fun testAccuracyChangedDoesNotAlterHeading() {
        sensorManager.startListening()
        sensorManager.updateSmoothedAzimuth(120.0f)
        val currentHeading = sensorManager.deviceAzimuth.value

        // Accuracy events should have zero effect on heading
        sensorManager.onAccuracyChanged(null, 3) // SENSOR_STATUS_ACCURACY_HIGH
        assertEquals(currentHeading, sensorManager.deviceAzimuth.value, 1e-4f)

        sensorManager.onAccuracyChanged(null, 0) // SENSOR_STATUS_UNRELIABLE
        assertEquals(currentHeading, sensorManager.deviceAzimuth.value, 1e-4f)
    }
}
