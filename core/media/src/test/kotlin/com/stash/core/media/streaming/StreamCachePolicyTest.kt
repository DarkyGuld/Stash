package com.stash.core.media.streaming

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCachePolicyTest {
    private val file = File("/tmp/fake")

    /** Cache with [spans] keyed by name; removing a key frees its bytes. */
    private fun cache(vararg spans: CacheSpan): Cache {
        val live = spans.associateBy { it.key }.toMutableMap()
        val cache = mockk<Cache>()
        every { cache.cacheSpace } answers { live.values.sumOf { it.length } }
        every { cache.keys } answers { live.keys.toSet() }
        every { cache.getCachedSpans(any()) } answers {
            java.util.TreeSet<CacheSpan>().apply { live[firstArg<String>()]?.let { add(it) } }
        }
        every { cache.removeResource(any()) } answers { live.remove(firstArg<String>()) }
        return cache
    }

    private fun span(key: String, touched: Long) =
        CacheSpan(key, 0, StreamCachePolicy.MB * 100, touched, file)

    @Test
    fun `lowering the cap evicts the oldest unpinned file`() {
        val cache = cache(span("old", 1), span("new", 2))
        val policy = StreamCachePolicy()

        policy.setMaxBytes(100 * StreamCachePolicy.MB, cache)

        verify(exactly = 1) { cache.removeResource("old") }
        verify(exactly = 0) { cache.removeResource("new") }
        assertEquals(1, policy.counters().evictions)
    }

    @Test
    fun `a pinned key survives while an unpinned one can be evicted`() {
        val cache = cache(span("pinned-old", 1), span("new", 2))
        val policy = StreamCachePolicy()
        policy.pin("pinned-old")

        policy.setMaxBytes(100 * StreamCachePolicy.MB, cache)

        verify(exactly = 0) { cache.removeResource("pinned-old") }
        verify(exactly = 1) { cache.removeResource("new") }
        assertEquals(0, policy.counters().pinnedEvictions)
    }

    @Test
    fun `when every entry is pinned the oldest pinned one goes`() {
        val cache = cache(span("p1", 1), span("p2", 2))
        val policy = StreamCachePolicy()
        policy.pin("p1"); policy.pin("p2")

        policy.setMaxBytes(100 * StreamCachePolicy.MB, cache)

        verify(exactly = 1) { cache.removeResource("p1") }
        assertEquals(1, policy.counters().pinnedEvictions)
    }

    /**
     * Regression: Media3 calls onSpanAdded/onStartFile while holding SimpleCache's lock.
     * setMaxBytes() runs on another thread and calls back into Cache.cacheSpace. A blocking
     * policy lock inverted those two locks and ANR'd the main thread during startup/sync.
     */
    @Test
    fun `a cache callback never blocks behind a resize holding the policy lock`() {
        val cache = mockk<Cache>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { cache.cacheSpace } answers {
            if (Thread.currentThread().name == "resize") {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            StreamCachePolicy.MB * 200
        }
        every { cache.keys } returns emptySet()
        every { cache.getCachedSpans(any()) } returns sortedSetOf()
        every { cache.removeResource(any()) } answers { }
        val policy = StreamCachePolicy()

        val resize = thread(name = "resize") { policy.setMaxBytes(100 * StreamCachePolicy.MB, cache) }
        assertTrue("resize never reached cacheSpace", entered.await(2, TimeUnit.SECONDS))

        val callback = thread(name = "callback") { policy.onSpanAdded(cache, span("x", 1)) }
        callback.join(1_000)
        assertFalse("onSpanAdded must not block on the policy lock", callback.isAlive)

        release.countDown()
        resize.join(2_000)
        assertFalse("resize did not finish", resize.isAlive)
    }

    @Test
    fun `raising the cap keeps everything and counters track hits and misses`() {
        val cache = cache(span("a", 1), span("b", 2))
        val policy = StreamCachePolicy()

        policy.setMaxBytes(1_000 * StreamCachePolicy.MB, cache)
        policy.recordHit(); policy.recordMiss(); policy.recordMiss()
        policy.unpinAll()

        assertEquals(3, policy.counters().hits + policy.counters().misses)
        assertEquals(0, policy.counters().evictions)
        assertEquals(0, policy.counters().pinned)
    }
}
