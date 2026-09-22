package com.athan.app

import com.athan.app.core.astronomy.AstronomicalEngine
import com.athan.app.core.astronomy.CalculationMethod
import com.athan.app.core.astronomy.HighLatitudeRule
import com.athan.app.core.astronomy.Madhab
import com.athan.app.data.location.UserLocation
import com.athan.app.util.InputValidator
import com.athan.app.util.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class InputValidatorTest {

    // 1. Latitude Tests
    @Test
    fun latitude_validValues_areAccepted() {
        assertTrue(InputValidator.isValidLatitude(0.0))
        assertTrue(InputValidator.isValidLatitude(90.0))
        assertTrue(InputValidator.isValidLatitude(-90.0))
        assertTrue(InputValidator.isValidLatitude(21.4225)) // Mecca
        assertTrue(InputValidator.isValidLatitude(-33.8688)) // Sydney
    }

    @Test
    fun latitude_invalidValues_areRejected() {
        assertFalse(InputValidator.isValidLatitude(90.0001))
        assertFalse(InputValidator.isValidLatitude(-90.0001))
        assertFalse(InputValidator.isValidLatitude(100.0))
        assertFalse(InputValidator.isValidLatitude(-180.0))
        assertFalse(InputValidator.isValidLatitude(Double.NaN))
        assertFalse(InputValidator.isValidLatitude(Double.POSITIVE_INFINITY))
        assertFalse(InputValidator.isValidLatitude(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun latitude_parseAndValidate_worksCorrectly() {
        val validResult = InputValidator.parseAndValidateLatitude("21.4225")
        assertTrue(validResult is ValidationResult.Valid)
        assertEquals(21.4225, (validResult as ValidationResult.Valid).value, 1e-6)

        val boundaryResult = InputValidator.parseAndValidateLatitude(" -90.0 ")
        assertTrue(boundaryResult is ValidationResult.Valid)
        assertEquals(-90.0, (boundaryResult as ValidationResult.Valid).value, 1e-6)

        // Unparseable
        val emptyResult = InputValidator.parseAndValidateLatitude("")
        assertTrue(emptyResult is ValidationResult.Invalid)

        val textResult = InputValidator.parseAndValidateLatitude("abc")
        assertTrue(textResult is ValidationResult.Invalid)

        val nanResult = InputValidator.parseAndValidateLatitude("NaN")
        assertTrue(nanResult is ValidationResult.Invalid)

        val infResult = InputValidator.parseAndValidateLatitude("Infinity")
        assertTrue(infResult is ValidationResult.Invalid)

        // Out of range (must NOT clamp)
        val outOfRangeResult = InputValidator.parseAndValidateLatitude("91.0")
        assertTrue(outOfRangeResult is ValidationResult.Invalid)
        assertTrue((outOfRangeResult as ValidationResult.Invalid).errorMessage.contains("-90.0"))
    }

    // 2. Longitude Tests
    @Test
    fun longitude_validValues_areAccepted() {
        assertTrue(InputValidator.isValidLongitude(0.0))
        assertTrue(InputValidator.isValidLongitude(180.0))
        assertTrue(InputValidator.isValidLongitude(-180.0))
        assertTrue(InputValidator.isValidLongitude(39.8262)) // Mecca
        assertTrue(InputValidator.isValidLongitude(151.2093)) // Sydney
    }

    @Test
    fun longitude_invalidValues_areRejected() {
        assertFalse(InputValidator.isValidLongitude(180.0001))
        assertFalse(InputValidator.isValidLongitude(-180.0001))
        assertFalse(InputValidator.isValidLongitude(200.0))
        assertFalse(InputValidator.isValidLongitude(-360.0))
        assertFalse(InputValidator.isValidLongitude(Double.NaN))
        assertFalse(InputValidator.isValidLongitude(Double.POSITIVE_INFINITY))
        assertFalse(InputValidator.isValidLongitude(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun longitude_parseAndValidate_worksCorrectly() {
        val validResult = InputValidator.parseAndValidateLongitude("39.8262")
        assertTrue(validResult is ValidationResult.Valid)
        assertEquals(39.8262, (validResult as ValidationResult.Valid).value, 1e-6)

        val nanResult = InputValidator.parseAndValidateLongitude("NaN")
        assertTrue(nanResult is ValidationResult.Invalid)

        val outOfRangeResult = InputValidator.parseAndValidateLongitude("-181.0")
        assertTrue(outOfRangeResult is ValidationResult.Invalid)
        assertTrue((outOfRangeResult as ValidationResult.Invalid).errorMessage.contains("-180.0"))
    }

    // 3. Elevation Tests
    @Test
    fun elevation_validValues_areAccepted() {
        assertTrue(InputValidator.isValidElevation(0.0))
        assertTrue(InputValidator.isValidElevation(-500.0)) // Dead Sea lowest point
        assertTrue(InputValidator.isValidElevation(8848.0)) // Mt Everest
        assertTrue(InputValidator.isValidElevation(9000.0))
    }

    @Test
    fun elevation_invalidValues_areRejected() {
        assertFalse(InputValidator.isValidElevation(-500.1))
        assertFalse(InputValidator.isValidElevation(9000.1))
        assertFalse(InputValidator.isValidElevation(10000.0))
        assertFalse(InputValidator.isValidElevation(Double.NaN))
        assertFalse(InputValidator.isValidElevation(Double.POSITIVE_INFINITY))
        assertFalse(InputValidator.isValidElevation(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun elevation_parseAndValidate_worksCorrectly() {
        // Optional blank yields 0.0
        val optionalBlank = InputValidator.parseAndValidateElevation("", isOptional = true)
        assertTrue(optionalBlank is ValidationResult.Valid)
        assertEquals(0.0, (optionalBlank as ValidationResult.Valid).value, 1e-6)

        // Required blank yields invalid
        val requiredBlank = InputValidator.parseAndValidateElevation("", isOptional = false)
        assertTrue(requiredBlank is ValidationResult.Invalid)

        // Invalid text
        val invalidText = InputValidator.parseAndValidateElevation("meters", isOptional = true)
        assertTrue(invalidText is ValidationResult.Invalid)

        // NaN text
        val nanText = InputValidator.parseAndValidateElevation("NaN", isOptional = true)
        assertTrue(nanText is ValidationResult.Invalid)

        // Out of range
        val outOfRange = InputValidator.parseAndValidateElevation("9500", isOptional = true)
        assertTrue(outOfRange is ValidationResult.Invalid)
    }

    // 4. Custom Angle Tests (Fajr & Isha)
    @Test
    fun customAngle_validValues_areAccepted() {
        assertTrue(InputValidator.isValidCustomAngle(0.1))
        assertTrue(InputValidator.isValidCustomAngle(12.0))
        assertTrue(InputValidator.isValidCustomAngle(18.0))
        assertTrue(InputValidator.isValidCustomAngle(17.0))
        assertTrue(InputValidator.isValidCustomAngle(19.5))
        assertTrue(InputValidator.isValidCustomAngle(60.0))
    }

    @Test
    fun customAngle_invalidValues_areRejected() {
        assertFalse(InputValidator.isValidCustomAngle(0.0))
        assertFalse(InputValidator.isValidCustomAngle(-0.1))
        assertFalse(InputValidator.isValidCustomAngle(-18.0))
        assertFalse(InputValidator.isValidCustomAngle(60.1))
        assertFalse(InputValidator.isValidCustomAngle(91.0))
        assertFalse(InputValidator.isValidCustomAngle(Double.NaN))
        assertFalse(InputValidator.isValidCustomAngle(Double.POSITIVE_INFINITY))
        assertFalse(InputValidator.isValidCustomAngle(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun customAngle_parseAndValidate_doesNotClamp() {
        // A value of 91 must be rejected, NOT clamped to 60 or 18
        val result91 = InputValidator.parseAndValidateCustomAngle("91", "Fajr")
        assertTrue(result91 is ValidationResult.Invalid)
        assertTrue((result91 as ValidationResult.Invalid).errorMessage.contains("Fajr"))

        val resultZero = InputValidator.parseAndValidateCustomAngle("0", "Isha")
        assertTrue(resultZero is ValidationResult.Invalid)

        val resultNaN = InputValidator.parseAndValidateCustomAngle("NaN", "Fajr")
        assertTrue(resultNaN is ValidationResult.Invalid)

        val valid18 = InputValidator.parseAndValidateCustomAngle("18.5", "Fajr")
        assertTrue(valid18 is ValidationResult.Valid)
        assertEquals(18.5, (valid18 as ValidationResult.Valid).value, 1e-6)
    }

    // 5. Reminder Minutes Tests
    @Test
    fun reminderMinutes_validValues_areAccepted() {
        assertTrue(InputValidator.isValidReminderMinutes(0))
        assertTrue(InputValidator.isValidReminderMinutes(5))
        assertTrue(InputValidator.isValidReminderMinutes(10))
        assertTrue(InputValidator.isValidReminderMinutes(15))
        assertTrue(InputValidator.isValidReminderMinutes(60))
        assertTrue(InputValidator.isValidReminderMinutes(120))
    }

    @Test
    fun reminderMinutes_invalidValues_areRejected() {
        assertFalse(InputValidator.isValidReminderMinutes(-1))
        assertFalse(InputValidator.isValidReminderMinutes(-10))
        assertFalse(InputValidator.isValidReminderMinutes(121))
        assertFalse(InputValidator.isValidReminderMinutes(500))
    }

    @Test
    fun reminderMinutes_parseAndValidate_worksCorrectly() {
        val validResult = InputValidator.parseAndValidateReminderMinutes("15")
        assertTrue(validResult is ValidationResult.Valid)
        assertEquals(15, (validResult as ValidationResult.Valid).value)

        val negativeResult = InputValidator.parseAndValidateReminderMinutes("-5")
        assertTrue(negativeResult is ValidationResult.Invalid)

        val excessiveResult = InputValidator.parseAndValidateReminderMinutes("150")
        assertTrue(excessiveResult is ValidationResult.Invalid)

        val floatResult = InputValidator.parseAndValidateReminderMinutes("15.5")
        assertTrue(floatResult is ValidationResult.Invalid)

        val textResult = InputValidator.parseAndValidateReminderMinutes("abc")
        assertTrue(textResult is ValidationResult.Invalid)
    }

    // 6. Hijri Day Adjustment Tests
    @Test
    fun hijriAdjustment_validValues_areAccepted() {
        assertTrue(InputValidator.isValidHijriAdjustment(-2))
        assertTrue(InputValidator.isValidHijriAdjustment(-1))
        assertTrue(InputValidator.isValidHijriAdjustment(0))
        assertTrue(InputValidator.isValidHijriAdjustment(1))
        assertTrue(InputValidator.isValidHijriAdjustment(2))
    }

    @Test
    fun hijriAdjustment_invalidValues_areRejected() {
        assertFalse(InputValidator.isValidHijriAdjustment(-3))
        assertFalse(InputValidator.isValidHijriAdjustment(3))
        assertFalse(InputValidator.isValidHijriAdjustment(100))
        assertFalse(InputValidator.isValidHijriAdjustment(-100))
    }

    @Test
    fun hijriAdjustment_parseAndValidate_worksCorrectly() {
        val validResult = InputValidator.parseAndValidateHijriAdjustment("+1")
        assertTrue(validResult is ValidationResult.Valid)
        assertEquals(1, (validResult as ValidationResult.Valid).value)

        val outOfBounds = InputValidator.parseAndValidateHijriAdjustment("3")
        assertTrue(outOfBounds is ValidationResult.Invalid)

        val invalidText = InputValidator.parseAndValidateHijriAdjustment("invalid")
        assertTrue(invalidText is ValidationResult.Invalid)
    }

    // 7. Domain Model (UserLocation.isValid)
    @Test
    fun userLocation_isValid_checksAllCoordinatesAndElevation() {
        val validLoc = UserLocation(
            cityName = "Makkah",
            countryName = "Saudi Arabia",
            latitude = 21.4225,
            longitude = 39.8262,
            elevationMeters = 277.0,
            timezoneId = "Asia/Riyadh",
            isGpsDetected = false
        )
        assertTrue(validLoc.isValid())

        val invalidLat = validLoc.copy(latitude = 95.0)
        assertFalse(invalidLat.isValid())

        val nanLat = validLoc.copy(latitude = Double.NaN)
        assertFalse(nanLat.isValid())

        val infLon = validLoc.copy(longitude = Double.POSITIVE_INFINITY)
        assertFalse(infLon.isValid())

        val invalidEle = validLoc.copy(elevationMeters = -600.0)
        assertFalse(invalidEle.isValid())

        val nanEle = validLoc.copy(elevationMeters = Double.NaN)
        assertFalse(nanEle.isValid())
    }

    // 8. AstronomicalEngine Resilience
    @Test
    fun astronomicalEngine_resilientToInvalidValuesWithoutCrashing() {
        // Even if extreme, NaN, or infinite values reach the calculate method directly,
        // it must safely sanitize them and compute valid timestamps without throwing an uncaught exception
        val timesWithExtremeLat = AstronomicalEngine.calculate(
            year = 2026,
            month = 9,
            day = 15,
            latitude = 120.0, // Invalid: exceeds 90
            longitude = 39.8262,
            elevationMeters = 0.0,
            method = CalculationMethod.MuslimWorldLeague,
            madhab = Madhab.Shafi,
            highLatitudeRule = HighLatitudeRule.AngleBased,
            timeZone = TimeZone.getTimeZone("UTC")
        )
        assertNotNull(timesWithExtremeLat)
        assertTrue(timesWithExtremeLat.dhuhr > 0L)

        val timesWithNan = AstronomicalEngine.calculate(
            year = 2026,
            month = 9,
            day = 15,
            latitude = Double.NaN,
            longitude = Double.NaN,
            elevationMeters = Double.NaN,
            customFajrAngle = Double.NaN,
            customIshaAngle = Double.NaN,
            timeZone = TimeZone.getTimeZone("UTC")
        )
        assertNotNull(timesWithNan)
        assertTrue(timesWithNan.dhuhr > 0L)
    }

    // 7. Prayer Adjustment Validation Tests
    @Test
    fun prayerAdjustment_validValues_areAccepted() {
        assertTrue(InputValidator.isValidPrayerAdjustment(-30))
        assertTrue(InputValidator.isValidPrayerAdjustment(-1))
        assertTrue(InputValidator.isValidPrayerAdjustment(0))
        assertTrue(InputValidator.isValidPrayerAdjustment(1))
        assertTrue(InputValidator.isValidPrayerAdjustment(15))
        assertTrue(InputValidator.isValidPrayerAdjustment(30))
    }

    @Test
    fun prayerAdjustment_invalidValues_areRejected() {
        assertFalse(InputValidator.isValidPrayerAdjustment(-31))
        assertFalse(InputValidator.isValidPrayerAdjustment(-100))
        assertFalse(InputValidator.isValidPrayerAdjustment(31))
        assertFalse(InputValidator.isValidPrayerAdjustment(100))
        assertFalse(InputValidator.isValidPrayerAdjustment(Int.MIN_VALUE))
        assertFalse(InputValidator.isValidPrayerAdjustment(Int.MAX_VALUE))
    }

    @Test
    fun prayerAdjustment_textParsing_handlesAllCases() {
        val validZero = InputValidator.parseAndValidatePrayerAdjustment("0", "Fajr")
        assertTrue(validZero is ValidationResult.Valid && validZero.value == 0)

        val validPositive = InputValidator.parseAndValidatePrayerAdjustment("+5", "Fajr")
        assertTrue(validPositive is ValidationResult.Valid && validPositive.value == 5)

        val validPositiveNoSign = InputValidator.parseAndValidatePrayerAdjustment("12", "Fajr")
        assertTrue(validPositiveNoSign is ValidationResult.Valid && validPositiveNoSign.value == 12)

        val validNegative = InputValidator.parseAndValidatePrayerAdjustment("-10", "Fajr")
        assertTrue(validNegative is ValidationResult.Valid && validNegative.value == -10)

        val emptyText = InputValidator.parseAndValidatePrayerAdjustment("", "Fajr")
        assertTrue(emptyText is ValidationResult.Invalid)

        val invalidNumber = InputValidator.parseAndValidatePrayerAdjustment("five", "Fajr")
        assertTrue(invalidNumber is ValidationResult.Invalid)

        val tooLow = InputValidator.parseAndValidatePrayerAdjustment("-31", "Fajr")
        assertTrue(tooLow is ValidationResult.Invalid)

        val tooHigh = InputValidator.parseAndValidatePrayerAdjustment("31", "Fajr")
        assertTrue(tooHigh is ValidationResult.Invalid)
    }
}
