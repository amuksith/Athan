package com.athan.app.audio

import android.content.Context
import android.media.MediaPlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import org.robolectric.Shadows.shadowOf
import com.athan.app.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AthanAudioManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AthanAudioManager.resetForTesting()

        val ringingUri = "android.resource://${context.packageName}/${R.raw.ringing}"
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(ringingUri),
            ShadowMediaPlayer.MediaInfo(2000, 0)
        )

        val athanFullUri = "android.resource://${context.packageName}/${R.raw.athan_full}"
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(athanFullUri),
            ShadowMediaPlayer.MediaInfo(5000, 0)
        )
    }

    @After
    fun tearDown() {
        AthanAudioManager.resetForTesting()
        ShadowMediaPlayer.resetStaticState()
    }

    // 1. Starting playback A, then starting playback B; a delayed completion from A must not stop B.
    @Test
    fun testDelayedCompletionFromPreviousSessionDoesNotStopCurrentSession() {
        val playerA = MediaPlayer()
        val playerB = MediaPlayer()
        var completionACalled = false
        var completionBCalled = false

        val genA = AthanAudioManager.createSessionForTesting(playerA) {
            completionACalled = true
        }
        val genB = AthanAudioManager.createSessionForTesting(playerB) {
            completionBCalled = true
        }

        assertTrue("Playback B must be active", AthanAudioManager.isPlaying())
        assertSame("Active player must be playerB", playerB, AthanAudioManager.activePlayer)

        // Delayed completion from session A arrives
        AthanAudioManager.handlePlaybackFinished(genA, playerA)

        // Verify session B is completely unharmed
        assertTrue("Session B must still be marked playing", AthanAudioManager.isPlaying())
        assertSame("Active player must remain playerB", playerB, AthanAudioManager.activePlayer)
        assertFalse("Stale session A completion must NOT be invoked", completionACalled)
        assertFalse("Session B completion must NOT be invoked by session A", completionBCalled)

        // Now session B finishes normally
        AthanAudioManager.handlePlaybackFinished(genB, playerB)
        assertFalse("Playback must be stopped after session B completes", AthanAudioManager.isPlaying())
        assertNull("Active player must be null after session B completes", AthanAudioManager.activePlayer)
        assertTrue("Session B completion callback must be invoked", completionBCalled)
    }

    // 2. Starting playback A, then starting playback B; a delayed error from A must not stop B.
    @Test
    fun testDelayedErrorFromPreviousSessionDoesNotStopCurrentSession() {
        val playerA = MediaPlayer()
        val playerB = MediaPlayer()
        var completionACalled = false
        var completionBCalled = false

        val genA = AthanAudioManager.createSessionForTesting(playerA) {
            completionACalled = true
        }
        val genB = AthanAudioManager.createSessionForTesting(playerB) {
            completionBCalled = true
        }

        // Delayed error from session A arrives
        val errorHandled = AthanAudioManager.handlePlaybackError(genA, playerA, what = 1, extra = -1004)
        assertTrue(errorHandled)

        // Verify session B is completely unharmed
        assertTrue("Session B must still be playing after stale error from session A", AthanAudioManager.isPlaying())
        assertSame("Active player must remain playerB", playerB, AthanAudioManager.activePlayer)
        assertFalse("Stale session A callback must NOT be invoked", completionACalled)
        assertFalse("Session B callback must NOT be invoked", completionBCalled)

        // Session B can now complete normally
        AthanAudioManager.handlePlaybackFinished(genB, playerB)
        assertFalse(AthanAudioManager.isPlaying())
        assertTrue(completionBCalled)
    }

    // 3. "stopSound()" followed by a delayed completion from the stopped player must not invoke its completion callback.
    @Test
    fun testStopSoundFollowedByDelayedCompletionDoesNotInvokeCallback() {
        val player = MediaPlayer()
        var completionCalled = false

        val gen = AthanAudioManager.createSessionForTesting(player) {
            completionCalled = true
        }
        assertTrue(AthanAudioManager.isPlaying())
        assertSame(player, AthanAudioManager.activePlayer)

        // User explicitly stops sound
        AthanAudioManager.stopSound()
        assertFalse("isPlaying must be false immediately after stopSound", AthanAudioManager.isPlaying())
        assertNull("activePlayer must be null after stopSound", AthanAudioManager.activePlayer)

        // Delayed completion from the stopped player arrives
        AthanAudioManager.handlePlaybackFinished(gen, player)

        assertFalse("Completion callback must NOT be invoked for stopped player", completionCalled)
        assertFalse(AthanAudioManager.isPlaying())
        assertNull(AthanAudioManager.activePlayer)
    }

    // 4. Starting playback, calling "stopSound()" twice, must remain safe and idempotent.
    @Test
    fun testRepeatedStopSoundIsSafeAndIdempotent() {
        val player = MediaPlayer()
        var completionCalled = false

        AthanAudioManager.createSessionForTesting(player) {
            completionCalled = true
        }
        assertTrue(AthanAudioManager.isPlaying())

        // First stop
        AthanAudioManager.stopSound()
        assertFalse(AthanAudioManager.isPlaying())
        assertNull(AthanAudioManager.activePlayer)

        // Second stop
        AthanAudioManager.stopSound()
        assertFalse(AthanAudioManager.isPlaying())
        assertNull(AthanAudioManager.activePlayer)

        // Third stop when already empty
        AthanAudioManager.stopSound()
        assertFalse(AthanAudioManager.isPlaying())
        assertNull(AthanAudioManager.activePlayer)
        assertFalse("Completion callback must not be called", completionCalled)
    }

    // 5. A completion callback must execute at most once.
    @Test
    fun testCompletionCallbackExecutesAtMostOnce() {
        val player = MediaPlayer()
        var completionCount = 0

        val gen = AthanAudioManager.createSessionForTesting(player) {
            completionCount++
        }

        // First completion
        AthanAudioManager.handlePlaybackFinished(gen, player)
        assertEquals(1, completionCount)
        assertFalse(AthanAudioManager.isPlaying())

        // Duplicate completion callback for same generation
        AthanAudioManager.handlePlaybackFinished(gen, player)
        assertEquals("Completion callback must execute at most once", 1, completionCount)

        // A third delayed completion
        AthanAudioManager.handlePlaybackFinished(gen, player)
        assertEquals("Completion callback must execute at most once", 1, completionCount)
    }

    // 6. An error callback followed by completion must not produce duplicate completion handling.
    @Test
    fun testErrorCallbackFollowedByCompletionDoesNotProduceDuplicateHandling() {
        val player = MediaPlayer()
        var completionCount = 0

        val gen = AthanAudioManager.createSessionForTesting(player) {
            completionCount++
        }

        // Error arrives first
        AthanAudioManager.handlePlaybackError(gen, player, what = 1, extra = 0)
        assertEquals("Completion must be dispatched once on error", 1, completionCount)
        assertFalse(AthanAudioManager.isPlaying())

        // Subsequent completion arrives for the same generation
        AthanAudioManager.handlePlaybackFinished(gen, player)
        assertEquals("Subsequent completion callback must be ignored", 1, completionCount)
    }

    // 7. Repeated rapid "playSound()" → "stopSound()" → "playSound()" sequences must leave only the newest session active.
    @Test
    fun testRapidPlaybackAndStopSequenceLeavesOnlyNewestSessionActive() {
        val p1 = MediaPlayer()
        val p2 = MediaPlayer()
        val p3 = MediaPlayer()
        var c1 = false
        var c2 = false
        var c3 = false

        val g1 = AthanAudioManager.createSessionForTesting(p1) { c1 = true }
        AthanAudioManager.stopSound()

        val g2 = AthanAudioManager.createSessionForTesting(p2) { c2 = true }
        AthanAudioManager.stopSound()

        val g3 = AthanAudioManager.createSessionForTesting(p3) { c3 = true }

        assertTrue(AthanAudioManager.isPlaying())
        assertSame("Only newest session player must be active", p3, AthanAudioManager.activePlayer)

        // Stale callbacks arrive in arbitrary order from previous sessions
        AthanAudioManager.handlePlaybackFinished(g1, p1)
        AthanAudioManager.handlePlaybackError(g2, p2)
        AthanAudioManager.handlePlaybackFinished(g2, p2)

        assertTrue("Newest session must remain active", AthanAudioManager.isPlaying())
        assertSame(p3, AthanAudioManager.activePlayer)
        assertFalse(c1)
        assertFalse(c2)
        assertFalse(c3)

        // Proper completion for session 3
        AthanAudioManager.handlePlaybackFinished(g3, p3)
        assertFalse(AthanAudioManager.isPlaying())
        assertNull(AthanAudioManager.activePlayer)
        assertTrue("Only newest session callback should be invoked", c3)
    }

    // 8. The manager must never report "isPlaying() == true" after the active session has been stopped/completed.
    @Test
    fun testNeverReportsPlayingAfterStopOrCompletion() {
        // Test with SILENT
        var silentCompleted = false
        val silentPlayed = AthanAudioManager.playSound(context, AthanSound.SILENT) {
            silentCompleted = true
        }
        assertFalse(silentPlayed)
        assertTrue(silentCompleted)
        assertFalse("isPlaying() must be false for SILENT", AthanAudioManager.isPlaying())

        // Test with manual session stopped
        val p1 = MediaPlayer()
        val g1 = AthanAudioManager.createSessionForTesting(p1) {}
        assertTrue(AthanAudioManager.isPlaying())
        AthanAudioManager.stopSound()
        assertFalse("isPlaying() must be false immediately after stopSound", AthanAudioManager.isPlaying())

        // Test with manual session completed
        val p2 = MediaPlayer()
        val g2 = AthanAudioManager.createSessionForTesting(p2) {}
        assertTrue(AthanAudioManager.isPlaying())
        AthanAudioManager.handlePlaybackFinished(g2, p2)
        assertFalse("isPlaying() must be false immediately after completion", AthanAudioManager.isPlaying())

        // Test with manual session errored
        val p3 = MediaPlayer()
        val g3 = AthanAudioManager.createSessionForTesting(p3) {}
        assertTrue(AthanAudioManager.isPlaying())
        AthanAudioManager.handlePlaybackError(g3, p3)
        assertFalse("isPlaying() must be false immediately after error", AthanAudioManager.isPlaying())
    }

    // 9. State must remain correct when callbacks execute asynchronously.
    @Test
    fun testConcurrentAsyncExecutionMaintainsConsistentState() {
        val threadCount = 10
        val iterationsPerThread = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val completionDispatches = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val player = MediaPlayer()
                        val gen = AthanAudioManager.createSessionForTesting(player) {
                            completionDispatches.incrementAndGet()
                        }
                        if (i % 3 == 0) {
                            AthanAudioManager.stopSound()
                        } else if (i % 3 == 1) {
                            AthanAudioManager.handlePlaybackFinished(gen, player)
                        } else {
                            AthanAudioManager.handlePlaybackError(gen, player)
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue("All concurrent operations should complete within timeout", completed)

        // Clean up and assert consistency
        AthanAudioManager.stopSound()
        assertFalse(AthanAudioManager.isPlaying())
        assertNull(AthanAudioManager.activePlayer)
    }

    // 10. Verify that a stale callback cannot clear or release the current "activePlayer".
    @Test
    fun testStaleCallbackCannotClearOrReleaseCurrentActivePlayer() {
        val playerA = MediaPlayer()
        val playerB = MediaPlayer()

        val genA = AthanAudioManager.createSessionForTesting(playerA) {}
        val genB = AthanAudioManager.createSessionForTesting(playerB) {}

        assertSame("Active player must be playerB", playerB, AthanAudioManager.activePlayer)

        // Stale completion arrives from playerA
        AthanAudioManager.handlePlaybackFinished(genA, playerA)
        assertSame("Active player must NOT be cleared by stale callback", playerB, AthanAudioManager.activePlayer)
        assertTrue("Playback must NOT be stopped by stale callback", AthanAudioManager.isPlaying())

        // Stale error arrives from playerA
        AthanAudioManager.handlePlaybackError(genA, playerA)
        assertSame("Active player must NOT be cleared by stale error", playerB, AthanAudioManager.activePlayer)
        assertTrue("Playback must NOT be stopped by stale error", AthanAudioManager.isPlaying())
    }

    // 11. Real playSound lifecycle with Robolectric MediaPlayer
    @Test
    fun testRealPlaySoundLifecycleWithRingingSound() {
        var completionInvoked = false
        val played = AthanAudioManager.playSound(context, AthanSound.RINGING) {
            completionInvoked = true
        }

        assertTrue("Playback should start for bundled RINGING sound", played)
        assertTrue("Manager must report playing", AthanAudioManager.isPlaying())
        assertNotNull("Active player must be non-null", AthanAudioManager.activePlayer)

        val activePlayer = AthanAudioManager.activePlayer!!
        val shadow = shadowOf(activePlayer)

        // Verify activePlayer was started
        assertTrue(activePlayer.isPlaying)

        // Invoke completion via Robolectric shadow
        shadow.invokeCompletionListener()

        assertFalse("Manager must not report playing after completion", AthanAudioManager.isPlaying())
        assertNull("Active player must be null after completion", AthanAudioManager.activePlayer)
        assertTrue("Completion callback must be invoked", completionInvoked)
    }

    // 12. Real playSound replacement: Playback A started, then Playback B started via playSound
    @Test
    fun testRealPlaySoundReplacesPreviousPlayerAndReleasesIt() {
        var completionAInvoked = false
        var completionBInvoked = false

        val playedA = AthanAudioManager.playSound(context, AthanSound.RINGING) {
            completionAInvoked = true
        }
        assertTrue(playedA)
        val playerA = AthanAudioManager.activePlayer!!
        val shadowA = shadowOf(playerA)

        // Now start playback B
        val playedB = AthanAudioManager.playSound(context, AthanSound.FULL_ATHAN) {
            completionBInvoked = true
        }
        assertTrue(playedB)
        val playerB = AthanAudioManager.activePlayer!!
        val shadowB = shadowOf(playerB)
        assertNotSame(playerA, playerB)

        // Delayed completion from player A arrives
        shadowA.invokeCompletionListener()

        // Verify player B is still playing and not cleared
        assertTrue("Player B must still be playing", AthanAudioManager.isPlaying())
        assertSame(playerB, AthanAudioManager.activePlayer)
        assertFalse("Completion A must not be called", completionAInvoked)
        assertFalse("Completion B must not be called prematurely", completionBInvoked)

        // Player B finishes
        shadowB.invokeCompletionListener()
        assertFalse(AthanAudioManager.isPlaying())
        assertNull(AthanAudioManager.activePlayer)
        assertTrue("Completion B must be called", completionBInvoked)
    }
}
