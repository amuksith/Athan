package com.athan.app.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.athan.app.MainActivity
import com.athan.app.core.astronomy.AstronomicalEngine
import com.athan.app.core.astronomy.PrayerTimeFormatter
import com.athan.app.core.astronomy.PrayerTimes
import com.athan.app.core.astronomy.PrayerType
import com.athan.app.core.astronomy.TimeZoneResolver
import com.athan.app.data.repository.AthanPreferences
import com.athan.app.receiver.AthanAlarmReceiver
import com.athan.app.util.ExactAlarmPermissionManager
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object AthanAlarmScheduler {

    private const val TAG = "AthanAlarmScheduler"

    private const val PREFS_SCHEDULER = "athan_scheduler_internal"
    private const val KEY_ACTIVE_BANK = "active_bank_index"
    private const val KEY_SCHEDULED_REQUEST_CODES = "scheduled_request_codes"

    const val BANK_0_BASE = 10000
    const val BANK_1_BASE = 10100
    const val BANK_CAPACITY = 100

    // Fixed request code dedicated to the self-recovery watchdog alarm
    const val WATCHDOG_REQUEST_CODE = 10999

    const val ROBUST_MAX_DAYS = 7
    const val ROLLING_DAYS = 3

    // Prayers within 45 seconds of 'now' are considered currently firing / elapsed
    // and must NOT be rescheduled as future alarms during the same cycle.
    const val ALARM_RESCHEDULE_BUFFER_MS = 45_000L

    // Preserved for backwards compatibility with any existing external code
    const val REQUEST_CODE_BASE = BANK_0_BASE

    @Volatile
    var appContext: Context? = null

    // Synchronization lock to ensure thread safety across concurrent lifecycle and receiver events
    val scheduleLock = Any()

    /**
     * Test hook for verifying atomic transactional rollback behavior.
     * When set, called during scheduling before committing the new schedule.
     */
    @Volatile
    var testSchedulingHook: ((index: Int, total: Int, item: StagedAlarm) -> Unit)? = null

    /**
     * Test hook called during Phase A (schedule construction/validation) before any scheduling or cancellation.
     */
    @Volatile
    var testPreSchedulingHook: (() -> Unit)? = null

    data class StagedAlarm(
        val requestCode: Int,
        val triggerAtMillis: Long,
        val prayerType: PrayerType,
        val isPreReminder: Boolean,
        val dateStr: String
    )

    fun canScheduleExactAlarms(context: Context): Boolean {
        return ExactAlarmPermissionManager.canScheduleExactAlarms(context)
    }

    /**
     * Computes a unique, deterministic request code for a given bank, day offset, prayer type, and alarm kind.
     * bankIndex: 0 (BANK_0_BASE: 10000..10099) or 1 (BANK_1_BASE: 10100..10199)
     * dayOffset: 0..ROLLING_DAYS-1 (or up to ROBUST_MAX_DAYS-1)
     * prayerType: FAJR..ISHA
     * isPreReminder: false for exact prayer Athan, true for pre-prayer reminder
     */
    fun getRequestCode(
        bankIndex: Int,
        dayOffset: Int,
        prayerType: PrayerType,
        isPreReminder: Boolean
    ): Int {
        require(bankIndex == 0 || bankIndex == 1) { "bankIndex must be 0 or 1, got $bankIndex" }

        val base = if (bankIndex == 1) {
            BANK_1_BASE
        } else {
            BANK_0_BASE
        }

        val prayerIndex = prayerType.ordinal
        val typeOffset = if (isPreReminder) 1 else 0

        val code =
            base +
                ((dayOffset * PrayerType.entries.size + prayerIndex) * 2) +
                typeOffset

        require(code in base until (base + BANK_CAPACITY)) {
            "Generated request code $code is outside bank capacity ($base until ${base + BANK_CAPACITY})"
        }

        return code
    }

    /**
     * Convenience overload querying the active bank from the provided Context.
     */
    fun getRequestCode(
        context: Context,
        dayOffset: Int,
        prayerType: PrayerType,
        isPreReminder: Boolean
    ): Int {
        return getRequestCode(
            getActiveBankIndex(context),
            dayOffset,
            prayerType,
            isPreReminder
        )
    }

    /**
     * Convenience overload querying the active bank using appContext (or default bank 0).
     */
    fun getRequestCode(
        dayOffset: Int,
        prayerType: PrayerType,
        isPreReminder: Boolean
    ): Int {
        return getRequestCode(
            getActiveBankIndex(appContext),
            dayOffset,
            prayerType,
            isPreReminder
        )
    }

    /**
     * Returns the currently active bank index (0 or 1).
     * If the persisted value is invalid, recovers safely and deterministically to 0.
     */
    fun getActiveBankIndex(context: Context?): Int {
        val ctx = context ?: appContext ?: return 0
        appContext = ctx.applicationContext
        val prefs = ctx.getSharedPreferences(PREFS_SCHEDULER, Context.MODE_PRIVATE)
        val bank = prefs.getInt(KEY_ACTIVE_BANK, 0)
        return if (bank == 0 || bank == 1) {
            bank
        } else {
            Log.w(TAG, "Invalid active bank index persisted: $bank. Recovering safely to default bank 0.")
            prefs.edit().putInt(KEY_ACTIVE_BANK, 0).apply()
            0
        }
    }

    /**
     * Returns all potential request codes belonging to a given bank.
     */
    fun getBankRequestCodes(bankIndex: Int): Set<Int> {
        require(bankIndex == 0 || bankIndex == 1) { "bankIndex must be 0 or 1, got $bankIndex" }
        val base = if (bankIndex == 1) BANK_1_BASE else BANK_0_BASE
        return (base until (base + BANK_CAPACITY)).toSet()
    }

    /**
     * Cancels all alarms belonging to a specific bank in AlarmManager.
     */
    fun cancelBankAlarms(context: Context, bankIndex: Int): Int {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return 0
        val codes = getBankRequestCodes(bankIndex)
        return cancelAlarmsByRequestCodes(context, alarmManager, codes)
    }

    /**
     * Cancels specific alarms from AlarmManager by their request codes.
     */
    private fun cancelAlarmsByRequestCodes(
        context: Context,
        alarmManager: AlarmManager,
        codesToCancel: Set<Int>
    ): Int {
        var cancelledCount = 0
        for (code in codesToCancel) {
            val intent = Intent(context, AthanAlarmReceiver::class.java).apply {
                action = AthanAlarmReceiver.ACTION_PRAYER_ALARM
            }

            // Check for existing PendingIntent with FLAG_NO_CREATE
            val existingPi = PendingIntent.getBroadcast(
                context,
                code,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )

            if (existingPi != null) {
                alarmManager.cancel(existingPi)
                existingPi.cancel()
                cancelledCount++
                Log.d(TAG, "Cancelled existing alarm: requestCode=$code")
            } else {
                // Reconstruct matching PendingIntent with FLAG_UPDATE_CURRENT to ensure AlarmManager cleans up
                val cancelPi = PendingIntent.getBroadcast(
                    context,
                    code,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(cancelPi)
                cancelPi.cancel()
            }
        }
        return cancelledCount
    }

    /**
     * Returns all potential candidate codes across Bank 0, Bank 1, and historical legacy codes.
     */
    fun getCandidateSlotAndLegacyCodes(): Set<Int> {
        val candidateCodes = mutableSetOf<Int>()
        // All Bank 0 request codes
        candidateCodes.addAll(getBankRequestCodes(0))
        // All Bank 1 request codes
        candidateCodes.addAll(getBankRequestCodes(1))
        // Historical legacy codes from pre-dual-bank implementation (historical range ran up to 10200)
        for (legacyCode in (BANK_1_BASE + BANK_CAPACITY)..10200) {
            candidateCodes.add(legacyCode)
        }
        return candidateCodes
    }

    /**
     * Cancels all alarms previously created by this app in AlarmManager across both banks and legacy ranges.
     * Reconstructs the exact PendingIntent identity (matching component, action, and request code).
     */
    fun cancelAllAlarms(context: Context) {
        synchronized(scheduleLock) {
            appContext = context.applicationContext
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val schedulerPrefs = context.getSharedPreferences(PREFS_SCHEDULER, Context.MODE_PRIVATE)

            // Gather all possible request codes to cancel:
            // 1. All explicitly registered codes from previous schedules
            val candidateCodes = mutableSetOf<Int>()
            val storedCodes = schedulerPrefs.getStringSet(KEY_SCHEDULED_REQUEST_CODES, emptySet())
            storedCodes?.forEach { str ->
                str.toIntOrNull()?.let { candidateCodes.add(it) }
            }

            // 2. All candidate slot codes from Bank 0, Bank 1, and legacy codes
            candidateCodes.addAll(getCandidateSlotAndLegacyCodes())

            Log.d(TAG, "Starting cancellation of existing Athan alarms. Candidate IDs count: ${candidateCodes.size}")
            val cancelledCount = cancelAlarmsByRequestCodes(context, alarmManager, candidateCodes)

            // Cancel any watchdog alarm as part of full cancellation
            cancelWatchdogAlarm(context, alarmManager)

            // Clear persistent registry and reset active bank to 0
            schedulerPrefs.edit()
                .remove(KEY_SCHEDULED_REQUEST_CODES)
                .putInt(KEY_ACTIVE_BANK, 0)
                .apply()
            Log.d(TAG, "Finished cancellation: cancelled $cancelledCount active alarms. Registry cleared, bank reset to 0.")
        }
    }

    /**
     * Schedules a rolling 3-day window of exact prayer alarms using a Dual-Bank transactional architecture.
     *
     * Transactional Sequence:
     * 1. Check exact-alarm capability. If revoked, cancel all alarms from both banks and clear registry.
     * 2. Read and validate current settings. If invalid, preserve existing alarms.
     * 3. Phase A (Prepare/Staging):
     *    - Read activeBank (0 or 1).
     *    - Calculate targetBank = 1 - activeBank.
     *    - Construct and validate the replacement schedule in memory using targetBank request codes.
     *    - Do NOT modify active bank or registry state during this phase.
     * 4. Phase B (Stage Target Bank):
     *    - Schedule every new alarm into the inactive target bank.
     *    - If scheduling fails or hook throws:
     *      * Cancel ONLY alarms belonging to targetBank that were scheduled during this attempt.
     *      * Do NOT cancel or modify activeBank alarms.
     *      * Do NOT modify active-bank registry or KEY_ACTIVE_BANK.
     *      * Return false.
     * 5. Phase C (Atomic Commit):
     *    - Cancel all alarms belonging to the old active bank.
     *    - Clean up any legacy codes.
     *    - Persist KEY_ACTIVE_BANK = targetBank and KEY_SCHEDULED_REQUEST_CODES = targetBankRequestCodes.
     *    - Return true.
     *
     * This operation is fully idempotent, transactional, and thread-safe.
     */
    fun scheduleRollingAlarms(context: Context): Boolean {
        return scheduleRollingAlarms(context, System.currentTimeMillis())
    }

    fun scheduleRollingAlarms(context: Context, nowMillis: Long): Boolean {
        synchronized(scheduleLock) {
            appContext = context.applicationContext
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false

            // Step 0: Check exact-alarm capability immediately before attempting to schedule
            if (!canScheduleExactAlarms(context)) {
                Log.w(TAG, "Exact-alarm capability unavailable (restricted or revoked). Cancelling all active alarms.")
                cancelAllAlarms(context)
                return false
            }

            Log.d(TAG, "Step 1: Reading and validating current settings.")
            val prefs = AthanPreferences.getInstance(context)
            val settings = prefs.settingsFlow.value
            val location = settings.location

            if (!location.isValid()) {
                Log.w(TAG, "scheduleRollingAlarms aborted: invalid location ($location). Preserving existing alarms.")
                return false
            }

            val tz = TimeZoneResolver.resolve(location.timezoneId)
            val now = nowMillis
            val calendar = Calendar.getInstance(tz).apply { timeInMillis = now }

            // Phase A: In-memory schedule construction & validation
            val schedulerPrefs = context.getSharedPreferences(PREFS_SCHEDULER, Context.MODE_PRIVATE)
            val activeBank = getActiveBankIndex(context)
            val targetBank = 1 - activeBank
            val targetBase = if (targetBank == 1) BANK_1_BASE else BANK_0_BASE

            Log.d(
                TAG,
                "Phase A: Building replacement schedule in-memory for next $ROLLING_DAYS days in targetBank $targetBank (activeBank is $activeBank)."
            )
            val stagedAlarms = mutableListOf<StagedAlarm>()

            try {
                testPreSchedulingHook?.invoke()
                for (dayOffset in 0 until ROLLING_DAYS) {
                    val targetCal = (calendar.clone() as Calendar).apply {
                        add(Calendar.DAY_OF_YEAR, dayOffset)
                    }
                    val year = targetCal.get(Calendar.YEAR)
                    val month = targetCal.get(Calendar.MONTH) + 1
                    val day = targetCal.get(Calendar.DAY_OF_MONTH)
                    val dateStr = String.format(Locale.US, "%04d-%02d-%02d", year, month, day)

                    val rawPrayerTimes = AstronomicalEngine.calculate(
                        year = year,
                        month = month,
                        day = day,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        elevationMeters = location.elevationMeters,
                        method = settings.method,
                        madhab = settings.madhab,
                        highLatitudeRule = settings.highLatitudeRule,
                        customFajrAngle = settings.customFajrAngle,
                        customIshaAngle = settings.customIshaAngle,
                        timeZone = tz,
                        hijriDayAdjustment = settings.hijriDayAdjustment
                    )
                    val prayerTimes = rawPrayerTimes.withValidatedAdjustments(settings.prayerAdjustments)

                    for (prayer in prayerTimes.getAllPrayers()) {
                        val prayerType = prayer.first
                        val prayerTimeMillis = prayer.second

                        // Unavailable prayers (TIME_UNAVAILABLE = 0L) must never create an alarm
                        if (prayerTimeMillis <= PrayerTimes.TIME_UNAVAILABLE) {
                            Log.d(TAG, "Prayer ${prayerType.englishName} on $dateStr is unavailable (TIME_UNAVAILABLE). Skipping alarm.")
                            continue
                        }

                        // Only schedule if user wants notification for this prayer
                        val isPrayerEnabled = settings.prayerNotifications[prayerType] ?: true
                        if (!isPrayerEnabled) {
                            Log.d(TAG, "Prayer ${prayerType.englishName} on $dateStr is disabled by user settings. Skipping.")
                            continue
                        }

                        // 1. Pre-prayer reminder (if configured)
                        if (settings.reminderMinutesBefore > 0) {
                            val reminderTimeMillis = prayerTimeMillis - (settings.reminderMinutesBefore * 60 * 1000L)
                            if (reminderTimeMillis > (now + ALARM_RESCHEDULE_BUFFER_MS)) {
                                val requestCode = getRequestCode(targetBank, dayOffset, prayerType, isPreReminder = true)
                                require(requestCode in targetBase until (targetBase + BANK_CAPACITY)) {
                                    "Request code $requestCode outside target bank $targetBank ($targetBase..${targetBase + BANK_CAPACITY})"
                                }
                                stagedAlarms.add(
                                    StagedAlarm(
                                        requestCode = requestCode,
                                        triggerAtMillis = reminderTimeMillis,
                                        prayerType = prayerType,
                                        isPreReminder = true,
                                        dateStr = dateStr
                                    )
                                )
                            }
                        }

                        // 2. Exact Prayer Time Athan
                        if (prayerTimeMillis > (now + ALARM_RESCHEDULE_BUFFER_MS)) {
                            val requestCode = getRequestCode(targetBank, dayOffset, prayerType, isPreReminder = false)
                            require(requestCode in targetBase until (targetBase + BANK_CAPACITY)) {
                                "Request code $requestCode outside target bank $targetBank ($targetBase..${targetBase + BANK_CAPACITY})"
                            }
                            stagedAlarms.add(
                                StagedAlarm(
                                    requestCode = requestCode,
                                    triggerAtMillis = prayerTimeMillis,
                                    prayerType = prayerType,
                                    isPreReminder = false,
                                    dateStr = dateStr
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error building replacement schedule: ${e.message}. Aborting without cancelling old schedule.", e)
                return false
            }

            // If the staged schedule is completely empty (e.g. all prayers disabled or past),
            // safely cancel all previous alarms from both banks, clear registry, persist deterministic bank 0,
            // schedule watchdog alarm for next local midnight, and return true.
            if (stagedAlarms.isEmpty()) {
                Log.d(TAG, "Replacement schedule is completely empty. Cancelling all existing alarms from both banks and scheduling midnight watchdog.")
                cancelAllAlarms(context)
                scheduleWatchdogAlarm(context, tz, now)
                return true
            }

            // Phase B: Stage Target Bank
            Log.d(TAG, "Phase B: Staging ${stagedAlarms.size} alarms in targetBank $targetBank (activeBank remains $activeBank).")
            val newlyScheduledTargetCodes = mutableSetOf<Int>()
            var schedulingFailed = false

            for ((index, item) in stagedAlarms.withIndex()) {
                require(item.requestCode in targetBase until (targetBase + BANK_CAPACITY)) {
                    "Illegal request code ${item.requestCode} outside targetBank $targetBank bounds!"
                }

                val pendingIntent = createAlarmPendingIntent(
                    context = context,
                    requestCode = item.requestCode,
                    prayerType = item.prayerType,
                    isPreReminder = item.isPreReminder
                )

                // Test hook invocation if registered (simulates mid-schedule failures)
                testSchedulingHook?.let { hook ->
                    try {
                        hook(index, stagedAlarms.size, item)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Test scheduling hook threw exception on item $index: ${t.message}")
                        schedulingFailed = true
                    }
                }

                if (schedulingFailed) break

                val scheduled = setExactAlarm(alarmManager, item.triggerAtMillis, pendingIntent)
                if (scheduled) {
                    newlyScheduledTargetCodes.add(item.requestCode)
                    Log.d(
                        TAG,
                        "Target staged alarm scheduled: prayer=${item.prayerType.englishName}, isPreReminder=${item.isPreReminder}, requestCode=${item.requestCode}, time=${item.triggerAtMillis}"
                    )
                } else {
                    Log.e(TAG, "Failed to schedule exact alarm for requestCode=${item.requestCode} in target bank. Initiating rollback.")
                    schedulingFailed = true
                    break
                }
            }

            if (schedulingFailed) {
                Log.w(
                    TAG,
                    "Transactional scheduling failed. Rolling back ${newlyScheduledTargetCodes.size} target-bank alarms. Active bank $activeBank alarms are untouched."
                )
                // Cancel ONLY the target bank alarms scheduled during this attempt
                cancelAlarmsByRequestCodes(context, alarmManager, newlyScheduledTargetCodes)
                // Active bank, registry, and preferences remain completely unmodified
                return false
            }

            // Phase C: Commit
            // Only after all target-bank alarms have been scheduled successfully,
            // cancel all alarms belonging to the old active bank.
            Log.d(TAG, "Phase C: Committing targetBank $targetBank. Cancelling old activeBank $activeBank alarms.")
            val activeBankCodes = getBankRequestCodes(activeBank)
            cancelAlarmsByRequestCodes(context, alarmManager, activeBankCodes)

            // Cancel any previous registry codes that do not belong to the target bank
            val previouslyScheduledCodes = schedulerPrefs.getStringSet(KEY_SCHEDULED_REQUEST_CODES, emptySet())
                ?.mapNotNull { it.toIntOrNull() }?.filter { it !in newlyScheduledTargetCodes }?.toSet() ?: emptySet()
            if (previouslyScheduledCodes.isNotEmpty()) {
                cancelAlarmsByRequestCodes(context, alarmManager, previouslyScheduledCodes)
            }

            // Clean up historical legacy codes outside both banks
            val legacyCodes = getCandidateSlotAndLegacyCodes()
                .filter { it !in getBankRequestCodes(targetBank) && it !in activeBankCodes }
                .toSet()
            if (legacyCodes.isNotEmpty()) {
                cancelAlarmsByRequestCodes(context, alarmManager, legacyCodes)
            }

            // Cancel any previous watchdog alarm since the new schedule has valid upcoming prayer alarms
            cancelWatchdogAlarm(context, alarmManager)

            // Persist the new active bank and new registry
            val stringCodesSet = newlyScheduledTargetCodes.map { it.toString() }.toSet()
            schedulerPrefs.edit()
                .putInt(KEY_ACTIVE_BANK, targetBank)
                .putStringSet(KEY_SCHEDULED_REQUEST_CODES, stringCodesSet)
                .apply()

            Log.d(
                TAG,
                "Dual-bank transition committed successfully: active bank is now $targetBank with ${newlyScheduledTargetCodes.size} alarms."
            )
            return true
        }
    }

    /**
     * Schedules a lightweight self-recovery watchdog alarm at the next local midnight
     * when the prayer schedule is completely empty.
     * Fires BootAndClockReceiver to re-evaluate the schedule without triggering an Athan notification.
     */
    fun scheduleWatchdogAlarm(context: Context, timeZone: TimeZone, nowMillis: Long = System.currentTimeMillis()) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val midnightMillis = getNextLocalMidnightMillis(nowMillis, timeZone)
        val watchdogIntent = createWatchdogPendingIntent(context)
        val scheduled = setExactAlarm(alarmManager, midnightMillis, watchdogIntent)
        Log.d(TAG, "Scheduled self-recovery watchdog alarm at $midnightMillis (scheduled=$scheduled)")
    }

    /**
     * Cancels any active self-recovery watchdog alarm in AlarmManager.
     */
    fun cancelWatchdogAlarm(context: Context, alarmManager: AlarmManager? = null) {
        val am = alarmManager ?: context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val watchdogIntent = Intent(context, com.athan.app.receiver.BootAndClockReceiver::class.java).apply {
            action = Intent.ACTION_TIME_CHANGED
        }
        val existingPi = PendingIntent.getBroadcast(
            context,
            WATCHDOG_REQUEST_CODE,
            watchdogIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (existingPi != null) {
            am.cancel(existingPi)
            existingPi.cancel()
            Log.d(TAG, "Cancelled active self-recovery watchdog alarm (requestCode=$WATCHDOG_REQUEST_CODE)")
        } else {
            val cancelPi = PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST_CODE,
                watchdogIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(cancelPi)
            cancelPi.cancel()
        }
    }

    private fun createWatchdogPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, com.athan.app.receiver.BootAndClockReceiver::class.java).apply {
            action = Intent.ACTION_TIME_CHANGED
        }
        return PendingIntent.getBroadcast(
            context,
            WATCHDOG_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getNextLocalMidnightMillis(currentTimeMillis: Long, timeZone: TimeZone): Long {
        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = currentTimeMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun setExactAlarm(
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
            true
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException while scheduling exact alarm: ${se.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Exception while scheduling exact alarm: ${e.message}")
            false
        }
    }

    private fun createAlarmPendingIntent(
        context: Context,
        requestCode: Int,
        prayerType: PrayerType,
        isPreReminder: Boolean
    ): PendingIntent {
        val intent = Intent(context, AthanAlarmReceiver::class.java).apply {
            action = AthanAlarmReceiver.ACTION_PRAYER_ALARM
            putExtra(AthanAlarmReceiver.EXTRA_PRAYER_TYPE, prayerType.name)
            putExtra(AthanAlarmReceiver.EXTRA_PRAYER_NAME, prayerType.englishName)
            putExtra(AthanAlarmReceiver.EXTRA_ARABIC_NAME, prayerType.arabicName)
            putExtra(AthanAlarmReceiver.EXTRA_IS_PRE_REMINDER, isPreReminder)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun getScheduledRequestCodes(context: Context): Set<Int> {
        val schedulerPrefs = context.getSharedPreferences(PREFS_SCHEDULER, Context.MODE_PRIVATE)
        val stored = schedulerPrefs.getStringSet(KEY_SCHEDULED_REQUEST_CODES, emptySet()) ?: emptySet()
        return stored.mapNotNull { it.toIntOrNull() }.toSet()
    }
}
