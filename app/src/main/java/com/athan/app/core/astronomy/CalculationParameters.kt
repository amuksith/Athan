package com.athan.app.core.astronomy

enum class CalculationMethod(val displayName: String, val description: String) {
    MuslimWorldLeague(
        displayName = "Muslim World League",
        description = "Fajr: 18°, Isha: 17°. Widely used in Europe, Far East, and parts of the Americas."
    ),
    Isna(
        displayName = "Islamic Society of North America (ISNA)",
        description = "Fajr: 15°, Isha: 15°. Used primarily in North America (USA & Canada)."
    ),
    EgyptianGeneral(
        displayName = "Egyptian General Authority of Survey",
        description = "Fajr: 19.5°, Isha: 17.5°. Used in Egypt, Africa, Syria, and Lebanon."
    ),
    UmmAlQura(
        displayName = "Umm Al-Qura University, Makkah",
        description = "Fajr: 18.5°, Isha: 90 min after Maghrib (120 min in Ramadan). Used in Saudi Arabia."
    ),
    Karachi(
        displayName = "University of Islamic Sciences, Karachi",
        description = "Fajr: 18°, Isha: 18°. Used in Pakistan, India, Bangladesh, and Afghanistan."
    ),
    Dubai(
        displayName = "Dubai (UAE)",
        description = "Fajr: 18.2°, Isha: 18.2°. Official method of the UAE."
    ),
    Kuwait(
        displayName = "Kuwait",
        description = "Fajr: 18°, Isha: 17.5°. Official method for Kuwait."
    ),
    Qatar(
        displayName = "Qatar",
        description = "Fajr: 18°, Isha: 90 min after Maghrib. Official method for Qatar."
    ),
    Singapore(
        displayName = "MUIS (Singapore)",
        description = "Fajr: 20°, Isha: 18°. Official method for Singapore & Malaysia region."
    ),
    Tehran(
        displayName = "Institute of Geophysics, University of Tehran",
        description = "Fajr: 17.7°, Isha: 14°, Maghrib: 4.5°. Used in Iran and Shia communities."
    ),
    NorthAmerica(
        displayName = "North America (ISNA Alias)",
        description = "Fajr: 15°, Isha: 15°."
    ),
    Custom(
        displayName = "Custom Angles",
        description = "Manually specified Fajr and Isha depression angles."
    );

    fun getFajrAngle(customFajr: Double = 18.0): Double {
        return when (this) {
            MuslimWorldLeague -> 18.0
            Isna, NorthAmerica -> 15.0
            EgyptianGeneral -> 19.5
            UmmAlQura -> 18.5
            Karachi -> 18.0
            Dubai -> 18.2
            Kuwait -> 18.0
            Qatar -> 18.0
            Singapore -> 20.0
            Tehran -> 17.7
            Custom -> customFajr
        }
    }

    fun getIshaAngle(customIsha: Double = 17.0): Double {
        return when (this) {
            MuslimWorldLeague -> 17.0
            Isna, NorthAmerica -> 15.0
            EgyptianGeneral -> 17.5
            UmmAlQura, Qatar -> 0.0 // Fixed interval after Maghrib
            Karachi -> 18.0
            Dubai -> 18.2
            Kuwait -> 17.5
            Singapore -> 18.0
            Tehran -> 14.0
            Custom -> customIsha
        }
    }

    fun isIshaInterval(): Boolean {
        return this == UmmAlQura || this == Qatar
    }

    fun getIshaIntervalMinutes(isRamadan: Boolean = false): Int {
        return when (this) {
            UmmAlQura -> if (isRamadan) 120 else 90
            Qatar -> 90
            else -> 0
        }
    }
}

enum class Madhab(val displayName: String, val shadowMultiplier: Double, val description: String) {
    Shafi(
        displayName = "Shafi'i, Maliki, Hanbali (Standard)",
        shadowMultiplier = 1.0,
        description = "Asr starts when shadow equals object length + noon shadow."
    ),
    Hanafi(
        displayName = "Hanafi",
        shadowMultiplier = 2.0,
        description = "Asr starts when shadow equals twice object length + noon shadow."
    )
}

enum class HighLatitudeRule(val displayName: String, val description: String) {
    None(
        displayName = "None",
        description = "Direct astronomical angle calculation without adjustment."
    ),
    MiddleOfTheNight(
        displayName = "Middle of the Night",
        description = "Fajr and Isha do not exceed half of the total night duration."
    ),
    SeventhOfTheNight(
        displayName = "One-Seventh of the Night",
        description = "Fajr and Isha do not exceed 1/7th of the night duration."
    ),
    AngleBased(
        displayName = "Angle-Based (Proportional)",
        description = "Night portion is scaled proportionally based on angle/60."
    );

    companion object {
        val OneSeventh: HighLatitudeRule get() = SeventhOfTheNight
    }
}
