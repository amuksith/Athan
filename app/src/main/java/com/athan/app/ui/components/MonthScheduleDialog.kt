package com.athan.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.athan.app.core.astronomy.LocalCalendarDay
import com.athan.app.core.astronomy.PrayerTimeFormatter
import com.athan.app.core.astronomy.PrayerTimes
import com.athan.app.core.astronomy.TimeZoneResolver
import com.athan.app.ui.theme.GoldAccent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object MonthScheduleHelper {
    /**
     * Determines whether a given day number in the viewed month/year represents "today"
     * for the selected prayer-location calendar.
     */
    fun isToday(
        viewedYear: Int,
        viewedMonth: Int,
        dayNum: Int,
        locationDay: LocalCalendarDay
    ): Boolean {
        return viewedYear == locationDay.year &&
                viewedMonth == locationDay.month &&
                dayNum == locationDay.dayOfMonth
    }

    /**
     * Resolves the authoritative LocalCalendarDay for a given instant in the location's timezone.
     */
    fun getEffectiveLocationDay(
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone
    ): LocalCalendarDay {
        return LocalCalendarDay.fromMillis(now, timeZone)
    }

    fun formatMonthTitle(
        year: Int,
        month: Int,
        timeZone: TimeZone,
        locale: Locale = Locale.getDefault()
    ): String {
        val cal = Calendar.getInstance(timeZone).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sdf = SimpleDateFormat("MMMM yyyy", locale).apply {
            this.timeZone = timeZone
        }
        return sdf.format(cal.time)
    }
}

@Composable
fun MonthScheduleDialog(
    monthPrayers: List<PrayerTimes>,
    cityName: String,
    is24Hour: Boolean,
    currentLocationDay: LocalCalendarDay? = null,
    locationTimeZone: TimeZone? = null,
    viewedYear: Int? = null,
    viewedMonth: Int? = null,
    onPreviousMonth: (() -> Unit)? = null,
    onNextMonth: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val tz = locationTimeZone ?: currentLocationDay?.getTimeZone() ?: TimeZoneResolver.resolve(null)
    val effectiveLocationDay = currentLocationDay ?: LocalCalendarDay.fromMillis(System.currentTimeMillis(), tz)
    val activeYear = viewedYear ?: effectiveLocationDay.year
    val activeMonth = viewedMonth ?: effectiveLocationDay.month

    val monthTitle = remember(activeYear, activeMonth, tz.id) {
        MonthScheduleHelper.formatMonthTitle(activeYear, activeMonth, tz)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f)
                .padding(12.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = null,
                            tint = GoldAccent,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (onPreviousMonth != null) {
                                    IconButton(
                                        onClick = onPreviousMonth,
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Previous Month",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "$monthTitle Schedule",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (onNextMonth != null) {
                                    IconButton(
                                        onClick = onNextMonth,
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = "Next Month",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = cityName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Table Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(vertical = 8.dp, horizontal = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Day", modifier = Modifier.weight(0.9f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("Fajr", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("Dhuhr", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("Asr", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("Maghrib", modifier = Modifier.weight(1.1f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("Isha", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Table Rows
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(monthPrayers) { index, prayer ->
                        val dayNum = index + 1
                        val isToday = MonthScheduleHelper.isToday(activeYear, activeMonth, dayNum, effectiveLocationDay)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (isToday) GoldAccent.copy(alpha = 0.2f)
                                    else if (index % 2 == 0) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                    else Color.Transparent
                                )
                                .padding(vertical = 7.dp, horizontal = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isToday) "$dayNum *" else "$dayNum",
                                modifier = Modifier.weight(0.9f),
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                color = if (isToday) GoldAccent else MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp
                            )
                            Text(
                                text = PrayerTimeFormatter.formatTime(prayer.fajr, is24Hour, tz),
                                modifier = Modifier.weight(1f),
                                fontSize = 11.sp
                            )
                            Text(
                                text = PrayerTimeFormatter.formatTime(prayer.dhuhr, is24Hour, tz),
                                modifier = Modifier.weight(1f),
                                fontSize = 11.sp
                            )
                            Text(
                                text = PrayerTimeFormatter.formatTime(prayer.asr, is24Hour, tz),
                                modifier = Modifier.weight(1f),
                                fontSize = 11.sp
                            )
                            Text(
                                text = PrayerTimeFormatter.formatTime(prayer.maghrib, is24Hour, tz),
                                modifier = Modifier.weight(1.1f),
                                fontSize = 11.sp
                            )
                            Text(
                                text = PrayerTimeFormatter.formatTime(prayer.isha, is24Hour, tz),
                                modifier = Modifier.weight(1f),
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "* Denotes today. Precomputed completely offline via astronomical algorithms.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}
