package com.athan.app.core.astronomy

import com.athan.app.data.location.BundledCities
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Represents a discrete local calendar day in a specific time zone.
 * Used to drive day-boundary updates, Gregorian date formatting,
 * and Hijri date synchronization across midnight and lifecycle events.
 */
data class LocalCalendarDay(
    val year: Int,
    val month: Int, // 1-indexed (1 = January, 12 = December)
    val dayOfMonth: Int,
    val timeZoneId: String = BundledCities.DEFAULT_CITY.timezoneId
) {
    fun getTimeZone(): TimeZone {
        return TimeZoneResolver.resolve(timeZoneId)
    }

    val dateKey: String get() = String.format(Locale.US, "%04d-%02d-%02d", year, month, dayOfMonth)

    fun formatGregorian(locale: Locale = Locale.getDefault()): String {
        val tz = getTimeZone()
        val cal = Calendar.getInstance(tz).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, dayOfMonth)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sdf = SimpleDateFormat("EEEE, d MMMM yyyy", locale)
        sdf.timeZone = tz
        return sdf.format(cal.time)
    }

    fun toHijriDate(hijriDayAdjustment: Int = 0): HijriDate {
        return HijriCalendar.fromGregorian(
            year = year,
            month = month,
            day = dayOfMonth,
            dayAdjustment = hijriDayAdjustment
        )
    }

    companion object {
        fun fromMillis(timeMillis: Long, timeZone: TimeZone): LocalCalendarDay {
            val cal = Calendar.getInstance(timeZone).apply {
                this.timeInMillis = timeMillis
            }
            return LocalCalendarDay(
                year = cal.get(Calendar.YEAR),
                month = cal.get(Calendar.MONTH) + 1,
                dayOfMonth = cal.get(Calendar.DAY_OF_MONTH),
                timeZoneId = timeZone.id
            )
        }

        fun getMillisUntilNextMidnight(currentTimeMillis: Long, timeZone: TimeZone): Long {
            val cal = Calendar.getInstance(timeZone).apply {
                this.timeInMillis = currentTimeMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, 1)
            }
            return (cal.timeInMillis - currentTimeMillis).coerceAtLeast(1L)
        }
    }
}
