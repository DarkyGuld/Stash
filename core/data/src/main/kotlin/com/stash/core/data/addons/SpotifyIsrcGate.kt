package com.stash.core.data.addons

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shares one Spotify ISRC lane between foreground playback and background
 * backfill: playback (and a per-play lookup) always wins. A 429 sets a persisted
 * backoff, so a process restart does not resume hammering.
 */
@Singleton
class SpotifyIsrcGate @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("spotify_isrc_gate", Context.MODE_PRIVATE)
    private val foreground = AtomicInteger(0)

    /** Set by the playback service while audio is playing. */
    @Volatile var playbackActive: Boolean = false

    fun beginForeground() { foreground.incrementAndGet() }
    fun endForeground() { foreground.decrementAndGet() }
    fun foregroundActive(): Boolean = foreground.get() > 0

    /** No backfill while audio is playing, a per-play lookup runs, or a 429 backoff is armed. */
    fun backfillAllowed(): Boolean =
        !playbackActive && !foregroundActive() && System.currentTimeMillis() >= backoffUntilMs()

    fun backoffUntilMs(): Long = prefs.getLong(KEY_BACKOFF_UNTIL, 0L)

    fun setBackoff(seconds: Long) {
        val until = System.currentTimeMillis() + seconds.coerceAtLeast(60L) * 1_000L
        prefs.edit().putLong(KEY_BACKOFF_UNTIL, until).apply()
    }

    fun clearBackoff() {
        prefs.edit().remove(KEY_BACKOFF_UNTIL).apply()
    }

    private companion object {
        const val KEY_BACKOFF_UNTIL = "backoff_until_ms"
    }
}
