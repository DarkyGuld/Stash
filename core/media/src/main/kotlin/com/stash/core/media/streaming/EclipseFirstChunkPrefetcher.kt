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

    suspend fun warm(trackId: Long, stream: StreamUrl) {
        if (stream.isHls || stream.isDash || stream.url.isBlank()) return
        val key = addonCacheKey(trackId, stream, stream.url)
        val before = cache.getCachedBytes(key, 0, FIRST_CHUNK_BYTES)
        if (before >= FIRST_CHUNK_BYTES) {
            Log.d(TAG, "first chunk already cached ($before bytes)")
            return
        }

        val dataSpec = DataSpec.Builder()
            .setUri(stream.url)
            .setPosition(0)
            .setLength(FIRST_CHUNK_BYTES)
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
        val cached = cache.getCachedBytes(key, 0, FIRST_CHUNK_BYTES)
        Log.d(TAG, "first chunk $cached bytes warmed (${if (cached >= FIRST_CHUNK_BYTES) "hit" else "partial"})")
    }

    private companion object {
        const val TAG = "EclipseAddonStream"
        const val FIRST_CHUNK_BYTES = 2L * 1024 * 1024
        const val BUFFER_BYTES = 64 * 1024
    }
}
