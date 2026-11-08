package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [PrefetchOrchestrator] resolves the *next-up* queue item ahead of
 * auto-advance. Unlike the queue-wide background fill (which must never
 * touch antra — one playlist tap would drain the quota), the next-up
 * track is about to be played: its antra single gets spent either way
 * the moment auto-advance reaches it, and antra jobs take 60-120s, so
 * prefetching it is the only way auto-advance can be seamless during a
 * Qobuz-proxy outage. The resolve therefore goes out with antra ALLOWED
 * (the default).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrefetchOrchestratorTest {

    private val streamingPreference: StreamingPreference = mockk()
    private val streamResolver: StreamSourceRegistry = mockk()
    private val streamUrlCache: StreamUrlCache = mockk(relaxUnitFun = true)
    private val trackDao: TrackDao = mockk()

    private fun orchestrator() = PrefetchOrchestrator(
        streamResolver = streamResolver,
        streamUrlCache = streamUrlCache,
        trackDao = trackDao,
    )

    @Test
    fun prefetch_resolves_nextUp_with_antra_allowed() = runTest {
        coEvery { streamingPreference.current() } returns true
        every { streamUrlCache.get(5L) } returns null
        val track = TrackEntity(
            id = 5L,
            title = "Next Up",
            artist = "Artist",
            album = "Album",
            durationMs = 200_000L,
            youtubeId = "abc123",
            isDownloaded = false,
            isStreamable = true,
        )
        coEvery { trackDao.getById(5L) } returns track
        coEvery {
            streamResolver.resolve(track, allowYouTube = true, allowYtDlp = true, speculative = true)
        } returns null

        orchestrator().onPlaybackProgress(
            scope = this,
            nextTrackId = 5L,
            positionMs = 70_000L,
            durationMs = 100_000L,
        )
        advanceUntilIdle()

        coVerify(exactly = 1) {
            streamResolver.resolve(track, allowYouTube = true, allowYtDlp = true, speculative = true)
        }
    }

    @Test
    fun prefetch_discards_a_result_when_addons_changed_mid_flight() = runTest {
        every { streamUrlCache.get(5L) } returns null
        val track = TrackEntity(
            id = 5L,
            title = "Next Up",
            artist = "Artist",
            album = "Album",
            durationMs = 200_000L,
            youtubeId = "abc123",
            isDownloaded = false,
            isStreamable = true,
        )
        val addonUrl = StreamUrl(
            url = "https://cdn.test/1.flac",
            expiresAtMs = Long.MAX_VALUE,
            codec = "flac",
            origin = "addon:Cloud",
        )
        val generation = kotlinx.coroutines.flow.MutableStateFlow(0L)
        val addonRepository: com.stash.core.data.addons.EclipseAddonRepository = mockk {
            every { addonGeneration } returns generation
        }
        coEvery { trackDao.getById(5L) } returns track
        // The user disables the addon while the speculative resolve is in flight.
        coEvery {
            streamResolver.resolve(track, allowYouTube = true, allowYtDlp = true, speculative = true)
        } coAnswers {
            generation.value = 1L
            addonUrl
        }
        val orchestrator = PrefetchOrchestrator(
            streamResolver = streamResolver,
            streamUrlCache = streamUrlCache,
            trackDao = trackDao,
            eclipseAddons = addonRepository,
        )

        orchestrator.onPlaybackProgress(
            scope = this,
            nextTrackId = 5L,
            positionMs = 70_000L,
            durationMs = 100_000L,
        )
        advanceUntilIdle()

        // The ViewModel cleared the cache on the toggle; this write must not undo it.
        io.mockk.verify(exactly = 0) { streamUrlCache.put(any(), any()) }
    }

    @Test
    fun prefetch_urls_ahead_fills_the_memory_cache_and_does_not_duplicate() = runTest {
        val cache = StreamUrlCache()
        val track = TrackEntity(
            id = 5L,
            title = "Next Up",
            artist = "Artist",
            durationMs = 200_000L,
            isDownloaded = false,
            isStreamable = true,
        )
        coEvery { trackDao.getById(5L) } returns track
        val addonUrl = StreamUrl(
            url = "https://cdn.test/file.flac",
            expiresAtMs = Long.MAX_VALUE,
            codec = "flac",
            origin = "addon:Cloud",
        )
        coEvery {
            streamResolver.resolve(track, allowYouTube = true, allowYtDlp = true, speculative = true)
        } returns addonUrl
        val firstChunk: EclipseFirstChunkPrefetcher = mockk(relaxed = true)
        val orchestrator = PrefetchOrchestrator(
            streamResolver = streamResolver,
            streamUrlCache = cache,
            trackDao = trackDao,
            firstChunk = firstChunk,
        )

        orchestrator.prefetchUrlsAhead(this, listOf(5L))
        advanceUntilIdle()

        assertThat(cache.get(5L)).isEqualTo(addonUrl)
        coVerify(exactly = 1) { firstChunk.warm(5L, addonUrl) }

        // A second call sees the memory hit and does not resolve again.
        orchestrator.prefetchUrlsAhead(this, listOf(5L))
        advanceUntilIdle()
        coVerify(exactly = 1) {
            streamResolver.resolve(track, allowYouTube = true, allowYtDlp = true, speculative = true)
        }
    }

    @Test
    fun prefetch_caches_an_addon_resolved_next_up() = runTest {        every { streamUrlCache.get(5L) } returns null
        val track = TrackEntity(
            id = 5L,
            title = "Next Up",
            artist = "Artist",
            album = "Album",
            durationMs = 200_000L,
            youtubeId = "abc123",
            isDownloaded = false,
            isStreamable = true,
        )
        val addonUrl = StreamUrl(
            url = "https://cdn.test/1.flac",
            expiresAtMs = Long.MAX_VALUE,
            codec = "flac",
            origin = "addon:Cloud",
        )
        coEvery { trackDao.getById(5L) } returns track
        coEvery {
            streamResolver.resolve(track, allowYouTube = true, allowYtDlp = true, speculative = true)
        } returns addonUrl

        orchestrator().onPlaybackProgress(
            scope = this,
            nextTrackId = 5L,
            positionMs = 70_000L,
            durationMs = 100_000L,
        )
        advanceUntilIdle()

        io.mockk.verify(exactly = 1) { streamUrlCache.put(5L, addonUrl) }
    }

    /**
     * Spotify-synced, never-downloaded rows sit at isStreamable=false with
     * isStreamableCheckedAt=null ("not checked yet" — the legacy
     * AvailabilityCheckWorker that set the flag is gone). Treating that as
     * "not streamable" silently killed next-track prefetch for the entire
     * synced library. Only a CHECKED-false row may be skipped.
     */
    @Test
    fun prefetch_attempts_resolve_when_streamability_never_checked() = runTest {
        coEvery { streamingPreference.current() } returns true
        every { streamUrlCache.get(6L) } returns null
        val track = TrackEntity(
            id = 6L,
            title = "Synced Never Checked",
            artist = "Artist",
            album = "Album",
            durationMs = 200_000L,
            isDownloaded = false,
            isStreamable = false,
            isStreamableCheckedAt = null,
        )
        coEvery { trackDao.getById(6L) } returns track
        coEvery { streamResolver.resolve(track, speculative = true) } returns null

        orchestrator().onPlaybackProgress(
            scope = this,
            nextTrackId = 6L,
            positionMs = 70_000L,
            durationMs = 100_000L,
        )
        advanceUntilIdle()

        coVerify(exactly = 1) { streamResolver.resolve(track, speculative = true) }
    }

    @Test
    fun prefetch_skips_track_confirmed_unstreamable() = runTest {
        coEvery { streamingPreference.current() } returns true
        every { streamUrlCache.get(7L) } returns null
        val track = TrackEntity(
            id = 7L,
            title = "Checked And Unstreamable",
            artist = "Artist",
            album = "Album",
            durationMs = 200_000L,
            isDownloaded = false,
            isStreamable = false,
            isStreamableCheckedAt = 1_700_000_000_000L,
        )
        coEvery { trackDao.getById(7L) } returns track

        orchestrator().onPlaybackProgress(
            scope = this,
            nextTrackId = 7L,
            positionMs = 70_000L,
            durationMs = 100_000L,
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { streamResolver.resolve(any(), any(), any(), any()) }
        coVerify(exactly = 0) { streamResolver.resolve(any(), any(), any(), any()) }
    }
}
