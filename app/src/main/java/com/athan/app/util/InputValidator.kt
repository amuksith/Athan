package com.athan.app.util

sealed class ValidationResult<out T> {
    data class Valid<T>(val value: T) : ValidationResult<T>()
    data class Invalid(val errorMessage: String) : ValidationResult<Nothing>()

    val isValid: Boolean get() = this is Valid
    fun getOrNull(): T? = (this as? Valid)?.value
}

object InputValidator {
    // Geographic coordinate limits
    const val MIN_LATITUDE = -90.0
    const val MAX_LATITUDE = 90.0
    const val MIN_LONGITUDE = -180.0
    const val MAX_LONGITUDE = 180.0

    // Elevation limits (terrestrial Earth: Dead Sea shores ~ -430m, Everest ~ 8849m)
    const val MIN_ELEVATION_METERS = -500.0
    const val MAX_ELEVATION_METERS = 9000.0

    // Astronomical custom depression angles
    const val MAX_CUSTOM_ANGLE = 60.0

    // Reminder minutes (0 = none, up to 120 minutes)
    const val MIN_REMINDER_MINUTES = 0
    const val MAX_REMINDER_MINUTES = 120

    // Hijri day adjustment (-2 .. +2 days)
    const val MIN_HIJRI_ADJUSTMENT = -2
    const val MAX_HIJRI_ADJUSTMENT = 2

    // Prayer time adjustment in minutes (-30 .. +30 minutes)
    const val MIN_PRAYER_ADJUSTMENT_MINUTES = -30
    const val MAX_PRAYER_ADJUSTMENT_MINUTES = 30
    const val DEFAULT_PRAYER_ADJUSTMENT_MINUTES = 0

    // --- Domain Validation Functions ---

    fun isValidLatitude(latitude: Double): Boolean {
        return latitude.isFinite() && !latitude.isNaN() && latitude in MIN_LATITUDE..MAX_LATITUDE
    }

    fun isValidLongitude(longitude: Double): Boolean {
        return longitude.isFinite() && !longitude.isNaN() && longitude in MIN_LONGITUDE..MAX_LONGITUDE
    }

    fun isValidElevation(elevationMeters: Double): Boolean {
        return elevationMeters.isFinite() && !elevationMeters.isNaN() && elevationMeters in MIN_ELEVATION_METERS..MAX_ELEVATION_METERS
    }

    fun isValidCustomAngle(angle: Double): Boolean {
        return angle.isFinite() && !angle.isNaN() && angle > 0.0 && angle <= MAX_CUSTOM_ANGLE
    }

    fun isValidReminderMinutes(minutes: Int): Boolean {
        return minutes in MIN_REMINDER_MINUTES..MAX_REMINDER_MINUTES
    }

    fun isValidHijriAdjustment(adjustment: Int): Boolean {
        return adjustment in MIN_HIJRI_ADJUSTMENT..MAX_HIJRI_ADJUSTMENT
    }

    fun isValidPrayerAdjustment(minutes: Int): Boolean {
        return minutes in MIN_PRAYER_ADJUSTMENT_MINUTES..MAX_PRAYER_ADJUSTMENT_MINUTES
    }

    // --- Text-to-Number Parsing & Validation Functions ---

    fun parseAndValidateLatitude(text: String): ValidationResult<Double> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return ValidationResult.Invalid("Latitude cannot be empty.")
        }
        val parsed = parseFiniteDouble(trimmed)
            ?: return ValidationResult.Invalid("Latitude must be a valid finite number.")
        if (parsed < MIN_LATITUDE || parsed > MAX_LATITUDE) {
            return ValidationResult.Invalid("Latitude must be between -90.0° and 90.0°.")
        }
        return ValidationResult.Valid(parsed)
    }

    fun parseAndValidateLongitude(text: String): ValidationResult<Double> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return ValidationResult.Invalid("Longitude cannot be empty.")
        }
        val parsed = parseFiniteDouble(trimmed)
            ?: return ValidationResult.Invalid("Longitude must be a valid finite number.")
        if (parsed < MIN_LONGITUDE || parsed > MAX_LONGITUDE) {
            return ValidationResult.Invalid("Longitude must be between -180.0° and 180.0°.")
        }
        return ValidationResult.Valid(parsed)
    }

    fun parseAndValidateElevation(text: String, isOptional: Boolean = true): ValidationResult<Double> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return if (isOptional) {
                ValidationResult.Valid(0.0)
            } else {
                ValidationResult.Invalid("Elevation cannot be empty.")
            }
        }
        val parsed = parseFiniteDouble(trimmed)
            ?: return ValidationResult.Invalid("Elevation must be a valid finite number.")
        if (parsed < MIN_ELEVATION_METERS || parsed > MAX_ELEVATION_METERS) {
            return ValidationResult.Invalid("Elevation must be between -500m and 9,000m.")
        }
        return ValidationResult.Valid(parsed)
    }

    fun parseAndValidateCustomAngle(text: String, angleName: String = "Angle"): ValidationResult<Double> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return ValidationResult.Invalid("$angleName angle cannot be empty.")
        }
        val parsed = parseFiniteDouble(trimmed)
            ?: return ValidationResult.Invalid("$angleName angle must be a valid finite number.")
        if (parsed <= 0.0 || parsed > MAX_CUSTOM_ANGLE) {
            return ValidationResult.Invalid("$angleName angle must be between 0.1° and 60.0°.")
        }
        return ValidationResult.Valid(parsed)
    }

    fun parseAndValidateReminderMinutes(text: String): ValidationResult<Int> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return ValidationResult.Invalid("Reminder minutes cannot be empty.")
        }
        val parsed = trimmed.toIntOrNull()
            ?: return ValidationResult.Invalid("Reminder minutes must be an integer.")
        if (parsed < MIN_REMINDER_MINUTES || parsed > MAX_REMINDER_MINUTES) {
            return ValidationResult.Invalid("Reminder minutes must be between 0 and 120.")
        }
        return ValidationResult.Valid(parsed)
    }

    fun parseAndValidateHijriAdjustment(text: String): ValidationResult<Int> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return ValidationResult.Invalid("Hijri adjustment cannot be empty.")
        }
        val parsed = trimmed.toIntOrNull()
            ?: return ValidationResult.Invalid("Hijri adjustment must be an integer.")
        if (parsed < MIN_HIJRI_ADJUSTMENT || parsed > MAX_HIJRI_ADJUSTMENT) {
            return ValidationResult.Invalid("Hijri adjustment must be between -2 and +2 days.")
        }
        return ValidationResult.Valid(parsed)
    }

    fun parseAndValidatePrayerAdjustment(text: String, prayerName: String = "Prayer"): ValidationResult<Int> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return ValidationResult.Invalid("$prayerName adjustment cannot be empty.")
        }
        val cleanText = if (trimmed.startsWith("+")) trimmed.substring(1).trim() else trimmed
        val parsed = cleanText.toIntOrNull()
            ?: return ValidationResult.Invalid("$prayerName adjustment must be an integer minute value.")
        if (parsed < MIN_PRAYER_ADJUSTMENT_MINUTES || parsed > MAX_PRAYER_ADJUSTMENT_MINUTES) {
            return ValidationResult.Invalid("$prayerName adjustment must be between -30 and +30 minutes.")
        }
        return ValidationResult.Valid(parsed)
    }

    private fun parseFiniteDouble(trimmed: String): Double? {
        if (trimmed.equals("nan", ignoreCase = true) ||
            trimmed.contains("infinity", ignoreCase = true)
        ) {
            return null
        }
        val parsed = trimmed.toDoubleOrNull() ?: return null
        if (!parsed.isFinite() || parsed.isNaN()) {
            return null
        }
        return parsed
    }
}
