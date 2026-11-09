package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.addons.EclipseAddon
import com.stash.core.data.addons.EclipseAddonException
import com.stash.core.data.addons.EclipseAddonQuery
import com.stash.core.data.addons.EclipseAddonRepository
import com.stash.core.data.addons.EclipseAddonStream
import com.stash.core.data.addons.EclipseAddonTrack
import com.stash.core.data.addons.EclipseAppleLookupStore
import com.stash.core.data.addons.EclipseAppleTrack
import com.stash.core.data.addons.EclipseIsrcEnricher
import com.stash.core.data.db.entity.TrackEntity
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Resolves a track through the installed Eclipse addons, in the user's order.
 *
 * Per addon: `/resolve` first when it supports it (Eclipse's metadata → item
 * lookup, ISRC authoritative), then `/resolve-isrc` when the manifest lists
 * `isrc`; if neither yields an item and the addon also does `search`, the
 * search hits go through [EclipseAddonMatcher]. The chosen id is exchanged for
 * a playable URL. Any failure — offline, a timeout, a 503 "preparing", a 403
 * "Unavailable" — just moves on. [resolve] never throws.
 */
@Singleton
class EclipseAddonStreamResolver private constructor(
    private val repository: EclipseAddonRepository,
    private val resolveCache: EclipseAddonResolveCache,
    /** Read per resolve: Cloud's "fastest source" switch (race Tidal and Apple). */
    private val cloudFastestSource: suspend () -> Boolean,
    /** Apple ids that survive restarts; null in hand-built tests. */
    private val appleLookup: EclipseAppleLookupStore?,
    /** The player's in-memory URL cache; null in hand-built tests. */
    private val urlCache: StreamUrlCache?,
    /** Resolved addon URLs on disk; null in hand-built tests. */
    private val streamUrlStore: EclipseStreamUrlStore?,
    /** ISRC enrichment for Cloud's Apple route; null in hand-built tests. */
    private val isrcEnricher: EclipseIsrcEnricher?,
) {
    /** Apple ids that 404'd at every tier this session: never request them again. */
    private val deadAppleIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Inject
    constructor(
        repository: EclipseAddonRepository,
        streamingPreference: com.stash.core.data.prefs.StreamingPreference,
        appleLookup: EclipseAppleLookupStore,
        urlCache: StreamUrlCache,
        streamUrlStore: EclipseStreamUrlStore,
        isrcEnricher: EclipseIsrcEnricher,
    ) : this(
        repository,
        EclipseAddonResolveCache(),
        streamingPreference::isCloudFastestSource,
        appleLookup,
        urlCache,
        streamUrlStore,
        isrcEnricher,
    )

    /** Hand-built tests: Tidal-first, as the default switch does. */
    internal constructor(repository: EclipseAddonRepository) :
        this(repository, EclipseAddonResolveCache(), { false }, null, null, null, null)

    suspend fun resolve(track: TrackEntity, speculative: Boolean = false): StreamUrl? {
        val query = "${track.artist} ${track.title}".trim()
        if (query.isEmpty()) return null
        val startedNs = System.nanoTime()

        val addons = enabledAddons()
        memoryCachedUrl(track, addons)?.let { return it }
        for (addon in addons) {
            try {
                // Disk below memory: a resolved URL that outlived the process, while
                // still valid and still from an enabled addon in this walk.
                persistentUrl(addon, track)?.let { return it }
                if (addon.isEclipseCloud) {
                    // Cloud: Tidal and the Apple catalog are looked up together, so a
                    // Tidal miss does not pay the iTunes search latency.
                    cloudResolve(addon, track, startedNs, speculative)?.let { return it }
                    continue
                }

                // The item id is cached per addon + settings; a hit skips /resolve entirely.
                val key = resolveCacheKey(addon, track)
                resolveAndStream(addon, track, key, startedNs)?.let { return it }

                // Only when the manifest advertised it: a resolve-only addon has no search.
                if (addon.supportsSearch) {
                    val candidates = repository.search(addon, query, SEARCH_LIMIT)
                    val match = EclipseAddonMatcher.best(track, candidates)
                    if (match == null && candidates.isNotEmpty()) {
                        Log.d(
                            TAG,
                            "addon '${addon.name}' rejected ${candidates.size} search hit(s) for " +
                                "'${track.title}' / '${track.artist}' / ${track.durationMs} ms",
                        )
                        continue
                    }
                    if (match == null) continue
                    val url = mapStream(streamWithRetry(addon, match.id)) ?: continue
                    return served(addon, track, url, startedNs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "addon '${addon.name}' failed for ${track.id}: ${failureSummary(e)}")
            }
        }
        return null
    }

    /**
     * Cloud's two routes. The iTunes search always runs in parallel with the
     * Tidal lookup. "Cloud: fastest source" on races two full pipelines and
     * takes the first strong result; off is Tidal-first, with Apple only on a
     * Tidal miss (its search already in flight) — never a second search.
     */
    private suspend fun cloudResolve(
        addon: EclipseAddon,
        track: TrackEntity,
        startedNs: Long,
        speculative: Boolean,
    ): StreamUrl? = coroutineScope {
        // Start the ISRC lookup immediately, but only Apple waits on it.
        val isrcLookup = async {
            track.isrc?.trim()?.takeIf { it.isNotEmpty() }
                ?: withTimeoutOrNull(ISRC_LOOKUP_TIMEOUT_MS) { isrcEnricher?.isrcFor(track) }
        }
        val isrcProvider: suspend () -> String? = {
            try { isrcLookup.await() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        }
        if (cloudFastestSource()) {
            fastestCloudResolve(addon, track, startedNs, speculative, isrcProvider)
        } else {
            tidalFirstCloudResolve(addon, track, startedNs, speculative, isrcProvider)
        }
    }

    /** Tidal-first (the switch off): today's behaviour, Apple only after a Tidal miss. */
    private suspend fun tidalFirstCloudResolve(
        addon: EclipseAddon,
        track: TrackEntity,
        startedNs: Long,
        speculative: Boolean,
        isrcProvider: suspend () -> String?,
    ): StreamUrl? = coroutineScope {
        val tidalKey = resolveCacheKey(addon, track)
        val appleKey = appleCacheKey(addon, track)

        val appleSearch = async { appleHits(addon, track, appleKey, speculative) }
        val tidalLookup = async {
            cachedResolveId(
                addon, track, tidalKey, CLOUD_TIDAL_MISS_TTL_MS,
                bypassNegative = !speculative,
            )
        }

        val tidalId = try {
            tidalLookup.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

        if (tidalId != null) {
            val url = tidalStream(addon, track, tidalId, startedNs)
            if (url != null) {
                // The listener will hear Tidal; nothing Apple was needed.
                appleSearch.cancel()
                return@coroutineScope url
            }
            resolveCache.invalidate(tidalKey)
        }

        val hits = try {
            appleSearch.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        appleStreamFromHits(addon, track, hits, startedNs, isrcProvider)
    }

    /**
     * Fast mode: the Tidal pipeline and the Apple pipeline start together. The
     * first non-null answer wins, its cache entry is committed and the loser is
     * cancelled before it commits anything. Apple may only start its stream for
     * a unique [strongAppleId] that is also the normal `best()` pick.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun fastestCloudResolve(
        addon: EclipseAddon,
        track: TrackEntity,
        startedNs: Long,
        speculative: Boolean,
        isrcProvider: suspend () -> String?,
    ): StreamUrl? = coroutineScope {
        val tidalKey = resolveCacheKey(addon, track)
        val appleKey = appleCacheKey(addon, track)

        val tidal = async { tidalRace(addon, track, tidalKey, startedNs, speculative) }
        val apple = async { appleRace(addon, track, appleKey, startedNs, speculative, isrcProvider) }

        val first = kotlinx.coroutines.selects.select<Pair<kotlinx.coroutines.Deferred<CloudRace?>, CloudRace?>> {
            tidal.onAwait { tidal to it }
            apple.onAwait { apple to it }
        }
        if (first.second != null) {
            (if (first.first === tidal) apple else tidal).cancel()
            first.second!!.commit()
            return@coroutineScope first.second!!.url
        }

        // First finished with a miss: wait for the other; it may still hit.
        val other = if (first.first === tidal) apple else tidal
        val second = try {
            other.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (second != null) {
            second.commit()
            return@coroutineScope second.url
        }
        // Both missed: remember the Apple miss too (the Tidal miss is cached
        // with the long Cloud TTL inside [tidalRace]).
        rememberAppleId(addon, track, null)
        null
    }

    /** One fast-mode branch result: the URL, plus the cache write to run only if it wins. */
    private class CloudRace(val url: StreamUrl, val commit: suspend () -> Unit)

    /** Tidal: cached id or a fresh resolve, then the stream. A miss is cached with the Cloud TTL at once. */
    private suspend fun tidalRace(
        addon: EclipseAddon,
        track: TrackEntity,
        tidalKey: String,
        startedNs: Long,
        speculative: Boolean,
    ): CloudRace? =
        try {
            val entry = resolveCache.get(tidalKey)
            val itemId = when {
                entry?.itemId != null -> entry.itemId
                // A speculative lookup may use a cached miss; a user play re-checks it.
                entry != null && speculative -> null
                else -> resolveId(addon, track)
            }
            if (itemId == null) {
                resolveCache.putMiss(tidalKey, CLOUD_TIDAL_MISS_TTL_MS)
                null
            } else {
                val url = tidalStream(addon, track, itemId, startedNs) ?: run {
                    resolveCache.invalidate(tidalKey)
                    streamUrlStore?.remove(addon.id, settingsKey(addon), identityKey(track))
                    return null
                }
                CloudRace(url) { resolveCache.put(tidalKey, itemId) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /**
     * Apple: at most one iTunes search. The strict [strongAppleId] gate decides
     * whether Apple may win the race *early*; when it declines, this is still the
     * normal Apple fallback and the ordinary [matchAppleId] pick may start its
     * stream. The fast path is an optimisation, never a reason to miss the song.
     */
    private suspend fun appleRace(
        addon: EclipseAddon,
        track: TrackEntity,
        appleKey: String,
        startedNs: Long,
        speculative: Boolean,
        isrcProvider: suspend () -> String?,
    ): CloudRace? =
        try {
            val hits = appleHits(addon, track, appleKey, speculative) ?: return null
            val strongId = strongAppleId(track, hits)
            val appleId = strongId ?: matchAppleId(track, hits) ?: return null
            if (strongId == null) {
                Log.d(TAG, "apple fast-path rejected ${hits.size} hit(s); falling back to best() -> $appleId")
            }
            val url = mapStream(streamAppleWithRetry(addon, appleId, isrcProvider))
                ?.let { served(addon, track, it, startedNs) }
                ?: return null
            CloudRace(url) { rememberAppleId(addon, track, appleId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /** The Tidal stream for a resolved id; a dead id (403/404, outlived preparing) reads as a miss. */
    private suspend fun tidalStream(
        addon: EclipseAddon,
        track: TrackEntity,
        itemId: String,
        startedNs: Long,
    ): StreamUrl? =
        try {
            mapStream(streamWithRetry(addon, itemId))?.let { served(addon, track, it, startedNs) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (invalidatesResolve(e) || e is EclipseAddonException.Unreachable) null else throw e
        }

    /**
     * The cached (or freshly resolved) item id, streamed. A stream answer that
     * invalidates the id (403/404, or a preparing 503 that outlived its one retry)
     * drops the entry and resolves once more — the addon may have rotated ids.
     */
    private suspend fun resolveAndStream(
        addon: EclipseAddon,
        track: TrackEntity,
        key: String,
        startedNs: Long,
    ): StreamUrl? {
        val itemId = cachedResolveId(addon, track, key) ?: return null

        return try {
            mapStream(streamWithRetry(addon, itemId))?.let { served(addon, track, it, startedNs) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!invalidatesResolve(e)) throw e
            resolveCache.invalidate(key)
            streamUrlStore?.remove(addon.id, settingsKey(addon), identityKey(track))
            val fresh = resolveId(addon, track) ?: run {
                resolveCache.put(key, null)
                return null
            }
            resolveCache.put(key, fresh)
            mapStream(streamWithRetry(addon, fresh))?.let { served(addon, track, it, startedNs) }
        }
    }

    /** Cached item id for the Tidal route, or a fresh `/resolve`/`/resolve-isrc` (negative results cached too). */
    private suspend fun cachedResolveId(
        addon: EclipseAddon,
        track: TrackEntity,
        key: String,
        missTtlMs: Long? = null,
        bypassNegative: Boolean = false,
    ): String? {
        val cached = resolveCache.get(key)
        if (cached != null) {
            val id = cached.itemId
            if (id != null || !bypassNegative) return id
            // A user play re-checks a cached Tidal miss; only a new miss is recorded.
            resolveCache.invalidate(key)
        }
        val id = resolveId(addon, track)
        if (id == null && missTtlMs != null) {
            resolveCache.putMiss(key, missTtlMs)
        } else {
            resolveCache.put(key, id)
        }
        return id
    }

    /** A stream answer saying "that id is no longer usable": re-resolve once. */
    private fun invalidatesResolve(e: Exception): Boolean =
        e is EclipseAddonException.Preparing ||
            (e is EclipseAddonException.HttpFailure && e.code in setOf(403, 404, 410))

    /**
     * Cloud's Apple Music fallback: Tidal had no item (or its id died), so search
     * the public iTunes catalog, match it to the track, and stream its Apple id.
     * Never used for a third-party addon. The Apple id is cached in its own key
     * space; the resulting URL is cached by the player/prefetch like any other.
     */
    /**
     * The Apple candidates for this track, from the resolve cache when one is
     * already known (positive id or a short-lived negative), else one iTunes
     * search. Null means "known miss"; an empty list means "searched, no hits"
     * and gets cached as a miss by the caller once matching fails.
     */
    private suspend fun appleHits(
        addon: EclipseAddon,
        track: TrackEntity,
        key: String,
        speculative: Boolean,
    ): List<EclipseAppleTrack>? {
        val cached = cachedAppleId(addon, track, key)
        if (cached != null) {
            val id = cached.appleId
            // A cached MISS may only spare a speculative lookup. A user play always
            // searches again: one stale negative (a bad query, a catalog that has
            // since gained the track) must not bend playback to another source.
            if (id == null) {
                if (speculative) return null
                Log.d(TAG, "ignoring cached Apple miss for foreground play; searching")
            } else {
            val trackId = id.toLongOrNull() ?: return null
            // A synthetic hit that matches itself, so the Apple path needs no re-search.
            return listOf(
                EclipseAppleTrack(
                    trackId = trackId,
                    trackName = track.title,
                    artistName = track.artist,
                    trackTimeMillis = track.durationMs,
                ),
            )
            }
        }
        return try {
            repository.searchApple(addon, track.title, track.artist)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No iTunes answer is just no Apple route on this play.
            emptyList()
        }
    }

    /**
     * The known Apple id for this track — memory first, then the on-disk cache —
     * or null when unknown. A null *value* inside a non-null result is a cached
     * negative (the track is not in the Apple catalog); null result means "search".
     */
    private suspend fun cachedAppleId(addon: EclipseAddon, track: TrackEntity, key: String): AppleCached? {
        val memory = resolveCache.get(key)
        if (memory != null) return AppleCached(memory.itemId)
        val disk = appleLookup?.get(addon.id, identityKey(track)) ?: return null
        Log.d(TAG, "apple id for ${track.id} from disk cache (${if (disk.appleId == null) "miss" else "hit"})")
        resolveCache.put(key, disk.appleId)
        return AppleCached(disk.appleId)
    }

    /** A known Apple lookup; [appleId] null is a cached miss (vs the null result = unknown). */
    private class AppleCached(val appleId: String?)

    /** Writes an Apple id (or negative) to memory and disk. */
    private suspend fun rememberAppleId(addon: EclipseAddon, track: TrackEntity, appleId: String?) {
        val key = appleCacheKey(addon, track)
        resolveCache.put(key, appleId)
        // Negatives are in-memory only: a persistent 24 h miss once hid a playable
        // track for a day. Positives are stable and do go to disk.
        if (appleId != null) {
            appleLookup?.put(addon.id, identityKey(track), appleId)
        }
        Log.d(TAG, "apple id for ${track.id} ${if (appleId == null) "recorded as a miss" else "cached"}")
    }

    private suspend fun forgetAppleId(addon: EclipseAddon, track: TrackEntity) {
        resolveCache.invalidate(appleCacheKey(addon, track))
        appleLookup?.remove(addon.id, identityKey(track))
        // The URL that pointed at that id is dead too.
        streamUrlStore?.remove(addon.id, settingsKey(addon), identityKey(track))
    }

    /** Strictly matches the already-fetched Apple hits and streams the winner. */
    private suspend fun appleStreamFromHits(
        addon: EclipseAddon,
        track: TrackEntity,
        hits: List<EclipseAppleTrack>?,
        startedNs: Long,
        isrcProvider: suspend () -> String?,
    ): StreamUrl? {
        if (hits == null) return null
        val appleId = matchAppleId(track, hits)
        if (appleId == null) {
            rememberAppleId(addon, track, null)
            return null
        }
        return try {
            mapStream(streamAppleWithRetry(addon, appleId, isrcProvider))
                ?.let { served(addon, track, it, startedNs) }
                ?.also { rememberAppleId(addon, track, appleId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A stale Apple id: drop it; the next play searches again.
            if (invalidatesResolve(e)) forgetAppleId(addon, track)
            null
        }
    }

    /** Normalised title + artist + ~15 s duration, through the same matcher as search hits. */
    private fun matchAppleId(track: TrackEntity, hits: List<EclipseAppleTrack>): String? {
        val alive = hits.filterNot { it.trackId.toString() in deadAppleIds }
        if (alive.isEmpty()) return null
        val byId = alive.associateBy { it.trackId.toString() }
        val candidates = alive.map { hit ->
            EclipseAddonTrack(
                id = hit.trackId.toString(),
                title = hit.trackName,
                artist = hit.artistName,
                album = hit.collectionName,
                // iTunes reports milliseconds; the matcher reads >10,000 as ms.
                duration = hit.trackTimeMillis,
                isrc = hit.isrc,
            )
        }
        val best = EclipseAddonMatcher.best(track, candidates)
        if (best == null && hits.isNotEmpty()) {
            Log.d(
                TAG,
                "apple match rejected ${hits.size} hit(s) for '${track.title}' / '${track.artist}' / " +
                    "${track.durationMs} ms: " + hits.take(3).joinToString("; ") {
                        "${it.trackName}|${it.artistName}|${it.trackTimeMillis}"
                    },
            )
        }
        return best?.id
            ?.let(byId::get)?.trackId?.takeIf { it > 0 }?.toString()
    }

    /**
     * Fast mode's bar for starting the Apple stream early: one unambiguous
     * strong candidate that is also the normal `best()` pick. ISRC wins when
     * both sides carry one; otherwise title exact, same recording markers,
     * strong artist, and both durations known within 10 s.
     */
    internal fun strongAppleId(track: TrackEntity, hits: List<EclipseAppleTrack>): String? {
        val strong = hits.filterNot { it.trackId.toString() in deadAppleIds }
            .filter { isStrongAppleHit(track, it) }
        if (strong.size != 1) return null
        val id = strong[0].trackId.toString()
        return id.takeIf { it == matchAppleId(track, hits) }
    }

    internal fun isStrongAppleHit(track: TrackEntity, hit: EclipseAppleTrack): Boolean {
        val trackIsrc = track.isrc?.trim()?.takeIf { it.isNotEmpty() }
        val hitIsrc = hit.isrc?.trim()?.takeIf { it.isNotEmpty() }
        if (trackIsrc != null && hitIsrc != null) {
            return trackIsrc.equals(hitIsrc, ignoreCase = true)
        }
        val title = EclipseAddonMatcher.normalize(track.title)
        if (title.isEmpty() || title != EclipseAddonMatcher.normalize(hit.trackName)) return false
        if (EclipseAddonMatcher.markers(track.title) != EclipseAddonMatcher.markers(hit.trackName)) return false
        if (!strongArtist(track.artist, hit.artistName)) return false
        val duration = hit.trackTimeMillis.coerceAtLeast(0)
        return track.durationMs > 0 && duration > 0 &&
            kotlin.math.abs(track.durationMs - duration) <= STRONG_DURATION_TOLERANCE_MS
    }

    /** Exact normalised artist, or every token of the shorter name present in the longer one. */
    private fun strongArtist(a: String, b: String): Boolean {
        val left = EclipseAddonMatcher.normalize(a)
        val right = EclipseAddonMatcher.normalize(b)
        if (left.isEmpty() || right.isEmpty()) return false
        if (left == right) return true
        // Connector words don't identify an artist: "Aaron Smith & southstar" and
        // "Aaron Smith, Luvli, southstar" must compare on the names only.
        val leftTokens = left.split(' ').filterNot { it in ARTIST_CONNECTORS }.toSet()
        val rightTokens = right.split(' ').filterNot { it in ARTIST_CONNECTORS }.toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return false
        val (shorter, longer) = if (leftTokens.size <= rightTokens.size) {
            leftTokens to rightTokens
        } else {
            rightTokens to leftTokens
        }
        return shorter.all { it in longer }
    }

    private suspend fun streamAppleWithRetry(
        addon: EclipseAddon,
        appleTrackId: String,
        isrcProvider: suspend () -> String?,
    ): EclipseAddonStream {
        var isrc = try { isrcProvider() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        // Only the configured tier and CD quality: the old HI_RES → LOSSLESS →
        // HI_RES_LOSSLESS walk cost up to four serial round trips (~0.4-0.7 s each)
        // on tracks the catalogue has no hi-res for. One fallback is enough.
        val tiers = listOf(addon.appleQuality, "LOSSLESS").distinct()
        var last: Exception? = null
        for (tier in tiers) {
            try {
                return if (tier == addon.appleQuality) {
                    repository.streamApple(addon, appleTrackId, isrc)
                } else {
                    repository.streamAppleAtTier(addon, appleTrackId, isrc, tier)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EclipseAddonException.Preparing) {
                try {
                    waitPreparing(addon, e)
                    return if (tier == addon.appleQuality) {
                    repository.streamApple(addon, appleTrackId, isrc)
                } else {
                    repository.streamAppleAtTier(addon, appleTrackId, isrc, tier)
                }
                } catch (retry: CancellationException) {
                    throw retry
                } catch (retry: Exception) {
                    last = retry
                }
            } catch (e: EclipseAddonException.HttpFailure) {
                if (e.code !in setOf(403, 404)) throw e
                Log.d(
                    TAG,
                    "apple stream $appleTrackId tier=$tier HTTP ${e.code} isrc=${if (isrc == null) "none" else "yes"}",
                )
                last = e
            }
        }
        if (isrc == null) {
            // The 404 may be "ISRC required": enrich once and retry the tiers.
            val enriched = try { isrcProvider() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            if (!enriched.isNullOrBlank()) {
                Log.d(TAG, "apple stream $appleTrackId retrying with an ISRC")
                isrc = enriched
                for (tier in tiers) {
                    try {
                        return if (tier == addon.appleQuality) {
                            repository.streamApple(addon, appleTrackId, isrc)
                        } else {
                            repository.streamAppleAtTier(addon, appleTrackId, isrc, tier)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e !is EclipseAddonException.HttpFailure || e.code !in setOf(403, 404)) throw e
                        last = e
                    }
                }
            }
        }
        deadAppleIds += appleTrackId
        throw last ?: EclipseAddonException.HttpFailure(404)
    }

    /**
     * Per-addon, per-track identity: ISRC when the track has one, else the
     * matcher's normalised title|artist|duration, plus the addon's settings so a
     * quality change starts from a clean id.
     */
    private fun resolveCacheKey(addon: EclipseAddon, track: TrackEntity): String =
        "${addon.id}|${settingsKey(addon)}|${identityKey(track)}"

    /**
     * Apple ids live in their own key space (a Tidal id must never answer an Apple
     * lookup), and deliberately WITHOUT the settings snapshot: a quality change
     * must not force a new ~200 ms iTunes search.
     */
    private fun appleCacheKey(addon: EclipseAddon, track: TrackEntity): String =
        "apple|${addon.id}|${identityKey(track)}"

    private fun settingsKey(addon: EclipseAddon): String =
        addon.settings.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }

    private fun identityKey(track: TrackEntity): String {
        val isrc = track.isrc?.trim()?.takeIf { it.isNotEmpty() }
        return if (isrc != null) {
            "isrc:${isrc.lowercase()}"
        } else {
            "meta:" + EclipseAddonMatcher.normalize(
                "${track.title}|${track.artist}|${track.durationMs}",
            )
        }
    }

    /** `/resolve`, then `/resolve-isrc`; a miss (null) falls through to search, a throw does not escape. */
    private suspend fun resolveId(addon: EclipseAddon, track: TrackEntity): String? {
        val isrc = track.isrc?.trim()?.takeIf { it.isNotEmpty() }
        if (addon.supportsResolve) {
            try {
                repository.resolve(
                    addon = addon,
                    title = track.title,
                    artist = track.artist,
                    durationMs = track.durationMs.takeIf { it > 0 },
                    isrc = isrc,
                )?.let { return it.id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "addon '${addon.name}' resolve missed for ${track.id}: ${failureSummary(e)}")
            }
        }
        if ((addon.supportsIsrc || addon.isEclipseCloud) && isrc != null) {
            try {
                repository.resolveIsrc(addon, isrc)?.let { return it.id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "addon '${addon.name}' resolve-isrc missed for ${track.id}: ${failureSummary(e)}")
            }
        }
        // Version suffixes and multi-artist credits break some catalogs' search;
        // the cleaned variants are only tried after the full query missed. The id
        // goes into the same track-identity cache entry as a full-query hit.
        if (addon.supportsResolve) {
            for ((title, artist) in EclipseAddonQuery.variants(track.title, track.artist).drop(1)) {
                try {
                    repository.resolve(
                        addon = addon,
                        title = title,
                        artist = artist,
                        durationMs = track.durationMs.takeIf { it > 0 },
                        isrc = null,
                    )?.let { return it.id }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(TAG, "addon '${addon.name}' variant resolve missed for ${track.id}: ${failureSummary(e)}")
                }
            }
        }
        return null
    }

    /**
     * Streams [trackId], waiting out one 503 "preparing" for at most
     * [MAX_PREPARING_WAIT_SECONDS]. A cache-backed addon (TorBox) answers 503 while
     * it caches; without the retry that became a chain miss and the user had to
     * press play again. Other failures are not retried. The registry's 45 s
     * resolve deadline still bounds the whole walk.
     */
    private suspend fun streamWithRetry(addon: EclipseAddon, trackId: String): EclipseAddonStream =
        try {
            repository.stream(addon, trackId)
        } catch (e: EclipseAddonException.Preparing) {
            waitPreparing(addon, e)
            repository.stream(addon, trackId)
        }

    /** Waits out one "preparing" 503, honouring `Retry-After` up to [MAX_PREPARING_WAIT_SECONDS]. */
    private suspend fun waitPreparing(addon: EclipseAddon, e: EclipseAddonException.Preparing) {
        val seconds = (e.retryAfterSeconds ?: DEFAULT_PREPARING_WAIT_SECONDS)
            .coerceIn(0, MAX_PREPARING_WAIT_SECONDS)
        Log.i(TAG, "addon '${addon.name}' is preparing; retrying once in ${seconds}s")
        if (seconds > 0) delay(seconds * 1_000L)
    }

    private suspend fun served(
        addon: EclipseAddon,
        track: TrackEntity,
        url: StreamUrl,
        startedNs: Long,
    ): StreamUrl {
        // Never log the URL: it carries the addon's signed token.
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        // The listener-facing service label ("via <addon name>"): the base origin is
        // the prefix, the name follows it (StreamService.displayName).
        val labelled = url.copy(origin = originFor(addon.name))
        // Both layers: the player's memory cache and the disk copy that survives restart.
        urlCache?.put(track.id, labelled)
        streamUrlStore?.put(addon.id, settingsKey(addon), identityKey(track), labelled)
        Log.i(TAG, "addon '${addon.name}' served ${track.id} '${track.title}' in $elapsedMs ms")
        return labelled
    }

    /** The player's in-memory entry, but only when its addon is still enabled. */
    private fun memoryCachedUrl(track: TrackEntity, addons: List<EclipseAddon>): StreamUrl? {
        val url = urlCache?.get(track.id) ?: return null
        val origin = url.origin ?: return url
        if (!origin.startsWith("${ORIGIN}:")) return url
        val name = origin.substringAfter(':')
        return url.takeIf { cached -> addons.any { it.enabled && it.name == name } }
    }

    /** A still-valid URL from the disk store, promoted to the player's memory cache. */
    private suspend fun persistentUrl(addon: EclipseAddon, track: TrackEntity): StreamUrl? {
        val store = streamUrlStore ?: return null
        val url = store.get(addon.id, settingsKey(addon), identityKey(track)) ?: return null
        Log.i(TAG, "addon '${addon.name}' stream URL for ${track.id} from disk cache")
        urlCache?.put(track.id, url)
        return url
    }

    /** The HTTP status for a status failure, the class name otherwise; never the URL. */
    private fun failureSummary(e: Exception): String =
        (e as? EclipseAddonException.HttpFailure)?.let { "HTTP ${it.code}" } ?: e::class.java.simpleName

    /**
     * Warms the Apple ids for the queue ahead: iTunes search only, never a stream.
     * The next-up prefetcher's companion for Cloud — by the time the user reaches
     * the track, the ~200 ms lookup is already done. At most two at a time; a
     * queue change cancels the caller's scope and stale work with it.
     */
    suspend fun warmAppleIds(tracks: List<TrackEntity>) {
        val work = tracks.filter { it.warmableForApple() }.distinctBy { identityKey(it) }
        if (work.isEmpty()) return
        val generation = repository.addonGeneration.value
        val addons = try {
            repository.enabledNow().filter { it.isEclipseCloud && it.enabled }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        if (addons.isEmpty()) return
        coroutineScope {
            // Sequential: the queue warm must not compete with the search view's
            // warm or the foreground search for iPhone-side rate limits.
            val slots = Semaphore(1)
            for (track in work) launch {
                slots.withPermit {
                    for (addon in addons) {
                        // A queue change/toggle while warming: drop the rest.
                        if (repository.addonGeneration.value != generation) return@withPermit
                        try {
                            if (cachedAppleId(addon, track, appleCacheKey(addon, track)) != null) continue
                            // Warm the ISRC alongside the Apple id: the next play must not look it up.
                            runCatching { isrcEnricher?.isrcFor(track) }
                            val hits = repository.searchApple(addon, track.title, track.artist)
                            rememberAppleId(addon, track, matchAppleId(track, hits))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Warming is best-effort; the play itself searches again.
                        }
                    }
                }
            }
        }
    }

    /** Same skip rules as the next-up prefetch: a file, a cloud song, or a known-unplayable row. */
    private fun TrackEntity.warmableForApple(): Boolean =
        !isDownloaded &&
            source != com.stash.core.model.MusicSource.CLOUD &&
            (isStreamable || isStreamableCheckedAt == null)

    /** A preference read failure reads as "no addons": a streaming miss, never a crash. */
    private suspend fun enabledAddons(): List<EclipseAddon> =
        try {
            repository.enabledNow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "couldn't read addons: ${e::class.java.simpleName}")
            emptyList()
        }

    companion object {
        /** [StreamUrl.origin] prefix for everything this resolver serves; [StreamService] names the base "Addon". */
        const val ORIGIN: String = "addon"

        /** "addon" for an unnamed addon, else "addon:<name>" — the label [StreamService] shows. */
        fun originFor(name: String): String {
            val clean = name.trim()
            return if (clean.isEmpty()) ORIGIN else "$ORIGIN:$clean"
        }

        /** Test seam: a resolver with an injectable cache (TTL/eviction tests). */
        internal fun forTest(
            repository: EclipseAddonRepository,
            cache: EclipseAddonResolveCache,
            cloudFastestSource: suspend () -> Boolean = { false },
            appleLookup: EclipseAppleLookupStore? = null,
            urlCache: StreamUrlCache? = null,
            streamUrlStore: EclipseStreamUrlStore? = null,
            isrcEnricher: EclipseIsrcEnricher? = null,
        ): EclipseAddonStreamResolver =
            EclipseAddonStreamResolver(
                repository, cache, cloudFastestSource, appleLookup, urlCache, streamUrlStore, isrcEnricher,
            )

        private const val TAG = "EclipseAddonStreamResolver"
        private const val SEARCH_LIMIT = 25

        /** One retry for a 503 "preparing", as long as the addon asks (up to 8 s). */
        private const val DEFAULT_PREPARING_WAIT_SECONDS = 2
        private const val MAX_PREPARING_WAIT_SECONDS = 8

        /** Cloud's Tidal miss is long-lived: Apple still serves the track, and the lookup is ~0.8 s. */
        private const val CLOUD_TIDAL_MISS_TTL_MS = 6 * 60 * 60_000L

        /** Fast mode's stricter duration tolerance: 10 s, not the matcher's 15 s. */
        private const val STRONG_DURATION_TOLERANCE_MS = 10_000L

        /** A per-play ISRC lookup never holds the Apple stream for more than a second. */
        private const val ISRC_LOOKUP_TIMEOUT_MS = 1_000L

        /** Credit connectors ignored when comparing artist names. */
        private val ARTIST_CONNECTORS = setOf("and", "feat", "ft", "with", "the")
    }
}

/** Maps an addon's stream answer to the player's [StreamUrl]; null when it has no URL. */
internal fun mapStream(
    stream: EclipseAddonStream,
    nowMs: Long = System.currentTimeMillis(),
): StreamUrl? {
    val url = stream.url.trim()
    if (url.isEmpty()) return null

    val codec = stream.codec?.trim()?.takeIf { it.isNotEmpty() }
        ?: stream.format?.trim()?.takeIf { it.isNotEmpty() }
        ?: codecFromUrl(url)
    val manifest = stream.manifest?.trim()?.lowercase()
    // Cloud answers "format":"hls" with no manifest field; a signed .m3u8 URL says it too.
    val isHls = manifest == "hls" ||
        stream.format?.trim()?.equals("hls", ignoreCase = true) == true ||
        url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
    val expiresAt = stream.expiresAt ?: expiresAtFromUrl(url)

    return StreamUrl(
        url = url,
        expiresAtMs = expiresAtMs(expiresAt, nowMs) { if (isHls) HLS_TTL_MS else DEFAULT_TTL_MS },
        codec = codec?.lowercase(),
        bitsPerSample = stream.bitDepth?.takeIf { it > 0 },
        sampleRateHz = stream.sampleRate?.takeIf { it > 0 },
        bitrateKbps = parseBitrateKbps(stream.quality),
        origin = EclipseAddonStreamResolver.ORIGIN,
        isDash = manifest == "dash",
        isHls = isHls,
    )
}

/** `exp` from a signed playback URL (Cloud's HLS manifests) or Qobuz's `etsp` (~38 min). */
private fun expiresAtFromUrl(url: String): Long? {
    val parsed = url.toHttpUrlOrNull() ?: return null
    return parsed.queryParameter("etsp")?.toLongOrNull()
        ?: parsed.queryParameter("exp")?.toLongOrNull()
}

/** Unix seconds per the protocol, milliseconds when a value that large comes back; a fallback otherwise. */
private fun expiresAtMs(expiresAt: Long?, nowMs: Long, fallbackMs: () -> Long): Long = when {
    expiresAt == null || expiresAt <= 0 -> nowMs + fallbackMs()
    expiresAt < 10_000_000_000L -> expiresAt * 1_000
    else -> expiresAt
}

private fun parseBitrateKbps(quality: String?): Int? =
    quality?.let { Regex("(\\d+)\\s*kbps", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() }

private fun codecFromUrl(url: String): String? {
    val path = url.substringBefore('?').substringBefore('#')
    return path.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .takeIf { it in setOf("flac", "mp3", "m4a", "aac", "opus", "ogg", "wav") }
}

private const val DEFAULT_TTL_MS = 30 * 60_000L

/** Cloud's signed HLS addresses last hours; its API's own answer knows, this is only the fallback. */
private const val HLS_TTL_MS = 6 * 60 * 60_000L

/**
 * Picks the addon search hit that really is [track]. Pure and side-effect free,
 * so the rules are unit-testable without HTTP.
 *
 * Hard rules, in order:
 *  - an ISRC match (case-insensitive) wins immediately, duration or not;
 *  - otherwise the normalised title must be identical, the artist equal or one
 *    containing the other, and the durations within 15 s;
 *  - a live/remix/instrumental marker on one side only is a different recording.
 *
 * Among the survivors the best score wins: lossless first, then bit depth and
 * sample rate, then the closest duration.
 */
internal object EclipseAddonMatcher {
    private const val DURATION_TOLERANCE_MS = 15_000L

    /**
     * Edit labels differ between catalogues ("Radio Edit" vs "Edit"), so the title
     * alone cannot prove it is the same file. Two edits of one song can also differ
     * in length, so the alias is accepted only when the durations agree this closely
     * (the rejected candidate above was 171 ms apart).
     */
    private const val EDIT_DURATION_TOLERANCE_MS = 2_000L
    private const val LOSSLESS_BONUS = 100.0
    private val MARKERS = listOf("live", "remix", "instrumental")

    /**
     * A trailing edit marker in either spelling a catalog uses:
     * `- Radio Edit`, `(Radio Edit)`, `(Edit)`, `- Edit`. Only the label differs;
     * the recording is the same one trimmed, so the two sides match.
     */
    private val EDIT_SUFFIX = Regex("(?i)\\s*[-–—]?\\s*\\(?\\s*(?:radio\\s+)?edit\\s*\\)?\\s*$")

    private fun editless(raw: String): String = normalize(EDIT_SUFFIX.replace(raw, ""))

    fun best(track: TrackEntity, candidates: List<EclipseAddonTrack>): EclipseAddonTrack? {
        val isrc = track.isrc?.trim()?.takeIf { it.isNotEmpty() }
        if (isrc != null) {
            candidates.firstOrNull { it.isrc?.trim().equals(isrc, ignoreCase = true) }?.let { return it }
        }

        val title = normalize(track.title)
        if (title.isEmpty()) return null
        // A version suffix ("Stay - Ely Oaks Version") may be stripped by the
        // catalog; the plain title is accepted as an alias. The marker check below
        // still rejects Remix/Live/Instrumental, which are different recordings.
        val cleanedTitle = normalize(EclipseAddonQuery.cleanedTitle(track.title))
        val trackEditless = editless(track.title)
        val trackMarkers = markers(track.title)

        return candidates
            .asSequence()
            .filter { candidate -> candidate.id.isNotBlank() }
            .filter { candidate ->
                val candidateTitle = normalize(candidate.title)
                if (candidateTitle == title || (cleanedTitle != title && candidateTitle == cleanedTitle)) {
                    return@filter true
                }
                // "Radio Edit" vs "Edit": only the same file when the length agrees.
                trackEditless.isNotEmpty() &&
                    editless(candidate.title) == trackEditless &&
                    track.durationMs > 0 &&
                    candidate.durationMs > 0 &&
                    kotlin.math.abs(track.durationMs - candidate.durationMs) <= EDIT_DURATION_TOLERANCE_MS
            }
            .filter { candidate -> markers(candidate.title) == trackMarkers }
            .filter { candidate -> artistMatches(track.artist, candidate.artist) }
            .filter { candidate ->
                val other = candidate.durationMs
                track.durationMs <= 0 || other <= 0 || kotlin.math.abs(track.durationMs - other) <= DURATION_TOLERANCE_MS
            }
            .maxByOrNull { score(track, it) }
    }

    /** Lowercase, de-accented, punctuation-free, single-spaced; `&` reads as "and". */
    fun normalize(raw: String): String {
        val folded = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace("&", " and ")
        return folded
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun artistMatches(a: String, b: String): Boolean {
        val left = normalize(a)
        val right = normalize(b)
        if (left.isEmpty() || right.isEmpty()) return false
        if (left == right || left.contains(right) || right.contains(left)) return true
        val leftTokens = left.split(' ').toSet()
        val rightTokens = right.split(' ').toSet()
        val shared = leftTokens.intersect(rightTokens)
        return shared.isNotEmpty() && shared.size * 2 >= minOf(leftTokens.size, rightTokens.size)
    }

    fun markers(title: String): Set<String> {
        val lower = title.lowercase()
        return MARKERS.filter { Regex("\\b$it\\b").containsMatchIn(lower) }.toSet()
    }

    private fun score(track: TrackEntity, candidate: EclipseAddonTrack): Double {
        var score = 0.0
        if (isLossless(candidate)) score += LOSSLESS_BONUS
        val candidateMs = candidate.durationMs
        if (track.durationMs > 0 && candidateMs > 0) {
            val diffSeconds = kotlin.math.abs(track.durationMs - candidateMs) / 1_000
            score += (DURATION_TOLERANCE_MS / 1_000 - diffSeconds).coerceAtLeast(0)
        }
        return score
    }

    private fun isLossless(candidate: EclipseAddonTrack): Boolean =
        sequenceOf(candidate.format, candidate.streamUrl)
            .filterNotNull()
            .any { value ->
                val lower = value.lowercase()
                lower.contains("flac") || lower.contains("lossless") || lower.contains("alac")
            }
}
