package com.stash.core.data.addons

/**
 * Conservative query variants for catalogs whose search dislikes version
 * suffixes and multi-artist credits. Only used after the full query misses.
 *
 * Cleaning strips a trailing `" - … Version"` / `"(… Version)"`; it never
 * touches Remix/Live/Instrumental/Extended/Acoustic, which are different
 * recordings and must keep their full title.
 */
object EclipseAddonQuery {
    private val DASH_VERSION = Regex("(?i)\\s+-\\s+[^-]*\\bversion\\b\\s*$")
    private val PAREN_VERSION = Regex("(?i)\\s*\\([^)]*\\bversion\\b\\)\\s*$")
    private val ARTIST_SEPARATOR =
        Regex("(?i)\\s*,\\s*|\\s*&\\s*|\\s*/\\s*|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+x\\s+")

    /** The title without a trailing version suffix. */
    fun cleanedTitle(title: String): String {
        val cleaned = title
            .replace(DASH_VERSION, "")
            .replace(PAREN_VERSION, "")
            .trim()
        return cleaned.ifEmpty { title.trim() }
    }

    /** The first credited artist, before a comma/&/slash/feat/ft/" x ". */
    fun primaryArtist(artist: String): String =
        artist.split(ARTIST_SEPARATOR).firstOrNull()?.trim().orEmpty().ifEmpty { artist.trim() }

    /**
     * Full query first, then cleaned title + primary artist, then full title +
     * primary artist. Deduplicated and never empty for a non-blank title.
     */
    fun variants(title: String, artist: String): List<Pair<String, String>> {
        val fullTitle = title.trim()
        val fullArtist = artist.trim()
        val cleaned = cleanedTitle(fullTitle)
        val primary = primaryArtist(fullArtist)
        return listOf(
            fullTitle to fullArtist,
            cleaned to primary,
            fullTitle to primary,
        ).filter { it.first.isNotEmpty() }.distinct()
    }
}
