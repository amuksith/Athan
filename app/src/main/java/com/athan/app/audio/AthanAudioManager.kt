package com.athan.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dedicated manager for audio playback lifecycles.
 * Queries [AthanAudioCatalog] to resolve audio resources and controls MediaPlayer playback.
 *
 * Playback is protected by a monotonically increasing playback generation/session ID
 * to prevent asynchronous MediaPlayer callback race conditions.
 */
object AthanAudioManager {
    private const val TAG = "AthanAudioManager"

    internal class PlaybackSession(
        val generation: Long,
        val player: MediaPlayer?,
        val onCompletion: (() -> Unit)?
    ) {
        private val completed = AtomicBoolean(false)

        /**
         * Atomically marks this session as completed/handled.
         * Returns true only on the first transition from false to true.
         */
        fun markCompleted(): Boolean = completed.compareAndSet(false, true)

        fun isCompleted(): Boolean = completed.get()
    }

    internal val lock = Any()

    @Volatile
    internal var currentGeneration: Long = 0L
        private set

    @Volatile
    internal var activePlayer: MediaPlayer? = null
        private set

    @Volatile
    internal var activeSession: PlaybackSession? = null
        private set

    @Volatile
    internal var isPlayingState: Boolean = false
        private set

    /**
     * Increments and returns the next session generation ID under [lock].
     */
    internal fun nextGeneration(): Long {
        synchronized(lock) {
            currentGeneration++
            return currentGeneration
        }
    }

    private fun nextGenerationLocked(): Long {
        currentGeneration++
        return currentGeneration
    }

    /**
     * Plays the audio associated with [sound] using [AthanAudioCatalog].
     * If [sound] is SILENT or unavailable, completes immediately and returns false.
     *
     * @param context Application or service context.
     * @param sound The logical sound type to play.
     * @param onCompletion Callback invoked when playback finishes, errors, or is skipped.
     * @return true if playback was initiated, false otherwise.
     */
    @Synchronized
    fun playSound(
        context: Context,
        sound: AthanSound,
        onCompletion: (() -> Unit)? = null
    ): Boolean {
        // Step 1: Invalidate previous session and release previous player
        stopSound()

        val resourceId = AthanAudioCatalog.getAudioResource(sound)
        if (resourceId == null) {
            onCompletion?.invoke()
            return false
        }

        // Step 2: Create new MediaPlayer
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val mp = try {
            MediaPlayer.create(context, resourceId, audioAttributes, 0)
                ?: MediaPlayer.create(context, resourceId)
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer.create exception for resource $resourceId", e)
            null
        }

        if (mp == null) {
            Log.w(TAG, "Could not create MediaPlayer for resource $resourceId")
            onCompletion?.invoke()
            return false
        }

        val sessionGen: Long
        val session: PlaybackSession
        var started = false
        var failureCallback: (() -> Unit)? = null

        synchronized(lock) {
            // Step 3: Assign new session ID
            sessionGen = nextGenerationLocked()
            session = PlaybackSession(sessionGen, mp, onCompletion)
            activeSession = session

            // Step 4: Publish active player + playing state BEFORE start()
            activePlayer = mp
            isPlayingState = true

            // Step 5: Attach callbacks carrying that session ID
            mp.setOnCompletionListener {
                handlePlaybackFinished(sessionGen, mp, onCompletion)
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error: what=$what, extra=$extra (gen=$sessionGen)")
                handlePlaybackError(sessionGen, mp, what, extra, onCompletion)
                true
            }

            // Step 6: Start player
            try {
                mp.start()
                started = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start audio playback for $sound (gen=$sessionGen)", e)
                // If start failed, rollback if this session is still the active one
                if (currentGeneration == sessionGen && activeSession == session) {
                    activePlayer = null
                    activeSession = null
                    isPlayingState = false
                    nextGenerationLocked()
                    if (session.markCompleted()) {
                        failureCallback = onCompletion
                    }
                }
                releasePlayer(mp)
            }
        }

        if (!started) {
            failureCallback?.invoke()
            return false
        }

        return true
    }

    /**
     * Stops and releases any active MediaPlayer instance.
     * Invalidates the current generation before releasing the player so callbacks
     * already queued from that player become stale and harmless.
     * Repeated calls remain safe and idempotent.
     */
    @Synchronized
    fun stopSound() {
        var playerToRelease: MediaPlayer? = null
        synchronized(lock) {
            // Invalidate current generation first
            nextGenerationLocked()
            playerToRelease = activePlayer
            val sessionToInvalidate = activeSession

            activePlayer = null
            activeSession = null
            isPlayingState = false

            // Ensure this session cannot be marked completed by future callbacks
            sessionToInvalidate?.markCompleted()
        }

        releasePlayer(playerToRelease)
    }

    /**
     * Handles completion of playback for the given [generation].
     * Releases only the MediaPlayer belonging to that session.
     * Stale callbacks (whose generation != currentGeneration or already handled)
     * are safely ignored without affecting newer playback sessions.
     */
    internal fun handlePlaybackFinished(
        generation: Long,
        player: MediaPlayer?,
        onCompletion: (() -> Unit)? = null
    ) {
        var callbackToInvoke: (() -> Unit)? = null
        var playerToRelease: MediaPlayer? = null

        synchronized(lock) {
            val session = activeSession
            if (session != null && session.generation == generation && currentGeneration == generation) {
                if (session.markCompleted()) {
                    activePlayer = null
                    activeSession = null
                    isPlayingState = false
                    // Advance generation so subsequent duplicate callbacks become stale
                    nextGenerationLocked()
                    callbackToInvoke = session.onCompletion ?: onCompletion
                    playerToRelease = session.player ?: player
                }
            } else {
                Log.d(TAG, "Ignoring stale onCompletion for gen=$generation (currentGen=$currentGeneration)")
            }
        }

        releasePlayer(playerToRelease)
        callbackToInvoke?.invoke()
    }

    /**
     * Handles playback errors for the given [generation].
     * Releases only the MediaPlayer belonging to that session and dispatches completion at most once.
     */
    internal fun handlePlaybackError(
        generation: Long,
        player: MediaPlayer?,
        what: Int = 0,
        extra: Int = 0,
        onCompletion: (() -> Unit)? = null
    ): Boolean {
        var callbackToInvoke: (() -> Unit)? = null
        var playerToRelease: MediaPlayer? = null

        synchronized(lock) {
            val session = activeSession
            if (session != null && session.generation == generation && currentGeneration == generation) {
                if (session.markCompleted()) {
                    activePlayer = null
                    activeSession = null
                    isPlayingState = false
                    // Advance generation so subsequent duplicate callbacks become stale
                    nextGenerationLocked()
                    callbackToInvoke = session.onCompletion ?: onCompletion
                    playerToRelease = session.player ?: player
                }
            } else {
                Log.d(TAG, "Ignoring stale onError for gen=$generation (currentGen=$currentGeneration)")
            }
        }

        releasePlayer(playerToRelease)
        callbackToInvoke?.invoke()
        return true
    }

    /**
     * Releases a MediaPlayer instance safely, catching any exceptions (e.g. if already released).
     */
    internal fun releasePlayer(player: MediaPlayer?) {
        if (player == null) return
        try {
            if (player.isPlaying) {
                player.stop()
            }
        } catch (e: Exception) {
            // Ignored: already stopped or in an invalid state
        }
        try {
            player.reset()
        } catch (e: Exception) {
            // Ignored: already reset or in an invalid state
        }
        try {
            player.release()
        } catch (e: Exception) {
            // Ignored: already released
        }
    }

    /**
     * Returns true if audio is currently playing.
     */
    fun isPlaying(): Boolean = isPlayingState

    /**
     * Helper for deterministic testing of session and callback races.
     */
    @VisibleForTesting
    internal fun createSessionForTesting(
        player: MediaPlayer?,
        onCompletion: (() -> Unit)?
    ): Long {
        synchronized(lock) {
            val gen = nextGenerationLocked()
            val session = PlaybackSession(gen, player, onCompletion)
            activeSession = session
            activePlayer = player
            isPlayingState = true
            return gen
        }
    }

    /**
     * Resets state for testing.
     */
    @VisibleForTesting
    internal fun resetForTesting() {
        stopSound()
        synchronized(lock) {
            currentGeneration = 0L
            activePlayer = null
            activeSession = null
            isPlayingState = false
        }
    }
}
