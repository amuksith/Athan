package com.athan.app.core.astronomy

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class PrayerType(
    val englishName: String,
    val arabicName: String,
    val isFardh: Boolean = true
) {
    FAJR("Fajr", "الفجر", true),
    SUNRISE("Sunrise", "الشروق", false),
    DHUHR("Dhuhr", "الظهر", true),
    ASR("Asr", "العصر", true),
    MAGHRIB("Maghrib", "المغرب", true),
    ISHA("Isha", "العشاء", true)
}

data class NextPrayerState(
    val prayerType: PrayerType?,
    val timestamp: Long?,
    val isAvailable: Boolean,
    val remainingMillis: Long? = null
) {
    val available: Boolean get() = isAvailable

    companion object {
        val UNAVAILABLE = NextPrayerState(
            prayerType = null,
            timestamp = null,
            isAvailable = false,
            remainingMillis = null
        )
    }
}

data class ChronologicalValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null,
    val conflictingEarlier: PrayerType? = null,
    val conflictingLater: PrayerType? = null
) {
    companion object {
        val VALID = ChronologicalValidationResult(isValid = true)
    }
}

data class PrayerTimes(
    val fajr: Long,
    val sunrise: Long,
    val dhuhr: Long,
    val asr: Long,
    val maghrib: Long,
    val isha: Long,
    val dateString: String = "",
    val timezoneId: String = ""
) {
    companion object {
        const val TIME_UNAVAILABLE: Long = 0L

        /**
         * Resolves the next upcoming prayer state given today's schedule, tomorrow's schedule,
         * day-after-tomorrow's schedule, and the current epoch timestamp.
         *
         * Searches in this exact order:
         * 1. Today's available future prayers.
         * 2. Tomorrow's available future prayers.
         * 3. Day-after-tomorrow's available prayers.
         *
         * Returns NextPrayerState.UNAVAILABLE only when none of the three days contains an available upcoming prayer.
         * Unavailable prayers (TIME_UNAVAILABLE = 0L) are strictly skipped.
         */
        fun resolveNextPrayer(
            todayPrayers: PrayerTimes,
            tomorrowPrayers: PrayerTimes?,
            currentTimeMillis: Long
        ): NextPrayerState {
            return resolveNextPrayer(
                todayPrayers = todayPrayers,
                tomorrowPrayers = tomorrowPrayers,
                dayAfterTomorrowPrayers = null,
                currentTimeMillis = currentTimeMillis
            )
        }

        fun resolveNextPrayer(
            todayPrayers: PrayerTimes,
            tomorrowPrayers: PrayerTimes? = null,
            dayAfterTomorrowPrayers: PrayerTimes? = null,
            currentTimeMillis: Long = System.currentTimeMillis()
        ): NextPrayerState {
            // 1. Today's available future prayers
            for (item in todayPrayers.getAllPrayers()) {
                if (item.second > TIME_UNAVAILABLE && item.second > currentTimeMillis) {
                    val remaining = item.second - currentTimeMillis
                    return NextPrayerState(
                        prayerType = item.first,
                        timestamp = item.second,
                        isAvailable = true,
                        remainingMillis = remaining
                    )
                }
            }

            // 2. Tomorrow's available future prayers
            if (tomorrowPrayers != null) {
                for (item in tomorrowPrayers.getAllPrayers()) {
                    if (item.second > TIME_UNAVAILABLE && item.second > currentTimeMillis) {
                        val remaining = item.second - currentTimeMillis
                        return NextPrayerState(
                            prayerType = item.first,
                            timestamp = item.second,
                            isAvailable = true,
                            remainingMillis = remaining
                        )
                    }
                }
            }

            // 3. Day-after-tomorrow's available prayers
            if (dayAfterTomorrowPrayers != null) {
                for (item in dayAfterTomorrowPrayers.getAllPrayers()) {
                    if (item.second > TIME_UNAVAILABLE && item.second > currentTimeMillis) {
                        val remaining = item.second - currentTimeMillis
                        return NextPrayerState(
                            prayerType = item.first,
                            timestamp = item.second,
                            isAvailable = true,
                            remainingMillis = remaining
                        )
                    }
                }
            }

            // 4. Return NextPrayerState.UNAVAILABLE only when none of the three days contains an available upcoming prayer
            return NextPrayerState.UNAVAILABLE
        }
    }

    val isFajrAvailable: Boolean get() = fajr > TIME_UNAVAILABLE
    val isSunriseAvailable: Boolean get() = sunrise > TIME_UNAVAILABLE
    val isDhuhrAvailable: Boolean get() = dhuhr > TIME_UNAVAILABLE
    val isAsrAvailable: Boolean get() = asr > TIME_UNAVAILABLE
    val isMaghribAvailable: Boolean get() = maghrib > TIME_UNAVAILABLE
    val isIshaAvailable: Boolean get() = isha > TIME_UNAVAILABLE

    fun isAvailable(prayerType: PrayerType): Boolean = getTime(prayerType) > TIME_UNAVAILABLE

    fun getTime(prayerType: PrayerType): Long {
        return when (prayerType) {
            PrayerType.FAJR -> fajr
            PrayerType.SUNRISE -> sunrise
            PrayerType.DHUHR -> dhuhr
            PrayerType.ASR -> asr
            PrayerType.MAGHRIB -> maghrib
            PrayerType.ISHA -> isha
        }
    }

    fun getAllPrayers(): List<Pair<PrayerType, Long>> {
        return listOf(
            PrayerType.FAJR to fajr,
            PrayerType.SUNRISE to sunrise,
            PrayerType.DHUHR to dhuhr,
            PrayerType.ASR to asr,
            PrayerType.MAGHRIB to maghrib,
            PrayerType.ISHA to isha
        )
    }

    /**
     * Produces a new PrayerTimes instance by applying user manual minute adjustments
     * to the calculated astronomical prayer times.
     *
     * Validates adjustments to stay strictly within -30..+30 minutes.
     * Unavailable prayer times (<= 0L) are preserved without modification.
     */
    fun withAdjustments(adjustments: Map<PrayerType, Int>?): PrayerTimes {
        if (adjustments.isNullOrEmpty()) return this

        fun adjustTime(time: Long, prayerType: PrayerType): Long {
            if (time <= TIME_UNAVAILABLE) return TIME_UNAVAILABLE
            val rawMinutes = adjustments[prayerType] ?: 0
            val offsetMinutes = if (rawMinutes in -30..30) rawMinutes else 0
            if (offsetMinutes == 0) return time
            val adjusted = time + (offsetMinutes * 60 * 1000L)
            return if (adjusted > TIME_UNAVAILABLE) adjusted else TIME_UNAVAILABLE
        }

        return copy(
            fajr = adjustTime(fajr, PrayerType.FAJR),
            sunrise = adjustTime(sunrise, PrayerType.SUNRISE),
            dhuhr = adjustTime(dhuhr, PrayerType.DHUHR),
            asr = adjustTime(asr, PrayerType.ASR),
            maghrib = adjustTime(maghrib, PrayerType.MAGHRIB),
            isha = adjustTime(isha, PrayerType.ISHA)
        )
    }

    fun withAdjustment(prayerType: PrayerType, minutes: Int): PrayerTimes {
        return withAdjustments(mapOf(prayerType to minutes))
    }

    /**
     * Validates that all available prayer times follow canonical chronological order:
     * Fajr < Sunrise < Dhuhr < Asr < Maghrib < Isha.
     * Unavailable prayer times (<= 0L) do not participate in ordering comparisons.
     * Epoch timestamps are compared directly to handle midnight crossing correctly.
     * Earlier times must be strictly less than later times (<).
     */
    fun validateChronologicalOrder(): ChronologicalValidationResult {
        val available = getAllPrayers().filter { it.second > TIME_UNAVAILABLE }
        for (i in 0 until available.size - 1) {
            val current = available[i]
            val next = available[i + 1]
            if (current.second >= next.second) {
                val earlierTimeStr = PrayerTimeFormatter.formatTime(current.second, false, timezoneId)
                val laterTimeStr = PrayerTimeFormatter.formatTime(next.second, false, timezoneId)
                return ChronologicalValidationResult(
                    isValid = false,
                    errorMessage = "${current.first.englishName} ($earlierTimeStr) cannot be at or after ${next.first.englishName} ($laterTimeStr).",
                    conflictingEarlier = current.first,
                    conflictingLater = next.first
                )
            }
        }
        return ChronologicalValidationResult.VALID
    }

    val isChronologicallyValid: Boolean
        get() = validateChronologicalOrder().isValid

    /**
     * Applies manual adjustments while strictly guaranteeing that the resulting schedule
     * preserves canonical chronological order among all available prayers.
     *
     * If the proposed adjustments are valid, they are applied directly.
     * If the proposed adjustments produce an invalid schedule, recovers safely by applying
     * only non-conflicting adjustments (preserving as much valid configuration as practical),
     * and discarding adjustments that cause ordering violations.
     */
    fun withValidatedAdjustments(adjustments: Map<PrayerType, Int>?): PrayerTimes {
        if (adjustments.isNullOrEmpty()) return this
        val candidate = withAdjustments(adjustments)
        if (candidate.validateChronologicalOrder().isValid) {
            return candidate
        }
        // Recover safely: apply only non-conflicting adjustments deterministically
        var safeSchedule = this
        for (prayer in PrayerType.entries) {
            val mins = adjustments[prayer] ?: 0
            if (mins != 0) {
                val testCandidate = safeSchedule.withAdjustment(prayer, mins)
                if (testCandidate.validateChronologicalOrder().isValid) {
                    safeSchedule = testCandidate
                }
            }
        }
        return safeSchedule
    }

    /**
     * Determines the upcoming prayer.
     * Checks today's schedule first; if exhausted and tomorrowPrayers is provided,
     * checks tomorrow's schedule in defined order (Fajr, Sunrise, Dhuhr, Asr, Maghrib, Isha).
     * Unavailable prayers (TIME_UNAVAILABLE = 0L) are strictly skipped.
     * Returns null if no valid future upcoming prayer exists.
     */
    fun getNextPrayer(
        currentTimeMillis: Long,
        tomorrowPrayers: PrayerTimes? = null
    ): Pair<PrayerType, Long>? {
        for (item in getAllPrayers()) {
            if (item.second > TIME_UNAVAILABLE && currentTimeMillis < item.second) {
                return item
            }
        }
        if (tomorrowPrayers != null) {
            for (item in tomorrowPrayers.getAllPrayers()) {
                if (item.second > TIME_UNAVAILABLE && item.second > currentTimeMillis) {
                    return item
                }
            }
        }
        return null
    }

    /**
     * Determines the current active prayer for the day.
     * Unavailable prayers are completely skipped and never returned as current.
     */
    fun getCurrentPrayer(currentTimeMillis: Long): PrayerType? {
        val availablePrayers = getAllPrayers().filter { it.second > TIME_UNAVAILABLE }
        if (availablePrayers.isEmpty()) return null
        if (currentTimeMillis < availablePrayers.first().second) return null
        return availablePrayers.lastOrNull { currentTimeMillis >= it.second }?.first
    }

    /**
     * Obtains the timezone offset in milliseconds for the actual event timestamp.
     */
    fun getOffsetMillis(prayerType: PrayerType, timeZone: TimeZone): Int {
        val time = getTime(prayerType)
        return if (time > TIME_UNAVAILABLE) timeZone.getOffset(time) else 0
    }

    /**
     * Obtains the timezone offset in hours for the actual event timestamp.
     */
    fun getOffsetHours(prayerType: PrayerType, timeZone: TimeZone): Double {
        return getOffsetMillis(prayerType, timeZone).toDouble() / (1000.0 * 60.0 * 60.0)
    }

    fun formatTime(
        timeMillis: Long,
        is24Hour: Boolean = false,
        timezoneId: String? = null
    ): String {
        if (timeMillis <= TIME_UNAVAILABLE) return "--:--"
        val targetTz = if (!timezoneId.isNullOrBlank()) {
            timezoneId
        } else if (this.timezoneId.isNotBlank()) {
            this.timezoneId
        } else {
            PrayerTimeFormatter.DEFAULT_FALLBACK_TIMEZONE_ID
        }
        return PrayerTimeFormatter.formatTime(timeMillis, is24Hour, targetTz)
    }

    fun formatTime(
        timeMillis: Long,
        is24Hour: Boolean = false,
        timeZone: TimeZone
    ): String {
        if (timeMillis <= TIME_UNAVAILABLE) return "--:--"
        return PrayerTimeFormatter.formatTime(timeMillis, is24Hour, timeZone)
    }
}
