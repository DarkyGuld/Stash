package com.stash.core.media.streaming

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import com.stash.core.data.addons.EclipseAppleLookupStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings' view of the streaming cache: current size, and the one "Clear cache"
 * action that drops every layer Stash keeps for addon playback — the Media3
 * SimpleCache, the in-memory URL cache, the persistent addon URL store and the
 * Apple-id lookup store. Playback refetches lazily on the next play.
 */
@OptIn(UnstableApi::class)
@Singleton
class StreamCacheMaintenance @Inject constructor(
    @StreamCache private val streamCache: SimpleCache,
    private val urlCache: StreamUrlCache,
    private val urlStore: EclipseStreamUrlStore,
    private val appleLookup: EclipseAppleLookupStore,
    private val policy: StreamCachePolicy,
) {
    fun sizeBytes(): Long = streamCache.cacheSpace.coerceAtLeast(0)

    /** Drops the in-memory layer and every SimpleCache resource. */
    fun clearStreamCache() {
        runCatching { streamCache.keys.toList().forEach(streamCache::removeResource) }
        urlCache.clear()
    }

    /** Adjusts the live cap: raising keeps entries, lowering trims the oldest unpinned files. */
    fun setMaxBytes(bytes: Long) {
        policy.setMaxBytes(bytes, streamCache)
        Log.i(
            "StreamCacheMaintenance",
            "cache ${policy.counters()} after resize to ${bytes / StreamCachePolicy.MB} MB",
        )
    }

    /** The full clear: stream cache + persistent addon URL/Apple lookups (sign-out, account change, button). */
    suspend fun clearAll() {
        clearStreamCache()
        runCatching { urlStore.clear() }
        runCatching { appleLookup.clear() }
    }
}
