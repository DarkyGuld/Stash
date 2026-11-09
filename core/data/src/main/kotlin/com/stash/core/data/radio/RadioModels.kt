package com.stash.core.data.radio

import com.stash.core.data.addons.EclipseAddonTrack

/**
 * One potential radio track before YouTube resolution.
 *
 * [videoId] is known for artist-radio picks (they come from a YT `popular`
 * list) and null for song-radio similar-track picks (resolved via
 * `searchCanonicalVideoId` at emit time). [weight] is the source artist's
 * relative frequency (seed share, or a neighbor's Last.fm match score); the
 * interleaver uses it to decide how often this candidate's artist appears.
 *
 * [hop] is how many artist-graph steps away from the seed the candidate came
 * from (0 = seed, 1 = direct neighbour, 2+ = a widen slice). The interleaver
 * decays a candidate's effective weight by [RadioInterleaver.HOP_DECAY] per
 * hop, so the station opens recognisable and drifts outward instead of
 * looping the same neighbours.
 *
 * [durationMs] is known when the source supplies it (YT Popular/InnerTube or an
 * addon hit) and [addonTrack] is set only when the candidate came from an
 * addon artist search. Carrying the hit lets the generator emit it without a
 * second `resolve`/`search` round-trip (which also preserves the catalogue's
 * artwork, duration and artist metadata).
 */
data class RadioCandidate(
    val artist: String,
    val title: String,
    val videoId: String?,
    val weight: Float,
    val thumbnailUrl: String? = null,
    val hop: Int = 0,
    val durationMs: Long = 0L,
    val addonTrack: EclipseAddonTrack? = null,
)

/** Stable per-station identity for the no-repeat set. */
internal fun RadioCandidate.identity(): String =
    videoId?.takeIf { it.isNotBlank() }
        ?: (artist.trim().lowercase() + "|" + title.trim().lowercase())

/**
 * Live, in-memory state of one station. Mutable cursor + no-repeat set; not
 * persisted (a process kill ends the station — queued tracks still play out).
 * Created and mutated only by [RadioStationGenerator]; consumed by the player.
 */
class RadioSession internal constructor(
    internal val seed: RadioSeed,
    internal val ordered: MutableList<RadioCandidate>,
    internal val played: MutableSet<String>,   // identity() + resolved-videoId keys already emitted
) {
    internal var cursor: Int = 0

    /** True once the pool is exhausted and no further widening is possible. */
    internal var exhausted: Boolean = false

    /**
     * Neighbors not yet used, consumed NEIGHBOR_POOL at a time by widening.
     * This is what keeps the station extending: artist radio fills it at start
     * (with the neighbors AFTER the first pool); song radio lazy-loads it on the
     * first widen. Draining it — not re-fetching the same top-N — is the fix for
     * "widen returns the same already-played neighbors."
     */
    internal val remainingNeighbors: ArrayDeque<com.stash.core.data.lastfm.LastFmSimilarArtist> = ArrayDeque()
    internal var neighborsLoaded: Boolean = false

    /**
     * Artist names discovered from addon search results, with the weight they
     * inherit. This is the "local graph" used when Last.fm has no key or has no
     * more neighbours: it lets an addon-only station keep widening instead of
     * stopping after the first artist pool.
     */
    internal val addonNeighbors: ArrayDeque<Pair<String, Float>> = ArrayDeque()
    internal val addonNeighborsSeen: MutableSet<String> = mutableSetOf()

    /** How many widen slices have been appended; feeds [RadioCandidate.hop]. */
    internal var widenCount: Int = 0

    /**
     * Per-artist skip counts. The user's transport actions are not network
     * calls, so the ordering layer can consume them deterministically: a
     * skipped artist is down-weighted on every later order/widen.
     */
    internal val skippedArtists: MutableMap<String, Int> = mutableMapOf()

    /** Record a user skip so the ordering layer can penalise that artist. */
    fun noteSkipped(artist: String) {
        val key = artist.trim()
        if (key.isNotEmpty()) skippedArtists[key] = (skippedArtists[key] ?: 0) + 1
    }
}
