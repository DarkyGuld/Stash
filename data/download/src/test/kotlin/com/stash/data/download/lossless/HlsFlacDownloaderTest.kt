package com.stash.data.download.lossless

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
class HlsFlacDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var destination: File
    private val downloader = HlsFlacDownloader()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        dir = File(System.getProperty("java.io.tmpdir"), "hls_test_${System.nanoTime()}")
            .also { it.mkdirs() }
        destination = File(dir, "track.m4a")
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        dir.deleteRecursively()
    }

    private fun playlist(): String {
        val base = server.url("/path/master.m3u8?exp=1").toString()
        return """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.0,
            seg-1.m4s
            #EXTINF:4.0,
            seg-2.m4s
            #EXT-X-ENDLIST
        """.trimIndent().also { assertThat(base).isNotEmpty() }
    }

    @Test
    fun `init and segments are concatenated in order`() = runTest {
        server.enqueue(MockResponse().setBody(playlist()))
        server.enqueue(MockResponse().setBody(Buffer().write("INIT".toByteArray())))
        server.enqueue(MockResponse().setBody(Buffer().write("SEG1".toByteArray())))
        server.enqueue(MockResponse().setBody(Buffer().write("SEG2".toByteArray())))

        val progress = mutableListOf<Pair<Long, Long>>()
        val result = downloader.download(
            client = OkHttpClient(),
            playlistUrl = server.url("/path/master.m3u8?exp=1&sig=abc").toString(),
            headers = emptyMap(),
            destination = destination,
            onProgress = { done, total -> progress += done to total },
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(destination.readText()).isEqualTo("INITSEG1SEG2")
        assertThat(progress).containsExactly(1L to 3L, 2L to 3L, 3L to 3L).inOrder()
        assertThat(server.takeRequest().path).startsWith("/path/master.m3u8")
        assertThat(server.takeRequest().path).isEqualTo("/path/init.mp4")
    }

    @Test
    fun `an unsupported playlist fails with a clear reason`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:1,\na.m4s\n#EXT-X-ENDLIST",
            ),
        )

        val result = downloader.download(
            OkHttpClient(),
            server.url("/path/master.m3u8").toString(),
            emptyMap(),
            destination,
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("HLS download isn't supported")
        assertThat(destination.exists()).isFalse()
        assertThat(File(dir, "track.hls.m4a").exists()).isFalse()
    }

    @Test
    fun `cancellation deletes the partial temp file`() = runTest {
        server.enqueue(MockResponse().setBody(playlist()))
        server.enqueue(MockResponse().setBody(Buffer().write("INIT".toByteArray())))
        server.enqueue(
            MockResponse().setBody(Buffer().write(ByteArray(1024 * 1024)))
                .setHeadersDelay(10, java.util.concurrent.TimeUnit.SECONDS),
        )
        server.enqueue(MockResponse().setBody(Buffer().write("SEG2".toByteArray())))

        val job = async {
            runCatching {
                downloader.download(
                    OkHttpClient(),
                    server.url("/path/master.m3u8").toString(),
                    emptyMap(),
                    destination,
                )
            }
        }
        server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        val startedNs = System.nanoTime()
        job.cancelAndJoin()

        assertThat(job.isCancelled).isTrue()
        assertThat((System.nanoTime() - startedNs) / 1_000_000).isLessThan(3_000)
        assertThat(destination.exists()).isFalse()
        assertThat(File(dir, "track.hls.m4a").exists()).isFalse()
    }
}
