package com.athan.app

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.athan.app.core.astronomy.AstronomicalEngine
import com.athan.app.core.astronomy.CalculationMethod
import com.athan.app.core.astronomy.HighLatitudeRule
import com.athan.app.core.astronomy.Madhab
import com.athan.app.core.astronomy.PrayerTimes
import com.athan.app.core.astronomy.PrayerType
import com.athan.app.data.location.BundledCities
import com.athan.app.data.repository.AthanPreferences
import com.athan.app.receiver.AthanAlarmReceiver
import com.athan.app.scheduling.AthanAlarmScheduler
import com.athan.app.ui.PrayerViewModel
import com.athan.app.util.InputValidator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.text.SimpleDateFormat
import java.util.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrayerTimeAdjustmentTest {

    private lateinit var context: Context
    private lateinit var prefs: AthanPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val sp = context.getSharedPreferences("athan_offline_prefs", Context.MODE_PRIVATE)
        sp.edit().clear().commit()

        AthanPreferences.resetInstanceForTesting()
        prefs = AthanPreferences.getInstance(context)

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        AthanAlarmScheduler.cancelAllAlarms(context)

        // Ensure all notifications are on by default for scheduler tests
        PrayerType.entries.forEach { prefs.togglePrayerNotification(it, true) }
    }

    // 1. Default adjustment for all prayers is "0"
    @Test
    fun defaultAdjustment_forAllPrayers_isZero() {
        val settings = prefs.settingsFlow.value
        for (prayer in PrayerType.entries) {
            val adj = settings.prayerAdjustments[prayer]
            assertEquals("Default adjustment for $prayer must be 0", 0, adj)
        }
    }

    // 2. Positive Fajr adjustment works
    @Test
    fun positiveFajrAdjustment_shiftsFajrForward() {
        val rawTimes = calculateRawTimesForMakkah(2026, 9, 15)
        val adjustedTimes = rawTimes.withAdjustment(PrayerType.FAJR, 2)

        val expectedMillis = rawTimes.fajr + (2 * 60 * 1000L)
        assertEquals("Fajr should be shifted forward by exactly 2 minutes (120,000 ms)", expectedMillis, adjustedTimes.fajr)
    }

    // 3. Negative Fajr adjustment works
    @Test
    fun negativeFajrAdjustment_shiftsFajrBackward() {
        val rawTimes = calculateRawTimesForMakkah(2026, 9, 15)
        val adjustedTimes = rawTimes.withAdjustment(PrayerType.FAJR, -5)

        val expectedMillis = rawTimes.fajr - (5 * 60 * 1000L)
        assertEquals("Fajr should be shifted backward by exactly 5 minutes (300,000 ms)", expectedMillis, adjustedTimes.fajr)
    }

    // 4. Each prayer has an independent adjustment
    @Test
    fun eachPrayer_hasIndependentAdjustment() {
        val adjustments = mapOf(
            PrayerType.FAJR to 2,
            PrayerType.SUNRISE to -1,
            PrayerType.DHUHR to 4,
            PrayerType.ASR to -3,
            PrayerType.MAGHRIB to 5,
            PrayerType.ISHA to -2
        )

        for ((prayer, minutes) in adjustments) {
            val success = prefs.updatePrayerAdjustment(prayer, minutes)
            assertTrue("Updating adjustment for $prayer should succeed", success)
        }

        val settings = prefs.settingsFlow.value
        for ((prayer, minutes) in adjustments) {
            assertEquals("Settings should reflect independent adjustment for $prayer", minutes, settings.prayerAdjustments[prayer])
        }
    }

    // 5. One prayer's adjustment does not affect another prayer
    @Test
    fun adjustingOnePrayer_leavesOtherPrayersUnchanged() {
        val rawTimes = calculateRawTimesForMakkah(2026, 9, 15)
        val adjustedTimes = rawTimes.withAdjustment(PrayerType.FAJR, 10)

        assertEquals("Fajr should be adjusted", rawTimes.fajr + (10 * 60 * 1000L), adjustedTimes.fajr)
        assertEquals("Sunrise must remain unchanged", rawTimes.sunrise, adjustedTimes.sunrise)
        assertEquals("Dhuhr must remain unchanged", rawTimes.dhuhr, adjustedTimes.dhuhr)
        assertEquals("Asr must remain unchanged", rawTimes.asr, adjustedTimes.asr)
        assertEquals("Maghrib must remain unchanged", rawTimes.maghrib, adjustedTimes.maghrib)
        assertEquals("Isha must remain unchanged", rawTimes.isha, adjustedTimes.isha)
    }

    // 6. Adjustment is applied exactly once
    @Test
    fun adjustment_isAppliedExactlyOnce() {
        val rawTimes = calculateRawTimesForMakkah(2026, 9, 15)
        val offset = 7
        val adjustedTimes = rawTimes.withAdjustment(PrayerType.DHUHR, offset)

        val differenceMs = adjustedTimes.dhuhr - rawTimes.dhuhr
        val differenceMinutes = differenceMs / (60 * 1000L)
        assertEquals("Difference between adjusted and raw time must be exactly the offset", offset.toLong(), differenceMinutes)
    }

    // 7. Adjusted time is used by the Home screen/state model
    @Test
    fun adjustedTime_isUsedByViewModelTodayPrayers() {
        val vm = PrayerViewModel(ApplicationProvider.getApplicationContext())
        prefs.updatePrayerAdjustment(PrayerType.MAGHRIB, 3)

        val todayTimes = vm.todayPrayerTimes.value
        val settings = vm.settings.value
        assertEquals(3, settings.prayerAdjustments[PrayerType.MAGHRIB])
        assertTrue("Today's prayer times must have valid Maghrib timestamp", todayTimes.maghrib > 0L)
    }

    // 8. Adjusted time is used by monthly schedule generation
    @Test
    fun adjustedTime_isUsedByMonthlySchedule() {
        val vm = PrayerViewModel(ApplicationProvider.getApplicationContext())
        // Obtain base unadjusted monthly schedule (default adjustment is 0)
        val rawSchedule = vm.getMonthPrayerTimes(2026, 9)
        assertFalse("Monthly schedule should not be empty", rawSchedule.isEmpty())

        val asrOffset = 4
        val updated = vm.updatePrayerAdjustment(PrayerType.ASR, asrOffset)
        assertTrue("updatePrayerAdjustment should return true", updated)

        val adjustedSchedule = vm.getMonthPrayerTimes(2026, 9)
        assertEquals("Schedule length should remain identical", rawSchedule.size, adjustedSchedule.size)

        for (i in rawSchedule.indices) {
            val rawDay = rawSchedule[i]
            val adjDay = adjustedSchedule[i]
            assertEquals("Day ${i + 1} Asr must reflect +$asrOffset min adjustment",
                rawDay.asr + (asrOffset * 60 * 1000L), adjDay.asr)
            assertEquals("Day ${i + 1} Fajr should remain unchanged", rawDay.fajr, adjDay.fajr)
        }
    }

    // 9. Adjusted time is used by alarm scheduling
    @Test
    fun adjustedTime_isUsedByAlarmScheduler() {
        val dhuhrOffset = 5
        prefs.updatePrayerAdjustment(PrayerType.DHUHR, dhuhrOffset)
        AthanAlarmScheduler.scheduleRollingAlarms(context)

        val shadowAm = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        val todayDhuhrAlarm = shadowAm.scheduledAlarms.firstOrNull { alarm ->
            val intent = shadowOf(alarm.operation).savedIntent
            intent?.getStringExtra(AthanAlarmReceiver.EXTRA_PRAYER_TYPE) == PrayerType.DHUHR.name &&
                intent.getBooleanExtra(AthanAlarmReceiver.EXTRA_IS_PRE_REMINDER, false) == false
        }
        assertNotNull("Dhuhr alarm must be scheduled", todayDhuhrAlarm)

        // Determine which dayOffset was scheduled (0 for today, or 1 for tomorrow if today's Dhuhr has passed)
        val requestCode = shadowOf(todayDhuhrAlarm!!.operation).requestCode
        val activeBank = AthanAlarmScheduler.getActiveBankIndex(context)
        val bankBase = if (activeBank == 1) AthanAlarmScheduler.BANK_1_BASE else AthanAlarmScheduler.BANK_0_BASE
        val dayOffset = (requestCode - bankBase) / (PrayerType.entries.size * 2)
        assertEquals(
            "Dhuhr alarm request code must match canonical request code for dayOffset $dayOffset",
            AthanAlarmScheduler.getRequestCode(activeBank, dayOffset, PrayerType.DHUHR, isPreReminder = false),
            requestCode
        )

        val tz = TimeZone.getTimeZone(BundledCities.DEFAULT_CITY.timezoneId)
        val targetCal = Calendar.getInstance(tz).apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
        }
        val basePrayerTimes = calculateRawTimesForMakkah(
            targetCal.get(Calendar.YEAR),
            targetCal.get(Calendar.MONTH) + 1,
            targetCal.get(Calendar.DAY_OF_MONTH)
        )
        val expectedDhuhrMillis = basePrayerTimes.dhuhr + (dhuhrOffset * 60 * 1000L)
        assertEquals(
            "Scheduled Dhuhr trigger timestamp must match adjusted prayer time",
            expectedDhuhrMillis,
            todayDhuhrAlarm.triggerAtTime
        )
    }

    // 10. Pre-prayer reminders are calculated from the adjusted prayer time
    @Test
    fun prePrayerReminders_calculatedFromAdjustedTime() {
        val reminderMinutes = 15
        val ishaOffset = 5
        prefs.updateReminderMinutes(reminderMinutes)
        prefs.updatePrayerAdjustment(PrayerType.ISHA, ishaOffset)
        AthanAlarmScheduler.scheduleRollingAlarms(context)

        val shadowAm = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        val ishaReminderAlarm = shadowAm.scheduledAlarms.firstOrNull { alarm ->
            val intent = shadowOf(alarm.operation).savedIntent
            intent?.getStringExtra(AthanAlarmReceiver.EXTRA_PRAYER_TYPE) == PrayerType.ISHA.name &&
                intent.getBooleanExtra(AthanAlarmReceiver.EXTRA_IS_PRE_REMINDER, false) == true
        }
        assertNotNull("Isha pre-reminder alarm must be scheduled", ishaReminderAlarm)

        val requestCode = shadowOf(ishaReminderAlarm!!.operation).requestCode
        val activeBank = AthanAlarmScheduler.getActiveBankIndex(context)
        val bankBase = if (activeBank == 1) AthanAlarmScheduler.BANK_1_BASE else AthanAlarmScheduler.BANK_0_BASE
        val dayOffset = (requestCode - bankBase) / (PrayerType.entries.size * 2)
        assertEquals(
            "Isha reminder request code must match canonical request code for dayOffset $dayOffset",
            AthanAlarmScheduler.getRequestCode(activeBank, dayOffset, PrayerType.ISHA, isPreReminder = true),
            requestCode
        )

        val tz = TimeZone.getTimeZone(BundledCities.DEFAULT_CITY.timezoneId)
        val targetCal = Calendar.getInstance(tz).apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
        }
        val basePrayerTimes = calculateRawTimesForMakkah(
            targetCal.get(Calendar.YEAR),
            targetCal.get(Calendar.MONTH) + 1,
            targetCal.get(Calendar.DAY_OF_MONTH)
        )
        val expectedIshaMillis = basePrayerTimes.isha + (ishaOffset * 60 * 1000L)
        val expectedReminderMillis = expectedIshaMillis - (reminderMinutes * 60 * 1000L)

        assertEquals(
            "Scheduled reminder trigger timestamp must match (adjusted prayer time - reminder minutes)",
            expectedReminderMillis,
            ishaReminderAlarm.triggerAtTime
        )
    }

    // 11. Settings persist after reload
    @Test
    fun settingsPersist_afterReload() {
        prefs.updatePrayerAdjustment(PrayerType.FAJR, 3)
        prefs.updatePrayerAdjustment(PrayerType.ISHA, -4)

        // Reset singleton to force reload from SharedPreferences
        AthanPreferences.resetInstanceForTesting()
        val reloadedPrefs = AthanPreferences.getInstance(context)
        val reloadedSettings = reloadedPrefs.settingsFlow.value

        assertEquals(3, reloadedSettings.prayerAdjustments[PrayerType.FAJR])
        assertEquals(-4, reloadedSettings.prayerAdjustments[PrayerType.ISHA])
        assertEquals(0, reloadedSettings.prayerAdjustments[PrayerType.DHUHR])
    }

    // 12. Corrupted persisted adjustment safely becomes "0"
    @Test
    fun corruptedPersistedAdjustment_safelyDefaultsToZero() {
        val sp = context.getSharedPreferences("athan_offline_prefs", Context.MODE_PRIVATE)
        // Store an out-of-range value directly
        sp.edit().putInt("adj_FAJR", 999).commit()

        AthanPreferences.resetInstanceForTesting()
        val reloadedPrefs = AthanPreferences.getInstance(context)
        val reloadedSettings = reloadedPrefs.settingsFlow.value

        assertEquals("Corrupted value 999 must safely recover to 0", 0, reloadedSettings.prayerAdjustments[PrayerType.FAJR])
    }

    // 13. Out-of-range adjustment is rejected
    @Test
    fun outOfRangeAdjustment_isRejectedByPersistence() {
        val tooHigh = prefs.updatePrayerAdjustment(PrayerType.FAJR, 31)
        assertFalse("Adjustment > 30 must be rejected", tooHigh)

        val tooLow = prefs.updatePrayerAdjustment(PrayerType.FAJR, -31)
        assertFalse("Adjustment < -30 must be rejected", tooLow)

        assertEquals("Fajr adjustment should remain 0 after rejected attempts", 0, prefs.settingsFlow.value.prayerAdjustments[PrayerType.FAJR])
    }

    // 14. Reset-all returns every adjustment to "0"
    @Test
    fun resetAllPrayerAdjustments_resetsAllToZero_withoutAffectingOtherSettings() {
        prefs.updateTimeFormat(true)
        prefs.updateHijriAdjustment(1)
        prefs.updatePrayerAdjustment(PrayerType.FAJR, 10)
        prefs.updatePrayerAdjustment(PrayerType.DHUHR, -5)
        prefs.updatePrayerAdjustment(PrayerType.ISHA, 3)

        prefs.resetAllPrayerAdjustments()
        val settings = prefs.settingsFlow.value

        for (prayer in PrayerType.entries) {
            assertEquals("Prayer $prayer adjustment should be reset to 0", 0, settings.prayerAdjustments[prayer])
        }

        // Verify other settings were preserved
        assertTrue("24-hour format should be preserved", settings.is24HourFormat)
        assertEquals("Hijri adjustment should be preserved", 1, settings.hijriDayAdjustment)
    }

    // 15. Adjustment across midnight produces the correct calendar date
    @Test
    fun adjustmentAcrossMidnight_producesCorrectCalendarDate() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.SEPTEMBER, 15, 0, 2, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val earlyTimeMillis = cal.timeInMillis // 2026-09-15 00:02:00 UTC

        val rawTimes = PrayerTimes(
            fajr = earlyTimeMillis,
            sunrise = earlyTimeMillis + 3600000,
            dhuhr = earlyTimeMillis + 7200000,
            asr = earlyTimeMillis + 10800000,
            maghrib = earlyTimeMillis + 14400000,
            isha = earlyTimeMillis + 18000000,
            dateString = "2026-09-15"
        )

        // Subtract 5 minutes: 00:02 - 5 min = 23:57 on previous calendar day (2026-09-14)
        val adjusted = rawTimes.withAdjustment(PrayerType.FAJR, -5)

        val expectedMillis = earlyTimeMillis - (5 * 60 * 1000L)
        assertEquals(expectedMillis, adjusted.fajr)

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = tz }
        val formatted = sdf.format(Date(adjusted.fajr))
        assertEquals("2026-09-14 23:57", formatted)
    }

    // 16. DST transition behavior remains correct
    @Test
    fun dstTransition_adjustedTimestampShiftsByExactWallClockMinutes() {
        val tzLondon = TimeZone.getTimeZone("Europe/London")
        // London DST fall-back in 2026 is October 25, 2026
        val cal = Calendar.getInstance(tzLondon).apply {
            set(2026, Calendar.OCTOBER, 25, 1, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val timestamp = cal.timeInMillis

        val rawTimes = PrayerTimes(
            fajr = timestamp,
            sunrise = timestamp + 3600000,
            dhuhr = timestamp + 7200000,
            asr = timestamp + 10800000,
            maghrib = timestamp + 14400000,
            isha = timestamp + 18000000,
            dateString = "2026-10-25"
        )

        val adjusted = rawTimes.withAdjustment(PrayerType.FAJR, 15)
        assertEquals("Timestamp must shift by exact 15 minutes", timestamp + (15 * 60 * 1000L), adjusted.fajr)
    }

    // 17. Changing an adjustment triggers the existing idempotent alarm rebuild
    @Test
    fun changingAdjustment_triggersIdempotentAlarmRebuild() {
        AthanAlarmScheduler.scheduleRollingAlarms(context)
        val countBefore = AthanAlarmScheduler.getScheduledRequestCodes(context).size
        assertTrue("Alarms should be scheduled initially", countBefore > 0)

        // Change adjustment
        prefs.updatePrayerAdjustment(PrayerType.FAJR, 5)

        val countAfter = AthanAlarmScheduler.getScheduledRequestCodes(context).size
        assertEquals("Total scheduled alarm count should remain identical across rebuilds", countBefore, countAfter)
    }

    // 18. Repeated rebuilds do not create duplicate alarms
    @Test
    fun repeatedRebuilds_doNotCreateDuplicateAlarms() {
        for (i in 1..3) {
            AthanAlarmScheduler.scheduleRollingAlarms(context)
        }
        val scheduledCodes = AthanAlarmScheduler.getScheduledRequestCodes(context)
        val shadowAm = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        val actualAlarms = shadowAm.scheduledAlarms

        assertEquals("Registered codes and shadow alarms count should match exactly without duplication",
            scheduledCodes.size, actualAlarms.size)

        val actualCodes = actualAlarms.map { shadowOf(it.operation).requestCode }.toSet()
        assertEquals("Registered codes and shadow alarm request codes must match exactly",
            scheduledCodes, actualCodes)
    }

    // 19. Disabled prayer remains unscheduled
    @Test
    fun disabledPrayer_remainsUnscheduledEvenWithAdjustment() {
        prefs.togglePrayerNotification(PrayerType.SUNRISE, false)
        prefs.updatePrayerAdjustment(PrayerType.SUNRISE, 10)
        AthanAlarmScheduler.scheduleRollingAlarms(context)

        // Sunrise should not be in the scheduled alarms
        val scheduledCodes = AthanAlarmScheduler.getScheduledRequestCodes(context)

        val activeBank = AthanAlarmScheduler.getActiveBankIndex(context)

        // B. Canonical request codes across all rolling days for both banks
        for (bank in 0..1) {
            for (dayOffset in 0 until AthanAlarmScheduler.ROLLING_DAYS) {
                val exactCode = AthanAlarmScheduler.getRequestCode(bank, dayOffset, PrayerType.SUNRISE, isPreReminder = false)
                val reminderCode = AthanAlarmScheduler.getRequestCode(bank, dayOffset, PrayerType.SUNRISE, isPreReminder = true)
                assertFalse("Sunrise exact alarm code $exactCode in bank $bank must not be in scheduled codes", scheduledCodes.contains(exactCode))
                assertFalse("Sunrise reminder alarm code $reminderCode in bank $bank must not be in scheduled codes", scheduledCodes.contains(reminderCode))
            }
        }

        // C. Also inspect the actual alarms registered in ShadowAlarmManager
        val shadowAm = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        val sunriseAlarms = shadowAm.scheduledAlarms.filter { alarm ->
            val intent = shadowOf(alarm.operation).savedIntent
            intent?.getStringExtra(AthanAlarmReceiver.EXTRA_PRAYER_TYPE) == PrayerType.SUNRISE.name
        }
        assertEquals("AlarmManager must contain 0 alarms for disabled Sunrise", 0, sunriseAlarms.size)

        // D. Positive control: Verify enabled prayer (such as PrayerType.DHUHR) DOES have its corresponding canonical request codes present
        val dhuhrAlarms = shadowAm.scheduledAlarms.filter { alarm ->
            val intent = shadowOf(alarm.operation).savedIntent
            intent?.getStringExtra(AthanAlarmReceiver.EXTRA_PRAYER_TYPE) == PrayerType.DHUHR.name
        }
        assertTrue("AlarmManager must contain scheduled alarms for enabled Dhuhr", dhuhrAlarms.isNotEmpty())
        val tomorrowDhuhrCode = AthanAlarmScheduler.getRequestCode(activeBank, 1, PrayerType.DHUHR, isPreReminder = false)
        assertTrue("Enabled prayer (Dhuhr tomorrow) canonical request code must be present in scheduledCodes", scheduledCodes.contains(tomorrowDhuhrCode))
    }

    // 20. Exact-alarm permission denial still follows the existing permission-recovery behavior
    @Test
    fun exactAlarmPermissionDenial_followsExistingRecoveryBehavior() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val success = AthanAlarmScheduler.scheduleRollingAlarms(context)

        assertFalse("scheduleRollingAlarms must return false when permission is revoked", success)
        val scheduledCodes = AthanAlarmScheduler.getScheduledRequestCodes(context)
        assertTrue("All alarms must be cancelled when exact alarm permission is unavailable", scheduledCodes.isEmpty())
        val shadowAm = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        assertTrue("AlarmManager alarms must be completely empty when permission is revoked", shadowAm.scheduledAlarms.isEmpty())
    }

    private fun calculateRawTimesForMakkah(year: Int, month: Int, day: Int): PrayerTimes {
        val defaultCity = BundledCities.DEFAULT_CITY
        val tz = TimeZone.getTimeZone(defaultCity.timezoneId)
        return AstronomicalEngine.calculate(
            year = year,
            month = month,
            day = day,
            latitude = defaultCity.latitude,
            longitude = defaultCity.longitude,
            elevationMeters = 0.0,
            method = defaultCity.recommendedMethod,
            madhab = defaultCity.recommendedMadhab,
            highLatitudeRule = HighLatitudeRule.AngleBased,
            timeZone = tz
        )
    }
}
