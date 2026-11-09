package com.stash.core.media.streaming

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The streaming cache's size policy and evictor: one LRU cap, adjustable while
 * the cache is live. Raising it never deletes entries; lowering trims the oldest
 * unpinned files immediately. Pinned keys (the queue's next-track chunk) are
 * skipped by eviction unless every remaining entry is pinned.
 *
 * Media3's CacheEvictor is the supported seam for this, so no second cache and
 * no cache recreation is involved.
 */
@OptIn(UnstableApi::class)
@Singleton
class StreamCachePolicy @Inject constructor() : CacheEvictor {
    @Volatile private var maxBytes: Long = DEFAULT_MB * MB
    private val pinnedKeys = CopyOnWriteArraySet<String>()
    /**
     * Serializes evictions without ever blocking a cache callback: Media3 calls
     * [onSpanAdded]/[onStartFile] while holding SimpleCache's own lock, and the
     * settings screen reads [Cache.cacheSpace] from another thread. A blocking
     * lock here would invert those two locks (SimpleCache -> policy vs policy ->
     * SimpleCache) and deadlock the main thread.
     */
    private val lock = ReentrantLock()

    private val hits = AtomicLong(0)
    private val misses = AtomicLong(0)
    private val evictions = AtomicLong(0)
    private val pinnedEvictions = AtomicLong(0)

    override fun requiresCacheSpanTouches(): Boolean = true
    override fun onCacheInitialized() = Unit
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        enforce(cache, incomingBytes = length.coerceAtLeast(0))
    }
    override fun onSpanAdded(cache: Cache, span: CacheSpan) { enforce(cache) }
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) = Unit
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) = Unit

    fun setMaxBytes(bytes: Long, cache: Cache) {
        val value = bytes.coerceAtLeast(MIN_MB * MB)
        maxBytes = value
        // Lowering trims now; raising just leaves everything in place.
        enforce(cache)
        Log.i(TAG, "cache cap set to ${value / MB} MB (now ${cache.cacheSpace / MB} MB)")
    }

    fun maxBytes(): Long = maxBytes

    fun pin(key: String) { pinnedKeys += key }
    fun unpin(key: String) { pinnedKeys -= key }
    fun unpinAll() { pinnedKeys.clear() }
    fun isPinned(key: String): Boolean = key in pinnedKeys

    fun recordHit() { hits.incrementAndGet() }
    fun recordMiss() { misses.incrementAndGet() }

    fun counters(): Counters = Counters(
        hits = hits.get(),
        misses = misses.get(),
        evictions = evictions.get(),
        pinnedEvictions = pinnedEvictions.get(),
        pinned = pinnedKeys.size,
    )

    data class Counters(
        val hits: Long,
        val misses: Long,
        val evictions: Long,
        val pinnedEvictions: Long,
        val pinned: Int,
    )

    /** Evicts oldest unpinned cached files until the cap (plus an incoming file) fits. */
    fun enforce(cache: Cache, incomingBytes: Long = 0) {
        // A cache callback runs under SimpleCache's lock. Waiting for the policy
        // lock there can deadlock with setMaxBytes()/sizeBytes() holding the
        // policy path and waiting for SimpleCache. Skipping this pass is safe:
        // the other pass trims, and the next span/start enforces again.
        if (!lock.tryLock()) return
        try {
            val target = maxBytes - incomingBytes
            if (cache.cacheSpace + incomingBytes <= maxBytes) return
            val spans = cache.keys.toList()
                .flatMap { key -> cache.getCachedSpans(key).filter { it.isCached } }
                .sortedBy { it.lastTouchTimestamp }
            for (span in spans) {
                if (cache.cacheSpace + incomingBytes <= maxBytes) break
                val pinned = span.key in pinnedKeys
                val unpinnedLeft = spans.any { it.key !in pinnedKeys && cache.getCachedSpans(it.key).isNotEmpty() }
                if (pinned && unpinnedLeft) continue
                runCatching { cache.removeResource(span.key) }
                evictions.incrementAndGet()
                if (pinned) pinnedEvictions.incrementAndGet()
                // Key only, never a URL query.
                Log.d(TAG, "cache evicted ${if (pinned) "pinned " else ""}$span.key (target ${target / MB} MB)")
            }
        } finally {
            lock.unlock()
        }
    }

    companion object {
        const val MB = 1024L * 1024L
        const val DEFAULT_MB = 500L
        const val MIN_MB = 100L
        val OPTIONS_MB = listOf(250, 500, 1000, 2000)
        private const val TAG = "StreamCachePolicy"
    }
}
