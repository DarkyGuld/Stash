package com.stash.core.data.addons

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.model.Track
import com.stash.data.spotify.SpotifyApiClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ISRCs for the queue, in one Spotify call.
 *
 * The phone's Spotify session has its own quota, so this is one call for up
 * to fifty songs - never one call per song.
 */
class SpotifyIsrcQueuePrefetcherTest {
    private val dao: TrackDao = mockk(relaxed = true)
    private val spotify: SpotifyApiClient = mockk()
    private val gate: SpotifyIsrcGate = mockk(relaxed = true) {
        every { backoffUntilMs() } returns 0L
    }

    private fun prefetcher() = SpotifyIsrcQueuePrefetcher(spotify, dao, gate)

    private fun track(id: Long, spotifyId: String?, isrc: String? = null) = Track(
        id = id,
        title = "T$id",
        artist = "A",
        spotifyUri = spotifyId?.let { "spotify:track:$it" },
        isrc = isrc,
    )

    @Test
    fun `fifty songs go in one call`() = runTest {
        val tracks = (1..80).map { track(it.toLong(), "id$it") }
        coEvery { spotify.getTracksIsrc(any()) } answers {
            val ids = firstArg<List<String>>()
            SpotifyApiClient.IsrcBatch.Success(ids.associateWith { "ISRC-$it" })
        }

        prefetcher().prefetch(tracks)

        coVerify(exactly = 1) { spotify.getTracksIsrc(any()) }
        coVerify(exactly = 50) { dao.backfillIsrcIfMissing(any(), any()) }
    }

    @Test
    fun `songs that already have an ISRC are left alone`() = runTest {
        val tracks = listOf(
            track(1, "id1", isrc = "USAAA0000001"),
            track(2, "id2"),
        )
        coEvery { spotify.getTracksIsrc(any()) } answers {
            val ids = firstArg<List<String>>()
            SpotifyApiClient.IsrcBatch.Success(ids.associateWith { "ISRC-$it" })
        }

        prefetcher().prefetch(tracks)

        // Only the song without an ISRC is asked about.
        coVerify(exactly = 1) { spotify.getTracksIsrc(listOf("id2")) }
        coVerify(exactly = 1) { dao.backfillIsrcIfMissing(any(), any()) }
        coVerify(exactly = 0) { dao.backfillIsrcIfMissing(1L, any()) }
    }

    @Test
    fun `songs without a Spotify id are not asked about`() = runTest {
        prefetcher().prefetch(listOf(track(1, null), track(2, "")))

        coVerify(exactly = 0) { spotify.getTracksIsrc(any()) }
    }

    @Test
    fun `a 429 stops the prefetch and persists the backoff`() = runTest {
        coEvery { spotify.getTracksIsrc(any()) } returns SpotifyApiClient.IsrcBatch.RateLimited(600)

        prefetcher().prefetch(listOf(track(1, "id1")))

        coVerify(exactly = 1) { gate.setBackoff(600) }
        coVerify(exactly = 0) { dao.backfillIsrcIfMissing(any(), any()) }
    }

    @Test
    fun `a backoff means no call at all`() = runTest {
        every { gate.backoffUntilMs() } returns System.currentTimeMillis() + 600_000

        prefetcher().prefetch(listOf(track(1, "id1")))

        coVerify(exactly = 0) { spotify.getTracksIsrc(any()) }
        coVerify(exactly = 0) { dao.backfillIsrcIfMissing(any(), any()) }
    }

    @Test
    fun `an empty queue does nothing`() = runTest {
        prefetcher().prefetch(emptyList())

        coVerify(exactly = 0) { spotify.getTracksIsrc(any()) }
        assertEquals(0, 0)
    }
}
