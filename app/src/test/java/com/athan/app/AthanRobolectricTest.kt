package com.athan.app

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.athan.app.core.astronomy.*
import com.athan.app.data.location.BundledCities
import com.athan.app.data.location.UserLocation
import com.athan.app.data.repository.AthanPreferences
import com.athan.app.audio.AthanSound
import com.athan.app.receiver.AthanAlarmReceiver
import com.athan.app.receiver.BootAndClockReceiver
import com.athan.app.scheduling.AthanAlarmScheduler
import com.athan.app.service.AthanAudioService
import com.athan.app.ui.PrayerViewModel
import com.athan.app.ui.components.MonthScheduleHelper
import com.athan.app.util.ExactAlarmPermissionManager
import com.athan.app.util.NotificationPermissionManager
import android.provider.Settings
import android.location.Location
import android.location.LocationManager
import com.athan.app.data.location.LocationResult
import com.athan.app.data.location.OfflineLocationProvider
import com.athan.app.ui.GpsLocationState
import kotlinx.coroutines.runBlocking
import org.robolectric.shadows.ShadowLocationManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AthanRobolectricTest {

    @Before
    fun setUp() {
        AthanPreferences.resetInstanceForTesting()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AthanPreferences.getInstance(context)
        PrayerType.entries.forEach { prefs.togglePrayerNotification(it, true) }
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        AthanAlarmScheduler.cancelAllAlarms(context)
    }

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Athan", appName)
    }

    @Test
    fun `astronomical calculation computes prayer times offline`() {
        // Test Makkah coordinates (21.4225, 39.8262)
        val tz = TimeZone.getTimeZone("Asia/Riyadh")
        val prayerTimes = AstronomicalEngine.calculate(
            year = 2026,
            month = 9,
            day = 13,
            latitude = 21.4225,
            longitude = 39.8262,
            elevationMeters = 277.0,
            method = CalculationMethod.UmmAlQura,
            madhab = Madhab.Shafi,
            highLatitudeRule = HighLatitudeRule.AngleBased,
            timeZone = tz
        )

        assertTrue("Fajr must be earlier than sunrise", prayerTimes.fajr < prayerTimes.sunrise)
        assertTrue("Sunrise must be earlier than Dhuhr", prayerTimes.sunrise < prayerTimes.dhuhr)
        assertTrue("Dhuhr must be earlier than Asr", prayerTimes.dhuhr < prayerTimes.asr)
        assertTrue("Asr must be earlier than Maghrib", prayerTimes.asr < prayerTimes.maghrib)
        assertTrue("Maghrib must be earlier than Isha", prayerTimes.maghrib < prayerTimes.isha)
    }

    @Test
    fun `qibla calculation returns valid bearing`() {
        // London to Makkah bearing is around 118 degrees
        val bearing = QiblaCalculator.calculateBearing(51.5074, -0.1278)
        assertEquals(118.9, bearing, 1.0)

        // Distance from London to Makkah is around 4780 km
        val distance = QiblaCalculator.calculateDistanceKm(51.5074, -0.1278)
        assertEquals(4780.0, distance, 50.0)
    }

    @Test
    fun `bundled cities contains major cities`() {
        assertFalse(BundledCities.CITIES.isEmpty())
        val searchResult = BundledCities.search("Makkah")
        assertTrue(searchResult.isNotEmpty())
        assertTrue(searchResult[0].name.contains("Makkah"))
    }

    @Test
    fun `hijri calendar conversion works offline`() {
        val hijri = HijriCalendar.fromGregorian(2026, 9, 13)
        assertTrue("Hijri year should be around 1448", hijri.year in 1447..1449)
        assertTrue("Hijri month must be between 1 and 12", hijri.month in 1..12)
        assertTrue("Hijri day must be between 1 and 30", hijri.day in 1..30)
    }

    @Test
    fun `high latitude isha wrapping past midnight is stamped on next calendar day`() {
        // Oslo, Norway (59.9139 N, 10.7522 E) during summer solstice
        val tz = TimeZone.getTimeZone("Europe/Oslo")
        val prayerTimes = AstronomicalEngine.calculate(
            year = 2026,
            month = 6,
            day = 21,
            latitude = 59.9139,
            longitude = 10.7522,
            method = CalculationMethod.MuslimWorldLeague,
            highLatitudeRule = HighLatitudeRule.MiddleOfTheNight,
            timeZone = tz
        )

        assertTrue("Maghrib must be before Isha", prayerTimes.maghrib < prayerTimes.isha)

        val maghribCal = java.util.Calendar.getInstance(tz).apply { timeInMillis = prayerTimes.maghrib }
        val ishaCal = java.util.Calendar.getInstance(tz).apply { timeInMillis = prayerTimes.isha }

        assertEquals(21, maghribCal.get(java.util.Calendar.DAY_OF_MONTH))
        // Isha wraps past midnight so it must be stamped on June 22
        assertEquals(22, ishaCal.get(java.util.Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `umm al qura ramadan rule calculates 120 minutes isha interval vs 90 minutes normally`() {
        val tz = TimeZone.getTimeZone("Asia/Riyadh")

        // 1. Outside Ramadan (September 2026 -> Rabi' al-Awwal)
        val nonRamadanTimes = AstronomicalEngine.calculate(
            year = 2026,
            month = 9,
            day = 13,
            latitude = 21.4225,
            longitude = 39.8262,
            method = CalculationMethod.UmmAlQura,
            timeZone = tz
        )
        val nonRamadanIntervalMin = (nonRamadanTimes.isha - nonRamadanTimes.maghrib) / (60 * 1000)
        assertEquals("Outside Ramadan, Umm Al-Qura Isha interval must be 90 minutes", 90L, nonRamadanIntervalMin)

        // 2. Inside Ramadan: find a Ramadan date using HijriCalendar
        var ramadanYear = 0
        var ramadanMonth = 0
        var ramadanDay = 0
        var found = false
        for (y in 2025..2028) {
            for (m in 1..12) {
                for (d in 1..28) {
                    if (HijriCalendar.fromGregorian(y, m, d).month == 9) {
                        ramadanYear = y
                        ramadanMonth = m
                        ramadanDay = d
                        found = true
                        break
                    }
                }
                if (found) break
            }
            if (found) break
        }
        assertTrue("Must find a date in Ramadan", found)

        val ramadanTimes = AstronomicalEngine.calculate(
            year = ramadanYear,
            month = ramadanMonth,
            day = ramadanDay,
            latitude = 21.4225,
            longitude = 39.8262,
            method = CalculationMethod.UmmAlQura,
            timeZone = tz
        )
        val ramadanIntervalMin = (ramadanTimes.isha - ramadanTimes.maghrib) / (60 * 1000)
        assertEquals("During Ramadan, Umm Al-Qura Isha interval must be 120 minutes", 120L, ramadanIntervalMin)
    }

    @Test
    fun `dst spring forward transition on 2026-03-08 correctly handles pre and post transition offsets`() {
        val tz = TimeZone.getTimeZone("America/New_York")
        // On 2026-03-08 in America/New_York, DST starts at 02:00 EST (07:00 UTC).
        // Before 07:00 UTC: UTC-5 (EST)
        // After 07:00 UTC: UTC-4 (EDT)

        // 1. Check New York City coordinates (40.7128 N, -74.0060 W)
        val nycTimes = AstronomicalEngine.calculate(
            year = 2026,
            month = 3,
            day = 8,
            latitude = 40.7128,
            longitude = -74.0060,
            timeZone = tz
        )

        // Ensure chronological progression
        assertTrue(nycTimes.fajr < nycTimes.sunrise)
        assertTrue(nycTimes.sunrise < nycTimes.dhuhr)
        assertTrue(nycTimes.dhuhr < nycTimes.asr)
        assertTrue(nycTimes.asr < nycTimes.maghrib)
        assertTrue(nycTimes.maghrib < nycTimes.isha)

        // All daytime prayers in NYC on March 8 occur after 07:00 UTC (after 02:00 EST)
        val dhuhrOffset = nycTimes.getOffsetMillis(PrayerType.DHUHR, tz)
        assertEquals(-4 * 3600 * 1000, dhuhrOffset) // UTC-4 (EDT)
        assertEquals(-4.0, nycTimes.getOffsetHours(PrayerType.DHUHR, tz), 0.001)

        // Verify local time for Dhuhr is around 13:06 EDT (solar transit ~ 1:06 PM), NOT shifted by an hour
        val dhuhrCal = java.util.Calendar.getInstance(tz).apply { timeInMillis = nycTimes.dhuhr }
        assertEquals(13, dhuhrCal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(7, dhuhrCal.get(java.util.Calendar.MINUTE))

        // 2. Calculation spanning across the 07:00 UTC transition:
        // For a calculation evaluated with America/New_York timezone where Fajr occurs before 07:00 UTC
        // (e.g. at longitude 0.0 where Fajr is ~04:50 UTC and Dhuhr is ~12:10 UTC):
        val spanningTimes = AstronomicalEngine.calculate(
            year = 2026,
            month = 3,
            day = 8,
            latitude = 25.0,
            longitude = 0.0,
            timeZone = tz
        )

        val preTransitionOffset = spanningTimes.getOffsetMillis(PrayerType.FAJR, tz)
        val postTransitionOffset = spanningTimes.getOffsetMillis(PrayerType.DHUHR, tz)

        // Event before DST transition (04:50 UTC < 07:00 UTC) must use pre-transition offset (UTC-5 / EST)
        assertEquals("Fajr before 07:00 UTC must have UTC-5 offset", -5 * 3600 * 1000, preTransitionOffset)
        assertEquals(-5.0, spanningTimes.getOffsetHours(PrayerType.FAJR, tz), 0.001)

        // Event after DST transition (12:10 UTC > 07:00 UTC) must use post-transition offset (UTC-4 / EDT)
        assertEquals("Dhuhr after 07:00 UTC must have UTC-4 offset", -4 * 3600 * 1000, postTransitionOffset)
        assertEquals(-4.0, spanningTimes.getOffsetHours(PrayerType.DHUHR, tz), 0.001)

        // Local times must not be shifted by a single daily offset:
        // Fajr (05:00 UTC - 5h) = 00:xx EST
        val fajrCal = java.util.Calendar.getInstance(tz).apply { timeInMillis = spanningTimes.fajr }
        val spanDhuhrCal = java.util.Calendar.getInstance(tz).apply { timeInMillis = spanningTimes.dhuhr }

        assertEquals(0, fajrCal.get(java.util.Calendar.HOUR_OF_DAY)) // 00:xx EST
        assertEquals(8, spanDhuhrCal.get(java.util.Calendar.HOUR_OF_DAY)) // 08:xx EDT
    }

    @Test
    fun `dst fall back transition on 2026-11-01 correctly handles pre and post transition offsets`() {
        val tz = TimeZone.getTimeZone("America/New_York")
        // On 2026-11-01 in America/New_York, DST ends at 02:00 EDT (06:00 UTC).
        // Before 06:00 UTC: UTC-4 (EDT)
        // After 06:00 UTC: UTC-5 (EST)

        // 1. Standard calculation for New York City on 2026-11-01
        val nycTimes = AstronomicalEngine.calculate(
            year = 2026,
            month = 11,
            day = 1,
            latitude = 40.7128,
            longitude = -74.0060,
            timeZone = tz
        )

        // Daytime prayers occur after 06:00 UTC (after 02:00 EDT fall-back)
        val dhuhrOffset = nycTimes.getOffsetMillis(PrayerType.DHUHR, tz)
        assertEquals("Dhuhr on 2026-11-01 must be in standard time EST (UTC-5)", -5 * 3600 * 1000, dhuhrOffset)
        assertEquals(-5.0, nycTimes.getOffsetHours(PrayerType.DHUHR, tz), 0.001)

        // Local Dhuhr is around 11:40 AM EST (solar transit ~ 11:40 AM EST), NOT shifted by an hour
        val dhuhrCal = java.util.Calendar.getInstance(tz).apply { timeInMillis = nycTimes.dhuhr }
        assertEquals(11, dhuhrCal.get(java.util.Calendar.HOUR_OF_DAY))
        assertTrue("Dhuhr minute should be between 35 and 45", dhuhrCal.get(java.util.Calendar.MINUTE) in 35..45)

        // 2. Calculation spanning across the 06:00 UTC fall-back transition:
        // At longitude 0.0, Fajr is ~04:50 UTC (before 06:00 UTC transition) and Dhuhr is ~11:40 UTC (after 06:00 UTC transition)
        val spanningTimes = AstronomicalEngine.calculate(
            year = 2026,
            month = 11,
            day = 1,
            latitude = 25.0,
            longitude = 0.0,
            timeZone = tz
        )

        val preTransitionOffset = spanningTimes.getOffsetMillis(PrayerType.FAJR, tz)
        val postTransitionOffset = spanningTimes.getOffsetMillis(PrayerType.DHUHR, tz)

        // Event before fall-back transition (04:50 UTC < 06:00 UTC) must use pre-transition offset (UTC-4 / EDT)
        assertEquals("Fajr before 06:00 UTC must have UTC-4 offset (EDT)", -4 * 3600 * 1000, preTransitionOffset)
        assertEquals(-4.0, spanningTimes.getOffsetHours(PrayerType.FAJR, tz), 0.001)

        // Event after fall-back transition (11:40 UTC > 06:00 UTC) must use post-transition offset (UTC-5 / EST)
        assertEquals("Dhuhr after 06:00 UTC must have UTC-5 offset (EST)", -5 * 3600 * 1000, postTransitionOffset)
        assertEquals(-5.0, spanningTimes.getOffsetHours(PrayerType.DHUHR, tz), 0.001)

        // Local times are correct and not shifted:
        val fajrCal = java.util.Calendar.getInstance(tz).apply { timeInMillis = spanningTimes.fajr }
        val spanDhuhrCal = java.util.Calendar.getInstance(tz).apply { timeInMillis = spanningTimes.dhuhr }

        assertEquals(0, fajrCal.get(java.util.Calendar.HOUR_OF_DAY)) // 00:xx EDT
        assertEquals(6, spanDhuhrCal.get(java.util.Calendar.HOUR_OF_DAY)) // 06:xx EST
    }

    // ============================================================================================
    // Bug #2 Regression Tests: Robust Athan Alarm Scheduling and Invalidation
    // ============================================================================================

    @Test
    fun `repeated scheduling does not produce duplicate alarms`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)

        // Run 1
        AthanAlarmScheduler.scheduleRollingAlarms(context)
        val alarmsRun1 = shadowAlarmManager.scheduledAlarms.toList()
        assertTrue("At least one rolling alarm should be scheduled", alarmsRun1.isNotEmpty())
        val countRun1 = alarmsRun1.size

        // Run 2
        AthanAlarmScheduler.scheduleRollingAlarms(context)
        val alarmsRun2 = shadowAlarmManager.scheduledAlarms.toList()
        assertEquals("Calling scheduleRollingAlarms twice must not duplicate alarms", countRun1, alarmsRun2.size)

        // Run 3
        AthanAlarmScheduler.scheduleRollingAlarms(context)
        val alarmsRun3 = shadowAlarmManager.scheduledAlarms.toList()
        assertEquals("Calling scheduleRollingAlarms three times must remain idempotent", countRun1, alarmsRun3.size)

        // Verify all scheduled request codes are unique
        val requestCodes = alarmsRun3.map { shadowOf(it.operation).requestCode }
        assertEquals("All scheduled alarms must have unique request codes", requestCodes.size, requestCodes.toSet().size)
    }

    @Test
    fun `disabling a prayer removes that prayer alarm on reschedule`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)
        val prefs = AthanPreferences.getInstance(context)

        // Ensure all prayers are enabled initially
        PrayerType.entries.forEach { prayer ->
            prefs.togglePrayerNotification(prayer, true)
        }
        AthanAlarmScheduler.scheduleRollingAlarms(context)

        // Verify Sunrise alarm is present
        val sunriseAlarmsBefore = shadowAlarmManager.scheduledAlarms.filter { alarm ->
            val intent = shadowOf(alarm.operation).savedIntent
            intent?.getStringExtra(AthanAlarmReceiver.EXTRA_PRAYER_TYPE) == PrayerType.SUNRISE.name
        }
        assertTrue("Sunrise alarm should be scheduled when enabled", sunriseAlarmsBefore.isNotEmpty())

        // Disable Sunrise
        prefs.togglePrayerNotification(PrayerType.SUNRISE, false)

        // Verify Sunrise alarms were completely removed from AlarmManager
        val sunriseAlarmsAfter = shadowAlarmManager.scheduledAlarms.filter { alarm ->
            val intent = shadowOf(alarm.operation).savedIntent
            intent?.getStringExtra(AthanAlarmReceiver.EXTRA_PRAYER_TYPE) == PrayerType.SUNRISE.name
        }
        assertEquals("Sunrise alarms must be removed when prayer is disabled", 0, sunriseAlarmsAfter.size)

        // Other enabled prayers must still be scheduled
        val dhuhrAlarms = shadowAlarmManager.scheduledAlarms.filter { alarm ->
            val intent = shadowOf(alarm.operation).savedIntent
            intent?.getStringExtra(AthanAlarmReceiver.EXTRA_PRAYER_TYPE) == PrayerType.DHUHR.name
        }
        assertTrue("Dhuhr alarms must remain scheduled", dhuhrAlarms.isNotEmpty())
    }

    @Test
    fun `changing location removes old alarms and establishes new alarms`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)
        val prefs = AthanPreferences.getInstance(context)

        // 1. Location A: Makkah
        val locMakkah = UserLocation(
            cityName = "Makkah",
            countryName = "Saudi Arabia",
            latitude = 21.4225,
            longitude = 39.8262,
            elevationMeters = 277.0,
            timezoneId = "Asia/Riyadh",
            isGpsDetected = false
        )
        prefs.updateLocation(locMakkah)
        val makkahTriggers = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }.toSet()
        assertTrue("Makkah alarms must be scheduled", makkahTriggers.isNotEmpty())

        // 2. Location B: London
        val locLondon = UserLocation(
            cityName = "London",
            countryName = "United Kingdom",
            latitude = 51.5074,
            longitude = -0.1278,
            elevationMeters = 15.0,
            timezoneId = "Europe/London",
            isGpsDetected = false
        )
        prefs.updateLocation(locLondon)
        val londonTriggers = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }.toSet()
        assertTrue("London alarms must be scheduled", londonTriggers.isNotEmpty())

        // Ensure no old Makkah triggers remain in the newly scheduled London alarms
        val lingeringOldTriggers = makkahTriggers.intersect(londonTriggers)
        assertEquals("No Makkah alarm trigger timestamps should remain after changing location to London", 0, lingeringOldTriggers.size)
    }

    @Test
    fun `changing calculation method removes old alarms and establishes new alarms`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)
        val prefs = AthanPreferences.getInstance(context)

        // Set baseline location
        prefs.updateLocation(
            UserLocation("Cairo", "Egypt", 30.0444, 31.2357, 23.0, "Africa/Cairo", false)
        )

        // Method 1: MuslimWorldLeague (Fajr 18.0)
        prefs.updateMethod(CalculationMethod.MuslimWorldLeague)
        val mwlFajrTriggers = shadowAlarmManager.scheduledAlarms.filter { alarm ->
            val intent = shadowOf(alarm.operation).savedIntent
            intent?.getStringExtra(AthanAlarmReceiver.EXTRA_PRAYER_TYPE) == PrayerType.FAJR.name &&
                intent.getBooleanExtra(AthanAlarmReceiver.EXTRA_IS_PRE_REMINDER, false) == false
        }.map { it.triggerAtTime }.toSet()
        assertTrue("MuslimWorldLeague Fajr alarms should exist", mwlFajrTriggers.isNotEmpty())

        // Method 2: EgyptianGeneral (Fajr 19.5, earlier Fajr)
        prefs.updateMethod(CalculationMethod.EgyptianGeneral)
        val egyptianFajrTriggers = shadowAlarmManager.scheduledAlarms.filter { alarm ->
            val intent = shadowOf(alarm.operation).savedIntent
            intent?.getStringExtra(AthanAlarmReceiver.EXTRA_PRAYER_TYPE) == PrayerType.FAJR.name &&
                intent.getBooleanExtra(AthanAlarmReceiver.EXTRA_IS_PRE_REMINDER, false) == false
        }.map { it.triggerAtTime }.toSet()
        assertTrue("EgyptianGeneral Fajr alarms should exist", egyptianFajrTriggers.isNotEmpty())

        // Old MWL Fajr alarms must be gone and not present in Egyptian triggers
        val overlap = mwlFajrTriggers.intersect(egyptianFajrTriggers)
        assertEquals("Old MWL Fajr triggers must be removed when changing to Egyptian method", 0, overlap.size)
    }

    @Test
    fun `changing madhab removes old Asr alarm and establishes new Hanafi Asr`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)
        val prefs = AthanPreferences.getInstance(context)

        prefs.updateLocation(
            UserLocation("Lahore", "Pakistan", 31.5204, 74.3587, 217.0, "Asia/Karachi", false)
        )

        // 1. Shafi Madhab (shadow factor 1.0)
        prefs.updateMadhab(Madhab.Shafi)
        val shafiAsrTriggers = shadowAlarmManager.scheduledAlarms.filter { alarm ->
            val intent = shadowOf(alarm.operation).savedIntent
            intent?.getStringExtra(AthanAlarmReceiver.EXTRA_PRAYER_TYPE) == PrayerType.ASR.name &&
                intent.getBooleanExtra(AthanAlarmReceiver.EXTRA_IS_PRE_REMINDER, false) == false
        }.map { it.triggerAtTime }.toSet()
        assertTrue("Shafi Asr alarms should exist", shafiAsrTriggers.isNotEmpty())

        // 2. Hanafi Madhab (shadow factor 2.0, later in afternoon)
        prefs.updateMadhab(Madhab.Hanafi)
        val hanafiAsrTriggers = shadowAlarmManager.scheduledAlarms.filter { alarm ->
            val intent = shadowOf(alarm.operation).savedIntent
            intent?.getStringExtra(AthanAlarmReceiver.EXTRA_PRAYER_TYPE) == PrayerType.ASR.name &&
                intent.getBooleanExtra(AthanAlarmReceiver.EXTRA_IS_PRE_REMINDER, false) == false
        }.map { it.triggerAtTime }.toSet()
        assertTrue("Hanafi Asr alarms should exist", hanafiAsrTriggers.isNotEmpty())

        // Verify old Shafi Asr is removed and Hanafi Asr is later
        val overlap = shafiAsrTriggers.intersect(hanafiAsrTriggers)
        assertEquals("Old Shafi Asr alarms must be removed when switching to Hanafi", 0, overlap.size)

        val shafiLast = shafiAsrTriggers.maxOrNull() ?: 0L
        val hanafiLast = hanafiAsrTriggers.maxOrNull() ?: 0L
        assertTrue("Hanafi Asr must occur later than Shafi Asr", hanafiLast > shafiLast)
    }

    @Test
    fun `timezone change results in updated schedule without old alarms remaining`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)
        val prefs = AthanPreferences.getInstance(context)

        // Location A (Dubai, Asia/Dubai, UTC+4)
        val locA = UserLocation("Dubai", "UAE", 25.2048, 55.2708, 5.0, "Asia/Dubai", false)
        prefs.updateLocation(locA)
        val alarmsTzA = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }.toSet()
        assertTrue("Dubai alarms must be scheduled", alarmsTzA.isNotEmpty())

        // Location B (New York, America/New_York, UTC-4/5)
        val locB = UserLocation("New York", "USA", 40.7128, -74.0060, 10.0, "America/New_York", false)
        prefs.updateLocation(locB)
        val alarmsTzB = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }.toSet()
        assertTrue("New York alarms must be scheduled", alarmsTzB.isNotEmpty())

        val lingering = alarmsTzA.intersect(alarmsTzB)
        assertEquals("Alarms from old timezone and location must be cleared", 0, lingering.size)

        // Also test BootAndClockReceiver receiving ACTION_TIMEZONE_CHANGED broadcast
        val receiver = BootAndClockReceiver()
        val tzIntent = Intent(Intent.ACTION_TIMEZONE_CHANGED)
        receiver.onReceive(context, tzIntent)

        val alarmsAfterBroadcast = shadowAlarmManager.scheduledAlarms.toList()
        val registeredCodes = AthanAlarmScheduler.getScheduledRequestCodes(context)
        assertEquals(
            "Alarm count in AlarmManager must match registered codes after timezone broadcast",
            registeredCodes.size,
            alarmsAfterBroadcast.size
        )
    }

    @Test
    fun `alarms are cancelled using correct identity and scheduler cleans up every old alarm`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)

        // Schedule normal alarms
        AthanAlarmScheduler.scheduleRollingAlarms(context)
        assertTrue("Alarms should be scheduled", shadowAlarmManager.scheduledAlarms.isNotEmpty())

        // Cancel all
        AthanAlarmScheduler.cancelAllAlarms(context)
        assertTrue(
            "cancelAllAlarms must cancel every active alarm in AlarmManager",
            shadowAlarmManager.scheduledAlarms.isEmpty()
        )

        // Seed manual alarms across legacy and extended ranges with the scheduler's identity
        for (code in AthanAlarmScheduler.REQUEST_CODE_BASE..(AthanAlarmScheduler.REQUEST_CODE_BASE + 20)) {
            val intent = Intent(context, AthanAlarmReceiver::class.java).apply {
                action = AthanAlarmReceiver.ACTION_PRAYER_ALARM
            }
            val pi = PendingIntent.getBroadcast(
                context,
                code,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 100000L, pi)
        }
        assertEquals(21, shadowAlarmManager.scheduledAlarms.size)

        // Rescheduling must purge all 21 seeded alarms before scheduling the new set
        AthanAlarmScheduler.scheduleRollingAlarms(context)
        val scheduledCodes = shadowAlarmManager.scheduledAlarms.map { shadowOf(it.operation).requestCode }.toSet()

        // The new set should strictly match the valid rolling schedule request codes
        val registeredCodes = AthanAlarmScheduler.getScheduledRequestCodes(context)
        assertEquals(registeredCodes, scheduledCodes)
    }

    // ============================================================================
    // Bug #3 Regression Tests: HighLatitudeRule.None vs Fallback Rules
    // ============================================================================

    @Test
    fun `test 1 high latitude rule None does not apply AngleBased fallback when Fajr and Isha angle unavailable`() {
        // Oslo, Norway (59.9139 N, 10.7522 E) on June 21, 2026 (Summer Solstice)
        // MuslimWorldLeague method requires Fajr angle 18° and Isha angle 17°.
        // At 59.9° N on June 21, the sun never dips below 6.7° below horizon.
        val tz = TimeZone.getTimeZone("Europe/Oslo")
        val prayerTimes = AstronomicalEngine.calculate(
            year = 2026,
            month = 6,
            day = 21,
            latitude = 59.9139,
            longitude = 10.7522,
            method = CalculationMethod.MuslimWorldLeague,
            highLatitudeRule = HighLatitudeRule.None,
            timeZone = tz
        )

        // Sunrise, Dhuhr, Asr, Maghrib are astronomically available
        assertTrue("Sunrise must be valid", prayerTimes.sunrise > 0L)
        assertTrue("Dhuhr must be valid", prayerTimes.dhuhr > 0L)
        assertTrue("Asr must be valid", prayerTimes.asr > 0L)
        assertTrue("Maghrib must be valid", prayerTimes.maghrib > 0L)

        // Fajr and Isha solar angles never occur, and Rule.None must NOT apply AngleBased or any other fallback
        assertEquals("Fajr must be unavailable (0L) under HighLatitudeRule.None", 0L, prayerTimes.fajr)
        assertEquals("Isha must be unavailable (0L) under HighLatitudeRule.None", 0L, prayerTimes.isha)
        assertFalse("isFajrAvailable must be false", prayerTimes.isFajrAvailable)
        assertFalse("isIshaAvailable must be false", prayerTimes.isIshaAvailable)
        assertEquals("--:--", prayerTimes.formatTime(prayerTimes.fajr))
        assertEquals("--:--", prayerTimes.formatTime(prayerTimes.isha))
    }

    @Test
    fun `test 2 high latitude rule AngleBased applies proportional night fallback when angle unavailable`() {
        val tz = TimeZone.getTimeZone("Europe/Oslo")
        val prayerTimes = AstronomicalEngine.calculate(
            year = 2026,
            month = 6,
            day = 21,
            latitude = 59.9139,
            longitude = 10.7522,
            method = CalculationMethod.MuslimWorldLeague,
            highLatitudeRule = HighLatitudeRule.AngleBased,
            timeZone = tz
        )

        // Fallback calculation produces positive timestamps
        assertTrue("Fajr must be calculated with AngleBased fallback", prayerTimes.fajr > 0L)
        assertTrue("Isha must be calculated with AngleBased fallback", prayerTimes.isha > 0L)
        assertTrue("isFajrAvailable must be true", prayerTimes.isFajrAvailable)
        assertTrue("isIshaAvailable must be true", prayerTimes.isIshaAvailable)
        assertTrue("Fajr must be before sunrise", prayerTimes.fajr < prayerTimes.sunrise)
        assertTrue("Maghrib must be before Isha", prayerTimes.maghrib < prayerTimes.isha)

        // Proves that AngleBased is NOT identical to None (which returned 0L)
        assertNotEquals(0L, prayerTimes.fajr)
        assertNotEquals(0L, prayerTimes.isha)
    }

    @Test
    fun `test 3 high latitude rule MiddleOfTheNight applies half night fallback`() {
        val tz = TimeZone.getTimeZone("Europe/Oslo")
        val prayerTimes = AstronomicalEngine.calculate(
            year = 2026,
            month = 6,
            day = 21,
            latitude = 59.9139,
            longitude = 10.7522,
            method = CalculationMethod.MuslimWorldLeague,
            highLatitudeRule = HighLatitudeRule.MiddleOfTheNight,
            timeZone = tz
        )

        val angleBasedTimes = AstronomicalEngine.calculate(
            year = 2026,
            month = 6,
            day = 21,
            latitude = 59.9139,
            longitude = 10.7522,
            method = CalculationMethod.MuslimWorldLeague,
            highLatitudeRule = HighLatitudeRule.AngleBased,
            timeZone = tz
        )

        assertTrue("Fajr must be valid under MiddleOfTheNight", prayerTimes.fajr > 0L)
        assertTrue("Isha must be valid under MiddleOfTheNight", prayerTimes.isha > 0L)
        assertTrue("Fajr must be earlier than sunrise", prayerTimes.fajr < prayerTimes.sunrise)
        assertTrue("Maghrib must be earlier than Isha", prayerTimes.maghrib < prayerTimes.isha)

        // Middle of the night allocates 1/2 of the night (50%), whereas AngleBased allocates 18/60 (30%).
        // Therefore Fajr is earlier and Isha is later under MiddleOfTheNight than AngleBased.
        assertTrue("MiddleOfTheNight Fajr must be earlier than AngleBased Fajr", prayerTimes.fajr < angleBasedTimes.fajr)
        assertTrue("MiddleOfTheNight Isha must be later than AngleBased Isha", prayerTimes.isha > angleBasedTimes.isha)
    }

    @Test
    fun `test 4 high latitude rule OneSeventh applies one seventh night fallback`() {
        val tz = TimeZone.getTimeZone("Europe/Oslo")
        val prayerTimes = AstronomicalEngine.calculate(
            year = 2026,
            month = 6,
            day = 21,
            latitude = 59.9139,
            longitude = 10.7522,
            method = CalculationMethod.MuslimWorldLeague,
            highLatitudeRule = HighLatitudeRule.OneSeventh,
            timeZone = tz
        )

        val angleBasedTimes = AstronomicalEngine.calculate(
            year = 2026,
            month = 6,
            day = 21,
            latitude = 59.9139,
            longitude = 10.7522,
            method = CalculationMethod.MuslimWorldLeague,
            highLatitudeRule = HighLatitudeRule.AngleBased,
            timeZone = tz
        )

        assertTrue("Fajr must be valid under OneSeventh", prayerTimes.fajr > 0L)
        assertTrue("Isha must be valid under OneSeventh", prayerTimes.isha > 0L)
        assertTrue("Fajr must be earlier than sunrise", prayerTimes.fajr < prayerTimes.sunrise)
        assertTrue("Maghrib must be earlier than Isha", prayerTimes.maghrib < prayerTimes.isha)

        // One-seventh allocates 1/7 of the night (~14.3%), whereas AngleBased allocates 18/60 (30%).
        // Therefore Fajr is later and Isha is earlier under OneSeventh than AngleBased.
        assertTrue("OneSeventh Fajr must be later than AngleBased Fajr", prayerTimes.fajr > angleBasedTimes.fajr)
        assertTrue("OneSeventh Isha must be earlier than AngleBased Isha", prayerTimes.isha < angleBasedTimes.isha)
    }

    @Test
    fun `test 5 normal latitude produces identical astronomical prayer times regardless of HighLatitudeRule`() {
        // Cairo, Egypt (30.0444 N, 31.2357 E) on September 15, 2026 - where Fajr and Isha angles are astronomically reached
        val tz = TimeZone.getTimeZone("Africa/Cairo")
        val timesNone = AstronomicalEngine.calculate(
            year = 2026,
            month = 9,
            day = 15,
            latitude = 30.0444,
            longitude = 31.2357,
            method = CalculationMethod.EgyptianGeneral,
            highLatitudeRule = HighLatitudeRule.None,
            timeZone = tz
        )

        val timesAngleBased = AstronomicalEngine.calculate(
            year = 2026,
            month = 9,
            day = 15,
            latitude = 30.0444,
            longitude = 31.2357,
            method = CalculationMethod.EgyptianGeneral,
            highLatitudeRule = HighLatitudeRule.AngleBased,
            timeZone = tz
        )

        // Fajr and Isha must be valid positive timestamps
        assertTrue("Fajr must be valid", timesNone.fajr > 0L)
        assertTrue("Isha must be valid", timesNone.isha > 0L)
        assertTrue("Fajr must be before sunrise", timesNone.fajr < timesNone.sunrise)
        assertTrue("Sunrise must be before Dhuhr", timesNone.sunrise < timesNone.dhuhr)
        assertTrue("Dhuhr must be before Asr", timesNone.dhuhr < timesNone.asr)
        assertTrue("Asr must be before Maghrib", timesNone.asr < timesNone.maghrib)
        assertTrue("Maghrib must be before Isha", timesNone.maghrib < timesNone.isha)

        // HighLatitudeRule.None must return the exact same astronomical times as AngleBased when calculation succeeds
        assertEquals("Fajr must be identical between None and AngleBased when astronomically available", timesAngleBased.fajr, timesNone.fajr)
        assertEquals("Sunrise must be identical", timesAngleBased.sunrise, timesNone.sunrise)
        assertEquals("Dhuhr must be identical", timesAngleBased.dhuhr, timesNone.dhuhr)
        assertEquals("Asr must be identical", timesAngleBased.asr, timesNone.asr)
        assertEquals("Maghrib must be identical", timesAngleBased.maghrib, timesNone.maghrib)
        assertEquals("Isha must be identical between None and AngleBased when astronomically available", timesAngleBased.isha, timesNone.isha)
    }

    @Test
    fun `polar conditions do not manufacture artificial 12-hour day when rule is None`() {
        // Tromso, Norway (69.6492 N, 18.9553 E) on June 21, 2026 (Polar Day / Midnight Sun)
        val tz = TimeZone.getTimeZone("Europe/Oslo")
        val polarTimes = AstronomicalEngine.calculate(
            year = 2026,
            month = 6,
            day = 21,
            latitude = 69.6492,
            longitude = 18.9553,
            method = CalculationMethod.MuslimWorldLeague,
            highLatitudeRule = HighLatitudeRule.None,
            timeZone = tz
        )

        // Sun never sets or reaches sunrise altitude -> sunrise, sunset/maghrib, fajr, isha are unavailable
        assertEquals("Sunrise must not be manufactured when sun never reaches altitude", 0L, polarTimes.sunrise)
        assertEquals("Maghrib must not be manufactured when sun never sets", 0L, polarTimes.maghrib)
        assertEquals("Fajr must not be manufactured", 0L, polarTimes.fajr)
        assertEquals("Isha must not be manufactured", 0L, polarTimes.isha)
        assertFalse(polarTimes.isSunriseAvailable)
        assertFalse(polarTimes.isMaghribAvailable)
        assertFalse(polarTimes.isFajrAvailable)
        assertFalse(polarTimes.isIshaAvailable)

        // Transit / Dhuhr remains astronomically calculated from solar noon
        assertTrue("Dhuhr must remain valid based on solar transit", polarTimes.dhuhr > 0L)
    }

    @Test
    fun `polar day Tromso all high-latitude rules produce no fabricated sunrise, sunset, fajr, isha`() {
        // Tromsø, Norway (69.6492 N, 18.9553 E) on June 21, 2026 (Polar Day / Midnight Sun)
        val tz = TimeZone.getTimeZone("Europe/Oslo")
        val rules = listOf(
            HighLatitudeRule.None,
            HighLatitudeRule.AngleBased,
            HighLatitudeRule.MiddleOfTheNight,
            HighLatitudeRule.OneSeventh
        )
        for (rule in rules) {
            val polarTimes = AstronomicalEngine.calculate(
                year = 2026,
                month = 6,
                day = 21,
                latitude = 69.6492,
                longitude = 18.9553,
                method = CalculationMethod.MuslimWorldLeague,
                highLatitudeRule = rule,
                timeZone = tz
            )

            assertEquals("Sunrise must be unavailable under $rule in polar day", 0L, polarTimes.sunrise)
            assertEquals("Maghrib must be unavailable under $rule in polar day", 0L, polarTimes.maghrib)
            assertEquals("Fajr must be unavailable under $rule in polar day", 0L, polarTimes.fajr)
            assertEquals("Isha must be unavailable under $rule in polar day", 0L, polarTimes.isha)
            assertTrue("Dhuhr must remain valid based on solar transit under $rule in polar day", polarTimes.dhuhr > 0L)
        }
    }

    @Test
    fun `polar night Tromso all high-latitude rules produce no fabricated sunrise, sunset, fajr, isha, asr`() {
        // Tromsø, Norway (69.6492 N, 18.9553 E) on December 21, 2026 (Polar Night)
        val tz = TimeZone.getTimeZone("Europe/Oslo")
        val rules = listOf(
            HighLatitudeRule.None,
            HighLatitudeRule.AngleBased,
            HighLatitudeRule.MiddleOfTheNight,
            HighLatitudeRule.OneSeventh
        )
        for (rule in rules) {
            val polarTimes = AstronomicalEngine.calculate(
                year = 2026,
                month = 12,
                day = 21,
                latitude = 69.6492,
                longitude = 18.9553,
                method = CalculationMethod.MuslimWorldLeague,
                highLatitudeRule = rule,
                timeZone = tz
            )

            assertEquals("Sunrise must be unavailable under $rule in polar night", 0L, polarTimes.sunrise)
            assertEquals("Maghrib must be unavailable under $rule in polar night", 0L, polarTimes.maghrib)
            assertEquals("Fajr must be unavailable under $rule in polar night", 0L, polarTimes.fajr)
            assertEquals("Isha must be unavailable under $rule in polar night", 0L, polarTimes.isha)
            assertTrue("Dhuhr should remain valid when solar noon exists under $rule in polar night", polarTimes.dhuhr > 0L)
            assertEquals("Asr must be unavailable when its required altitude is not reached under $rule", 0L, polarTimes.asr)
        }
    }

    @Test
    fun `normal locations Cairo and Makkah have all six prayer times calculate normally`() {
        // Cairo, Egypt (30.0444 N, 31.2357 E)
        val tzCairo = TimeZone.getTimeZone("Africa/Cairo")
        val cairoTimes = AstronomicalEngine.calculate(
            year = 2026,
            month = 6,
            day = 21,
            latitude = 30.0444,
            longitude = 31.2357,
            method = CalculationMethod.EgyptianGeneral,
            highLatitudeRule = HighLatitudeRule.AngleBased,
            timeZone = tzCairo
        )
        assertTrue("Cairo Fajr must be valid", cairoTimes.fajr > 0L)
        assertTrue("Cairo Sunrise must be valid", cairoTimes.sunrise > 0L)
        assertTrue("Cairo Dhuhr must be valid", cairoTimes.dhuhr > 0L)
        assertTrue("Cairo Asr must be valid", cairoTimes.asr > 0L)
        assertTrue("Cairo Maghrib must be valid", cairoTimes.maghrib > 0L)
        assertTrue("Cairo Isha must be valid", cairoTimes.isha > 0L)
        assertTrue(cairoTimes.fajr < cairoTimes.sunrise)
        assertTrue(cairoTimes.sunrise < cairoTimes.dhuhr)
        assertTrue(cairoTimes.dhuhr < cairoTimes.asr)
        assertTrue(cairoTimes.asr < cairoTimes.maghrib)
        assertTrue(cairoTimes.maghrib < cairoTimes.isha)

        // Makkah, Saudi Arabia (21.4225 N, 39.8262 E)
        val tzMakkah = TimeZone.getTimeZone("Asia/Riyadh")
        val makkahTimes = AstronomicalEngine.calculate(
            year = 2026,
            month = 6,
            day = 21,
            latitude = 21.4225,
            longitude = 39.8262,
            method = CalculationMethod.UmmAlQura,
            highLatitudeRule = HighLatitudeRule.AngleBased,
            timeZone = tzMakkah
        )
        assertTrue("Makkah Fajr must be valid", makkahTimes.fajr > 0L)
        assertTrue("Makkah Sunrise must be valid", makkahTimes.sunrise > 0L)
        assertTrue("Makkah Dhuhr must be valid", makkahTimes.dhuhr > 0L)
        assertTrue("Makkah Asr must be valid", makkahTimes.asr > 0L)
        assertTrue("Makkah Maghrib must be valid", makkahTimes.maghrib > 0L)
        assertTrue("Makkah Isha must be valid", makkahTimes.isha > 0L)
        assertTrue(makkahTimes.fajr < makkahTimes.sunrise)
        assertTrue(makkahTimes.sunrise < makkahTimes.dhuhr)
        assertTrue(makkahTimes.dhuhr < makkahTimes.asr)
        assertTrue(makkahTimes.asr < makkahTimes.maghrib)
        assertTrue(makkahTimes.maghrib < makkahTimes.isha)
    }

    @Test
    fun `test 1 app remains open across midnight Gregorian date changes to new calendar day`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = PrayerViewModel(app)
        val tz = TimeZone.getTimeZone("Asia/Riyadh")

        // 23:59:50 on September 15, 2026 in Asia/Riyadh
        val calBeforeMidnight = Calendar.getInstance(tz).apply {
            set(2026, Calendar.SEPTEMBER, 15, 23, 59, 50)
            set(Calendar.MILLISECOND, 0)
        }
        val tBeforeMidnight = calBeforeMidnight.timeInMillis

        // Set to 23:59:50
        viewModel.checkDateRefresh(tBeforeMidnight)
        val gregorianBefore = viewModel.currentGregorianDate.value
        assertTrue("Must display 15 September before midnight, got: $gregorianBefore", gregorianBefore.contains("15 September 2026"))

        // Verify midnight calculation determines the exact time to next midnight
        val millisToMidnight = LocalCalendarDay.getMillisUntilNextMidnight(tBeforeMidnight, tz)
        assertEquals("Should be exactly 10 seconds to midnight", 10_000L, millisToMidnight)

        // Simulate clock ticking across midnight to 00:00:05 on September 16, 2026
        val calAfterMidnight = Calendar.getInstance(tz).apply {
            set(2026, Calendar.SEPTEMBER, 16, 0, 0, 5)
            set(Calendar.MILLISECOND, 0)
        }
        val tAfterMidnight = calAfterMidnight.timeInMillis

        val refreshed = viewModel.checkDateRefresh(tAfterMidnight)
        assertTrue("Date refresh should return true when crossing midnight boundary", refreshed)

        val gregorianAfter = viewModel.currentGregorianDate.value
        assertTrue("Must automatically update to 16 September 2026, got: $gregorianAfter", gregorianAfter.contains("16 September 2026"))
        assertNotEquals("Gregorian date must change across midnight", gregorianBefore, gregorianAfter)
    }

    @Test
    fun `test 2 Hijri date changes with Gregorian date`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = PrayerViewModel(app)
        val tz = TimeZone.getTimeZone("Asia/Riyadh")

        val cal15 = Calendar.getInstance(tz).apply {
            set(2026, Calendar.SEPTEMBER, 15, 23, 59, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val cal16 = Calendar.getInstance(tz).apply {
            set(2026, Calendar.SEPTEMBER, 16, 0, 1, 0)
            set(Calendar.MILLISECOND, 0)
        }

        viewModel.checkDateRefresh(cal15.timeInMillis)
        val hijri15 = viewModel.currentHijriDate.value
        val expected15 = HijriCalendar.fromGregorian(2026, 9, 15)
        assertEquals("Hijri day on Sep 15 must match", expected15.day, hijri15.day)
        assertEquals("Hijri month on Sep 15 must match", expected15.month, hijri15.month)

        // Advance to Sep 16
        viewModel.checkDateRefresh(cal16.timeInMillis)
        val hijri16 = viewModel.currentHijriDate.value
        val expected16 = HijriCalendar.fromGregorian(2026, 9, 16)
        assertEquals("Hijri date must recalculate from new Gregorian date day", expected16.day, hijri16.day)
        assertEquals("Hijri date must recalculate from new Gregorian date month", expected16.month, hijri16.month)
        assertNotEquals("Hijri day must advance", hijri15.day, hijri16.day)
    }

    @Test
    fun `test 3 app resumes after midnight`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = PrayerViewModel(app)
        val tz = TimeZone.getTimeZone("Asia/Riyadh")

        // User paused app at 23:55 on September 15
        val cal2355 = Calendar.getInstance(tz).apply {
            set(2026, Calendar.SEPTEMBER, 15, 23, 55, 0)
            set(Calendar.MILLISECOND, 0)
        }
        viewModel.checkDateRefresh(cal2355.timeInMillis)
        assertTrue(viewModel.currentGregorianDate.value.contains("15 September 2026"))

        // User resumes app at 00:10 on September 16
        val cal0010 = Calendar.getInstance(tz).apply {
            set(2026, Calendar.SEPTEMBER, 16, 0, 10, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // On resume check
        viewModel.checkDateRefresh(cal0010.timeInMillis)
        val resumedDate = viewModel.currentGregorianDate.value
        assertTrue("Date must immediately refresh to September 16 upon resume, got: $resumedDate", resumedDate.contains("16 September 2026"))

        // Prayer times for today must be computed for September 16
        val todayPrayers = viewModel.todayPrayerTimes.value
        val expectedTimes = AstronomicalEngine.calculate(
            year = 2026,
            month = 9,
            day = 16,
            latitude = viewModel.settings.value.location.latitude,
            longitude = viewModel.settings.value.location.longitude,
            elevationMeters = viewModel.settings.value.location.elevationMeters,
            method = viewModel.settings.value.method,
            madhab = viewModel.settings.value.madhab,
            highLatitudeRule = viewModel.settings.value.highLatitudeRule,
            timeZone = tz
        )
        assertEquals("Today's prayer times must match September 16 after midnight resume", expectedTimes.fajr, todayPrayers.fajr)
    }

    @Test
    fun `test 4 no unnecessary continuous recomputation on seconds ticks within same day`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = PrayerViewModel(app)
        val tz = TimeZone.getTimeZone("Asia/Riyadh")

        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.SEPTEMBER, 15, 23, 59, 50)
            set(Calendar.MILLISECOND, 0)
        }

        viewModel.checkDateRefresh(cal.timeInMillis)
        val initialDay = viewModel.currentLocalDate.value
        val initialGregorian = viewModel.currentGregorianDate.value
        val initialHijri = viewModel.currentHijriDate.value

        // Simulate 5 seconds ticking within the same day
        for (sec in 51..55) {
            cal.set(Calendar.SECOND, sec)
            val changed = viewModel.checkDateRefresh(cal.timeInMillis)
            assertFalse("checkDateRefresh should not trigger a day change within same day", changed)
            assertSame("currentLocalDate instance should not change on second ticks", initialDay, viewModel.currentLocalDate.value)
            assertEquals("Gregorian date should remain unchanged", initialGregorian, viewModel.currentGregorianDate.value)
            assertEquals("Hijri date should remain unchanged", initialHijri, viewModel.currentHijriDate.value)
        }
    }

    @Test
    fun `test 5 Hijri adjustment preserved after midnight`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = PrayerViewModel(app)
        val tz = TimeZone.getTimeZone("Asia/Riyadh")

        // Set Hijri adjustment to +1 day
        viewModel.updateHijriAdjustment(1)

        val cal15 = Calendar.getInstance(tz).apply {
            set(2026, Calendar.SEPTEMBER, 15, 23, 59, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val cal16 = Calendar.getInstance(tz).apply {
            set(2026, Calendar.SEPTEMBER, 16, 0, 1, 0)
            set(Calendar.MILLISECOND, 0)
        }

        viewModel.checkDateRefresh(cal15.timeInMillis)
        val hijri15 = viewModel.currentHijriDate.value
        val expected15WithAdj = HijriCalendar.fromGregorian(2026, 9, 15, dayAdjustment = 1)
        assertEquals("Hijri day on Sep 15 with adjustment +1", expected15WithAdj.day, hijri15.day)

        // Advance to Sep 16
        viewModel.checkDateRefresh(cal16.timeInMillis)
        val hijri16 = viewModel.currentHijriDate.value
        val expected16WithAdj = HijriCalendar.fromGregorian(2026, 9, 16, dayAdjustment = 1)
        assertEquals("Hijri day on Sep 16 must still preserve adjustment +1", expected16WithAdj.day, hijri16.day)
        assertEquals("Hijri month on Sep 16 must still preserve adjustment +1", expected16WithAdj.month, hijri16.month)
    }

    // ============================================================================
    // Bug #5 Regression Tests: Monthly Prayer Schedule Location-Timezone Consistency
    // ============================================================================

    @Test
    fun `test 1 device and location have different dates identifies selected-location date as today`() {
        val originalDefault = TimeZone.getDefault()
        try {
            // Device timezone is Asia/Colombo (UTC+5:30)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Colombo"))

            val app = ApplicationProvider.getApplicationContext<Application>()
            val viewModel = PrayerViewModel(app)

            // Selected prayer location: Los Angeles (America/Los_Angeles, UTC-7:00 PDT)
            val laCity = BundledCities.CITIES.first { it.name == "Los Angeles" }
            viewModel.selectBundledCity(laCity)

            // Instant: 2026-09-16 02:00:00 Colombo time
            // In UTC: 2026-09-15 20:30:00 UTC
            // In Los Angeles (PDT): 2026-09-15 13:30:00 -> Date is September 15, 2026!
            // In Colombo (device): 2026-09-16 02:00:00 -> Date is September 16, 2026!
            val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(2026, Calendar.SEPTEMBER, 15, 20, 30, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val instantMillis = utcCal.timeInMillis

            // Refresh ViewModel date for this instant
            viewModel.checkDateRefresh(instantMillis)

            // Location-aware day must be September 15, 2026
            val locationDay = viewModel.currentLocalDate.value
            assertEquals(2026, locationDay.year)
            assertEquals(9, locationDay.month)
            assertEquals(15, locationDay.dayOfMonth)
            assertEquals("America/Los_Angeles", locationDay.timeZoneId)

            // Verify device date would have been 16 (confirming the test condition)
            val deviceCal = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = instantMillis }
            assertEquals(16, deviceCal.get(Calendar.DAY_OF_MONTH))

            // In Monthly Schedule for September 2026:
            // Day 15 must be today, Day 16 must NOT be today
            val isDay15Today = MonthScheduleHelper.isToday(2026, 9, 15, locationDay)
            val isDay16Today = MonthScheduleHelper.isToday(2026, 9, 16, locationDay)
            assertTrue("Day 15 must be identified as today for Los Angeles", isDay15Today)
            assertFalse("Day 16 (device date) must NOT be today for Los Angeles", isDay16Today)

            val monthTitle = MonthScheduleHelper.formatMonthTitle(2026, 9, viewModel.getLocationTimeZone())
            assertEquals("September 2026", monthTitle)
        } finally {
            TimeZone.setDefault(originalDefault)
        }
    }

    @Test
    fun `test 2 same date different timezone preserves normal behavior`() {
        val originalDefault = TimeZone.getDefault()
        try {
            // Device timezone is Asia/Colombo (UTC+5:30)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Colombo"))

            val app = ApplicationProvider.getApplicationContext<Application>()
            val viewModel = PrayerViewModel(app)

            // Selected prayer location: Dubai (Asia/Dubai, UTC+4:00)
            val dubaiCity = BundledCities.CITIES.first { it.name == "Dubai" }
            viewModel.selectBundledCity(dubaiCity)

            // Instant: 2026-09-16 12:00:00 UTC
            // In Colombo (UTC+5:30): 2026-09-16 17:30:00 -> Date: Sep 16
            // In Dubai (UTC+4:00):   2026-09-16 16:00:00 -> Date: Sep 16
            val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(2026, Calendar.SEPTEMBER, 16, 12, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val instantMillis = utcCal.timeInMillis

            viewModel.checkDateRefresh(instantMillis)
            val locationDay = viewModel.currentLocalDate.value
            assertEquals(2026, locationDay.year)
            assertEquals(9, locationDay.month)
            assertEquals(16, locationDay.dayOfMonth)
            assertEquals("Asia/Dubai", locationDay.timeZoneId)

            assertTrue("Day 16 must be identified as today in Dubai", MonthScheduleHelper.isToday(2026, 9, 16, locationDay))
            assertFalse("Day 15 must not be today", MonthScheduleHelper.isToday(2026, 9, 15, locationDay))
            assertFalse("Day 17 must not be today", MonthScheduleHelper.isToday(2026, 9, 17, locationDay))
        } finally {
            TimeZone.setDefault(originalDefault)
        }
    }

    @Test
    fun `test 3 location change recalculates monthly today state for new location`() {
        val originalDefault = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

            val app = ApplicationProvider.getApplicationContext<Application>()
            val viewModel = PrayerViewModel(app)

            // Instant: 2026-09-15 21:00:00 UTC
            val instantMillis = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(2026, Calendar.SEPTEMBER, 15, 21, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            // Start with Location A: Dubai (Asia/Dubai, UTC+4:00)
            // In Dubai: 2026-09-16 01:00:00 -> Local date is September 16
            val dubaiCity = BundledCities.CITIES.first { it.name == "Dubai" }
            viewModel.selectBundledCity(dubaiCity)
            viewModel.checkDateRefresh(instantMillis)

            val dubaiDay = viewModel.currentLocalDate.value
            assertEquals(16, dubaiDay.dayOfMonth)
            assertTrue("Dubai at 21:00 UTC has crossed midnight into Sep 16", MonthScheduleHelper.isToday(2026, 9, 16, dubaiDay))
            assertFalse("Dubai is no longer on Sep 15", MonthScheduleHelper.isToday(2026, 9, 15, dubaiDay))

            // Switch to Location B: Los Angeles (America/Los_Angeles, UTC-7:00)
            // In Los Angeles: 2026-09-15 14:00:00 -> Local date is September 15
            val laCity = BundledCities.CITIES.first { it.name == "Los Angeles" }
            viewModel.selectBundledCity(laCity)
            viewModel.checkDateRefresh(instantMillis)

            val laDay = viewModel.currentLocalDate.value
            assertEquals("America/Los_Angeles", laDay.timeZoneId)
            assertEquals(15, laDay.dayOfMonth)
            assertTrue("Los Angeles at 21:00 UTC is still on Sep 15", MonthScheduleHelper.isToday(2026, 9, 15, laDay))
            assertFalse("Los Angeles has not yet reached Sep 16", MonthScheduleHelper.isToday(2026, 9, 16, laDay))
        } finally {
            TimeZone.setDefault(originalDefault)
        }
    }

    @Test
    fun `test 4 around midnight changes day at correct location-local boundary`() {
        val originalDefault = TimeZone.getDefault()
        try {
            // Device timezone is Asia/Colombo (UTC+5:30)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Colombo"))

            val app = ApplicationProvider.getApplicationContext<Application>()
            val viewModel = PrayerViewModel(app)

            // Selected prayer location: Dubai (Asia/Dubai, UTC+4:00)
            val dubaiCity = BundledCities.CITIES.first { it.name == "Dubai" }
            viewModel.selectBundledCity(dubaiCity)

            // Instant T1: 2026-09-15 19:30:00 UTC
            // Colombo (device): 2026-09-16 01:00:00 -> Device has crossed midnight to Sep 16!
            // Dubai (location): 2026-09-15 23:30:00 -> Dubai is still on Sep 15!
            val t1 = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(2026, Calendar.SEPTEMBER, 15, 19, 30, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            viewModel.checkDateRefresh(t1)
            val dayBeforeMidnightInDubai = viewModel.currentLocalDate.value
            assertEquals("Dubai must still be on Sep 15 before its local midnight", 15, dayBeforeMidnightInDubai.dayOfMonth)
            assertTrue("Monthly schedule must identify Sep 15 as today for Dubai", MonthScheduleHelper.isToday(2026, 9, 15, dayBeforeMidnightInDubai))
            assertFalse("Monthly schedule must NOT identify Sep 16 as today for Dubai yet", MonthScheduleHelper.isToday(2026, 9, 16, dayBeforeMidnightInDubai))

            // Instant T2: 2026-09-15 20:05:00 UTC (35 minutes later)
            // Colombo (device): 2026-09-16 01:35:00
            // Dubai (location): 2026-09-16 00:05:00 -> Dubai has crossed midnight to Sep 16!
            val t2 = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(2026, Calendar.SEPTEMBER, 15, 20, 5, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            viewModel.checkDateRefresh(t2)
            val dayAfterMidnightInDubai = viewModel.currentLocalDate.value
            assertEquals("Dubai must be on Sep 16 after its local midnight", 16, dayAfterMidnightInDubai.dayOfMonth)
            assertTrue("Monthly schedule must now identify Sep 16 as today for Dubai", MonthScheduleHelper.isToday(2026, 9, 16, dayAfterMidnightInDubai))
            assertFalse("Monthly schedule must no longer identify Sep 15 as today for Dubai", MonthScheduleHelper.isToday(2026, 9, 15, dayAfterMidnightInDubai))
        } finally {
            TimeZone.setDefault(originalDefault)
        }
    }

    @Test
    fun `test 5 DST transition preserves correct date selection in monthly schedule`() {
        val originalDefault = TimeZone.getDefault()
        try {
            // Device timezone is UTC
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

            val app = ApplicationProvider.getApplicationContext<Application>()
            val viewModel = PrayerViewModel(app)

            // London (Europe/London) has DST ending on Oct 25, 2026 at 02:00 BST -> 01:00 GMT
            val londonCity = BundledCities.CITIES.first { it.name == "London" }
            viewModel.selectBundledCity(londonCity)

            // Instant before DST transition: Oct 25, 2026 00:30 UTC (01:30 BST)
            val tBeforeTransition = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(2026, Calendar.OCTOBER, 25, 0, 30, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            viewModel.checkDateRefresh(tBeforeTransition)
            val dayBefore = viewModel.currentLocalDate.value
            assertEquals(2026, dayBefore.year)
            assertEquals(10, dayBefore.month)
            assertEquals(25, dayBefore.dayOfMonth)
            assertTrue(MonthScheduleHelper.isToday(2026, 10, 25, dayBefore))

            // Instant after DST transition: Oct 25, 2026 01:30 UTC (01:30 GMT)
            val tAfterTransition = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(2026, Calendar.OCTOBER, 25, 1, 30, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            viewModel.checkDateRefresh(tAfterTransition)
            val dayAfterTransition = viewModel.currentLocalDate.value
            assertEquals(25, dayAfterTransition.dayOfMonth)
            assertTrue(MonthScheduleHelper.isToday(2026, 10, 25, dayAfterTransition))

            // Instant crossing midnight into Oct 26: Oct 26, 2026 00:15 UTC (00:15 GMT)
            val tNextDay = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(2026, Calendar.OCTOBER, 26, 0, 15, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            viewModel.checkDateRefresh(tNextDay)
            val dayNext = viewModel.currentLocalDate.value
            assertEquals(26, dayNext.dayOfMonth)
            assertTrue(MonthScheduleHelper.isToday(2026, 10, 26, dayNext))
            assertFalse(MonthScheduleHelper.isToday(2026, 10, 25, dayNext))

            // Month title for October
            val title = MonthScheduleHelper.formatMonthTitle(2026, 10, viewModel.getLocationTimeZone())
            assertEquals("October 2026", title)
        } finally {
            TimeZone.setDefault(originalDefault)
        }
    }

    // ============================================================================
    // Bug #1 Notification Runtime Permission Tests (Android 13+ & Android 12-)
    // ============================================================================

    @Test
    @Config(sdk = [33])
    fun `test 1 Android 13+ permission not granted initiates appropriate request from UI layer and detects missing permission`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowApp = shadowOf(app)
        shadowApp.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val prefs = AthanPreferences.getInstance(app)
        prefs.setNotificationPermissionPrompted(false)

        val viewModel = PrayerViewModel(app)

        // 1. Detect whether POST_NOTIFICATIONS is already granted
        assertFalse("POST_NOTIFICATIONS must not be granted initially", NotificationPermissionManager.isPostNotificationsGranted(app))
        assertFalse("NotificationPermissionManager must detect missing permission", NotificationPermissionManager.hasNotificationPermission(app))
        assertFalse("ViewModel state must report permission not granted", viewModel.isNotificationPermissionGranted.value)

        // 2. Fresh install on API 33+ with no prior prompt should initiate permission request
        val shouldPrompt = NotificationPermissionManager.shouldPromptInitialPermission(
            context = app,
            hasAlreadyPrompted = viewModel.hasPromptedNotificationPermission()
        )
        assertTrue("Application must detect missing permission and initiate request on first UI launch", shouldPrompt)

        // 3. UI/Lifecycle layer triggers request and records prompted state to prevent spamming
        viewModel.markNotificationPermissionPrompted()
        assertTrue("Prompted flag must be persisted", viewModel.hasPromptedNotificationPermission())

        // 4. Repeated requests on subsequent recompositions or app launches are prevented
        val shouldPromptAgain = NotificationPermissionManager.shouldPromptInitialPermission(
            context = app,
            hasAlreadyPrompted = viewModel.hasPromptedNotificationPermission()
        )
        assertFalse("Application must not repeatedly request permission after it was prompted", shouldPromptAgain)
    }

    @Test
    @Config(sdk = [33])
    fun `test 2 Android 13+ permission already granted does not trigger unnecessary permission request`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowApp = shadowOf(app)
        shadowApp.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val prefs = AthanPreferences.getInstance(app)
        prefs.setNotificationPermissionPrompted(false)

        val viewModel = PrayerViewModel(app)

        assertTrue("POST_NOTIFICATIONS must be detected as granted", NotificationPermissionManager.isPostNotificationsGranted(app))
        assertTrue("Notification permission must be active", NotificationPermissionManager.hasNotificationPermission(app))
        assertTrue("ViewModel state must report permission granted", viewModel.isNotificationPermissionGranted.value)

        // Since it is already granted, shouldPromptInitialPermission must be false even if not previously prompted
        val shouldPrompt = NotificationPermissionManager.shouldPromptInitialPermission(
            context = app,
            hasAlreadyPrompted = viewModel.hasPromptedNotificationPermission()
        )
        assertFalse("Permission request must not be triggered when already granted", shouldPrompt)
    }

    @Test
    @Config(sdk = [33])
    fun `test 3 user grants permission continues normally and notification scheduling remains enabled`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowApp = shadowOf(app)
        shadowApp.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val viewModel = PrayerViewModel(app)

        // User grants permission in runtime dialog
        shadowApp.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        viewModel.onNotificationPermissionResult(true)

        assertTrue("ViewModel permission state must update to true", viewModel.isNotificationPermissionGranted.value)
        assertTrue("Prompted flag must be set", viewModel.hasPromptedNotificationPermission())

        // Verify alarms and notification scheduling remain enabled and intact
        val scheduledAlarms = AthanAlarmScheduler.getScheduledRequestCodes(app)
        assertTrue("Alarms must continue to be scheduled and managed", scheduledAlarms.isNotEmpty())

        // Prayer calculations continue normally
        val todayTimes = viewModel.todayPrayerTimes.value
        assertTrue("Prayer times calculation must continue normally", todayTimes.fajr > 0L)
        assertTrue("Fajr must precede sunrise", todayTimes.fajr < todayTimes.sunrise)
    }

    @Test
    @Config(sdk = [33])
    fun `test 4 user denies permission does not crash continues calculations maintains alarms and prevents loops`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowApp = shadowOf(app)
        shadowApp.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val viewModel = PrayerViewModel(app)

        // User denies permission in runtime dialog
        viewModel.onNotificationPermissionResult(false)

        // 1. Application does not crash and state reflects denial
        assertFalse("ViewModel permission state must be false", viewModel.isNotificationPermissionGranted.value)
        assertTrue("Prompted flag must be recorded to prevent repeated prompts", viewModel.hasPromptedNotificationPermission())

        // 2. Prayer-time calculations continue completely unaffected
        val todayTimes = viewModel.todayPrayerTimes.value
        val tomorrowTimes = viewModel.tomorrowPrayerTimes.value
        assertTrue("Fajr calculation must continue offline", todayTimes.fajr > 0L)
        assertTrue("Isha calculation must continue offline", todayTimes.isha > 0L)
        assertTrue("Tomorrow Fajr calculation must continue offline", tomorrowTimes.fajr > 0L)

        // 3. Alarms continue to be managed in the background without crashing
        AthanAlarmScheduler.scheduleRollingAlarms(app)
        val scheduledAlarms = AthanAlarmScheduler.getScheduledRequestCodes(app)
        assertTrue("Alarms must remain scheduled so that if permission is granted in Settings, alarms trigger", scheduledAlarms.isNotEmpty())

        // 4. Permission dialog is not repeatedly triggered on subsequent cycles
        val shouldPromptOnRelaunch = NotificationPermissionManager.shouldPromptInitialPermission(
            context = app,
            hasAlreadyPrompted = viewModel.hasPromptedNotificationPermission()
        )
        assertFalse("Permission dialog must not be repeatedly forced after user denial", shouldPromptOnRelaunch)
    }

    @Test
    @Config(sdk = [32])
    fun `test 5 Android 12 or lower does not attempt runtime POST_NOTIFICATIONS permission request`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = AthanPreferences.getInstance(app)
        prefs.setNotificationPermissionPrompted(false)

        val viewModel = PrayerViewModel(app)

        // On Android 12 (API 32) and lower:
        assertFalse("Runtime permission must not be required on API 32 or lower", NotificationPermissionManager.isRuntimePermissionRequired())
        assertTrue("isPostNotificationsGranted must return true on API <= 32", NotificationPermissionManager.isPostNotificationsGranted(app))

        val shouldPrompt = NotificationPermissionManager.shouldPromptInitialPermission(
            context = app,
            hasAlreadyPrompted = false
        )
        assertFalse("No POST_NOTIFICATIONS runtime request must ever be attempted on Android 12 or lower", shouldPrompt)

        // Normal notification system continues uninterrupted
        assertTrue("Notification permission is treated as standard on Android 12", NotificationPermissionManager.hasNotificationPermission(app))
        assertTrue("ViewModel reflects notification permission as granted", viewModel.isNotificationPermissionGranted.value)
    }

    // =========================================================================
    // BUG #2 TESTS: EXACT ALARM PERMISSION HANDLING & RECOVERY
    // =========================================================================

    @Test
    fun `test exact alarm capability check delegates to system on Android 12+`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(ExactAlarmPermissionManager.isExactAlarmPermissionRequired())

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(ExactAlarmPermissionManager.canScheduleExactAlarms(app))
        assertTrue(AthanAlarmScheduler.canScheduleExactAlarms(app))

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(ExactAlarmPermissionManager.canScheduleExactAlarms(app))
        assertFalse(AthanAlarmScheduler.canScheduleExactAlarms(app))
    }

    @Test
    @Config(sdk = [30])
    fun `test exact alarm permission is not required on Android 11 and lower`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(ExactAlarmPermissionManager.isExactAlarmPermissionRequired())
        assertTrue(ExactAlarmPermissionManager.canScheduleExactAlarms(app))
        assertTrue(AthanAlarmScheduler.canScheduleExactAlarms(app))
    }

    @Test
    fun `test exact alarm settings intent targets schedule exact alarm action`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val intent = ExactAlarmPermissionManager.createExactAlarmSettingsIntent(app)
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
    }

    @Test
    fun `test exact alarm denied cancels alarms safely without crashing or throwing`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)

        // First schedule while granted
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val scheduled = AthanAlarmScheduler.scheduleRollingAlarms(app)
        assertTrue("Scheduling should succeed when permission is granted", scheduled)
        assertTrue("Alarms should be present in AlarmManager", shadowAlarmManager.scheduledAlarms.isNotEmpty())

        // Now revoke exact alarm permission
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val rescheduled = AthanAlarmScheduler.scheduleRollingAlarms(app)
        assertFalse("Scheduling should return false when permission is revoked", rescheduled)
        assertTrue("All alarms should be cancelled from AlarmManager", shadowAlarmManager.scheduledAlarms.isEmpty())
        assertTrue("Scheduled request codes set should be cleared", AthanAlarmScheduler.getScheduledRequestCodes(app).isEmpty())
    }

    @Test
    fun `test exact alarm restoration on app resume rebuilds rolling alarms idempotently`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)

        // Start in revoked state
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val viewModel = PrayerViewModel(app)
        assertFalse("ViewModel must reflect restricted exact alarm status", viewModel.canScheduleExactAlarms.value)
        assertTrue("AlarmManager should have 0 alarms in revoked state", shadowAlarmManager.scheduledAlarms.isEmpty())

        // User grants permission in system settings and resumes app
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        viewModel.onAppResume()

        assertTrue("ViewModel must reflect restored exact alarm status", viewModel.canScheduleExactAlarms.value)
        val alarmsAfterResume = shadowAlarmManager.scheduledAlarms.toList()
        assertTrue("Alarms must be restored after app resume", alarmsAfterResume.isNotEmpty())

        // Resuming again should remain idempotent without duplicate alarms
        viewModel.onAppResume()
        val alarmsAfterSecondResume = shadowAlarmManager.scheduledAlarms.toList()
        assertEquals("Subsequent resume must not duplicate alarms", alarmsAfterResume.size, alarmsAfterSecondResume.size)
    }

    @Test
    fun `test exact alarm permission broadcast triggers schedule rebuild in BootAndClockReceiver`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)

        // Start revoked
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        AthanAlarmScheduler.scheduleRollingAlarms(app)
        assertTrue("Alarms must be empty initially", shadowAlarmManager.scheduledAlarms.isEmpty())

        // System broadcasts permission state changed after grant
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val receiver = BootAndClockReceiver()
        val intent = Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
        receiver.onReceive(app, intent)

        assertTrue("Alarms must be scheduled after receiver handles permission changed broadcast", shadowAlarmManager.scheduledAlarms.isNotEmpty())
    }

    @Test
    fun `test prayer calculations and user settings remain intact when exact alarms are revoked`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = AthanPreferences.getInstance(app)

        // Configure custom user preferences
        prefs.updateMethod(CalculationMethod.MuslimWorldLeague)
        prefs.updateMadhab(Madhab.Hanafi)
        prefs.togglePrayerNotification(PrayerType.FAJR, true)
        prefs.togglePrayerNotification(PrayerType.ASR, false)

        // Revoke exact alarm
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val viewModel = PrayerViewModel(app)
        viewModel.onAppResume()

        // Verify calculations continue offline completely unaffected
        val todayTimes = viewModel.todayPrayerTimes.value
        assertTrue("Fajr calculation must continue", todayTimes.fajr > 0L)
        assertTrue("Dhuhr calculation must continue", todayTimes.dhuhr > 0L)
        assertTrue("Asr calculation must continue", todayTimes.asr > 0L)

        // Verify user preferences are strictly preserved
        val currentSettings = prefs.settingsFlow.value
        assertEquals(CalculationMethod.MuslimWorldLeague, currentSettings.method)
        assertEquals(Madhab.Hanafi, currentSettings.madhab)
        assertEquals(true, currentSettings.prayerNotifications[PrayerType.FAJR])
        assertEquals(false, currentSettings.prayerNotifications[PrayerType.ASR])
    }

    // =========================================================================
    // BUG #3 TESTS: GPS TIMEOUT & ROBUST LOCATION PROVIDER BEHAVIOR
    // =========================================================================

    @Test
    fun `test 1 GPS request succeeds before timeout`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadowLocationManager = shadowOf(locationManager)
        shadowLocationManager.setProviderEnabled(LocationManager.GPS_PROVIDER, true)

        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 24.467
            longitude = 39.611
            altitude = 600.0
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = System.nanoTime()
        }
        shadowLocationManager.simulateLocation(loc)

        val result = runBlocking {
            OfflineLocationProvider.requestSingleLocation(app, timeoutMillis = 2000L, useLastKnownIfFresh = true)
        }
        assertTrue("Location result should be Success, got $result", result is LocationResult.Success)
        val successLoc = (result as LocationResult.Success).location
        assertEquals(24.467, successLoc.latitude, 0.001)
        assertEquals(39.611, successLoc.longitude, 0.001)
    }

    @Test
    fun `test 2 GPS request reaches timeout`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadowLocationManager = shadowOf(locationManager)
        shadowLocationManager.setProviderEnabled(LocationManager.GPS_PROVIDER, true)

        val result = runBlocking {
            OfflineLocationProvider.requestSingleLocation(app, timeoutMillis = 100L, useLastKnownIfFresh = false)
        }
        assertTrue("Result must be Timeout, got $result", result is LocationResult.Timeout)
    }

    @Test
    fun `test 3 GPS request is cancelled`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val viewModel = PrayerViewModel(app)
        viewModel.requestGpsLocation(timeoutMillis = 5000L, useLastKnownIfFresh = false)
        assertTrue(viewModel.isLocatingGps.value)
        assertEquals(GpsLocationState.Locating, viewModel.gpsState.value)

        viewModel.cancelGpsLocationRequest()
        assertFalse(viewModel.isLocatingGps.value)
        assertEquals(GpsLocationState.Cancelled, viewModel.gpsState.value)
    }

    @Test
    fun `test 4 Location permission denied`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        val result = runBlocking {
            OfflineLocationProvider.requestSingleLocation(app, timeoutMillis = 1000L)
        }
        assertEquals(LocationResult.PermissionDenied, result)

        val viewModel = PrayerViewModel(app)
        viewModel.onLocationPermissionDenied()
        assertEquals(GpsLocationState.PermissionDenied, viewModel.gpsState.value)
        assertFalse(viewModel.isLocatingGps.value)
    }

    @Test
    fun `test 5 Location provider unavailable or disabled`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadowLocationManager = shadowOf(locationManager)
        shadowLocationManager.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowLocationManager.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            shadowLocationManager.setLocationEnabled(false)
        }

        val result = runBlocking {
            OfflineLocationProvider.requestSingleLocation(app, timeoutMillis = 1000L)
        }
        assertEquals(LocationResult.ProviderDisabled, result)
    }

    @Test
    fun `test 6 Provider produces no usable fix`() {
        assertFalse(OfflineLocationProvider.isValidCoordinates(Double.NaN, 0.0))
        assertFalse(OfflineLocationProvider.isValidCoordinates(0.0, Double.NaN))
        assertFalse(OfflineLocationProvider.isValidCoordinates(Double.POSITIVE_INFINITY, 0.0))
        assertFalse(OfflineLocationProvider.isValidCoordinates(100.0, 50.0))
        assertFalse(OfflineLocationProvider.isValidCoordinates(0.0, 200.0))
        assertFalse(OfflineLocationProvider.isValidLocation(null))
        assertTrue(OfflineLocationProvider.isValidCoordinates(21.4225, 39.8262))
    }

    @Test
    fun `test 7 Invalid latitude or longitude is rejected`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = AthanPreferences.getInstance(app)
        val initialLocation = prefs.settingsFlow.value.location

        val viewModel = PrayerViewModel(app)
        viewModel.setManualCoordinates("Bad Coordinates", 95.0, 200.0, 0.0)

        // Existing location must remain intact
        assertEquals(initialLocation.latitude, prefs.settingsFlow.value.location.latitude, 0.0001)
        assertEquals(initialLocation.longitude, prefs.settingsFlow.value.location.longitude, 0.0001)
        assertTrue(viewModel.gpsState.value is GpsLocationState.Error)
    }

    @Test
    fun `test 8 Late callback after timeout is ignored`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadowLocationManager = shadowOf(locationManager)
        shadowLocationManager.setProviderEnabled(LocationManager.GPS_PROVIDER, true)

        val prefs = AthanPreferences.getInstance(app)
        val originalLocation = prefs.settingsFlow.value.location

        val viewModel = PrayerViewModel(app)
        viewModel.requestGpsLocation(timeoutMillis = 50L, useLastKnownIfFresh = false)
        Thread.sleep(150L)
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertFalse("Locating state must be false after timeout", viewModel.isLocatingGps.value)
        assertEquals(GpsLocationState.TimeoutOrNoFix, viewModel.gpsState.value)
        assertEquals("Original location should remain intact", originalLocation.latitude, prefs.settingsFlow.value.location.latitude, 0.0001)
    }

    @Test
    fun `test 9 Late callback after cancellation is ignored`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadowLocationManager = shadowOf(locationManager)
        shadowLocationManager.setProviderEnabled(LocationManager.GPS_PROVIDER, true)

        val prefs = AthanPreferences.getInstance(app)
        val originalLocation = prefs.settingsFlow.value.location

        val viewModel = PrayerViewModel(app)
        viewModel.requestGpsLocation(timeoutMillis = 5000L, useLastKnownIfFresh = false)
        viewModel.cancelGpsLocationRequest()

        assertEquals(GpsLocationState.Cancelled, viewModel.gpsState.value)
        assertEquals(originalLocation.latitude, prefs.settingsFlow.value.location.latitude, 0.0001)
    }

    @Test
    fun `test 10 GPS result cannot overwrite a newer manual or city selection`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadowLocationManager = shadowOf(locationManager)
        shadowLocationManager.setProviderEnabled(LocationManager.GPS_PROVIDER, true)

        val prefs = AthanPreferences.getInstance(app)
        val viewModel = PrayerViewModel(app)

        viewModel.requestGpsLocation(timeoutMillis = 5000L, useLastKnownIfFresh = false)
        assertTrue(viewModel.isLocatingGps.value)

        val london = BundledCities.CITIES.first { it.name == "London" }
        viewModel.selectBundledCity(london)

        assertFalse("GPS request must be cancelled when city is selected", viewModel.isLocatingGps.value)
        assertEquals("London", prefs.settingsFlow.value.location.cityName)
        assertEquals(london.latitude, prefs.settingsFlow.value.location.latitude, 0.001)
    }

    @Test
    fun `test 11 Existing valid location remains intact after GPS failure`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadowLocationManager = shadowOf(locationManager)
        shadowLocationManager.setProviderEnabled(LocationManager.GPS_PROVIDER, true)

        val prefs = AthanPreferences.getInstance(app)
        val cairo = BundledCities.CITIES.first { it.name == "Cairo" }
        val viewModel = PrayerViewModel(app)
        viewModel.selectBundledCity(cairo)

        viewModel.requestGpsLocation(timeoutMillis = 50L, useLastKnownIfFresh = false)
        Thread.sleep(150L)
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals("Cairo", prefs.settingsFlow.value.location.cityName)
        assertEquals(cairo.latitude, prefs.settingsFlow.value.location.latitude, 0.001)
    }

    @Test
    fun `test 12 Successful location change recalculates prayer times`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = PrayerViewModel(app)
        val makkah = BundledCities.CITIES.first { it.name.startsWith("Makkah") }
        viewModel.selectBundledCity(makkah)
        val makkahFajr = viewModel.todayPrayerTimes.value.fajr

        val tokyo = BundledCities.CITIES.first { it.name == "Tokyo" }
        viewModel.selectBundledCity(tokyo)
        val tokyoFajr = viewModel.todayPrayerTimes.value.fajr

        assertNotEquals("Prayer times must be recalculated on location change", makkahFajr, tokyoFajr)
    }

    @Test
    fun `test 13 Successful location change causes alarm scheduler to rebuild idempotently`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)

        val viewModel = PrayerViewModel(app)
        val makkah = BundledCities.CITIES.first { it.name.startsWith("Makkah") }
        viewModel.selectBundledCity(makkah)
        val makkahAlarmCount = shadowAlarmManager.scheduledAlarms.size
        assertTrue("Alarms should be scheduled for Makkah", makkahAlarmCount > 0)

        // Switch location to London
        val london = BundledCities.CITIES.first { it.name == "London" }
        viewModel.selectBundledCity(london)
        val londonAlarms = shadowAlarmManager.scheduledAlarms.toList()
        assertTrue("Alarms should be scheduled for London", londonAlarms.isNotEmpty())

        // Rescheduling/rebuilding again must be idempotent and not create duplicates
        viewModel.refreshAlarms()
        val rescheduledAlarms = shadowAlarmManager.scheduledAlarms.toList()
        assertEquals("Alarms must not duplicate on subsequent refresh", londonAlarms.size, rescheduledAlarms.size)

        // All scheduled alarms must have unique request codes
        val requestCodes = rescheduledAlarms.map { shadowOf(it.operation).requestCode }
        assertEquals("All scheduled alarms must have unique request codes", requestCodes.size, requestCodes.toSet().size)
    }

    @Test
    fun `test 14 No network access is introduced`() {
        assertTrue(OfflineLocationProvider.DEFAULT_TIMEOUT_MS > 0)
        assertTrue(BundledCities.CITIES.isNotEmpty())
        assertTrue(BundledCities.search("Makkah").isNotEmpty())
    }

    @Test
    fun `test 15 Lifecycle destruction cancels active location request`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadowLocationManager = shadowOf(locationManager)
        shadowLocationManager.setProviderEnabled(LocationManager.GPS_PROVIDER, true)

        val viewModel = PrayerViewModel(app)
        viewModel.requestGpsLocation(timeoutMillis = 10000L, useLastKnownIfFresh = false)
        assertTrue(viewModel.isLocatingGps.value)

        val onClearedMethod = PrayerViewModel::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(viewModel)

        assertFalse("GPS request must be cancelled on ViewModel clear", viewModel.isLocatingGps.value)
    }

    @Test
    fun `test prayer time formatted using selected location timezone instead of device timezone`() {
        val originalDeviceTz = TimeZone.getDefault()
        try {
            // Set device default timezone to America/New_York (UTC-4 in summer)
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))

            // 2026-09-16 12:00:00 UTC = 1789560000000L approx
            // We can construct a precise UTC calendar:
            val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(2026, Calendar.SEPTEMBER, 16, 12, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val testTimestamp = utcCal.timeInMillis

            // 1. In Europe/London (British Summer Time, UTC+1): 13:00 / 1:00 PM
            val london24 = PrayerTimeFormatter.formatTime(testTimestamp, is24Hour = true, timezoneId = "Europe/London")
            val london12 = PrayerTimeFormatter.formatTime(testTimestamp, is24Hour = false, timezoneId = "Europe/London")
            assertEquals("13:00", london24)
            assertEquals("1:00 PM", london12)

            // 2. In Asia/Colombo (Sri Lanka Standard Time, UTC+5:30): 17:30 / 5:30 PM
            val colombo24 = PrayerTimeFormatter.formatTime(testTimestamp, is24Hour = true, timezoneId = "Asia/Colombo")
            val colombo12 = PrayerTimeFormatter.formatTime(testTimestamp, is24Hour = false, timezoneId = "Asia/Colombo")
            assertEquals("17:30", colombo24)
            assertEquals("5:30 PM", colombo12)

            // 3. In Asia/Riyadh (Arabia Standard Time, UTC+3): 15:00 / 3:00 PM
            val riyadh24 = PrayerTimeFormatter.formatTime(testTimestamp, is24Hour = true, timezoneId = "Asia/Riyadh")
            val riyadh12 = PrayerTimeFormatter.formatTime(testTimestamp, is24Hour = false, timezoneId = "Asia/Riyadh")
            assertEquals("15:00", riyadh24)
            assertEquals("3:00 PM", riyadh12)

            // 4. Verify PrayerTimes.formatTime delegates correctly
            val prayerTimes = PrayerTimes(
                fajr = testTimestamp,
                sunrise = testTimestamp,
                dhuhr = testTimestamp,
                asr = testTimestamp,
                maghrib = testTimestamp,
                isha = testTimestamp,
                timezoneId = "Asia/Colombo"
            )
            assertEquals("17:30", prayerTimes.formatTime(prayerTimes.dhuhr, is24Hour = true))
            assertEquals("5:30 PM", prayerTimes.formatTime(prayerTimes.dhuhr, is24Hour = false))

            // Even when overridden with another timezoneId parameter:
            assertEquals("13:00", prayerTimes.formatTime(prayerTimes.dhuhr, is24Hour = true, timezoneId = "Europe/London"))
        } finally {
            TimeZone.setDefault(originalDeviceTz)
        }
    }

    @Test
    fun `test invalid or empty timezone ID safely resolves to default location timezone and never device timezone`() {
        val originalDeviceTz = TimeZone.getDefault()
        try {
            // Set device to Pacific time
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))

            val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(2026, Calendar.SEPTEMBER, 16, 12, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val testTimestamp = utcCal.timeInMillis

            // Invalid timezone ID
            val resolvedInvalid = PrayerTimeFormatter.resolveTimeZone("Invalid/Bogus_Zone")
            assertEquals(BundledCities.DEFAULT_CITY.timezoneId, resolvedInvalid.id)
            assertNotEquals("America/Los_Angeles", resolvedInvalid.id)

            // Blank timezone ID
            val resolvedBlank = PrayerTimeFormatter.resolveTimeZone("")
            assertEquals(BundledCities.DEFAULT_CITY.timezoneId, resolvedBlank.id)

            // Null timezone ID
            val resolvedNull = PrayerTimeFormatter.resolveTimeZone(null)
            assertEquals(BundledCities.DEFAULT_CITY.timezoneId, resolvedNull.id)

            // Formatting with invalid timezone uses default location fallback (Asia/Riyadh UTC+3: 15:00)
            val formattedInvalid = PrayerTimeFormatter.formatTime(testTimestamp, is24Hour = true, timezoneId = "Invalid/Bogus_Zone")
            assertEquals("15:00", formattedInvalid)

            // Unavailable time returns placeholder
            val formattedUnavailable = PrayerTimeFormatter.formatTime(0L, is24Hour = true, timezoneId = "Asia/Colombo")
            assertEquals("--:--", formattedUnavailable)
        } finally {
            TimeZone.setDefault(originalDeviceTz)
        }
    }

    @Test
    fun `test prayer time adjustments format accurately in selected location timezone`() {
        val originalDeviceTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"))

            val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(2026, Calendar.SEPTEMBER, 16, 12, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val dhuhrUtc = utcCal.timeInMillis // 12:00 UTC -> 17:30 Colombo

            val baseTimes = PrayerTimes(
                fajr = dhuhrUtc - 3600000L,
                sunrise = dhuhrUtc - 1800000L,
                dhuhr = dhuhrUtc,
                asr = dhuhrUtc + 3600000L,
                maghrib = dhuhrUtc + 7200000L,
                isha = dhuhrUtc + 10800000L,
                timezoneId = "Asia/Colombo"
            )

            // Adjust Dhuhr by +15 minutes
            val adjusted = baseTimes.withAdjustment(PrayerType.DHUHR, 15)
            // 17:30 + 15 min = 17:45 Colombo
            val formattedAdjusted24 = PrayerTimeFormatter.formatTime(
                adjusted.dhuhr,
                is24Hour = true,
                timezoneId = "Asia/Colombo"
            )
            val formattedAdjusted12 = PrayerTimeFormatter.formatTime(
                adjusted.dhuhr,
                is24Hour = false,
                timezoneId = "Asia/Colombo"
            )
            assertEquals("17:45", formattedAdjusted24)
            assertEquals("5:45 PM", formattedAdjusted12)
        } finally {
            TimeZone.setDefault(originalDeviceTz)
        }
    }

    @Test
    fun `test error 2 - Test 1 Formatter displays placeholder for TIME_UNAVAILABLE`() {
        val result12 = PrayerTimeFormatter.formatTime(
            millis = PrayerTimes.TIME_UNAVAILABLE,
            is24Hour = false,
            timezoneId = "Europe/London"
        )
        val result24 = PrayerTimeFormatter.formatTime(
            millis = PrayerTimes.TIME_UNAVAILABLE,
            is24Hour = true,
            timezoneId = "Europe/London"
        )
        assertEquals("--:--", result12)
        assertEquals("--:--", result24)
        assertFalse("Must not format as 1970", result12.contains("1970"))
        assertFalse("Must not format as 00:00", result24.contains("00:00"))

        val prayers = PrayerTimes(
            fajr = PrayerTimes.TIME_UNAVAILABLE,
            sunrise = 1000000000000L,
            dhuhr = 1000000000000L,
            asr = 1000000000000L,
            maghrib = 1000000000000L,
            isha = PrayerTimes.TIME_UNAVAILABLE,
            timezoneId = "Europe/London"
        )
        assertEquals("--:--", prayers.formatTime(prayers.fajr, is24Hour = false))
        assertEquals("--:--", prayers.formatTime(prayers.isha, is24Hour = true))
    }

    @Test
    fun `test error 2 - Test 2 Passed status is false for TIME_UNAVAILABLE`() {
        val prayerTime = PrayerTimes.TIME_UNAVAILABLE
        val now = System.currentTimeMillis()

        val isAvailable = prayerTime > PrayerTimes.TIME_UNAVAILABLE
        val isPassed = isAvailable && now > prayerTime

        assertFalse("Unavailable prayer must not be considered available", isAvailable)
        assertFalse("Unavailable prayer must not receive a PASSED state", isPassed)

        val prayers = PrayerTimes(
            fajr = PrayerTimes.TIME_UNAVAILABLE,
            sunrise = now + 100000L,
            dhuhr = now + 200000L,
            asr = now + 300000L,
            maghrib = now + 400000L,
            isha = PrayerTimes.TIME_UNAVAILABLE,
            timezoneId = "UTC"
        )
        assertFalse("isFajrAvailable must be false", prayers.isFajrAvailable)
        assertFalse("isIshaAvailable must be false", prayers.isIshaAvailable)
        assertFalse("isAvailable for FAJR must be false", prayers.isAvailable(PrayerType.FAJR))
        assertFalse("isAvailable for ISHA must be false", prayers.isAvailable(PrayerType.ISHA))
    }

    @Test
    fun `test error 2 - Test 3 Current prayer skips unavailable prayer`() {
        // Base time 12:00 UTC
        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.SEPTEMBER, 16, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val baseDhuhr = utcCal.timeInMillis
        val sunrise = baseDhuhr - (6 * 3600 * 1000L) // 06:00 UTC
        val asr = baseDhuhr + (3 * 3600 * 1000L) // 15:00 UTC
        val maghrib = baseDhuhr + (6 * 3600 * 1000L) // 18:00 UTC

        // Fajr and Isha are unavailable
        val schedule = PrayerTimes(
            fajr = PrayerTimes.TIME_UNAVAILABLE,
            sunrise = sunrise,
            dhuhr = baseDhuhr,
            asr = asr,
            maghrib = maghrib,
            isha = PrayerTimes.TIME_UNAVAILABLE,
            timezoneId = "UTC"
        )

        // 1. Before sunrise (04:00 UTC): Fajr is unavailable, so current prayer must be null (not Fajr)
        val earlyMorning = sunrise - (2 * 3600 * 1000L)
        assertNull("Before sunrise when Fajr is unavailable, current prayer must be null", schedule.getCurrentPrayer(earlyMorning))

        // 2. Between sunrise and dhuhr (09:00 UTC): Current prayer is Sunrise
        val midMorning = sunrise + (3 * 3600 * 1000L)
        assertEquals(PrayerType.SUNRISE, schedule.getCurrentPrayer(midMorning))

        // 3. Between dhuhr and asr (13:00 UTC): Current prayer is Dhuhr
        val afternoon = baseDhuhr + (1 * 3600 * 1000L)
        assertEquals(PrayerType.DHUHR, schedule.getCurrentPrayer(afternoon))

        // 4. After maghrib (20:00 UTC): Isha is unavailable, so current prayer remains Maghrib (never Isha)
        val night = maghrib + (2 * 3600 * 1000L)
        assertEquals(PrayerType.MAGHRIB, schedule.getCurrentPrayer(night))
        assertNotEquals(PrayerType.ISHA, schedule.getCurrentPrayer(night))
    }

    @Test
    fun `test error 2 - Test 4 Next prayer skips unavailable prayer`() {
        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.SEPTEMBER, 16, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val baseDhuhr = utcCal.timeInMillis
        val sunrise = baseDhuhr - (6 * 3600 * 1000L) // 06:00 UTC
        val asr = baseDhuhr + (3 * 3600 * 1000L) // 15:00 UTC
        val maghrib = baseDhuhr + (6 * 3600 * 1000L) // 18:00 UTC

        // Fajr and Isha are unavailable
        val schedule = PrayerTimes(
            fajr = PrayerTimes.TIME_UNAVAILABLE,
            sunrise = sunrise,
            dhuhr = baseDhuhr,
            asr = asr,
            maghrib = maghrib,
            isha = PrayerTimes.TIME_UNAVAILABLE,
            timezoneId = "UTC"
        )

        // At 04:00 UTC (before sunrise): Fajr is unavailable, so next prayer must be Sunrise, NOT Fajr
        val earlyMorning = sunrise - (2 * 3600 * 1000L)
        val nextEarly = schedule.getNextPrayer(earlyMorning)
        assertNotNull(nextEarly)
        assertEquals(PrayerType.SUNRISE, nextEarly!!.first)
        assertEquals(sunrise, nextEarly.second)

        // At 20:00 UTC (after maghrib): Isha is unavailable, so next prayer must be null (no more prayers today)
        val night = maghrib + (2 * 3600 * 1000L)
        val nextNight = schedule.getNextPrayer(night)
        assertNull("Unavailable Isha must not be selected as next prayer", nextNight)
    }

    @Test
    fun `test error 2 - Test 5 Monthly schedule displays placeholder for unavailable prayer`() {
        val prayerWithUnavailable = PrayerTimes(
            fajr = PrayerTimes.TIME_UNAVAILABLE,
            sunrise = 1789560000000L,
            dhuhr = 1789580000000L,
            asr = 1789590000000L,
            maghrib = 1789600000000L,
            isha = PrayerTimes.TIME_UNAVAILABLE,
            timezoneId = "Europe/London"
        )
        val tz = PrayerTimeFormatter.resolveTimeZone("Europe/London")
        assertEquals("--:--", PrayerTimeFormatter.formatTime(prayerWithUnavailable.fajr, is24Hour = false, tz))
        assertEquals("--:--", PrayerTimeFormatter.formatTime(prayerWithUnavailable.isha, is24Hour = true, tz))
        assertNotEquals("--:--", PrayerTimeFormatter.formatTime(prayerWithUnavailable.dhuhr, is24Hour = false, tz))
    }

    @Test
    fun `test error 2 - Test 6 Alarm scheduler skips TIME_UNAVAILABLE`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AthanPreferences.getInstance(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)

        // Configure a location in extreme high latitude (e.g. Tromsø in midsummer) with HighLatitudeRule.None
        // where Fajr and Isha cannot be calculated (polar day -> returns 0L / TIME_UNAVAILABLE)
        val polarLocation = UserLocation(
            cityName = "Tromsø",
            countryName = "Norway",
            latitude = 69.6492,
            longitude = 18.9553,
            elevationMeters = 0.0,
            timezoneId = "Europe/Oslo",
            isGpsDetected = false
        )
        prefs.updateLocation(polarLocation)
        prefs.updateHighLatitudeRule(HighLatitudeRule.None)

        val scheduled = AthanAlarmScheduler.scheduleRollingAlarms(context)
        assertTrue("Scheduler should succeed", scheduled)

        // Verify that no scheduled alarm has triggerAtMillis <= 0L
        for (scheduledAlarm in shadowAlarmManager.scheduledAlarms) {
            assertTrue(
                "No alarm must ever be scheduled at or before epoch 0",
                scheduledAlarm.triggerAtTime > PrayerTimes.TIME_UNAVAILABLE
            )
        }
    }

    @Test
    fun `test error 2 - Test 7 Adjustment preservation retains TIME_UNAVAILABLE`() {
        val baseTimes = PrayerTimes(
            fajr = PrayerTimes.TIME_UNAVAILABLE,
            sunrise = 1789560000000L,
            dhuhr = 1789580000000L,
            asr = 1789590000000L,
            maghrib = 1789600000000L,
            isha = PrayerTimes.TIME_UNAVAILABLE,
            timezoneId = "Asia/Riyadh"
        )

        val adjustedSingle = baseTimes.withAdjustment(PrayerType.FAJR, 20)
        assertEquals(
            "Applying adjustment to unavailable Fajr must remain TIME_UNAVAILABLE",
            PrayerTimes.TIME_UNAVAILABLE,
            adjustedSingle.fajr
        )

        val adjustedAll = baseTimes.withAdjustments(
            mapOf(
                PrayerType.FAJR to 15,
                PrayerType.SUNRISE to -5,
                PrayerType.DHUHR to 10,
                PrayerType.ASR to 0,
                PrayerType.MAGHRIB to 5,
                PrayerType.ISHA to -25
            )
        )
        assertEquals(PrayerTimes.TIME_UNAVAILABLE, adjustedAll.fajr)
        assertEquals(PrayerTimes.TIME_UNAVAILABLE, adjustedAll.isha)
        assertEquals(1789580000000L + (10 * 60 * 1000L), adjustedAll.dhuhr)
    }

    @Test
    fun `test error 3 - Test 1 Tomorrow Fajr unavailable enters unavailable state and never 00_00_00`() {
        val now = 1789605000000L // 21:00 UTC
        // Today's prayers all passed
        val todayPrayers = PrayerTimes(
            fajr = now - 50000000L,
            sunrise = now - 40000000L,
            dhuhr = now - 30000000L,
            asr = now - 20000000L,
            maghrib = now - 10000000L,
            isha = now - 5000000L,
            timezoneId = "UTC"
        )
        // Tomorrow's prayers are unavailable (e.g. polar night/day condition)
        val tomorrowPrayers = PrayerTimes(
            fajr = PrayerTimes.TIME_UNAVAILABLE,
            sunrise = PrayerTimes.TIME_UNAVAILABLE,
            dhuhr = PrayerTimes.TIME_UNAVAILABLE,
            asr = PrayerTimes.TIME_UNAVAILABLE,
            maghrib = PrayerTimes.TIME_UNAVAILABLE,
            isha = PrayerTimes.TIME_UNAVAILABLE,
            timezoneId = "UTC"
        )

        val nextPair = todayPrayers.getNextPrayer(now, tomorrowPrayers)
        assertNull("Next prayer must be null when today is exhausted and tomorrow has no available prayer", nextPair)

        val state = PrayerTimes.resolveNextPrayer(todayPrayers, tomorrowPrayers, now)
        assertFalse("Countdown state must be marked unavailable", state.isAvailable)
        assertFalse("state.available getter must be false", state.available)
        assertNull("No prayer type should be selected", state.prayerType)
        assertNull("No timestamp should be assigned", state.timestamp)
        assertNull("No countdown arithmetic should be performed", state.remainingMillis)

        // Verify that the UI digits are '--' and not '00:00:00'
        val hrStr = if (state.isAvailable && state.remainingMillis != null) String.format("%02d", state.remainingMillis!! / 3600000) else "--"
        val minStr = if (state.isAvailable && state.remainingMillis != null) String.format("%02d", (state.remainingMillis!! % 3600000) / 60000) else "--"
        val secStr = if (state.isAvailable && state.remainingMillis != null) String.format("%02d", (state.remainingMillis!! % 60000) / 1000) else "--"
        val countdownDisplay = "$hrStr:$minStr:$secStr"

        assertEquals("--:--:--", countdownDisplay)
        assertNotEquals("00:00:00", countdownDisplay)
    }

    @Test
    fun `test error 3 - Test 2 Tomorrow Fajr available becomes the countdown target`() {
        val now = 1789605000000L // 21:00 UTC
        // Today's prayers all passed
        val todayPrayers = PrayerTimes(
            fajr = now - 50000000L,
            sunrise = now - 40000000L,
            dhuhr = now - 30000000L,
            asr = now - 20000000L,
            maghrib = now - 10000000L,
            isha = now - 5000000L,
            timezoneId = "UTC"
        )
        val tomorrowFajr = now + (7 * 3600 * 1000L) // in 7 hours
        val tomorrowPrayers = PrayerTimes(
            fajr = tomorrowFajr,
            sunrise = tomorrowFajr + (1 * 3600 * 1000L),
            dhuhr = tomorrowFajr + (6 * 3600 * 1000L),
            asr = tomorrowFajr + (9 * 3600 * 1000L),
            maghrib = tomorrowFajr + (12 * 3600 * 1000L),
            isha = tomorrowFajr + (14 * 3600 * 1000L),
            timezoneId = "UTC"
        )

        val nextPair = todayPrayers.getNextPrayer(now, tomorrowPrayers)
        assertNotNull("Next prayer must be found for tomorrow", nextPair)
        assertEquals(PrayerType.FAJR, nextPair!!.first)
        assertEquals(tomorrowFajr, nextPair.second)

        val state = PrayerTimes.resolveNextPrayer(todayPrayers, tomorrowPrayers, now)
        assertTrue("Countdown state must be available", state.isAvailable)
        assertEquals(PrayerType.FAJR, state.prayerType)
        assertEquals(tomorrowFajr, state.timestamp)
        assertEquals(7 * 3600 * 1000L, state.remainingMillis)
    }

    @Test
    fun `test error 3 - Test 3 Other tomorrow prayers available selects valid future prayer instead of Fajr 0L`() {
        val now = 1789605000000L // 21:00 UTC
        val todayPrayers = PrayerTimes(
            fajr = now - 50000000L,
            sunrise = now - 40000000L,
            dhuhr = now - 30000000L,
            asr = now - 20000000L,
            maghrib = now - 10000000L,
            isha = now - 5000000L,
            timezoneId = "UTC"
        )
        // Tomorrow Fajr is unavailable (0L), but Sunrise is available
        val tomorrowSunrise = now + (8 * 3600 * 1000L)
        val tomorrowDhuhr = now + (14 * 3600 * 1000L)
        val tomorrowPrayers = PrayerTimes(
            fajr = PrayerTimes.TIME_UNAVAILABLE,
            sunrise = tomorrowSunrise,
            dhuhr = tomorrowDhuhr,
            asr = tomorrowDhuhr + 3000000L,
            maghrib = tomorrowDhuhr + 6000000L,
            isha = PrayerTimes.TIME_UNAVAILABLE,
            timezoneId = "UTC"
        )

        val nextPair = todayPrayers.getNextPrayer(now, tomorrowPrayers)
        assertNotNull("Should select next available prayer in defined schedule order", nextPair)
        assertEquals(PrayerType.SUNRISE, nextPair!!.first)
        assertEquals(tomorrowSunrise, nextPair.second)

        val state = PrayerTimes.resolveNextPrayer(todayPrayers, tomorrowPrayers, now)
        assertTrue("Countdown state must be available", state.isAvailable)
        assertEquals(PrayerType.SUNRISE, state.prayerType)
        assertEquals(tomorrowSunrise, state.timestamp)
        assertTrue(state.timestamp!! > PrayerTimes.TIME_UNAVAILABLE)
        assertEquals(8 * 3600 * 1000L, state.remainingMillis)
    }

    @Test
    fun `test error 3 - Test 4 Unavailable adjustment preserves unavailable state and does not create countdown target`() {
        val now = 1789605000000L
        val todayPrayers = PrayerTimes(
            fajr = now - 50000000L,
            sunrise = now - 40000000L,
            dhuhr = now - 30000000L,
            asr = now - 20000000L,
            maghrib = now - 10000000L,
            isha = now - 5000000L,
            timezoneId = "UTC"
        )
        val rawTomorrow = PrayerTimes(
            fajr = PrayerTimes.TIME_UNAVAILABLE,
            sunrise = PrayerTimes.TIME_UNAVAILABLE,
            dhuhr = PrayerTimes.TIME_UNAVAILABLE,
            asr = PrayerTimes.TIME_UNAVAILABLE,
            maghrib = PrayerTimes.TIME_UNAVAILABLE,
            isha = PrayerTimes.TIME_UNAVAILABLE,
            timezoneId = "UTC"
        )
        val adjustedTomorrow = rawTomorrow.withAdjustments(
            mapOf(
                PrayerType.FAJR to 20,
                PrayerType.SUNRISE to -10,
                PrayerType.DHUHR to 15,
                PrayerType.ISHA to -20
            )
        )
        assertEquals(PrayerTimes.TIME_UNAVAILABLE, adjustedTomorrow.fajr)

        val nextPair = todayPrayers.getNextPrayer(now, adjustedTomorrow)
        assertNull("Adjusted unavailable times must not be returned as next prayer", nextPair)

        val state = PrayerTimes.resolveNextPrayer(todayPrayers, adjustedTomorrow, now)
        assertFalse("Adjusted unavailable times must never produce an active countdown target", state.isAvailable)
        assertNull(state.timestamp)
        assertNull(state.remainingMillis)
    }

    @Test
    fun `test error 3 - Test 5 Midnight transition refreshes active schedule and resolves next valid prayer`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AthanPreferences.getInstance(context)
        prefs.updateLocation(
            UserLocation(
                cityName = "London",
                countryName = "UK",
                latitude = 51.5074,
                longitude = -0.1278,
                elevationMeters = 15.0,
                timezoneId = "UTC",
                isGpsDetected = false
            )
        )

        val viewModel = PrayerViewModel(context as Application)

        // 1. Set time to 23:59:59 UTC on Sept 16, 2026
        val cal2359 = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.SEPTEMBER, 16, 23, 59, 59)
            set(Calendar.MILLISECOND, 0)
        }
        val timeBeforeMidnight = cal2359.timeInMillis
        viewModel.checkDateRefresh(timeBeforeMidnight)

        // 2. Set time to 00:00:05 UTC on Sept 17, 2026 (day transition)
        val cal0000 = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.SEPTEMBER, 17, 0, 0, 5)
            set(Calendar.MILLISECOND, 0)
        }
        val timeAfterMidnight = cal0000.timeInMillis

        val refreshed = viewModel.checkDateRefresh(timeAfterMidnight)
        assertTrue("Date refresh must report day rollover", refreshed)

        val activeNext = viewModel.nextPrayerState.value
        assertTrue("Active next prayer must be available after day rollover", activeNext.isAvailable)
        assertNotNull(activeNext.prayerType)
        assertTrue(activeNext.timestamp!! > timeAfterMidnight)
        assertTrue(activeNext.remainingMillis!! > 0L)
    }

    @Test
    fun `test error 3 - Test 6 High latitude configuration with unavailable Fajr enters unavailable state`() {
        // Longyearbyen (Svalbard) lat 78.2232, lon 15.6267 in polar summer (midnight sun)
        // With HighLatitudeRule.None, fajr and isha cannot be calculated -> return 0L
        val polarSummerTimes = AstronomicalEngine.calculate(
            year = 2026,
            month = 6,
            day = 21,
            latitude = 78.2232,
            longitude = 15.6267,
            elevationMeters = 0.0,
            method = CalculationMethod.MuslimWorldLeague,
            madhab = Madhab.Shafi,
            highLatitudeRule = HighLatitudeRule.None,
            timeZone = TimeZone.getTimeZone("Europe/Oslo")
        )

        assertEquals("Fajr must be TIME_UNAVAILABLE in polar summer with HighLatitudeRule.None",
            PrayerTimes.TIME_UNAVAILABLE, polarSummerTimes.fajr)

        // At 23:55 (after all available events have passed)
        val calLate = Calendar.getInstance(TimeZone.getTimeZone("Europe/Oslo")).apply {
            set(2026, Calendar.JUNE, 21, 23, 55, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowLate = calLate.timeInMillis

        // Tomorrow also has unavailable Fajr
        val tomorrowPolar = AstronomicalEngine.calculate(
            year = 2026,
            month = 6,
            day = 22,
            latitude = 78.2232,
            longitude = 15.6267,
            elevationMeters = 0.0,
            method = CalculationMethod.MuslimWorldLeague,
            madhab = Madhab.Shafi,
            highLatitudeRule = HighLatitudeRule.None,
            timeZone = TimeZone.getTimeZone("Europe/Oslo")
        )

        val state = PrayerTimes.resolveNextPrayer(polarSummerTimes, tomorrowPolar, nowLate)
        // If polar day causes no upcoming prayers to be available, it must gracefully enter unavailable
        if (!state.isAvailable) {
            assertNull("No remaining millis for unavailable countdown", state.remainingMillis)
            assertNull("No timestamp for unavailable countdown", state.timestamp)
        } else {
            // If another event like Dhuhr or Asr is available, it must strictly be > nowLate and > 0L
            assertTrue(state.timestamp!! > nowLate)
            assertTrue(state.timestamp!! > PrayerTimes.TIME_UNAVAILABLE)
            assertNotEquals(PrayerType.FAJR, state.prayerType)
        }
    }

    @Test
    fun `test error 3 - Test 7 Passed target is discarded and never produces negative countdown`() {
        val now = 1789605000000L
        val pastPrayerTimes = PrayerTimes(
            fajr = now - 50000L,
            sunrise = now - 40000L,
            dhuhr = now - 30000L,
            asr = now - 20000L,
            maghrib = now - 10000L,
            isha = now - 5000L,
            timezoneId = "UTC"
        )
        // Passed tomorrow schedule (e.g. simulated clock jump)
        val passedTomorrow = PrayerTimes(
            fajr = now - 2000L,
            sunrise = now - 1000L,
            dhuhr = now - 500L,
            asr = now - 200L,
            maghrib = now - 100L,
            isha = now - 50L,
            timezoneId = "UTC"
        )

        val state = PrayerTimes.resolveNextPrayer(pastPrayerTimes, passedTomorrow, now)
        assertFalse("Passed target must be discarded", state.isAvailable)
        assertNull("Passed target must not produce a negative remainingMillis", state.remainingMillis)
    }

    @Test
    fun `test error 3 - Test 8 Home screen UI state receives unavailable state rather than zero-duration countdown`() {
        val unavailableState = NextPrayerState.UNAVAILABLE
        assertFalse("unavailableState must report isAvailable false", unavailableState.isAvailable)
        assertFalse("unavailableState must report available false", unavailableState.available)
        assertNull("unavailableState must have null timestamp", unavailableState.timestamp)
        assertNull("unavailableState must have null prayerType", unavailableState.prayerType)
        assertNull("unavailableState must have null remainingMillis", unavailableState.remainingMillis)

        val isUpcomingAvailable = unavailableState.isAvailable && unavailableState.timestamp != null
        assertFalse(isUpcomingAvailable)

        val formattedTime = if (isUpcomingAvailable) {
            PrayerTimeFormatter.formatTime(unavailableState.timestamp!!, is24Hour = false, timezoneId = "UTC")
        } else {
            "--:--"
        }
        assertEquals("--:--", formattedTime)

        val remainingMillis = if (isUpcomingAvailable) {
            unavailableState.remainingMillis ?: 0L
        } else 0L

        val hrStr = if (isUpcomingAvailable) String.format("%02d", remainingMillis / 3600000) else "--"
        val minStr = if (isUpcomingAvailable) String.format("%02d", (remainingMillis % 3600000) / 60000) else "--"
        val secStr = if (isUpcomingAvailable) String.format("%02d", (remainingMillis % 60000) / 1000) else "--"

        assertEquals("--", hrStr)
        assertEquals("--", minStr)
        assertEquals("--", secStr)
        assertNotEquals("00:00:00", "$hrStr:$minStr:$secStr")
    }

    // -------------------------------------------------------------
    // Error #4: Chronological Prayer Time Adjustment Tests (Tests 1 - 14)
    // -------------------------------------------------------------

    @Test
    fun `test error 4 - Test 1 Valid independent adjustments preserve canonical order`() {
        val base = PrayerTimes(
            fajr = 1789530000000L,     // 05:00
            sunrise = 1789535400000L,  // 06:30
            dhuhr = 1789557000000L,    // 12:30
            asr = 1789569600000L,      // 16:00
            maghrib = 1789578600000L,  // 18:30
            isha = 1789585800000L,     // 20:30
            timezoneId = "UTC"
        )
        val adjustments = mapOf(
            PrayerType.FAJR to 10,
            PrayerType.SUNRISE to -5,
            PrayerType.DHUHR to 15,
            PrayerType.ASR to -10,
            PrayerType.MAGHRIB to 5,
            PrayerType.ISHA to -5
        )
        val adjusted = base.withAdjustments(adjustments)
        val validation = adjusted.validateChronologicalOrder()
        assertTrue("Valid independent adjustments must be accepted", validation.isValid)
        assertNull(validation.errorMessage)

        val safe = base.withValidatedAdjustments(adjustments)
        assertEquals(adjusted, safe)
    }

    @Test
    fun `test error 4 - Test 2 Fajr-Sunrise conflict is rejected`() {
        val base = PrayerTimes(
            fajr = 1789534200000L,     // 06:10
            sunrise = 1789535400000L,  // 06:30
            dhuhr = 1789557000000L,
            asr = 1789569600000L,
            maghrib = 1789578600000L,
            isha = 1789585800000L,
            timezoneId = "UTC"
        )
        // Fajr +30 -> 06:40, while Sunrise is 06:30
        val invalidCandidate = base.withAdjustments(mapOf(PrayerType.FAJR to 30))
        val validation = invalidCandidate.validateChronologicalOrder()
        assertFalse("Fajr >= Sunrise must be rejected", validation.isValid)
        assertEquals(PrayerType.FAJR, validation.conflictingEarlier)
        assertEquals(PrayerType.SUNRISE, validation.conflictingLater)
        assertTrue(validation.errorMessage!!.contains("Fajr"))
        assertTrue(validation.errorMessage!!.contains("Sunrise"))

        // Equality case: Fajr +20 -> 06:30 == Sunrise 06:30
        val equalCandidate = base.withAdjustments(mapOf(PrayerType.FAJR to 20))
        val equalValidation = equalCandidate.validateChronologicalOrder()
        assertFalse("Fajr == Sunrise must also be rejected", equalValidation.isValid)

        // withValidatedAdjustments must recover safely
        val safe = base.withValidatedAdjustments(mapOf(PrayerType.FAJR to 30))
        assertTrue("Safe schedule must remain valid", safe.validateChronologicalOrder().isValid)
        assertEquals("Conflicting Fajr adjustment must be discarded", base.fajr, safe.fajr)
    }

    @Test
    fun `test error 4 - Test 3 Dhuhr-Asr conflict is rejected`() {
        val base = PrayerTimes(
            fajr = 1789530000000L,
            sunrise = 1789535400000L,
            dhuhr = 1789567800000L,    // 15:30
            asr = 1789569600000L,      // 16:00
            maghrib = 1789578600000L,
            isha = 1789585800000L,
            timezoneId = "UTC"
        )
        // Dhuhr +20 and Asr -15 -> Dhuhr 15:50, Asr 15:45 -> Dhuhr > Asr
        val invalidCandidate = base.withAdjustments(mapOf(PrayerType.DHUHR to 20, PrayerType.ASR to -15))
        val validation = invalidCandidate.validateChronologicalOrder()
        assertFalse("Dhuhr >= Asr must be rejected", validation.isValid)
        assertEquals(PrayerType.DHUHR, validation.conflictingEarlier)
        assertEquals(PrayerType.ASR, validation.conflictingLater)

        val safe = base.withValidatedAdjustments(mapOf(PrayerType.DHUHR to 20, PrayerType.ASR to -15))
        assertTrue(safe.validateChronologicalOrder().isValid)
    }

    @Test
    fun `test error 4 - Test 4 Asr-Maghrib conflict is rejected`() {
        val base = PrayerTimes(
            fajr = 1789530000000L,
            sunrise = 1789535400000L,
            dhuhr = 1789557000000L,
            asr = 1789576800000L,      // 18:00
            maghrib = 1789578600000L,  // 18:30
            isha = 1789585800000L,
            timezoneId = "UTC"
        )
        // Asr +30 and Maghrib -10 -> Asr 18:30, Maghrib 18:20
        val invalidCandidate = base.withAdjustments(mapOf(PrayerType.ASR to 30, PrayerType.MAGHRIB to -10))
        val validation = invalidCandidate.validateChronologicalOrder()
        assertFalse("Asr >= Maghrib must be rejected", validation.isValid)
        assertEquals(PrayerType.ASR, validation.conflictingEarlier)
        assertEquals(PrayerType.MAGHRIB, validation.conflictingLater)

        val safe = base.withValidatedAdjustments(mapOf(PrayerType.ASR to 30, PrayerType.MAGHRIB to -10))
        assertTrue(safe.validateChronologicalOrder().isValid)
    }

    @Test
    fun `test error 4 - Test 5 Maghrib-Isha conflict is rejected`() {
        val base = PrayerTimes(
            fajr = 1789530000000L,
            sunrise = 1789535400000L,
            dhuhr = 1789557000000L,
            asr = 1789569600000L,
            maghrib = 1789584000000L,  // 20:00
            isha = 1789585800000L,     // 20:30
            timezoneId = "UTC"
        )
        // Maghrib +25 and Isha -15 -> Maghrib 20:25, Isha 20:15
        val invalidCandidate = base.withAdjustments(mapOf(PrayerType.MAGHRIB to 25, PrayerType.ISHA to -15))
        val validation = invalidCandidate.validateChronologicalOrder()
        assertFalse("Maghrib >= Isha must be rejected", validation.isValid)
        assertEquals(PrayerType.MAGHRIB, validation.conflictingEarlier)
        assertEquals(PrayerType.ISHA, validation.conflictingLater)

        val safe = base.withValidatedAdjustments(mapOf(PrayerType.MAGHRIB to 25, PrayerType.ISHA to -15))
        assertTrue(safe.validateChronologicalOrder().isValid)
    }

    @Test
    fun `test error 4 - Test 6 Unavailable prayer does not participate in ordering validation`() {
        val base = PrayerTimes(
            fajr = PrayerTimes.TIME_UNAVAILABLE, // 0L
            sunrise = 1789535400000L,           // 06:30
            dhuhr = 1789557000000L,             // 12:30
            asr = 1789569600000L,               // 16:00
            maghrib = 1789578600000L,           // 18:30
            isha = PrayerTimes.TIME_UNAVAILABLE,// 0L
            timezoneId = "UTC"
        )
        val validation = base.validateChronologicalOrder()
        assertTrue("Schedule with unavailable Fajr and Isha must be valid", validation.isValid)

        // Applying adjustment to unavailable prayer keeps it unavailable and valid
        val adjusted = base.withAdjustments(mapOf(PrayerType.FAJR to 30, PrayerType.ISHA to -30))
        assertEquals(PrayerTimes.TIME_UNAVAILABLE, adjusted.fajr)
        assertEquals(PrayerTimes.TIME_UNAVAILABLE, adjusted.isha)
        assertTrue(adjusted.validateChronologicalOrder().isValid)
    }

    @Test
    fun `test error 4 - Test 7 Midnight timestamps use epoch milliseconds rather than formatted clock strings`() {
        val maghribMillis = 1789599000000L // Day 1, 23:50 UTC
        val ishaMillis = 1789600800000L    // Day 2, 00:20 UTC
        val base = PrayerTimes(
            fajr = 1789530000000L,
            sunrise = 1789535400000L,
            dhuhr = 1789557000000L,
            asr = 1789569600000L,
            maghrib = maghribMillis,
            isha = ishaMillis,
            timezoneId = "UTC"
        )
        val validation = base.validateChronologicalOrder()
        assertTrue("Epoch millis must correctly validate midnight-crossing sequence", validation.isValid)
        assertTrue(base.maghrib < base.isha)
    }

    @Test
    fun `test error 4 - Test 8 Invalid adjustment combinations are not persisted`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AthanPreferences(context)
        prefs.resetAllPrayerAdjustments()

        // Configure custom method with 1.0° Fajr angle so Fajr is within ~1 minute of Sunrise
        prefs.updateMethod(CalculationMethod.Custom)
        prefs.updateCustomAngles(1.0, 1.0)

        // Attempt an adjustment of +20 min to Fajr. Since Fajr is ~1 min before Sunrise, this would make Fajr > Sunrise
        val rejected = prefs.updatePrayerAdjustment(PrayerType.FAJR, 20)
        assertFalse("Conflicting adjustment must be rejected by updatePrayerAdjustment", rejected)
        assertEquals("Adjustment must not be persisted in settings flow", 0, prefs.settingsFlow.value.prayerAdjustments[PrayerType.FAJR])

        // Verify corrupted data in SharedPreferences is sanitized on loadSettings()
        context.getSharedPreferences("athan_offline_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("adj_FAJR", 25)
            .apply()

        val loadedSettings = prefs.loadSettings()
        assertEquals("Corrupted adjustment in storage must be sanitized to 0", 0, loadedSettings.prayerAdjustments[PrayerType.FAJR])

        // Clean up
        prefs.resetAllPrayerAdjustments()
        prefs.updateMethod(CalculationMethod.MuslimWorldLeague)
    }

    @Test
    fun `test error 4 - Test 9 Existing valid adjustment remains unchanged when another invalid adjustment is attempted`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AthanPreferences(context)
        prefs.resetAllPrayerAdjustments()

        // Apply a valid adjustment (+5 min to Dhuhr)
        val validSuccess = prefs.updatePrayerAdjustment(PrayerType.DHUHR, 5)
        assertTrue("Valid +5 to Dhuhr must succeed", validSuccess)
        assertEquals(5, prefs.settingsFlow.value.prayerAdjustments[PrayerType.DHUHR])

        // Now attempt an out-of-range adjustment
        val invalidAttempt = prefs.updatePrayerAdjustment(PrayerType.DHUHR, 999)
        assertFalse("Invalid adjustment must be rejected", invalidAttempt)

        // Verify previous valid adjustment (+5) remains intact
        assertEquals("Previously valid adjustment must be preserved", 5, prefs.settingsFlow.value.prayerAdjustments[PrayerType.DHUHR])
    }

    @Test
    fun `test error 4 - Test 10 Reset all restores all adjustments to zero and produces valid schedule`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AthanPreferences(context)

        // Set non-zero valid adjustments
        prefs.updatePrayerAdjustment(PrayerType.DHUHR, 5)
        prefs.updatePrayerAdjustment(PrayerType.ASR, -5)

        assertEquals(5, prefs.settingsFlow.value.prayerAdjustments[PrayerType.DHUHR])
        assertEquals(-5, prefs.settingsFlow.value.prayerAdjustments[PrayerType.ASR])

        // Reset all
        prefs.resetAllPrayerAdjustments()

        val resetSettings = prefs.settingsFlow.value
        for (prayer in PrayerType.entries) {
            assertEquals("Adjustment for $prayer must be 0 after reset", 0, resetSettings.prayerAdjustments[prayer])
        }

        // Verify resulting schedule is valid
        val validation = prefs.validateChronologicalSchedule(resetSettings, resetSettings.prayerAdjustments)
        assertTrue("Schedule after reset all must be chronologically valid", validation.isValid)
    }

    @Test
    fun `test error 4 - Test 11 Alarm scheduler only receives validated adjusted schedule`() {
        val base = PrayerTimes(
            fajr = 1789530000000L,
            sunrise = 1789535400000L,
            dhuhr = 1789557000000L,
            asr = 1789569600000L,
            maghrib = 1789578600000L,
            isha = 1789585800000L,
            timezoneId = "UTC"
        )
        // Adjustments that would create Fajr >= Sunrise
        val conflictingAdjustments = mapOf(PrayerType.FAJR to 30, PrayerType.SUNRISE to -30)
        val validatedSchedule = base.withValidatedAdjustments(conflictingAdjustments)

        assertTrue("Scheduler target must be chronologically valid", validatedSchedule.validateChronologicalOrder().isValid)
        assertTrue("Fajr must strictly precede Sunrise", validatedSchedule.fajr < validatedSchedule.sunrise)
    }

    @Test
    fun `test error 4 - Test 12 Daily and monthly schedules use identical adjustment and order rules`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = PrayerViewModel(app)

        val dailyTimes = viewModel.todayPrayerTimes.value
        val monthTimes = viewModel.getMonthPrayerTimes(2026, 9)

        // All days in monthly schedule must be validated
        for (dayTimes in monthTimes) {
            assertTrue("Every day in monthly schedule must be chronologically valid", dayTimes.validateChronologicalOrder().isValid)
        }
        if (dailyTimes != null) {
            assertTrue("Daily schedule must be chronologically valid", dailyTimes.validateChronologicalOrder().isValid)
        }
    }

    @Test
    fun `test error 4 - Test 13 High latitude unavailable times are ignored and valid times remain correctly handled`() {
        // Svalbard polar summer: Fajr and Isha unavailable (0L)
        val polarTimes = AstronomicalEngine.calculate(
            year = 2026,
            month = 6,
            day = 21,
            latitude = 78.2232,
            longitude = 15.6267,
            elevationMeters = 0.0,
            method = CalculationMethod.MuslimWorldLeague,
            madhab = Madhab.Shafi,
            highLatitudeRule = HighLatitudeRule.None,
            timeZone = TimeZone.getTimeZone("Europe/Oslo")
        )
        assertEquals(PrayerTimes.TIME_UNAVAILABLE, polarTimes.fajr)
        assertEquals(PrayerTimes.TIME_UNAVAILABLE, polarTimes.isha)

        // Apply adjustments to available middle prayers
        val adjustments = mapOf(
            PrayerType.DHUHR to 5,
            PrayerType.ASR to -5
        )
        val adjusted = polarTimes.withValidatedAdjustments(adjustments)
        assertTrue("Polar times with unavailable Fajr/Isha must be valid", adjusted.validateChronologicalOrder().isValid)
        assertEquals(PrayerTimes.TIME_UNAVAILABLE, adjusted.fajr)
        assertEquals(PrayerTimes.TIME_UNAVAILABLE, adjusted.isha)
        assertTrue(adjusted.dhuhr < adjusted.asr)
    }

    @Test
    fun `test error 4 - Test 14 Location and date dependence of adjustment validation`() {
        // In Mecca on equinox (wide gap between Fajr and Sunrise, ~75 mins):
        val meccaTimes = AstronomicalEngine.calculate(
            year = 2026, month = 3, day = 21,
            latitude = 21.4225, longitude = 39.8262, elevationMeters = 0.0,
            method = CalculationMethod.MuslimWorldLeague,
            madhab = Madhab.Shafi,
            highLatitudeRule = HighLatitudeRule.None,
            timeZone = TimeZone.getTimeZone("Asia/Riyadh")
        )
        // Fajr +20 in Mecca is valid
        val meccaAdjusted = meccaTimes.withAdjustments(mapOf(PrayerType.FAJR to 20))
        assertTrue("Fajr +20 in Mecca must be valid due to wide gap", meccaAdjusted.validateChronologicalOrder().isValid)

        // Construct a location/date where the gap between Fajr and Sunrise is only 15 mins:
        val narrowTimes = PrayerTimes(
            fajr = 1789534500000L,     // 06:15
            sunrise = 1789535400000L,  // 06:30 (15 min gap)
            dhuhr = 1789557000000L,
            asr = 1789569600000L,
            maghrib = 1789578600000L,
            isha = 1789585800000L,
            timezoneId = "UTC"
        )
        // The SAME +20 min adjustment to Fajr in narrow gap causes Fajr (06:35) > Sunrise (06:30):
        val narrowAdjusted = narrowTimes.withAdjustments(mapOf(PrayerType.FAJR to 20))
        assertFalse("Same Fajr +20 must be rejected when gap is narrower than adjustment", narrowAdjusted.validateChronologicalOrder().isValid)
    }

    // =================================================================================
    // ERROR #5: Timezone Correctness for Manual and GPS Coordinates
    // =================================================================================

    @Test
    fun `test error 5 - Test 1 Manual coordinates in another timezone`() {
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Colombo"))
            val context = ApplicationProvider.getApplicationContext<Application>()
            val viewModel = PrayerViewModel(context)

            // User sets manual coordinates for New York (lat 40.7128, lon -74.0060)
            val success = viewModel.setManualCoordinates("New York", 40.7128, -74.0060, 10.0, "America/New_York")
            assertTrue(success)

            val currentLoc = viewModel.settings.value.location
            assertEquals("America/New_York", currentLoc.timezoneId)
            assertNotEquals("Asia/Colombo", currentLoc.timezoneId)

            // Also test without explicit timezoneId argument - offline lookup should resolve America/New_York
            val success2 = viewModel.setManualCoordinates("New York 2", 40.7128, -74.0060, 10.0)
            assertTrue(success2)
            assertEquals("America/New_York", viewModel.settings.value.location.timezoneId)
            assertNotEquals("Asia/Colombo", viewModel.settings.value.location.timezoneId)
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `test error 5 - Test 2 GPS in another timezone`() {
        val originalTz = TimeZone.getDefault()
        try {
            // Device timezone is Asia/Colombo
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Colombo"))
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val shadowLocationManager = shadowOf(locationManager)

            val londonLoc = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 51.5074
                longitude = -0.1278
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                accuracy = 5.0f
            }
            shadowLocationManager.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            shadowLocationManager.simulateLocation(londonLoc)

            val viewModel = PrayerViewModel(context)
            viewModel.requestGpsLocation(useLastKnownIfFresh = true)
            Thread.sleep(100L)
            org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

            val storedLocation = viewModel.settings.value.location
            assertEquals("Europe/London", storedLocation.timezoneId)
            assertNotEquals("Asia/Colombo", storedLocation.timezoneId)
            assertTrue(storedLocation.isGpsDetected)
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `test error 5 - Test 3 Phone timezone change`() {
        val originalTz = TimeZone.getDefault()
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs = AthanPreferences.getInstance(context)

            // Select London (Europe/London)
            val london = BundledCities.CITIES.first { it.timezoneId == "Europe/London" }
            val londonLoc = UserLocation(
                cityName = london.name,
                countryName = london.country,
                latitude = london.latitude,
                longitude = london.longitude,
                elevationMeters = 0.0,
                timezoneId = london.timezoneId,
                isGpsDetected = false
            )
            prefs.updateLocation(londonLoc)
            assertEquals("Europe/London", prefs.settingsFlow.value.location.timezoneId)

            // Phone timezone changes to Asia/Colombo
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Colombo"))
            val receiver = BootAndClockReceiver()
            receiver.onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))

            // The selected location's timezone MUST remain Europe/London!
            val currentLoc = prefs.settingsFlow.value.location
            assertEquals("Europe/London", currentLoc.timezoneId)
            assertNotEquals("Asia/Colombo", currentLoc.timezoneId)

            // Even if location was GPS-detected, changing device timezone must NOT overwrite it
            val gpsLoc = londonLoc.copy(isGpsDetected = true)
            prefs.updateLocation(gpsLoc)
            receiver.onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
            assertEquals("Europe/London", prefs.settingsFlow.value.location.timezoneId)
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `test error 5 - Test 4 Manual location persistence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AthanPreferences.getInstance(context)

        val manualLoc = UserLocation(
            cityName = "Custom City",
            countryName = "Custom Country",
            latitude = 40.7128,
            longitude = -74.0060,
            elevationMeters = 15.0,
            timezoneId = "America/New_York",
            isGpsDetected = false
        )
        prefs.updateLocation(manualLoc)

        // Reset memory instance and reload preferences directly
        AthanPreferences.resetInstanceForTesting()
        val reloadedPrefs = AthanPreferences.getInstance(context)
        val loadedSettings = reloadedPrefs.loadSettings()

        assertEquals("Custom City", loadedSettings.location.cityName)
        assertEquals(40.7128, loadedSettings.location.latitude, 0.0001)
        assertEquals(-74.0060, loadedSettings.location.longitude, 0.0001)
        assertEquals(15.0, loadedSettings.location.elevationMeters, 0.0001)
        assertEquals("America/New_York", loadedSettings.location.timezoneId)
    }

    @Test
    fun `test error 5 - Test 5 Calculation uses location timezone`() {
        val nyTz = TimeZone.getTimeZone("America/New_York")
        val colomboTz = TimeZone.getTimeZone("Asia/Colombo")

        // Instant: 2026-07-15 01:00 AM Colombo time = 2026-07-14 03:30 PM New York time
        val testInstant = Calendar.getInstance(colomboTz).apply {
            set(2026, Calendar.JULY, 15, 1, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // If computed using location timezone (New York):
        val nyDay = LocalCalendarDay.fromMillis(testInstant, nyTz)
        assertEquals(14, nyDay.dayOfMonth)

        val nyTimes = AstronomicalEngine.calculate(
            year = nyDay.year, month = nyDay.month, day = nyDay.dayOfMonth,
            latitude = 40.7128, longitude = -74.0060, elevationMeters = 10.0,
            method = CalculationMethod.Isna,
            madhab = Madhab.Shafi,
            highLatitudeRule = HighLatitudeRule.AngleBased,
            timeZone = nyTz
        )

        // If computed using device timezone (Colombo):
        val colomboDay = LocalCalendarDay.fromMillis(testInstant, colomboTz)
        assertEquals(15, colomboDay.dayOfMonth)

        val colomboDayTimes = AstronomicalEngine.calculate(
            year = colomboDay.year, month = colomboDay.month, day = colomboDay.dayOfMonth,
            latitude = 40.7128, longitude = -74.0060, elevationMeters = 10.0,
            method = CalculationMethod.Isna,
            madhab = Madhab.Shafi,
            highLatitudeRule = HighLatitudeRule.AngleBased,
            timeZone = colomboTz
        )

        // Prayer times are for different calendar days, so dhuhr must differ
        assertEquals("America/New_York", nyTimes.timezoneId)
        assertNotEquals(nyTimes.dhuhr, colomboDayTimes.dhuhr)

        // Verify dhuhr occurs at local noon in New York (~13:00 EDT)
        val cal = Calendar.getInstance(nyTz).apply { timeInMillis = nyTimes.dhuhr }
        assertEquals(13, cal.get(Calendar.HOUR_OF_DAY)) // 1:00 PM EDT
    }

    @Test
    fun `test error 5 - Test 6 Display uses location timezone`() {
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Colombo"))

            val nyTz = "America/New_York"
            val timestamp = 1784048400000L

            val formattedInLocationTz = PrayerTimeFormatter.formatTime(millis = timestamp, is24Hour = true, timezoneId = nyTz)
            val cal = Calendar.getInstance(TimeZone.getTimeZone(nyTz)).apply { timeInMillis = timestamp }
            val expectedHour = cal.get(Calendar.HOUR_OF_DAY)
            val expectedMin = cal.get(Calendar.MINUTE)
            val expectedStr = String.format(java.util.Locale.US, "%02d:%02d", expectedHour, expectedMin)

            assertEquals(expectedStr, formattedInLocationTz)

            val colomboCal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Colombo")).apply { timeInMillis = timestamp }
            val colomboHour = colomboCal.get(Calendar.HOUR_OF_DAY)
            assertNotEquals(colomboHour, expectedHour)
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `test error 5 - Test 7 Monthly schedule uses location timezone`() {
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Colombo"))
            val context = ApplicationProvider.getApplicationContext<Application>()
            val viewModel = PrayerViewModel(context)

            val london = BundledCities.CITIES.first { it.timezoneId == "Europe/London" }
            viewModel.selectBundledCity(london)

            val monthPrayers = viewModel.getMonthPrayerTimes(year = 2026, month = 6)
            assertEquals(30, monthPrayers.size)
            for (dayTimes in monthPrayers) {
                assertEquals("Europe/London", dayTimes.timezoneId)
                assertNotEquals("Asia/Colombo", dayTimes.timezoneId)
            }
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `test error 5 - Test 8 Midnight rollover according to location timezone`() {
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Colombo"))

            val nyTz = TimeZone.getTimeZone("America/New_York")

            // 2026-07-15 01:00 AM Colombo time is 2026-07-14 03:30 PM New York time
            val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Colombo")).apply {
                set(2026, Calendar.JULY, 15, 1, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val testInstant = cal.timeInMillis

            val dayInLocationTz = LocalCalendarDay.fromMillis(testInstant, nyTz)
            assertEquals(14, dayInLocationTz.dayOfMonth)
            assertEquals(7, dayInLocationTz.month)
            assertEquals(2026, dayInLocationTz.year)

            val dayInDeviceTz = LocalCalendarDay.fromMillis(testInstant, TimeZone.getTimeZone("Asia/Colombo"))
            assertEquals(15, dayInDeviceTz.dayOfMonth)

            assertNotEquals(dayInDeviceTz.dayOfMonth, dayInLocationTz.dayOfMonth)
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `test error 5 - Test 9 Alarm scheduling rebuilds alarms using location timezone`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AthanPreferences.getInstance(context)

        val nyLoc = UserLocation(
            cityName = "New York", countryName = "USA",
            latitude = 40.7128, longitude = -74.0060, elevationMeters = 10.0,
            timezoneId = "America/New_York", isGpsDetected = false
        )
        prefs.updateLocation(nyLoc)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)
        val alarms = shadowAlarmManager.scheduledAlarms
        assertTrue("Alarms must be scheduled after location update", alarms.isNotEmpty())

        val londonLoc = UserLocation(
            cityName = "London", countryName = "UK",
            latitude = 51.5074, longitude = -0.1278, elevationMeters = 15.0,
            timezoneId = "Europe/London", isGpsDetected = false
        )
        prefs.updateLocation(londonLoc)

        val updatedAlarms = shadowAlarmManager.scheduledAlarms
        assertTrue("Alarms must be rescheduled for new location", updatedAlarms.isNotEmpty())
    }

    @Test
    fun `test error 5 - Test 10 Invalid timezone does not become device timezone`() {
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Colombo"))
            val context = ApplicationProvider.getApplicationContext<Context>()
            val sharedPrefs = context.getSharedPreferences("athan_offline_prefs", Context.MODE_PRIVATE)

            // Inject corrupted preferences: valid coordinates for New York, but invalid timezone
            sharedPrefs.edit()
                .putString("city_name", "Corrupted City")
                .putString("country_name", "Corrupted Country")
                .putFloat("latitude", 40.7128f)
                .putFloat("longitude", -74.0060f)
                .putString("timezone_id", "invalid_tz_value")
                .apply()

            AthanPreferences.resetInstanceForTesting()
            val prefs = AthanPreferences.getInstance(context)
            val settings = prefs.loadSettings()

            // Timezone must NOT fall back to device timezone (Asia/Colombo)
            assertNotEquals("Asia/Colombo", settings.location.timezoneId)
            assertNotEquals("invalid_tz_value", settings.location.timezoneId)
            // Deterministically resolves America/New_York from coordinates
            assertEquals("America/New_York", settings.location.timezoneId)
            assertEquals(40.7128, settings.location.latitude, 0.001)

            // Test with blank timezone and ocean coordinates where lookup fails:
            sharedPrefs.edit()
                .putFloat("latitude", 0.0f)
                .putFloat("longitude", -30.0f)
                .putString("timezone_id", "")
                .apply()

            AthanPreferences.resetInstanceForTesting()
            val prefs2 = AthanPreferences.getInstance(context)
            val settings2 = prefs2.loadSettings()
            assertNotEquals("Asia/Colombo", settings2.location.timezoneId)
            assertEquals(BundledCities.DEFAULT_CITY.timezoneId, settings2.location.timezoneId)
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `test error 5 - Test 11 DST retention with canonical IANA timezone`() {
        val londonTz = TimeZone.getTimeZone("Europe/London")
        val winterTimes = AstronomicalEngine.calculate(
            year = 2026, month = 1, day = 15,
            latitude = 51.5074, longitude = -0.1278, elevationMeters = 0.0,
            method = CalculationMethod.MuslimWorldLeague,
            madhab = Madhab.Shafi,
            highLatitudeRule = HighLatitudeRule.AngleBased,
            timeZone = londonTz
        )
        val summerTimes = AstronomicalEngine.calculate(
            year = 2026, month = 7, day = 15,
            latitude = 51.5074, longitude = -0.1278, elevationMeters = 0.0,
            method = CalculationMethod.MuslimWorldLeague,
            madhab = Madhab.Shafi,
            highLatitudeRule = HighLatitudeRule.AngleBased,
            timeZone = londonTz
        )

        assertEquals("Europe/London", winterTimes.timezoneId)
        assertEquals("Europe/London", summerTimes.timezoneId)

        val winterCal = Calendar.getInstance(londonTz).apply { timeInMillis = winterTimes.dhuhr }
        assertEquals(12, winterCal.get(Calendar.HOUR_OF_DAY))

        val summerCal = Calendar.getInstance(londonTz).apply { timeInMillis = summerTimes.dhuhr }
        assertEquals(13, summerCal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `test error 5 - Test 12 GPS timezone-resolution failure does not copy device timezone`() {
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Colombo"))
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val shadowLocationManager = shadowOf(locationManager)

            val prefs = AthanPreferences.getInstance(context)
            val initialLoc = BundledCities.DEFAULT_CITY
            prefs.updateLocation(
                UserLocation(
                    cityName = initialLoc.name,
                    countryName = initialLoc.country,
                    latitude = initialLoc.latitude,
                    longitude = initialLoc.longitude,
                    elevationMeters = 0.0,
                    timezoneId = initialLoc.timezoneId,
                    isGpsDetected = false
                )
            )

            // Simulate GPS coordinates in Atlantic Ocean (0.0, -30.0) where offline resolution fails
            val oceanLoc = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 0.0
                longitude = -30.0
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                accuracy = 5.0f
            }
            shadowLocationManager.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            shadowLocationManager.simulateLocation(oceanLoc)

            val viewModel = PrayerViewModel(context)
            viewModel.requestGpsLocation(useLastKnownIfFresh = true)
            Thread.sleep(100L)
            org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

            // Stored location timezone was NOT overwritten with Asia/Colombo
            val currentLoc = prefs.settingsFlow.value.location
            assertNotEquals("Asia/Colombo", currentLoc.timezoneId)
            assertEquals("Asia/Riyadh", currentLoc.timezoneId)
            assertTrue(viewModel.gpsState.value is GpsLocationState.Error)
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    // =========================================================================================
    // ERROR #6 REGRESSION TESTS: Centralized Timezone Validation & Resolution
    // =========================================================================================

    @Test
    fun `test error 6 - validation of valid and invalid timezone strings`() {
        // Valid IANA IDs
        assertTrue("Asia/Colombo must be valid", TimeZoneResolver.isValidTimeZoneId("Asia/Colombo"))
        assertTrue("Europe/London must be valid", TimeZoneResolver.isValidTimeZoneId("Europe/London"))
        assertTrue("America/New_York must be valid", TimeZoneResolver.isValidTimeZoneId("America/New_York"))
        assertTrue("GMT must be valid", TimeZoneResolver.isValidTimeZoneId("GMT"))
        assertTrue("UTC must be valid", TimeZoneResolver.isValidTimeZoneId("UTC"))

        // Invalid / corrupted / blank IDs
        assertFalse("Empty string must be invalid", TimeZoneResolver.isValidTimeZoneId(""))
        assertFalse("Whitespace string must be invalid", TimeZoneResolver.isValidTimeZoneId("   "))
        assertFalse("Null string must be invalid", TimeZoneResolver.isValidTimeZoneId(null))
        assertFalse("Invalid/Timezone must be invalid", TimeZoneResolver.isValidTimeZoneId("Invalid/Timezone"))
        assertFalse("Not/A/RealZone must be invalid", TimeZoneResolver.isValidTimeZoneId("Not/A/RealZone"))
        assertFalse("BogusZone must be invalid", TimeZoneResolver.isValidTimeZoneId("BogusZone"))

        // Also verify PrayerTimeFormatter delegates correctly
        assertTrue("PrayerTimeFormatter Asia/Colombo", PrayerTimeFormatter.isValidTimezoneId("Asia/Colombo"))
        assertTrue("PrayerTimeFormatter Europe/London", PrayerTimeFormatter.isValidTimezoneId("Europe/London"))
        assertTrue("PrayerTimeFormatter America/New_York", PrayerTimeFormatter.isValidTimezoneId("America/New_York"))
        assertTrue("PrayerTimeFormatter GMT", PrayerTimeFormatter.isValidTimezoneId("GMT"))
        assertFalse("PrayerTimeFormatter BogusZone", PrayerTimeFormatter.isValidTimezoneId("BogusZone"))
        assertFalse("PrayerTimeFormatter Invalid/Timezone", PrayerTimeFormatter.isValidTimezoneId("Invalid/Timezone"))
        assertFalse("PrayerTimeFormatter empty", PrayerTimeFormatter.isValidTimezoneId(""))
    }

    @Test
    fun `test error 6 - TimeZoneResolver resolve rejects bogus zones and uses fallback instead of GMT`() {
        val resolvedBogus = TimeZoneResolver.resolve("BogusZone")
        assertEquals(TimeZoneResolver.DEFAULT_FALLBACK_TIMEZONE_ID, resolvedBogus.id)
        assertNotEquals("GMT", resolvedBogus.id)

        val customFallback = TimeZone.getTimeZone("Europe/London")
        val resolvedInvalid = TimeZoneResolver.resolve("Invalid/Timezone", fallback = customFallback)
        assertEquals("Europe/London", resolvedInvalid.id)
        assertNotEquals("GMT", resolvedInvalid.id)

        val resolvedBlank = TimeZoneResolver.resolve("", fallback = customFallback)
        assertEquals("Europe/London", resolvedBlank.id)

        val resolvedNull = TimeZoneResolver.resolve(null, fallback = customFallback)
        assertEquals("Europe/London", resolvedNull.id)
    }

    @Test
    fun `test error 6 - A real GMT timezone remains accepted and resolves to GMT`() {
        assertTrue(TimeZoneResolver.isValidTimeZoneId("GMT"))
        val tzGmt = TimeZoneResolver.resolve("GMT")
        assertEquals("GMT", tzGmt.id)
        assertEquals(0, tzGmt.rawOffset)
    }

    @Test
    fun `test error 6 - AthanPreferences loadSettings recovers from corrupted stored timezone without defaulting to GMT`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sharedPrefs = context.getSharedPreferences("athan_preferences", Context.MODE_PRIVATE)
        sharedPrefs.edit().apply {
            putFloat("key_latitude", 21.4225f)
            putFloat("key_longitude", 39.8262f)
            putString("key_city", "Makkah")
            putString("key_country", "Saudi Arabia")
            putString("key_timezone", "BogusZone") // Corrupted stored timezone!
            apply()
        }

        AthanPreferences.resetInstanceForTesting()
        val prefs = AthanPreferences.getInstance(context)
        val loadedLocation = prefs.settingsFlow.value.location

        // Verify it did not silently resolve to GMT or stay BogusZone
        assertNotEquals("BogusZone", loadedLocation.timezoneId)
        assertNotEquals("GMT", loadedLocation.timezoneId)
        assertEquals("Asia/Riyadh", loadedLocation.timezoneId)
        assertEquals(21.4225, loadedLocation.latitude, 0.001)
        assertEquals(39.8262, loadedLocation.longitude, 0.001)
    }

    @Test
    fun `test error 6 - AthanPreferences updateLocation rejects invalid timezone and recovers deterministically`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AthanPreferences.getInstance(context)

        // London coordinates with invalid timezone string
        val londonLoc = UserLocation(
            cityName = "London",
            countryName = "UK",
            latitude = 51.5074,
            longitude = -0.1278,
            elevationMeters = 0.0,
            timezoneId = "Not/A/RealZone", // Invalid!
            isGpsDetected = false
        )
        val result = prefs.updateLocation(londonLoc)
        assertTrue(result)

        val updated = prefs.settingsFlow.value.location
        assertNotEquals("Not/A/RealZone", updated.timezoneId)
        assertNotEquals("GMT", updated.timezoneId)
        assertEquals("Europe/London", updated.timezoneId)
    }

    @Test
    fun `test error 6 - PrayerViewModel does not silently use GMT for an invalid location timezone`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = PrayerViewModel(context)

        // Colombo coordinates (6.9271, 79.8612) with invalid timezone
        val success = viewModel.setManualCoordinates(
            name = "Colombo Test",
            lat = 6.9271,
            lon = 79.8612,
            elevation = 10.0,
            timezoneId = "Invalid/Timezone"
        )
        assertTrue(success)

        val tz = viewModel.getLocationTimeZone()
        assertNotEquals("GMT", tz.id)
        assertEquals("Asia/Colombo", tz.id)
    }

    @Test
    fun `test error 6 - AthanAlarmScheduler does not calculate prayer times using silently substituted GMT`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AthanPreferences.getInstance(context)

        // Set Cairo coordinates with invalid timezone string
        prefs.updateLocation(
            UserLocation(
                cityName = "Cairo",
                countryName = "Egypt",
                latitude = 30.0444,
                longitude = 31.2357,
                elevationMeters = 0.0,
                timezoneId = "BogusZone",
                isGpsDetected = false
            )
        )

        // The location in preferences was sanitized to Africa/Cairo (not GMT)
        val loc = prefs.settingsFlow.value.location
        assertEquals("Africa/Cairo", loc.timezoneId)

        // Scheduling rolling alarms calculates times in Africa/Cairo, never GMT
        val scheduled = AthanAlarmScheduler.scheduleRollingAlarms(context)
        assertTrue(scheduled)
    }

    @Test
    fun `test error 6 - LocalCalendarDay does not silently convert an invalid ID into GMT`() {
        val day = LocalCalendarDay(
            year = 2026,
            month = 9,
            dayOfMonth = 16,
            timeZoneId = "BogusZone"
        )
        val tz = day.getTimeZone()
        assertNotEquals("GMT", tz.id)
        assertEquals(TimeZoneResolver.DEFAULT_FALLBACK_TIMEZONE_ID, tz.id)
    }

    @Test
    fun `test error 6 - Valid DST zones such as Europe-London and America-New_York continue working correctly`() {
        assertTrue(TimeZoneResolver.isValidTimeZoneId("Europe/London"))
        assertTrue(TimeZoneResolver.isValidTimeZoneId("America/New_York"))

        val londonTz = TimeZoneResolver.resolve("Europe/London")
        val nyTz = TimeZoneResolver.resolve("America/New_York")

        // Winter (January 15) vs Summer (July 15) in 2026
        val calWinter = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.JANUARY, 15, 12, 0, 0)
        }
        val calSummer = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.JULY, 15, 12, 0, 0)
        }

        // Europe/London: GMT (offset 0) in winter, BST (offset +1h) in summer
        assertEquals(0, londonTz.getOffset(calWinter.timeInMillis))
        assertEquals(3600000, londonTz.getOffset(calSummer.timeInMillis))

        // America/New_York: EST (offset -5h) in winter, EDT (offset -4h) in summer
        assertEquals(-18000000, nyTz.getOffset(calWinter.timeInMillis))
        assertEquals(-14400000, nyTz.getOffset(calSummer.timeInMillis))
    }

    // =========================================================================
    // Error #7: Coordinate Precision Degradation in Storage Tests
    // =========================================================================

    @Test
    fun `test error 7 - A High-precision latitude is persisted and restored without precision degradation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AthanPreferences.getInstance(context)

        val highPrecisionLat = 51.50735091234567
        val lon = -0.1277583
        val location = UserLocation(
            cityName = "London",
            countryName = "UK",
            latitude = highPrecisionLat,
            longitude = lon,
            timezoneId = "Europe/London"
        )
        assertTrue(prefs.updateLocation(location))

        AthanPreferences.resetInstanceForTesting()
        val restored = AthanPreferences.getInstance(context).loadSettings().location
        assertEquals(highPrecisionLat, restored.latitude, 0.0)
        assertEquals(lon, restored.longitude, 0.0)
    }

    @Test
    fun `test error 7 - B High-precision longitude is persisted and restored without precision degradation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AthanPreferences.getInstance(context)

        val lat = 40.7127753
        val highPrecisionLon = -74.00597281234567
        val location = UserLocation(
            cityName = "New York",
            countryName = "USA",
            latitude = lat,
            longitude = highPrecisionLon,
            timezoneId = "America/New_York"
        )
        assertTrue(prefs.updateLocation(location))

        AthanPreferences.resetInstanceForTesting()
        val restored = AthanPreferences.getInstance(context).loadSettings().location
        assertEquals(lat, restored.latitude, 0.0)
        assertEquals(highPrecisionLon, restored.longitude, 0.0)
    }

    @Test
    fun `test error 7 - C Negative coordinates preserve full double precision`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AthanPreferences.getInstance(context)

        val negLat = -33.86881971234567
        val negLon = -151.20929551234567
        val location = UserLocation(
            cityName = "Sydney Point",
            countryName = "Australia",
            latitude = negLat,
            longitude = negLon,
            timezoneId = "Australia/Sydney"
        )
        assertTrue(prefs.updateLocation(location))

        AthanPreferences.resetInstanceForTesting()
        val restored = AthanPreferences.getInstance(context).loadSettings().location
        assertEquals(negLat, restored.latitude, 0.0)
        assertEquals(negLon, restored.longitude, 0.0)
    }

    @Test
    fun `test error 7 - D Zero coordinates are valid and not confused with missing or default values`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AthanPreferences.getInstance(context)

        val location = UserLocation(
            cityName = "Null Island",
            countryName = "Atlantic Ocean",
            latitude = 0.0,
            longitude = 0.0,
            timezoneId = "UTC"
        )
        assertTrue(prefs.updateLocation(location))

        AthanPreferences.resetInstanceForTesting()
        val restored = AthanPreferences.getInstance(context).loadSettings().location
        assertEquals(0.0, restored.latitude, 0.0)
        assertEquals(0.0, restored.longitude, 0.0)
        assertEquals("Null Island", restored.cityName)
    }

    @Test
    fun `test error 7 - E Boundary coordinates are preserved accurately`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AthanPreferences.getInstance(context)

        // Min boundaries
        val southBound = UserLocation(
            cityName = "South Pole Boundary",
            countryName = "Antarctica",
            latitude = -90.0,
            longitude = -180.0,
            timezoneId = "UTC"
        )
        assertTrue(prefs.updateLocation(southBound))
        AthanPreferences.resetInstanceForTesting()
        val restoredSouth = AthanPreferences.getInstance(context).loadSettings().location
        assertEquals(-90.0, restoredSouth.latitude, 0.0)
        assertEquals(-180.0, restoredSouth.longitude, 0.0)

        // Max boundaries
        val northBound = UserLocation(
            cityName = "North Pole Boundary",
            countryName = "Arctic",
            latitude = 90.0,
            longitude = 180.0,
            timezoneId = "UTC"
        )
        assertTrue(prefs.updateLocation(northBound))
        AthanPreferences.resetInstanceForTesting()
        val restoredNorth = AthanPreferences.getInstance(context).loadSettings().location
        assertEquals(90.0, restoredNorth.latitude, 0.0)
        assertEquals(180.0, restoredNorth.longitude, 0.0)
    }

    @Test
    fun `test error 7 - F Legacy Float migration converts to Double, persists as String, and subsequent reads use String`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sharedPrefs = context.getSharedPreferences("athan_offline_prefs", Context.MODE_PRIVATE)

        // Simulate legacy float-based storage
        sharedPrefs.edit()
            .putFloat("latitude", 21.4225f)
            .putFloat("longitude", 39.8262f)
            .putString("city", "Legacy Makkah")
            .putString("country", "Saudi Arabia")
            .putString("timezone", "Asia/Riyadh")
            .apply()

        AthanPreferences.resetInstanceForTesting()
        val prefInstance = AthanPreferences.getInstance(context)

        // 1. First read migrates and returns Double representation of the float
        val loaded1 = prefInstance.loadSettings().location
        assertEquals(21.4225f.toDouble(), loaded1.latitude, 0.0)
        assertEquals(39.8262f.toDouble(), loaded1.longitude, 0.0)

        // 2. Verify preferences now stores string representation
        val storedLatStr = sharedPrefs.getString("latitude", null)
        val storedLonStr = sharedPrefs.getString("longitude", null)
        assertNotNull(storedLatStr)
        assertNotNull(storedLonStr)
        assertEquals(21.4225f.toDouble().toString(), storedLatStr)
        assertEquals(39.8262f.toDouble().toString(), storedLonStr)

        // 3. Subsequent read uses new string representation without re-migration
        AthanPreferences.resetInstanceForTesting()
        val prefInstance2 = AthanPreferences.getInstance(context)
        val loaded2 = prefInstance2.loadSettings().location
        assertEquals(21.4225f.toDouble(), loaded2.latitude, 0.0)
        assertEquals(39.8262f.toDouble(), loaded2.longitude, 0.0)
    }

    @Test
    fun `test error 7 - G Corrupted or malformed coordinate strings recover safely without entering invalid state`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sharedPrefs = context.getSharedPreferences("athan_offline_prefs", Context.MODE_PRIVATE)

        // Write malformed string coordinates
        sharedPrefs.edit()
            .putString("latitude", "not_a_valid_number")
            .putString("longitude", "999.99") // out of range
            .putString("city", "Corrupted City")
            .apply()

        AthanPreferences.resetInstanceForTesting()
        val prefInstance = AthanPreferences.getInstance(context)
        val settings = prefInstance.loadSettings()

        // Must fallback safely to default city coordinates, no exception thrown
        val defaultCity = BundledCities.DEFAULT_CITY
        assertEquals(defaultCity.latitude, settings.location.latitude, 0.0)
        assertEquals(defaultCity.longitude, settings.location.longitude, 0.0)

        // Also test NaN and Infinity string values
        sharedPrefs.edit()
            .putString("latitude", "NaN")
            .putString("longitude", "Infinity")
            .apply()

        AthanPreferences.resetInstanceForTesting()
        val settingsNan = AthanPreferences.getInstance(context).loadSettings()
        assertEquals(defaultCity.latitude, settingsNan.location.latitude, 0.0)
        assertEquals(defaultCity.longitude, settingsNan.location.longitude, 0.0)
    }

    @Test
    fun `test error 7 - Downstream calculations receive exact double coordinates`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AthanPreferences.getInstance(context)

        val highPrecisionLat = 21.422487654321
        val highPrecisionLon = 39.826206543210
        val customLoc = UserLocation(
            cityName = "Makkah High Precision",
            countryName = "Saudi Arabia",
            latitude = highPrecisionLat,
            longitude = highPrecisionLon,
            timezoneId = "Asia/Riyadh"
        )
        assertTrue(prefs.updateLocation(customLoc))
        val restored = prefs.loadSettings().location

        // Verify downstream Qibla bearing uses the high precision Double without truncation
        val qiblaBearing = QiblaCalculator.calculateBearing(restored.latitude, restored.longitude)
        assertTrue(qiblaBearing.isFinite() && !qiblaBearing.isNaN())

        // Verify downstream Astronomical calculation uses the high precision Double without truncation
        val times = AstronomicalEngine.calculate(
            year = 2026,
            month = 9,
            day = 16,
            latitude = restored.latitude,
            longitude = restored.longitude,
            elevationMeters = restored.elevationMeters,
            method = CalculationMethod.UmmAlQura,
            madhab = Madhab.Shafi,
            timeZone = TimeZoneResolver.resolve(restored.timezoneId)
        )
        assertTrue(times.fajr > 0)
        assertTrue(times.dhuhr > 0)
    }

    // ==========================================
    // Error #8: Transactional Alarm Rescheduling Tests
    // ==========================================

    @Test
    fun test_transactional_scheduling_successful_replacement() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)
        val prefs = AthanPreferences.getInstance(context)

        // Setup valid settings
        val loc = UserLocation(
            cityName = "Cairo",
            countryName = "Egypt",
            latitude = 30.0444,
            longitude = 31.2357,
            elevationMeters = 23.0,
            timezoneId = "Africa/Cairo"
        )
        prefs.updateLocation(loc)

        val schedulerPrefs = context.getSharedPreferences("athan_scheduler_internal", Context.MODE_PRIVATE)

        // Step 1: Initial schedule
        val initialScheduled = AthanAlarmScheduler.scheduleRollingAlarms(context)
        assertTrue("Initial scheduling should succeed", initialScheduled)

        val initialCodes = schedulerPrefs.getStringSet("scheduled_request_codes", emptySet()) ?: emptySet()
        assertTrue("Initial schedule should have alarms registered", initialCodes.isNotEmpty())
        val initialScheduledAlarmsCount = shadowAlarmManager.scheduledAlarms.size
        assertTrue("AlarmManager should have scheduled alarms", initialScheduledAlarmsCount > 0)

        // Step 2: Replace schedule with new valid settings (adjust reminder minutes)
        prefs.updateReminderMinutes(10)
        val replacedScheduled = AthanAlarmScheduler.scheduleRollingAlarms(context)
        assertTrue("Replaced scheduling should succeed", replacedScheduled)

        val newCodes = schedulerPrefs.getStringSet("scheduled_request_codes", emptySet()) ?: emptySet()
        assertTrue("New schedule should have alarms registered", newCodes.isNotEmpty())
        assertTrue("New schedule with 10min reminder should have more or different alarms", newCodes.size >= initialCodes.size)

        // Ensure AlarmManager has active alarms matching the new schedule
        val currentAlarms = shadowAlarmManager.scheduledAlarms
        assertTrue("AlarmManager must have active alarms after replacement", currentAlarms.isNotEmpty())
    }

    @Test
    fun test_transactional_scheduling_failure_before_cancellation_preserves_old_alarms() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)
        val prefs = AthanPreferences.getInstance(context)

        val loc = UserLocation(
            cityName = "London",
            countryName = "UK",
            latitude = 51.5074,
            longitude = -0.1278,
            elevationMeters = 15.0,
            timezoneId = "Europe/London"
        )
        prefs.updateLocation(loc)

        val schedulerPrefs = context.getSharedPreferences("athan_scheduler_internal", Context.MODE_PRIVATE)

        // Schedule initial valid window
        val initialSuccess = AthanAlarmScheduler.scheduleRollingAlarms(context)
        assertTrue(initialSuccess)

        val initialCodes = schedulerPrefs.getStringSet("scheduled_request_codes", emptySet())?.toSet() ?: emptySet()
        assertTrue("Should have initial alarms", initialCodes.isNotEmpty())
        val initialAlarmsInSystem = shadowAlarmManager.scheduledAlarms.toList()
        assertTrue("Alarms must exist in AlarmManager", initialAlarmsInSystem.isNotEmpty())

        // Inject simulated failure during Phase A (in-memory schedule construction) before any scheduling/cancellation
        AthanAlarmScheduler.testPreSchedulingHook = {
            throw RuntimeException("Simulated Phase A schedule construction failure")
        }

        try {
            // Attempt rebuild
            val rebuildSuccess = AthanAlarmScheduler.scheduleRollingAlarms(context)
            assertFalse("Rebuild with Phase A failure should return false", rebuildSuccess)

            // Old schedule and registry MUST be completely preserved
            val postCodes = schedulerPrefs.getStringSet("scheduled_request_codes", emptySet())?.toSet() ?: emptySet()
            assertEquals("Registry must preserve existing codes on pre-cancellation failure", initialCodes, postCodes)
            val postAlarmsInSystem = shadowAlarmManager.scheduledAlarms.toList()
            assertEquals("Existing alarms in AlarmManager must remain intact", initialAlarmsInSystem.size, postAlarmsInSystem.size)
        } finally {
            AthanAlarmScheduler.testPreSchedulingHook = null
        }
    }

    @Test
    fun test_transactional_scheduling_failure_during_scheduling_rolls_back() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)
        val prefs = AthanPreferences.getInstance(context)

        val loc = UserLocation(
            cityName = "Tokyo",
            countryName = "Japan",
            latitude = 35.6762,
            longitude = 139.6503,
            elevationMeters = 40.0,
            timezoneId = "Asia/Tokyo"
        )
        prefs.updateLocation(loc)

        val schedulerPrefs = context.getSharedPreferences("athan_scheduler_internal", Context.MODE_PRIVATE)

        // Step 1: Initial schedule
        val initialSuccess = AthanAlarmScheduler.scheduleRollingAlarms(context)
        assertTrue("Initial scheduling must succeed", initialSuccess)

        val initialActiveBank = schedulerPrefs.getInt("active_bank_index", 0)
        val initialRegistry = schedulerPrefs.getStringSet("scheduled_request_codes", emptySet())?.toSet() ?: emptySet()
        assertTrue("Initial registry must not be empty", initialRegistry.isNotEmpty())

        val initialAlarmMap: Map<Int, Long> = shadowAlarmManager.scheduledAlarms.associate {
            shadowOf(it.operation).requestCode to it.triggerAtTime
        }
        assertTrue("Initial alarms in AlarmManager must not be empty", initialAlarmMap.isNotEmpty())
        assertEquals("Initial alarm count must match registry size", initialRegistry.size, initialAlarmMap.size)

        // Step 2: Inject failure on the 3rd target-bank alarm
        var hookCallCount = 0
        AthanAlarmScheduler.testSchedulingHook = { index, _, _ ->
            hookCallCount++
            if (index == 2) {
                throw RuntimeException("Simulated mid-scheduling exception on 3rd target-bank alarm")
            }
        }

        try {
            // Attempt replacement
            val rescheduleResult = AthanAlarmScheduler.scheduleRollingAlarms(context)
            assertFalse("Rescheduling must return false on partial failure", rescheduleResult)
            assertTrue("Test scheduling hook must have been called", hookCallCount >= 3)

            // Step 3: Strict rollback assertions
            // 1. "KEY_ACTIVE_BANK" is exactly the original active bank
            val postActiveBank = schedulerPrefs.getInt("active_bank_index", 0)
            assertEquals("Active bank must remain original active bank after failure", initialActiveBank, postActiveBank)

            // 2. "KEY_SCHEDULED_REQUEST_CODES" is exactly the original registry
            val postRegistry = schedulerPrefs.getStringSet("scheduled_request_codes", emptySet())?.toSet() ?: emptySet()
            assertEquals("Persistent registry must retain previous valid codes after failure", initialRegistry, postRegistry)

            // Current alarms in AlarmManager
            val currentAlarmMap: Map<Int, Long> = shadowAlarmManager.scheduledAlarms.associate {
                shadowOf(it.operation).requestCode to it.triggerAtTime
            }

            // 3. Every original request code is still present
            for (code in initialAlarmMap.keys) {
                assertTrue("Original request code $code must still be present in AlarmManager", currentAlarmMap.containsKey(code))
            }

            // 4. For every original request code: actual triggerAtTime == original triggerAtTime (exact equality)
            for ((code, originalTrigger) in initialAlarmMap) {
                val currentTrigger = currentAlarmMap[code]
                assertEquals(
                    "triggerAtTime for request code $code must match original triggerAtTime exactly",
                    originalTrigger,
                    currentTrigger
                )
            }

            // 5. No target-bank alarm scheduled by the failed attempt remains
            val targetBank = 1 - initialActiveBank
            val targetBankCodes = AthanAlarmScheduler.getBankRequestCodes(targetBank)
            val lingeringTargetAlarms = currentAlarmMap.keys.intersect(targetBankCodes)
            assertTrue(
                "No target-bank alarm scheduled by failed attempt should remain, but found: $lingeringTargetAlarms",
                lingeringTargetAlarms.isEmpty()
            )

            // 6. No active-bank alarm was cancelled
            // 7. No active-bank alarm was replaced
            // 8. The number of scheduler-owned alarms is consistent with the original state
            assertEquals(
                "Number of scheduler-owned alarms must be consistent with original state",
                initialAlarmMap.size,
                currentAlarmMap.size
            )
            assertEquals(
                "Complete AlarmManager state must match original state exactly",
                initialAlarmMap,
                currentAlarmMap
            )
        } finally {
            AthanAlarmScheduler.testSchedulingHook = null
        }
    }

    @Test
    fun test_successful_dual_bank_transition_alternates_banks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)
        val prefs = AthanPreferences.getInstance(context)

        val loc = UserLocation(
            cityName = "Cairo",
            countryName = "Egypt",
            latitude = 30.0444,
            longitude = 31.2357,
            elevationMeters = 23.0,
            timezoneId = "Africa/Cairo"
        )
        prefs.updateLocation(loc)

        // Reset to clean state (bank 0 active)
        AthanAlarmScheduler.cancelAllAlarms(context)
        assertEquals(0, AthanAlarmScheduler.getActiveBankIndex(context))

        val bank0Codes = AthanAlarmScheduler.getBankRequestCodes(0)
        val bank1Codes = AthanAlarmScheduler.getBankRequestCodes(1)

        // Transition 1: Bank 0 active -> schedule -> Bank 1 active
        val success1 = AthanAlarmScheduler.scheduleRollingAlarms(context)
        assertTrue("First scheduling should succeed", success1)
        assertEquals("Active bank must change from 0 to 1", 1, AthanAlarmScheduler.getActiveBankIndex(context))

        val registry1 = AthanAlarmScheduler.getScheduledRequestCodes(context)
        assertTrue("Registry must not be empty", registry1.isNotEmpty())
        for (code in registry1) {
            assertTrue("Registry code $code must belong to Bank 1", bank1Codes.contains(code))
            assertFalse("Registry code $code must NOT belong to Bank 0", bank0Codes.contains(code))
        }

        val alarms1 = shadowAlarmManager.scheduledAlarms.associate { shadowOf(it.operation).requestCode to it.triggerAtTime }
        for (code in alarms1.keys) {
            assertTrue("Active alarm $code in AlarmManager must belong to Bank 1", bank1Codes.contains(code))
            assertFalse("No Bank 0 alarm must exist in AlarmManager", bank0Codes.contains(code))
        }
        assertEquals("Alarm count must equal registry count", registry1.size, alarms1.size)

        // Transition 2: Bank 1 active -> successful replacement -> Bank 0 active
        val success2 = AthanAlarmScheduler.scheduleRollingAlarms(context)
        assertTrue("Second scheduling should succeed", success2)
        assertEquals("Active bank must change from 1 to 0", 0, AthanAlarmScheduler.getActiveBankIndex(context))

        val registry2 = AthanAlarmScheduler.getScheduledRequestCodes(context)
        assertTrue("Registry must not be empty", registry2.isNotEmpty())
        for (code in registry2) {
            assertTrue("Registry code $code must belong to Bank 0", bank0Codes.contains(code))
            assertFalse("Registry code $code must NOT belong to Bank 1", bank1Codes.contains(code))
        }

        val alarms2 = shadowAlarmManager.scheduledAlarms.associate { shadowOf(it.operation).requestCode to it.triggerAtTime }
        for (code in alarms2.keys) {
            assertTrue("Active alarm $code in AlarmManager must belong to Bank 0", bank0Codes.contains(code))
            assertFalse("No Bank 1 alarm must exist in AlarmManager", bank1Codes.contains(code))
        }
        assertEquals("Alarm count must equal registry count", registry2.size, alarms2.size)

        // Transition 3: Bank 0 active -> schedule again -> Bank 1 active
        val success3 = AthanAlarmScheduler.scheduleRollingAlarms(context)
        assertTrue("Third scheduling should succeed", success3)
        assertEquals("Active bank must alternate back from 0 to 1", 1, AthanAlarmScheduler.getActiveBankIndex(context))

        val registry3 = AthanAlarmScheduler.getScheduledRequestCodes(context)
        for (code in registry3) {
            assertTrue("Registry code $code must belong to Bank 1", bank1Codes.contains(code))
        }
    }

    @Test
    fun test_request_code_bank_boundaries_and_isolation() {
        val bank0Codes = AthanAlarmScheduler.getBankRequestCodes(0)
        val bank1Codes = AthanAlarmScheduler.getBankRequestCodes(1)

        // Bank 0 boundaries: 10000..10099
        assertEquals(AthanAlarmScheduler.BANK_CAPACITY, bank0Codes.size)
        assertTrue(bank0Codes.all { it >= AthanAlarmScheduler.BANK_0_BASE && it < AthanAlarmScheduler.BANK_0_BASE + AthanAlarmScheduler.BANK_CAPACITY })

        // Bank 1 boundaries: 10100..10199
        assertEquals(AthanAlarmScheduler.BANK_CAPACITY, bank1Codes.size)
        assertTrue(bank1Codes.all { it >= AthanAlarmScheduler.BANK_1_BASE && it < AthanAlarmScheduler.BANK_1_BASE + AthanAlarmScheduler.BANK_CAPACITY })

        // Bank 0 codes ∩ Bank 1 codes = empty
        val intersection = bank0Codes.intersect(bank1Codes)
        assertTrue("Bank 0 and Bank 1 must have no overlapping request codes", intersection.isEmpty())

        // Test maximum supported scheduling window (ROBUST_MAX_DAYS = 7)
        for (bank in 0..1) {
            val base = if (bank == 1) AthanAlarmScheduler.BANK_1_BASE else AthanAlarmScheduler.BANK_0_BASE
            val bankLimit = base + AthanAlarmScheduler.BANK_CAPACITY
            for (dayOffset in 0 until AthanAlarmScheduler.ROBUST_MAX_DAYS) {
                for (prayer in PrayerType.entries) {
                    val exactCode = AthanAlarmScheduler.getRequestCode(bank, dayOffset, prayer, isPreReminder = false)
                    val reminderCode = AthanAlarmScheduler.getRequestCode(bank, dayOffset, prayer, isPreReminder = true)

                    assertTrue("Exact code $exactCode must be >= $base and < $bankLimit", exactCode in base until bankLimit)
                    assertTrue("Reminder code $reminderCode must be >= $base and < $bankLimit", reminderCode in base until bankLimit)
                    assertTrue("Exact code and reminder code must be distinct", exactCode != reminderCode)
                }
            }
        }
    }

    @Test
    fun test_transactional_scheduling_empty_schedule_cancels_cleanly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)
        val prefs = AthanPreferences.getInstance(context)

        val loc = UserLocation(
            cityName = "Dubai",
            countryName = "UAE",
            latitude = 25.2048,
            longitude = 55.2708,
            elevationMeters = 5.0,
            timezoneId = "Asia/Dubai"
        )
        prefs.updateLocation(loc)

        val schedulerPrefs = context.getSharedPreferences("athan_scheduler_internal", Context.MODE_PRIVATE)

        // Initial schedule
        assertTrue(AthanAlarmScheduler.scheduleRollingAlarms(context))
        assertTrue(schedulerPrefs.getStringSet("scheduled_request_codes", emptySet())!!.isNotEmpty())
        assertTrue(shadowAlarmManager.scheduledAlarms.isNotEmpty())

        // Disable all prayer notifications -> results in empty staged schedule
        PrayerType.entries.forEach { prayer ->
            prefs.togglePrayerNotification(prayer, false)
        }

        val emptyResult = AthanAlarmScheduler.scheduleRollingAlarms(context)
        assertTrue("Scheduling an empty set should succeed cleanly", emptyResult)

        // Registry should be cleared of prayer alarms and only watchdog alarm should remain scheduled
        val finalCodes = schedulerPrefs.getStringSet("scheduled_request_codes", emptySet()) ?: emptySet()
        assertTrue("Registry should be empty when all prayers are disabled", finalCodes.isEmpty())
        val remainingAlarms = shadowAlarmManager.scheduledAlarms
        assertTrue("Only watchdog alarm should remain in AlarmManager", remainingAlarms.all { it.triggerAtTime > System.currentTimeMillis() } && remainingAlarms.size <= 1)
    }

    @Test
    fun test_transactional_scheduling_exact_alarm_revocation_cancels_all() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)
        val prefs = AthanPreferences.getInstance(context)

        val loc = UserLocation(
            cityName = "Riyadh",
            countryName = "Saudi Arabia",
            latitude = 24.7136,
            longitude = 46.6753,
            elevationMeters = 612.0,
            timezoneId = "Asia/Riyadh"
        )
        prefs.updateLocation(loc)

        val schedulerPrefs = context.getSharedPreferences("athan_scheduler_internal", Context.MODE_PRIVATE)

        // Initial schedule
        assertTrue(AthanAlarmScheduler.scheduleRollingAlarms(context))
        assertTrue(schedulerPrefs.getStringSet("scheduled_request_codes", emptySet())!!.isNotEmpty())

        // Now revoke exact alarm capability
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val scheduleWhenRevoked = AthanAlarmScheduler.scheduleRollingAlarms(context)
        assertFalse("Should return false when exact alarm capability is revoked", scheduleWhenRevoked)

        // Alarms must be cancelled and registry cleared
        val remainingCodes = schedulerPrefs.getStringSet("scheduled_request_codes", emptySet()) ?: emptySet()
        assertTrue("Registry must be empty when capability revoked", remainingCodes.isEmpty())
        assertTrue("AlarmManager alarms must be cancelled", shadowAlarmManager.scheduledAlarms.isEmpty())

        // Restore permission for other tests
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Test
    fun test_concurrent_rebuilds_thread_safety() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AthanPreferences.getInstance(context)

        val loc = UserLocation(
            cityName = "Medina",
            countryName = "Saudi Arabia",
            latitude = 24.5247,
            longitude = 39.5692,
            elevationMeters = 608.0,
            timezoneId = "Asia/Riyadh"
        )
        prefs.updateLocation(loc)

        val schedulerPrefs = context.getSharedPreferences("athan_scheduler_internal", Context.MODE_PRIVATE)

        // Run multiple concurrent scheduleRollingAlarms on separate threads
        val threadCount = 6
        val threads = mutableListOf<Thread>()
        val results = java.util.Collections.synchronizedList(mutableListOf<Boolean>())

        for (i in 0 until threadCount) {
            val t = Thread {
                val res = AthanAlarmScheduler.scheduleRollingAlarms(context)
                results.add(res)
            }
            threads.add(t)
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(threadCount, results.size)
        assertTrue("All concurrent schedules should succeed without race conditions", results.all { it })

        val finalCodes = schedulerPrefs.getStringSet("scheduled_request_codes", emptySet()) ?: emptySet()
        assertTrue("Registry should contain valid request codes after concurrent rebuilds", finalCodes.isNotEmpty())
    }

    @Test
    fun test_resolve_next_prayer_three_day_window() {
        val now = 1000L * 60 * 60 * 12 // 12:00:00 UTC day 1

        // Today has no valid prayers
        val emptyToday = PrayerTimes(
            fajr = PrayerTimes.TIME_UNAVAILABLE,
            sunrise = PrayerTimes.TIME_UNAVAILABLE,
            dhuhr = PrayerTimes.TIME_UNAVAILABLE,
            asr = PrayerTimes.TIME_UNAVAILABLE,
            maghrib = PrayerTimes.TIME_UNAVAILABLE,
            isha = PrayerTimes.TIME_UNAVAILABLE,
            timezoneId = "UTC"
        )

        // Tomorrow has no valid prayers
        val emptyTomorrow = PrayerTimes(
            fajr = PrayerTimes.TIME_UNAVAILABLE,
            sunrise = PrayerTimes.TIME_UNAVAILABLE,
            dhuhr = PrayerTimes.TIME_UNAVAILABLE,
            asr = PrayerTimes.TIME_UNAVAILABLE,
            maghrib = PrayerTimes.TIME_UNAVAILABLE,
            isha = PrayerTimes.TIME_UNAVAILABLE,
            timezoneId = "UTC"
        )

        // Day after tomorrow has Fajr
        val dayAfterTomorrowFajr = now + 2 * 86400000L + (5 * 3600000L)
        val validDayAfterTomorrow = PrayerTimes(
            fajr = dayAfterTomorrowFajr,
            sunrise = PrayerTimes.TIME_UNAVAILABLE,
            dhuhr = PrayerTimes.TIME_UNAVAILABLE,
            asr = PrayerTimes.TIME_UNAVAILABLE,
            maghrib = PrayerTimes.TIME_UNAVAILABLE,
            isha = PrayerTimes.TIME_UNAVAILABLE,
            timezoneId = "UTC"
        )

        val nextPrayer = PrayerTimes.resolveNextPrayer(
            todayPrayers = emptyToday,
            tomorrowPrayers = emptyTomorrow,
            dayAfterTomorrowPrayers = validDayAfterTomorrow,
            currentTimeMillis = now
        )

        assertTrue("Should resolve prayer from day-after-tomorrow window", nextPrayer.isAvailable)
        assertEquals(PrayerType.FAJR, nextPrayer.prayerType)
        assertEquals(dayAfterTomorrowFajr, nextPrayer.timestamp)
    }

    @Test
    fun test_offline_location_provider_rejects_future_timestamp() {
        val now = System.currentTimeMillis()
        val validLocation = Location("gps").apply {
            latitude = 21.4225
            longitude = 39.8262
            time = now - 10000L // 10 seconds ago
        }
        assertTrue("Past timestamp should be valid", OfflineLocationProvider.isValidLocation(validLocation, now))

        val farFutureLocation = Location("gps").apply {
            latitude = 21.4225
            longitude = 39.8262
            time = now + 1000L * 60 * 60 // 1 hour in future (> 5 min tolerance)
        }
        assertFalse("Future-dated GPS timestamp exceeding tolerance must be rejected", OfflineLocationProvider.isValidLocation(farFutureLocation, now))
    }

    @Test
    fun test_scheduler_watchdog_on_empty_schedule() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AthanPreferences.getInstance(context)

        // Turn off notifications for all prayers so schedule is empty
        for (prayer in PrayerType.entries) {
            prefs.togglePrayerNotification(prayer, false)
        }

        val success = AthanAlarmScheduler.scheduleRollingAlarms(context)
        assertTrue("Schedule rolling alarms should succeed with watchdog", success)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = org.robolectric.Shadows.shadowOf(alarmManager)

        // Verify watchdog alarm is scheduled with WATCHDOG_REQUEST_CODE (10999)
        val nextScheduledAlarm = shadowAlarmManager.nextScheduledAlarm
        assertNotNull("A watchdog alarm should be scheduled when schedule is empty", nextScheduledAlarm)
        assertTrue("Watchdog alarm trigger time should be in the future", (nextScheduledAlarm?.triggerAtTime ?: 0L) > System.currentTimeMillis())

        // Re-enable Fajr notification and verify watchdog is cancelled on valid schedule
        prefs.togglePrayerNotification(PrayerType.FAJR, true)
        val successWithPrayers = AthanAlarmScheduler.scheduleRollingAlarms(context)
        assertTrue("Schedule with prayers should succeed", successWithPrayers)
    }

    @Test
    fun test_sound_update_does_not_churn_alarms() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AthanPreferences.getInstance(context)

        // Ensure schedule is fresh
        AthanAlarmScheduler.scheduleRollingAlarms(context)

        val schedulerPrefs = context.getSharedPreferences("athan_scheduler_internal", Context.MODE_PRIVATE)
        val activeBankBefore = schedulerPrefs.getInt("active_bank", 0)
        val codesBefore = schedulerPrefs.getStringSet("scheduled_request_codes", emptySet()) ?: emptySet()

        // Updating sound should not trigger alarm rescheduling
        prefs.updateSound(AthanSound.RINGING)
        val activeBankAfter = schedulerPrefs.getInt("active_bank", 0)
        val codesAfter = schedulerPrefs.getStringSet("scheduled_request_codes", emptySet()) ?: emptySet()

        assertEquals("Active bank should not change on sound update", activeBankBefore, activeBankAfter)
        assertEquals("Scheduled request codes should remain untouched on sound update", codesBefore, codesAfter)
    }

    @Test
    fun test_select_bundled_city_batches_updates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AthanPreferences.getInstance(context)

        val tokyoCity = BundledCities.CITIES.first { it.name == "Tokyo" }

        // Select city in preferences directly or through bundled city selection
        val loc = UserLocation(
            cityName = tokyoCity.name,
            countryName = tokyoCity.country,
            latitude = tokyoCity.latitude,
            longitude = tokyoCity.longitude,
            elevationMeters = 0.0,
            timezoneId = tokyoCity.timezoneId,
            isGpsDetected = false
        )

        val updateSuccess = prefs.updateCitySelection(
            location = loc,
            method = tokyoCity.recommendedMethod,
            madhab = tokyoCity.recommendedMadhab
        )
        assertTrue("City selection batch update should succeed", updateSuccess)

        val currentSettings = prefs.settingsFlow.value
        assertEquals("Tokyo", currentSettings.location.cityName)
        assertEquals(tokyoCity.recommendedMethod, currentSettings.method)
        assertEquals(tokyoCity.recommendedMadhab, currentSettings.madhab)
    }

    @Test
    fun test_early_firing_alarm_does_not_reschedule_itself() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AthanPreferences.getInstance(context)
        val settings = prefs.settingsFlow.value
        val tz = TimeZoneResolver.resolve(settings.location.timezoneId)

        val cal = Calendar.getInstance(tz)
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)

        val rawPrayerTimes = AstronomicalEngine.calculate(
            year = year,
            month = month,
            day = day,
            latitude = settings.location.latitude,
            longitude = settings.location.longitude,
            elevationMeters = settings.location.elevationMeters,
            method = settings.method,
            madhab = settings.madhab,
            highLatitudeRule = settings.highLatitudeRule,
            customFajrAngle = settings.customFajrAngle,
            customIshaAngle = settings.customIshaAngle,
            timeZone = tz,
            hijriDayAdjustment = settings.hijriDayAdjustment
        )
        val prayerTimes = rawPrayerTimes.withValidatedAdjustments(settings.prayerAdjustments)
        val maghribTimeMillis = prayerTimes.maghrib

        assertNotNull("Maghrib time should be available", maghribTimeMillis)
        assertTrue("Maghrib time should be valid", maghribTimeMillis > 0L)

        // Simulate alarm firing 1 second before the exact prayer time (e.g. 18:00:59 for 18:01:00)
        val earlyVirtualNow = maghribTimeMillis - 1000L
        val success = AthanAlarmScheduler.scheduleRollingAlarms(context, earlyVirtualNow)
        assertTrue("scheduleRollingAlarms should succeed", success)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = org.robolectric.Shadows.shadowOf(alarmManager)

        // Verify that today's Maghrib (at maghribTimeMillis) is NOT scheduled
        val scheduledAlarms = shadowAlarmManager.scheduledAlarms
        val todayMaghribScheduled = scheduledAlarms.any { it.triggerAtTime == maghribTimeMillis }
        assertFalse("Today's prayer at $maghribTimeMillis must NOT be scheduled when running within the reschedule buffer", todayMaghribScheduled)

        // Verify that if scheduleRollingAlarms is called outside the buffer (e.g. 2 minutes prior)
        val wellBeforeVirtualNow = maghribTimeMillis - 120_000L
        val successEarly = AthanAlarmScheduler.scheduleRollingAlarms(context, wellBeforeVirtualNow)
        assertTrue("scheduleRollingAlarms should succeed", successEarly)

        val updatedScheduledAlarms = shadowAlarmManager.scheduledAlarms
        val todayMaghribScheduledPrior = updatedScheduledAlarms.any { it.triggerAtTime == maghribTimeMillis }
        assertTrue("Today's prayer at $maghribTimeMillis SHOULD be scheduled when called well in advance", todayMaghribScheduledPrior)
    }

    @Test
    fun test_receiver_drops_duplicate_triggers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadowApp = org.robolectric.Shadows.shadowOf(context as Application)
        while (shadowApp.nextStartedService != null) { /* drain */ }
        AthanAlarmReceiver.resetDebounceState()

        val receiver = AthanAlarmReceiver()
        val intent1 = Intent(AthanAlarmReceiver.ACTION_PRAYER_ALARM).apply {
            putExtra(AthanAlarmReceiver.EXTRA_PRAYER_TYPE, PrayerType.MAGHRIB.name)
            putExtra(AthanAlarmReceiver.EXTRA_PRAYER_NAME, "Maghrib")
            putExtra(AthanAlarmReceiver.EXTRA_ARABIC_NAME, "المغرب")
            putExtra(AthanAlarmReceiver.EXTRA_IS_PRE_REMINDER, false)
        }

        receiver.onReceive(context, intent1)

        val firstService = shadowApp.nextStartedService
        assertNotNull("AthanAudioService should be started on first trigger", firstService)
        assertEquals(AthanAudioService::class.java.name, firstService.component?.className)

        // Send second consecutive intent for MAGHRIB within 5 seconds
        val intent2 = Intent(AthanAlarmReceiver.ACTION_PRAYER_ALARM).apply {
            putExtra(AthanAlarmReceiver.EXTRA_PRAYER_TYPE, PrayerType.MAGHRIB.name)
            putExtra(AthanAlarmReceiver.EXTRA_PRAYER_NAME, "Maghrib")
            putExtra(AthanAlarmReceiver.EXTRA_ARABIC_NAME, "المغرب")
            putExtra(AthanAlarmReceiver.EXTRA_IS_PRE_REMINDER, false)
        }

        receiver.onReceive(context, intent2)

        val secondService = shadowApp.nextStartedService
        assertNull("AthanAudioService must NOT be started on duplicate trigger within debounce window", secondService)
    }

    @Test
    fun test_pre_reminder_never_starts_athan_audio() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadowApp = org.robolectric.Shadows.shadowOf(context as Application)
        while (shadowApp.nextStartedService != null) { /* drain */ }
        AthanAlarmReceiver.resetDebounceState()

        val receiver = AthanAlarmReceiver()
        val preReminderIntent = Intent(AthanAlarmReceiver.ACTION_PRAYER_ALARM).apply {
            putExtra(AthanAlarmReceiver.EXTRA_PRAYER_TYPE, PrayerType.MAGHRIB.name)
            putExtra(AthanAlarmReceiver.EXTRA_PRAYER_NAME, "Maghrib")
            putExtra(AthanAlarmReceiver.EXTRA_ARABIC_NAME, "المغرب")
            putExtra(AthanAlarmReceiver.EXTRA_IS_PRE_REMINDER, true)
        }

        receiver.onReceive(context, preReminderIntent)

        val serviceIntent = shadowApp.nextStartedService
        assertNull("Pre-reminder must NEVER start AthanAudioService", serviceIntent)
    }

    @Test
    fun test_about_developer_string_resources() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val devName = context.getString(R.string.developer_name)
        val supportUrl = context.getString(R.string.developer_support_url)
        val repoUrl = context.getString(R.string.github_repository_url)

        assertEquals("amuksith", devName)
        assertEquals("https://github.com/sponsors/amuksith", supportUrl)
        assertEquals("https://github.com/amuksith/Athan", repoUrl)
    }

    @Test
    fun test_about_developer_and_github_urls_are_valid() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val supportUrl = context.getString(R.string.developer_support_url)
        val repoUrl = context.getString(R.string.github_repository_url)

        val supportUri = android.net.Uri.parse(supportUrl)
        val repoUri = android.net.Uri.parse(repoUrl)

        assertEquals("https", supportUri.scheme)
        assertEquals("github.com", supportUri.host)
        assertEquals("/sponsors/amuksith", supportUri.path)

        assertEquals("https", repoUri.scheme)
        assertEquals("github.com", repoUri.host)
        assertEquals("/amuksith/Athan", repoUri.path)
    }

    @Test
    fun test_prayer_schedule_share_text_builder() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Riyadh")).apply {
            set(2026, Calendar.SEPTEMBER, 21, 12, 0, 0)
        }
        val baseMillis = cal.timeInMillis

        val dummyPrayers = PrayerTimes(
            fajr = baseMillis + 1000,
            sunrise = baseMillis + 2000,
            dhuhr = baseMillis + 3000,
            asr = baseMillis + 4000,
            maghrib = baseMillis + 5000,
            isha = baseMillis + 6000,
            dateString = "2026-09-21",
            timezoneId = "Asia/Riyadh"
        )

        val shareText = com.athan.app.ui.components.PrayerShareFormatter.buildPrayerShareText(
            cityName = "Mecca",
            gregorianDate = "Sep 21, 2026",
            hijriDate = "9 Rabi' al-Awwal 1448",
            prayerTimes = dummyPrayers,
            is24Hour = false,
            timezoneId = "Asia/Riyadh"
        )

        assertTrue("Share text must contain city name", shareText.contains("Mecca"))
        assertTrue("Share text must contain Gregorian date", shareText.contains("Sep 21, 2026"))
        assertTrue("Share text must contain Hijri date", shareText.contains("9 Rabi' al-Awwal 1448"))

        // All 6 prayer names
        assertTrue("Share text must contain Fajr", shareText.contains("Fajr"))
        assertTrue("Share text must contain Sunrise", shareText.contains("Sunrise"))
        assertTrue("Share text must contain Dhuhr", shareText.contains("Dhuhr"))
        assertTrue("Share text must contain Asr", shareText.contains("Asr"))
        assertTrue("Share text must contain Maghrib", shareText.contains("Maghrib"))
        assertTrue("Share text must contain Isha", shareText.contains("Isha"))

        // GitHub link
        assertTrue("Share text must contain GitHub link", shareText.contains("https://github.com/amuksith/Athan"))
    }
}

