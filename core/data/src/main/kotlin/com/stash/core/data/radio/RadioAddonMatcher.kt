package com.stash.core.data.radio

import com.stash.core.data.addons.EclipseAddonQuery
import com.stash.core.data.addons.EclipseAddonTrack
import java.text.Normalizer

/**
 * Picks the addon search hit that really is [RadioCandidate]'s song. Pure and
 * side-effect free, so the rules are unit-testable without HTTP.
 *
 * `/resolve` misses songs its catalogue still holds (multi-artist credits,
 * version labels), so the radio generator falls back to `/search`; this matcher
 * decides whether any hit is the same recording:
 *  - the normalised title must be equal, or both sides must reduce to the same
 *    core once a trailing version/edit/remaster suffix is stripped;
 *  - the artist token sets must overlap, with at least half of the smaller set
 *    shared;
 *  - a live/remix/instrumental marker on one side only is a different recording.
 *
 * Among the survivors the exactly-titled one wins, then the first hit. (A
 * [RadioCandidate] carries no duration today; when it gains one, the closest
 * duration should break this tie ahead of declaration order.)
 */
internal object RadioAddonMatcher {
    private val MARKERS = listOf("live", "remix", "instrumental")

    /**
     * A trailing edit/remaster label in either spelling a catalogue uses:
     * `- Radio Edit`, `(Radio Edit)`, `- Edit`, `(Edit)`, `- Remastered 2011`,
     * `(Remaster…)`. Only the label differs; the recording is the same one.
     * The word boundaries keep titles that merely END in such letters
     * ("Credit") from being truncated.
     */
    private val EDIT_SUFFIX = Regex(
        "(?i)\\s*[-–—]?\\s*\\(?\\s*(?:\\bradio\\s+edit\\b|\\bedit\\b|\\bremaster(?:ed)?\\b(?:\\s+[^)]*)?)\\s*\\)?\\s*$",
    )

    fun bestAddonSearchHit(cand: RadioCandidate, hits: List<EclipseAddonTrack>): EclipseAddonTrack? {
        val title = normalize(cand.title)
        if (title.isEmpty()) return null
        val core = coreTitle(cand.title)
        val markers = markers(cand.title)
        val survivors = hits.filter { hit ->
            hit.id.isNotBlank() &&
                titleMatches(hit.title, title, core) &&
                markers(hit.title) == markers &&
                artistMatches(cand.artist, hit.artist)
        }
        return survivors.firstOrNull { normalize(it.title) == title } ?: survivors.firstOrNull()
    }

    /** Lowercase, de-accented, punctuation-free, single-spaced; `&` reads as "and". */
    internal fun normalize(raw: String): String {
        val folded = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace("&", " and ")
        return folded
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun titleMatches(raw: String, normalizedTarget: String, targetCore: String): Boolean {
        if (normalize(raw) == normalizedTarget) return true
        return targetCore.isNotEmpty() && coreTitle(raw) == targetCore
    }

    /** The title with a trailing version suffix AND an edit/remaster label removed. */
    private fun coreTitle(raw: String): String =
        normalize(EDIT_SUFFIX.replace(EclipseAddonQuery.cleanedTitle(raw), ""))

    private fun markers(title: String): Set<String> {
        val lower = title.lowercase()
        return MARKERS.filter { Regex("\\b$it\\b").containsMatchIn(lower) }.toSet()
    }

    private fun artistMatches(a: String, b: String): Boolean {
        val left = normalize(a).split(' ').filter { it.isNotEmpty() }.toSet()
        val right = normalize(b).split(' ').filter { it.isNotEmpty() }.toSet()
        if (left.isEmpty() || right.isEmpty()) return false
        val shared = left.intersect(right)
        return shared.isNotEmpty() && shared.size * 2 >= minOf(left.size, right.size)
    }

    /** The same artist-overlap rule the generator uses to keep artist-search hits. */
    internal fun artistOverlaps(a: String, b: String): Boolean = artistMatches(a, b)
}
