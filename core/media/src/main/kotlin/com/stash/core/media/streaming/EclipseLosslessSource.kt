package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.addons.EclipseAddonRepository
import com.stash.core.data.db.dao.TrackDao
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Eclipse addons as a download source. It reuses [EclipseAddonStreamResolver],
 * so downloads get the same behaviour as playback: enabled-addon gating,
 * fast mode (Tidal/Apple race), the persistent Apple id and URL caches, and the
 * direct Qobuz FLAC path.
 *
 * Only progressive files are downloadable here. An HLS/DASH answer returns null
 * (a segmented download is a separate piece of work), so the chain can try the
 * next source. The user's own addon is not a community aggregator: there is no
 * [com.stash.data.download.lossless.AggregatorRateLimiter] token for it.
 */
@Singleton
class EclipseLosslessSource @Inject constructor(
    private val repository: EclipseAddonRepository,
    private val resolver: EclipseAddonStreamResolver,
    private val trackDao: TrackDao,
) : LosslessSource {
    override val id: String = ID
    override val displayName: String = "Eclipse addons"

    override suspend fun isEnabled(): Boolean =
        try {
            repository.enabledNow().any { it.enabled && (it.isEclipseCloud || it.supportsResolve) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

    override suspend fun resolve(query: TrackQuery, bypassRateLimit: Boolean): SourceResult? {
        // The resolver works on the real row (metadata, ISRC, duration) — not the query copy.
        val trackId = query.trackId ?: return null
        val row = try {
            trackDao.getById(trackId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null

        val stream = try {
            resolver.resolve(row)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A 403/404 already reads as a miss inside the resolver; anything else is one too.
            Log.d(TAG, "addon resolve failed for ${row.id}: ${e::class.java.simpleName}")
            null
        } ?: return null

        if (stream.isHls || stream.isDash || stream.url.isBlank()) return null
        val codec = stream.codec?.lowercase()?.takeIf { it.isNotBlank() } ?: extensionFor(stream.url)
        return SourceResult(
            sourceId = id,
            downloadUrl = stream.url,
            // A direct, already-signed Qobuz/file URL needs no headers of ours.
            downloadHeaders = emptyMap(),
            format = AudioFormat(
                codec = codec,
                bitrateKbps = stream.bitrateKbps ?: 0,
                sampleRateHz = stream.sampleRateHz ?: 0,
                bitsPerSample = stream.bitsPerSample ?: 0,
                fileExtension = extensionFor(stream.url, codec),
            ),
            confidence = 1.0f,
        )
    }

    /** Not an aggregator: nothing to throttle, always idle. */
    override suspend fun rateLimitState(): RateLimitState = IDLE

    private fun extensionFor(url: String, codec: String? = null): String {
        val candidate = codec ?: url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return candidate.takeIf { it in EXTENSIONS } ?: "flac"
    }

    companion object {
        /** Must match [com.stash.data.download.lossless.LosslessAvailability]'s row id. */
        const val ID = "eclipse-addon"

        private const val TAG = "EclipseLosslessSource"
        private val EXTENSIONS = setOf("flac", "mp3", "m4a", "aac", "opus", "ogg", "wav", "alac")

        private val IDLE = RateLimitState(
            tokensAvailable = 1.0,
            msUntilNextToken = 0L,
            isCircuitBroken = false,
            msUntilUnblock = 0L,
            recentFailures = 0,
        )
    }
}
