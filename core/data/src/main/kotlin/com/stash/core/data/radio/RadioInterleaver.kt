package com.stash.core.data.radio

/**
 * Pure ordering of a radio candidate pool into play order.
 *
 * Groups candidates by artist (preserving each artist's own order), then draws
 * across the groups with smooth weighted round-robin: an artist's "credit"
 * accumulates by its weight each slot; the highest-credit artist that is not
 * the previous one is picked and pays 1 credit. This yields frequency ∝ weight.
 *
 * Anti-drift rules, all deterministic and network-free:
 *  - **Match floor**: a widen slice can drop candidates whose match score is
 *    below [matchFloor], so a weak "similar" artist never dilutes the station.
 *  - **Hop decay**: an artist's effective weight is multiplied by
 *    [HOP_DECAY]^[RadioCandidate.hop], so direct neighbours lead and two- or
 *    three-hop discoveries drift in later.
 *  - **Artist cooldown**: the same artist is not picked within [ARTIST_COOLDOWN]
 *    slots while another artist has a candidate. Beyond that hard window a
 *    soft penalty (up to [COOLDOWN_PENALTY_MAX]) keeps recently played artists
 *    from immediately dominating again without starving them.
 *  - **Skip-event penalty**: every skip recorded for an artist divides its
 *    weight, so skipped artists recede on later orders.
 */
object RadioInterleaver {
    /** Weight multiplier applied per artist-graph hop. */
    const val HOP_DECAY = 0.85f

    /** Hard no-repeat window: no artist within this many previous slots. */
    const val ARTIST_COOLDOWN = 3

    /** Soft cooldown length; the penalty decays to zero by this slot distance. */
    const val SOFT_COOLDOWN = 10

    /** Maximum credit penalty for an artist played in the immediately previous slot. */
    const val COOLDOWN_PENALTY_MAX = 10f

    /** Each recorded skip divides an artist's weight by `1 + this * skipCount`. */
    const val SKIP_EVENT_PENALTY = 2f

    /**
     * @param matchFloor candidates with a raw [RadioCandidate.weight] below this
     *   are discarded (used per widen slice; 0 keeps everything).
     * @param skippedArtists skip counts by artist (see [RadioSession.skippedArtists]).
     */
    fun order(
        pool: List<RadioCandidate>,
        matchFloor: Float = 0f,
        skippedArtists: Map<String, Int> = emptyMap(),
    ): List<RadioCandidate> {
        val eligible = if (matchFloor <= 0f) pool else pool.filter { it.weight >= matchFloor }
        if (eligible.size <= 1) return eligible

        // Per-artist FIFO queues, and each artist's effective weight. Candidates
        // of one artist share the artist's weight; take the strongest (a seed
        // candidate at hop 0 beats a distant rediscovery of the same artist).
        val queues = LinkedHashMap<String, ArrayDeque<RadioCandidate>>()
        val weights = LinkedHashMap<String, Float>()
        for (c in eligible) {
            queues.getOrPut(c.artist) { ArrayDeque() }.addLast(c)
            val effective = effectiveWeight(c)
            weights[c.artist] = maxOf(weights[c.artist] ?: 0f, effective)
        }
        // A skip is an artist-level signal, not a network call.
        for ((artist, count) in skippedArtists) {
            val current = weights[artist] ?: continue
            weights[artist] = current / (1f + SKIP_EVENT_PENALTY * count)
        }
        // Very small weights are valid (a weak Last.fm match), but a zero weight
        // would make the credit loop never accumulate; keep the scheme stable.
        for (artist in weights.keys.toList()) weights[artist] = weights.getValue(artist).coerceAtLeast(0.01f)

        val credit = HashMap<String, Float>().apply { queues.keys.forEach { this[it] = 0f } }
        val out = ArrayList<RadioCandidate>(eligible.size)
        val recentArtists = ArrayDeque<String>(ARTIST_COOLDOWN)
        val lastPlayedAt = HashMap<String, Int>()
        repeat(eligible.size) {
            for (a in queues.keys) if (queues.getValue(a).isNotEmpty()) {
                credit[a] = credit.getValue(a) + weights.getValue(a)
            }
            val nonEmpty = queues.keys.filter { queues.getValue(it).isNotEmpty() }
            // Hard cooldown: avoid the last ARTIST_COOLDOWN artists while any
            // other artist still has a track. Fall back to all only when the
            // recent ones are literally the only material left.
            val allowed = nonEmpty.filter { it !in recentArtists }.ifEmpty { nonEmpty }
            val slot = out.size
            val pick = allowed.maxByOrNull { score(it, credit, lastPlayedAt, slot) } ?: nonEmpty.first()
            out.add(queues.getValue(pick).removeFirst())
            credit[pick] = credit.getValue(pick) - 1f
            lastPlayedAt[pick] = slot
            recentArtists.addLast(pick)
            if (recentArtists.size > ARTIST_COOLDOWN) recentArtists.removeFirst()
        }
        return out
    }

    private fun effectiveWeight(c: RadioCandidate): Float {
        var decay = 1f
        repeat(c.hop.coerceAtLeast(0)) { decay *= HOP_DECAY }
        return c.weight.coerceAtLeast(0f) * decay
    }

    /**
     * Base credit minus a soft cooldown penalty that decays with distance:
     * 10 in the immediately previous slot, then 9, 8 … 1 ten slots back,
     * and 0 beyond [SOFT_COOLDOWN].
     */
    private fun score(
        artist: String,
        credit: Map<String, Float>,
        lastPlayedAt: Map<String, Int>,
        slot: Int,
    ): Float {
        val base = credit.getValue(artist)
        val last = lastPlayedAt[artist] ?: return base
        val distance = slot - last
        if (distance <= 0 || distance > SOFT_COOLDOWN) return base
        val penalty = COOLDOWN_PENALTY_MAX * (SOFT_COOLDOWN - distance + 1) / SOFT_COOLDOWN
        return base - penalty
    }
}
