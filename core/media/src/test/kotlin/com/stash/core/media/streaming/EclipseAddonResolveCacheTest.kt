package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EclipseAddonResolveCacheTest {
    private var now = 1_000_000_000_000L

    @Test
    fun `a positive hit lives six hours, a negative miss half a minute`() {
        val cache = EclipseAddonResolveCache(clock = { now })

        cache.put("hit", "item-1")
        cache.put("miss", null)

        now += 30_000L - 1
        assertThat(cache.get("hit")?.itemId).isEqualTo("item-1")
        assertThat(cache.get("miss")?.itemId).isNull()
        assertThat(cache.get("miss")).isNotNull()

        now += 2 // the negative entry is now past half a minute
        assertThat(cache.get("miss")).isNull()
        assertThat(cache.get("hit")).isNotNull()

        now += 6 * 60 * 60_000L
        assertThat(cache.get("hit")).isNull()
    }

    @Test
    fun `the cache evicts the least recently used entry past its bound`() {
        val cache = EclipseAddonResolveCache(maxEntries = 2, clock = { now })

        cache.put("a", "1")
        cache.put("b", "2")
        cache.get("a") // a becomes most recently used
        cache.put("c", "3")

        assertThat(cache.size()).isEqualTo(2)
        assertThat(cache.get("b")).isNull()
        assertThat(cache.get("a")).isNotNull()
        assertThat(cache.get("c")).isNotNull()
    }

    @Test
    fun `invalidate drops a positive entry so the next lookup misses`() {
        val cache = EclipseAddonResolveCache(clock = { now })
        cache.put("key", "item")

        cache.invalidate("key")

        assertThat(cache.get("key")).isNull()
        assertThat(cache.size()).isEqualTo(0)
    }
}
