package com.stash.core.media.streaming

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Warms the first ~2 MB of an addon's direct (progressive) file into the shared
 * [SimpleCache], under the same key the player will read ([addonCacheKey]). The
 * next-track start then reads cached bytes instead of paying TLS + metadata +
 * first-byte latency.
 *
 * The cap comes from the [DataSpec] length, so a multi-hundred-MB track is never
 * pulled whole. Cancellation interrupts the write; the player never waits on it
 * (the cache source it uses has no BLOCK_ON_CACHE flag).
 */
@OptIn(UnstableApi::class)
@Singleton
class EclipseFirstChunkPrefetcher @Inject constructor(
    @StreamCache private val cache: SimpleCache,
    okHttpClient: OkHttpClient,
) {
    private val httpClient = okHttpClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()

    /**
     * The client that asks a CDN how long its file is.
     *
     * Redirects are followed here (and not in [httpClient]): the file's length
     * is behind whatever the CDN answers with first.
     */
    private val probeClient = okHttpClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .build()

    /**
     * @param full true warms the whole file up to [FULL_FILE_CAP_BYTES] (Wi-Fi,
     *   next track only); false warms [FIRST_CHUNK_BYTES]. Mobile data keeps the
     *   small chunk.
     */
    suspend fun warm(trackId: Long, stream: StreamUrl, full: Boolean = false) {
        if (stream.isHls || stream.isDash || stream.url.isBlank()) return
        val cap = if (full) FULL_FILE_CAP_BYTES else FIRST_CHUNK_BYTES
        val key = addonCacheKey(trackId, stream, stream.url)
        Log.d(TAG, "warm invoked track=$trackId full=$full cap=$cap key=$key")
        val before = cache.getCachedBytes(key, 0, cap)
        if (before >= cap) {
            Log.d(TAG, "addon file already cached ($before bytes, full=$full)")
            return
        }

        val dataSpec = DataSpec.Builder()
            .setUri(stream.url)
            .setPosition(0)
            .setLength(cap)
            .build()
        val cacheSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(httpClient))
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
            .setCacheKeyFactory(EclipseAddonCacheKeyFactory(trackId, stream))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource() as CacheDataSource
        val writer = CacheWriter(cacheSource, dataSpec, ByteArray(BUFFER_BYTES), null)
        try {
            withContext(Dispatchers.IO) { runInterruptible { writer.cache() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed warm is not a playback error; the player fetches normally.
            Log.d(TAG, "first-chunk warm stopped: ${e::class.java.simpleName}")
        } finally {
            writer.cancel()
            cacheSource.close()
        }
        val cached = cache.getCachedBytes(key, 0, cap)
        Log.d(TAG, "addon ${if (full) "file" else "first chunk"} $cached bytes warmed (${if (cached >= cap) "hit" else "partial"})")
    }

    /**
     * HLS next-track warm: fetch the small `.m3u8` into the same cache and under
     * the same key the player's HlsMediaSource will read. Segments are left to
     * playback (and the player's own cache), never fetched as a playlist.
     */
    suspend fun warmHls(trackId: Long, stream: StreamUrl) {
        if (!stream.isHls || stream.url.isBlank()) return
        val key = addonCacheKey(trackId, stream, stream.url)
        if (cache.isCached(key, 0, 1)) {
            Log.d(TAG, "hls manifest already cached track=$trackId key=$key")
            return
        }
        Log.d(TAG, "hls warm invoked track=$trackId key=$key")
        val dataSpec = DataSpec.Builder()
            .setUri(stream.url)
            .setPosition(0)
            .setLength(-1L) // the manifest is small; read to its end
            .build()
        val cacheSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(httpClient))
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
            .setCacheKeyFactory(EclipseAddonCacheKeyFactory(trackId, stream))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource() as CacheDataSource
        val writer = CacheWriter(cacheSource, dataSpec, ByteArray(BUFFER_BYTES), null)
        try {
            withContext(Dispatchers.IO) { runInterruptible { writer.cache() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "hls manifest warm stopped: ${e::class.java.simpleName}")
        } finally {
            writer.cancel()
            cacheSource.close()
        }
        val cached = cache.getCachedLength(key, 0, Long.MAX_VALUE)
        Log.d(TAG, "hls manifest $cached bytes warmed")
    }

    /**
     * Warms the bytes AROUND the position the player is at, so a swap to this
     * file mid-song reads from the cache instead of the network.
     *
     * The byte offset is an estimate: the file's length (a one-byte range
     * request) times the position's share of the duration. A VBR file's
     * estimate is not exact, so the window is wider than the estimate's
     * error - the player finds its bytes inside it either way.
     *
     * True when the window was written. False when it could not be: a stream
     * that is not a progressive file, a file whose length the server will not
     * say, or a write that failed. The caller then leaves the song alone.
     */
    suspend fun warmAround(
        trackId: Long,
        stream: StreamUrl,
        positionMs: Long,
        durationMs: Long,
    ): Boolean {
        if (stream.isHls || stream.isDash || stream.url.isBlank()) return false
        if (durationMs <= 0) return false
        val length = contentLength(stream.url) ?: return false
        if (length <= 0) return false

        val key = addonCacheKey(trackId, stream, stream.url)
        // The offset the player will read at this position, as far as the
        // file's length and the song's length can tell.
        val estimated = (length * positionMs.coerceAtLeast(0L) / durationMs).coerceIn(0L, length)
        // The window starts before the estimate and reaches past it: the
        // estimate can be off by a few per cent, and the player must not read
        // outside what was warmed.
        val start = (estimated - WINDOW_MARGIN_BYTES).coerceAtLeast(0L)
        val window = (WINDOW_MARGIN_BYTES * 2 + WINDOW_BASE_BYTES).coerceAtMost(length - start)
        if (window <= 0) return false

        Log.d(TAG, "warm around track=$trackId position=${positionMs}ms offset=$estimated window=$window key=$key")
        val dataSpec = DataSpec.Builder()
            .setUri(stream.url)
            .setPosition(start)
            .setLength(window)
            .build()
        val cacheSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(httpClient))
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
            .setCacheKeyFactory(EclipseAddonCacheKeyFactory(trackId, stream))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource() as CacheDataSource
        val writer = CacheWriter(cacheSource, dataSpec, ByteArray(BUFFER_BYTES), null)
        try {
            withContext(Dispatchers.IO) { runInterruptible { writer.cache() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "the warm around the position stopped: ${e::class.java.simpleName}")
            return false
        } finally {
            writer.cancel()
            cacheSource.close()
        }
        val warmed = cache.getCachedBytes(key, start, window)
        Log.d(TAG, "warmed $warmed of $window bytes around ${positionMs}ms for track=$trackId")
        return warmed > 0
    }

    /**
     * The file's length in bytes.
     *
     * A one-byte range request: the answer's `Content-Range` carries the
     * total, and the server sends a single byte instead of the whole file.
     * Null when the server will not say.
     */
    private suspend fun contentLength(url: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .build()
            // Redirects followed: the CDN may answer with one, and the file's
            // length is behind it.
            probeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val range = response.header("Content-Range")
                val total = range?.substringAfterLast('/')?.trim()?.toLongOrNull()
                total ?: response.header("Content-Length")?.trim()?.toLongOrNull()
            }
        }.getOrNull()
    }

    private companion object {
        const val TAG = "EclipseAddonStream"
        const val FIRST_CHUNK_BYTES = 2L * 1024 * 1024

        /** Wi-Fi next-track warm: enough for a whole song, never a playlist. */
        const val FULL_FILE_CAP_BYTES = 60L * 1024 * 1024
        const val BUFFER_BYTES = 64 * 1024

        /** How far before the estimate the window starts, and how far past it reaches. */
        const val WINDOW_MARGIN_BYTES = 3L * 1024 * 1024

        /** The window's own size, on top of the margins. */
        const val WINDOW_BASE_BYTES = 2L * 1024 * 1024
    }
}
