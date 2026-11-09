package com.stash.data.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val inspiredByJson = Json { ignoreUnknownKeys = true }

/**
 * Pulls the playlist URI out of an "Inspired by" answer
 * (`spclient.wg.spotify.com/inspiredby-mix/v2/seed_to_playlist/…`).
 *
 * Only `mediaItems[0].uri` is read, and only when it is a non-empty
 * `spotify:playlist:…` URI: an artist, album, track or an empty answer is a
 * miss. Any malformed body is a miss too, never a crash.
 */
internal fun parseInspiredByPlaylistUri(body: String?): String? {
    if (body.isNullOrBlank()) return null
    return try {
        val root = inspiredByJson.parseToJsonElement(body) as? JsonObject ?: return null
        val mediaItems = root["mediaItems"] as? JsonArray ?: return null
        val uri = ((mediaItems.firstOrNull() as? JsonObject)?.get("uri") as? JsonPrimitive)
            ?.contentOrNull
        uri?.takeIf { it.startsWith("spotify:playlist:") && it.length > "spotify:playlist:".length }
    } catch (e: Exception) {
        null
    }
}
