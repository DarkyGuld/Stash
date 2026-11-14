package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.addons.EclipseAddon
import com.stash.core.data.addons.EclipseAddonRepository
import com.stash.core.data.addons.EclipseAddonStream
import com.stash.core.data.addons.EclipseAddonTrack
import com.stash.core.data.db.entity.TrackEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * One track must not push the rest of the queue to another source.
 *
 * The symptom: as soon as ONE song fell back to YouTube, the songs after it
 * were played from YouTube too - even though the add-on had a FLAC for every
 * one of them. Three things held that state:
 *
 *  1. the add-on was cooled down after ONE slow track, and the cooling was
 *     per add-on, not per track: every track behind it was skipped;
 *  2. a "preparing" answer was remembered as a definitive miss, so the track
 *     was left alone for the whole miss window;
 *  3. a YouTube url that the pre-fetch had put in the cache was served
 *     instead of asking the add-on.
 *
 * Each track in a queue is asked on its own, with the full "addons first"
 * priority.
 */
class QueueIsolationTest {

    private fun track(id: Long, title: String) =
        TrackEntity(id = id, title = title, artist = "Artist", album = "Album", durationMs = 200_000)

    private fun addon(settings: Map<String, String> = mapOf("quality" to "max")) = EclipseAddon(
        id = "nullbeat-cache",
        name = "NullBeat Cache",
        version = "1",
        manifestUrl = "https://addons.test/nullbeat/manifest.json",
        resources = listOf("stream", "resolve"),
        settings = settings,
    )

    private fun flac() = EclipseAddonStream(
        url = "https://cdn.test/song.flac",
        format = "flac",
        codec = "flac",
        quality = "HiFi CD",
    )

    @Test
    fun `a slow track does not silence the tracks behind it`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val addon = addon()
        coEvery { repository.enabledNow() } returns listOf(addon)
        // The first track times out. It is that track's problem.
        coEvery { repository.resolve(addon, any(), any(), any(), any()) } throws
            com.stash.core.data.addons.EclipseAddonException.Unreachable(java.io.IOException("timed out"))
        val resolver = EclipseAddonStreamResolver.forTest(repository, EclipseAddonResolveCache())

        assertThat(resolver.resolve(track(1, "Slow"))).isNull()

        // The second track is asked, and the add-on answers.
        coEvery { repository.resolve(addon, any(), any(), any(), any()) } returns
            EclipseAddonTrack(id = "nb:22222222222222222222222222222222:lossless")
        coEvery { repository.stream(addon, "nb:22222222222222222222222222222222:lossless") } returns flac()

        val second = resolver.resolve(track(2, "Next"))

        assertThat(second).isNotNull()
        assertThat(second?.codec).isEqualTo("flac")
        // The add-on was asked for the second track: the first track's timeout
        // did not skip it.
        coVerify(atLeast = 1) { repository.resolve(addon, any(), any(), any(), any()) }
    }

    @Test
    fun `a preparing track does not stop the next one from being asked`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val addon = addon()
        coEvery { repository.enabledNow() } returns listOf(addon)
        // The first track is being installed: the add-on says "preparing".
        coEvery { repository.resolve(addon, any(), any(), any(), any()) } returns
            EclipseAddonTrack(id = "nb:11111111111111111111111111111111:lossless")
        coEvery { repository.stream(addon, "nb:11111111111111111111111111111111:lossless") } throws
            com.stash.core.data.addons.EclipseAddonException.Preparing(1)
        val resolver = EclipseAddonStreamResolver.forTest(repository, EclipseAddonResolveCache())

        // The track is left to another source...
        assertThat(resolver.resolve(track(1, "Preparing"))).isNull()

        // ...and the next track is asked anyway.
        coEvery { repository.resolve(addon, any(), any(), any(), any()) } returns
            EclipseAddonTrack(id = "nb:33333333333333333333333333333333:lossless")
        coEvery { repository.stream(addon, "nb:33333333333333333333333333333333:lossless") } returns flac()

        val second = resolver.resolve(track(2, "After"))

        assertThat(second).isNotNull()
        assertThat(second?.codec).isEqualTo("flac")
    }

    @Test
    fun `a cached fallback does not keep a track away from the add-on`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val addon = addon()
        coEvery { repository.enabledNow() } returns listOf(addon)
        coEvery { repository.resolve(addon, any(), any(), any(), any()) } returns
            EclipseAddonTrack(id = "nb:44444444444444444444444444444444:lossless")
        coEvery { repository.stream(addon, "nb:44444444444444444444444444444444:lossless") } returns flac()
        val urlCache = StreamUrlCache()
        // A YouTube url from a pre-fetch, cached under the track.
        urlCache.put(
            9L,
            StreamUrl(
                url = "https://youtube.test/watch?v=abc",
                expiresAtMs = Long.MAX_VALUE,
                codec = "m4a",
                origin = "youtube",
            ),
        )
        val resolver = EclipseAddonStreamResolver.forTest(
            repository,
            EclipseAddonResolveCache(),
            urlCache = urlCache,
        )

        val result = resolver.resolve(track(9, "Cached"))

        // The add-on's file, not the cached fallback.
        assertThat(result?.codec).isEqualTo("flac")
        assertThat(result?.origin).isNotEqualTo("youtube")
    }
}
