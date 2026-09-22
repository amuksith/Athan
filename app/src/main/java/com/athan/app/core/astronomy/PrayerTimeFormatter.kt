package com.athan.app.core.astronomy

import com.athan.app.data.location.BundledCities
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Centralized formatter for prayer times.
 *
 * Guarantees that every prayer timestamp is formatted using the selected
 * prayer location's authoritative timezone (e.g., AthanSettings.location.timezoneId)
 * and NEVER silently falls back to the phone's device default timezone (TimeZone.getDefault()).
 */
object PrayerTimeFormatter {

    /**
     * The authoritative fallback timezone when an invalid, null, or blank timezone ID is provided.
     * Aligned with AthanSettings default location (Mecca / "Asia/Riyadh").
     * CRITICAL: Never falls back to TimeZone.getDefault().
     */
    val DEFAULT_FALLBACK_TIMEZONE_ID: String get() = TimeZoneResolver.DEFAULT_FALLBACK_TIMEZONE_ID

    /**
     * Validates whether [timezoneId] is a recognized Olson/IANA timezone identifier or standard UTC/GMT.
     * Delegates to centralized [TimeZoneResolver].
     */
    fun isValidTimezoneId(timezoneId: String?): Boolean {
        return TimeZoneResolver.isValidTimeZoneId(timezoneId)
    }

    /**
     * Resolves an authoritative TimeZone from [timezoneId].
     * If [timezoneId] is null, blank, or unrecognized, falls back to [DEFAULT_FALLBACK_TIMEZONE_ID]
     * ("Asia/Riyadh"). Crucially, it NEVER falls back to TimeZone.getDefault() and NEVER silently resolves to GMT.
     * Delegates to centralized [TimeZoneResolver].
     */
    fun resolveTimeZone(timezoneId: String?): TimeZone {
        return TimeZoneResolver.resolve(timezoneId)
    }

    /**
     * Formats an epoch millisecond timestamp into clock text (e.g., "05:30" or "5:30 AM")
     * strictly using the specified prayer location's timezoneId.
     */
    fun formatTime(
        millis: Long,
        is24Hour: Boolean = false,
        timezoneId: String,
        locale: Locale = Locale.getDefault()
    ): String {
        if (millis <= PrayerTimes.TIME_UNAVAILABLE) return "--:--"
        val tz = resolveTimeZone(timezoneId)
        return formatTime(millis = millis, is24Hour = is24Hour, timeZone = tz, locale = locale)
    }

    /**
     * Formats an epoch millisecond timestamp into clock text using an explicit [timeZone].
     */
    fun formatTime(
        millis: Long,
        is24Hour: Boolean = false,
        timeZone: TimeZone,
        locale: Locale = Locale.getDefault()
    ): String {
        if (millis <= PrayerTimes.TIME_UNAVAILABLE) return "--:--"
        val pattern = if (is24Hour) "HH:mm" else "h:mm a"
        val sdf = SimpleDateFormat(pattern, locale).apply {
            this.timeZone = timeZone
        }
        return sdf.format(Date(millis))
    }
}
