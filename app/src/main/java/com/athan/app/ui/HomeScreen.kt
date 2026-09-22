package com.athan.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.athan.app.core.astronomy.NextPrayerState
import com.athan.app.core.astronomy.PrayerTimeFormatter
import com.athan.app.core.astronomy.PrayerTimes
import com.athan.app.core.astronomy.PrayerType
import com.athan.app.data.repository.AthanSettings
import com.athan.app.util.ExactAlarmPermissionManager
import com.athan.app.util.NotificationPermissionManager
import com.athan.app.ui.components.AboutDialog
import com.athan.app.ui.components.LocationPickerSheet
import com.athan.app.ui.components.MonthScheduleDialog
import com.athan.app.ui.components.QiblaCompassDialog
import com.athan.app.ui.components.SettingsSheet
import com.athan.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: PrayerViewModel
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val todayPrayers by viewModel.todayPrayerTimes.collectAsState()
    val tomorrowPrayers by viewModel.tomorrowPrayerTimes.collectAsState()
    val currentTimeMillis by viewModel.currentTimeMillis.collectAsState()
    val nextPrayerState by viewModel.nextPrayerState.collectAsState()
    val gregorianDate by viewModel.currentGregorianDate.collectAsState()
    val hijriDate by viewModel.currentHijriDate.collectAsState()
    val qiblaInfo by viewModel.qiblaInfo.collectAsState()
    val isAudioTesting by viewModel.isAudioTesting.collectAsState()
    val isGpsLoading by viewModel.isLocatingGps.collectAsState()
    val gpsState by viewModel.gpsState.collectAsState()
    val gpsMessage by viewModel.gpsMessage.collectAsState()
    val adjustmentErrorMessage by viewModel.adjustmentErrorMessage.collectAsState()

    var showLocationSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showQiblaDialog by remember { mutableStateOf(false) }
    var showMonthDialog by remember { mutableStateOf(false) }
    var monthOffset by remember(settings.location.timezoneId) { mutableIntStateOf(0) }

    val activity = context as? Activity
    val isNotificationPermissionGranted by viewModel.isNotificationPermissionGranted.collectAsState()
    var showBlockedNotificationDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.onNotificationPermissionResult(isGranted)
    }

    // On initial launch, if Android 13+ and permission hasn't been prompted yet, request it cleanly
    LaunchedEffect(Unit) {
        if (NotificationPermissionManager.shouldPromptInitialPermission(
                context = context,
                hasAlreadyPrompted = viewModel.hasPromptedNotificationPermission()
            )
        ) {
            viewModel.markNotificationPermissionPrompted()
            permissionLauncher.launch(NotificationPermissionManager.PERMISSION_POST_NOTIFICATIONS)
        }
    }

    val onRequestNotificationPermission: () -> Unit = {
        if (NotificationPermissionManager.isRuntimePermissionRequired()) {
            if (!viewModel.hasPromptedNotificationPermission()) {
                viewModel.markNotificationPermissionPrompted()
                permissionLauncher.launch(NotificationPermissionManager.PERMISSION_POST_NOTIFICATIONS)
            } else if (activity != null && !NotificationPermissionManager.isPermanentlyDenied(activity, true)) {
                permissionLauncher.launch(NotificationPermissionManager.PERMISSION_POST_NOTIFICATIONS)
            } else {
                showBlockedNotificationDialog = true
            }
        } else {
            showBlockedNotificationDialog = true
        }
    }

    val canScheduleExact by viewModel.canScheduleExactAlarms.collectAsState()

    val onRequestExactAlarmPermission: () -> Unit = {
        try {
            context.startActivity(ExactAlarmPermissionManager.createExactAlarmSettingsIntent(context))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = GoldAccent.copy(alpha = 0.18f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.NightsStay,
                                    contentDescription = null,
                                    tint = GoldAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Athan",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Zero Telemetry • Offline",
                                style = MaterialTheme.typography.labelSmall,
                                color = GoldAccent
                            )
                        }
                    }
                },
                actions = {
                    // Privacy indicator chip
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = Color(0xFF2ECC71),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Offline", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // About Button
                    IconButton(
                        onClick = { showAboutDialog = true },
                        modifier = Modifier.testTag("about_top_app_bar_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = "About Athan",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Settings Button
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                // Location & Date Header Row
                LocationAndDateCard(
                    settings = settings,
                    gregorianDate = gregorianDate,
                    hijriDisplay = hijriDate.formatDisplay(),
                    hijriArabic = hijriDate.formatArabicDisplay(),
                    onLocationClick = { showLocationSheet = true }
                )
            }

            // Android 13+ Notification Permission warning if needed
            if (!isNotificationPermissionGranted) {
                item {
                    NotificationPermissionWarningCard(
                        onRequestNotificationPermission = onRequestNotificationPermission
                    )
                }
            }

            // Android 12+ Exact Alarm Permission warning if needed
            if (!canScheduleExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    ExactAlarmWarningCard(
                        onRequestExactAlarmPermission = onRequestExactAlarmPermission
                    )
                }
            }

            item {
                // Next Prayer Hero Card with Countdown
                NextPrayerHeroCard(
                    todayPrayers = todayPrayers,
                    tomorrowPrayers = tomorrowPrayers,
                    currentTimeMillis = currentTimeMillis,
                    is24Hour = settings.is24HourFormat,
                    timezoneId = settings.location.timezoneId,
                    isAudioTesting = isAudioTesting,
                    onToggleAudio = { viewModel.testAudio(settings.sound) },
                    nextPrayerState = nextPrayerState
                )
            }

            item {
                // Quick Access Controls: Qibla, Month, Location
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = { showQiblaDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Icon(Icons.Default.Explore, contentDescription = null, tint = GoldAccent, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Qibla (${qiblaInfo.first.toInt()}°)", fontSize = 13.sp)
                    }

                    FilledTonalButton(
                        onClick = { showMonthDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = GoldAccent, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Monthly", fontSize = 13.sp)
                    }
                }
            }

            item {
                // Section Title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "TODAY'S SCHEDULE",
                        style = MaterialTheme.typography.labelMedium,
                        color = GoldAccent,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = settings.method.displayName.split("(")[0].trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // List of all 6 prayers for today
            items(PrayerType.entries.size) { index ->
                val prayerType = PrayerType.entries[index]
                val prayerTimeMillis = todayPrayers.getTime(prayerType)
                val isAvailable = todayPrayers.isAvailable(prayerType)
                val isEnabled = settings.prayerNotifications[prayerType] ?: true
                val currentPrayer = todayPrayers.getCurrentPrayer(currentTimeMillis)
                val isCurrent = isAvailable && currentPrayer == prayerType
                val isPassed = isAvailable && currentTimeMillis > prayerTimeMillis

                PrayerRowCard(
                    prayerType = prayerType,
                    timeMillis = prayerTimeMillis,
                    isCurrent = isCurrent,
                    isPassed = isPassed,
                    isNotificationEnabled = isEnabled,
                    isSystemNotificationBlocked = !isNotificationPermissionGranted,
                    is24Hour = settings.is24HourFormat,
                    timezoneId = settings.location.timezoneId,
                    onToggleNotification = {
                        val willEnable = !isEnabled
                        viewModel.togglePrayerNotification(prayerType)
                        if (willEnable && !isNotificationPermissionGranted) {
                            onRequestNotificationPermission()
                        }
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Sheets & Dialogs
    if (showLocationSheet) {
        LocationPickerSheet(
            currentLocation = settings.location,
            isGpsLoading = isGpsLoading,
            gpsState = gpsState,
            gpsMessage = gpsMessage,
            onCitySelected = { city ->
                viewModel.selectBundledCity(city)
            },
            onManualCoordinates = { name, lat, lon, ele, tz ->
                viewModel.setManualCoordinates(name, lat, lon, ele, tz)
            },
            onRequestGps = {
                viewModel.requestGpsLocation()
            },
            onCancelGps = {
                viewModel.cancelGpsLocationRequest()
            },
            onPermissionDenied = {
                viewModel.onLocationPermissionDenied()
            },
            onDismiss = {
                viewModel.cancelGpsLocationRequest()
                viewModel.clearGpsMessage()
                showLocationSheet = false
            }
        )
    }

    if (showSettingsSheet) {
        SettingsSheet(
            settings = settings,
            todayPrayers = todayPrayers,
            isNotificationPermissionGranted = isNotificationPermissionGranted,
            onRequestNotificationPermission = onRequestNotificationPermission,
            canScheduleExactAlarms = canScheduleExact,
            onRequestExactAlarmPermission = onRequestExactAlarmPermission,
            onMethodSelected = { viewModel.updateCalculationMethod(it) },
            onMadhabSelected = { viewModel.updateMadhab(it) },
            onHighLatitudeRuleSelected = { viewModel.updateHighLatitudeRule(it) },
            onCustomAnglesChanged = { f, i -> viewModel.updateCustomAngles(f, i) },
            onSoundSelected = { viewModel.updateSound(it) },
            onReminderMinutesChanged = { viewModel.updateReminderMinutes(it) },
            onTimeFormatChanged = { viewModel.updateTimeFormat(it) },
            onHijriAdjustmentChanged = { viewModel.updateHijriAdjustment(it) },
            onPrayerAdjustmentChanged = { prayer, mins -> viewModel.updatePrayerAdjustment(prayer, mins) },
            onResetAllPrayerAdjustments = { viewModel.resetAllPrayerAdjustments() },
            adjustmentErrorMessage = adjustmentErrorMessage,
            onTestAudio = { viewModel.testAudio(it) },
            isAudioTesting = isAudioTesting,
            onAboutClick = { showAboutDialog = true },
            onDismiss = {
                viewModel.clearAdjustmentErrorMessage()
                showSettingsSheet = false
            }
        )
    }

    if (showBlockedNotificationDialog) {
        AlertDialog(
            onDismissRequest = { showBlockedNotificationDialog = false },
            icon = { Icon(Icons.Default.NotificationsOff, contentDescription = null, tint = GoldAccent) },
            title = { Text("Notifications Disabled") },
            text = {
                Text("Android notifications are disabled for Athan. To receive prayer alerts and Athan calls on time, please allow notifications in system settings.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBlockedNotificationDialog = false
                        try {
                            context.startActivity(NotificationPermissionManager.createNotificationSettingsIntent(context))
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                ) {
                    Text("Open Settings", color = GoldAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockedNotificationDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showQiblaDialog) {
        QiblaCompassDialog(
            qiblaBearing = qiblaInfo.first,
            distanceKm = qiblaInfo.second,
            cityName = settings.location.cityName,
            onDismiss = { showQiblaDialog = false }
        )
    }

    if (showMonthDialog) {
        val currentLocalDay by viewModel.currentLocalDate.collectAsState()
        val tz = remember(settings.location.timezoneId) {
            viewModel.getLocationTimeZone()
        }
        val viewedCal = remember(currentLocalDay.timeZoneId, currentLocalDay.year, currentLocalDay.month, monthOffset) {
            Calendar.getInstance(tz).apply {
                set(Calendar.YEAR, currentLocalDay.year)
                set(Calendar.MONTH, currentLocalDay.month - 1)
                set(Calendar.DAY_OF_MONTH, 1)
                add(Calendar.MONTH, monthOffset)
            }
        }
        val viewedYear = viewedCal.get(Calendar.YEAR)
        val viewedMonth = viewedCal.get(Calendar.MONTH) + 1
        val monthPrayers = remember(viewedYear, viewedMonth, settings) {
            viewModel.getMonthPrayerTimes(viewedYear, viewedMonth)
        }

        MonthScheduleDialog(
            monthPrayers = monthPrayers,
            cityName = settings.location.cityName,
            is24Hour = settings.is24HourFormat,
            currentLocationDay = currentLocalDay,
            locationTimeZone = tz,
            viewedYear = viewedYear,
            viewedMonth = viewedMonth,
            onPreviousMonth = { monthOffset-- },
            onNextMonth = { monthOffset++ },
            onDismiss = {
                showMonthDialog = false
                monthOffset = 0
            }
        )
    }

    if (showAboutDialog) {
        AboutDialog(
            cityName = settings.location.cityName,
            gregorianDate = gregorianDate,
            hijriDate = "${hijriDate.formatDisplay()} (${hijriDate.formatArabicDisplay()})",
            todayPrayers = todayPrayers,
            is24Hour = settings.is24HourFormat,
            timezoneId = settings.location.timezoneId,
            onDismiss = { showAboutDialog = false }
        )
    }
}

@Composable
private fun LocationAndDateCard(
    settings: AthanSettings,
    gregorianDate: String,
    hijriDisplay: String,
    hijriArabic: String,
    onLocationClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onLocationClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = null,
                        tint = GoldAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = settings.location.getDisplayName(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$hijriDisplay • $hijriArabic",
                    style = MaterialTheme.typography.bodySmall,
                    color = GoldAccent
                )
                Text(
                    text = gregorianDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.EditLocationAlt,
                        contentDescription = "Change Location",
                        tint = GoldAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun NextPrayerHeroCard(
    todayPrayers: PrayerTimes,
    tomorrowPrayers: PrayerTimes,
    currentTimeMillis: Long,
    is24Hour: Boolean,
    timezoneId: String,
    isAudioTesting: Boolean,
    onToggleAudio: () -> Unit,
    nextPrayerState: NextPrayerState = remember(todayPrayers, tomorrowPrayers, currentTimeMillis) {
        PrayerTimes.resolveNextPrayer(todayPrayers, tomorrowPrayers, currentTimeMillis)
    }
) {
    val isUpcomingAvailable = nextPrayerState.isAvailable && nextPrayerState.timestamp != null
    val nextPrayerType = nextPrayerState.prayerType
    val nextPrayerTimeMillis = nextPrayerState.timestamp ?: PrayerTimes.TIME_UNAVAILABLE

    val remainingMillis = if (isUpcomingAvailable) {
        nextPrayerState.remainingMillis
            ?: (nextPrayerTimeMillis - currentTimeMillis).coerceAtLeast(0L)
    } else 0L
    val totalSeconds = remainingMillis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    val timeFormatted = remember(nextPrayerTimeMillis, is24Hour, timezoneId, isUpcomingAvailable) {
        if (isUpcomingAvailable) {
            PrayerTimeFormatter.formatTime(nextPrayerTimeMillis, is24Hour, timezoneId)
        } else {
            "--:--"
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF0F382A),
                            Color(0xFF0A261C),
                            Color(0xFF051811)
                        )
                    )
                )
                .border(1.dp, GoldAccent.copy(alpha = 0.35f), RoundedCornerShape(26.dp))
                .padding(22.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = GoldAccent.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "UPCOMING PRAYER",
                        color = GoldAccentLight,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Prayer Name (English + Arabic)
                Text(
                    text = if (isUpcomingAvailable && nextPrayerType != null) {
                        nextPrayerType.englishName
                    } else {
                        "Time unavailable"
                    },
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
                Text(
                    text = if (isUpcomingAvailable && nextPrayerType != null) {
                        nextPrayerType.arabicName
                    } else {
                        "--"
                    },
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = GoldAccent
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isUpcomingAvailable) "Scheduled at $timeFormatted" else "Time unavailable ($timeFormatted)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Countdown Digits
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    val hrStr = if (isUpcomingAvailable) String.format("%02d", hours) else "--"
                    val minStr = if (isUpcomingAvailable) String.format("%02d", minutes) else "--"
                    val secStr = if (isUpcomingAvailable) String.format("%02d", seconds) else "--"
                    CountdownDigitBox(value = hrStr, unit = "HR")
                    Text(":", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = GoldAccent, modifier = Modifier.padding(horizontal = 6.dp))
                    CountdownDigitBox(value = minStr, unit = "MIN")
                    Text(":", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = GoldAccent, modifier = Modifier.padding(horizontal = 6.dp))
                    CountdownDigitBox(value = secStr, unit = "SEC")
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Quick audio preview button
                OutlinedButton(
                    onClick = onToggleAudio,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (isAudioTesting) Color(0xFFE74C3C) else GoldAccent
                    ),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = Brush.linearGradient(listOf(GoldAccent.copy(alpha = 0.6f), GoldAccent.copy(alpha = 0.2f)))
                    )
                ) {
                    Icon(
                        imageVector = if (isAudioTesting) Icons.Default.Stop else Icons.Default.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isAudioTesting) "Stop Athan Voice" else "Test Athan Call Voice",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun CountdownDigitBox(value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF144031).copy(alpha = 0.85f),
            border = androidx.compose.foundation.BorderStroke(1.dp, GoldAccent.copy(alpha = 0.3f)),
            modifier = Modifier.size(width = 54.dp, height = 48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = value,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = unit,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = GoldAccentLight
        )
    }
}

@Composable
private fun PrayerRowCard(
    prayerType: PrayerType,
    timeMillis: Long,
    isCurrent: Boolean,
    isPassed: Boolean,
    isNotificationEnabled: Boolean,
    isSystemNotificationBlocked: Boolean = false,
    is24Hour: Boolean,
    timezoneId: String,
    onToggleNotification: () -> Unit
) {
    val isAvailable = timeMillis > PrayerTimes.TIME_UNAVAILABLE
    val effectiveCurrent = isAvailable && isCurrent
    val effectivePassed = isAvailable && isPassed

    val formattedTime = remember(timeMillis, is24Hour, timezoneId) {
        PrayerTimeFormatter.formatTime(
            millis = timeMillis,
            is24Hour = is24Hour,
            timezoneId = timezoneId
        )
    }

    val containerColor = when {
        effectiveCurrent -> GoldAccent.copy(alpha = 0.16f)
        effectivePassed -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.surface
    }

    val borderColor = if (effectiveCurrent) GoldAccent else Color.Transparent

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = if (effectiveCurrent) androidx.compose.foundation.BorderStroke(1.5.dp, borderColor) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Icon & Names
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = if (effectiveCurrent) GoldAccent else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when (prayerType) {
                                PrayerType.FAJR -> Icons.Default.WbTwilight
                                PrayerType.SUNRISE -> Icons.Default.WbSunny
                                PrayerType.DHUHR -> Icons.Default.LightMode
                                PrayerType.ASR -> Icons.Default.WbCloudy
                                PrayerType.MAGHRIB -> Icons.Default.WbTwilight
                                PrayerType.ISHA -> Icons.Default.NightsStay
                            },
                            contentDescription = null,
                            tint = if (effectiveCurrent) Color(0xFF1B1800) else GoldAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = prayerType.englishName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (effectiveCurrent) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (effectivePassed || !isAvailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                        if (effectiveCurrent) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = GoldAccent
                            ) {
                                Text(
                                    text = "NOW",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1B1800),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = prayerType.arabicName,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (effectiveCurrent) GoldAccent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }

            // Right: Time & Notification toggle
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (effectivePassed || !isAvailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = onToggleNotification,
                    enabled = isAvailable,
                    modifier = Modifier.size(36.dp)
                ) {
                    val (icon, tint) = when {
                        !isAvailable -> Icons.Outlined.NotificationsOff to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                        !isNotificationEnabled -> Icons.Outlined.NotificationsOff to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        isSystemNotificationBlocked -> Icons.Default.NotificationsPaused to Color(0xFFE67E22)
                        else -> Icons.Default.NotificationsActive to GoldAccent
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = if (!isAvailable) "Prayer unavailable" else if (isSystemNotificationBlocked) "Notifications blocked by Android system" else "Notification Toggle",
                        tint = tint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationPermissionWarningCard(
    onRequestNotificationPermission: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2411))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.NotificationsOff, contentDescription = null, tint = GoldAccent, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Notification Permission Required",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Allow notifications so Athan calls and prayer reminders can sound on time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
            TextButton(onClick = onRequestNotificationPermission) {
                Text("Enable", color = GoldAccent, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ExactAlarmWarningCard(
    onRequestExactAlarmPermission: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF382314))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Alarm, contentDescription = null, tint = GoldAccent, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Exact Alarms Required",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Enable exact alarms so the Athan sounds precisely on time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
            TextButton(onClick = onRequestExactAlarmPermission) {
                Text("Enable", color = GoldAccent, fontWeight = FontWeight.Bold)
            }
        }
    }
}
