package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The quiet quality upgrade: a song that started on a reserve quality is
 * watched, and the better file is swapped in when the addon has it.
 */
class QualityUpgradeWatcherTest {

    private class FakePlayer(
        var trackId: Long? = 1L,
        var positionMs: Long = 0L,
        var durationMs: Long = 200_000L,
        var stream: StreamUrl? = null,
    ) : QualityUpgradePlayer {
        val swaps = mutableListOf<Pair<StreamUrl, Long>>()
        override fun currentTrackId(): Long? = trackId
        override fun currentPositionMs(): Long = positionMs
        override fun currentDurationMs(): Long = durationMs
        override fun currentStream(): StreamUrl? = stream
        override fun swapTo(stream: StreamUrl, positionMs: Long) {
            swaps += stream to positionMs
            this.stream = stream
        }
    }

    private fun lossy() = StreamUrl(
        url = "https://cdn.test/aac.m4a",
        expiresAtMs = Long.MAX_VALUE,
        codec = "aac",
        bitrateKbps = 320,
        origin = "addon:nullbeat",
        qualityTier = "high",
    )

    private fun lossless() = StreamUrl(
        url = "https://cdn.test/cd.flac",
        expiresAtMs = Long.MAX_VALUE,
        codec = "flac",
        origin = "addon:nullbeat",
        qualityTier = "lossless",
    )

    @Test
    fun `the better file is swapped in at the position the song had reached`() = runTest {
        val player = FakePlayer(positionMs = 12_000L, stream = lossy())
        var asked = 0
        val watcher = QualityUpgradeWatcher(
            scope = this@runTest,
            player = player,
            resolveFresh = { asked++; lossless() },
            warmAround = { _, _, _, _ -> true },
        )

        watcher.watch(1L)
        advanceUntilIdle()

        assertThat(asked).isEqualTo(1)
        assertThat(player.swaps).hasSize(1)
        assertThat(player.swaps[0].first.codec).isEqualTo("flac")
        assertThat(player.swaps[0].first.qualityTier).isEqualTo("lossless")
        // The song carries on where it was - no jump, no restart.
        assertThat(player.swaps[0].second).isEqualTo(12_000L)
    }

    @Test
    fun `a song that is more than three quarters done is left alone`() = runTest {
        val player = FakePlayer(positionMs = 160_000L, durationMs = 200_000L, stream = lossy())
        var asked = 0
        val watcher = QualityUpgradeWatcher(
            scope = this@runTest,
            player = player,
            resolveFresh = { asked++; lossless() },
            warmAround = { _, _, _, _ -> true },
        )

        watcher.watch(1L)
        advanceUntilIdle()

        assertThat(asked).isEqualTo(0)
        assertThat(player.swaps).isEmpty()
    }

    @Test
    fun `a file that cannot be buffered is not swapped in`() = runTest {
        val player = FakePlayer(positionMs = 5_000L, stream = lossy())
        val watcher = QualityUpgradeWatcher(
            scope = this@runTest,
            player = player,
            resolveFresh = { lossless() },
            warmAround = { _, _, _, _ -> false },
        )

        watcher.watch(1L)
        advanceUntilIdle()

        assertThat(player.swaps).isEmpty()
    }

    @Test
    fun `the watcher stops when another song takes over`() = runTest {
        val player = FakePlayer(trackId = 1L, stream = lossy())
        var asked = 0
        val watcher = QualityUpgradeWatcher(
            scope = this@runTest,
            player = player,
            resolveFresh = {
                asked++
                player.trackId = 2L
                null
            },
            warmAround = { _, _, _, _ -> true },
        )

        watcher.watch(1L)
        advanceUntilIdle()

        assertThat(asked).isEqualTo(1)
        assertThat(player.swaps).isEmpty()
    }

    @Test
    fun `the watcher looks at most four times`() = runTest {
        val player = FakePlayer(stream = lossy())
        var asked = 0
        val watcher = QualityUpgradeWatcher(
            scope = this@runTest,
            player = player,
            // The same file: there is nothing better, so nothing is swapped.
            resolveFresh = { asked++; lossy() },
            warmAround = { _, _, _, _ -> true },
        )

        watcher.watch(1L)
        advanceUntilIdle()

        assertThat(asked).isEqualTo(4)
        assertThat(player.swaps).isEmpty()
    }

    @Test
    fun `the watcher looks every three seconds`() = runTest {
        val testScope = this
        val player = FakePlayer(stream = lossy())
        val looks = mutableListOf<Long>()
        val watcher = QualityUpgradeWatcher(
            scope = this@runTest,
            player = player,
            resolveFresh = { looks += testScope.currentTime; null },
            warmAround = { _, _, _, _ -> true },
        )

        watcher.watch(1L)
        advanceUntilIdle()

        assertThat(looks).containsExactly(3_000L, 6_000L, 9_000L, 12_000L).inOrder()
    }

    @Test
    fun `a resolve that fails does not end the watch`() = runTest {
        val player = FakePlayer(positionMs = 1_000L, stream = lossy())
        var asked = 0
        val watcher = QualityUpgradeWatcher(
            scope = this@runTest,
            player = player,
            resolveFresh = {
                asked++
                if (asked == 1) throw IllegalStateException("the addon is down")
                lossless()
            },
            warmAround = { _, _, _, _ -> true },
        )

        watcher.watch(1L)
        advanceUntilIdle()

        assertThat(asked).isEqualTo(2)
        assertThat(player.swaps).hasSize(1)
    }

    // ── the comparison ─────────────────────────────────────────────────────

    @Test
    fun `a lossless file beats a lossy one`() {
        assertThat(isBetter(lossless(), lossy())).isTrue()
        assertThat(isBetter(lossy(), lossless())).isFalse()
    }

    @Test
    fun `a named tier beats a file that only says it is lossless`() {
        val hires = lossless().copy(qualityTier = "hires")
        val cd = lossless().copy(qualityTier = "lossless")

        assertThat(isBetter(hires, cd)).isTrue()
        assertThat(isBetter(cd, hires)).isFalse()
    }

    @Test
    fun `the same file is not better than itself`() {
        assertThat(isBetter(lossless(), lossless())).isFalse()
    }

    @Test
    fun `a tiered lossless file beats an untiered lossless one`() {
        val tiered = lossless().copy(qualityTier = "lossless")
        val untiered = lossless().copy(qualityTier = null)

        assertThat(isBetter(tiered, untiered)).isTrue()
    }
}
