package com.stash.core.media.streaming

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The streaming cache's size policy and evictor: one LRU cap, adjustable while
 * the cache is live. Raising it never deletes entries; lowering trims the oldest
 * files immediately.
 *
 * Protection has two levels:
 *  - exact [pinnedKeys] (a prefetched first chunk / manifest key), and
 *  - [pinTrack]'s `eclipse|<trackId>|` prefixes, so every segment of the
 *    actively playing track and its lookahead survives.
 *
 * Eviction order is unprotected first, then lookahead, then the active track.
 * If every remaining entry is protected, the oldest protected entry goes so a
 * single track larger than the cap can never grow the cache without bound.
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
     * Track ids whose cache prefix (`eclipse|<id>|`) must not be evicted.
     * Value `true` marks the active (audible) track; lookahead tracks are
     * `false`. Guarded by `this` for updates; read lock-free through
     * [protectedPrefixes]/[activePrefix].
     */
    private val trackPins = ConcurrentHashMap<Long, Boolean>()
    @Volatile private var activeTrackId: Long? = null
    @Volatile private var protectedPrefixes: List<String> = emptyList()
    @Volatile private var activePrefix: String? = null

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
    private val protectedBytes = AtomicLong(0)

    /**
     * key -> elapsedRealtime of the last touch. Lock-free and allocation-light:
     * [onSpanTouched] runs inside a cache callback and must never take the policy
     * lock or call back into [Cache].
     */
    private val touchTimes = ConcurrentHashMap<String, Long>()
    private val lastEnforceMs = AtomicLong(Long.MIN_VALUE / 2)

    /**
     * Monotonic wall clock; replaceable in tests. On Android this is
     * elapsedRealtime (counts deep sleep); JVM unit tests get 0 from the stub,
     * so fall back to nanoTime there.
     */
    @Volatile internal var elapsedRealtimeMs: () -> Long = {
        val realtime = android.os.SystemClock.elapsedRealtime()
        if (realtime > 0L) realtime else System.nanoTime() / 1_000_000L
    }

    override fun requiresCacheSpanTouches(): Boolean = true
    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        // A progressive whole-file warm may report 60 MB; only the actual
        // overshoot matters for eviction, so clamp it hard.
        enforce(cache, incomingBytes = length.coerceIn(0L, MAX_START_FILE_BYTES))
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) { enforce(cache) }
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) = Unit

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        // No lock, no cache call: SimpleCache holds its own lock here.
        touchTimes[newSpan.key] = elapsedRealtimeMs()
    }

    fun setMaxBytes(bytes: Long, cache: Cache) {
        val value = bytes.coerceAtLeast(MIN_MB * MB)
        maxBytes = value
        // Explicit resize is not subject to the callback throttle: lowering the
        // cap must trim right away, raising it just leaves everything in place.
        enforceNow(cache)
        Log.i(TAG, "cache cap set to ${value / MB} MB (now ${cache.cacheSpace / MB} MB)")
    }

    fun maxBytes(): Long = maxBytes

    fun pin(key: String) { pinnedKeys += key }
    fun unpin(key: String) { pinnedKeys -= key }

    /** Protects every cache entry of [id] (`eclipse|<id>|`) as lookahead. */
    @Synchronized
    fun pinTrack(id: Long) {
        trackPins[id] = trackPins[id] == true || activeTrackId == id
        rebuildPrefixesLocked()
    }

    @Synchronized
    fun unpinTrack(id: Long) {
        trackPins.remove(id)
        if (activeTrackId == id) activeTrackId = null
        rebuildPrefixesLocked()
    }

    /**
     * The playback position moved: protect exactly this window (current + next
     * 2, the first id being the active one). Callers always include the item
     * actually playing, including on a seek, so a seek never drops its pin.
     */
    @Synchronized
    fun retainTracks(ids: List<Long>) {
        val wanted = ids.distinct()
        if (wanted.isEmpty()) return
        val current = wanted.first()
        val keep = wanted.toSet()
        activeTrackId = current
        trackPins.keys.retainAll(keep)
        wanted.forEachIndexed { index, id -> trackPins[id] = index == 0 }
        // Exact first-chunk/manifest pins belong to a track too: release the ones
        // whose track just left the window, or they would accumulate and push
        // the next all-protected fallback onto the audible track.
        val stalePins = pinnedKeys.filter { key -> trackIdOf(key)?.let { it !in keep } == true }
        if (stalePins.isNotEmpty()) pinnedKeys.removeAll(stalePins.toSet())
        rebuildPrefixesLocked()
    }

    @Synchronized
    fun unpinAll() {
        pinnedKeys.clear()
        trackPins.clear()
        activeTrackId = null
        rebuildPrefixesLocked()
    }

    fun isPinned(key: String): Boolean = key in pinnedKeys

    /** Exact pin or any protected track prefix. */
    fun isProtected(key: String): Boolean {
        if (key in pinnedKeys) return true
        val prefixes = protectedPrefixes
        for (i in prefixes.indices) {
            if (key.startsWith(prefixes[i])) return true
        }
        return false
    }

    fun recordHit() { hits.incrementAndGet() }
    fun recordMiss() { misses.incrementAndGet() }

    fun counters(): Counters = Counters(
        hits = hits.get(),
        misses = misses.get(),
        evictions = evictions.get(),
        pinnedEvictions = pinnedEvictions.get(),
        pinned = pinnedKeys.size + trackPins.size,
        protectedBytes = protectedBytes.get(),
    )

    data class Counters(
        val hits: Long,
        val misses: Long,
        val evictions: Long,
        val pinnedEvictions: Long,
        val pinned: Int,
        val protectedBytes: Long = 0,
    )

    /**
     * Evicts oldest unprotected cached files until the cap (plus an incoming
     * file) fits. Throttled: cache callbacks fire for every span, and a full
     * scan that often is wasteful while playback is streaming.
     */
    fun enforce(cache: Cache, incomingBytes: Long = 0) {
        // A cache callback runs under SimpleCache's lock. Waiting for the policy
        // lock there can deadlock with setMaxBytes()/sizeBytes() holding the
        // policy path and waiting for SimpleCache. Skipping this pass is safe:
        // the other pass trims, and the next span/start enforces again.
        if (!lock.tryLock()) return
        try {
            // Only trim when we are already over the cap; being exactly at it is
            // fine and avoids a scan per callback.
            if (cache.cacheSpace <= maxBytes) return
            if (!acquireThrottle()) return
            enforceLocked(cache, incomingBytes)
        } finally {
            lock.unlock()
        }
    }

    /** Uncapped pass used by [setMaxBytes]: an explicit resize always trims. */
    private fun enforceNow(cache: Cache) {
        if (!lock.tryLock()) return
        try {
            enforceLocked(cache, incomingBytes = 0)
        } finally {
            lock.unlock()
        }
    }

    private fun acquireThrottle(): Boolean {
        val now = elapsedRealtimeMs()
        while (true) {
            val last = lastEnforceMs.get()
            if (now - last < ENFORCE_INTERVAL_MS) return false
            if (lastEnforceMs.compareAndSet(last, now)) return true
        }
    }

    private fun enforceLocked(cache: Cache, incomingBytes: Long) {
        val candidates = ArrayList<Candidate>(cache.keys.size)
        var protectedTotal = 0L
        for (key in cache.keys) {
            val spans = cache.getCachedSpans(key)
            if (spans.isEmpty()) continue
            var oldest = Long.MAX_VALUE
            var bytes = 0L
            var cached = false
            for (span in spans) {
                if (!span.isCached) continue
                cached = true
                oldest = minOf(oldest, span.lastTouchTimestamp)
                if (span.length > 0) bytes += span.length
            }
            if (!cached) continue
            val rank = rankOf(key)
            if (rank > 0) protectedTotal += bytes
            candidates += Candidate(key, rank, oldest)
        }
        protectedBytes.set(protectedTotal)
        candidates.sortWith(compareBy({ it.rank }, { it.oldestTouch }))

        val now = elapsedRealtimeMs()
        val removedKeys = HashSet<String>()
        for (candidate in candidates) {
            if (cache.cacheSpace + incomingBytes <= maxBytes) break
            if (!removedKeys.add(candidate.key)) continue
            val touchedAt = touchTimes[candidate.key]
            // An actively read/written key is never a candidate, even if some
            // other file has an older lastTouch timestamp.
            if (touchedAt != null && now - touchedAt < RECENT_TOUCH_WINDOW_MS) continue
            val result = runCatching { cache.removeResource(candidate.key) }
            if (result.isFailure) {
                Log.d(TAG, "cache eviction failed for ${candidate.key}: ${result.exceptionOrNull()?.javaClass?.simpleName}")
                continue
            }
            touchTimes.remove(candidate.key)
            evictions.incrementAndGet()
            if (candidate.rank > 0) pinnedEvictions.incrementAndGet()
            // Key only, never a URL query.
            Log.d(
                TAG,
                "cache evicted ${if (candidate.rank > 0) "protected " else ""}${candidate.key} " +
                    "(target ${(maxBytes - incomingBytes) / MB} MB)",
            )
        }
    }

    /** 0 unprotected, 1 protected lookahead, 2 the active track. */
    private fun rankOf(key: String): Int {
        if (!isProtected(key)) return 0
        val active = activePrefix
        return if (active != null && key.startsWith(active)) 2 else 1
    }

    @Synchronized
    private fun rebuildPrefixesLocked() {
        protectedPrefixes = trackPins.keys.map(::trackPrefix)
        activePrefix = activeTrackId?.let(::trackPrefix)
    }

    private fun trackPrefix(id: Long): String = "$TRACK_PREFIX$id|"

    /** `eclipse|42|...` -> 42; anything else -> null. */
    private fun trackIdOf(key: String): Long? {
        if (!key.startsWith(TRACK_PREFIX)) return null
        val end = key.indexOf('|', TRACK_PREFIX.length)
        if (end <= TRACK_PREFIX.length) return null
        return key.substring(TRACK_PREFIX.length, end).toLongOrNull()
    }

    private data class Candidate(
        val key: String,
        val rank: Int,
        val oldestTouch: Long,
    )

    companion object {
        private const val TRACK_PREFIX = "eclipse|"

        const val MB = 1024L * 1024L
        const val DEFAULT_MB = 500L
        const val MIN_MB = 100L
        val OPTIONS_MB = listOf(250, 500, 1000, 2000)

        /** Longest a single onStartFile length may influence a trim. */
        private const val MAX_START_FILE_BYTES = 5 * MB

        /** At most one full eviction scan per this interval. */
        private const val ENFORCE_INTERVAL_MS = 750L

        /** A key touched this recently is being read/written and must survive. */
        private const val RECENT_TOUCH_WINDOW_MS = 90_000L

        private const val TAG = "StreamCachePolicy"
    }
}
