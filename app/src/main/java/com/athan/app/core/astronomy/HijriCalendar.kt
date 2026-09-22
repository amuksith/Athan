package com.athan.app.core.astronomy

import java.util.Calendar
import kotlin.math.floor

data class HijriDate(
    val year: Int,
    val month: Int,
    val day: Int,
    val monthNameEnglish: String,
    val monthNameArabic: String
) {
    fun formatDisplay(): String {
        return "$day $monthNameEnglish $year AH"
    }

    fun formatArabicDisplay(): String {
        return "$day $monthNameArabic $year هـ"
    }
}

object HijriCalendar {
    private val MONTH_NAMES_ENGLISH = arrayOf(
        "Muharram", "Safar", "Rabi' al-Awwal", "Rabi' al-Thani",
        "Jumada al-Awwal", "Jumada al-Thani", "Rajab", "Sha'ban",
        "Ramadan", "Shawwal", "Dhu al-Qi'dah", "Dhu al-Hijjah"
    )

    private val MONTH_NAMES_ARABIC = arrayOf(
        "محرم", "صفر", "ربيع الأول", "ربيع الثاني",
        "جمادى الأولى", "جمادى الثانية", "رجب", "شعبان",
        "رمضان", "شوال", "ذو القعدة", "ذو الحجة"
    )

    // Standard 30-year Islamic cycle leap years (years 2, 5, 7, 10, 13, 16, 18, 21, 24, 26, 29)
    private val LEAP_YEARS = booleanArrayOf(
        false, // index 0 unused
        false, true, false, false, true,
        false, true, false, false, true,
        false, false, true, false, false,
        true, false, true, false, false,
        true, false, false, true, false,
        true, false, false, true, false
    )

    private fun daysInMonth(month: Int, isLeapYear: Boolean): Int {
        return when (month) {
            1, 3, 5, 7, 9, 11 -> 30
            2, 4, 6, 8, 10 -> 29
            12 -> if (isLeapYear) 30 else 29
            else -> 30
        }
    }

    /**
     * Converts a Gregorian calendar date to Hijri (Islamic) date algorithmically,
     * working 100% offline without any network call.
     */
    fun fromGregorian(year: Int, month: Int, day: Int, dayAdjustment: Int = 0): HijriDate {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        val jd = floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5 + dayAdjustment

        // Days elapsed since Islamic epoch (1 Muharram 1 AH = JD 1948439.5)
        var days = (jd - 1948439.5).toLong()
        if (days < 0) days = 0

        val cycle = (days / 10631).toInt()
        var remDays = (days % 10631).toInt()

        var yearInCycle = 1
        while (yearInCycle <= 30) {
            val daysInYear = if (LEAP_YEARS[yearInCycle]) 355 else 354
            if (remDays < daysInYear) {
                break
            }
            remDays -= daysInYear
            yearInCycle++
        }

        val hijriYear = cycle * 30 + yearInCycle
        val isLeap = LEAP_YEARS[yearInCycle.coerceIn(1, 30)]

        var hijriMonth = 1
        while (hijriMonth <= 12) {
            val dim = daysInMonth(hijriMonth, isLeap)
            if (remDays < dim) {
                break
            }
            remDays -= dim
            hijriMonth++
        }

        val safeMonth = (hijriMonth - 1).coerceIn(0, 11)
        val hijriDay = (remDays + 1).coerceIn(1, 30)

        return HijriDate(
            year = hijriYear,
            month = safeMonth + 1,
            day = hijriDay,
            monthNameEnglish = MONTH_NAMES_ENGLISH[safeMonth],
            monthNameArabic = MONTH_NAMES_ARABIC[safeMonth]
        )
    }

    fun today(dayAdjustment: Int = 0): HijriDate {
        val cal = Calendar.getInstance()
        return fromGregorian(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            dayAdjustment
        )
    }
}
