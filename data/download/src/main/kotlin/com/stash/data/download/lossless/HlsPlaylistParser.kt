package com.stash.data.download.lossless

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One media playlist's download plan. */
data class HlsPlaylistPlan(
    val initUrl: String?,
    val segmentUrls: List<String>,
)

/**
 * Parses an HLS **media** playlist (not a master playlist) into init + segment
 * URLs, resolving relative references against the playlist address. Rejects
 * anything we cannot download faithfully: encryption, live (no ENDLIST), master
 * playlists, and absurdly long lists.
 */
object HlsPlaylistParser {
    private const val MAX_SEGMENTS = 500
    private val URI_ATTR = Regex("URI=\"([^\"]+)\"")

    /** Null when the playlist is not a supported, finite, unencrypted media playlist. */
    fun parse(text: String, playlistUrl: String): HlsPlaylistPlan? {
        val base = playlistUrl.toHttpUrlOrNull() ?: return null
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.none { it == "#EXTM3U" }) return null
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) return null // master playlist
        if (lines.none { it == "#EXT-X-ENDLIST" }) return null // live
        for (line in lines) {
            if (!line.startsWith("#EXT-X-KEY")) continue
            val method = Regex("METHOD=([^,\"]+)").find(line)?.groupValues?.get(1)
            if (!method.equals("NONE", ignoreCase = true)) return null // encrypted / DRM
        }

        var init: String? = null
        val segments = mutableListOf<String>()
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MAP") -> {
                    val uri = URI_ATTR.find(line)?.groupValues?.get(1) ?: return null
                    init = base.resolve(uri)?.toString() ?: return null
                }
                line.startsWith("#") -> Unit
                else -> {
                    val resolved = base.resolve(line)?.toString() ?: return null
                    segments += resolved
                    if (segments.size > MAX_SEGMENTS) return null
                }
            }
        }
        if (segments.isEmpty()) return null
        return HlsPlaylistPlan(init, segments)
    }
}
