package com.stash.core.media.streaming

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory

/**
 * Stable cache key for an addon's progressive file: the signed query
 * (`etsp`/`exp`/`hmac`/`token`/`sig`) changes every time and must not fragment
 * the cache. Player and first-chunk prefetcher build the same key.
 */
@OptIn(UnstableApi::class)
internal class EclipseAddonCacheKeyFactory(
    private val trackId: Long?,
    private val stream: StreamUrl,
) : CacheKeyFactory {
    override fun buildCacheKey(dataSpec: DataSpec): String =
        addonCacheKey(trackId, stream, dataSpec.uri.toString())
}

@OptIn(UnstableApi::class)
internal fun addonCacheKey(trackId: Long?, stream: StreamUrl, url: String): String {
    val path = url.substringBefore('?').substringBefore('#')
    val quality = listOfNotNull(
        stream.codec,
        stream.bitsPerSample?.toString(),
        stream.sampleRateHz?.toString(),
        stream.bitrateKbps?.toString(),
    ).joinToString("/")
    return "eclipse|${trackId ?: 0}|${stream.origin.orEmpty()}|$quality|$path"
}
