package com.athan.app.data.location

import com.athan.app.core.astronomy.CalculationMethod
import com.athan.app.core.astronomy.Madhab
import com.athan.app.util.InputValidator

data class UserLocation(
    val cityName: String,
    val countryName: String,
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double = 0.0,
    val timezoneId: String = "",
    val isGpsDetected: Boolean = false
) {
    fun isValid(): Boolean {
        return InputValidator.isValidLatitude(latitude) &&
                InputValidator.isValidLongitude(longitude) &&
                InputValidator.isValidElevation(elevationMeters)
    }

    fun getDisplayName(): String {
        return if (countryName.isNotBlank()) "$cityName, $countryName" else cityName
    }
}

data class BundledCity(
    val name: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    val timezoneId: String,
    val recommendedMethod: CalculationMethod,
    val recommendedMadhab: Madhab = Madhab.Shafi
)

object BundledCities {
    val CITIES = listOf(
        BundledCity("Makkah (Mecca)", "Saudi Arabia", 21.4225, 39.8262, "Asia/Riyadh", CalculationMethod.UmmAlQura),
        BundledCity("Madinah (Medina)", "Saudi Arabia", 24.5247, 39.5692, "Asia/Riyadh", CalculationMethod.UmmAlQura),
        BundledCity("Riyadh", "Saudi Arabia", 24.7136, 46.6753, "Asia/Riyadh", CalculationMethod.UmmAlQura),
        BundledCity("Jeddah", "Saudi Arabia", 21.5433, 39.1728, "Asia/Riyadh", CalculationMethod.UmmAlQura),
        BundledCity("Jerusalem (Al-Quds)", "Palestine", 31.7683, 35.2137, "Asia/Jerusalem", CalculationMethod.MuslimWorldLeague),
        BundledCity("Cairo", "Egypt", 30.0444, 31.2357, "Africa/Cairo", CalculationMethod.EgyptianGeneral),
        BundledCity("Alexandria", "Egypt", 31.2001, 29.9187, "Africa/Cairo", CalculationMethod.EgyptianGeneral),
        BundledCity("Istanbul", "Turkey", 41.0082, 28.9784, "Europe/Istanbul", CalculationMethod.MuslimWorldLeague, Madhab.Hanafi),
        BundledCity("Ankara", "Turkey", 39.9334, 32.8597, "Europe/Istanbul", CalculationMethod.MuslimWorldLeague, Madhab.Hanafi),
        BundledCity("Dubai", "United Arab Emirates", 25.2048, 55.2708, "Asia/Dubai", CalculationMethod.Dubai),
        BundledCity("Abu Dhabi", "United Arab Emirates", 24.4539, 54.3773, "Asia/Dubai", CalculationMethod.Dubai),
        BundledCity("Doha", "Qatar", 25.2854, 51.5310, "Asia/Qatar", CalculationMethod.Qatar),
        BundledCity("Kuwait City", "Kuwait", 29.3759, 47.9774, "Asia/Kuwait", CalculationMethod.Kuwait),
        BundledCity("Muscat", "Oman", 23.5880, 58.3829, "Asia/Muscat", CalculationMethod.UmmAlQura),
        BundledCity("Manama", "Bahrain", 26.2285, 50.5860, "Asia/Bahrain", CalculationMethod.UmmAlQura),
        BundledCity("Amman", "Jordan", 31.9454, 35.9284, "Asia/Amman", CalculationMethod.MuslimWorldLeague),
        BundledCity("Beirut", "Lebanon", 33.8938, 35.5018, "Asia/Beirut", CalculationMethod.EgyptianGeneral),
        BundledCity("Baghdad", "Iraq", 33.3152, 44.3661, "Asia/Baghdad", CalculationMethod.MuslimWorldLeague),
        BundledCity("Damascus", "Syria", 33.5138, 36.2765, "Asia/Damascus", CalculationMethod.EgyptianGeneral),
        BundledCity("Karachi", "Pakistan", 24.8607, 67.0011, "Asia/Karachi", CalculationMethod.Karachi, Madhab.Hanafi),
        BundledCity("Lahore", "Pakistan", 31.5204, 74.3587, "Asia/Karachi", CalculationMethod.Karachi, Madhab.Hanafi),
        BundledCity("Islamabad", "Pakistan", 33.6844, 73.0479, "Asia/Karachi", CalculationMethod.Karachi, Madhab.Hanafi),
        BundledCity("Dhaka", "Bangladesh", 23.8103, 90.4125, "Asia/Dhaka", CalculationMethod.Karachi, Madhab.Hanafi),
        BundledCity("Chittagong", "Bangladesh", 22.3569, 91.7832, "Asia/Dhaka", CalculationMethod.Karachi, Madhab.Hanafi),
        BundledCity("Jakarta", "Indonesia", -6.2088, 106.8456, "Asia/Jakarta", CalculationMethod.Singapore),
        BundledCity("Surabaya", "Indonesia", -7.2575, 112.7521, "Asia/Jakarta", CalculationMethod.Singapore),
        BundledCity("Kuala Lumpur", "Malaysia", 3.1390, 101.6869, "Asia/Kuala_Lumpur", CalculationMethod.Singapore),
        BundledCity("Singapore", "Singapore", 1.3521, 103.8198, "Asia/Singapore", CalculationMethod.Singapore),
        BundledCity("New Delhi", "India", 28.6139, 77.2090, "Asia/Kolkata", CalculationMethod.Karachi, Madhab.Hanafi),
        BundledCity("Mumbai", "India", 19.0760, 72.8777, "Asia/Kolkata", CalculationMethod.Karachi, Madhab.Hanafi),
        BundledCity("Hyderabad", "India", 17.3850, 78.4867, "Asia/Kolkata", CalculationMethod.Karachi, Madhab.Hanafi),
        BundledCity("Colombo", "Sri Lanka", 6.9271, 79.8612, "Asia/Colombo", CalculationMethod.Karachi, Madhab.Shafi),
        BundledCity("Casablanca", "Morocco", 33.5731, -7.5898, "Africa/Casablanca", CalculationMethod.MuslimWorldLeague),
        BundledCity("Rabat", "Morocco", 34.0209, -6.8416, "Africa/Casablanca", CalculationMethod.MuslimWorldLeague),
        BundledCity("Algiers", "Algeria", 36.7538, 3.0588, "Africa/Algiers", CalculationMethod.MuslimWorldLeague),
        BundledCity("Tunis", "Tunisia", 36.8065, 10.1815, "Africa/Tunis", CalculationMethod.MuslimWorldLeague),
        BundledCity("Tripoli", "Libya", 32.8872, 13.1913, "Africa/Tripoli", CalculationMethod.EgyptianGeneral),
        BundledCity("Khartoum", "Sudan", 15.5007, 32.5599, "Africa/Khartoum", CalculationMethod.EgyptianGeneral),
        BundledCity("Kano", "Nigeria", 12.0022, 8.5920, "Africa/Lagos", CalculationMethod.MuslimWorldLeague),
        BundledCity("Lagos", "Nigeria", 6.5244, 3.3792, "Africa/Lagos", CalculationMethod.MuslimWorldLeague),
        BundledCity("Nairobi", "Kenya", -1.2921, 36.8219, "Africa/Nairobi", CalculationMethod.MuslimWorldLeague),
        BundledCity("Johannesburg", "South Africa", -26.2041, 28.0473, "Africa/Johannesburg", CalculationMethod.MuslimWorldLeague),
        BundledCity("London", "United Kingdom", 51.5074, -0.1278, "Europe/London", CalculationMethod.MuslimWorldLeague),
        BundledCity("Birmingham", "United Kingdom", 52.4862, -1.8904, "Europe/London", CalculationMethod.MuslimWorldLeague),
        BundledCity("Manchester", "United Kingdom", 53.4808, -2.2426, "Europe/London", CalculationMethod.MuslimWorldLeague),
        BundledCity("Paris", "France", 48.8566, 2.3522, "Europe/Paris", CalculationMethod.MuslimWorldLeague),
        BundledCity("Marseille", "France", 43.2965, 5.3698, "Europe/Paris", CalculationMethod.MuslimWorldLeague),
        BundledCity("Berlin", "Germany", 52.5200, 13.4050, "Europe/Berlin", CalculationMethod.MuslimWorldLeague),
        BundledCity("Frankfurt", "Germany", 50.1109, 8.6821, "Europe/Berlin", CalculationMethod.MuslimWorldLeague),
        BundledCity("Amsterdam", "Netherlands", 52.3676, 4.9041, "Europe/Amsterdam", CalculationMethod.MuslimWorldLeague),
        BundledCity("Brussels", "Belgium", 50.8503, 4.3517, "Europe/Brussels", CalculationMethod.MuslimWorldLeague),
        BundledCity("Vienna", "Austria", 48.2082, 16.3738, "Europe/Vienna", CalculationMethod.MuslimWorldLeague),
        BundledCity("Zurich", "Switzerland", 47.3769, 8.5417, "Europe/Zurich", CalculationMethod.MuslimWorldLeague),
        BundledCity("Rome", "Italy", 41.9028, 12.4964, "Europe/Rome", CalculationMethod.MuslimWorldLeague),
        BundledCity("Madrid", "Spain", 40.4168, -3.7038, "Europe/Madrid", CalculationMethod.MuslimWorldLeague),
        BundledCity("Sarajevo", "Bosnia and Herzegovina", 43.8563, 18.4131, "Europe/Sarajevo", CalculationMethod.MuslimWorldLeague, Madhab.Hanafi),
        BundledCity("Pristina", "Kosovo", 42.6629, 21.1655, "Europe/Belgrade", CalculationMethod.MuslimWorldLeague, Madhab.Hanafi),
        BundledCity("Tirana", "Albania", 41.3275, 19.8187, "Europe/Tirane", CalculationMethod.MuslimWorldLeague, Madhab.Hanafi),
        BundledCity("Stockholm", "Sweden", 59.3293, 18.0686, "Europe/Stockholm", CalculationMethod.MuslimWorldLeague),
        BundledCity("Oslo", "Norway", 59.9139, 10.7522, "Europe/Oslo", CalculationMethod.MuslimWorldLeague),
        BundledCity("Copenhagen", "Denmark", 55.6761, 12.5683, "Europe/Copenhagen", CalculationMethod.MuslimWorldLeague),
        BundledCity("Helsinki", "Finland", 60.1699, 24.9384, "Europe/Helsinki", CalculationMethod.MuslimWorldLeague),
        BundledCity("Moscow", "Russia", 55.7558, 37.6173, "Europe/Moscow", CalculationMethod.MuslimWorldLeague, Madhab.Hanafi),
        BundledCity("Kazan", "Russia", 55.7887, 49.1221, "Europe/Moscow", CalculationMethod.MuslimWorldLeague, Madhab.Hanafi),
        BundledCity("Tashkent", "Uzbekistan", 41.2995, 69.2401, "Asia/Tashkent", CalculationMethod.Karachi, Madhab.Hanafi),
        BundledCity("Samarkand", "Uzbekistan", 39.6270, 66.9750, "Asia/Tashkent", CalculationMethod.Karachi, Madhab.Hanafi),
        BundledCity("Baku", "Azerbaijan", 40.4093, 49.8671, "Asia/Baku", CalculationMethod.MuslimWorldLeague),
        BundledCity("Tehran", "Iran", 35.6892, 51.3890, "Asia/Tehran", CalculationMethod.Tehran),
        BundledCity("New York", "United States", 40.7128, -74.0060, "America/New_York", CalculationMethod.Isna),
        BundledCity("Chicago", "United States", 41.8781, -87.6298, "America/Chicago", CalculationMethod.Isna),
        BundledCity("Los Angeles", "United States", 34.0522, -118.2437, "America/Los_Angeles", CalculationMethod.Isna),
        BundledCity("Houston", "United States", 29.7604, -95.3698, "America/Chicago", CalculationMethod.Isna),
        BundledCity("Dallas", "United States", 32.7767, -96.7970, "America/Chicago", CalculationMethod.Isna),
        BundledCity("Washington D.C.", "United States", 38.9072, -77.0369, "America/New_York", CalculationMethod.Isna),
        BundledCity("San Francisco", "United States", 37.7749, -122.4194, "America/Los_Angeles", CalculationMethod.Isna),
        BundledCity("Toronto", "Canada", 43.6532, -79.3832, "America/Toronto", CalculationMethod.Isna),
        BundledCity("Montreal", "Canada", 45.5017, -73.5673, "America/Toronto", CalculationMethod.Isna),
        BundledCity("Vancouver", "Canada", 49.2827, -123.1207, "America/Vancouver", CalculationMethod.Isna),
        BundledCity("Calgary", "Canada", 51.0447, -114.0719, "America/Edmonton", CalculationMethod.Isna),
        BundledCity("Sydney", "Australia", -33.8688, 151.2093, "Australia/Sydney", CalculationMethod.MuslimWorldLeague),
        BundledCity("Melbourne", "Australia", -37.8136, 144.9631, "Australia/Melbourne", CalculationMethod.MuslimWorldLeague),
        BundledCity("Auckland", "New Zealand", -36.8485, 174.7633, "Pacific/Auckland", CalculationMethod.MuslimWorldLeague),
        BundledCity("Tokyo", "Japan", 35.6762, 139.6503, "Asia/Tokyo", CalculationMethod.MuslimWorldLeague),
        BundledCity("Seoul", "South Korea", 37.5665, 126.9780, "Asia/Seoul", CalculationMethod.MuslimWorldLeague),
        BundledCity("Beijing", "China", 39.9042, 116.4074, "Asia/Shanghai", CalculationMethod.MuslimWorldLeague),
        BundledCity("São Paulo", "Brazil", -23.5505, -46.6333, "America/Sao_Paulo", CalculationMethod.Isna),
        BundledCity("Buenos Aires", "Argentina", -34.6037, -58.3816, "America/Argentina/Buenos_Aires", CalculationMethod.MuslimWorldLeague)
    )

    val DEFAULT_CITY = CITIES[0] // Makkah (Mecca)

    fun search(query: String): List<BundledCity> {
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) return CITIES
        return CITIES.filter {
            it.name.lowercase().contains(trimmed) || it.country.lowercase().contains(trimmed)
        }
    }
}
