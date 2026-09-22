package com.athan.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.athan.app.core.astronomy.CalculationMethod
import com.athan.app.core.astronomy.HighLatitudeRule
import com.athan.app.core.astronomy.Madhab
import com.athan.app.core.astronomy.PrayerType
import com.athan.app.core.astronomy.AstronomicalEngine
import com.athan.app.core.astronomy.ChronologicalValidationResult
import com.athan.app.core.astronomy.LocalCalendarDay
import com.athan.app.core.astronomy.PrayerTimeFormatter
import com.athan.app.core.astronomy.TimeZoneResolver
import com.athan.app.data.location.BundledCities
import com.athan.app.data.location.OfflineTimezoneLookup
import com.athan.app.data.location.UserLocation
import com.athan.app.scheduling.AthanAlarmScheduler
import com.athan.app.util.InputValidator
import com.athan.app.audio.AthanAudioCatalog
import com.athan.app.audio.AthanSound
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AthanSettings(
    val location: UserLocation,
    val method: CalculationMethod,
    val madhab: Madhab,
    val highLatitudeRule: HighLatitudeRule,
    val customFajrAngle: Double,
    val customIshaAngle: Double,
    val sound: AthanSound,
    val prayerNotifications: Map<PrayerType, Boolean>,
    val reminderMinutesBefore: Int,
    val is24HourFormat: Boolean,
    val hijriDayAdjustment: Int,
    val prayerAdjustments: Map<PrayerType, Int> = PrayerType.entries.associateWith { 0 }
)

class AthanPreferences(context: Context) {
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("athan_offline_prefs", Context.MODE_PRIVATE)

    private val _settingsFlow = MutableStateFlow(loadSettings())
    val settingsFlow: StateFlow<AthanSettings> = _settingsFlow.asStateFlow()

    fun loadSettings(): AthanSettings {
        val defaultCity = BundledCities.DEFAULT_CITY

        val (safeLat, migratedLat) = readAndMigrateCoordinate(KEY_LAT, defaultCity.latitude, InputValidator::isValidLatitude)
        val (safeLon, migratedLon) = readAndMigrateCoordinate(KEY_LON, defaultCity.longitude, InputValidator::isValidLongitude)

        if (migratedLat != null || migratedLon != null) {
            val editor = prefs.edit()
            if (migratedLat != null) {
                editor.putString(KEY_LAT, migratedLat.toString())
            }
            if (migratedLon != null) {
                editor.putString(KEY_LON, migratedLon.toString())
            }
            editor.apply()
        }

        val rawElevation = prefs.getFloat(KEY_ELEVATION, 0.0f).toDouble()
        val safeElevation = if (InputValidator.isValidElevation(rawElevation)) rawElevation else 0.0

        val rawTz = prefs.getString(KEY_TIMEZONE, null)
        val safeTz = if (TimeZoneResolver.isValidTimeZoneId(rawTz)) {
            rawTz!!.trim()
        } else {
            // Unrecognized or invalid timezone string (e.g. "Invalid/Timezone", "Not/A/RealZone", "BogusZone", or blank)
            // MUST NOT silently resolve to GMT or device timezone.
            val recoveredTz = OfflineTimezoneLookup.lookup(safeLat, safeLon) ?: defaultCity.timezoneId
            Log.w(TAG, "Persisted timezone '$rawTz' is invalid or unrecognized; recovering deterministically to '$recoveredTz' for coordinates ($safeLat, $safeLon)")
            recoveredTz
        }

        val location = UserLocation(
            cityName = prefs.getString(KEY_CITY, defaultCity.name) ?: defaultCity.name,
            countryName = prefs.getString(KEY_COUNTRY, defaultCity.country) ?: defaultCity.country,
            latitude = safeLat,
            longitude = safeLon,
            elevationMeters = safeElevation,
            timezoneId = safeTz,
            isGpsDetected = prefs.getBoolean(KEY_IS_GPS, false)
        )

        val methodStr = prefs.getString(KEY_METHOD, defaultCity.recommendedMethod.name) ?: defaultCity.recommendedMethod.name
        val method = try { CalculationMethod.valueOf(methodStr) } catch (e: Exception) { defaultCity.recommendedMethod }

        val madhabStr = prefs.getString(KEY_MADHAB, defaultCity.recommendedMadhab.name) ?: defaultCity.recommendedMadhab.name
        val madhab = try { Madhab.valueOf(madhabStr) } catch (e: Exception) { defaultCity.recommendedMadhab }

        val highLatStr = prefs.getString(KEY_HIGH_LAT, HighLatitudeRule.AngleBased.name) ?: HighLatitudeRule.AngleBased.name
        val highLatitudeRule = try { HighLatitudeRule.valueOf(highLatStr) } catch (e: Exception) { HighLatitudeRule.AngleBased }

        val rawCustomFajr = prefs.getFloat(KEY_CUSTOM_FAJR, 18.0f).toDouble()
        val safeCustomFajr = if (InputValidator.isValidCustomAngle(rawCustomFajr)) rawCustomFajr else 18.0

        val rawCustomIsha = prefs.getFloat(KEY_CUSTOM_ISHA, 17.0f).toDouble()
        val safeCustomIsha = if (InputValidator.isValidCustomAngle(rawCustomIsha)) rawCustomIsha else 17.0

        val soundStr = prefs.getString(KEY_SOUND, AthanSound.FULL_ATHAN.name)
        val sound = AthanAudioCatalog.fromNameOrDefault(soundStr)

        val prayerNotifs = mutableMapOf<PrayerType, Boolean>()
        for (prayer in PrayerType.entries) {
            val defVal = prayer != PrayerType.SUNRISE // Sunrise is silent by default
            prayerNotifs[prayer] = prefs.getBoolean(KEY_NOTIF_PREFIX + prayer.name, defVal)
        }

        val rawReminderMinutes = prefs.getInt(KEY_REMINDER_MINUTES, 0)
        val safeReminderMinutes = if (InputValidator.isValidReminderMinutes(rawReminderMinutes)) rawReminderMinutes else 0

        val is24Hour = prefs.getBoolean(KEY_24_HOUR, false)

        val rawHijriAdjustment = prefs.getInt(KEY_HIJRI_ADJUSTMENT, 0)
        val safeHijriAdjustment = if (InputValidator.isValidHijriAdjustment(rawHijriAdjustment)) rawHijriAdjustment else 0

        val prayerAdjustments = mutableMapOf<PrayerType, Int>()
        for (prayer in PrayerType.entries) {
            val rawAdj = try {
                prefs.getInt(KEY_ADJUSTMENT_PREFIX + prayer.name, 0)
            } catch (e: Exception) {
                0
            }
            val safeAdj = if (InputValidator.isValidPrayerAdjustment(rawAdj)) rawAdj else 0
            prayerAdjustments[prayer] = safeAdj
        }

        val baseSettings = AthanSettings(
            location = location,
            method = method,
            madhab = madhab,
            highLatitudeRule = highLatitudeRule,
            customFajrAngle = safeCustomFajr,
            customIshaAngle = safeCustomIsha,
            sound = sound,
            prayerNotifications = prayerNotifs,
            reminderMinutesBefore = safeReminderMinutes,
            is24HourFormat = is24Hour,
            hijriDayAdjustment = safeHijriAdjustment,
            prayerAdjustments = prayerAdjustments
        )

        // Protect against corrupted persisted combinations by validating chronological order
        val validation = validateChronologicalSchedule(baseSettings, prayerAdjustments)
        val finalAdjustments = if (validation.isValid) {
            prayerAdjustments
        } else {
            Log.w(TAG, "Persisted prayer adjustments created invalid schedule (${validation.errorMessage}), recovering safe adjustments")
            val recovered = mutableMapOf<PrayerType, Int>()
            val editor = prefs.edit()
            for (prayer in PrayerType.entries) {
                val mins = prayerAdjustments[prayer] ?: 0
                if (mins != 0) {
                    val candidate = recovered.toMutableMap().apply { put(prayer, mins) }
                    if (validateChronologicalSchedule(baseSettings, candidate).isValid) {
                        recovered[prayer] = mins
                    } else {
                        editor.putInt(KEY_ADJUSTMENT_PREFIX + prayer.name, 0)
                        recovered[prayer] = 0
                    }
                } else {
                    recovered[prayer] = 0
                }
            }
            editor.apply()
            recovered
        }

        return baseSettings.copy(prayerAdjustments = finalAdjustments)
    }

    fun updateLocation(location: UserLocation): Boolean {
        if (!location.isValid()) {
            Log.w(TAG, "updateLocation rejected invalid location: $location")
            return false
        }
        val safeTz = if (TimeZoneResolver.isValidTimeZoneId(location.timezoneId)) {
            location.timezoneId.trim()
        } else {
            val recoveredTz = OfflineTimezoneLookup.lookup(location.latitude, location.longitude) ?: BundledCities.DEFAULT_CITY.timezoneId
            Log.w(TAG, "Supplied location timezone '${location.timezoneId}' is invalid or unrecognized; recovering to '$recoveredTz'")
            recoveredTz
        }
        val safeLocation = location.copy(timezoneId = safeTz)
        prefs.edit().apply {
            putString(KEY_CITY, safeLocation.cityName)
            putString(KEY_COUNTRY, safeLocation.countryName)
            putString(KEY_LAT, safeLocation.latitude.toString())
            putString(KEY_LON, safeLocation.longitude.toString())
            putFloat(KEY_ELEVATION, safeLocation.elevationMeters.toFloat())
            putString(KEY_TIMEZONE, safeLocation.timezoneId)
            putBoolean(KEY_IS_GPS, safeLocation.isGpsDetected)
            apply()
        }
        _settingsFlow.value = loadSettings()
        AthanAlarmScheduler.scheduleRollingAlarms(appContext)
        return true
    }

    fun updateCitySelection(
        location: UserLocation,
        method: CalculationMethod,
        madhab: Madhab
    ): Boolean {
        if (!location.isValid()) {
            Log.w(TAG, "updateCitySelection rejected invalid location: $location")
            return false
        }
        val safeTz = if (TimeZoneResolver.isValidTimeZoneId(location.timezoneId)) {
            location.timezoneId.trim()
        } else {
            val recoveredTz = OfflineTimezoneLookup.lookup(location.latitude, location.longitude) ?: BundledCities.DEFAULT_CITY.timezoneId
            Log.w(TAG, "Supplied location timezone '${location.timezoneId}' is invalid or unrecognized; recovering to '$recoveredTz'")
            recoveredTz
        }
        val safeLocation = location.copy(timezoneId = safeTz)
        prefs.edit().apply {
            putString(KEY_CITY, safeLocation.cityName)
            putString(KEY_COUNTRY, safeLocation.countryName)
            putString(KEY_LAT, safeLocation.latitude.toString())
            putString(KEY_LON, safeLocation.longitude.toString())
            putFloat(KEY_ELEVATION, safeLocation.elevationMeters.toFloat())
            putString(KEY_TIMEZONE, safeLocation.timezoneId)
            putBoolean(KEY_IS_GPS, safeLocation.isGpsDetected)
            putString(KEY_METHOD, method.name)
            putString(KEY_MADHAB, madhab.name)
            apply()
        }
        _settingsFlow.value = loadSettings()
        AthanAlarmScheduler.scheduleRollingAlarms(appContext)
        return true
    }

    fun updateMethod(method: CalculationMethod) {
        prefs.edit().putString(KEY_METHOD, method.name).apply()
        _settingsFlow.value = loadSettings()
        AthanAlarmScheduler.scheduleRollingAlarms(appContext)
    }

    fun updateMadhab(madhab: Madhab) {
        prefs.edit().putString(KEY_MADHAB, madhab.name).apply()
        _settingsFlow.value = loadSettings()
        AthanAlarmScheduler.scheduleRollingAlarms(appContext)
    }

    fun updateHighLatitudeRule(rule: HighLatitudeRule) {
        prefs.edit().putString(KEY_HIGH_LAT, rule.name).apply()
        _settingsFlow.value = loadSettings()
        AthanAlarmScheduler.scheduleRollingAlarms(appContext)
    }

    fun updateCustomAngles(fajr: Double, isha: Double): Boolean {
        if (!InputValidator.isValidCustomAngle(fajr) || !InputValidator.isValidCustomAngle(isha)) {
            Log.w(TAG, "updateCustomAngles rejected invalid angles: fajr=$fajr, isha=$isha")
            return false
        }
        prefs.edit()
            .putFloat(KEY_CUSTOM_FAJR, fajr.toFloat())
            .putFloat(KEY_CUSTOM_ISHA, isha.toFloat())
            .apply()
        _settingsFlow.value = loadSettings()
        AthanAlarmScheduler.scheduleRollingAlarms(appContext)
        return true
    }

    fun updateSound(sound: AthanSound) {
        prefs.edit().putString(KEY_SOUND, sound.name).apply()
        _settingsFlow.value = loadSettings()
    }

    fun togglePrayerNotification(prayerType: PrayerType, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIF_PREFIX + prayerType.name, enabled).apply()
        _settingsFlow.value = loadSettings()
        AthanAlarmScheduler.scheduleRollingAlarms(appContext)
    }

    fun updateReminderMinutes(minutes: Int): Boolean {
        if (!InputValidator.isValidReminderMinutes(minutes)) {
            Log.w(TAG, "updateReminderMinutes rejected invalid minutes: $minutes")
            return false
        }
        prefs.edit().putInt(KEY_REMINDER_MINUTES, minutes).apply()
        _settingsFlow.value = loadSettings()
        AthanAlarmScheduler.scheduleRollingAlarms(appContext)
        return true
    }

    fun updateTimeFormat(is24Hour: Boolean) {
        prefs.edit().putBoolean(KEY_24_HOUR, is24Hour).apply()
        _settingsFlow.value = loadSettings()
    }

    fun updateHijriAdjustment(adjustment: Int): Boolean {
        if (!InputValidator.isValidHijriAdjustment(adjustment)) {
            Log.w(TAG, "updateHijriAdjustment rejected invalid adjustment: $adjustment")
            return false
        }
        prefs.edit().putInt(KEY_HIJRI_ADJUSTMENT, adjustment).apply()
        _settingsFlow.value = loadSettings()
        AthanAlarmScheduler.scheduleRollingAlarms(appContext)
        return true
    }

    fun validateChronologicalSchedule(
        settings: AthanSettings,
        adjustments: Map<PrayerType, Int>,
        dateMillis: Long = System.currentTimeMillis()
    ): ChronologicalValidationResult {
        val tz = TimeZoneResolver.resolve(settings.location.timezoneId)
        val localDay = LocalCalendarDay.fromMillis(dateMillis, tz)
        val rawTimes = AstronomicalEngine.calculate(
            year = localDay.year,
            month = localDay.month,
            day = localDay.dayOfMonth,
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
        val candidate = rawTimes.withAdjustments(adjustments)
        return candidate.validateChronologicalOrder()
    }

    fun updatePrayerAdjustment(prayerType: PrayerType, minutes: Int): Boolean {
        if (!InputValidator.isValidPrayerAdjustment(minutes)) {
            Log.w(TAG, "updatePrayerAdjustment rejected invalid minutes: $minutes for $prayerType")
            return false
        }
        val currentSettings = loadSettings()
        val proposedAdjustments = currentSettings.prayerAdjustments.toMutableMap().apply {
            put(prayerType, minutes)
        }
        val validation = validateChronologicalSchedule(currentSettings, proposedAdjustments)
        if (!validation.isValid) {
            Log.w(TAG, "updatePrayerAdjustment rejected due to chronological conflict: ${validation.errorMessage}")
            return false
        }
        prefs.edit().putInt(KEY_ADJUSTMENT_PREFIX + prayerType.name, minutes).apply()
        _settingsFlow.value = loadSettings()
        AthanAlarmScheduler.scheduleRollingAlarms(appContext)
        return true
    }

    fun resetAllPrayerAdjustments() {
        val editor = prefs.edit()
        for (prayer in PrayerType.entries) {
            editor.putInt(KEY_ADJUSTMENT_PREFIX + prayer.name, 0)
        }
        editor.apply()
        _settingsFlow.value = loadSettings()
        AthanAlarmScheduler.scheduleRollingAlarms(appContext)
    }

    fun hasPromptedNotificationPermission(): Boolean {
        return prefs.getBoolean(KEY_NOTIF_PERMISSION_PROMPTED, false)
    }

    fun setNotificationPermissionPrompted(prompted: Boolean = true) {
        prefs.edit().putBoolean(KEY_NOTIF_PERMISSION_PROMPTED, prompted).apply()
    }

    private fun readAndMigrateCoordinate(
        key: String,
        defaultCoordinate: Double,
        validator: (Double) -> Boolean
    ): Pair<Double, Double?> {
        // 1. Read the new lossless coordinate representation if it exists
        val strVal = try {
            prefs.getString(key, null)
        } catch (_: ClassCastException) {
            null
        }

        if (strVal != null) {
            val parsed = strVal.toDoubleOrNull()
            return if (parsed != null && validator(parsed)) {
                Pair(parsed, null)
            } else {
                Log.w(TAG, "Persisted coordinate '$key' with value '$strVal' is malformed or invalid; falling back to default $defaultCoordinate")
                Pair(defaultCoordinate, null)
            }
        }

        // 2. If the new representation does not exist, check the existing legacy Float preference
        if (prefs.contains(key)) {
            val legacyFloat = try {
                prefs.getFloat(key, Float.NaN)
            } catch (_: ClassCastException) {
                Float.NaN
            }

            if (!legacyFloat.isNaN() && legacyFloat.isFinite()) {
                val legacyDouble = legacyFloat.toDouble()
                if (validator(legacyDouble)) {
                    // 3. Valid legacy value exists -> convert to Double and mark for migration
                    return Pair(legacyDouble, legacyDouble)
                }
            }
            Log.w(TAG, "Legacy coordinate '$key' is invalid; falling back to default $defaultCoordinate")
            return Pair(defaultCoordinate, null)
        }

        // Key does not exist at all in preferences (fresh install)
        return Pair(defaultCoordinate, null)
    }

    companion object {
        private const val TAG = "AthanPreferences"
        private const val KEY_CITY = "city"
        private const val KEY_COUNTRY = "country"
        private const val KEY_LAT = "latitude"
        private const val KEY_LON = "longitude"
        private const val KEY_ELEVATION = "elevation"
        private const val KEY_TIMEZONE = "timezone"
        private const val KEY_IS_GPS = "is_gps"
        private const val KEY_METHOD = "method"
        private const val KEY_MADHAB = "madhab"
        private const val KEY_HIGH_LAT = "high_latitude_rule"
        private const val KEY_CUSTOM_FAJR = "custom_fajr"
        private const val KEY_CUSTOM_ISHA = "custom_isha"
        private const val KEY_SOUND = "sound"
        private const val KEY_NOTIF_PREFIX = "notif_"
        private const val KEY_REMINDER_MINUTES = "reminder_minutes"
        private const val KEY_24_HOUR = "is_24_hour"
        private const val KEY_HIJRI_ADJUSTMENT = "hijri_adjustment"
        private const val KEY_ADJUSTMENT_PREFIX = "adj_"
        private const val KEY_NOTIF_PERMISSION_PROMPTED = "notif_permission_prompted"

        @Volatile
        private var INSTANCE: AthanPreferences? = null

        fun getInstance(context: Context): AthanPreferences {
            val app = context.applicationContext ?: context
            val current = INSTANCE
            if (current != null && current.appContext == app) {
                return current
            }
            return synchronized(this) {
                val cur = INSTANCE
                if (cur != null && cur.appContext == app) {
                    cur
                } else {
                    AthanPreferences(app).also { INSTANCE = it }
                }
            }
        }

        fun resetInstanceForTesting() {
            synchronized(this) {
                INSTANCE = null
            }
        }
    }
}
