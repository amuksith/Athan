package com.athan.app.audio

import androidx.annotation.RawRes
import com.athan.app.R

/**
 * Type-safe selection of available Athan alert sounds.
 * Physical audio filenames and resource IDs are isolated in [AthanAudioCatalog].
 */
enum class AthanSound(
    val id: String,
    val displayName: String,
    val description: String
) {
    FULL_ATHAN(
        id = "FULL_ATHAN",
        displayName = "Full Athan Call",
        description = "Authentic recording of the complete Islamic call to prayer"
    ),
    FIRST_TAKBEER(
        id = "FIRST_TAKBEER",
        displayName = "First Takbeer",
        description = "Opening takbeer call (Allahu Akbar)"
    ),
    RINGING(
        id = "RINGING",
        displayName = "Ringing Alarm",
        description = "Standard local ringing notification tone"
    ),
    SILENT(
        id = "SILENT",
        displayName = "Silent / Vibrate Only",
        description = "Visual notification and vibration only without audio"
    )
}

/**
 * Metadata descriptor for audio resources in the catalog.
 */
data class AudioCatalogEntry(
    val sound: AthanSound,
    @RawRes val resourceId: Int?,
    val isAvailable: Boolean,
    val title: String,
    val description: String
)

/**
 * Central audio catalog for the Athan application.
 * This is the SINGLE SOURCE OF TRUTH mapping logical sound types to physical Android resources.
 * Application logic and UI reference only [AthanSound], keeping audio assets easily replaceable.
 */
object AthanAudioCatalog {

    /**
     * Resolves the raw Android resource ID for the given [AthanSound], or null if silent or unavailable.
     * To replace or add recordings, update ONLY this mapping.
     */
    @RawRes
    fun getAudioResource(sound: AthanSound): Int? {
        return when (sound) {
            AthanSound.FULL_ATHAN -> R.raw.athan_full
            AthanSound.FIRST_TAKBEER -> null // Not yet available; safely marked unavailable without fake speech
            AthanSound.RINGING -> R.raw.ringing
            AthanSound.SILENT -> null // Silent has no audio resource
        }
    }

    /**
     * Checks if a human recording or audio asset is currently available in the catalog.
     */
    fun isAvailable(sound: AthanSound): Boolean {
        return when (sound) {
            AthanSound.FULL_ATHAN -> true
            AthanSound.FIRST_TAKBEER -> false // Safely mark unavailable until a real recording is bundled
            AthanSound.RINGING -> true
            AthanSound.SILENT -> true
        }
    }

    /**
     * Returns descriptor info for the given [AthanSound].
     */
    fun getEntry(sound: AthanSound): AudioCatalogEntry {
        val available = isAvailable(sound)
        val resId = getAudioResource(sound)
        val desc = if (!available) {
            "${sound.description} (Recording not yet available)"
        } else {
            sound.description
        }
        return AudioCatalogEntry(
            sound = sound,
            resourceId = resId,
            isAvailable = available,
            title = sound.displayName,
            description = desc
        )
    }

    /**
     * Safely maps a persisted string identifier to an [AthanSound].
     * Supports current enum names and legacy IDs, safely falling back to [AthanSound.FULL_ATHAN]
     * if null, empty, or corrupted.
     */
    fun fromNameOrDefault(name: String?): AthanSound {
        if (name.isNullOrBlank()) return AthanSound.FULL_ATHAN
        return try {
            AthanSound.valueOf(name.trim().uppercase())
        } catch (e: Exception) {
            when (name.trim().lowercase()) {
                "full_athan" -> AthanSound.FULL_ATHAN
                "first_takbeer", "short_takbeer" -> AthanSound.FIRST_TAKBEER
                "ringing", "gentle_chime" -> AthanSound.RINGING
                "silent" -> AthanSound.SILENT
                else -> AthanSound.FULL_ATHAN
            }
        }
    }
}
