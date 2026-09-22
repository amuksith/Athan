package com.athan.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.athan.app.audio.AthanAudioCatalog
import com.athan.app.audio.AthanAudioManager
import com.athan.app.audio.AthanSound
import com.athan.app.core.astronomy.PrayerType
import com.athan.app.data.repository.AthanPreferences
import com.athan.app.receiver.AthanAlarmReceiver
import com.athan.app.service.AthanAudioService
import com.athan.app.ui.PrayerViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AthanAudioSystemTest {

    private lateinit var context: Context
    private lateinit var prefs: AthanPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val sp = context.getSharedPreferences("athan_offline_prefs", Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        prefs = AthanPreferences.getInstance(context)
    }

    // 1. Each AthanSound value
    @Test
    fun testAllAthanSoundEnumValues() {
        val sounds = AthanSound.entries
        assertEquals(4, sounds.size)
        assertTrue(sounds.contains(AthanSound.FULL_ATHAN))
        assertTrue(sounds.contains(AthanSound.FIRST_TAKBEER))
        assertTrue(sounds.contains(AthanSound.RINGING))
        assertTrue(sounds.contains(AthanSound.SILENT))

        sounds.forEach { sound ->
            assertFalse("Sound id must not be blank", sound.id.isBlank())
            assertFalse("Sound displayName must not be blank", sound.displayName.isBlank())
            assertFalse("Sound description must not be blank", sound.description.isBlank())
        }
    }

    // 2. Catalog mapping
    @Test
    fun testCatalogMappingForAllSounds() {
        for (sound in AthanSound.entries) {
            val entry = AthanAudioCatalog.getEntry(sound)
            assertEquals(sound, entry.sound)
            assertNotNull(entry.title)
            assertNotNull(entry.description)
            assertEquals(AthanAudioCatalog.isAvailable(sound), entry.isAvailable)
            assertEquals(AthanAudioCatalog.getAudioResource(sound), entry.resourceId)
        }
    }

    // 3. FULL_ATHAN resolves to the bundled full Adhan resource
    @Test
    fun testFullAthanResolvesToBundledResource() {
        val resourceId = AthanAudioCatalog.getAudioResource(AthanSound.FULL_ATHAN)
        assertNotNull("FULL_ATHAN must have a valid resource ID", resourceId)
        assertEquals(R.raw.athan_full, resourceId)
        assertTrue("FULL_ATHAN must be available", AthanAudioCatalog.isAvailable(AthanSound.FULL_ATHAN))

        // Check entry description
        val entry = AthanAudioCatalog.getEntry(AthanSound.FULL_ATHAN)
        assertEquals(R.raw.athan_full, entry.resourceId)
        assertTrue(entry.isAvailable)
    }

    // 4. RINGING resolves correctly
    @Test
    fun testRingingResolvesCorrectly() {
        val resourceId = AthanAudioCatalog.getAudioResource(AthanSound.RINGING)
        assertNotNull("RINGING must have a valid resource ID", resourceId)
        assertEquals(R.raw.ringing, resourceId)
        assertTrue("RINGING must be available", AthanAudioCatalog.isAvailable(AthanSound.RINGING))

        val entry = AthanAudioCatalog.getEntry(AthanSound.RINGING)
        assertEquals(R.raw.ringing, entry.resourceId)
        assertTrue(entry.isAvailable)
    }

    // 5. SILENT has no audio resource
    @Test
    fun testSilentHasNoAudioResource() {
        val resourceId = AthanAudioCatalog.getAudioResource(AthanSound.SILENT)
        assertNull("SILENT must have no audio resource ID", resourceId)
        assertTrue("SILENT must be selectable/available in catalog", AthanAudioCatalog.isAvailable(AthanSound.SILENT))

        var completionCalled = false
        val played = AthanAudioManager.playSound(context, AthanSound.SILENT) {
            completionCalled = true
        }
        assertFalse("Playback should not be initiated for SILENT", played)
        assertTrue("Completion callback should be immediately invoked for SILENT", completionCalled)
        assertFalse("AudioManager should not be playing", AthanAudioManager.isPlaying())
    }

    // 5b. FIRST_TAKBEER is safely marked unavailable without fake speech
    @Test
    fun testFirstTakbeerIsSafelyUnavailableWithoutFakeSpeech() {
        val resourceId = AthanAudioCatalog.getAudioResource(AthanSound.FIRST_TAKBEER)
        assertNull("FIRST_TAKBEER should not invent audio until human recording is bundled", resourceId)
        assertFalse("FIRST_TAKBEER must be marked unavailable", AthanAudioCatalog.isAvailable(AthanSound.FIRST_TAKBEER))

        var completionCalled = false
        val played = AthanAudioManager.playSound(context, AthanSound.FIRST_TAKBEER) {
            completionCalled = true
        }
        assertFalse("Playback should not start for unavailable sound", played)
        assertTrue(completionCalled)
    }

    // 6. Invalid preference falls back to FULL_ATHAN
    @Test
    fun testInvalidPreferenceFallsBackToFullAthan() {
        assertEquals(AthanSound.FULL_ATHAN, AthanAudioCatalog.fromNameOrDefault(null))
        assertEquals(AthanSound.FULL_ATHAN, AthanAudioCatalog.fromNameOrDefault(""))
        assertEquals(AthanSound.FULL_ATHAN, AthanAudioCatalog.fromNameOrDefault("   "))
        assertEquals(AthanSound.FULL_ATHAN, AthanAudioCatalog.fromNameOrDefault("CORRUPTED_VALUE"))
        assertEquals(AthanSound.FULL_ATHAN, AthanAudioCatalog.fromNameOrDefault("random_string_123"))

        // Case insensitivity
        assertEquals(AthanSound.FULL_ATHAN, AthanAudioCatalog.fromNameOrDefault("full_athan"))
        assertEquals(AthanSound.FULL_ATHAN, AthanAudioCatalog.fromNameOrDefault("FULL_ATHAN"))
        assertEquals(AthanSound.RINGING, AthanAudioCatalog.fromNameOrDefault("ringing"))
        assertEquals(AthanSound.RINGING, AthanAudioCatalog.fromNameOrDefault("RINGING"))
        assertEquals(AthanSound.SILENT, AthanAudioCatalog.fromNameOrDefault("silent"))
        assertEquals(AthanSound.SILENT, AthanAudioCatalog.fromNameOrDefault("SILENT"))
        assertEquals(AthanSound.FIRST_TAKBEER, AthanAudioCatalog.fromNameOrDefault("FIRST_TAKBEER"))
        assertEquals(AthanSound.FIRST_TAKBEER, AthanAudioCatalog.fromNameOrDefault("first_takbeer"))

        // Legacy compatibility mappings
        assertEquals(AthanSound.RINGING, AthanAudioCatalog.fromNameOrDefault("gentle_chime"))
        assertEquals(AthanSound.FIRST_TAKBEER, AthanAudioCatalog.fromNameOrDefault("short_takbeer"))
    }

    // 7. Selected sound survives app restart
    @Test
    fun testSelectedSoundSurvivesAppRestart() {
        // Initial default
        assertEquals(AthanSound.FULL_ATHAN, prefs.settingsFlow.value.sound)

        // Set to RINGING
        prefs.updateSound(AthanSound.RINGING)
        assertEquals(AthanSound.RINGING, prefs.settingsFlow.value.sound)

        // Simulate app restart by creating a new preferences instance
        val restartedPrefs1 = AthanPreferences(context)
        assertEquals(AthanSound.RINGING, restartedPrefs1.settingsFlow.value.sound)

        // Set to SILENT
        restartedPrefs1.updateSound(AthanSound.SILENT)
        val restartedPrefs2 = AthanPreferences(context)
        assertEquals(AthanSound.SILENT, restartedPrefs2.settingsFlow.value.sound)

        // Set to FULL_ATHAN
        restartedPrefs2.updateSound(AthanSound.FULL_ATHAN)
        val restartedPrefs3 = AthanPreferences(context)
        assertEquals(AthanSound.FULL_ATHAN, restartedPrefs3.settingsFlow.value.sound)

        // Simulate corrupted data in SharedPreferences
        val sp = context.getSharedPreferences("athan_offline_prefs", Context.MODE_PRIVATE)
        sp.edit().putString("sound", "NON_EXISTENT_CORRUPTED_SOUND").commit()
        val restartedPrefs4 = AthanPreferences(context)
        assertEquals(AthanSound.FULL_ATHAN, restartedPrefs4.settingsFlow.value.sound)
    }

    // 8. Preview uses the catalog and does not schedule alarms
    @Test
    fun testPreviewUsesCatalogAndDoesNotModifyAlarms() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowApp = shadowOf(app)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)

        val initialAlarmsCount = shadowAlarmManager.scheduledAlarms.size

        val viewModel = PrayerViewModel(app)

        // Preview sound via ViewModel
        assertFalse(viewModel.isAudioTesting.value)
        viewModel.testAudio(AthanSound.SILENT)

        // Audio preview must never schedule alarms
        assertEquals("Preview must never create alarms", initialAlarmsCount, shadowAlarmManager.scheduledAlarms.size)

        // Stop preview
        viewModel.stopAudio()
        assertFalse(viewModel.isAudioTesting.value)
    }

    // 9. Prayer alarm uses the catalog
    @Test
    fun testPrayerAlarmUsesCatalog() {
        prefs.updateSound(AthanSound.FULL_ATHAN)

        val receiver = AthanAlarmReceiver()
        val intent = Intent(AthanAlarmReceiver.ACTION_PRAYER_ALARM).apply {
            putExtra(AthanAlarmReceiver.EXTRA_PRAYER_TYPE, PrayerType.FAJR.name)
            putExtra(AthanAlarmReceiver.EXTRA_PRAYER_NAME, "Fajr")
            putExtra(AthanAlarmReceiver.EXTRA_ARABIC_NAME, "الفجر")
            putExtra(AthanAlarmReceiver.EXTRA_IS_PRE_REMINDER, false)
        }

        receiver.onReceive(context, intent)

        // Verify service intent was started with correct sound
        val shadowApp: ShadowApplication = shadowOf(context as Application)
        val nextService = shadowApp.nextStartedService
        assertNotNull("AthanAudioService should be started", nextService)
        assertEquals(AthanAudioService::class.java.name, nextService.component?.className)
        assertEquals(AthanSound.FULL_ATHAN.name, nextService.getStringExtra(AthanAudioService.EXTRA_SOUND_TYPE))

        // Test pre-reminder never starts Athan audio service
        val reminderIntent = Intent(AthanAlarmReceiver.ACTION_PRAYER_ALARM).apply {
            putExtra(AthanAlarmReceiver.EXTRA_PRAYER_TYPE, PrayerType.DHUHR.name)
            putExtra(AthanAlarmReceiver.EXTRA_PRAYER_NAME, "Dhuhr")
            putExtra(AthanAlarmReceiver.EXTRA_ARABIC_NAME, "الظهر")
            putExtra(AthanAlarmReceiver.EXTRA_IS_PRE_REMINDER, true)
        }
        receiver.onReceive(context, reminderIntent)

        val reminderService = shadowApp.nextStartedService
        assertNull("AthanAudioService must NOT be started for pre-reminder", reminderService)
    }

    // 10. No network dependency is introduced
    @Test
    fun testNoNetworkDependencyIntroduced() {
        val packageManager = context.packageManager
        val packageInfo = packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requestedPermissions = packageInfo.requestedPermissions ?: emptyArray()

        // Privacy: INTERNET permission must NOT be present
        assertFalse(
            "App must NOT request android.permission.INTERNET",
            requestedPermissions.contains("android.permission.INTERNET")
        )
    }

    // 11. Stop sound safety
    @Test
    fun testStopSoundSafety() {
        AthanAudioManager.stopSound()
        assertFalse(AthanAudioManager.isPlaying())
        // Multiple calls to stopSound should be safe and idempotent
        AthanAudioManager.stopSound()
        assertFalse(AthanAudioManager.isPlaying())
    }
}
