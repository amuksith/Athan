package com.athan.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.athan.app.audio.AthanSound
import com.athan.app.core.astronomy.PrayerType
import com.athan.app.data.repository.AthanPreferences
import com.athan.app.scheduling.AthanAlarmScheduler
import com.athan.app.service.AthanAudioService

class AthanAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AthanAlarmReceiver"

        const val ACTION_PRAYER_ALARM = "com.athan.app.ACTION_PRAYER_ALARM"
        const val EXTRA_PRAYER_NAME = "prayer_name"
        const val EXTRA_ARABIC_NAME = "arabic_name"
        const val EXTRA_PRAYER_TYPE = "prayer_type"
        const val EXTRA_IS_PRE_REMINDER = "is_pre_reminder"

        @Volatile
        var lastFiredPrayer: PrayerType? = null
            internal set
        @Volatile
        var lastFiredTimestamp: Long = 0L
            internal set
        const val DEBOUNCE_WINDOW_MS = 120_000L

        fun resetDebounceState() {
            lastFiredPrayer = null
            lastFiredTimestamp = 0L
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val prayerTypeName = intent.getStringExtra(EXTRA_PRAYER_TYPE) ?: return
        val prayerType = try { PrayerType.valueOf(prayerTypeName) } catch (e: Exception) { return }
        val prayerName = intent.getStringExtra(EXTRA_PRAYER_NAME) ?: prayerType.englishName
        val arabicName = intent.getStringExtra(EXTRA_ARABIC_NAME) ?: prayerType.arabicName
        val isPreReminder = intent.getBooleanExtra(EXTRA_IS_PRE_REMINDER, false)

        val prefs = AthanPreferences.getInstance(context)
        val settings = prefs.settingsFlow.value

        // Check if user enabled notification for this prayer
        val isEnabled = settings.prayerNotifications[prayerType] ?: true
        if (!isEnabled) return

        val currentTime = System.currentTimeMillis()
        if (!isPreReminder && lastFiredPrayer == prayerType && (currentTime - lastFiredTimestamp) < DEBOUNCE_WINDOW_MS) {
            Log.w(TAG, "Dropping duplicate alarm trigger for $prayerType ($currentTime - $lastFiredTimestamp < $DEBOUNCE_WINDOW_MS)")
            return
        }
        if (!isPreReminder) {
            lastFiredPrayer = prayerType
            lastFiredTimestamp = currentTime
        }

        if (!isPreReminder) {
            val sound = settings.sound
            // Start Foreground Service with Athan notification and audio
            AthanAudioService.startAthan(
                context = context,
                prayerName = prayerName,
                arabicName = arabicName,
                cityName = settings.location.cityName,
                sound = sound
            )
        }

        // Reschedule alarms to keep the rolling window fresh
        AthanAlarmScheduler.scheduleRollingAlarms(context)
    }
}

