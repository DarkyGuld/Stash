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
import kotlinx.coroutines.delay
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
        coVerify(exactly = 1) { firstChunk.warm(5L, addonUrl, any()) }

        // A second call sees the memory hit and does not resolve again.
        orchestrator.prefetchUrlsAhead(this, listOf(5L))
        advanceUntilIdle()
        coVerify(exactly = 1) {
            streamResolver.resolve(track, allowYouTube = true, allowYtDlp = true, speculative = true)
        }
    }

    @Test
    fun warm_search_results_warms_apple_for_top_three_and_urls_for_top_two() = runTest {
        val dao = mockk<TrackDao>()
        coEvery { dao.getById(any()) } answers {
            val id = firstArg<Long>()
            TrackEntity(id = id, title = "Song $id", artist = "Artist", durationMs = 200_000, isStreamable = true)
        }
        val url = StreamUrl(
            url = "https://cdn.test/file.flac",
            expiresAtMs = Long.MAX_VALUE,
            codec = "flac",
            origin = "addon:Cloud",
        )
        coEvery {
            streamResolver.resolve(any(), allowYouTube = true, allowYtDlp = true, speculative = true)
        } returns url
        val apple: EclipseAddonStreamResolver = mockk(relaxed = true)
        val orchestrator = PrefetchOrchestrator(
            streamResolver = streamResolver,
            streamUrlCache = StreamUrlCache(),
            trackDao = dao,
            eclipseStreamResolver = apple,
        )

        orchestrator.warmSearchResults(this, listOf(1, 2, 3, 4))
        advanceUntilIdle()

        coVerify(exactly = 1) { apple.warmAppleIds(match { ids -> ids.map { it.id } == listOf(1L, 2L, 3L) }) }
        // Only the top two get their URL resolved.
        coVerify(exactly = 2) {
            streamResolver.resolve(any(), allowYouTube = true, allowYtDlp = true, speculative = true)
        }
    }

    @Test
    fun warm_search_results_replaces_the_previous_query_work() = runTest {
        val dao = mockk<TrackDao>()
        coEvery { dao.getById(any()) } answers {
            val id = firstArg<Long>()
            TrackEntity(id = id, title = "Song $id", artist = "Artist", durationMs = 200_000, isStreamable = true)
        }
        val slow = StreamUrl(url = "https://cdn.test/slow.flac", expiresAtMs = Long.MAX_VALUE, origin = "addon:Cloud")
        val fast = StreamUrl(url = "https://cdn.test/fast.flac", expiresAtMs = Long.MAX_VALUE, origin = "addon:Cloud")
        coEvery {
            streamResolver.resolve(match { it.id == 1L }, allowYouTube = true, allowYtDlp = true, speculative = true)
        } coAnswers {
            delay(10_000)
            slow
        }
        coEvery {
            streamResolver.resolve(match { it.id == 2L }, allowYouTube = true, allowYtDlp = true, speculative = true)
        } returns fast
        val cache = StreamUrlCache()
        val orchestrator = PrefetchOrchestrator(
            streamResolver = streamResolver,
            streamUrlCache = cache,
            trackDao = dao,
        )

        orchestrator.warmSearchResults(this, listOf(1))
        orchestrator.warmSearchResults(this, listOf(2))
        advanceUntilIdle()

        assertThat(cache.get(1L)).isNull()
        assertThat(cache.get(2L)).isEqualTo(fast)
    }

    @Test
    fun warm_search_results_respects_data_saver_for_the_url_warm() = runTest {
        val dao = mockk<TrackDao>()
        coEvery { dao.getById(any()) } answers {
            val id = firstArg<Long>()
            TrackEntity(id = id, title = "Song $id", artist = "Artist", durationMs = 200_000, isStreamable = true)
        }
        val policy: StreamQualityPolicy = mockk { coEvery { dataSaverApplies() } returns true }
        val apple: EclipseAddonStreamResolver = mockk(relaxed = true)
        val orchestrator = PrefetchOrchestrator(
            streamResolver = streamResolver,
            streamUrlCache = StreamUrlCache(),
            trackDao = dao,
            qualityPolicy = policy,
            eclipseStreamResolver = apple,
        )

        orchestrator.warmSearchResults(this, listOf(1, 2))
        advanceUntilIdle()

        coVerify(exactly = 0) {
            streamResolver.resolve(any(), allowYouTube = true, allowYtDlp = true, speculative = true)
        }
        // The Apple-id search itself is metadata-only and still runs.
        coVerify(exactly = 1) { apple.warmAppleIds(any()) }
    }

    @Test
    fun next_track_full_file_warm_follows_the_network() = runTest {
        val track = TrackEntity(
            id = 5L,
            title = "Next Up",
            artist = "Artist",
            durationMs = 200_000L,
            isStreamable = true,
        )
        coEvery { trackDao.getById(5L) } returns track
        val url = StreamUrl(
            url = "https://cdn.test/file.flac",
            expiresAtMs = Long.MAX_VALUE,
            codec = "flac",
            origin = "addon:Cloud",
        )
        coEvery {
            streamResolver.resolve(track, allowYouTube = true, allowYtDlp = true, speculative = true)
        } returns url

        val unmeteredPolicy: StreamQualityPolicy = mockk {
            coEvery { dataSaverApplies() } returns false
            coEvery { isMeteredNetwork() } returns false
        }
        val wholeFileOn: StreamingPreference = mockk { coEvery { isPrefetchWholeFile() } returns true }
        val fullWarmer: EclipseFirstChunkPrefetcher = mockk(relaxed = true)
        PrefetchOrchestrator(
            streamResolver, StreamUrlCache(), trackDao, unmeteredPolicy,
            firstChunk = fullWarmer, streamingPreference = wholeFileOn,
        ).prefetchUrlsAhead(this, listOf(5L))
        advanceUntilIdle()
        coVerify(exactly = 1) { fullWarmer.warm(5L, url, full = true) }

        // Default off: even unmetered, only the first chunk.
        val wholeFileOff: StreamingPreference = mockk { coEvery { isPrefetchWholeFile() } returns false }
        val chunkOnWifi: EclipseFirstChunkPrefetcher = mockk(relaxed = true)
        PrefetchOrchestrator(
            streamResolver, StreamUrlCache(), trackDao, unmeteredPolicy,
            firstChunk = chunkOnWifi, streamingPreference = wholeFileOff,
        ).prefetchUrlsAhead(this, listOf(5L))
        advanceUntilIdle()
        coVerify(exactly = 1) { chunkOnWifi.warm(5L, url, full = false) }

        val meteredPolicy: StreamQualityPolicy = mockk {
            coEvery { dataSaverApplies() } returns false
            coEvery { isMeteredNetwork() } returns true
        }
        val chunkWarmer: EclipseFirstChunkPrefetcher = mockk(relaxed = true)
        PrefetchOrchestrator(streamResolver, StreamUrlCache(), trackDao, meteredPolicy, firstChunk = chunkWarmer)
            .prefetchUrlsAhead(this, listOf(5L))
        advanceUntilIdle()
        coVerify(exactly = 1) { chunkWarmer.warm(5L, url, full = false) }
    }

    @Test
    fun list_warmup_does_not_cancel_the_queue_next_track_warm() = runTest {
        val dao = mockk<TrackDao>()
        val queueTrack = TrackEntity(id = 5L, title = "Next", artist = "A", durationMs = 200_000, isStreamable = true)
        val searchTrack = TrackEntity(id = 6L, title = "Search", artist = "B", durationMs = 200_000, isStreamable = true)
        coEvery { dao.getById(5L) } returns queueTrack
        coEvery { dao.getById(6L) } returns searchTrack
        val url5 = StreamUrl(url = "https://cdn.test/5.flac", expiresAtMs = Long.MAX_VALUE, origin = "addon:Cloud")
        val url6 = StreamUrl(url = "https://cdn.test/6.flac", expiresAtMs = Long.MAX_VALUE, origin = "addon:Cloud")
        coEvery {
            streamResolver.resolve(queueTrack, allowYouTube = true, allowYtDlp = true, speculative = true)
        } coAnswers {
            delay(1_000)
            url5
        }
        coEvery {
            streamResolver.resolve(searchTrack, allowYouTube = true, allowYtDlp = true, speculative = true)
        } returns url6
        val cache = StreamUrlCache()
        val orchestrator = PrefetchOrchestrator(streamResolver, cache, dao)

        orchestrator.prefetchUrlsAhead(this, listOf(5L))
        // Opportunistic warmup while the queue warm is in flight: it must not cancel it.
        orchestrator.warmVisibleRows(this, listOf(6L))
        advanceUntilIdle()

        assertThat(cache.get(5L)).isEqualTo(url5)
        assertThat(cache.get(6L)).isNull()
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
    fun resetSession_retains_the_new_window_instead_of_unpinning_everything() = runTest {
        val policy = StreamCachePolicy()
        val orchestrator = PrefetchOrchestrator(
            streamResolver = streamResolver,
            streamUrlCache = streamUrlCache,
            trackDao = trackDao,
            cachePolicy = policy,
        )
        policy.pinTrack(99L)

        orchestrator.resetSession(listOf(10L, 11L, 12L))

        assertThat(policy.isProtected("eclipse|10|addon:Cloud|flac|/a.flac")).isTrue()
        assertThat(policy.isProtected("eclipse|12|addon:Cloud|flac|/c.flac")).isTrue()
        // The old queue position is no longer part of the retained window.
        assertThat(policy.isProtected("eclipse|99|addon:Cloud|flac|/old.flac")).isFalse()
    }

    @Test
    fun prefetch_pins_the_track_prefix_for_an_hls_next_track() = runTest {
        val policy = StreamCachePolicy()
        val track = TrackEntity(
            id = 5L,
            title = "Next Up",
            artist = "Artist",
            durationMs = 200_000L,
            isStreamable = true,
        )
        val hls = StreamUrl(
            url = "https://cdn.test/playlist.m3u8",
            expiresAtMs = Long.MAX_VALUE,
            codec = "flac",
            origin = "addon:Cloud",
            isHls = true,
        )
        coEvery { trackDao.getById(5L) } returns track
        coEvery {
            streamResolver.resolve(track, allowYouTube = true, allowYtDlp = true, speculative = true)
        } returns hls
        val orchestrator = PrefetchOrchestrator(
            streamResolver = streamResolver,
            streamUrlCache = StreamUrlCache(),
            trackDao = trackDao,
            cachePolicy = policy,
        )

        orchestrator.prefetchUrlsAhead(this, listOf(5L))
        advanceUntilIdle()

        assertThat(policy.isProtected(addonCacheKey(5L, hls, hls.url))).isTrue()
        // HLS segments use their own keys: the whole prefix must be protected.
        assertThat(policy.isProtected("eclipse|5|addon:Cloud|flac|/segment-1.ts")).isTrue()
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
