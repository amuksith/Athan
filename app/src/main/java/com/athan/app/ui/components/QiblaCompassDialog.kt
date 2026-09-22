package com.athan.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.athan.app.core.astronomy.QiblaSensorManager
import com.athan.app.ui.theme.GoldAccent
import com.athan.app.ui.theme.GoldAccentLight
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun QiblaCompassDialog(
    qiblaBearing: Double,
    distanceKm: Double,
    cityName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sensorManager = remember { QiblaSensorManager(context) }
    val deviceAzimuth by sensorManager.deviceAzimuth.collectAsState()

    DisposableEffect(Unit) {
        sensorManager.startListening()
        onDispose {
            sensorManager.stopListening()
        }
    }

    // Difference between device heading and Qibla direction
    val relativeAngle = ((qiblaBearing - deviceAzimuth + 360.0) % 360.0).toFloat()
    val isFacingQibla = abs(if (relativeAngle > 180) relativeAngle - 360 else relativeAngle) <= 4.0f

    val accentColor by animateColorAsState(
        targetValue = if (isFacingQibla) Color(0xFF2ECC71) else GoldAccent,
        label = "qibla_accent"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Explore,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Qibla Direction",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Bearing from $cityName: ${qiblaBearing.roundToInt()}°",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Distance to Kaaba: ${distanceKm.roundToInt()} km",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Compass Dial
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(240.dp)) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val radius = size.width / 2f

                        // Outer ring
                        drawCircle(
                            color = accentColor.copy(alpha = 0.3f),
                            radius = radius - 4.dp.toPx(),
                            style = Stroke(width = 2.dp.toPx())
                        )

                        // Rotate dial based on device heading
                        rotate(degrees = -deviceAzimuth, pivot = center) {
                            // North marker
                            drawLine(
                                color = Color(0xFFE74C3C),
                                start = Offset(center.x, center.y - radius + 8.dp.toPx()),
                                end = Offset(center.x, center.y - radius + 22.dp.toPx()),
                                strokeWidth = 4.dp.toPx()
                            )

                            // Ticks
                            for (angle in 0 until 360 step 30) {
                                val rad = Math.toRadians(angle.toDouble())
                                val startR = radius - 16.dp.toPx()
                                val endR = radius - 8.dp.toPx()
                                val start = Offset(
                                    (center.x + startR * kotlin.math.sin(rad)).toFloat(),
                                    (center.y - startR * kotlin.math.cos(rad)).toFloat()
                                )
                                val end = Offset(
                                    (center.x + endR * kotlin.math.sin(rad)).toFloat(),
                                    (center.y - endR * kotlin.math.cos(rad)).toFloat()
                                )
                                drawLine(
                                    color = Color.Gray.copy(alpha = 0.4f),
                                    start = start,
                                    end = end,
                                    strokeWidth = 1.5.dp.toPx()
                                )
                            }
                        }

                        // Qibla Pointer (points towards Makkah relative to phone)
                        rotate(degrees = relativeAngle, pivot = center) {
                            val arrowPath = Path().apply {
                                moveTo(center.x, center.y - radius + 26.dp.toPx())
                                lineTo(center.x - 14.dp.toPx(), center.y - 15.dp.toPx())
                                lineTo(center.x + 14.dp.toPx(), center.y - 15.dp.toPx())
                                close()
                            }
                            drawPath(
                                path = arrowPath,
                                color = accentColor
                            )
                            // Stem
                            drawLine(
                                color = accentColor,
                                start = Offset(center.x, center.y - 15.dp.toPx()),
                                end = Offset(center.x, center.y + 40.dp.toPx()),
                                strokeWidth = 4.dp.toPx()
                            )
                        }

                        // Center pivot dot
                        drawCircle(
                            color = accentColor,
                            radius = 7.dp.toPx(),
                            center = center
                        )
                    }

                    // Alignment text in center
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.offset(y = 36.dp)
                    ) {
                        Text(
                            text = "${deviceAzimuth.roundToInt()}°",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Status Banner
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isFacingQibla) Color(0xFF1E4620) else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isFacingQibla) "★ Facing Qibla (Al-Kaaba) ★" else "Turn device to align with the golden arrow",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isFacingQibla) FontWeight.Bold else FontWeight.Normal,
                        color = if (isFacingQibla) Color(0xFF85E39C) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 16.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Calculated completely on-device using spherical trigonometry & compass sensor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}
