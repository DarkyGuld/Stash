package com.stash.data.spotify

import com.stash.core.auth.TokenManager
import com.stash.core.auth.spotify.SpotifyAuthManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The per-play ISRC lookup must not hold Cloud's resolve: a stalled body stops when the
 * caller gives up (or at the call's own timeout), and the request itself is dropped.
 */
class SpotifyApiClientIsrcStallTest {

    private lateinit var server: ServerSocket
    private lateinit var connectionClosed: CountDownLatch
    private lateinit var serverThread: Thread
    private lateinit var client: SpotifyApiClient

    /** Answers with headers and half a body, then stalls until the client drops the connection. */
    private fun startStallingServer() {
        server = ServerSocket(0)
        connectionClosed = CountDownLatch(1)
        serverThread = thread(isDaemon = true) {
            val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            socket.use {
                val input = it.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) {
                    head.append(input.read().takeIf { b -> b >= 0 }?.toChar() ?: return@thread)
                }
                it.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: 1000\r\n\r\n{\"tracks\":["
                            ).toByteArray(),
                    )
                    flush()
                }
                // Blocks until the client closes its end: -1 (or a reset) means the request was dropped.
                runCatching { while (input.read() >= 0) Unit }
                connectionClosed.countDown()
            }
        }

        val http = OkHttpClient.Builder()
            // Far past the caller's budget: only cancellation can stop the read.
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val local = "http://127.0.0.1:${server.localPort}/v1/tracks".toHttpUrl()
                chain.proceed(chain.request().newBuilder().url(local).build())
            }
            .build()

        client = SpotifyApiClient(
            http,
            mockk<TokenManager> {
                coEvery { getSpotifyAccessToken() } returns "access-token"
            },
            mockk<SpotifyAuthManager>(relaxed = true),
        )
    }

    @Before
    fun setUp() {
        startStallingServer()
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    @Test
    fun `a stalled ISRC call stops at the caller's budget and drops the request`() = runBlocking {
        val started = System.nanoTime()

        val batch = withTimeoutOrNull(500) { client.getTracksIsrc(listOf("5ZuIWk2CWo9NTh3Ijzbn3r")) }
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertNull(batch)
        assertTrue("returned after $tookMs ms", tookMs < 3_000)
        assertTrue("the server saw the request dropped", connectionClosed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `an ISRC call with no caller timeout is bounded by its own call timeout`() = runBlocking {
        val started = System.nanoTime()

        val batch = client.getTracksIsrc(listOf("5ZuIWk2CWo9NTh3Ijzbn3r"))
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("expected a failed batch, got $batch", batch is SpotifyApiClient.IsrcBatch.Failed)
        assertTrue("returned after $tookMs ms", tookMs < 5_000)
        assertTrue("the server saw the request dropped", connectionClosed.await(5, TimeUnit.SECONDS))
    }
}
