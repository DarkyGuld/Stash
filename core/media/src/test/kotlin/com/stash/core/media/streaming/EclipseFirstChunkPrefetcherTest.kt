package com.stash.core.media.streaming

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EclipseFirstChunkPrefetcherTest {
    private lateinit var context: Context
    private lateinit var dir: File
    private lateinit var cache: SimpleCache
    private lateinit var server: MockWebServer
    private lateinit var prefetcher: EclipseFirstChunkPrefetcher

    private val cap = 2L * 1024 * 1024

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = File(context.cacheDir, "first_chunk_TEST_${System.nanoTime()}")
        cache = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(50L * 1024 * 1024), StandaloneDatabaseProvider(context))
        server = MockWebServer().apply { start() }
        prefetcher = EclipseFirstChunkPrefetcher(cache, OkHttpClient())
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        runCatching { cache.release() }
        dir.deleteRecursively()
    }

    private fun stream(path: String = "/file.flac") = StreamUrl(
        url = server.url("$path?etsp=1762000000&sig=abc").toString(),
        expiresAtMs = Long.MAX_VALUE,
        codec = "flac",
        bitsPerSample = 24,
        sampleRateHz = 44_100,
        origin = "addon:Cloud",
    )

    @Test
    fun `warms only the first two megabytes`() = runTest {
        val body = ByteArray(5 * 1024 * 1024) { 7 }
        server.enqueue(MockResponse().setBody(Buffer().write(body)))

        val stream = stream()
        prefetcher.warm(trackId = 42, stream = stream)

        val key = addonCacheKey(42, stream, stream.url)
        val cached = cache.getCachedBytes(key, 0, cap)
        assertThat(cached).isEqualTo(cap)
        assertThat(cached).isLessThan(body.size.toLong())
    }

    @Test
    fun `cancellation stops the warm promptly`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(ByteArray(5 * 1024 * 1024) { 7 }))
                .setHeadersDelay(10, java.util.concurrent.TimeUnit.SECONDS),
        )
        val job = async { runCatching { prefetcher.warm(7, stream()) } }
        server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)

        val startedNs = System.nanoTime()
        job.cancelAndJoin()
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000

        assertThat(job.isCancelled).isTrue()
        assertThat(elapsedMs).isLessThan(3_000)
    }

    @Test
    fun `a second warm sees the bytes already cached and does not fetch again`() = runTest {
        val body = ByteArray(5 * 1024 * 1024) { 7 }
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        val stream = stream()

        prefetcher.warm(42, stream)
        prefetcher.warm(42, stream)

        assertThat(server.requestCount).isEqualTo(1)
    }
}
