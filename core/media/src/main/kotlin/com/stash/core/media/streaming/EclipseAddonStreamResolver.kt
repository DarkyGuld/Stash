package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.common.TrackCanonical
import com.stash.core.common.VersionWords
import com.stash.core.data.addons.EclipseAccountManager
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
    /** Account changes invalidate session-only Apple dead ids; null in hand-built tests. */
    private val account: EclipseAccountManager?,
    /** "The addon is preparing this song", for the player's hint. Null in hand-built tests. */
    private val preparingState: AddonPreparingState?,
    /** This phone's own TorBox account. Null in hand-built tests. */
    private val torboxLocal: com.stash.core.data.addons.TorboxLocalResolver?,
) {
    /** Apple ids that 404'd at every tier this session: never request them again. */
    private val deadAppleIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * The tracks whose addon answered "preparing": it is installing the file.
     *
     * The player uses this to know that a fallback to another source was only
     * standing in - the file itself is minutes away.
     */
    private val preparingTracks = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /**
     * Addons whose last call failed transiently (429, timeout/unreachable, 5xx):
     * no request is made to them until this instant. In memory only — a relay or
     * rights blip must not persist as a dead addon across restarts.
     */
    private val coolingUntilMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The addon/account epoch [deadAppleIds] belongs to. */
    @Volatile
    private var deadAppleEpoch: Pair<Long, Long>? = null

    @Inject
    constructor(
        repository: EclipseAddonRepository,
        streamingPreference: com.stash.core.data.prefs.StreamingPreference,
        appleLookup: EclipseAppleLookupStore,
        urlCache: StreamUrlCache,
        streamUrlStore: EclipseStreamUrlStore,
        isrcEnricher: EclipseIsrcEnricher,
        account: EclipseAccountManager,
        preparingState: AddonPreparingState,
        torboxLocal: com.stash.core.data.addons.TorboxLocalResolver,
    ) : this(
        repository,
        EclipseAddonResolveCache(),
        streamingPreference::isCloudFastestSource,
        appleLookup,
        urlCache,
        streamUrlStore,
        isrcEnricher,
        account,
        preparingState,
        torboxLocal,
    )

    /** Hand-built tests: Tidal-first, as the default switch does. */
    internal constructor(repository: EclipseAddonRepository) :
        this(repository, EclipseAddonResolveCache(), { false }, null, null, null, null, null, null, null)

    suspend fun resolve(track: TrackEntity, speculative: Boolean = false): StreamUrl? =
        resolveInternal(track, speculative, radio = false)

    /**
     * Was this track's addon installing its file when we last asked?
     *
     * True means a fallback to another source was only standing in, and must
     * not become the fixed answer for the track.
     */
    fun wasPreparing(trackId: Long): Boolean = preparingTracks.contains(trackId)

    /**
     * Radio playback: Eclipse Cloud is tried before every non-Cloud addon (the
     * user's order is kept inside each group), and an addon that recently failed
     * transiently is skipped. Normal playback keeps the user's exact order.
     */
    internal suspend fun resolveForRadio(track: TrackEntity, speculative: Boolean = false): StreamUrl? =
        resolveInternal(track, speculative, radio = true)

    private suspend fun resolveInternal(
        track: TrackEntity,
        speculative: Boolean,
        radio: Boolean,
    ): StreamUrl? {
        val query = "${track.artist} ${track.title}".trim()
        if (query.isEmpty()) return null
        forgetDeadAppleIdsOnChange()
        val startedNs = System.nanoTime()

        val addons = enabledAddons().let { list ->
            if (radio) list.sortedByDescending { it.isEclipseCloud } else list
        }
        memoryCachedUrl(track, addons)?.let { return it }
        for (addon in addons) {
            // A transient failure just bought this addon a minute of silence.
            if (isCooling(addon)) continue
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
                // Strict identity addons never fall back to search: that fallback would
                // turn a missing exact id into a title/version guess.
                if (addon.supportsSearch && !addon.strictIdentity) {
                    // A search-only addon may need the cleaned credit variants; one that
                    // already paid for /resolve variants keeps the single full query.
                    val searches = if (addon.supportsResolve) listOf(query) else searchQueries(track)
                    for (searchQuery in searches) {
                        val candidates = repository.search(addon, searchQuery, SEARCH_LIMIT)
                        val match = EclipseAddonMatcher.best(track, candidates)
                        if (match == null) {
                            if (candidates.isNotEmpty()) {
                                Log.d(
                                    TAG,
                                    "addon '${addon.name}' rejected ${candidates.size} search hit(s) for " +
                                        "'${track.title}' / '${track.artist}' / ${track.durationMs} ms",
                                )
                            }
                            continue
                        }
                        val url = mapStream(streamWithRetry(addon, match.id)) ?: continue
                        return served(addon, track, url, startedNs)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is EclipseAddonException.Preparing) {
                    // The addon is installing the file, not failing: it answered
                    // with a 503 after the full six-second window. A fallback to
                    // another source must not become the fixed answer for this
                    // track - the file will be ready in a few minutes.
                    //
                    // The addon is not cooled down either. The cooldown is for
                    // a broken addon, and silencing it here would push the next
                    // songs to YouTube while it is doing exactly what it should.
                    preparingTracks.add(track.id)
                    Log.i(
                        TAG,
                        "addon '${addon.name}' is still preparing ${track.id} '${track.title}'; " +
                            "the file is minutes away",
                    )
                } else if (isTransientFailure(e)) {
                    // One line, no URL/token. A transient failure also buys a
                    // cooldown so the next track does not pay the same four
                    // timed-out calls.
                    coolDown(addon, e)
                } else {
                    Log.w(TAG, "addon '${addon.name}' failed for ${track.id}: ${failureSummary(e)}")
                }
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
            if (isTransientFailure(e)) coolDown(addon, e)
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
            clearCooling(addon)
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
            clearCooling(addon)
            return@coroutineScope second.url
        }
        // Both missed. Each route records its own negative only when the addon
        // gave a definitive answer (404 / empty catalog); a 403 or a transient
        // failure must not be remembered here as "not in the catalog".
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
            val resolved = when {
                entry?.itemId != null -> ResolveId(entry.itemId, definitive = true)
                // A speculative lookup may use a cached miss; a user play re-checks it.
                entry != null && speculative -> ResolveId(null, definitive = true)
                else -> resolveId(addon, track)
            }
            val itemId = resolved.id
            if (itemId == null) {
                // Only a definitive 404 / empty catalog answer is a Tidal miss.
                // A rights refusal (403), 429 or timeout must be retried next play.
                if (resolved.definitive) resolveCache.putMiss(tidalKey, CLOUD_TIDAL_MISS_TTL_MS)
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
            if (isTransientFailure(e)) coolDown(addon, e)
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
            // Null means "no iTunes answer" (unknown, not a miss); an empty list
            // is a definitive "catalog has nothing", which may be remembered.
            val hits = appleHits(addon, track, appleKey, speculative) ?: return null
            val strongId = strongAppleId(track, hits)
            val appleId = strongId ?: matchAppleId(track, hits)
            if (appleId == null) {
                rememberAppleId(addon, track, null)
                return null
            }
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
            // 403 = rights/token refusal, 429/5xx/timeout = transient. Neither is
            // a catalog miss, so no negative is written; only a dead 404 is dropped.
            if (isDefinitiveAppleDead(e)) {
                forgetAppleId(addon, track)
            } else if (isTransientFailure(e)) {
                coolDown(addon, e)
            }
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

        // This phone's own TorBox account first. The addon named the file,
        // and the infohash is the same in every account in the world - so the
        // phone can add it to its own account and play it, without the
        // server's account serving it and without the server ever learning
        // anything about this account.
        localTorboxStream(addon, track, key)?.let { return served(addon, track, it, startedNs) }

        return try {
            mapStream(streamWithRetry(addon, itemId))?.let { served(addon, track, it, startedNs) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!invalidatesResolve(e)) throw e
            resolveCache.invalidate(key)
            streamUrlStore?.remove(addon.id, settingsKey(addon), identityKey(track))
            val fresh = resolveId(addon, track)
            val freshId = fresh.id ?: run {
                // A rights refusal or transient failure is not a catalog miss.
                if (fresh.definitive) resolveCache.put(key, null)
                return null
            }
            resolveCache.put(key, freshId)
            mapStream(streamWithRetry(addon, freshId))?.let { served(addon, track, it, startedNs) }
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
            // A cached answer can be older than the truth: the addon may have
            // fetched a better file since (the server fetches the tier the
            // client asked for in the background). A cached id that names a
            // worse tier than the addon's quality setting asks for is asked
            // again - otherwise a track that was upgraded an hour ago keeps
            // playing the old file for the whole cache lifetime.
            if (id != null && !belowPreferredTier(addon, id)) return id
            if (id == null && !bypassNegative) return null
            // A user play re-checks a cached Tidal miss; only a new miss is recorded.
            resolveCache.invalidate(key)
        }
        val resolved = resolveId(addon, track)
        when {
            resolved.id != null -> resolveCache.put(key, resolved.id, torbox = resolved.torbox)
            // Only a definitive 404 / empty catalog answer is a negative. A 403,
            // 429 or timeout must be asked again on the next play.
            missTtlMs != null && resolved.definitive -> resolveCache.putMiss(key, missTtlMs)
            missTtlMs == null && resolved.definitive -> resolveCache.put(key, null)
        }
        return resolved.id
    }

    /**
     * A stream answer saying "that id is no longer usable": re-resolve once.
     *
     * A "preparing" answer is NOT one of them: the item exists and its id is
     * good, the file is just not downloadable yet. Re-resolving would cost
     * another round trip and open a second poll window - the song would wait
     * twice as long for the same file.
     */
    private fun invalidatesResolve(e: Exception): Boolean =
        e is EclipseAddonException.HttpFailure && e.code in setOf(403, 404, 410)

    /**
     * Cloud's Apple Music fallback: Tidal had no item (or its id died), so search
     * the public iTunes catalog, match it to the track, and stream its Apple id.
     * Never used for a third-party addon. The Apple id is cached in its own key
     * space; the resulting URL is cached by the player/prefetch like any other.
     */
    /**
     * The Apple candidates for this track, from the resolve cache when one is
     * already known (positive id or a short-lived negative), else one iTunes
     * search. Null means the catalogue gave no usable answer (a cached miss for
     * a speculative lookup, or 429/timeout/5xx) and is NOT a negative. An empty
     * list is a definitive "searched, no hits", which the caller may cache.
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
            // No iTunes answer on this play: unknown, NOT a definitive empty
            // catalog. Returning null keeps a 429/timeout from becoming a
            // cached "not in the Apple catalog" negative.
            if (isTransientFailure(e)) coolDown(addon, e)
            null
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
            // Only a definitive 404/410 says the id is gone. A 403 is a rights/
            // token refusal and a 429/5xx/timeout is transient: keep the id and
            // never turn either into a cached negative.
            if (isDefinitiveAppleDead(e)) {
                forgetAppleId(addon, track)
            } else if (isTransientFailure(e)) {
                coolDown(addon, e)
            }
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

    /** Normalised, sorted artist sets: the same credit list, or a subset of it. */
    private fun strongArtist(a: String, b: String): Boolean = EclipseAddonMatcher.artistMatches(a, b)

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
                    return retryPreparing(addon) {
                        if (tier == addon.appleQuality) {
                            repository.streamApple(addon, appleTrackId, isrc)
                        } else {
                            repository.streamAppleAtTier(addon, appleTrackId, isrc, tier)
                        }
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
        val failure = last ?: EclipseAddonException.HttpFailure(404)
        // Only a 404 means "not in this catalog". A 403 is a rights/token refusal
        // that may clear on the next account or addon change.
        if (failure is EclipseAddonException.HttpFailure && failure.code == 404) {
            deadAppleIds += appleTrackId
        }
        throw failure
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
        val spotifyUri = track.spotifyUri?.trim()?.takeIf { it.isNotEmpty() }
        val youtubeId = track.youtubeId?.trim()?.takeIf { it.isNotEmpty() }
        return if (isrc != null) {
            "isrc:${isrc.lowercase()}"
        } else if (spotifyUri != null) {
            "spotify:${spotifyUri.lowercase()}"
        } else if (youtubeId != null) {
            // YouTube ids are case-sensitive, unlike the other two.
            "youtube:$youtubeId"
        } else {
            "meta:" + EclipseAddonMatcher.normalize(
                "${track.title}|${track.artist}|${track.durationMs}",
            )
        }
    }

    /** A resolve attempt: an id, or null with whether the addon gave a definitive "not here". */
    /**
     * The id the addon gave us, and - when the addon named a global TorBox
     * file - what that file is. The infohash is the same in every TorBox
     * account, so a phone with its own key can add the file to its own
     * account and play it without the server knowing anything about it.
     */
    private data class ResolveId(
        val id: String?,
        val definitive: Boolean,
        val torbox: com.stash.core.data.addons.EclipseTorboxFile? = null,
    )

    /**
     * `/resolve`, then `/resolve-isrc`, then the cleaned-query variants. A
     * definitive miss (404 / empty answer) falls through to search. A transient
     * failure (429, timeout/unreachable, 5xx) is rethrown at once so the caller
     * stops this addon's variants/search and cools it. A rights refusal (403)
     * or a malformed answer makes the overall answer non-definitive: the routes
     * below may still find the song, but no negative may be cached.
     */
    private suspend fun resolveId(addon: EclipseAddon, track: TrackEntity): ResolveId {
        val isrc = track.isrc?.trim()?.takeIf { it.isNotEmpty() }
        val spotifyUri = track.spotifyUri?.trim()?.takeIf { it.isNotEmpty() }
        val youtubeId = track.youtubeId?.trim()?.takeIf { it.isNotEmpty() }
        var definitive = true

        suspend fun route(name: String, call: suspend () -> String?): String? =
            try {
                call()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isTransientFailure(e)) throw e
                if (!isDefinitiveMiss(e)) definitive = false
                Log.d(TAG, "addon '${addon.name}' $name missed for ${track.id}: ${failureSummary(e)}")
                null
            }

        if (addon.supportsResolve) {
            // The whole answer, not just the id: a TorBox-backed addon also
            // names the file, and the infohash is what lets this phone add it
            // to its own TorBox account.
            var resolved: com.stash.core.data.addons.EclipseAddonTrack? = null
            route("resolve") {
                resolved = if (spotifyUri != null || youtubeId != null) {
                    repository.resolve(
                        addon = addon,
                        title = track.title,
                        artist = track.artist,
                        durationMs = track.durationMs.takeIf { it > 0 },
                        isrc = isrc,
                        spotifyUri = spotifyUri,
                        youtubeId = youtubeId,
                    )
                } else {
                    repository.resolve(
                        addon = addon,
                        title = track.title,
                        artist = track.artist,
                        durationMs = track.durationMs.takeIf { it > 0 },
                        isrc = isrc,
                    )
                }
                resolved?.id
            }?.let { return ResolveId(it, definitive = true, torbox = resolved?.torbox) }
        }
        if ((addon.supportsIsrc || addon.isEclipseCloud) && isrc != null) {
            route("resolve-isrc") { repository.resolveIsrc(addon, isrc)?.id }
                ?.let { return ResolveId(it, definitive = true) }
        }
        // Version suffixes and multi-artist credits break some catalogs' search;
        // the cleaned variants are only tried after the full query missed. The id
        // goes into the same track-identity cache entry as a full-query hit.
        // Strict addons skip these: a variant call carries no exact id.
        if (addon.supportsResolve && !addon.strictIdentity) {
            for ((title, artist) in EclipseAddonQuery.variants(track.title, track.artist).drop(1)) {
                route("variant resolve") {
                    repository.resolve(
                        addon = addon,
                        title = title,
                        artist = artist,
                        durationMs = track.durationMs.takeIf { it > 0 },
                        isrc = null,
                    )?.id
                }?.let { return ResolveId(it, definitive = true) }
            }
        }
        return ResolveId(null, definitive)
    }

    /** The bounded, deduplicated catalog searches for one track: full credit first, then the cleaned variants. */
    private fun searchQueries(track: TrackEntity): List<String> =
        EclipseAddonQuery.variants(track.title, track.artist)
            .asSequence()
            .map { (title, artist) -> "$artist $title".trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(SEARCH_VARIANT_LIMIT)
            .toList()

    /**
     * Drops session-only Apple negatives when the addon or account changed. A 404
     * stays dead for the session, but a new addon/quality setting or signed-in
     * account may reach a catalog that now answers.
     */
    private fun forgetDeadAppleIdsOnChange() {
        val addonGeneration = try {
            repository.addonGeneration.value
        } catch (e: Exception) {
            // Hand-built tests may not expose the flow; 0 keeps the epoch stable.
            0L
        }
        val accountGeneration = try {
            account?.accountGeneration?.value ?: 0L
        } catch (e: Exception) {
            0L
        }
        val epoch = addonGeneration to accountGeneration
        if (deadAppleEpoch != null && deadAppleEpoch != epoch) {
            deadAppleIds.clear()
            // A new account/addon setting may fix a 429/5xx cause: let it retry now.
            coolingUntilMs.clear()
        }
        deadAppleEpoch = epoch
    }

    /**
     * Streams [trackId], waiting out one 503 "preparing" for at most
     * [MAX_PREPARING_WAIT_SECONDS]. A cache-backed addon (TorBox) answers 503 while
     * it caches; without the retry that became a chain miss and the user had to
     * press play again. Other failures are not retried. The registry's 45 s
     * resolve deadline still bounds the whole walk.
     */
    private suspend fun streamWithRetry(addon: EclipseAddon, trackId: String): EclipseAddonStream =
        retryPreparing(addon) { repository.stream(addon, trackId) }

    /**
     * Runs one addon stream call, waiting out up to [MAX_PREPARING_RETRIES]
     * "preparing" 503s. A TorBox-backed addon answers 503 while it installs the
     * release; one short retry used to turn a normal cache miss into a skipped
     * song. The registry's 45 s resolve deadline still bounds the whole walk.
     *
     * The wait is [PREPARING_POLL_WAIT_MS] between the attempts, and the
     * attempts stop once [PREPARING_POLL_TOTAL_MS] is spent: six seconds is
     * long enough for a file that is nearly ready, and short enough that a
     * song which never arrives does not hold the player.
     */
    private suspend fun <T> retryPreparing(
        addon: EclipseAddon,
        block: suspend () -> T,
    ): T {
        var retries = 0
        // The time spent waiting, counted from the waits themselves: the clock
        // has to be the one the test scheduler drives, so the window is the
        // same in a test as on the phone.
        var waitedMs = 0L
        try {
            while (true) {
                try {
                    return block()
                } catch (e: EclipseAddonException.Preparing) {
                    if (retries >= MAX_PREPARING_RETRIES) throw e
                    if (waitedMs + PREPARING_POLL_WAIT_MS > PREPARING_POLL_TOTAL_MS) throw e
                    retries += 1
                    // The player shows "forbereder…" while this is true.
                    preparingState?.setPreparing(true)
                    waitedMs += waitPreparing(addon, e, retries)
                }
            }
        } finally {
            // The wait is over, however it ended: the song plays, or the
            // player moves on to another source.
            preparingState?.setPreparing(false)
        }
    }

    /**
     * The stream from this phone's own TorBox account, or null.
     *
     * The addon's answer named the file (the global infohash and the file
     * name). This phone keeps its own pointer to it - which torrent in
     * *this* account holds it - and asks TorBox for the url. A file the
     * account does not have yet is added first: TorBox has it in its own
     * cache when it is a release others play too, and that is quick.
     *
     * Null when there is no key, no file, or TorBox will not serve it. The
     * caller's own route (the server's stream) is the fallback.
     */
    private suspend fun localTorboxStream(
        addon: EclipseAddon,
        track: TrackEntity,
        key: String,
    ): StreamUrl? {
        val resolver = torboxLocal ?: return null
        val apiKey = addon.torboxApiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val file = resolveCache.get(key)?.torbox ?: return null
        if (file.infohash.isBlank()) return null

        val url = resolver.resolve(file, track.title, apiKey) ?: return null
        val lossless = file.quality?.contains("CD") == true || file.quality?.contains("Res") == true
        return mapStream(
            com.stash.core.data.addons.EclipseAddonStream(
                url = url,
                format = if (lossless) "flac" else "m4a",
                codec = if (lossless) "flac" else "aac",
                quality = file.quality,
            ),
        )
    }

    /**
     * Does this id name a tier below the addon's quality setting?
     *
     * The ids a TorBox-backed addon gives look like "nb:<hash>:<tier>", where
     * the tier is the same word the quality setting uses ("hires",
     * "lossless", "high", "low"). "max" asks for no particular tier, so
     * nothing is below it.
     */
    private fun belowPreferredTier(addon: EclipseAddon, id: String): Boolean {
        val preferred = addon.settings["quality"]?.trim()?.lowercase() ?: return false
        if (preferred.isEmpty() || preferred == "max" || preferred == "auto" || preferred == "best") return false
        val wanted = TIER_RANK[preferred] ?: return false
        val tier = id.substringAfterLast(':', "").lowercase()
        val have = TIER_RANK[tier] ?: return false
        return have < wanted
    }

    /**
     * Waits out one "preparing" 503 and returns how long it waited.
     *
     * The cadence is [PREPARING_POLL_WAIT_MS]: the poll happens every 1.5 s,
     * until [PREPARING_POLL_TOTAL_MS] is spent. The server's `Retry-After` may
     * shorten the wait, never lengthen it - a longer wait would blow the six
     * seconds the player can afford.
     */
    private suspend fun waitPreparing(
        addon: EclipseAddon,
        e: EclipseAddonException.Preparing,
        attempt: Int = 1,
    ): Long {
        val retryAfterMs = e.retryAfterSeconds
            ?.let { it.coerceIn(0, MAX_PREPARING_WAIT_SECONDS) * 1_000L }
            ?: PREPARING_POLL_WAIT_MS
        val waitMs = retryAfterMs.coerceAtMost(PREPARING_POLL_WAIT_MS)
        Log.i(TAG, "addon '${addon.name}' is preparing; retry $attempt in ${waitMs}ms")
        if (waitMs > 0) delay(waitMs)
        return waitMs
    }

    private suspend fun served(
        addon: EclipseAddon,
        track: TrackEntity,
        url: StreamUrl,
        startedNs: Long,
    ): StreamUrl {
        // Never log the URL: it carries the addon's signed token.
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        // A serve proves the addon is alive again (e.g. its Tidal route 429'd while
        // Apple answered), so lift any cooldown left by the losing branch.
        clearCooling(addon)
        // The listener-facing service label ("via <addon name>"): the base origin is
        // the prefix, the name follows it (StreamService.displayName).
        val labelled = url.copy(origin = originFor(addon.name))
        // The addon served it: the file is ready, and the flag may go.
        preparingTracks.remove(track.id)
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

    /** 429, a timeout/unreachable call, or a 5xx: the addon is worth trying again later. */
    private fun isTransientFailure(e: Exception): Boolean =
        e is EclipseAddonException.Unreachable ||
            e is EclipseAddonException.Preparing ||
            (e is EclipseAddonException.HttpFailure && (e.code == 408 || e.code == 429 || e.code in 500..599))

    /** 404/410: the catalog's definitive "no such item". */
    private fun isDefinitiveMiss(e: Exception): Boolean =
        e is EclipseAddonException.HttpFailure && e.code in setOf(404, 410)

    /** Apple: only a 404/410 proves the id is dead; 403 is a rights refusal. */
    private fun isDefinitiveAppleDead(e: Exception): Boolean =
        e is EclipseAddonException.HttpFailure && e.code in setOf(404, 410)

    /** One line in, one line out; never a URL or a token. */
    private fun coolDown(addon: EclipseAddon, e: Exception) {
        val now = System.currentTimeMillis()
        val until = coolingUntilMs[addon.id]
        // Already cooling: extend the window silently rather than log per route.
        if (until != null && now < until) {
            coolingUntilMs[addon.id] = now + ADDON_COOLDOWN_MS
            return
        }
        coolingUntilMs[addon.id] = now + ADDON_COOLDOWN_MS
        Log.i(
            TAG,
            "addon '${addon.name}' cooling for ${ADDON_COOLDOWN_MS / 1_000}s after ${failureSummary(e)}",
        )
    }

    private fun clearCooling(addon: EclipseAddon) {
        coolingUntilMs.remove(addon.id)
    }

    private fun isCooling(addon: EclipseAddon, nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = coolingUntilMs[addon.id] ?: return false
        if (nowMs < until) return true
        coolingUntilMs.remove(addon.id, until)
        return false
    }

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
            account: EclipseAccountManager? = null,
            preparingState: AddonPreparingState? = null,
            torboxLocal: com.stash.core.data.addons.TorboxLocalResolver? = null,
        ): EclipseAddonStreamResolver =
            EclipseAddonStreamResolver(
                repository, cache, cloudFastestSource, appleLookup, urlCache, streamUrlStore, isrcEnricher, account,
                preparingState, torboxLocal,
            )

        private const val TAG = "EclipseAddonStreamResolver"
        private const val SEARCH_LIMIT = 25

        /**
         * The quality tiers, best first. The words are the ones the addon's
         * quality setting uses, so a setting and an id can be compared.
         */
        private val TIER_RANK = mapOf(
            "atmos" to 5,
            "hires" to 4,
            "lossless" to 3,
            "high" to 2,
            "low" to 1,
        )

        /** Three credit variants at most: full, cleaned title/primary artist, full title/primary artist. */
        private const val SEARCH_VARIANT_LIMIT = 3

        /** Wait out a cache-backed addon's 503 "preparing" instead of skipping the song. */
        private const val MAX_PREPARING_WAIT_SECONDS = 15

        /**
         * How many times a "preparing" addon is asked again.
         *
         * Four retries, 1.5 s apart, is the six-second window the player can
         * afford: the addon gets five answers inside it, and the fifth answer
         * is the one that lets the song go to another source.
         */
        private const val MAX_PREPARING_RETRIES = 4

        /**
         * How long the addon gets to finish a file it is installing.
         *
         * The attempts are [PREPARING_POLL_WAIT_MS] apart and stop after
         * [PREPARING_POLL_TOTAL_MS] in total. Six seconds is long enough for a
         * file that is nearly ready, and short enough that a song which never
         * arrives does not hold the player.
         */
        private const val PREPARING_POLL_WAIT_MS = 1_500L
        private const val PREPARING_POLL_TOTAL_MS = 6_000L

        /** Cloud's Tidal miss is long-lived: Apple still serves the track, and the lookup is ~0.8 s. */
        private const val CLOUD_TIDAL_MISS_TTL_MS = 6 * 60 * 60_000L

        /** How long a 429/timeout/5xx keeps an addon out of the walk. In memory only. */
        private const val ADDON_COOLDOWN_MS = 60_000L

        /** Fast mode's stricter duration tolerance: 10 s, not the matcher's 15 s. */
        private const val STRONG_DURATION_TOLERANCE_MS = 10_000L

        /** A per-play ISRC lookup never holds the Apple stream for more than a second. */
        private const val ISRC_LOOKUP_TIMEOUT_MS = 1_000L
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
 *  - otherwise [TrackCanonical.sameTake] says the titles name the same take. It
 *    folds presentation tags ("(Official Video)", "(Audio)", "(HD)", the dash
 *    forms), credits, remasters and reissues, while a performance word on one side
 *    only (Live/Remix/Instrumental/Acoustic/re-recorded...) is a different take;
 *  - a trailing named-version suffix may be dropped by a catalog ("Stay - Ely Oaks
 *    Version" -> "Stay"), but never when the dropped suffix hides a performance word;
 *  - an edit ("Radio Edit" vs "Edit") is the same file only within 2 s;
 *  - the artist credit lists are the same set, or one is a subset of the other;
 *  - durations are within 15 s, unless the imported row is a video upload (then its
 *    length is not the audio length), while a live/long edit is still rejected above.
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

    /** Credit connectors ignored when comparing one artist name pair. */
    private val ARTIST_CONNECTORS = setOf("and", "feat", "ft", "with", "the")

    /** YouTube's presentation tags: an upload label, never another recording. */
    private val PRESENTATION_BRACKET = Regex("""[\(\[]([^)\]]*)[\)\]]""")
    private val PRESENTATION_DASH = Regex("""\s[-\u2013\u2014]\s.*""")
    private val PRESENTATION_WORD = Regex(
        """(?i)\b(?:official\s+(?:music\s+)?video|lyric\s+video|lyrics?|audio|visuali[sz]er|hd|hq|mv)\b""",
    )

    /** Music-video classifications whose length is not the studio master's length. */
    private val VIDEO_TYPES = setOf("OMV", "UGC", "OFFICIAL_SOURCE_MUSIC")

    /**
     * A trailing edit marker in either spelling a catalog uses:
     * `- Radio Edit`, `(Radio Edit)`, `(Edit)`, `- Edit`. Only the label differs;
     * the recording is the same one trimmed, so the two sides match.
     */
    private val EDIT_SUFFIX = Regex("(?i)\\s*[-–—]?\\s*\\(?\\s*(?:radio\\s+)?edit\\s*\\)?\\s*$")

    private fun editless(raw: String): String = normalize(EDIT_SUFFIX.replace(withoutPresentation(raw), ""))

    /** Removes only the upload's presentation tags, keeping performance/version labels. */
    private fun withoutPresentation(title: String): String {
        val noBrackets = PRESENTATION_BRACKET.replace(title) { match ->
            if (PRESENTATION_WORD.containsMatchIn(match.groupValues[1])) "" else match.value
        }
        val dash = PRESENTATION_DASH.find(noBrackets) ?: return noBrackets
        return if (PRESENTATION_WORD.containsMatchIn(dash.value)) {
            noBrackets.substring(0, dash.range.first)
        } else {
            noBrackets
        }
    }

    fun best(track: TrackEntity, candidates: List<EclipseAddonTrack>): EclipseAddonTrack? {
        val isrc = track.isrc?.trim()?.takeIf { it.isNotEmpty() }
        if (isrc != null) {
            candidates.firstOrNull { it.isrc?.trim().equals(isrc, ignoreCase = true) }?.let { return it }
        }

        val title = normalize(track.title)
        if (title.isEmpty()) return null
        val looseDuration = isVideoLike(track)

        return candidates
            .asSequence()
            .filter { candidate -> candidate.id.isNotBlank() }
            .filter { candidate ->
                if (!titleMatches(track.title, candidate.title)) return@filter false
                if (!editMismatch(track.title, candidate.title)) return@filter true
                // "Radio Edit" vs "Edit": only the same file when the length agrees.
                track.durationMs > 0 &&
                    candidate.durationMs > 0 &&
                    kotlin.math.abs(track.durationMs - candidate.durationMs) <= EDIT_DURATION_TOLERANCE_MS
            }
            .filter { candidate -> artistMatches(track.artist, candidate.artist) }
            .filter { candidate ->
                val other = candidate.durationMs
                track.durationMs <= 0 || other <= 0 || looseDuration ||
                    kotlin.math.abs(track.durationMs - other) <= DURATION_TOLERANCE_MS
            }
            .maxByOrNull { score(track, it) }
    }

    /**
     * Same take by [TrackCanonical.sameTake], or the catalog's plain title when the
     * imported row carries a named-version suffix it drops ("Stay - Ely Oaks
     * Version"). A performance word in that suffix ("Song - Remix Version") is not
     * an alias: the version is the recording the user imported.
     */
    private fun titleMatches(trackTitle: String, candidateTitle: String): Boolean {
        if (TrackCanonical.sameTake(trackTitle, candidateTitle)) return true

        // Punctuation, accents and a dash that is part of the title itself fold away;
        // the take still has to name the same performance words on both sides.
        val folded = normalize(trackTitle)
        if (folded.isNotEmpty() && folded == normalize(candidateTitle) &&
            versionWords(trackTitle) == versionWords(candidateTitle)
        ) {
            return true
        }

        val cleaned = EclipseAddonQuery.cleanedTitle(trackTitle)
        if (cleaned == trackTitle) return false
        // Only a *named* version suffix is an alias; a performance word hidden in
        // one ("Song - Remix Version") keeps the version the user imported.
        if (TrackCanonical.version(trackTitle, VersionWords::of).words.isNotEmpty()) return false
        return TrackCanonical.sameTake(cleaned, candidateTitle)
    }

    private fun versionWords(title: String): Set<String> =
        TrackCanonical.version(title, VersionWords::forCatalogMatch).words

    /** One side carries an edit label the other does not: the same file only when the lengths agree. */
    private fun editMismatch(trackTitle: String, candidateTitle: String): Boolean {
        val trackBase = withoutPresentation(trackTitle)
        val candidateBase = withoutPresentation(candidateTitle)
        if (normalize(trackBase) == normalize(candidateBase)) return false
        val wanted = editless(trackBase)
        return wanted.isNotEmpty() && editless(candidateBase) == wanted
    }

    /** A video upload's length is the video's, not the studio recording's. */
    private fun isVideoLike(track: TrackEntity): Boolean {
        if (hasPresentationMarker(track.title)) return true
        val type = track.musicVideoType?.trim()?.uppercase()
            ?.removePrefix("MUSIC_VIDEO_TYPE_")
            ?.takeIf { it.isNotEmpty() }
            ?: return false
        return type in VIDEO_TYPES
    }

    private fun hasPresentationMarker(title: String): Boolean = withoutPresentation(title) != title

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

    /**
     * [TrackCanonical.artist]'s normalized, sorted credit list: the same artists, or
     * one list a subset of the other ("Aaron Smith & southstar" vs "Aaron Smith,
     * Luvli, southstar"). Single names only fold connector words, so a namesake
     * ("The Weeknd Tribute Band") never matches on a shared prefix.
     */
    internal fun artistMatches(a: String, b: String): Boolean {
        val left = artistNames(a)
        val right = artistNames(b)
        if (left.isEmpty() || right.isEmpty()) return false
        val (shorter, longer) = if (left.size <= right.size) left to right else right to left
        return shorter.all { name -> longer.any { sameArtistName(name, it) } }
    }

    private fun artistNames(raw: String): Set<String> =
        TrackCanonical.artist(raw)
            .split(", ")
            .map(::normalize)
            .filter { it.isNotEmpty() }
            .toSet()

    private fun sameArtistName(a: String, b: String): Boolean {
        if (a == b) return true
        val left = a.split(' ').filterNot { it in ARTIST_CONNECTORS }.toSet()
        val right = b.split(' ').filterNot { it in ARTIST_CONNECTORS }.toSet()
        return left.isNotEmpty() && left == right
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
