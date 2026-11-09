package com.stash.data.spotify

import com.stash.core.common.ARTIST_CREDIT_ALLOWLIST
import com.stash.core.common.TrackCanonical
import com.stash.core.common.VersionWords
import com.stash.core.common.YouTubeSongTitle
import com.stash.core.common.primaryArtist
import com.stash.core.common.splitArtistCredits
import com.stash.core.model.Track
import java.text.Normalizer
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
 * visibly wrong video. Every ambiguity therefore fails closed. Most library
 * rows are not Spotify rows (radio, addons, YouTube), so their raw title is
 * YouTube-shaped: [YouTubeSongTitle.parse] separates the artist, song and
 * version before [TrackCanonical] decides whether the two name one recording.
 */
internal object SpotifyCanvas {

    /** A resolved Canvas URL stays warm for a day. */
    const val POSITIVE_TTL_MS = 24L * 60 * 60 * 1000

    /** "No Canvas" stays cold for ten minutes, then is asked again. */
    const val NEGATIVE_TTL_MS = 10L * 60 * 1000

    /** The in-memory cache is bounded so a long library walk can't grow it without end. */
    const val CACHE_MAX_ENTRIES = 200

    /**
     * A candidate is the same recording when its length is within this. YouTube
     * uploads pad the video (intro/outro) by a few seconds, so the two-second
     * master tolerance would reject the right row.
     */
    private const val DURATION_TOLERANCE_MS = 10_000L

    /** Two masters whose listed lengths differ by more than this are not the same pressing. */
    private const val DUPLICATE_TOLERANCE_MS = 2_000L

    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")
    private val WHITESPACE = Regex("""\s+""")
    private val COMBINING_MARKS = Regex("""\p{Mn}+""")

    /**
     * Punctuation that carries no identity but must not swallow the brackets or
     * the spaced dash [TrackCanonical.version] reads as a version. Apostrophes
     * are removed separately, so "Don't" and "Dont" compare equal.
     */
    private val TITLE_NOISE = Regex("""[^\p{L}\p{N}\s()\[\]\-–—]""")

    private val TOPIC_SUFFIX = Regex("""\s*-\s*topic\s*$""", RegexOption.IGNORE_CASE)
    private val VEVO_SUFFIX = Regex("""vevo\s*$""", RegexOption.IGNORE_CASE)
    private val OFFICIAL_SUFFIX = Regex("""\s+official\s*$""", RegexOption.IGNORE_CASE)

    /** Why [pickCandidate] accepted or abstained, for a URL-free debug log. */
    internal data class CandidatePick(val candidate: SpotifyTrackCandidate?, val reason: String)

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
     * Search queries for a row without a Spotify URI, broadest last:
     *  - raw title + raw artist (what the old lookup sent),
     *  - the [YouTubeSongTitle.parse]d title (with its version) + artist,
     *  - parsed title + primary artist,
     *  - parsed title alone.
     *
     * Blank queries and case-insensitive duplicates are dropped.
     */
    fun searchQueries(track: Track): List<String> {
        val parsed = YouTubeSongTitle.parse(track.title, track.artist)
        val artist = parsed.artist.ifBlank { cleanChannel(track.artist) }
        return listOf(
            join(track.title, track.artist),
            join(parsed.queryTitle, artist),
            join(parsed.queryTitle, artist.primaryArtist()),
            parsed.title,
        )
            .map { WHITESPACE.replace(it, " ").trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
    }

    /**
     * The one searched candidate that is unambiguously this recording.
     *
     * @return the candidate, or null when nothing or more than one distinct
     *   recording matches.
     */
    fun exactCandidate(track: Track, candidates: List<SpotifyTrackCandidate>): SpotifyTrackCandidate? =
        pickCandidate(track, candidates).candidate

    /**
     * [exactCandidate], also returning why it abstained. The reason names a
     * comparison (title, version, artist, duration, ambiguity), never a
     * candidate id or URI.
     *
     * A candidate matches when:
     *  - its title base agrees with the parsed track title,
     *  - [TrackCanonical.sameVersion] and [TrackCanonical.sameTake] agree, so a
     *    live/remix/acoustic/... version is only accepted when the row names
     *    the same version, while presentation/remaster tags are ignored,
     *  - every artist credited on the track is present on the candidate,
     *  - lengths are within [DURATION_TOLERANCE_MS] when both are known.
     *
     * Duplicate masters (same canonical title and artist, lengths within
     * [DUPLICATE_TOLERANCE_MS]) are one recording: the closest length wins.
     * Distinct recordings, or two masters further apart than that, abstain.
     */
    fun pickCandidate(track: Track, candidates: List<SpotifyTrackCandidate>): CandidatePick {
        if (candidates.isEmpty()) return CandidatePick(null, "no-candidates")

        val parsed = YouTubeSongTitle.parse(track.title, track.artist)
        val parsedTitle = parsed.queryTitle.trim().ifBlank { track.title.trim() }
        if (TrackCanonical.title(foldTitle(parsedTitle)).isBlank()) {
            return CandidatePick(null, "no-title")
        }
        val wantedArtists = artistSet(parsed.artist.ifBlank { track.artist })
        if (wantedArtists.isEmpty()) return CandidatePick(null, "no-artist")

        val seenIds = HashSet<String>()
        val accepted = ArrayList<SpotifyTrackCandidate>()
        var firstReason: String? = null

        for (candidate in candidates) {
            if (!seenIds.add(candidate.id)) continue
            val reason = rejectReason(track, parsedTitle, wantedArtists, candidate)
            if (reason == null) {
                accepted += candidate
            } else if (firstReason == null) {
                firstReason = reason
            }
        }

        if (accepted.isEmpty()) return CandidatePick(null, firstReason ?: "no-match")
        val chosen = closestMaster(track.durationMs, accepted)
            ?: return CandidatePick(null, "ambiguous-recordings")
        return CandidatePick(chosen, "matched")
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

    /**
     * Lowercase, accent-folded, apostrophe-free text for cache keys and the
     * loose comparisons that don't need [TrackCanonical]'s version split.
     */
    fun normalize(value: String): String =
        foldAccents(value.lowercase(Locale.ROOT))
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
            .replace('\u201c', '"')
            .replace('\u201d', '"')
            .replace('\u2013', '-')
            .replace('\u2014', '-')
            .replace("'", "")
            .replace(NON_ALNUM, " ")
            .trim()

    /** The separate artist credits in a billing line ("A, B feat. C"). */
    fun artistParts(value: String): List<String> = value.splitArtistCredits()

    /** Null when [candidate] is this recording, else a short reason word. */
    private fun rejectReason(
        track: Track,
        parsedTitle: String,
        wantedArtists: Set<String>,
        candidate: SpotifyTrackCandidate,
    ): String? {
        val titleReason = titleReason(track.title, parsedTitle, candidate.name)
        if (titleReason != null) return titleReason

        val candidateArtists = artistSet(candidate.artists.joinToString(", "))
        if (!candidateArtists.containsAll(wantedArtists)) return "artist"

        if (!durationCompatible(track.durationMs, candidate.durationMs)) return "duration"
        return null
    }

    /**
     * The base comes from the parsed title (artist prefix and upload noise
     * removed); the version words come from the raw track title, so a version
     * YouTubeSongTitle moved out of the title is not lost. Version text keeps
     * apostrophes ("Taylor's Version"), while bases drop them ("Don't"/"Dont").
     */
    private fun titleReason(rawTrackTitle: String, parsedTitle: String, candidateTitle: String): String? {
        val wantedBase = TrackCanonical.version(foldTitle(parsedTitle)).base
        val theirBase = TrackCanonical.version(foldTitle(candidateTitle)).base
        if (wantedBase.isBlank() || wantedBase != theirBase) return "title"

        val rawVersion = TrackCanonical.version(foldVersion(rawTrackTitle))
        val theirVersion = TrackCanonical.version(foldVersion(candidateTitle))
        if (rawVersion.words != theirVersion.words) return "version"

        val rawTake = TrackCanonical.version(foldVersion(rawTrackTitle), VersionWords::forCatalogMatch)
        val theirTake = TrackCanonical.version(foldVersion(candidateTitle), VersionWords::forCatalogMatch)
        if (rawTake.words != theirTake.words) return "version"
        return null
    }

    /**
     * The one recording among [accepted], or null when they are distinct.
     * Candidates with the same canonical title/artist and lengths within
     * [DUPLICATE_TOLERANCE_MS] are duplicate pressings of one master.
     */
    private fun closestMaster(ours: Long, accepted: List<SpotifyTrackCandidate>): SpotifyTrackCandidate? {
        val groups = accepted.groupBy { candidate ->
            val title = TrackCanonical.version(foldTitle(candidate.name)).base
            val artists = artistSet(candidate.artists.joinToString(", ")).sorted().joinToString(", ")
            "$title|$artists"
        }
        if (groups.size != 1) return null

        val group = groups.values.single()
        val known = group.map { it.durationMs }.filter { it > 0 }
        val spread = (known.maxOrNull() ?: 0L) - (known.minOrNull() ?: 0L)
        if (known.isNotEmpty() && spread > DUPLICATE_TOLERANCE_MS) return null

        if (ours <= 0) return group.minByOrNull { it.id }
        return group
            .minWithOrNull(
                compareBy<SpotifyTrackCandidate> { candidate ->
                    if (candidate.durationMs > 0) abs(candidate.durationMs - ours) else Long.MAX_VALUE
                }.thenBy { it.id },
            )
    }

    private fun durationCompatible(ours: Long, theirs: Long): Boolean {
        if (ours <= 0 || theirs <= 0) return true
        return abs(ours - theirs) <= DURATION_TOLERANCE_MS
    }

    /**
     * The acts in a credit string, canonicalized for comparison:
     * [splitArtistCredits] keeps a comma-bearing single act whole, then each
     * part is split on `&`, `/`, `;` and "and" by [TrackCanonical.artist],
     * except an allowlisted act ("Earth, Wind & Fire"), which stays one.
     */
    private fun artistSet(value: String): Set<String> =
        cleanChannel(value).splitArtistCredits()
            .flatMap { part ->
                val folded = foldArtist(part)
                when {
                    folded.isEmpty() -> emptyList<String>()
                    ARTIST_CREDIT_ALLOWLIST.any { it.equals(folded, ignoreCase = true) } ->
                        listOf(folded.lowercase(Locale.ROOT).trim())
                    else -> TrackCanonical.artist(folded).split(", ")
                }
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /** VEVO / "- Topic" / "… Official" uploader names are channels, not artists. */
    private fun cleanChannel(value: String): String =
        value
            .replace(TOPIC_SUFFIX, "")
            .replace(VEVO_SUFFIX, "")
            .replace(OFFICIAL_SUFFIX, "")
            .trim()

    /**
     * Version text: typographic characters folded, but apostrophes and other
     * punctuation left in place for [VersionWords] ("Taylor's Version").
     */
    private fun foldVersion(value: String): String =
        foldTypographic(value)
            .replace(WHITESPACE, " ")
            .trim()

    /**
     * Title text ready for [TrackCanonical.version]: accents folded,
     * apostrophes removed, other punctuation spaced out while brackets and
     * dashes survive (they carry version information).
     */
    private fun foldTitle(value: String): String =
        foldTypographic(value)
            .replace("'", "")
            .replace(TITLE_NOISE, " ")
            .replace(WHITESPACE, " ")
            .trim()

    /** Artist text: accents/apostrophes folded, separators (`&`, `/`, `,`) kept for splitting. */
    private fun foldArtist(value: String): String =
        foldTypographic(value)
            .replace("'", "")
            .replace(WHITESPACE, " ")
            .trim()

    private fun foldTypographic(value: String): String =
        foldAccents(value)
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
            .replace('`', '\'')
            .replace('\u201c', '"')
            .replace('\u201d', '"')
            .replace('\u2013', '-')
            .replace('\u2014', '-')

    private fun foldAccents(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")

    private fun join(title: String, artist: String): String =
        listOf(title, artist).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
}
