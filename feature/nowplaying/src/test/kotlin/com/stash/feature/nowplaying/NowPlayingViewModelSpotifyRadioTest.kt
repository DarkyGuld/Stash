package com.stash.feature.nowplaying

import android.content.Context
import app.cash.turbine.test
import com.stash.core.data.lossless.LosslessUpgrader
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.PlayerRepository
import com.stash.core.model.PlaybackSource
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import com.stash.data.lyrics.LyricsRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Spotify song radio: a finite "Inspired by" queue, separate from the radio station. The source is
 * faked here, so no test touches the network; the ViewModel's job is only to prepend the current
 * song, call `setQueue` once, and explain an empty/no-match answer without touching the queue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelSpotifyRadioTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val playerStateFlow = MutableStateFlow(PlayerState())
    private val positionFlow = MutableStateFlow(0L)

    private val playerRepository: PlayerRepository = mockk(relaxed = true) {
        every { playerState } returns playerStateFlow
        every { currentPosition } returns positionFlow
        every { radioSeedLabel } returns MutableStateFlow(null)
    }
    private val musicRepository: MusicRepository = mockk(relaxed = true) {
        every { observeTrackById(any()) } returns flowOf(null)
        every { observeTrackByYoutubeId(any()) } returns flowOf(null)
        every { getUserCreatedPlaylists() } returns flowOf(emptyList())
    }
    private val likeCoordinator: LikeCoordinator = mockk(relaxed = true) {
        every { mirrorFailures } returns MutableSharedFlow()
    }
    private val appContext: Context = mockk(relaxed = true)

    private val current = Track(id = 7L, title = "song", artist = "artist", youtubeId = "vid42")
    private val mix = listOf(
        Track(id = 101L, title = "one", artist = "a"),
        Track(id = 102L, title = "two", artist = "b"),
    )

    private fun vm(source: SpotifyRadioSource): NowPlayingViewModel {
        playerStateFlow.value = playerStateFlow.value.copy(currentTrack = current)
        return NowPlayingViewModel(
            playerRepository = playerRepository,
            musicRepository = musicRepository,
            likeCoordinator = likeCoordinator,
            losslessUpgrader = mockk(relaxed = true),
            lyricsRepository = mockk(relaxed = true),
            lyricsPreference = mockk(relaxed = true),
            nowPlayingPreference = mockk(relaxed = true),
            lyricsSidecarWriter = mockk(relaxed = true),
            appContext = appContext,
            sleepTimerController = mockk(relaxed = true),
            ytMusicApiClient = mockk(relaxed = true),
            spotifyRadioSource = source,
        )
    }

    @Test fun `a ready mix queues the current song first, then the finite mix, with the Spotify label`() =
        runTest(dispatcher) {
            val source = FakeSource(SpotifyRadioMix.Ready(mix))
            val vm = vm(source)
            advanceUntilIdle()

            vm.startSpotifyRadioFromCurrent()
            advanceUntilIdle()

            val tracks = slot<List<Track>>()
            val playbackSource = slot<PlaybackSource>()
            coVerify(exactly = 1) { playerRepository.setQueue(capture(tracks), 0, capture(playbackSource)) }
            assertEquals(listOf(current) + mix, tracks.captured)
            assertEquals(PlaybackSource.Radio("Spotify · song"), playbackSource.captured)
            assertEquals(1, source.calls)
            assertEquals(false, vm.spotifyRadioStarting.value)
        }

    @Test fun `an empty mix changes nothing and says it is unavailable`() = runTest(dispatcher) {
        val vm = vm(FakeSource(SpotifyRadioMix.Unavailable))
        advanceUntilIdle()

        vm.userMessages.test {
            vm.startSpotifyRadioFromCurrent()
            advanceUntilIdle()
            assertEquals("Spotify radio is unavailable for this song", awaitItem())
        }

        coVerify(exactly = 0) { playerRepository.setQueue(any(), any(), any()) }
        assertEquals(false, vm.spotifyRadioStarting.value)
    }

    @Test fun `a song with no Spotify match says that and changes nothing`() = runTest(dispatcher) {
        val vm = vm(FakeSource(SpotifyRadioMix.NoSpotifyMatch))
        advanceUntilIdle()

        vm.userMessages.test {
            vm.startSpotifyRadioFromCurrent()
            advanceUntilIdle()
            assertEquals("Spotify radio needs a Spotify match for this song", awaitItem())
        }

        coVerify(exactly = 0) { playerRepository.setQueue(any(), any(), any()) }
    }

    @Test fun `a double tap starts one lookup`() = runTest(dispatcher) {
        val gate = CompletableDeferred<SpotifyRadioMix>()
        val source = FakeSource(SpotifyRadioMix.Ready(mix), gate = gate)
        val vm = vm(source)
        advanceUntilIdle()

        vm.startSpotifyRadioFromCurrent()
        vm.startSpotifyRadioFromCurrent()
        gate.complete(SpotifyRadioMix.Ready(mix))
        advanceUntilIdle()

        assertEquals(1, source.calls)
        coVerify(exactly = 1) { playerRepository.setQueue(any(), any(), any()) }
    }

    private class FakeSource(
        private val result: SpotifyRadioMix = SpotifyRadioMix.Unavailable,
        private val gate: CompletableDeferred<SpotifyRadioMix>? = null,
    ) : SpotifyRadioSource {
        var calls = 0
            private set

        override suspend fun mix(current: Track, limit: Int): SpotifyRadioMix {
            calls++
            return gate?.await() ?: result
        }
    }
}
