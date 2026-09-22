package com.athan.app.ui.components

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.athan.app.core.astronomy.PrayerTimeFormatter
import com.athan.app.core.astronomy.TimeZoneResolver
import com.athan.app.data.location.BundledCities
import com.athan.app.data.location.BundledCity
import com.athan.app.data.location.OfflineLocationProvider
import com.athan.app.data.location.OfflineTimezoneLookup
import com.athan.app.data.location.UserLocation
import com.athan.app.ui.GpsLocationState
import com.athan.app.ui.theme.GoldAccent
import com.athan.app.util.InputValidator
import com.athan.app.util.ValidationResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationPickerSheet(
    currentLocation: UserLocation,
    isGpsLoading: Boolean,
    gpsState: GpsLocationState = GpsLocationState.Idle,
    gpsMessage: String?,
    onCitySelected: (BundledCity) -> Unit,
    onManualCoordinates: (name: String, lat: Double, lon: Double, elevation: Double, timezoneId: String) -> Unit,
    onRequestGps: () -> Unit,
    onCancelGps: () -> Unit = {},
    onPermissionDenied: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var showManualDialog by remember { mutableStateOf(false) }

    val filteredCities = remember(searchQuery) {
        BundledCities.search(searchQuery)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            onRequestGps()
        } else {
            onPermissionDenied()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            onCancelGps()
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Select Location",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Zero network calls • 100% offline database",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { showManualDialog = true }) {
                    Icon(Icons.Default.EditLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Custom Coords")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action: Device GPS button
            OutlinedCard(
                onClick = {
                    if (isGpsLoading) {
                        onCancelGps()
                    } else if (OfflineLocationProvider.hasLocationPermission(context)) {
                        onRequestGps()
                    } else {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = null,
                            tint = GoldAccent
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isGpsLoading) "Acquiring GPS Fix..." else "Use On-Device GPS Fix",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (isGpsLoading) "Tap card or Cancel to stop" else "One-shot hardware reading, no remote server",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (isGpsLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                onClick = onCancelGps,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Cancel", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    } else {
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (gpsState is GpsLocationState.ProviderUnavailable) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Location provider disabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                            } catch (e: Exception) {
                                // Ignore if cannot open settings
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Open Settings", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (gpsMessage != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = gpsMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (gpsState is GpsLocationState.Error || gpsState is GpsLocationState.ProviderUnavailable)
                        MaterialTheme.colorScheme.error else GoldAccent
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search bundled cities (e.g. London, Makkah)...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // List of bundled cities
            Text(
                text = "Bundled Offline Cities (${filteredCities.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(6.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredCities, key = { it.name + it.country }) { city ->
                    val isSelected = currentLocation.cityName.equals(city.name, ignoreCase = true)

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) GoldAccent.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onCitySelected(city)
                                onDismiss()
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = city.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = "${city.country} • ${String.format("%.2f°, %.2f°", city.latitude, city.longitude)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = GoldAccent
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showManualDialog) {
        ManualCoordinatesDialog(
            currentLocation = currentLocation,
            onSave = { name, lat, lon, ele, tz ->
                onManualCoordinates(name, lat, lon, ele, tz)
                showManualDialog = false
                onDismiss()
            },
            onDismiss = { showManualDialog = false }
        )
    }
}

@Composable
fun ManualCoordinatesDialog(
    currentLocation: UserLocation,
    onSave: (name: String, lat: Double, lon: Double, elevation: Double, timezoneId: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentLocation.cityName) }
    var latText by remember { mutableStateOf(currentLocation.latitude.toString()) }
    var lonText by remember { mutableStateOf(currentLocation.longitude.toString()) }
    var elevationText by remember { mutableStateOf(currentLocation.elevationMeters.toString()) }
    var timezoneText by remember {
        mutableStateOf(
            if (TimeZoneResolver.isValidTimeZoneId(currentLocation.timezoneId)) {
                currentLocation.timezoneId
            } else {
                OfflineTimezoneLookup.lookup(currentLocation.latitude, currentLocation.longitude)
                    ?: TimeZoneResolver.DEFAULT_FALLBACK_TIMEZONE_ID
            }
        )
    }
    var submitAttempted by remember { mutableStateOf(false) }

    val latValidation = InputValidator.parseAndValidateLatitude(latText)
    val lonValidation = InputValidator.parseAndValidateLongitude(lonText)
    val eleValidation = InputValidator.parseAndValidateElevation(elevationText, isOptional = true)
    val isTzValid = TimeZoneResolver.isValidTimeZoneId(timezoneText)

    val detectedTz = remember(latText, lonText) {
        if (latValidation is ValidationResult.Valid && lonValidation is ValidationResult.Valid) {
            OfflineTimezoneLookup.lookup(latValidation.value, lonValidation.value)
        } else null
    }

    val isLatError = (submitAttempted || latText.isNotEmpty()) && latValidation is ValidationResult.Invalid
    val isLonError = (submitAttempted || lonText.isNotEmpty()) && lonValidation is ValidationResult.Invalid
    val isEleError = (submitAttempted || elevationText.isNotEmpty()) && eleValidation is ValidationResult.Invalid
    val isTzError = (submitAttempted || timezoneText.isNotEmpty()) && !isTzValid

    val canSave = latValidation is ValidationResult.Valid &&
            lonValidation is ValidationResult.Valid &&
            eleValidation is ValidationResult.Valid &&
            isTzValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom Coordinates") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter latitude, longitude, and select the location's local IANA timezone.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Location Name / Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = latText,
                    onValueChange = { latText = it },
                    label = { Text("Latitude (-90.0 to 90.0)") },
                    isError = isLatError,
                    supportingText = {
                        if (isLatError && latValidation is ValidationResult.Invalid) {
                            Text(latValidation.errorMessage, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = lonText,
                    onValueChange = { lonText = it },
                    label = { Text("Longitude (-180.0 to 180.0)") },
                    isError = isLonError,
                    supportingText = {
                        if (isLonError && lonValidation is ValidationResult.Invalid) {
                            Text(lonValidation.errorMessage, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = elevationText,
                    onValueChange = { elevationText = it },
                    label = { Text("Elevation in meters (-500 to 9,000)") },
                    isError = isEleError,
                    supportingText = {
                        if (isEleError && eleValidation is ValidationResult.Invalid) {
                            Text(eleValidation.errorMessage, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = timezoneText,
                    onValueChange = { timezoneText = it },
                    label = { Text("IANA Timezone ID") },
                    isError = isTzError,
                    supportingText = {
                        if (isTzError) {
                            Text("Must be a valid IANA ID (e.g. Asia/Colombo, Europe/London)", color = MaterialTheme.colorScheme.error)
                        } else if (detectedTz != null && detectedTz != timezoneText) {
                            Text("Suggested from coordinates: $detectedTz", color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (detectedTz != null && detectedTz != timezoneText) {
                    SuggestionChip(
                        onClick = { timezoneText = detectedTz },
                        label = { Text("Apply: $detectedTz") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = {
                    submitAttempted = true
                    val validLat = (latValidation as? ValidationResult.Valid)?.value ?: return@Button
                    val validLon = (lonValidation as? ValidationResult.Valid)?.value ?: return@Button
                    val validEle = (eleValidation as? ValidationResult.Valid)?.value ?: 0.0
                    onSave(name, validLat, validLon, validEle, timezoneText.trim())
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
