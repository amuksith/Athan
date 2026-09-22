package com.athan.app.core.astronomy

import android.util.Log
import com.athan.app.data.location.BundledCities
import java.util.TimeZone

/**
 * Centralized, authoritative timezone validation and resolution mechanism.
 *
 * Prevents Java/Android TimeZone.getTimeZone(String) from silently returning "GMT"
 * for invalid, nonexistent, or corrupted timezone IDs (such as "Invalid/Timezone",
 * "Not/A/RealZone", "BogusZone", or empty strings).
 */
object TimeZoneResolver {

    private const val TAG = "TimeZoneResolver"

    /**
     * Set of available timezone IDs recognized by the Android/Java runtime timezone database.
     */
    private val availableZoneIds: Set<String> by lazy {
        TimeZone.getAvailableIDs().toSet()
    }

    /**
     * Authoritative fallback timezone ID (Asia/Riyadh, matching BundledCities.DEFAULT_CITY).
     * Never falls back to TimeZone.getDefault().
     */
    val DEFAULT_FALLBACK_TIMEZONE_ID: String = BundledCities.DEFAULT_CITY.timezoneId

    /**
     * Authoritative fallback TimeZone object.
     */
    val DEFAULT_FALLBACK_TIMEZONE: TimeZone by lazy {
        TimeZone.getTimeZone(DEFAULT_FALLBACK_TIMEZONE_ID)
    }

    /**
     * Validates whether [id] is a recognized, valid timezone identifier in the runtime timezone database.
     *
     * Explicitly rejects:
     * - null, empty, or whitespace-only strings
     * - Fake / invalid / corrupted identifiers (e.g., "Invalid/Timezone", "Not/A/RealZone", "BogusZone")
     *   which TimeZone.getTimeZone(...) would silently convert to GMT without throwing an exception.
     *
     * Explicitly accepts:
     * - Canonical IANA timezone identifiers (e.g., "Asia/Colombo", "Europe/London", "America/New_York")
     * - Legitimate, genuine "GMT" or "UTC" timezone identifiers.
     */
    fun isValidTimeZoneId(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        val trimmed = id.trim()
        if (availableZoneIds.contains(trimmed)) {
            return true
        }
        // Explicitly accept canonical GMT and UTC designations if not present in availableZoneIds
        if (trimmed == "GMT" || trimmed == "UTC") {
            return true
        }
        return false
    }

    /**
     * Resolves an authoritative [TimeZone] from [id].
     *
     * If [id] is null, blank, or unrecognized by the runtime timezone database,
     * it returns [fallback] (defaulting to [DEFAULT_FALLBACK_TIMEZONE] / "Asia/Riyadh")
     * and NEVER silently converts the invalid ID to GMT.
     */
    fun resolve(id: String?, fallback: TimeZone = DEFAULT_FALLBACK_TIMEZONE): TimeZone {
        if (!isValidTimeZoneId(id)) {
            Log.w(TAG, "Unrecognized or invalid timezone ID '$id'; resolving to fallback '${fallback.id}'")
            return fallback
        }
        return TimeZone.getTimeZone(id!!.trim())
    }

    /**
     * Resolves an authoritative [TimeZone] from [id], using [fallbackId] if [id] is invalid.
     */
    fun resolve(id: String?, fallbackId: String): TimeZone {
        val fallback = if (isValidTimeZoneId(fallbackId)) {
            TimeZone.getTimeZone(fallbackId.trim())
        } else {
            DEFAULT_FALLBACK_TIMEZONE
        }
        return resolve(id, fallback)
    }

    /**
     * Sanitizes a timezone ID string. Returns [id] (trimmed) if valid, or [fallbackId] if invalid.
     */
    fun sanitizeId(id: String?, fallbackId: String = DEFAULT_FALLBACK_TIMEZONE_ID): String {
        return if (isValidTimeZoneId(id)) {
            id!!.trim()
        } else {
            if (isValidTimeZoneId(fallbackId)) fallbackId.trim() else DEFAULT_FALLBACK_TIMEZONE_ID
        }
    }
}
