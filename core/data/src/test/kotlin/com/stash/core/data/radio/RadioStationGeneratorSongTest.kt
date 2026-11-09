package com.stash.core.data.radio

import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmSimilarTrack
import com.stash.core.data.lastfm.LastFmTopTrack
import com.stash.data.ytmusic.YTMusicApiClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RadioStationGeneratorSongTest {
    private val lastFm: LastFmApiClient = mockk()
    private val yt: YTMusicApiClient = mockk()
    private fun gen() = RadioStationGenerator(lastFm, yt)

    /** The start diagnostics read this property; strict mocks need an answer. */
    @Before fun stubDiagnostics() {
        every { lastFm.isConfigured } returns true
    }

    @Test fun `song seed blends seed-artist tracks with similar tracks, resolves via search`() = runTest {
        coEvery { lastFm.getSimilarTracks("Lil Wayne", "A Milli", any()) } returns
            Result.success(listOf(
                LastFmSimilarTrack("Drake", "Forever", 0.9f),
                LastFmSimilarTrack("T.I.", "Whatever You Like", 0.7f)))
        coEvery { lastFm.getArtistTopTracks("Lil Wayne", any()) } returns
            Result.success(listOf(
                LastFmTopTrack("Lil Wayne", "6 Foot 7 Foot", 100),
                LastFmTopTrack("Lil Wayne", "Lollipop", 90)))
        // Song-radio candidates carry no Last.fm artwork and resolve via YT Music
        // search — which also yields the song's square album cover. Most resolve
        // WITH a cover; "T.I." resolves without one to exercise the video-frame
        // fallback so blank covers never reach Now Playing/queue/notif.
        coEvery { yt.searchCanonicalMatch(any(), any()) } answers {
            val artist = firstArg<String>()
            com.stash.data.ytmusic.CanonicalMatch(
                // A distinct id per song, in the 11-character shape real ids have:
                // only those are built into a thumbnail URL.
                videoId = "v%010d".format("$artist|${secondArg<String>()}".hashCode().toLong() and 0x7fffffffL),
                thumbnailUrl = if (artist == "T.I.") null
                    else "https://lh3.googleusercontent.com/cover=w1024-h1024",
            )
        }

        val (session, batch) = gen().start(RadioSeed.Song("A Milli", "Lil Wayne"))

        assertTrue(batch.isNotEmpty())
        assertTrue(batch.all { it.youtubeId != null })
        assertTrue("has seed share", batch.any { it.artist == "Lil Wayne" })
        assertTrue("has similar tracks", batch.any { it.artist != "Lil Wayne" })
        assertTrue(session.played.isNotEmpty())
        // Every emitted track has album art, and the resolved square cover is used
        // when the search returned one (crisp, no black bars) rather than the
        // low-res video frame.
        assertTrue("all tracks have album art", batch.all { !it.albumArtUrl.isNullOrBlank() })
        assertTrue("resolved cover used", batch.any { it.albumArtUrl!!.contains("lh3.googleusercontent.com") })
    }

    /**
     * Every song-radio candidate but the seed needs a YouTube search, and the
     * first batch used to run them one after another: about a dozen searches,
     * 5-10 s before anything could play.
     */
    @Test fun `the first batch resolves its candidates concurrently`() = runTest {
        coEvery { lastFm.getSimilarTracks(any(), any(), any()) } returns
            Result.success((1..20).map { LastFmSimilarTrack("Artist $it", "Song $it", 0.5f) })
        coEvery { lastFm.getArtistTopTracks(any(), any()) } returns Result.success(emptyList())
        coEvery { yt.searchCanonicalMatch(any(), any()) } coAnswers {
            kotlinx.coroutines.delay(500) // one search's latency
            com.stash.data.ytmusic.CanonicalMatch(
                videoId = "v%010d".format("${firstArg<String>()}|${secondArg<String>()}".hashCode().toLong() and 0x7fffffffL),
                thumbnailUrl = null,
            )
        }

        val (_, batch) = gen().start(RadioSeed.Song("Seed", "Seed Artist", ytVideoId = "vSeed000001"))

        assertEquals(RadioStationGenerator.FIRST_BATCH, batch.size)
        // 11 searches back to back would take 5.5 s; in parallel a few rounds do.
        assertTrue("took ${testScheduler.currentTime} ms", testScheduler.currentTime <= 2_000)
    }
}
