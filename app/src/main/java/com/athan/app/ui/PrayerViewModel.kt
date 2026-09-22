package com.athan.app.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.athan.app.core.astronomy.*
import com.athan.app.audio.AthanAudioCatalog
import com.athan.app.audio.AthanAudioManager
import com.athan.app.audio.AthanSound
import com.athan.app.data.location.BundledCity
import com.athan.app.data.location.BundledCities
import com.athan.app.data.location.LocationResult
import com.athan.app.data.location.OfflineLocationProvider
import com.athan.app.data.location.OfflineTimezoneLookup
import com.athan.app.data.location.UserLocation
import com.athan.app.data.repository.AthanPreferences
import com.athan.app.data.repository.AthanSettings
import com.athan.app.scheduling.AthanAlarmScheduler
import com.athan.app.util.InputValidator
import com.athan.app.util.NotificationPermissionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

sealed class GpsLocationState {
    object Idle : GpsLocationState()
    object Locating : GpsLocationState()
    data class LocationFound(val location: UserLocation) : GpsLocationState()
    object PermissionDenied : GpsLocationState()
    object ProviderUnavailable : GpsLocationState()
    object TimeoutOrNoFix : GpsLocationState()
    object Cancelled : GpsLocationState()
    data class Error(val message: String) : GpsLocationState()
}

class PrayerViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = AthanPreferences.getInstance(application)
    val settings: StateFlow<AthanSettings> = preferences.settingsFlow

    private val _currentTimeMillis = MutableStateFlow(System.currentTimeMillis())
    val currentTimeMillis: StateFlow<Long> = _currentTimeMillis.asStateFlow()

    private val _currentLocalDate = MutableStateFlow(
        LocalCalendarDay.fromMillis(
            System.currentTimeMillis(),
            getTimeZone(preferences.settingsFlow.value.location.timezoneId)
        )
    )
    val currentLocalDate: StateFlow<LocalCalendarDay> = _currentLocalDate.asStateFlow()

    private val _currentGregorianDate = MutableStateFlow(_currentLocalDate.value.formatGregorian())
    val currentGregorianDate: StateFlow<String> = _currentGregorianDate.asStateFlow()

    private val _currentHijriDate = MutableStateFlow(_currentLocalDate.value.toHijriDate(preferences.settingsFlow.value.hijriDayAdjustment))
    val currentHijriDate: StateFlow<HijriDate> = _currentHijriDate.asStateFlow()

    private val _todayPrayerTimes = MutableStateFlow(computePrayerTimesForDay(preferences.settingsFlow.value, _currentLocalDate.value, 0))
    val todayPrayerTimes: StateFlow<PrayerTimes> = _todayPrayerTimes.asStateFlow()

    private val _tomorrowPrayerTimes = MutableStateFlow(computePrayerTimesForDay(preferences.settingsFlow.value, _currentLocalDate.value, 1))
    val tomorrowPrayerTimes: StateFlow<PrayerTimes> = _tomorrowPrayerTimes.asStateFlow()

    private val _dayAfterTomorrowPrayerTimes = MutableStateFlow(computePrayerTimesForDay(preferences.settingsFlow.value, _currentLocalDate.value, 2))
    val dayAfterTomorrowPrayerTimes: StateFlow<PrayerTimes> = _dayAfterTomorrowPrayerTimes.asStateFlow()

    private val _nextPrayerState = MutableStateFlow(
        PrayerTimes.resolveNextPrayer(
            todayPrayers = _todayPrayerTimes.value,
            tomorrowPrayers = _tomorrowPrayerTimes.value,
            dayAfterTomorrowPrayers = _dayAfterTomorrowPrayerTimes.value,
            currentTimeMillis = _currentTimeMillis.value
        )
    )
    val nextPrayerState: StateFlow<NextPrayerState> = _nextPrayerState.asStateFlow()

    private val _isAudioTesting = MutableStateFlow(false)
    val isAudioTesting: StateFlow<Boolean> = _isAudioTesting.asStateFlow()

    private val _isNotificationPermissionGranted = MutableStateFlow(
        NotificationPermissionManager.hasNotificationPermission(application)
    )
    val isNotificationPermissionGranted: StateFlow<Boolean> = _isNotificationPermissionGranted.asStateFlow()

    private val _canScheduleExactAlarms = MutableStateFlow(
        AthanAlarmScheduler.canScheduleExactAlarms(application)
    )
    val canScheduleExactAlarms: StateFlow<Boolean> = _canScheduleExactAlarms.asStateFlow()

    private val _isLocatingGps = MutableStateFlow(false)
    val isLocatingGps: StateFlow<Boolean> = _isLocatingGps.asStateFlow()

    private val _gpsState = MutableStateFlow<GpsLocationState>(GpsLocationState.Idle)
    val gpsState: StateFlow<GpsLocationState> = _gpsState.asStateFlow()

    private val _gpsMessage = MutableStateFlow<String?>(null)
    val gpsMessage: StateFlow<String?> = _gpsMessage.asStateFlow()

    private val _adjustmentErrorMessage = MutableStateFlow<String?>(null)
    val adjustmentErrorMessage: StateFlow<String?> = _adjustmentErrorMessage.asStateFlow()

    fun clearAdjustmentErrorMessage() {
        _adjustmentErrorMessage.value = null
    }

    private var activeGpsJob: Job? = null
    private var currentGpsRequestId: Long = 0L

    private var tickerJob: Job? = null
    private var midnightJob: Job? = null

    private val dateChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            checkDateRefresh(System.currentTimeMillis())
            refreshExactAlarmCapability()
            refreshAlarms()
            scheduleMidnightRefresh()
        }
    }

    init {
        // Start 1-second ticker for smooth countdown and prayer transitions
        startTicker()
        // Schedule exact midnight refresh
        scheduleMidnightRefresh()
        // Observe settings changes (timezone, hijri adjustment, location, calculation method)
        viewModelScope.launch {
            settings.collect { s ->
                val day = _currentLocalDate.value
                _currentHijriDate.value = day.toHijriDate(s.hijriDayAdjustment)
                _todayPrayerTimes.value = computePrayerTimesForDay(s, day, 0)
                _tomorrowPrayerTimes.value = computePrayerTimesForDay(s, day, 1)
                _dayAfterTomorrowPrayerTimes.value = computePrayerTimesForDay(s, day, 2)
                _nextPrayerState.value = PrayerTimes.resolveNextPrayer(
                    todayPrayers = _todayPrayerTimes.value,
                    tomorrowPrayers = _tomorrowPrayerTimes.value,
                    dayAfterTomorrowPrayers = _dayAfterTomorrowPrayerTimes.value,
                    currentTimeMillis = _currentTimeMillis.value
                )
            }
        }
        viewModelScope.launch {
            settings.map { it.location.timezoneId }.distinctUntilChanged().collect {
                checkDateRefresh(System.currentTimeMillis())
                scheduleMidnightRefresh()
            }
        }
        // Register receiver for system time/date and exact-alarm permission changes
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    addAction(android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
                }
            }
            application.registerReceiver(dateChangeReceiver, filter)
        } catch (e: Exception) {
            // Safe fallback if testing or restricted context
        }
        // Ensure rolling exact alarms are scheduled
        refreshAlarms()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                _currentTimeMillis.value = now
                // Double safety: if local calendar day rolled over, trigger date refresh
                val tz = getTimeZone(settings.value.location.timezoneId)
                val today = LocalCalendarDay.fromMillis(now, tz)
                if (today != _currentLocalDate.value) {
                    checkDateRefresh(now)
                    scheduleMidnightRefresh()
                } else {
                    _nextPrayerState.value = PrayerTimes.resolveNextPrayer(
                        todayPrayers = _todayPrayerTimes.value,
                        tomorrowPrayers = _tomorrowPrayerTimes.value,
                        dayAfterTomorrowPrayers = _dayAfterTomorrowPrayerTimes.value,
                        currentTimeMillis = now
                    )
                }
                delay(1000)
            }
        }
    }

    private fun scheduleMidnightRefresh() {
        midnightJob?.cancel()
        midnightJob = viewModelScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val tz = getTimeZone(settings.value.location.timezoneId)
                val millisToMidnight = LocalCalendarDay.getMillisUntilNextMidnight(now, tz)
                delay(millisToMidnight + 50L)
                checkDateRefresh(System.currentTimeMillis())
            }
        }
    }

    fun checkDateRefresh(now: Long = System.currentTimeMillis()): Boolean {
        val tz = getTimeZone(settings.value.location.timezoneId)
        val newDay = LocalCalendarDay.fromMillis(now, tz)
        if (newDay != _currentLocalDate.value) {
            _currentLocalDate.value = newDay
            _currentGregorianDate.value = newDay.formatGregorian()
            _currentHijriDate.value = newDay.toHijriDate(settings.value.hijriDayAdjustment)
            _todayPrayerTimes.value = computePrayerTimesForDay(settings.value, newDay, 0)
            _tomorrowPrayerTimes.value = computePrayerTimesForDay(settings.value, newDay, 1)
            _dayAfterTomorrowPrayerTimes.value = computePrayerTimesForDay(settings.value, newDay, 2)
            _nextPrayerState.value = PrayerTimes.resolveNextPrayer(
                todayPrayers = _todayPrayerTimes.value,
                tomorrowPrayers = _tomorrowPrayerTimes.value,
                dayAfterTomorrowPrayers = _dayAfterTomorrowPrayerTimes.value,
                currentTimeMillis = now
            )
            return true
        }
        return false
    }

    @get:VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    @set:VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal var lastResumeTimestamp: Long = 0L

    @get:VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal var resumeExecutionCount: Int = 0
        private set

    companion object {
        const val RESUME_DEBOUNCE_MS: Long = 500L
    }

    fun onAppResume(force: Boolean = false, currentTime: Long = System.currentTimeMillis()): Boolean {
        val now = currentTime
        if (!force && (now - lastResumeTimestamp) < RESUME_DEBOUNCE_MS) {
            return false
        }
        lastResumeTimestamp = now
        resumeExecutionCount++

        checkDateRefresh(now)
        refreshNotificationPermission()
        refreshExactAlarmCapability()
        refreshAlarms()
        scheduleMidnightRefresh()
        return true
    }

    val qiblaInfo: StateFlow<Pair<Double, Double>> = settings.map { s ->
        val bearing = QiblaCalculator.calculateBearing(s.location.latitude, s.location.longitude)
        val distance = QiblaCalculator.calculateDistanceKm(s.location.latitude, s.location.longitude)
        Pair(bearing, distance)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        Pair(
            QiblaCalculator.calculateBearing(
                preferences.settingsFlow.value.location.latitude,
                preferences.settingsFlow.value.location.longitude
            ),
            QiblaCalculator.calculateDistanceKm(
                preferences.settingsFlow.value.location.latitude,
                preferences.settingsFlow.value.location.longitude
            )
        )
    )

    fun getLocationTimeZone(): TimeZone {
        return getTimeZone(settings.value.location.timezoneId)
    }

    fun getMonthPrayerTimes(
        year: Int = currentLocalDate.value.year,
        month: Int = currentLocalDate.value.month
    ): List<PrayerTimes> {
        val s = settings.value
        val tz = getTimeZone(s.location.timezoneId)
        val rawMonth = AstronomicalEngine.calculateMonth(
            year = year,
            month = month,
            latitude = s.location.latitude,
            longitude = s.location.longitude,
            elevationMeters = s.location.elevationMeters,
            method = s.method,
            madhab = s.madhab,
            highLatitudeRule = s.highLatitudeRule,
            customFajrAngle = s.customFajrAngle,
            customIshaAngle = s.customIshaAngle,
            timeZone = tz,
            hijriDayAdjustment = s.hijriDayAdjustment
        )
        return rawMonth.map { it.withValidatedAdjustments(s.prayerAdjustments) }
    }

    fun computeRawPrayerTimesForDay(s: AthanSettings, localDay: LocalCalendarDay, dayOffset: Int): PrayerTimes {
        val tz = localDay.getTimeZone()
        val cal = Calendar.getInstance(tz).apply {
            set(Calendar.YEAR, localDay.year)
            set(Calendar.MONTH, localDay.month - 1)
            set(Calendar.DAY_OF_MONTH, localDay.dayOfMonth)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (dayOffset != 0) add(Calendar.DAY_OF_YEAR, dayOffset)
        }
        return AstronomicalEngine.calculate(
            year = cal.get(Calendar.YEAR),
            month = cal.get(Calendar.MONTH) + 1,
            day = cal.get(Calendar.DAY_OF_MONTH),
            latitude = s.location.latitude,
            longitude = s.location.longitude,
            elevationMeters = s.location.elevationMeters,
            method = s.method,
            madhab = s.madhab,
            highLatitudeRule = s.highLatitudeRule,
            customFajrAngle = s.customFajrAngle,
            customIshaAngle = s.customIshaAngle,
            timeZone = tz,
            hijriDayAdjustment = s.hijriDayAdjustment
        )
    }

    private fun computePrayerTimesForDay(s: AthanSettings, localDay: LocalCalendarDay, dayOffset: Int): PrayerTimes {
        val rawTimes = computeRawPrayerTimesForDay(s, localDay, dayOffset)
        return rawTimes.withValidatedAdjustments(s.prayerAdjustments)
    }

    private fun computePrayerTimesForOffset(s: AthanSettings, baseMillis: Long, dayOffset: Int): PrayerTimes {
        val tz = getTimeZone(s.location.timezoneId)
        val localDay = LocalCalendarDay.fromMillis(baseMillis, tz)
        return computePrayerTimesForDay(s, localDay, dayOffset)
    }

    private fun getTimeZone(timezoneId: String): TimeZone {
        return TimeZoneResolver.resolve(timezoneId)
    }

    fun selectBundledCity(city: BundledCity) {
        cancelGpsLocationRequest()
        val newLocation = UserLocation(
            cityName = city.name,
            countryName = city.country,
            latitude = city.latitude,
            longitude = city.longitude,
            elevationMeters = 0.0,
            timezoneId = city.timezoneId,
            isGpsDetected = false
        )
        preferences.updateCitySelection(
            location = newLocation,
            method = city.recommendedMethod,
            madhab = city.recommendedMadhab
        )
        checkDateRefresh(System.currentTimeMillis())
        scheduleMidnightRefresh()
    }

    fun setManualCoordinates(
        name: String,
        lat: Double,
        lon: Double,
        elevation: Double,
        timezoneId: String? = null
    ): Boolean {
        if (!InputValidator.isValidLatitude(lat)) {
            val msg = "Invalid latitude: must be between -90.0 and 90.0"
            _gpsState.value = GpsLocationState.Error(msg)
            _gpsMessage.value = msg
            return false
        }
        if (!InputValidator.isValidLongitude(lon)) {
            val msg = "Invalid longitude: must be between -180.0 and 180.0"
            _gpsState.value = GpsLocationState.Error(msg)
            _gpsMessage.value = msg
            return false
        }
        if (!InputValidator.isValidElevation(elevation)) {
            val msg = "Invalid elevation: must be between -500 and 9,000 meters"
            _gpsState.value = GpsLocationState.Error(msg)
            _gpsMessage.value = msg
            return false
        }
        cancelGpsLocationRequest()

        // Determine timezone: explicit user choice > offline resolution > safe fallback (never device timezone)
        val resolvedTz = if (TimeZoneResolver.isValidTimeZoneId(timezoneId)) {
            timezoneId!!.trim()
        } else {
            OfflineTimezoneLookup.lookup(lat, lon)
                ?: if (TimeZoneResolver.isValidTimeZoneId(preferences.settingsFlow.value.location.timezoneId)) {
                    preferences.settingsFlow.value.location.timezoneId
                } else {
                    TimeZoneResolver.DEFAULT_FALLBACK_TIMEZONE_ID
                }
        }

        val newLocation = UserLocation(
            cityName = name.ifBlank { "Custom Location" },
            countryName = String.format(Locale.US, "%.3f, %.3f", lat, lon),
            latitude = lat,
            longitude = lon,
            elevationMeters = elevation,
            timezoneId = resolvedTz,
            isGpsDetected = false
        )
        val success = preferences.updateLocation(newLocation)
        if (success) {
            checkDateRefresh(System.currentTimeMillis())
            scheduleMidnightRefresh()
            refreshAlarms()
        }
        return success
    }

    fun requestGpsLocation(
        timeoutMillis: Long = OfflineLocationProvider.DEFAULT_TIMEOUT_MS,
        useLastKnownIfFresh: Boolean = false
    ) {
        // Cancel any pending GPS request first
        cancelGpsJobOnly()

        val requestId = ++currentGpsRequestId
        _isLocatingGps.value = true
        _gpsState.value = GpsLocationState.Locating
        _gpsMessage.value = "Acquiring on-device GPS fix..."

        activeGpsJob = viewModelScope.launch {
            try {
                val result = OfflineLocationProvider.requestSingleLocation(
                    getApplication(),
                    timeoutMillis = timeoutMillis,
                    useLastKnownIfFresh = useLastKnownIfFresh
                )

                // Stale check: ignore if this request was superseded or cancelled
                if (requestId != currentGpsRequestId || !isActive) {
                    return@launch
                }

                when (result) {
                    is LocationResult.Success -> {
                        val loc = result.location
                        if (OfflineLocationProvider.isValidLocation(loc)) {
                            val resolvedTz = OfflineTimezoneLookup.lookup(loc.latitude, loc.longitude)
                            if (resolvedTz != null) {
                                val userLoc = UserLocation(
                                    cityName = "GPS Location",
                                    countryName = String.format(Locale.US, "%.3f, %.3f", loc.latitude, loc.longitude),
                                    latitude = loc.latitude,
                                    longitude = loc.longitude,
                                    elevationMeters = if (loc.hasAltitude()) loc.altitude else 0.0,
                                    timezoneId = resolvedTz,
                                    isGpsDetected = true
                                )
                                preferences.updateLocation(userLoc)
                                checkDateRefresh(System.currentTimeMillis())
                                scheduleMidnightRefresh()
                                refreshAlarms()
                                _gpsState.value = GpsLocationState.LocationFound(userLoc)
                                _gpsMessage.value = "GPS Location updated successfully ($resolvedTz)."
                            } else {
                                // Timezone resolution failed: do NOT invent one, do NOT copy device timezone!
                                // Preserve existing valid location in preferences
                                val preserved = preferences.settingsFlow.value.location
                                val msg = "GPS coordinates (${String.format(Locale.US, "%.3f, %.3f", loc.latitude, loc.longitude)}) acquired, but offline timezone could not be determined. Previous location (${preserved.cityName}) preserved."
                                _gpsState.value = GpsLocationState.Error(msg)
                                _gpsMessage.value = msg
                            }
                        } else {
                            _gpsState.value = GpsLocationState.Error("Invalid GPS coordinates received.")
                            _gpsMessage.value = "Invalid GPS coordinates received. Current location preserved."
                        }
                    }
                    is LocationResult.PermissionDenied -> {
                        _gpsState.value = GpsLocationState.PermissionDenied
                        _gpsMessage.value = "Location permission denied. You can select a city or enter coordinates below."
                    }
                    is LocationResult.ProviderDisabled -> {
                        _gpsState.value = GpsLocationState.ProviderUnavailable
                        _gpsMessage.value = "Location services/provider disabled on device. Enable location in system settings or select a city below."
                    }
                    is LocationResult.Timeout, is LocationResult.NoFix -> {
                        _gpsState.value = GpsLocationState.TimeoutOrNoFix
                        _gpsMessage.value = "GPS request timed out. Satellites could not be reached. Your previous location was preserved."
                    }
                    is LocationResult.InvalidCoordinates -> {
                        _gpsState.value = GpsLocationState.Error("Invalid GPS coordinates received.")
                        _gpsMessage.value = "Invalid coordinates received from GPS. Current location preserved."
                    }
                    is LocationResult.Cancelled -> {
                        _gpsState.value = GpsLocationState.Cancelled
                        _gpsMessage.value = "GPS request cancelled. Current location preserved."
                    }
                    is LocationResult.Error -> {
                        _gpsState.value = GpsLocationState.Error(result.message)
                        _gpsMessage.value = "GPS error: ${result.message}. Current location preserved."
                    }
                }
            } catch (e: CancellationException) {
                if (requestId == currentGpsRequestId) {
                    _gpsState.value = GpsLocationState.Cancelled
                    _gpsMessage.value = "GPS request cancelled. Current location preserved."
                }
            } catch (e: Exception) {
                if (requestId == currentGpsRequestId) {
                    _gpsState.value = GpsLocationState.Error(e.message ?: "Unknown error")
                    _gpsMessage.value = "GPS error: ${e.message}"
                }
            } finally {
                if (requestId == currentGpsRequestId) {
                    _isLocatingGps.value = false
                    activeGpsJob = null
                }
            }
        }
    }

    fun cancelGpsLocationRequest() {
        if (activeGpsJob != null && activeGpsJob?.isActive == true) {
            currentGpsRequestId++
            activeGpsJob?.cancel()
            activeGpsJob = null
            _isLocatingGps.value = false
            _gpsState.value = GpsLocationState.Cancelled
            _gpsMessage.value = "GPS request cancelled. Current location preserved."
        }
    }

    private fun cancelGpsJobOnly() {
        if (activeGpsJob != null && activeGpsJob?.isActive == true) {
            currentGpsRequestId++
            activeGpsJob?.cancel()
            activeGpsJob = null
            _isLocatingGps.value = false
        }
    }

    fun onLocationPermissionDenied() {
        cancelGpsJobOnly()
        _gpsState.value = GpsLocationState.PermissionDenied
        _gpsMessage.value = "Location permission was denied. You can select a city from the list below or enter custom coordinates."
    }

    fun clearGpsMessage() {
        _gpsMessage.value = null
        if (_gpsState.value !is GpsLocationState.Locating) {
            _gpsState.value = GpsLocationState.Idle
        }
    }

    fun updateCalculationMethod(method: CalculationMethod) {
        preferences.updateMethod(method)
        refreshAlarms()
    }

    fun updateMadhab(madhab: Madhab) {
        preferences.updateMadhab(madhab)
        refreshAlarms()
    }

    fun updateHighLatitudeRule(rule: HighLatitudeRule) {
        preferences.updateHighLatitudeRule(rule)
        refreshAlarms()
    }

    fun updateCustomAngles(fajr: Double, isha: Double): Boolean {
        if (!InputValidator.isValidCustomAngle(fajr) || !InputValidator.isValidCustomAngle(isha)) {
            return false
        }
        val success = preferences.updateCustomAngles(fajr, isha)
        if (success) {
            refreshAlarms()
        }
        return success
    }

    fun updateSound(sound: AthanSound) {
        preferences.updateSound(sound)
    }

    fun togglePrayerNotification(prayerType: PrayerType) {
        val current = settings.value.prayerNotifications[prayerType] ?: true
        preferences.togglePrayerNotification(prayerType, !current)
        refreshAlarms()
    }

    fun updateReminderMinutes(minutes: Int): Boolean {
        if (!InputValidator.isValidReminderMinutes(minutes)) {
            return false
        }
        val success = preferences.updateReminderMinutes(minutes)
        if (success) {
            refreshAlarms()
        }
        return success
    }

    fun updateTimeFormat(is24Hour: Boolean) {
        preferences.updateTimeFormat(is24Hour)
    }

    fun updateHijriAdjustment(adj: Int): Boolean {
        if (!InputValidator.isValidHijriAdjustment(adj)) {
            return false
        }
        val success = preferences.updateHijriAdjustment(adj)
        if (success) {
            _currentHijriDate.value = _currentLocalDate.value.toHijriDate(adj)
        }
        return success
    }

    fun updatePrayerAdjustment(prayerType: PrayerType, minutes: Int): Boolean {
        if (!InputValidator.isValidPrayerAdjustment(minutes)) {
            _adjustmentErrorMessage.value = "Adjustment must be between ${InputValidator.MIN_PRAYER_ADJUSTMENT_MINUTES} and +${InputValidator.MAX_PRAYER_ADJUSTMENT_MINUTES} minutes."
            return false
        }
        val currentSettings = settings.value
        val proposed = currentSettings.prayerAdjustments.toMutableMap().apply {
            put(prayerType, minutes)
        }
        val rawToday = computeRawPrayerTimesForDay(currentSettings, _currentLocalDate.value, 0)
        val candidate = rawToday.withAdjustments(proposed)
        val validation = candidate.validateChronologicalOrder()
        if (!validation.isValid) {
            val earlier = validation.conflictingEarlier
            val later = validation.conflictingLater
            val earlierName = earlier?.englishName ?: "Earlier prayer"
            val laterName = later?.englishName ?: "Later prayer"
            val earlierTime = if (earlier != null) PrayerTimeFormatter.formatTime(candidate.getTime(earlier), currentSettings.is24HourFormat, currentSettings.location.timezoneId) else ""
            val laterTime = if (later != null) PrayerTimeFormatter.formatTime(candidate.getTime(later), currentSettings.is24HourFormat, currentSettings.location.timezoneId) else ""
            val minStr = if (minutes > 0) "+$minutes" else "$minutes"

            _adjustmentErrorMessage.value = "Cannot apply $minStr min to ${prayerType.englishName}. $earlierName would become $earlierTime while $laterName is $laterTime."
            return false
        }

        _adjustmentErrorMessage.value = null
        val success = preferences.updatePrayerAdjustment(prayerType, minutes)
        if (success) {
            refreshAlarms()
        }
        return success
    }

    fun resetAllPrayerAdjustments() {
        _adjustmentErrorMessage.value = null
        preferences.resetAllPrayerAdjustments()
        refreshAlarms()
    }

    fun testAudio(sound: AthanSound) {
        if (_isAudioTesting.value) {
            stopAudio()
        } else {
            _isAudioTesting.value = true
            val started = AthanAudioManager.playSound(getApplication(), sound) {
                _isAudioTesting.value = false
            }
            if (!started) {
                _isAudioTesting.value = false
            }
        }
    }

    fun testAudio(soundType: String) {
        testAudio(AthanAudioCatalog.fromNameOrDefault(soundType))
    }

    fun stopAudio() {
        AthanAudioManager.stopSound()
        _isAudioTesting.value = false
    }

    fun refreshAlarms(): Boolean {
        refreshExactAlarmCapability()
        return AthanAlarmScheduler.scheduleRollingAlarms(getApplication())
    }

    fun refreshExactAlarmCapability(): Boolean {
        val canSchedule = AthanAlarmScheduler.canScheduleExactAlarms(getApplication())
        _canScheduleExactAlarms.value = canSchedule
        return canSchedule
    }

    fun canScheduleExactAlarms(): Boolean {
        return refreshExactAlarmCapability()
    }

    fun refreshNotificationPermission() {
        _isNotificationPermissionGranted.value = NotificationPermissionManager.hasNotificationPermission(getApplication())
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        _isNotificationPermissionGranted.value = granted
        markNotificationPermissionPrompted()
        // Note: Athan individual prayer settings are intentionally preserved.
    }

    fun hasPromptedNotificationPermission(): Boolean {
        return preferences.hasPromptedNotificationPermission()
    }

    fun markNotificationPermissionPrompted() {
        preferences.setNotificationPermissionPrompted(true)
    }

    override fun onCleared() {
        cancelGpsJobOnly()
        tickerJob?.cancel()
        midnightJob?.cancel()
        try {
            getApplication<Application>().unregisterReceiver(dateChangeReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
        AthanAudioManager.stopSound()
        super.onCleared()
    }
}
