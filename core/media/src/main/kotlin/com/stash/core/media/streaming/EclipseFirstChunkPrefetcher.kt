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

    private companion object {
        const val TAG = "EclipseAddonStream"
        const val FIRST_CHUNK_BYTES = 2L * 1024 * 1024

        /** Wi-Fi next-track warm: enough for a whole song, never a playlist. */
        const val FULL_FILE_CAP_BYTES = 60L * 1024 * 1024
        const val BUFFER_BYTES = 64 * 1024
    }
}
