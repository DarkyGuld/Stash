package com.stash.feature.nowplaying.canvas

import android.content.Context
import com.stash.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A small on-disk record of songs the Spotify data layer already said have no Canvas.
 *
 * `SpotifyApiClient.canvasUrl` keeps its own short-lived in-memory answer, but Now Playing is
 * opened again and again across launches: a miss is worth remembering there too, so a song without
 * a Canvas is not looked up once per play session. Ten minutes matches the resolver's own negative
 * lifetime; the store is bounded and entries are pruned as they are written.
 */
@Singleton
class CanvasNegativeCache @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True when this song was recently answered "no Canvas". */
    fun isNegative(track: Track, now: Long = System.currentTimeMillis()): Boolean {
        val key = key(track)
        val at = prefs.getLong(key, 0L)
        if (at <= 0L) return false
        if (now - at > TTL_MS) {
            prefs.edit().remove(key).apply()
            return false
        }
        return true
    }

    /** Remembers a miss for [TTL_MS], dropping the oldest entries if the store has grown. */
    fun markNegative(track: Track, now: Long = System.currentTimeMillis()) {
        val all = prefs.all
        if (all.size >= MAX_ENTRIES) {
            val oldest = all.entries
                .filter { it.value is Long }
                .sortedBy { it.value as Long }
                .take((all.size - MAX_ENTRIES + 1).coerceAtLeast(1))
                .map { it.key }
            val editor = prefs.edit()
            oldest.forEach { editor.remove(it) }
            editor.apply()
        }
        prefs.edit().putLong(key(track), now).apply()
    }

    /** Forgets a song's miss (used when the setting is turned back on). */
    fun clear(track: Track) {
        prefs.edit().remove(key(track)).apply()
    }

    /**
     * The key is the same identity the data layer accepts: the Spotify URI when known, else the
     * normalized title and artist. A Canvas belongs to a recording, so both are stable across
     * launches even when the player's numeric id is transient.
     */
    private fun key(track: Track): String {
        val uri = track.spotifyUri?.trim().orEmpty()
        if (uri.isNotEmpty()) return "uri:$uri"
        return "meta:${normalize(track.title)}|${normalize(track.artist)}"
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(NON_ALNUM, " ").trim()

    private companion object {
        const val PREFS_NAME = "canvas_negative_cache"
        const val TTL_MS = 10L * 60 * 1000
        const val MAX_ENTRIES = 300
        val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")
    }
}
