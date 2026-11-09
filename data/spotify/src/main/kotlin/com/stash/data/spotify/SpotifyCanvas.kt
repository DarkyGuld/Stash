package com.stash.data.spotify

import com.stash.core.model.Track
import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Pure helpers behind [SpotifyApiClient.canvasUrl]: resolving the right Spotify
 * track (no near-miss is good enough for a Canvas), reading the Pathfinder
 * `canvas` answer, and the cache lifetimes.
 *
 * A Canvas is cosmetic: a missing one is invisible, while the wrong one is a
 * visibly wrong video. Every ambiguity therefore fails closed.
 */
internal object SpotifyCanvas {

    /** A resolved Canvas URL stays warm for a day. */
    const val POSITIVE_TTL_MS = 24L * 60 * 60 * 1000

    /** "No Canvas" stays cold for ten minutes, then is asked again. */
    const val NEGATIVE_TTL_MS = 10L * 60 * 1000

    /** The in-memory cache is bounded so a long library walk can't grow it without end. */
    const val CACHE_MAX_ENTRIES = 200

    /** A candidate is the same recording only when its length is within this. */
    private const val DURATION_TOLERANCE_MS = 2_000L

    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")

    /** Artist credit separators; "&" is deliberately kept (Simon & Garfunkel). */
    private val ARTIST_SEPARATOR = Regex(
        """\s*(?:,|;|\bfeat\.?|\bfeaturing\b|\bft\.?|\bwith\b)\s*""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The cache key: the Spotify URI when known, else the normalized title and
     * artist. Null only when there is nothing to key on (no URI, blank text).
     */
    fun cacheKey(track: Track): String? {
        trackUri(track.spotifyUri)?.let { return "uri:$it" }
        val title = normalize(track.title)
        val artist = normalize(track.artist)
        if (title.isEmpty() && artist.isEmpty()) return null
        return "meta:$title|$artist"
    }

    /**
     * A full `spotify:track:…` URI from a stored Spotify URI or an
     * `open.spotify.com/track/…` link. Null for anything else.
     */
    fun trackUri(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith("spotify:track:")) {
            val id = value.removePrefix("spotify:track:").substringBefore('?').trim()
            return id.takeIf { it.isNotEmpty() }?.let { "spotify:track:$it" }
        }
        val url = value.toHttpUrlOrNull() ?: return null
        if (!url.host.equals("open.spotify.com", ignoreCase = true)) return null
        val trackIndex = url.pathSegments.indexOf("track")
        if (trackIndex < 0) return null
        val id = url.pathSegments.getOrNull(trackIndex + 1)?.takeIf { it.isNotBlank() } ?: return null
        return "spotify:track:$id"
    }

    /**
     * The one searched candidate that is unambiguously this recording: exact
     * normalized title, every artist part present, and — when both lengths are
     * known — within two seconds. Different candidate ids count as ambiguity,
     * so two pressings both answering exactly yield no Canvas.
     */
    fun exactCandidate(track: Track, candidates: List<SpotifyTrackCandidate>): SpotifyTrackCandidate? {
        val wantedTitle = normalize(track.title)
        if (wantedTitle.isEmpty()) return null
        val wantedArtists = artistParts(track.artist).map(::normalize).filter { it.isNotEmpty() }
        if (wantedArtists.isEmpty()) return null

        return candidates
            .filter { candidate ->
                normalize(candidate.name) == wantedTitle &&
                    candidateArtistParts(candidate).containsAll(wantedArtists) &&
                    durationCompatible(track.durationMs, candidate.durationMs)
            }
            .distinctBy { it.id }
            .singleOrNull()
    }

    /**
     * `data.trackUnion.canvas.url` from a Pathfinder `canvas` answer, also
     * accepting the shallower `data.track.canvas.url` / `data.canvas.url`
     * shapes. Only a playable http(s) MP4 URL is returned; a URL-less
     * `fileId` entry is no Canvas for now.
     */
    fun parseUrl(root: JsonObject): String? {
        val data = root["data"] as? JsonObject ?: return null
        val canvas = (data["trackUnion"] as? JsonObject)?.get("canvas") as? JsonObject
            ?: (data["track"] as? JsonObject)?.get("canvas") as? JsonObject
            ?: data["canvas"] as? JsonObject
            ?: return null
        val url = (canvas["url"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (url.isEmpty()) return null
        val type = (canvas["type"] as? JsonPrimitive)?.contentOrNull
        return url.takeIf { isPlayableUrl(it, type) }
    }

    /**
     * True for an http(s) URL whose path ends `.mp4`, or when the Canvas answer
     * explicitly declares `video/mp4`. Other schemes and image URLs are rejected.
     */
    fun isPlayableUrl(url: String, type: String? = null): Boolean {
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        if (type?.trim().equals("video/mp4", ignoreCase = true)) return true
        return parsed.encodedPath.endsWith(".mp4", ignoreCase = true)
    }

    /** Lowercase, punctuation-collapsed text used for exact comparisons and the cache key. */
    fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
            .replace('\u201c', '"')
            .replace('\u201d', '"')
            .replace('\u2013', '-')
            .replace('\u2014', '-')
            .replace(NON_ALNUM, " ")
            .trim()

    /** The separate artist credits in a billing line ("A, B feat. C"). */
    fun artistParts(value: String): List<String> =
        value.split(ARTIST_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    private fun candidateArtistParts(candidate: SpotifyTrackCandidate): List<String> =
        candidate.artists.flatMap(::artistParts).map(::normalize).filter { it.isNotEmpty() }

    private fun durationCompatible(ours: Long, theirs: Long): Boolean {
        if (ours <= 0 || theirs <= 0) return true
        return abs(ours - theirs) <= DURATION_TOLERANCE_MS
    }
}
