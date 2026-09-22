package com.athan.app.receiver

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.athan.app.data.repository.AthanPreferences
import com.athan.app.scheduling.AthanAlarmScheduler
import java.util.TimeZone

class BootAndClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val isExactAlarmPermissionChanged = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        } else {
            false
        }

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == "android.intent.action.TIME_SET" ||
            action == "android.intent.action.TIME_CHANGED" ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            isExactAlarmPermissionChanged
        ) {
            // Recompute astronomical prayer times and reschedule the exact alarm rolling window.
            // The selected location timezone must remain untouched when the device timezone changes.
            AthanAlarmScheduler.scheduleRollingAlarms(context)
        }
    }
}
