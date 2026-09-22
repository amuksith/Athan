package com.athan.app.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationPermissionManager {

    const val PERMISSION_POST_NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS

    /**
     * Determines whether Android runtime notification permission is required by the OS.
     * Only Android 13 (API 33, Tiramisu) and above require runtime POST_NOTIFICATIONS.
     */
    fun isRuntimePermissionRequired(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    /**
     * Checks if the app currently has permission to post notifications.
     * On Android 13+, checks POST_NOTIFICATIONS runtime permission.
     * On Android 12 and below, checks NotificationManagerCompat.areNotificationsEnabled().
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    /**
     * Checks if POST_NOTIFICATIONS runtime permission is specifically granted.
     * For API < 33, always returns true as runtime permission does not exist or apply.
     */
    fun isPostNotificationsGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Determines whether the app should prompt for runtime permission during the controlled initial launch.
     * Returns true ONLY on Android 13+ when runtime permission is NOT granted and has NOT yet been prompted.
     */
    fun shouldPromptInitialPermission(
        context: Context,
        hasAlreadyPrompted: Boolean
    ): Boolean {
        if (!isRuntimePermissionRequired()) return false
        if (hasAlreadyPrompted) return false
        return !isPostNotificationsGranted(context)
    }

    /**
     * Determines if permission was permanently denied by the user.
     * On Android 13+, if it was prompted once and shouldShowRequestPermissionRationale is false,
     * the system dialog will no longer appear, so user must be routed to system settings.
     */
    fun isPermanentlyDenied(activity: Activity?, hasPromptedOnce: Boolean): Boolean {
        if (!isRuntimePermissionRequired()) return false
        if (!hasPromptedOnce) return false
        if (activity == null) return false
        return !ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }

    /**
     * Creates an Intent to open the notification settings for the application.
     */
    fun createNotificationSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
