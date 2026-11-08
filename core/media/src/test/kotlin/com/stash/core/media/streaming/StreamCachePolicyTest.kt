package com.stash.core.media.streaming

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import org.junit.Assert.assertEquals
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
