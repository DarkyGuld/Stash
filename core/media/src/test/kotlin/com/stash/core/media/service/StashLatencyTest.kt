package com.stash.core.media.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StashLatencyTest {
    private var now = 0L
    private val lines = mutableListOf<String>()

    private fun latency() = StashLatency(clockMs = { now }, log = { lines += it })

    @Test
    fun `logs tap to first audio once, with the track and origin`() {
        val latency = latency()
        latency.start(trackId = 42, title = "Mario", origin = "addon:Cloud")

        now = 798
        latency.firstAudio(42)

        assertThat(lines).hasSize(1)
        assertThat(lines[0]).contains("track 42 'Mario'")
        assertThat(lines[0]).contains("origin=addon:Cloud")
        assertThat(lines[0]).contains("798 ms")

        // Ready/isPlaying both fire: the second event must not log again.
        now = 900
        latency.firstAudio(42)
        assertThat(lines).hasSize(1)
    }

    @Test
    fun `a pause and resume does not start a new measurement`() {
        val latency = latency()
        latency.start(trackId = 7, title = "Song", origin = null)
        now = 500
        latency.firstAudio(7)
        // No start() on pause/resume: nothing left to log.
        now = 9_000
        latency.firstAudio(7)
        assertThat(lines).hasSize(1)
    }

    @Test
    fun `a first-audio event for a different track is ignored`() {
        val latency = latency()
        latency.start(trackId = 7, title = "Song", origin = null)

        now = 300
        latency.firstAudio(trackId = 8)
        assertThat(lines).isEmpty()

        now = 700
        latency.firstAudio(trackId = 7)
        assertThat(lines).hasSize(1)
        assertThat(lines[0]).contains("700 ms")
    }

    @Test
    fun `a skip replaces the pending measurement instead of stacking`() {
        val latency = latency()
        latency.start(trackId = 1, title = "First", origin = null)
        now = 5_000
        latency.start(trackId = 2, title = "Second", origin = null)

        now = 5_400
        latency.firstAudio(2)

        assertThat(lines).hasSize(1)
        assertThat(lines[0]).contains("track 2 'Second'")
        assertThat(lines[0]).contains("400 ms")
    }

    @Test
    fun `no valid track id means no measurement`() {
        val latency = latency()
        latency.start(trackId = 0, title = "None", origin = null)
        now = 100
        latency.firstAudio(null)
        assertThat(lines).isEmpty()
    }
}
