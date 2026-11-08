package com.stash.core.media.streaming

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.stash.core.data.db.dao.TrackDao
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

/**
 * Composed [MediaSource.Factory] for online-streaming playback. Builds the
 * full read pipeline ExoPlayer consumes when sourcing bytes for a streamed
 * track:
 *
 * ```
 * CacheDataSource           <- writes hits into the shared [SimpleCache]
 *  └─ RefreshingDataSource  <- catches 403/410, re-resolves via Kennyy
 *      └─ DefaultHttpDataSource  <- actual HTTP GET against the signed CDN URL
 * ```
 *
 * The cache layer is on top so a partial download survives a 403 refresh
 * mid-stream — bytes already cached before the URL went stale aren't re-
 * fetched, and the refresh only kicks in for the still-missing tail.
 *
 * **Per-track factory via singleton + method.** The static deps
 * ([streamCache], [resolver], [urlCache], [trackDao]) are Hilt-injected
 * application-scope singletons; the only per-playback variable is
 * [create]'s `trackId`. Each call to [create] returns a fresh
 * [MediaSource.Factory] that closes over that id so Media3 can spawn
 * multiple [androidx.media3.datasource.DataSource] instances for the
 * same track and they'll all share the refresh policy.
 *
 * **CacheDataSource flags:**
 * - [CacheDataSource.FLAG_BLOCK_ON_CACHE]: if two readers ask for the same
 *   byte range concurrently, the second blocks until the first writes —
 *   avoids duplicate HTTP fetches for the same range.
 * - [CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR]: if cache I/O throws,
 *   fall through to the network rather than surfacing the cache error as
 *   a playback failure.
 *
 * **User-Agent** uses the same hard-coded "Stash/x.y.z" pattern as
 * [com.stash.core.media.preview.di.PreviewCacheModule] — the `core/media`
 * module doesn't generate a [BuildConfig] with `VERSION_NAME`, and adding
 * one just for this header would be scope creep beyond Task 7. Bump in
 * lock-step with `app/build.gradle.kts versionName` when releasing.
 *
 * Consumed by Task 11 ([com.stash.core.media.PlayerRepository]) and
 * Task 12 ([StashPlaybackService]) — both inject this singleton and call
 * [create] inside their per-track [androidx.media3.exoplayer.source.MediaSource]
 * construction.
 */
@OptIn(UnstableApi::class)
@Singleton
class StreamingMediaSourceFactory @Inject constructor(
    @StreamCache private val streamCache: SimpleCache,
    private val resolver: StreamSourceRegistry,
    private val urlCache: StreamUrlCache,
    private val trackDao: TrackDao,
    /**
     * The app-wide client, for addon HLS only: its connection pool is the one
     * [com.stash.core.data.addons.EclipseAddonClient] already warmed for the
     * `/applemusic/stream` answer, so the m3u8 and its segments reuse that
     * DNS/TCP/TLS connection instead of opening a fresh one each.
     */
    okHttpClient: okhttp3.OkHttpClient,
) {
    /**
     * False in Download mode, read when each source opens ([DownloadModeGate]): the playback service sets it once. Every
     * source these factories make is gated, cached or not, DASH segments included.
     */
    @Volatile
    var streamAllowed: () -> Boolean = { true }

    /**
     * HLS transport: the shared client's pool with the old DefaultHttpDataSource
     * timeouts, and no cross-protocol redirects (OkHttp follows same-protocol
     * ones, `followSslRedirects = false` blocks https↔http). No headers are
     * added here; the shared client carries no Eclipse Authorization interceptor.
     */
    private val TAG = "EclipseAddonStream"

    private val hlsHttpClient = okHttpClient.newBuilder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()

    fun create(trackId: Long): MediaSource.Factory {
        val cachedFactory = CacheDataSource.Factory()
            .setCache(streamCache)
            .setUpstreamDataSourceFactory(createUncachedDataSourceFactory(trackId))
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory().setCache(streamCache),
            )
            .setFlags(
                CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or
                    CacheDataSource.FLAG_BLOCK_ON_CACHE,
            )
        // Outside the cache: a cached stream doesn't play in Download mode either.
        return DefaultMediaSourceFactory(DownloadModeGate.gate(cachedFactory, { streamAllowed() }))
    }

    /**
     * A [DashMediaSource] for a Tidal MPD from the user's BitChord server
     * ([TidalStreamResolver], [StreamUrl.isDash]) at [mpdUrl], keeping
     * [mediaItem]'s id and metadata.
     *
     * Plain HTTP underneath — no [RefreshingDataSource] (that re-resolves a single
     * progressive file at a byte offset; a DASH period re-fetches its own MPD and
     * segments) and no [streamCache] (its keys are per-URI, and each segment URL
     * is a one-off Tidal CDN signature). Redirects are followed: Tidal's CDN uses
     * them, and every URL here came from the MPD the user's own server served.
     *
     * Nothing else is needed client-side: the server rewrites the MPD's
     * `initialization` to its own signed `/dash/td/<id>/init.mp4`, which patches
     * the fLaC box's zero sample rate that made FragmentedMp4Extractor fail.
     */
    fun createDashMediaSource(mediaItem: MediaItem, mpdUrl: String): MediaSource {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Stash/0.9.26")
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(false)
        val item = mediaItem.buildUpon()
            .setUri(mpdUrl)
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .build()
        return DashMediaSource.Factory(DownloadModeGate.gate(httpFactory, { streamAllowed() })).createMediaSource(item)
    }

    /**
     * A progressive addon file (Cloud's direct Qobuz FLAC) over the shared
     * [SimpleCache]: keyed by track/addon/quality/path (signed query ignored), so
     * the player and the first-chunk prefetcher read and write the same entry.
     * No `FLAG_BLOCK_ON_CACHE`: the player reads cached spans and fetches the
     * rest, even while a prefetch is still filling the tail.
     */
    fun createAddonProgressive(
        mediaItem: MediaItem,
        stream: StreamUrl,
        trackId: Long?,
    ): MediaSource {
        val cacheKey = addonCacheKey(trackId, stream, stream.url)
        val hit = streamCache.isCached(cacheKey, 0, 1)
        Log.d(
            TAG,
            "addon progressive track=${trackId ?: 0} ${if (hit) "cache hit" else "cache miss"} key=$cacheKey",
        )
        val cacheFactory = CacheDataSource.Factory()
            .setCache(streamCache)
            // Without an explicit write sink CacheDataSource only reads the cache.
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(streamCache))
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(hlsHttpClient))
            .setCacheKeyFactory(EclipseAddonCacheKeyFactory(trackId, stream))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val item = mediaItem.buildUpon().setUri(stream.url).build()
        return DefaultMediaSourceFactory(DownloadModeGate.gate(cacheFactory, { streamAllowed() }))
            .createMediaSource(item)
    }

    /** Drops every cached manifest/segment of [trackId] (401/403/410 invalidation). */
    private fun evictAddonCache(trackId: Long) {
        val prefix = "eclipse|$trackId|"
        runCatching {
            streamCache.keys.filter { it.startsWith(prefix) }.forEach(streamCache::removeResource)
        }.onFailure { Log.d(TAG, "cache eviction failed for track $trackId: ${it::class.java.simpleName}") }
    }

    fun createHlsMediaSource(mediaItem: MediaItem, stream: StreamUrl): MediaSource {
        val current = java.util.concurrent.atomic.AtomicReference(stream.url)
        val trackId = mediaItem.mediaId.toLongOrNull()
        val cacheKey = addonCacheKey(trackId, stream, stream.url)
        Log.d(
            TAG,
            "addon hls track=${trackId ?: 0} ${if (streamCache.isCached(cacheKey, 0, 1)) "cache hit" else "cache miss"} key=$cacheKey",
        )
        // Manifest and segments share the cache, keyed by their own paths (signed query ignored).
        val cachedHttp = CacheDataSource.Factory()
            .setCache(streamCache)
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(streamCache))
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(hlsHttpClient))
            .setCacheKeyFactory(EclipseAddonCacheKeyFactory(trackId, stream))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val source = DataSource.Factory {
            HlsManifestDataSource(cachedHttp.createDataSource(), stream.url, current, { streamAllowed() },
                refresh = {
                    if (trackId == null) null else kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                        kotlinx.coroutines.withTimeoutOrNull(30_000) {
                            trackDao.getById(trackId)?.let { resolver.resolve(it) }
                                ?.takeIf {
                                    it.isHls &&
                                        (it.origin == "soundcloud" ||
                                            it.origin == EclipseAddonStreamResolver.ORIGIN ||
                                            it.origin?.startsWith("${EclipseAddonStreamResolver.ORIGIN}:") == true) &&
                                        it.codec == stream.codec && it.isPreview == stream.isPreview
                                }
                                ?.also { urlCache.put(trackId, it) }?.url
                        }
                    }
                },
                invalidate = {
                    trackId?.let(urlCache::invalidate)
                    // Stale manifest/segments must not be served again.
                    trackId?.let(::evictAddonCache)
                },
            )
        }
        val item = mediaItem.buildUpon().setUri(stream.url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        return HlsMediaSource.Factory(DownloadModeGate.gate(source, { streamAllowed() })).createMediaSource(item)
    }

    /**
     * The 403-refreshing HTTP pipeline under [create], without the cache. The
     * cast media server reads through this: a speaker opens a new range
     * request on every seek while its old connection may still be draining,
     * and [CacheDataSource.FLAG_BLOCK_ON_CACHE] would make the new request
     * wait on the old one's span lock (cast spec 2026-10-06 §4).
     */
    fun createUncachedDataSourceFactory(trackId: Long): DataSource.Factory {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Stash/0.9.26")
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(30_000)
        return DownloadModeGate.gate(
            RefreshingDataSourceFactory(
                innerFactory = httpFactory,
                resolver = resolver,
                cache = urlCache,
                trackDao = trackDao,
                trackId = trackId,
            ),
            { streamAllowed() },
        )
    }
}
