package com.athan.app.core.astronomy

import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import kotlin.math.*

object AstronomicalEngine {

    /**
     * Calculates prayer times for a specific date and geographic coordinate.
     * All calculations are 100% on-device and offline.
     */
    fun calculate(
        year: Int,
        month: Int, // 1-12
        day: Int,   // 1-31
        latitude: Double,
        longitude: Double,
        elevationMeters: Double = 0.0,
        method: CalculationMethod = CalculationMethod.MuslimWorldLeague,
        madhab: Madhab = Madhab.Shafi,
        highLatitudeRule: HighLatitudeRule = HighLatitudeRule.AngleBased,
        customFajrAngle: Double = 18.0,
        customIshaAngle: Double = 17.0,
        timeZone: TimeZone = TimeZoneResolver.DEFAULT_FALLBACK_TIMEZONE,
        hijriDayAdjustment: Int = 0
    ): PrayerTimes {
        // Guard against any invalid/corrupt parameters reaching the astronomy engine from any code path
        val safeLat = if (latitude.isFinite() && !latitude.isNaN()) latitude.coerceIn(-90.0, 90.0) else 0.0
        val safeLon = if (longitude.isFinite() && !longitude.isNaN()) longitude.coerceIn(-180.0, 180.0) else 0.0
        val safeElevation = if (elevationMeters.isFinite() && !elevationMeters.isNaN()) elevationMeters.coerceIn(-500.0, 9000.0) else 0.0
        val safeFajrAngle = if (customFajrAngle.isFinite() && !customFajrAngle.isNaN() && customFajrAngle > 0.0 && customFajrAngle <= 60.0) customFajrAngle else 18.0
        val safeIshaAngle = if (customIshaAngle.isFinite() && !customIshaAngle.isNaN() && customIshaAngle > 0.0 && customIshaAngle <= 60.0) customIshaAngle else 17.0
        val safeHijriAdj = hijriDayAdjustment.coerceIn(-2, 2)

        // 1. Base UTC midnight timestamp for the specified Gregorian date
        val baseUtcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val baseUtcMillis = baseUtcCal.timeInMillis

        // 2. Julian Date for UTC noon
        val jd = computeJulianDate(year, month, day)

        // 3. Solar coordinates at solar noon
        val solar = computeSunCoordinates(jd)
        val d = solar.declination
        val eot = solar.equationOfTime // in hours

        // 4. Solar noon in UTC hours: 12 - lon/15 - EoT
        val solarNoonUtcHours = 12.0 - (safeLon / 15.0) - eot

        // 5. Sunrise and Sunset in UTC hours (altitude -0.8333° + elevation dip)
        val elevationDip = if (safeElevation > 0) 0.0347 * sqrt(safeElevation) else 0.0
        val sunriseAltitude = -0.8333 - elevationDip

        val sunriseHa = hourAngle(safeLat, d, sunriseAltitude)
        val sunriseUtcHours: Double? = if (sunriseHa != null) solarNoonUtcHours - (sunriseHa / 15.0) else null
        val sunsetUtcHours: Double? = if (sunriseHa != null) solarNoonUtcHours + (sunriseHa / 15.0) else null

        // Night duration calculated from actual astronomical sunrise & sunset if available
        val nightDurationHours: Double? = if (sunriseUtcHours != null && sunsetUtcHours != null) {
            (24.0 - sunsetUtcHours) + sunriseUtcHours
        } else {
            null
        }

        // 6. Fajr in UTC hours
        val fajrAngle = method.getFajrAngle(safeFajrAngle)

        var fajrUtcHours: Double? = if (sunriseUtcHours == null || sunsetUtcHours == null || nightDurationHours == null) {
            // During true polar conditions (Polar Day or Polar Night) where sunrise or sunset do not exist,
            // Fajr is unavailable.
            null
        } else {
            val fajrHa = hourAngle(safeLat, d, -fajrAngle)
            if (fajrHa != null) {
                // Situation 1: Normal astronomical calculation succeeds -> return calculated value regardless of rule
                solarNoonUtcHours - (fajrHa / 15.0)
            } else {
                // Situation 2: Normal calculation fails - apply high-latitude rule only when real sunrise and sunset exist
                if (highLatitudeRule == HighLatitudeRule.None) {
                    null
                } else {
                    applyHighLatitudeRule(
                        sunrise = sunriseUtcHours,
                        sunset = sunsetUtcHours,
                        nightDuration = nightDurationHours,
                        angle = fajrAngle,
                        isFajr = true,
                        rule = highLatitudeRule
                    )
                }
            }
        }

        // Fajr must be chronologically before sunrise. If calculation wrapped before midnight,
        // adjust by subtracting 24 hours.
        if (fajrUtcHours != null && sunriseUtcHours != null && fajrUtcHours > sunriseUtcHours) {
            fajrUtcHours -= 24.0
        }

        // 7. Dhuhr in UTC hours (Transit + safety buffer of ~1 minute)
        val dhuhrUtcHours = solarNoonUtcHours + (1.0 / 60.0)

        // 8. Asr in UTC hours (Shadow length formula)
        val asrUtcHours: Double? = if (abs(safeLat - d) >= 90.0) {
            // During polar night when the sun does not rise above the horizon at solar noon,
            // no shadow can be cast and Asr is unavailable.
            null
        } else {
            val shadowLength = tan(abs(Math.toRadians(safeLat - d))) + madhab.shadowMultiplier
            if (shadowLength <= 0.0) {
                null
            } else {
                val asrAltitude = Math.toDegrees(atan(1.0 / shadowLength))
                if (asrAltitude <= 0.0) {
                    null
                } else {
                    val asrHa = hourAngle(safeLat, d, asrAltitude)
                    if (asrHa != null) {
                        solarNoonUtcHours + (asrHa / 15.0)
                    } else {
                        null
                    }
                }
            }
        }

        // 9. Maghrib in UTC hours
        val maghribUtcHours: Double? = if (method == CalculationMethod.Tehran) {
            val tehranHa = hourAngle(safeLat, d, -4.5)
            if (tehranHa != null) {
                solarNoonUtcHours + (tehranHa / 15.0)
            } else if (sunsetUtcHours != null) {
                sunsetUtcHours + (15.0 / 60.0)
            } else {
                null
            }
        } else {
            if (sunsetUtcHours != null) {
                sunsetUtcHours + (1.0 / 60.0) // ~1 min after disk disappearance
            } else {
                null
            }
        }

        // 10. Isha in UTC hours
        var ishaUtcHours: Double? = if (sunriseUtcHours == null || sunsetUtcHours == null || nightDurationHours == null) {
            // During true polar conditions (Polar Day or Polar Night) where sunrise or sunset do not exist,
            // Isha is unavailable.
            null
        } else if (method.isIshaInterval()) {
            if (maghribUtcHours != null) {
                val isRamadan = HijriCalendar.fromGregorian(year, month, day, safeHijriAdj).month == 9
                val intervalMinutes = method.getIshaIntervalMinutes(isRamadan = isRamadan)
                maghribUtcHours + (intervalMinutes / 60.0)
            } else {
                null
            }
        } else {
            val ishaAngle = method.getIshaAngle(safeIshaAngle)
            val ishaHa = hourAngle(safeLat, d, -ishaAngle)
            if (ishaHa != null) {
                // Situation 1: Normal astronomical calculation succeeds -> return calculated value regardless of rule
                solarNoonUtcHours + (ishaHa / 15.0)
            } else {
                // Situation 2: Normal calculation fails - apply high-latitude rule only when real sunrise and sunset exist
                if (highLatitudeRule == HighLatitudeRule.None) {
                    null
                } else {
                    applyHighLatitudeRule(
                        sunrise = sunriseUtcHours,
                        sunset = sunsetUtcHours,
                        nightDuration = nightDurationHours,
                        angle = ishaAngle,
                        isFajr = false,
                        rule = highLatitudeRule
                    )
                }
            }
        }

        // Isha must be chronologically after Maghrib. If calculation wrapped past midnight,
        // ensure ishaUtcHours is after maghribUtcHours by adding 24 if needed.
        if (ishaUtcHours != null && maghribUtcHours != null && ishaUtcHours < maghribUtcHours) {
            ishaUtcHours += 24.0
        }

        // Convert UTC decimal hours into exact UTC epoch timestamps
        val fajrMillis = if (fajrUtcHours != null) utcHoursToEpochMillis(baseUtcMillis, fajrUtcHours) else PrayerTimes.TIME_UNAVAILABLE
        val sunriseMillis = if (sunriseUtcHours != null) utcHoursToEpochMillis(baseUtcMillis, sunriseUtcHours) else PrayerTimes.TIME_UNAVAILABLE
        val dhuhrMillis = utcHoursToEpochMillis(baseUtcMillis, dhuhrUtcHours)
        val asrMillis = if (asrUtcHours != null) utcHoursToEpochMillis(baseUtcMillis, asrUtcHours) else PrayerTimes.TIME_UNAVAILABLE
        val maghribMillis = if (maghribUtcHours != null) utcHoursToEpochMillis(baseUtcMillis, maghribUtcHours) else PrayerTimes.TIME_UNAVAILABLE
        val ishaMillis = if (ishaUtcHours != null) utcHoursToEpochMillis(baseUtcMillis, ishaUtcHours) else PrayerTimes.TIME_UNAVAILABLE

        // Timezone conversion is handled per event using each event's own timestamp:
        // By calculating each event's absolute UTC instant first, the local civil time and
        // offset are derived independently for each event from its actual occurrence instant,
        // preventing any 1-hour shifts across Daylight Saving Time (DST) transitions.
        val dateStr = String.format("%04d-%02d-%02d", year, month, day)

        return PrayerTimes(
            fajr = fajrMillis,
            sunrise = sunriseMillis,
            dhuhr = dhuhrMillis,
            asr = asrMillis,
            maghrib = maghribMillis,
            isha = ishaMillis,
            dateString = dateStr,
            timezoneId = timeZone.id
        )
    }

    /**
     * Calculates prayer times for a full calendar month.
     */
    fun calculateMonth(
        year: Int,
        month: Int,
        latitude: Double,
        longitude: Double,
        elevationMeters: Double = 0.0,
        method: CalculationMethod = CalculationMethod.MuslimWorldLeague,
        madhab: Madhab = Madhab.Shafi,
        highLatitudeRule: HighLatitudeRule = HighLatitudeRule.AngleBased,
        customFajrAngle: Double = 18.0,
        customIshaAngle: Double = 17.0,
        timeZone: TimeZone = TimeZoneResolver.DEFAULT_FALLBACK_TIMEZONE,
        hijriDayAdjustment: Int = 0
    ): List<PrayerTimes> {
        val cal = Calendar.getInstance(timeZone).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val maxDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val list = ArrayList<PrayerTimes>(maxDays)
        for (day in 1..maxDays) {
            list.add(
                calculate(
                    year = year,
                    month = month,
                    day = day,
                    latitude = latitude,
                    longitude = longitude,
                    elevationMeters = elevationMeters,
                    method = method,
                    madhab = madhab,
                    highLatitudeRule = highLatitudeRule,
                    customFajrAngle = customFajrAngle,
                    customIshaAngle = customIshaAngle,
                    timeZone = timeZone,
                    hijriDayAdjustment = hijriDayAdjustment
                )
            )
        }
        return list
    }

    // --- Astronomical Mathematics Core ---

    private fun computeJulianDate(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private data class SolarCoordinates(val declination: Double, val equationOfTime: Double)

    private fun computeSunCoordinates(jd: Double): SolarCoordinates {
        val d = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * sin(Math.toRadians(g)) + 0.020 * sin(Math.toRadians(2 * g)))

        val e = 23.439 - 0.00000036 * d
        val ra = Math.toDegrees(
            atan2(
                cos(Math.toRadians(e)) * sin(Math.toRadians(l)),
                cos(Math.toRadians(l))
            )
        ) / 15.0

        val declination = Math.toDegrees(asin(sin(Math.toRadians(e)) * sin(Math.toRadians(l))))
        val eqOfTime = (q / 15.0) - fixHour(ra)

        return SolarCoordinates(declination = declination, equationOfTime = eqOfTime)
    }

    private fun hourAngle(latitude: Double, declination: Double, altitude: Double): Double? {
        val latRad = Math.toRadians(latitude)
        val decRad = Math.toRadians(declination)
        val altRad = Math.toRadians(altitude)

        val cosHa = (sin(altRad) - sin(latRad) * sin(decRad)) / (cos(latRad) * cos(decRad))
        if (cosHa < -1.0 || cosHa > 1.0) {
            return null // Sun does not reach this altitude
        }
        return Math.toDegrees(acos(cosHa))
    }

    private fun applyHighLatitudeRule(
        sunrise: Double,
        sunset: Double,
        nightDuration: Double,
        angle: Double,
        isFajr: Boolean,
        rule: HighLatitudeRule
    ): Double {
        val nightPortion: Double = when (rule) {
            HighLatitudeRule.MiddleOfTheNight -> nightDuration / 2.0
            HighLatitudeRule.SeventhOfTheNight -> nightDuration / 7.0
            HighLatitudeRule.AngleBased -> nightDuration * (angle / 60.0)
            HighLatitudeRule.None -> throw IllegalArgumentException("HighLatitudeRule.None must never perform a fallback calculation")
        }

        return if (isFajr) {
            sunrise - nightPortion
        } else {
            sunset + nightPortion
        }
    }

    /**
     * Converts UTC decimal hours relative to the base UTC day midnight into an absolute epoch millisecond instant.
     */
    fun utcHoursToEpochMillis(baseUtcMillis: Long, utcHours: Double): Long {
        if (!utcHours.isFinite() || utcHours.isNaN()) {
            return 0L
        }
        val totalMillis = (utcHours * 3600000.0).roundToLong()
        return baseUtcMillis + totalMillis
    }

    /**
     * Returns the timezone offset in milliseconds for the given epoch instant in the specified timezone.
     */
    fun getTimeZoneOffset(epochMillis: Long, timeZone: TimeZone): Int {
        return timeZone.getOffset(epochMillis)
    }

    /**
     * Converts an epoch instant into local civil decimal hours in the specified timezone.
     */
    fun getLocalDecimalHours(epochMillis: Long, timeZone: TimeZone): Double {
        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = epochMillis
        }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)
        val millis = cal.get(Calendar.MILLISECOND)
        return hour + (minute / 60.0) + (second / 3600.0) + (millis / 3600000.0)
    }

    private fun decimalHoursToEpoch(baseCalendar: Calendar, decimalHours: Double): Long {
        val daysOffset = floor(decimalHours / 24.0).toInt()
        val normalizedHours = fixHour(decimalHours)
        val totalSeconds = (normalizedHours * 3600.0).roundToLong()
        val extraDays = (totalSeconds / 86400).toInt()
        val remSeconds = totalSeconds % 86400
        val hours = (remSeconds / 3600).toInt()
        val minutes = ((remSeconds % 3600) / 60).toInt()
        val seconds = (remSeconds % 60).toInt()

        val cal = (baseCalendar.clone() as Calendar).apply {
            val netDays = daysOffset + extraDays
            if (netDays != 0) {
                add(Calendar.DAY_OF_MONTH, netDays)
            }
            set(Calendar.HOUR_OF_DAY, hours)
            set(Calendar.MINUTE, minutes)
            set(Calendar.SECOND, seconds)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun fixAngle(a: Double): Double {
        var res = a - 360.0 * floor(a / 360.0)
        if (res < 0) res += 360.0
        return res
    }

    private fun fixHour(h: Double): Double {
        var res = h - 24.0 * floor(h / 24.0)
        if (res < 0) res += 24.0
        return res
    }
}
