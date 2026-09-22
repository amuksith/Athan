package com.athan.app.ui.components

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.athan.app.audio.AthanAudioCatalog
import com.athan.app.audio.AthanSound
import com.athan.app.core.astronomy.CalculationMethod
import com.athan.app.core.astronomy.HighLatitudeRule
import com.athan.app.core.astronomy.Madhab
import com.athan.app.core.astronomy.PrayerTimeFormatter
import com.athan.app.core.astronomy.PrayerTimes
import com.athan.app.core.astronomy.PrayerType
import com.athan.app.data.repository.AthanSettings
import com.athan.app.ui.theme.GoldAccent
import com.athan.app.util.InputValidator
import com.athan.app.util.ValidationResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AthanSettings,
    todayPrayers: PrayerTimes? = null,
    isNotificationPermissionGranted: Boolean = true,
    onRequestNotificationPermission: () -> Unit = {},
    canScheduleExactAlarms: Boolean = true,
    onRequestExactAlarmPermission: () -> Unit = {},
    onMethodSelected: (CalculationMethod) -> Unit,
    onMadhabSelected: (Madhab) -> Unit,
    onHighLatitudeRuleSelected: (HighLatitudeRule) -> Unit,
    onCustomAnglesChanged: (Double, Double) -> Unit,
    onSoundSelected: (AthanSound) -> Unit,
    onReminderMinutesChanged: (Int) -> Unit,
    onTimeFormatChanged: (Boolean) -> Unit,
    onHijriAdjustmentChanged: (Int) -> Unit,
    onPrayerAdjustmentChanged: (PrayerType, Int) -> Unit = { _, _ -> },
    onResetAllPrayerAdjustments: () -> Unit = {},
    adjustmentErrorMessage: String? = null,
    onTestAudio: (String) -> Unit,
    isAudioTesting: Boolean,
    onAboutClick: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var showMethodDialog by remember { mutableStateOf(false) }
    var showMadhabDialog by remember { mutableStateOf(false) }
    var showHighLatDialog by remember { mutableStateOf(false) }
    var showSoundDialog by remember { mutableStateOf(false) }
    var showCustomAnglesDialog by remember { mutableStateOf(false) }
    var showCustomReminderDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Calculation & Preferences",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Astronomical algorithms & local settings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Section 1: Calculation Parameters
            Text(
                text = "CALCULATION PARAMETERS",
                style = MaterialTheme.typography.labelSmall,
                color = GoldAccent,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Method
            SettingClickableRow(
                title = "Calculation Method",
                subtitle = settings.method.displayName,
                icon = Icons.Default.Calculate,
                onClick = { showMethodDialog = true }
            )

            if (settings.method == CalculationMethod.Custom) {
                SettingClickableRow(
                    title = "Custom Angles",
                    subtitle = "Fajr: ${settings.customFajrAngle}°, Isha: ${settings.customIshaAngle}°",
                    icon = Icons.Default.Tune,
                    onClick = { showCustomAnglesDialog = true }
                )
            }

            // Madhab
            SettingClickableRow(
                title = "Juristical Method (Madhab)",
                subtitle = settings.madhab.displayName,
                icon = Icons.Default.AccountBalance,
                onClick = { showMadhabDialog = true }
            )

            // High Latitude Rule
            SettingClickableRow(
                title = "High Latitude Adjustment",
                subtitle = settings.highLatitudeRule.displayName,
                icon = Icons.Default.Public,
                onClick = { showHighLatDialog = true }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Section 2: Audio & Reminders
            Text(
                text = "AUDIO & ALERTS",
                style = MaterialTheme.typography.labelSmall,
                color = GoldAccent,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Notification Permission Status
            SettingClickableRow(
                title = "Notification Permission",
                subtitle = if (isNotificationPermissionGranted) "Allowed by system" else "Blocked by Android (tap to enable)",
                icon = if (isNotificationPermissionGranted) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                onClick = {
                    if (!isNotificationPermissionGranted) {
                        onRequestNotificationPermission()
                    }
                }
            )

            // Exact Alarm Permission Status (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SettingClickableRow(
                    title = "Exact Alarm Permission",
                    subtitle = if (canScheduleExactAlarms) "Allowed by system" else "Restricted by Android (tap to enable)",
                    icon = Icons.Default.Alarm,
                    onClick = {
                        if (!canScheduleExactAlarms) {
                            onRequestExactAlarmPermission()
                        }
                    }
                )
            }

            // Sound Selector
            SettingClickableRow(
                title = "Athan Audio Tone",
                subtitle = settings.sound.displayName,
                icon = Icons.Default.VolumeUp,
                onClick = { showSoundDialog = true }
            )

            // Audio Test button
            OutlinedButton(
                onClick = { onTestAudio(settings.sound.id) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = if (isAudioTesting) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isAudioTesting) "Stop Audio Test" else "Preview ${settings.sound.displayName}")
            }

            // Pre-prayer reminder chips
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = "Pre-Prayer Reminder Alert",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val intervals = listOf(0 to "None", 5 to "5 min", 10 to "10 min", 15 to "15 min")
                    intervals.forEach { (mins, label) ->
                        FilterChip(
                            selected = settings.reminderMinutesBefore == mins,
                            onClick = { onReminderMinutesChanged(mins) },
                            label = { Text(label) }
                        )
                    }
                    val isCustomReminder = settings.reminderMinutesBefore !in listOf(0, 5, 10, 15)
                    FilterChip(
                        selected = isCustomReminder,
                        onClick = { showCustomReminderDialog = true },
                        label = { Text(if (isCustomReminder) "${settings.reminderMinutesBefore} min" else "Custom…") }
                    )
                }
            }

            // 24-Hour Format Switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("24-Hour Time Format", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("e.g. 13:45 instead of 1:45 PM", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = settings.is24HourFormat,
                    onCheckedChange = { onTimeFormatChanged(it) }
                )
            }

            // Hijri Day Adjustment
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Hijri Date Adjustment", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("Current offset: ${if (settings.hijriDayAdjustment > 0) "+${settings.hijriDayAdjustment}" else settings.hijriDayAdjustment} days", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = {
                            val next = settings.hijriDayAdjustment - 1
                            if (InputValidator.isValidHijriAdjustment(next)) {
                                onHijriAdjustmentChanged(next)
                            }
                        },
                        enabled = settings.hijriDayAdjustment > InputValidator.MIN_HIJRI_ADJUSTMENT,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("-")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalIconButton(
                        onClick = {
                            val next = settings.hijriDayAdjustment + 1
                            if (InputValidator.isValidHijriAdjustment(next)) {
                                onHijriAdjustmentChanged(next)
                            }
                        },
                        enabled = settings.hijriDayAdjustment < InputValidator.MAX_HIJRI_ADJUSTMENT,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("+")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section: Prayer Time Adjustments
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PRAYER TIME ADJUSTMENTS",
                    style = MaterialTheme.typography.labelSmall,
                    color = GoldAccent,
                    fontWeight = FontWeight.Bold
                )
                val hasNonZero = settings.prayerAdjustments.values.any { it != 0 }
                TextButton(
                    onClick = onResetAllPrayerAdjustments,
                    enabled = hasNonZero,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.testTag("reset_all_adjustments_button")
                ) {
                    Text("Reset all", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                text = "Fine-tune prayer times (-30 to +30 min) to match local mosques",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!adjustmentErrorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("adjustment_error_banner"),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Adjustment Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = adjustmentErrorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            PrayerType.entries.forEach { prayerType ->
                val currentAdj = settings.prayerAdjustments[prayerType] ?: 0
                val formattedTime = if (todayPrayers != null) {
                    PrayerTimeFormatter.formatTime(
                        millis = todayPrayers.getTime(prayerType),
                        is24Hour = settings.is24HourFormat,
                        timezoneId = settings.location.timezoneId
                    )
                } else null

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .testTag("prayer_adj_row_${prayerType.name}"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = prayerType.englishName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        if (formattedTime != null) {
                            Text(
                                text = "Final: $formattedTime",
                                style = MaterialTheme.typography.bodySmall,
                                color = GoldAccent
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(
                            onClick = {
                                val next = currentAdj - 1
                                if (InputValidator.isValidPrayerAdjustment(next)) {
                                    onPrayerAdjustmentChanged(prayerType, next)
                                }
                            },
                            enabled = currentAdj > InputValidator.MIN_PRAYER_ADJUSTMENT_MINUTES,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("adj_minus_${prayerType.name}")
                        ) {
                            Text("-", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Text(
                            text = when {
                                currentAdj > 0 -> "+$currentAdj min"
                                currentAdj < 0 -> "$currentAdj min"
                                else -> "0 min"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (currentAdj != 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (currentAdj != 0) GoldAccent else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .width(72.dp)
                                .testTag("adj_label_${prayerType.name}"),
                            textAlign = TextAlign.Center
                        )
                        FilledTonalIconButton(
                            onClick = {
                                val next = currentAdj + 1
                                if (InputValidator.isValidPrayerAdjustment(next)) {
                                    onPrayerAdjustmentChanged(prayerType, next)
                                }
                            },
                            enabled = currentAdj < InputValidator.MAX_PRAYER_ADJUSTMENT_MINUTES,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("adj_plus_${prayerType.name}")
                        ) {
                            Text("+", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section 3: Privacy Stance
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = GoldAccent,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Privacy Guarantee",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Zero network calls: INTERNET permission is NOT declared in the manifest.\n" +
                                "• Zero telemetry & zero analytics SDKs.\n" +
                                "• Zero external servers or tracking.\n" +
                                "• 100% on-device astronomical sun position calculations.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SettingClickableRow(
                title = "About & Developer",
                subtitle = "Developer amuksith, open source licenses, and support",
                icon = Icons.Default.Info,
                onClick = onAboutClick
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Dialog: Calculation Method
    if (showMethodDialog) {
        AlertDialog(
            onDismissRequest = { showMethodDialog = false },
            title = { Text("Calculation Method") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CalculationMethod.entries.forEach { method ->
                        val isSel = settings.method == method
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSel) GoldAccent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onMethodSelected(method)
                                    showMethodDialog = false
                                }
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(method.displayName, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                                Text(method.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMethodDialog = false }) { Text("Close") }
            }
        )
    }

    // Dialog: Madhab
    if (showMadhabDialog) {
        AlertDialog(
            onDismissRequest = { showMadhabDialog = false },
            title = { Text("Juristical Method (Madhab)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Madhab.entries.forEach { madhab ->
                        val isSel = settings.madhab == madhab
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSel) GoldAccent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onMadhabSelected(madhab)
                                    showMadhabDialog = false
                                }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(madhab.displayName, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                                Text(madhab.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMadhabDialog = false }) { Text("Close") }
            }
        )
    }

    // Dialog: High Latitude
    if (showHighLatDialog) {
        AlertDialog(
            onDismissRequest = { showHighLatDialog = false },
            title = { Text("High Latitude Rule") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HighLatitudeRule.entries.forEach { rule ->
                        val isSel = settings.highLatitudeRule == rule
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSel) GoldAccent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onHighLatitudeRuleSelected(rule)
                                    showHighLatDialog = false
                                }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(rule.displayName, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                                Text(rule.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHighLatDialog = false }) { Text("Close") }
            }
        )
    }

    // Dialog: Audio Sound
    if (showSoundDialog) {
        AlertDialog(
            onDismissRequest = { showSoundDialog = false },
            title = { Text("Athan Alert Audio") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AthanSound.entries.forEach { sound ->
                        val isSel = settings.sound == sound
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSel) GoldAccent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSoundSelected(sound)
                                    showSoundDialog = false
                                }
                        ) {
                            val entry = AthanAudioCatalog.getEntry(sound)
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(entry.title, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                                Text(entry.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSoundDialog = false }) { Text("Close") }
            }
        )
    }

    // Dialog: Custom Angles
    if (showCustomAnglesDialog) {
        var fajrAngleStr by remember { mutableStateOf(settings.customFajrAngle.toString()) }
        var ishaAngleStr by remember { mutableStateOf(settings.customIshaAngle.toString()) }
        var submitAttempted by remember { mutableStateOf(false) }

        val fajrValidation = InputValidator.parseAndValidateCustomAngle(fajrAngleStr, "Fajr")
        val ishaValidation = InputValidator.parseAndValidateCustomAngle(ishaAngleStr, "Isha")

        val isFajrError = (submitAttempted || fajrAngleStr.isNotEmpty()) && fajrValidation is ValidationResult.Invalid
        val isIshaError = (submitAttempted || ishaAngleStr.isNotEmpty()) && ishaValidation is ValidationResult.Invalid

        val canSave = fajrValidation is ValidationResult.Valid && ishaValidation is ValidationResult.Valid

        AlertDialog(
            onDismissRequest = { showCustomAnglesDialog = false },
            title = { Text("Custom Angles") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Set custom Fajr and Isha depression angles (between 0.0° and 60.0°).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = fajrAngleStr,
                        onValueChange = {
                            fajrAngleStr = it
                        },
                        label = { Text("Fajr Angle (0.0° - 60.0°)") },
                        isError = isFajrError,
                        supportingText = {
                            if (isFajrError && fajrValidation is ValidationResult.Invalid) {
                                Text(fajrValidation.errorMessage, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = ishaAngleStr,
                        onValueChange = {
                            ishaAngleStr = it
                        },
                        label = { Text("Isha Angle (0.0° - 60.0°)") },
                        isError = isIshaError,
                        supportingText = {
                            if (isIshaError && ishaValidation is ValidationResult.Invalid) {
                                Text(ishaValidation.errorMessage, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = canSave,
                    onClick = {
                        submitAttempted = true
                        val validFajr = (fajrValidation as? ValidationResult.Valid)?.value ?: return@Button
                        val validIsha = (ishaValidation as? ValidationResult.Valid)?.value ?: return@Button
                        onCustomAnglesChanged(validFajr, validIsha)
                        showCustomAnglesDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomAnglesDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Dialog: Custom Reminder Minutes
    if (showCustomReminderDialog) {
        var reminderStr by remember { mutableStateOf(settings.reminderMinutesBefore.toString()) }
        var submitAttempted by remember { mutableStateOf(false) }

        val reminderValidation = InputValidator.parseAndValidateReminderMinutes(reminderStr)
        val isReminderError = (submitAttempted || reminderStr.isNotEmpty()) && reminderValidation is ValidationResult.Invalid
        val canSaveReminder = reminderValidation is ValidationResult.Valid

        AlertDialog(
            onDismissRequest = { showCustomReminderDialog = false },
            title = { Text("Custom Reminder Alert") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Set minutes before each prayer to receive a reminder notification (0 to 120 minutes).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = reminderStr,
                        onValueChange = {
                            reminderStr = it
                        },
                        label = { Text("Reminder Minutes (0 - 120)") },
                        isError = isReminderError,
                        supportingText = {
                            if (isReminderError && reminderValidation is ValidationResult.Invalid) {
                                Text(reminderValidation.errorMessage, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = canSaveReminder,
                    onClick = {
                        submitAttempted = true
                        val validMins = (reminderValidation as? ValidationResult.Valid)?.value ?: return@Button
                        onReminderMinutesChanged(validMins)
                        showCustomReminderDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomReminderDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingClickableRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = GoldAccent)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
