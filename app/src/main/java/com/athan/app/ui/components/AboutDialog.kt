package com.athan.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.athan.app.R
import com.athan.app.core.astronomy.PrayerTimeFormatter
import com.athan.app.core.astronomy.PrayerTimes
import com.athan.app.ui.theme.EmeraldPrimary
import com.athan.app.ui.theme.EmeraldPrimaryContainer
import com.athan.app.ui.theme.GoldAccent

object PrayerShareFormatter {
    fun buildPrayerShareText(
        cityName: String,
        gregorianDate: String,
        hijriDate: String,
        prayerTimes: PrayerTimes?,
        is24Hour: Boolean,
        timezoneId: String
    ): String {
        val fajr = prayerTimes?.let { PrayerTimeFormatter.formatTime(it.fajr, is24Hour, timezoneId) } ?: "--:--"
        val sunrise = prayerTimes?.let { PrayerTimeFormatter.formatTime(it.sunrise, is24Hour, timezoneId) } ?: "--:--"
        val dhuhr = prayerTimes?.let { PrayerTimeFormatter.formatTime(it.dhuhr, is24Hour, timezoneId) } ?: "--:--"
        val asr = prayerTimes?.let { PrayerTimeFormatter.formatTime(it.asr, is24Hour, timezoneId) } ?: "--:--"
        val maghrib = prayerTimes?.let { PrayerTimeFormatter.formatTime(it.maghrib, is24Hour, timezoneId) } ?: "--:--"
        val isha = prayerTimes?.let { PrayerTimeFormatter.formatTime(it.isha, is24Hour, timezoneId) } ?: "--:--"

        return """
            🕌 Prayer Times for $cityName
            $gregorianDate | $hijriDate

            • Fajr: $fajr
            • Sunrise: $sunrise
            • Dhuhr: $dhuhr
            • Asr: $asr
            • Maghrib: $maghrib
            • Isha: $isha

            Calculated offline with Athan App:
            https://github.com/amuksith/Athan
        """.trimIndent()
    }

    const val APP_SHARE_MESSAGE: String =
        "Athan — 100% Offline & Zero-Telemetry Islamic Prayer Times and Qibla Compass for Android.\n\n" +
        "Explore the open-source code and download on GitHub:\n" +
        "https://github.com/amuksith/Athan"
}

@Composable
fun AboutDialog(
    cityName: String,
    gregorianDate: String,
    hijriDate: String,
    todayPrayers: PrayerTimes?,
    is24Hour: Boolean,
    timezoneId: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
                .padding(vertical = 16.dp)
                .testTag("about_dialog"),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Header Row with Title & Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "About Athan",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("about_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // App Badge & Identity
                Surface(
                    shape = CircleShape,
                    color = EmeraldPrimaryContainer,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.NightsStay,
                            contentDescription = null,
                            tint = GoldAccent,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = "${stringResource(R.string.app_version)} • 100% Offline • Zero Telemetry",
                    style = MaterialTheme.typography.bodySmall,
                    color = GoldAccent,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Developed by ${stringResource(R.string.developer_name)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Core Capabilities Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = GoldAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Offline & Privacy Highlights",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "• On-device astronomical prayer calculations.\n" +
                                   "• Great-circle Qibla direction with sensor smoothing.\n" +
                                   "• 80+ bundled offline global cities & offline timezone lookup.\n" +
                                   "• Strictly NO internet permission and zero telemetry.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Open Source Licenses & Acknowledgments Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("open_source_licenses_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                tint = GoldAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Open Source Libraries",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "• Google Jetpack Compose & Material 3 (Apache License 2.0)\n" +
                                   "• AndroidX Core, Lifecycle & Activity (Apache License 2.0)\n" +
                                   "• JetBrains Kotlinx Coroutines (Apache License 2.0)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Developer Support Card ("Support the Developer")
                AboutActionLinkRow(
                    icon = Icons.Default.Favorite,
                    title = stringResource(R.string.developer_support_title),
                    subtitle = stringResource(R.string.developer_support_subtitle),
                    testTag = "support_sponsor_card",
                    onClick = {
                        openUrl(context, context.getString(R.string.developer_support_url))
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // GitHub Repository Card
                AboutActionLinkRow(
                    icon = Icons.Default.Code,
                    title = "View Source Code on GitHub",
                    subtitle = "github.com/amuksith/Athan",
                    testTag = "github_repo_card",
                    onClick = {
                        openUrl(context, context.getString(R.string.github_repository_url))
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Sharing Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            shareText(
                                context = context,
                                subject = "Athan - Offline Prayer Times & Qibla",
                                text = PrayerShareFormatter.APP_SHARE_MESSAGE,
                                chooserTitle = "Share Athan App"
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("share_app_button"),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = GoldAccent
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Share App",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            val shareMessage = PrayerShareFormatter.buildPrayerShareText(
                                cityName = cityName,
                                gregorianDate = gregorianDate,
                                hijriDate = hijriDate,
                                prayerTimes = todayPrayers,
                                is24Hour = is24Hour,
                                timezoneId = timezoneId
                            )
                            shareText(
                                context = context,
                                subject = "Prayer Times for $cityName",
                                text = shareMessage,
                                chooserTitle = "Share Today's Prayer Times"
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("share_prayers_button"),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarToday,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = GoldAccent
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Share Times",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun AboutActionLinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = EmeraldPrimary.copy(alpha = 0.15f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = GoldAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun shareText(context: Context, subject: String, text: String, chooserTitle: String) {
    try {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(shareIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
