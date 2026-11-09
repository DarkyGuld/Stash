package com.stash.core.media

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.Track
import org.junit.Test

class RadioPlaybackSupportTest {

    private fun track(id: Long) = Track(id = id, title = "t$id", artist = "a", isStreamable = true)

    @Test
    fun `a dead candidate is skipped only for the short negative TTL`() {
        val tracker = RadioUnavailableTracker(ttlMs = 1_000L)

        tracker.markDead(7L, nowMs = 10_000L)

        assertThat(tracker.isDead(7L, nowMs = 10_000L)).isTrue()
        assertThat(tracker.isDead(7L, nowMs = 10_999L)).isTrue()
        assertThat(tracker.isDead(7L, nowMs = 11_000L)).isFalse()
        assertThat(tracker.liveCount(nowMs = 11_000L)).isEqualTo(0)
    }

    @Test
    fun `a new station clears the previous dead set`() {
        val tracker = RadioUnavailableTracker()
        tracker.markDead(7L)

        tracker.clear()

        assertThat(tracker.isDead(7L)).isFalse()
    }

    @Test
    fun `restore picks the captured song by id, not the stale shuffle slot`() {
        val tracks = listOf(track(10), track(20), track(30))

        val index = RadioExitRestore.startIndex(
            tracks = tracks,
            currentTrackId = 30L,
            capturedIndex = 0,
        )

        assertThat(index).isEqualTo(2)
    }

    @Test
    fun `restore falls back to the captured index when the song is gone`() {
        val tracks = listOf(track(10), track(20), track(30))

        val index = RadioExitRestore.startIndex(
            tracks = tracks,
            currentTrackId = 99L,
            capturedIndex = 1,
        )

        assertThat(index).isEqualTo(1)
    }

    @Test
    fun `restore is skipped without a snapshot, with the preference off, or when another output owns the queue`() {
        assertThat(RadioExitRestore.shouldRestore(hasSnapshot = false, preference = true, queueOwnedElsewhere = false)).isFalse()
        assertThat(RadioExitRestore.shouldRestore(hasSnapshot = true, preference = false, queueOwnedElsewhere = false)).isFalse()
        assertThat(RadioExitRestore.shouldRestore(hasSnapshot = true, preference = true, queueOwnedElsewhere = true)).isFalse()
        assertThat(RadioExitRestore.shouldRestore(hasSnapshot = true, preference = true, queueOwnedElsewhere = false)).isTrue()
    }

    @Test
    fun `a radio unavailable exception is found through the playback exception cause chain`() {
        val wrapped = IllegalStateException(
            "player",
            java.io.IOException(
                "data source",
                com.stash.core.media.streaming.RadioTrackUnavailableException(7L),
            ),
        )

        assertThat(
            wrapped.hasCauseOfType(com.stash.core.media.streaming.RadioTrackUnavailableException::class.java),
        ).isTrue()
        assertThat(wrapped.hasCauseOfType(IllegalArgumentException::class.java)).isFalse()
    }
}
