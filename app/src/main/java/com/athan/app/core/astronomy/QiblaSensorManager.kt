package com.athan.app.core.astronomy

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class QiblaSensorManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magneticSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val _deviceAzimuth = MutableStateFlow(0f)
    val deviceAzimuth: StateFlow<Float> = _deviceAzimuth.asStateFlow()

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)

    @VisibleForTesting
    internal var hasGravity = false
        private set

    @VisibleForTesting
    internal var hasGeomagnetic = false
        private set

    @VisibleForTesting
    internal var hasInitialHeading = false
        private set

    @VisibleForTesting
    internal var smoothedAzimuth = 0f
        private set

    companion object {
        const val SMOOTHING_FACTOR = 0.15f
    }

    fun startListening() {
        hasGravity = false
        hasGeomagnetic = false
        hasInitialHeading = false

        if (rotationVectorSensor != null) {
            sensorManager?.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            accelerometerSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            magneticSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    fun stopListening() {
        sensorManager?.unregisterListener(this)
        hasGravity = false
        hasGeomagnetic = false
        hasInitialHeading = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            var azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            azimuth = (azimuth + 360f) % 360f
            updateSmoothedAzimuth(azimuth)
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            handleAccelerometerData(event.values)
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            handleMagneticData(event.values)
        }
    }

    @VisibleForTesting
    internal fun handleAccelerometerData(values: FloatArray) {
        System.arraycopy(values, 0, gravity, 0, 3)
        hasGravity = true
        computeFallbackOrientation()
    }

    @VisibleForTesting
    internal fun handleMagneticData(values: FloatArray) {
        System.arraycopy(values, 0, geomagnetic, 0, 3)
        hasGeomagnetic = true
        computeFallbackOrientation()
    }

    private fun computeFallbackOrientation() {
        if (hasGravity && hasGeomagnetic) {
            val success = SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)
            if (success) {
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                var azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                azimuth = (azimuth + 360f) % 360f
                updateSmoothedAzimuth(azimuth)
            }
        }
    }

    @VisibleForTesting
    internal fun updateSmoothedAzimuth(newAzimuth: Float) {
        val normalizedAzimuth = (newAzimuth % 360f + 360f) % 360f
        if (!hasInitialHeading) {
            smoothedAzimuth = normalizedAzimuth
            _deviceAzimuth.value = normalizedAzimuth
            hasInitialHeading = true
        } else {
            var diff = normalizedAzimuth - smoothedAzimuth
            while (diff < -180f) diff += 360f
            while (diff > 180f) diff -= 360f
            smoothedAzimuth = (smoothedAzimuth + SMOOTHING_FACTOR * diff + 360f) % 360f
            _deviceAzimuth.value = smoothedAzimuth
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Accuracy changes do not alter or corrupt current heading state
    }
}
