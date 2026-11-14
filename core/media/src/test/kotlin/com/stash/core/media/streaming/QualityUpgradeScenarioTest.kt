package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.addons.EclipseAddon
import com.stash.core.data.addons.EclipseAddonRepository
import com.stash.core.data.addons.EclipseAddonStream
import com.stash.core.data.addons.EclipseAddonTrack
import com.stash.core.data.db.entity.TrackEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The quiet quality upgrade, end to end through the resolver.
 *
 * The scenario the user hit: a song started on the reserve file (AAC 320),
 * the server upgraded it in the background (HiFi CD), and the app had to
 * notice, swap the file and show the new quality.
 */
class QualityUpgradeScenarioTest {

    private fun track() = TrackEntity(id = 7L, title = "This Girl", artist = "Kungs", durationMs = 196_000)

    private fun addon(settings: Map<String, String>) = EclipseAddon(
        id = "nullbeat-cache",
        name = "NullBeat Cache",
        version = "1",
        manifestUrl = "https://addons.test/nullbeat/manifest.json",
        resources = listOf("stream", "resolve"),
        settings = settings,
    )

    private fun aacStream() = EclipseAddonStream(
        url = "https://cdn.test/aac.m4a",
        format = "m4a",
        codec = "aac",
        quality = "320kbps",
    )

    private fun cdStream() = EclipseAddonStream(
        url = "https://cdn.test/cd.flac",
        format = "flac",
        codec = "flac",
        quality = "HiFi CD",
    )

    private class FakePlayer(
        var trackId: Long? = 7L,
        var positionMs: Long = 0L,
        var durationMs: Long = 196_000L,
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

    /**
     * The whole scenario: the song plays in AAC 320, the server has the FLAC
     * now, and the app asks again - and the answer is the FLAC.
     *
     * The resolver is the real one, so the fresh answer really is a new
     * resolve call with the caches bypassed: the old item id would keep
     * naming the old file.
     */
    @Test
    fun `a song on the reserve file is swapped for the better one the addon now serves`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val addon = addon(mapOf("quality" to "max"))
        coEvery { repository.enabledNow() } returns listOf(addon)
        // The first answer names the AAC file; the second - the fresh one -
        // names the FLAC the server upgraded to.
        coEvery { repository.resolve(addon, any(), any(), any(), any()) } returns
            EclipseAddonTrack(id = "nb:11111111111111111111111111111111:high") andThen
            EclipseAddonTrack(id = "nb:22222222222222222222222222222222:lossless")
        coEvery { repository.stream(addon, "nb:11111111111111111111111111111111:high") } returns aacStream()
        coEvery { repository.stream(addon, "nb:22222222222222222222222222222222:lossless") } returns cdStream()

        val resolver = EclipseAddonStreamResolver(repository)
        // The play: the reserve file.
        val playing = resolver.resolve(track())
        assertThat(playing?.codec).isEqualTo("aac")
        assertThat(playing?.qualityTier).isEqualTo("high")

        val player = FakePlayer(positionMs = 4_000L, stream = playing)
        val watcher = QualityUpgradeWatcher(
            scope = this@runTest,
            player = player,
            resolveFresh = { resolver.resolveFresh(track()) },
            warmAround = { _, _, _, _ -> true },
        )

        watcher.watch(track().id)
        advanceUntilIdle()

        // The song is on the better file now, at the position it had reached.
        assertThat(player.swaps).hasSize(1)
        assertThat(player.swaps[0].first.codec).isEqualTo("flac")
        assertThat(player.swaps[0].first.qualityTier).isEqualTo("lossless")
        assertThat(player.swaps[0].second).isEqualTo(4_000L)
    }

    /** The addon names no tier of its own ("max"): the goal is the best one. */
    @Test
    fun `a max setting still names a tier to aim for`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        coEvery { repository.enabledNow() } returns listOf(addon(mapOf("quality" to "max")))

        val tier = EclipseAddonStreamResolver(repository).targetTier()

        assertThat(tier).isEqualTo(EclipseAddonStreamResolver.BEST_TIER)
        assertThat(EclipseAddonStreamResolver.TIER_RANK[tier!!]).isGreaterThan(
            EclipseAddonStreamResolver.TIER_RANK["lossless"]!!,
        )
    }

    /** A named tier is the goal, as it always was. */
    @Test
    fun `a named setting names its own tier as the goal`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        coEvery { repository.enabledNow() } returns listOf(addon(mapOf("quality" to "lossless")))

        assertThat(EclipseAddonStreamResolver(repository).targetTier()).isEqualTo("lossless")
    }
}
