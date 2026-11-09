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

    /** Cache with [spans] grouped by key; removing a key frees all its spans. */
    private fun cache(vararg spans: CacheSpan): Cache = cache(spans.toList())

    private fun cache(spans: List<CacheSpan>): Cache {
        val live = spans.groupBy { it.key }.mapValues { it.value.toMutableList() }.toMutableMap()
        val cache = mockk<Cache>()
        every { cache.cacheSpace } answers { live.values.flatten().sumOf { it.length } }
        every { cache.keys } answers { live.keys.toSet() }
        every { cache.getCachedSpans(any()) } answers {
            java.util.TreeSet<CacheSpan>().apply { live[firstArg<String>()]?.let { addAll(it) } }
        }
        every { cache.removeResource(any()) } answers { live.remove(firstArg<String>()) != null }
        return cache
    }

    private fun span(key: String, touched: Long, position: Long = 0) =
        CacheSpan(key, position, StreamCachePolicy.MB * 100, touched, file)

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

    @Test
    fun `prefix-protected tracks survive while an unprotected file goes`() {
        val active = "eclipse|1|addon:Cloud|flac|/a.flac"
        val lookahead = "eclipse|2|addon:Cloud|flac|/b.flac"
        val other = "eclipse|9|addon:Cloud|flac|/old.flac"
        val cache = cache(span(active, 1), span(lookahead, 2), span(other, 3))
        val policy = StreamCachePolicy()

        policy.retainTracks(listOf(1L, 2L, 3L))
        policy.setMaxBytes(200 * StreamCachePolicy.MB, cache)

        // Only the unprotected track goes, even though it is not the oldest.
        verify(exactly = 0) { cache.removeResource(active) }
        verify(exactly = 0) { cache.removeResource(lookahead) }
        verify(exactly = 1) { cache.removeResource(other) }
        assertTrue(policy.isProtected(active))
        assertTrue(policy.isProtected(lookahead))
        assertFalse(policy.isProtected("eclipse|4|addon:Cloud|flac|/d.flac"))
    }

    @Test
    fun `lookahead is evicted before the active track`() {
        val active = "eclipse|1|addon:Cloud|flac|/a.flac"
        val lookahead = "eclipse|2|addon:Cloud|flac|/b.flac"
        val cache = cache(span(active, 1), span(lookahead, 2))
        val policy = StreamCachePolicy()

        policy.retainTracks(listOf(1L, 2L))
        policy.setMaxBytes(100 * StreamCachePolicy.MB, cache)

        verify(exactly = 0) { cache.removeResource(active) }
        verify(exactly = 1) { cache.removeResource(lookahead) }
        assertEquals(1, policy.counters().pinnedEvictions)
    }

    @Test
    fun `a recently touched key survives even when it is the oldest`() {
        val cache = cache(span("old", 1), span("new", 2))
        val policy = StreamCachePolicy()
        policy.elapsedRealtimeMs = { 0L }
        policy.onSpanTouched(cache, span("old", 1), span("old", 1))
        policy.elapsedRealtimeMs = { 1_000L }

        policy.setMaxBytes(100 * StreamCachePolicy.MB, cache)

        verify(exactly = 0) { cache.removeResource("old") }
        verify(exactly = 1) { cache.removeResource("new") }
    }

    @Test
    fun `the touch window expires after ninety seconds`() {
        val cache = cache(span("old", 1), span("new", 2))
        val policy = StreamCachePolicy()
        policy.elapsedRealtimeMs = { 0L }
        policy.onSpanTouched(cache, span("old", 1), span("old", 1))
        policy.elapsedRealtimeMs = { 100_000L }

        policy.setMaxBytes(100 * StreamCachePolicy.MB, cache)

        verify(exactly = 1) { cache.removeResource("old") }
        verify(exactly = 0) { cache.removeResource("new") }
    }

    @Test
    fun `onStartFile clamps a 60 MB length to five megabytes`() {
        val empty = cache()
        val cache = cache(span("a", 1), span("b", 2), span("c", 3), span("d", 4))
        val policy = StreamCachePolicy()
        // Configure the cap without trimming the test cache itself.
        policy.setMaxBytes(300 * StreamCachePolicy.MB, empty)

        // 400 MB cached + 5 MB clamped (not 60 MB) needs two evictions,
        // stopping at 200 MB; an unclamped 600 MB would evict all four.
        policy.onStartFile(cache, "a", 0, 60 * StreamCachePolicy.MB)

        verify(exactly = 2) { cache.removeResource(any()) }
        assertEquals(2, policy.counters().evictions)
    }

    @Test
    fun `a key with several spans is removed exactly once`() {
        val cache = cache(
            span("dup", 1, position = 0),
            span("dup", 2, position = StreamCachePolicy.MB * 100),
            span("other", 3),
        )
        val policy = StreamCachePolicy()

        policy.setMaxBytes(100 * StreamCachePolicy.MB, cache)

        verify(exactly = 1) { cache.removeResource("dup") }
        verify(exactly = 0) { cache.removeResource("other") }
        // One eviction per key, not per span.
        assertEquals(1, policy.counters().evictions)
    }

    @Test
    fun `when every prefix track is protected the oldest protected one goes`() {
        val first = "eclipse|1|addon:Cloud|flac|/a.flac"
        val second = "eclipse|2|addon:Cloud|flac|/b.flac"
        val cache = cache(span(first, 1), span(second, 2))
        val policy = StreamCachePolicy()
        policy.pinTrack(1L); policy.pinTrack(2L)

        policy.setMaxBytes(100 * StreamCachePolicy.MB, cache)

        verify(exactly = 1) { cache.removeResource(first) }
        verify(exactly = 0) { cache.removeResource(second) }
        assertEquals(1, policy.counters().pinnedEvictions)
    }

    @Test
    fun `retainTracks replaces the previous window`() {
        val policy = StreamCachePolicy()
        policy.pinTrack(9L)

        policy.retainTracks(listOf(1L, 2L, 3L))

        assertTrue(policy.isProtected("eclipse|1|x"))
        assertTrue(policy.isProtected("eclipse|2|x"))
        assertFalse(policy.isProtected("eclipse|9|x"))
    }

    @Test
    fun `retainTracks releases exact pins for tracks that left the window`() {
        val policy = StreamCachePolicy()
        policy.pin("eclipse|9|addon:Cloud|flac|/old.flac")
        policy.pin("keep-me")

        policy.retainTracks(listOf(1L, 2L))

        assertFalse(policy.isPinned("eclipse|9|addon:Cloud|flac|/old.flac"))
        assertTrue(policy.isPinned("keep-me"))
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
