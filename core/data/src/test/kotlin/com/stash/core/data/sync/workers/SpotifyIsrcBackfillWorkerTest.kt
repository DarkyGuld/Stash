package com.stash.core.data.sync.workers

import android.content.Context
import androidx.work.WorkerParameters
import com.stash.core.data.addons.SpotifyIsrcGate
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.spotify.SpotifyApiClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyIsrcBackfillWorkerTest {
    private val dao: TrackDao = mockk(relaxed = true)
    private val spotify: SpotifyApiClient = mockk()
    private val gate: SpotifyIsrcGate = mockk(relaxed = true) {
        every { backfillAllowed() } returns true
    }

    private fun worker() = SpotifyIsrcBackfillWorker(
        mockk<Context>(relaxed = true),
        mockk<WorkerParameters>(relaxed = true),
        dao,
        spotify,
        gate,
    )

    @Test
    fun `rows are looked up in 50-id batches, not one request per track`() = runTest {
        val rows = (1..200).map {
            TrackEntity(id = it.toLong(), title = "T$it", artist = "A", spotifyUri = "spotify:track:id$it")
        }
        coEvery { dao.tracksMissingIsrcInPlaylists(500) } returns emptyList()
        coEvery { dao.tracksMissingIsrc(500) } returns rows
        coEvery { spotify.getTracksIsrc(any()) } answers {
            val ids = firstArg<List<String>>()
            SpotifyApiClient.IsrcBatch.Success(ids.associateWith { "ISRC-$it" })
        }

        val result = worker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        coVerify(exactly = 4) { spotify.getTracksIsrc(any()) }
        coVerify(exactly = 0) { spotify.getTrackIsrc(any()) }
        coVerify(exactly = 200) { dao.backfillIsrcIfMissing(any(), any()) }
    }

    @Test
    fun `the songs in a playlist are looked up first`() = runTest {
        val inPlaylist = (1..50).map {
            TrackEntity(id = it.toLong(), title = "P$it", artist = "A", spotifyUri = "spotify:track:pid$it")
        }
        val elsewhere = (51..100).map {
            TrackEntity(id = it.toLong(), title = "E$it", artist = "A", spotifyUri = "spotify:track:eid$it")
        }
        coEvery { dao.tracksMissingIsrcInPlaylists(500) } returns inPlaylist
        coEvery { dao.tracksMissingIsrc(500) } returns elsewhere
        coEvery { spotify.getTracksIsrc(any()) } answers {
            val ids = firstArg<List<String>>()
            SpotifyApiClient.IsrcBatch.Success(ids.associateWith { "ISRC-$it" })
        }

        worker().doWork()

        // The playlist's songs, and only those: the rest wait for the next run.
        coVerify(exactly = 1) { spotify.getTracksIsrc(any()) }
        coVerify(exactly = 50) { dao.backfillIsrcIfMissing(any(), any()) }
        coVerify(exactly = 0) { dao.tracksMissingIsrc(500) }
    }

    @Test
    fun `a 429 stops the run and persists the backoff`() = runTest {
        val rows = (1..100).map {
            TrackEntity(id = it.toLong(), title = "T$it", artist = "A", spotifyUri = "spotify:track:id$it")
        }
        coEvery { dao.tracksMissingIsrcInPlaylists(500) } returns emptyList()
        coEvery { dao.tracksMissingIsrc(500) } returns rows
        coEvery { spotify.getTracksIsrc(any()) } returns SpotifyApiClient.IsrcBatch.RateLimited(3_600)

        worker().doWork()

        coVerify(exactly = 1) { spotify.getTracksIsrc(any()) }
        coVerify(exactly = 1) { gate.setBackoff(3_600) }
        coVerify(exactly = 0) { dao.backfillIsrcIfMissing(any(), any()) }
    }

    @Test
    fun `playback or a foreground lookup pauses the backfill entirely`() = runTest {
        every { gate.backfillAllowed() } returns false
        coEvery { dao.tracksMissingIsrcInPlaylists(500) } returns listOf(
            TrackEntity(id = 1, title = "A", artist = "X", spotifyUri = "spotify:track:aaa"),
        )

        worker().doWork()

        coVerify(exactly = 0) { spotify.getTracksIsrc(any()) }
        coVerify(exactly = 0) { dao.backfillIsrcIfMissing(any(), any()) }
    }

    @Test
    fun `an empty backlog does no network work`() = runTest {
        coEvery { dao.tracksMissingIsrcInPlaylists(500) } returns emptyList()
        coEvery { dao.tracksMissingIsrc(500) } returns emptyList()

        worker().doWork()

        coVerify(exactly = 0) { spotify.getTracksIsrc(any()) }
    }
}
