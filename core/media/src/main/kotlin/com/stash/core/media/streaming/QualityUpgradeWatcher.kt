package com.stash.core.media.streaming

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the watcher needs from the player.
 *
 * A seam, so the decisions - when to swap, and when to give up - can be
 * tested without a player and without a network.
 */
interface QualityUpgradePlayer {
    /** The track playing right now; null when nothing is playing. */
    fun currentTrackId(): Long?

    /** Where the song is, in milliseconds. */
    fun currentPositionMs(): Long

    /** How long the song is, in milliseconds; 0 when unknown. */
    fun currentDurationMs(): Long

    /** The stream that is playing right now. */
    fun currentStream(): StreamUrl?

    /** Replace the playing item with [stream] and carry on at [positionMs]. */
    fun swapTo(stream: StreamUrl, positionMs: Long)
}

/**
 * Watches a song that started on a reserve quality and swaps it for the
 * better file as soon as the addon has it.
 *
 * The watch is quiet. Every [POLL_INTERVAL_MS], at most [MAX_CHECKS] times,
 * the addon is asked what it has NOW - the point is that the file it named
 * when the song started may be a better file by the time the song is a few
 * seconds in.
 *
 * Nothing is touched while the song plays. The better file is resolved to a
 * direct url, its bytes are written into the player's own cache around the
 * current position, and only then is the item replaced - at the exact
 * position, so the song does not jump.
 *
 * Two things end the watch without a swap:
 *  - the song is more than [LATE_FRACTION] of the way through. A swap there
 *    would cost more than it gives.
 *  - the pre-buffer fails or takes too long. The song keeps the file it has,
 *    and the next play starts on the better one.
 */
class QualityUpgradeWatcher(
    private val scope: CoroutineScope,
    private val player: QualityUpgradePlayer,
    /** The addon's answer for a track, asked fresh. */
    private val resolveFresh: suspend (Long) -> StreamUrl?,
    /** Writes the bytes around the position into the player's cache. */
    private val warmAround: suspend (Long, StreamUrl, Long, Long) -> Boolean,
    /** Called when the watch is over, however it ended. */
    private val onFinished: (Long) -> Unit = {},
) {

    /** Watch [trackId]: look for a better file for it, and swap it in if one turns up. */
    fun watch(trackId: Long): Job = scope.launch {
        try {
            watchLoop(trackId)
        } finally {
            onFinished(trackId)
        }
    }

    private suspend fun watchLoop(trackId: Long) {
        for (attempt in 1..MAX_CHECKS) {
            delay(POLL_INTERVAL_MS)
            val playing = player.currentTrackId() ?: return
            // Another song took over: this watch is over.
            if (playing != trackId) return
            val position = player.currentPositionMs()
            if (isTooLate(player.currentDurationMs(), position)) return

            val candidate = try {
                resolveFresh(trackId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the fresh resolve failed for $trackId: ${e.message}")
                null
            } ?: continue

            val current = player.currentStream() ?: continue
            if (!isBetter(candidate, current)) continue

            val warmed = withTimeoutOrNull(PREBUFFER_TIMEOUT_MS) {
                warmAround(trackId, candidate, position, player.currentDurationMs())
            } == true
            if (!warmed) {
                Log.i(TAG, "the better file for $trackId was not buffered; the song keeps what it has")
                return
            }

            // The song moved on while the bytes were fetched.
            val now = player.currentPositionMs()
            if (isTooLate(player.currentDurationMs(), now)) return

            Log.i(TAG, "track $trackId moves to the better file at ${now}ms")
            player.swapTo(candidate, now)
            return
        }
    }

    companion object {
        private const val TAG = "QualityUpgrade"

        /** How often the watcher looks for the better file. */
        const val POLL_INTERVAL_MS = 3_000L

        /** How many times it looks. */
        const val MAX_CHECKS = 4

        /** Past this share of the song, a swap is not worth it. */
        const val LATE_FRACTION = 0.75

        /** How long the pre-buffer may take before the swap is given up on. */
        const val PREBUFFER_TIMEOUT_MS = 8_000L
    }
}

/**
 * Is the song too far along for a swap?
 *
 * Past three quarters of the way through, the remaining minutes are worth
 * more than the better file: the swap would cost a re-buffer and the song
 * would end on the old file anyway.
 */
internal fun isTooLate(durationMs: Long, positionMs: Long): Boolean {
    if (durationMs <= 0) return false
    return positionMs > durationMs * QualityUpgradeWatcher.LATE_FRACTION
}

/**
 * Is [candidate] a better file than [current]?
 *
 * The tier the source named is the truth when it named one: it says which
 * release the file IS, not merely that it is lossless. A source that names no
 * tier is judged by what the stream says about itself.
 */
internal fun isBetter(candidate: StreamUrl, current: StreamUrl): Boolean =
    streamQualityRank(candidate) > streamQualityRank(current)

/**
 * A stream's quality, as a number: higher is better.
 *
 * Lossless is worth more than any tier below it, and a named tier is worth
 * more than what a stream can say about itself - "Hi-Res" says more than
 * "FLAC" does.
 */
internal fun streamQualityRank(stream: StreamUrl): Int {
    val lossless = if (stream.isLosslessCodec()) LOSSLESS_BASE else 0
    val tier = stream.qualityTier?.trim()?.lowercase()
        ?.let { EclipseAddonStreamResolver.TIER_RANK[it] }
    val detail = tier?.times(TIER_STEP)
        ?: (stream.bitsPerSample ?: 0) * 100 + (stream.sampleRateHz ?: 0) / 100 + (stream.bitrateKbps ?: 0)
    return lossless + detail
}

/** The codecs that carry the recording without throwing anything away. */
internal fun StreamUrl.isLosslessCodec(): Boolean =
    codec.equals("flac", ignoreCase = true) ||
        codec.equals("alac", ignoreCase = true) ||
        codec.equals("wav", ignoreCase = true) ||
        codec.equals("aiff", ignoreCase = true) ||
        codec.equals("ape", ignoreCase = true) ||
        (bitsPerSample ?: 0) > 0

/** Lossless is worth more than every tier below it. */
private const val LOSSLESS_BASE = 10_000

/** One tier step. */
private const val TIER_STEP = 1_000
