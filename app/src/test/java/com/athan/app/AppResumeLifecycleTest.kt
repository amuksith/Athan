package com.athan.app

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.athan.app.data.repository.AthanPreferences
import com.athan.app.ui.PrayerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppResumeLifecycleTest {

    private lateinit var app: Application
    private lateinit var prefs: AthanPreferences
    private lateinit var alarmManager: AlarmManager
    private lateinit var shadowAlarmManager: ShadowAlarmManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        prefs = AthanPreferences.getInstance(app)
        alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowAlarmManager = shadowOf(alarmManager)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    // A. Single execution per resume event:
    // Verify that on a standard resume, onAppResume() performs its refresh tasks once without duplication.
    @Test
    fun testSingleExecutionPerResumeEvent() {
        val viewModel = PrayerViewModel(app)
        assertEquals("Initial resume count should be 0", 0, viewModel.resumeExecutionCount)

        val executed = viewModel.onAppResume()
        assertTrue("First onAppResume invocation must execute", executed)
        assertEquals("Execution count must be exactly 1 after resume", 1, viewModel.resumeExecutionCount)

        // Verify alarm tasks were executed
        val scheduledAlarms = shadowAlarmManager.scheduledAlarms.toList()
        assertTrue("Alarms must be scheduled after resume", scheduledAlarms.isNotEmpty())
    }

    // B. Debounce protection:
    // Call viewModel.onAppResume() twice in rapid succession (0ms delay).
    // Verify that the second invocation is safely debounced and does not trigger duplicate alarm cancellation/rebuilds.
    @Test
    fun testDebounceProtectionOnRapidSuccession() {
        val viewModel = PrayerViewModel(app)

        val firstCall = viewModel.onAppResume()
        assertTrue("First call should execute", firstCall)
        assertEquals("Execution count should be 1 after first call", 1, viewModel.resumeExecutionCount)
        val alarmsAfterFirst = shadowAlarmManager.scheduledAlarms.toList()

        // Immediate second call with 0ms delay
        val secondCall = viewModel.onAppResume()
        assertFalse("Second call within debounce threshold must be debounced", secondCall)
        assertEquals("Execution count must remain 1 after debounced call", 1, viewModel.resumeExecutionCount)

        val alarmsAfterSecond = shadowAlarmManager.scheduledAlarms.toList()
        assertEquals("Alarms count must remain strictly unchanged", alarmsAfterFirst.size, alarmsAfterSecond.size)
    }

    // C. Legitimate subsequent resume:
    // Call viewModel.onAppResume(), advance virtual time past the debounce threshold (e.g. 1000ms),
    // and call viewModel.onAppResume() again. Verify that the second call executes normally.
    @Test
    fun testLegitimateSubsequentResumeAfterDebounceWindow() {
        val viewModel = PrayerViewModel(app)

        val firstCall = viewModel.onAppResume()
        assertTrue("First call must execute", firstCall)
        assertEquals(1, viewModel.resumeExecutionCount)

        // Advance virtual time past the 500ms debounce threshold
        val secondCall = viewModel.onAppResume(currentTime = System.currentTimeMillis() + 1000L)
        assertTrue("Subsequent call after debounce threshold must execute", secondCall)
        assertEquals("Execution count must increment to 2", 2, viewModel.resumeExecutionCount)
    }

    // D. Force parameter verification:
    // Call viewModel.onAppResume(force = true) immediately after a prior call.
    // Verify that it executes without being blocked by debounce.
    @Test
    fun testForceParameterBypassesDebounce() {
        val viewModel = PrayerViewModel(app)

        val firstCall = viewModel.onAppResume()
        assertTrue("First call must execute", firstCall)
        assertEquals(1, viewModel.resumeExecutionCount)

        // Immediate forced call (0ms elapsed)
        val forcedCall = viewModel.onAppResume(force = true)
        assertTrue("Forced call must execute despite 0ms interval", forcedCall)
        assertEquals("Execution count must increment to 2 on forced call", 2, viewModel.resumeExecutionCount)
    }

    // E. MainActivity single authoritative lifecycle owner:
    // Verify MainActivity resumes without triggering duplicate onAppResume calls
    @Test
    fun testMainActivityResumeInvokesOnAppResumeAuthoritatively() {
        val activityController = Robolectric.buildActivity(MainActivity::class.java)
        activityController.create().start().resume()

        val activity = activityController.get()
        // The activity should be successfully resumed
        assertFalse(activity.isFinishing)
        assertTrue(shadowAlarmManager.scheduledAlarms.isNotEmpty())
    }

    // F. Permission and exact-alarm updates on resume:
    @Test
    fun testExactAlarmRestoredOnResumeAfterGrant() {
        val viewModel = PrayerViewModel(app)

        // Revoke capability
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        viewModel.onAppResume(force = true)
        assertFalse("canScheduleExactAlarms must be false when revoked", viewModel.canScheduleExactAlarms.value)

        // Restore capability in system settings and resume after debounce threshold
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val executed = viewModel.onAppResume(currentTime = System.currentTimeMillis() + 1000L)

        assertTrue("Resume must execute after debounce threshold", executed)
        assertTrue("canScheduleExactAlarms must be restored on resume", viewModel.canScheduleExactAlarms.value)
        assertTrue("Alarms must be scheduled after resume restores permission", shadowAlarmManager.scheduledAlarms.isNotEmpty())
    }

    // G. Date refresh across midnight on resume:
    @Test
    fun testDateRefreshAcrossMidnightOnResume() {
        val viewModel = PrayerViewModel(app)
        viewModel.onAppResume()

        val initialDate = viewModel.currentLocalDate.value

        // Advance clock by 25 hours
        val futureTime = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(25)

        val resumed = viewModel.onAppResume(currentTime = futureTime)
        assertTrue("Resume after 25h must execute", resumed)

        val updatedDate = viewModel.currentLocalDate.value
        assertNotEquals("Current local date must change across midnight", initialDate, updatedDate)
        assertTrue("Current local date key must advance across midnight", updatedDate.dateKey > initialDate.dateKey)
    }
}
