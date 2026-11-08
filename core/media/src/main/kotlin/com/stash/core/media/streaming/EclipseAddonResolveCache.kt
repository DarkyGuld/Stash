package com.stash.core.media.streaming

/**
 * The resolver's per-addon item-id memo: `/resolve` (and `/resolve-isrc`) is one
 * metadata round trip that does not change between plays, while `/stream` signs a
 * fresh URL every time. Caching the id cuts the second round trip from every play.
 *
 * LRU, thread-safe, bounded, with TTLs: a hit lives 6 hours, a miss only 5 minutes
 * (the addon's catalog may gain the track). The key includes the addon and its
 * settings snapshot, so a quality change never reuses another quality's id.
 */
internal class EclipseAddonResolveCache(
    private val maxEntries: Int = 256,
    private val positiveTtlMs: Long = 6 * 60 * 60_000L,
    private val negativeTtlMs: Long = 5 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    internal data class Entry(val itemId: String?, val expiresAtMs: Long)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > maxEntries
    }

    /** The live entry for [key], or null when absent/expired. An entry with a null [Entry.itemId] is a cached miss. */
    @Synchronized
    fun get(key: String): Entry? {
        val entry = entries[key] ?: return null
        if (entry.expiresAtMs <= clock()) {
            entries.remove(key)
            return null
        }
        return entry
    }

    /** [itemId] null caches a miss (short TTL); a real id caches a hit (long TTL). */
    @Synchronized
    fun put(key: String, itemId: String?, ttlMs: Long? = null) {
        val ttl = ttlMs ?: if (itemId == null) negativeTtlMs else positiveTtlMs
        entries[key] = Entry(itemId, clock() + ttl)
    }

    /** Caches a miss with an explicit TTL (Cloud's Tidal miss is long: Apple still serves it). */
    @Synchronized
    fun putMiss(key: String, ttlMs: Long) {
        entries[key] = Entry(null, clock() + ttlMs)
    }

    @Synchronized
    fun invalidate(key: String) {
        entries.remove(key)
    }

    @Synchronized
    fun size(): Int = entries.size
}
