package com.stash.core.media

import com.stash.core.model.PlaybackSource
import com.stash.core.model.Track
import java.util.concurrent.ConcurrentHashMap

/**
 * How long a radio candidate that failed a play-time resolve stays out of the
 * station. Short on purpose: a relay restart or a transient metadata miss must
 * not take a song out for the rest of the session. In memory only — a transient
 * miss is never written back to `tracks.is_streamable`.
 */
internal const val RADIO_UNAVAILABLE_TTL_MS = 5 * 60_000L

/**
 * Everything radio replaced when it took the queue, so leaving the station can
 * put the user's own queue back exactly where it was.
 *
 * Captured immediately before [PlayerRepositoryImpl.startRadio] mutates the
 * player. The queue list is the logical queue at that moment; for a big queue
 * ([queueWindow] non-null) it is the window the player held, and the window
 * object keeps the whole order.
 */
internal data class RadioExitSnapshot(
    val tracks: List<Track>,
    val capturedIndex: Int,
    val currentTrackId: Long?,
    val positionMs: Long,
    val source: PlaybackSource,
    val queueWindow: QueueWindow?,
    val windowPinned: Map<Long, Track>,
    val windowUnconfirmed: Boolean,
    val shuffleModeEnabled: Boolean,
    val repeatMode: Int,
    val libraryShuffleActive: Boolean,
    val librarySnapshot: List<Track>,
    val wasPlaying: Boolean,
)

/**
 * The choice of queue slot to restore and whether an exit should restore at
 * all. Pure so [RadioExitRestoreTest] can pin the rules without a player.
 */
internal object RadioExitRestore {
    /** The restored slot: the captured song's id wins, else the captured index, clamped. */
    fun startIndex(tracks: List<Track>, currentTrackId: Long?, capturedIndex: Int): Int {
        if (tracks.isEmpty()) return 0
        val byId = currentTrackId?.let { id -> tracks.indexOfFirst { it.id == id } } ?: -1
        return (if (byId >= 0) byId else capturedIndex).coerceIn(0, tracks.size - 1)
    }

    /**
     * Restore only a real snapshot, when the user wants it, and when the player
     * still belongs to this phone's own queue (not a room, browser or cast).
     */
    fun shouldRestore(hasSnapshot: Boolean, preference: Boolean, queueOwnedElsewhere: Boolean): Boolean =
        hasSnapshot && preference && !queueOwnedElsewhere
}

/**
 * Per-station set of candidates whose stream could not be resolved, with a
 * short negative TTL. Thread-safe: [PlayerRepositoryImpl] marks from the
 * MediaController callback thread and reads from main/IO.
 *
 * Deliberately in-memory: a play-time miss is often a relay or network blip
 * and must not be persisted as `is_streamable = 0`.
 */
internal class RadioUnavailableTracker(
    private val ttlMs: Long = RADIO_UNAVAILABLE_TTL_MS,
) {
    private val deadUntilMs = ConcurrentHashMap<Long, Long>()

    fun markDead(trackId: Long, nowMs: Long = System.currentTimeMillis()) {
        if (trackId == 0L) return
        deadUntilMs[trackId] = nowMs + ttlMs
    }

    fun isDead(trackId: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = deadUntilMs[trackId] ?: return false
        if (nowMs < until) return true
        deadUntilMs.remove(trackId, until)
        return false
    }

    fun clear() = deadUntilMs.clear()

    /** Test/diagnostics helper: sweep expired entries, then say how many are live. */
    internal fun liveCount(nowMs: Long = System.currentTimeMillis()): Int {
        for ((id, until) in deadUntilMs) {
            if (nowMs >= until) deadUntilMs.remove(id, until)
        }
        return deadUntilMs.size
    }
}
