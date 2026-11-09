package com.stash.core.media.streaming

import androidx.media3.exoplayer.source.MediaSource
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [StreamingMediaSourceFactory] — the composed `DataSource.Factory`
 * (cache wraps refresh wraps http) that ExoPlayer consumes for streamed
 * playback.
 *
 * Verification surface is intentionally narrow: most behaviour
 * (cache hit/miss, 403 refresh, byte-offset preservation) is exercised in
 * the lower-level [RefreshingDataSourceFactoryTest] and the Task 23 manual
 * validation. Here we only assert that [StreamingMediaSourceFactory.create]
 * runs to completion without NPE-ing in Media3's builders and produces a
 * distinct [MediaSource.Factory] instance per call (i.e. the `trackId` arg
 * is captured per-invocation, not memoised).
 *
 * Uses [RobolectricTestRunner] because `CacheDataSource.Factory` /
 * `DefaultHttpDataSource.Factory` construction touches Android framework
 * stubs (e.g. `android.net.Uri` defaults) that throw without a Robolectric
 * environment.
 */
@RunWith(RobolectricTestRunner::class)
class StreamingMediaSourceFactoryTest {

    private val streamCache: androidx.media3.datasource.cache.SimpleCache = mockk(relaxed = true)
    private val resolver: StreamSourceRegistry = mockk(relaxed = true)
    private val urlCache: StreamUrlCache = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)

    @Test fun `SoundCloud manifest builds a real HLS source with its identity and MIME type`() {
        val item = androidx.media3.common.MediaItem.Builder().setMediaId("42")
            .setUri("stash-resolve://track/42?sc=1").build()
        val stream = StreamUrl("https://cf-media.sndcdn.com/playlist", Long.MAX_VALUE,
            origin = "soundcloud", isHls = true)
        val source = newFactory().createHlsMediaSource(item, stream)
        assertThat(source).isInstanceOf(androidx.media3.exoplayer.hls.HlsMediaSource::class.java)
        assertThat(source.mediaItem.mediaId).isEqualTo("42")
        assertThat(source.mediaItem.localConfiguration!!.mimeType).isEqualTo(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
    }

    private fun newFactory(
        policy: StreamCachePolicy? = null,
    ): StreamingMediaSourceFactory = StreamingMediaSourceFactory(
        streamCache = streamCache,
        resolver = resolver,
        urlCache = urlCache,
        trackDao = trackDao,
        okHttpClient = okhttp3.OkHttpClient(),
        cachePolicy = policy,
    )

    @Test
    fun `creating an HLS source pins the track prefix before playback starts`() {
        val policy = StreamCachePolicy()
        val item = androidx.media3.common.MediaItem.Builder().setMediaId("42")
            .setUri("stash-resolve://track/42?sc=1").build()
        val stream = StreamUrl("https://cf-media.sndcdn.com/playlist", Long.MAX_VALUE,
            origin = "soundcloud", isHls = true)

        newFactory(policy).createHlsMediaSource(item, stream)

        assertThat(policy.isProtected("eclipse|42|soundcloud||https://cf-media.sndcdn.com/segment-1.ts")).isTrue()
    }

    @Test
    fun `creating a progressive addon source pins the track prefix`() {
        val policy = StreamCachePolicy()
        val item = androidx.media3.common.MediaItem.Builder().setMediaId("7")
            .setUri("https://cdn.test/file.flac").build()
        val stream = StreamUrl(
            url = "https://cdn.test/file.flac",
            expiresAtMs = Long.MAX_VALUE,
            codec = "flac",
            origin = "addon:Cloud",
        )

        newFactory(policy).createAddonProgressive(item, stream, trackId = 7L)

        assertThat(policy.isProtected("eclipse|7|addon:Cloud|flac|/file.flac")).isTrue()
    }

    @Test
    fun create_returnsNonNullMediaSourceFactory() {
        val factory = newFactory()

        val mediaSourceFactory: MediaSource.Factory = factory.create(trackId = 42L)

        assertThat(mediaSourceFactory).isNotNull()
    }

    @Test
    fun create_returnsDistinctFactoryPerCall() {
        // Per-track factories must be independent so two simultaneous
        // playbacks of different tracks don't share refresh state.
        val factory = newFactory()

        val a = factory.create(trackId = 1L)
        val b = factory.create(trackId = 2L)

        assertThat(a).isNotSameInstanceAs(b)
    }
}
