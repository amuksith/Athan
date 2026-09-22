package com.athan.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import com.athan.app.MainActivity
import com.athan.app.R
import com.athan.app.audio.AthanAudioCatalog
import com.athan.app.audio.AthanAudioManager
import com.athan.app.audio.AthanSound
import com.athan.app.data.repository.AthanPreferences

class AthanAudioService : Service() {

    companion object {
        const val CHANNEL_ID = "athan_prayer_playback_v2"
        const val NOTIFICATION_ID = 9991
        const val ACTION_PLAY_ATHAN = "com.athan.app.ACTION_PLAY_ATHAN"
        const val ACTION_STOP_ATHAN = "com.athan.app.ACTION_STOP_ATHAN"

        const val EXTRA_PRAYER_NAME = "extra_prayer_name"
        const val EXTRA_ARABIC_NAME = "extra_arabic_name"
        const val EXTRA_CITY_NAME = "extra_city_name"
        const val EXTRA_SOUND_TYPE = "extra_sound_type"

        fun startAthan(
            context: Context,
            prayerName: String,
            arabicName: String,
            cityName: String,
            sound: AthanSound
        ) {
            val intent = Intent(context, AthanAudioService::class.java).apply {
                action = ACTION_PLAY_ATHAN
                putExtra(EXTRA_PRAYER_NAME, prayerName)
                putExtra(EXTRA_ARABIC_NAME, arabicName)
                putExtra(EXTRA_CITY_NAME, cityName)
                putExtra(EXTRA_SOUND_TYPE, sound.name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startAthan(
            context: Context,
            prayerName: String,
            arabicName: String,
            cityName: String,
            soundType: String
        ) {
            startAthan(
                context = context,
                prayerName = prayerName,
                arabicName = arabicName,
                cityName = cityName,
                sound = AthanAudioCatalog.fromNameOrDefault(soundType)
            )
        }

        fun stopAthan(context: Context) {
            val intent = Intent(context, AthanAudioService::class.java).apply {
                action = ACTION_STOP_ATHAN
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ATHAN) {
            stopAudioAndFinish()
            return START_NOT_STICKY
        }

        val prayerName = intent?.getStringExtra(EXTRA_PRAYER_NAME) ?: "Prayer"
        val arabicName = intent?.getStringExtra(EXTRA_ARABIC_NAME) ?: ""
        val cityName = intent?.getStringExtra(EXTRA_CITY_NAME) ?: "Your Location"
        val soundStr = intent?.getStringExtra(EXTRA_SOUND_TYPE)
        val sound = AthanAudioCatalog.fromNameOrDefault(soundStr)

        val notification = buildForegroundNotification(prayerName, arabicName, cityName)
        startForeground(NOTIFICATION_ID, notification)

        // Vibrate gently
        triggerVibration()

        // Play audio via centralized catalog & manager
        if (sound == AthanSound.SILENT || !AthanAudioCatalog.isAvailable(sound)) {
            // Audio is suppressed: leave notification detached/visible and stop service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            }
            stopSelf()
        } else {
            val played = AthanAudioManager.playSound(this, sound) {
                stopAudioAndFinish()
            }
            if (!played) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                }
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun stopAudioAndFinish() {
        AthanAudioManager.stopSound()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        AthanAudioManager.stopSound()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try {
                notificationManager.deleteNotificationChannel("athan_prayer_notifications")
            } catch (e: Exception) {
                // Ignore
            }

            val name = "Athan Prayer Times"
            val descriptionText = "Notifications for Islamic prayer times and Athan call"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                // Audio is handled by AthanAudioManager to prevent overlapping system chimes
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(prayerName: String, arabicName: String, cityName: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AthanAudioService::class.java).apply {
            action = ACTION_STOP_ATHAN
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (arabicName.isNotBlank()) "Time for $prayerName • $arabicName" else "Time for $prayerName"
        val message = "It is now prayer time in $cityName. Tap to open Athan."

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Athan", stopPendingIntent)
            .setDeleteIntent(stopPendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .build()
    }

    private fun triggerVibration() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = VibrationEffect.createWaveform(longArrayOf(0, 500, 300, 500), -1)
                    vibrator.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 500, 300, 500), -1)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
