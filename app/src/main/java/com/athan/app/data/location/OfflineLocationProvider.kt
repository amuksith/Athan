package com.athan.app.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import com.athan.app.util.InputValidator

sealed class LocationResult {
    data class Success(val location: Location) : LocationResult()
    object PermissionDenied : LocationResult()
    object ProviderDisabled : LocationResult()
    object Timeout : LocationResult()
    object NoFix : LocationResult()
    object InvalidCoordinates : LocationResult()
    object Cancelled : LocationResult()
    data class Error(val message: String) : LocationResult()
}

object OfflineLocationProvider {

    const val DEFAULT_TIMEOUT_MS: Long = 15_000L
    const val MAX_LAST_KNOWN_AGE_MS: Long = 1000L * 60 * 5 // 5 minutes fresh
    const val MAX_FUTURE_TIMESTAMP_TOLERANCE_MS: Long = 1000L * 60 * 5 // 5 minutes future tolerance

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun isLocationProviderAvailable(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        val gps = try { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (e: Exception) { false }
        val net = try { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { false }
        return gps || net
    }

    fun areLocationServicesEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        val masterEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                locationManager.isLocationEnabled
            } catch (e: Exception) {
                true
            }
        } else {
            true
        }
        return masterEnabled && isLocationProviderAvailable(context)
    }

    fun isValidCoordinates(latitude: Double, longitude: Double): Boolean {
        return InputValidator.isValidLatitude(latitude) &&
                InputValidator.isValidLongitude(longitude)
    }

    fun isValidLocation(location: Location?, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (location == null) return false
        val validCoords = isValidCoordinates(location.latitude, location.longitude)
        val validAltitude = if (location.hasAltitude()) InputValidator.isValidElevation(location.altitude) else true
        val notFutureDated = location.time <= (nowMillis + MAX_FUTURE_TIMESTAMP_TOLERANCE_MS)
        return validCoords && validAltitude && notFutureDated
    }

    fun getBestLastKnownLocation(context: Context): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return getBestLastKnownLocation(locationManager)
    }

    private fun getBestLastKnownLocation(locationManager: LocationManager): Location? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var bestLocation: Location? = null
        for (provider in providers) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    val loc = locationManager.getLastKnownLocation(provider)
                    if (loc != null && isValidLocation(loc) && (bestLocation == null || loc.time > bestLocation.time)) {
                        bestLocation = loc
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        return bestLocation
    }

    @SuppressLint("MissingPermission")
    suspend fun requestSingleLocation(
        context: Context,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
        useLastKnownIfFresh: Boolean = true
    ): LocationResult {
        val appContext = context.applicationContext
        if (!hasLocationPermission(appContext)) {
            return LocationResult.PermissionDenied
        }

        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return LocationResult.ProviderDisabled

        if (!areLocationServicesEnabled(appContext)) {
            return LocationResult.ProviderDisabled
        }

        val providersToListen = mutableListOf<String>()
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            providersToListen.add(LocationManager.GPS_PROVIDER)
        }
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            providersToListen.add(LocationManager.NETWORK_PROVIDER)
        }

        if (providersToListen.isEmpty()) {
            return LocationResult.ProviderDisabled
        }

        // Fresh last-known check
        if (useLastKnownIfFresh) {
            val bestLastKnown = getBestLastKnownLocation(locationManager)
            val now = System.currentTimeMillis()
            if (bestLastKnown != null && isValidLocation(bestLastKnown, now) &&
                (now - bestLastKnown.time) >= 0 &&
                (now - bestLastKnown.time) < MAX_LAST_KNOWN_AGE_MS
            ) {
                return LocationResult.Success(bestLastKnown)
            }
        }

        return try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            try {
                                locationManager.removeUpdates(this)
                            } catch (e: Exception) {
                                // Ignore
                            }
                            if (continuation.isActive) {
                                if (isValidLocation(location)) {
                                    continuation.resume(LocationResult.Success(location))
                                } else {
                                    continuation.resume(LocationResult.InvalidCoordinates)
                                }
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {
                            val anyStillEnabled = providersToListen.any { p ->
                                try { locationManager.isProviderEnabled(p) } catch (e: Exception) { false }
                            }
                            if (!anyStillEnabled) {
                                try {
                                    locationManager.removeUpdates(this)
                                } catch (e: Exception) {
                                    // Ignore
                                }
                                if (continuation.isActive) {
                                    continuation.resume(LocationResult.ProviderDisabled)
                                }
                            }
                        }
                    }

                    continuation.invokeOnCancellation {
                        try {
                            locationManager.removeUpdates(listener)
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }

                    try {
                        for (provider in providersToListen) {
                            locationManager.requestLocationUpdates(
                                provider,
                                0L,
                                0f,
                                listener,
                                Looper.getMainLooper()
                            )
                        }
                    } catch (e: SecurityException) {
                        try { locationManager.removeUpdates(listener) } catch (ignored: Exception) {}
                        if (continuation.isActive) {
                            continuation.resume(LocationResult.PermissionDenied)
                        }
                    } catch (e: IllegalArgumentException) {
                        try { locationManager.removeUpdates(listener) } catch (ignored: Exception) {}
                        if (continuation.isActive) {
                            continuation.resume(LocationResult.ProviderDisabled)
                        }
                    } catch (e: Exception) {
                        try { locationManager.removeUpdates(listener) } catch (ignored: Exception) {}
                        if (continuation.isActive) {
                            continuation.resume(LocationResult.Error(e.message ?: "Failed to request location"))
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            LocationResult.Timeout
        } catch (e: CancellationException) {
            LocationResult.Cancelled
        } catch (e: SecurityException) {
            LocationResult.PermissionDenied
        } catch (e: Exception) {
            LocationResult.Error(e.message ?: "Unknown location error")
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun getOneShotLocation(
        context: Context,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
        useLastKnownIfFresh: Boolean = true
    ): Location? {
        val result = requestSingleLocation(context, timeoutMillis, useLastKnownIfFresh)
        return if (result is LocationResult.Success) result.location else null
    }
}
