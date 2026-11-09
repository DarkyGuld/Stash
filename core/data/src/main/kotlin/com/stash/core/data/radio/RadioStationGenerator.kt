package com.stash.core.data.radio

import android.util.Log
import com.stash.core.data.addons.EclipseAddon
import com.stash.core.data.addons.EclipseAddonQuery
import com.stash.core.data.addons.EclipseAddonRepository
import com.stash.core.data.addons.EclipseAddonTrack
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmSimilarArtist
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.core.model.YouTubeVideoId
import com.stash.data.ytmusic.CanonicalMatch
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and extends a balanced radio station: Last.fm chooses the taste graph,
 * the enabled Eclipse addon (metadata-only tracks, streamed at play time) or
 * YouTube supplies the playable answer. Stateless across stations — all live
 * state lives in the returned [RadioSession]. Plain `@Inject` (all dependencies
 * are injectable); no Hilt module needed.
 */
@Singleton
class RadioStationGenerator @Inject constructor(
    private val lastFm: LastFmApiClient,
    private val yt: YTMusicApiClient,
    /**
     * Optional so hand-built tests keep their two-argument construction; Hilt
     * injects the real repository in production. When an addon is enabled it is
     * tried BEFORE the YouTube search, which is what makes radio work with the
     * YouTube source switched off.
     */
    private val eclipseAddons: EclipseAddonRepository? = null,
    /**
     * The user's per-source switches. Absent (hand-built tests) means "all on",
     * i.e. today's behaviour. Present, it lets the generator skip YouTube when
     * the user has that source off (addon-only radio) and skip the addon walk
     * when the addons are off, instead of paying for calls that can only miss.
     */
    private val streamingPreference: StreamingPreference? = null,
) {
    /** Start a station: returns the session + the first playable batch. */
    suspend fun start(seed: RadioSeed): Pair<RadioSession, List<Track>> = when (seed) {
        is RadioSeed.Artist -> startArtist(seed)
        is RadioSeed.Song -> startSong(seed)
    }

    private suspend fun startArtist(seed: RadioSeed.Artist): Pair<RadioSession, List<Track>> {
        // Fetch the FULL ranked neighbor list once; use the first NEIGHBOR_POOL now
        // and page the rest during widening (see widen()). Fetching the same top-N
        // again later would only re-return already-played neighbors. A
        // multi-artist credit ("BENNETT, Creeds") is also queried as its primary
        // artist, in parallel, and the first non-empty answer wins.
        val neighborResult = firstNonEmptyByArtist(seed.name) { name ->
            lastFm.getSimilarArtists(name, limit = NEIGHBOR_MAX).getOrDefault(emptyList())
        }
        val allNeighbors = neighborResult.items
        Log.d(
            TAG,
            "artist seed: neighbors=${allNeighbors.size} artistVariant=${neighborResult.variantLabel} " +
                "(lastfm configured=${lastFm.isConfigured})",
        )
        val neighbors = allNeighbors.take(NEIGHBOR_POOL)
        // Resolve seed (via known browseId) + each neighbor's popular list, in parallel.
        val artistTracks: Map<String, List<TrackSummary>> = coroutineScope {
            val seedJob = async { seed.name to seedPopular(seed) }
            val neighborJobs = neighbors.map { n ->
                async { n.name to resolvePopular(n.name) }
            }
            (listOf(seedJob) + neighborJobs).awaitAll().toMap()
        }
        val pool = buildArtistPool(seed.name, neighbors, artistTracks)
        val session = RadioSession(seed, RadioInterleaver.order(pool).toMutableList(), mutableSetOf())
        session.remainingNeighbors.addAll(allNeighbors.drop(NEIGHBOR_POOL))
        session.neighborsLoaded = true
        return session to drainBatch(session, FIRST_BATCH)
    }

    /**
     * The artist strings worth asking Last.fm for: the full credit and, when a
     * separator (comma/&/feat/…) makes it differ, the primary artist. A
     * multi-artist credit ("BENNETT, Creeds") is not an artist page on Last.fm;
     * "BENNETT" is. The second element marks the primary variant.
     */
    private fun artistVariants(raw: String): List<Pair<String, Boolean>> {
        val full = raw.trim()
        val primary = EclipseAddonQuery.primaryArtist(raw)
        return if (primary.isNotEmpty() && !primary.equals(full, ignoreCase = true)) {
            listOf(full to false, primary to true)
        } else {
            listOf(full to false)
        }
    }

    /**
     * Runs the [artistVariants] queries CONCURRENTLY and returns the first
     * non-empty answer (full credit first, then primary), cancelling the loser.
     * Both calls are already in flight, so a hit on one never waits for the
     * other's latency; a single-artist seed still makes one call.
     */
    private suspend fun <T> firstNonEmptyByArtist(
        artist: String,
        query: suspend (String) -> List<T>,
    ): ArtistVariantResult<T> = coroutineScope {
        val variants = artistVariants(artist)
        if (variants.size == 1) {
            val (name, usedPrimary) = variants.single()
            return@coroutineScope ArtistVariantResult(name, query(name), usedPrimary)
        }
        val jobs = variants.map { (name, usedPrimary) ->
            async { Triple(name, usedPrimary, query(name)) }
        }
        var result: ArtistVariantResult<T>? = null
        try {
            for (job in jobs) {
                val (name, usedPrimary, items) = job.await()
                if (items.isNotEmpty()) {
                    result = ArtistVariantResult(name, items, usedPrimary)
                    break
                }
            }
        } finally {
            // Never leave the loser running once the winner has answered.
            jobs.forEach { it.cancel() }
        }
        result ?: ArtistVariantResult(variants.first().first, emptyList(), false)
    }

    /**
     * Artist-native fallback for an addon-only station: one `/search` for the
     * artist's name (its primary credit — catalogues have no page for a
     * multi-artist string) and the hits that are really by that artist, deduped
     * by id and capped at [TRACKS_PER_ARTIST] * 2. No resolve round-trip: the
     * candidates go through the normal addon resolution path at playback.
     */
    private suspend fun addonArtistTracks(artist: String, limit: Int): List<EclipseAddonTrack> {
        val repo = eclipseAddons ?: return emptyList()
        val addon = try {
            repo.enabledNow().firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val hits = try {
            repo.search(addon, EclipseAddonQuery.primaryArtist(artist), limit = limit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptyList()
        }
        return hits
            .asSequence()
            .filter { it.id.isNotBlank() && RadioAddonMatcher.artistOverlaps(artist, it.artist) }
            .distinctBy { it.id }
            .take(TRACKS_PER_ARTIST * 2)
            .toList()
    }

    /** One artist-variant answer: which string answered, and whether it was the primary. */
    private data class ArtistVariantResult<T>(
        val artist: String,
        val items: List<T>,
        val usedPrimary: Boolean,
    ) {
        val variantLabel: String get() = if (usedPrimary) "primary" else "full"
    }

    private suspend fun seedPopular(seed: RadioSeed.Artist): List<TrackSummary> {
        val browseId = seed.ytBrowseId ?: yt.resolveArtist(seed.name)?.id ?: return emptyList()
        return runCatching { yt.getArtist(browseId).popular }.getOrDefault(emptyList())
    }

    private suspend fun resolvePopular(name: String): List<TrackSummary> {
        val id = yt.resolveArtist(name)?.id ?: return emptyList()
        return runCatching { yt.getArtist(id).popular }.getOrDefault(emptyList())
    }

    /**
     * Seed weight tuned so that WITHIN the current pool the seed is the single
     * most frequent artist — weight = SEED_SHARE/(1-SEED_SHARE) * W (W = total
     * neighbor weight) makes the seed's weight ~1/3 of the total, so the
     * interleaver front-loads the seed's tracks across the opening. The seed has
     * a finite catalog (~[TRACKS_PER_ARTIST] popular tracks), so as the station
     * widens to more neighbors the seed naturally recedes — "recognizable open,
     * always drifting outward." A literal steady 1/3-forever is impossible
     * without repeating the seed's few tracks, which the no-repeat set forbids.
     */
    private fun buildArtistPool(
        seedName: String,
        neighbors: List<LastFmSimilarArtist>,
        tracks: Map<String, List<TrackSummary>>,
    ): List<RadioCandidate> {
        val neighborWeightSum = neighbors.sumOf { it.match.coerceAtLeast(0.05f).toDouble() }.toFloat()
        val seedWeight = if (neighborWeightSum <= 0f) 1f
            else (SEED_SHARE / (1f - SEED_SHARE)) * neighborWeightSum
        val out = ArrayList<RadioCandidate>()
        tracks[seedName]?.take(TRACKS_PER_ARTIST)?.forEach {
            out += RadioCandidate(seedName, it.title, it.videoId, seedWeight, it.thumbnailUrl)
        }
        for (n in neighbors) {
            val w = n.match.coerceAtLeast(0.05f)
            tracks[n.name]?.take(TRACKS_PER_ARTIST)?.forEach {
                out += RadioCandidate(n.name, it.title, it.videoId, w, it.thumbnailUrl)
            }
        }
        return out
    }

    /** Walk the ordered pool from the cursor, emit up to [size] fresh tracks,
     *  resolving any candidate that still lacks a videoId. Skips dupes + misses.
     *  Does NOT set `exhausted` — widen() owns that (the pool may still widen). */
    internal suspend fun drainBatch(session: RadioSession, size: Int): List<Track> {
        val startedAt = System.currentTimeMillis()
        // One prefs read per batch, not per candidate. The user's source switches
        // decide whether a resolve leg is even reachable: an addon-only user must
        // not pay for a YouTube search, and vice versa.
        val youtubeOn = youtubeOn()
        val addonsOn = addonsOn()
        if (!youtubeOn) Log.d(TAG, "youtube: disabled, skipping search")
        if (!addonsOn && eclipseAddons != null) Log.d(TAG, "addons: disabled, skipping addon resolve")
        val prefetched = prefetchMatches(session, size + RESOLVE_SLACK, youtubeOn, addonsOn)
        val out = ArrayList<Track>(size)
        while (out.size < size && session.cursor < session.ordered.size) {
            val cand = session.ordered[session.cursor++]
            val id = cand.identity()
            if (id in session.played) continue
            // Candidates with a known videoId (artist-radio, seeded song) keep
            // their own thumbnail; those without one resolve via the enabled
            // Eclipse addon first, then YT Music search — which also yields the
            // song's square album cover, so no-thumbnail candidates get a crisp
            // cover instead of the low-res video frame.
            val existingId = cand.videoId?.takeIf { it.isNotBlank() }
            if (existingId != null) {
                val vidKey = "vid:$existingId"
                if (vidKey in session.played) continue
                session.played += id
                session.played += vidKey
                out += cand.toTrack(existingId)
                continue
            }
            val resolution =
                (if (id in prefetched) prefetched[id] else resolveCandidate(cand, youtubeOn, addonsOn))
                    ?: continue
            when (resolution) {
                is Resolution.Addon -> {
                    // The same addon item can be reached by two candidates (e.g.
                    // the seed and a similar track) — dedup on the addon id too.
                    val addonKey = ADDON_PREFIX + resolution.track.id
                    if (addonKey in session.played) continue
                    session.played += id
                    session.played += addonKey
                    out += cand.toAddonTrack(resolution.track)
                }
                is Resolution.YouTube -> {
                    val match = resolution.match
                    // Two different candidates can resolve to the SAME videoId —
                    // guard on the resolved id too so the same audio never plays
                    // twice in one station.
                    val vidKey = "vid:${match.videoId}"
                    if (vidKey in session.played) continue
                    session.played += id
                    session.played += vidKey
                    out += cand.toTrack(match.videoId, match.thumbnailUrl)
                }
            }
        }
        Log.d(
            TAG,
            "batch: asked=$size made=${out.size} pool=${session.ordered.size} " +
                "cursor=${session.cursor} prefetched=${prefetched.size} in ${System.currentTimeMillis() - startedAt} ms",
        )
        return out
    }

    /**
     * Resolve one candidate that has no videoId: the enabled Eclipse addon
     * first (metadata-only track; the stream is resolved at play time), then
     * YouTube search. Either leg is skipped when the user's source switch is
     * off, so no call can be spent on a source that cannot serve the track.
     */
    private suspend fun resolveCandidate(
        cand: RadioCandidate,
        youtubeOn: Boolean,
        addonsOn: Boolean,
    ): Resolution? {
        if (addonsOn) addonResolve(cand)?.let { return Resolution.Addon(it) }
        if (!youtubeOn) return null
        return yt.searchCanonicalMatch(cand.artist, cand.title)?.let { Resolution.YouTube(it) }
    }

    /** The user's YouTube switch; absent prefs (tests) mean on. */
    private suspend fun youtubeOn(): Boolean {
        val prefs = streamingPreference ?: return true
        return try {
            prefs.isYoutubeEnabled()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            true
        }
    }

    /** The user's Eclipse-addons switch AND an installed repository; absent prefs mean on. */
    private suspend fun addonsOn(): Boolean {
        if (eclipseAddons == null) return false
        val prefs = streamingPreference ?: return true
        return try {
            prefs.isEclipseAddonsEnabled()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Resolves, concurrently, the next [count] candidates from the cursor that
     * still need an addon/YouTube resolve, keyed by [identity] (a null value is
     * a miss). [drainBatch] used to search them one at a time: every song-radio
     * candidate but the seed lacks a videoId, so the first batch waited on about
     * a dozen sequential searches (5-10 s) before anything could play. Artist
     * radio already resolves its neighbours in parallel. Candidates past
     * [count] (when misses or duplicates use up the lookahead) are still
     * resolved one by one in [drainBatch].
     */
    private suspend fun prefetchMatches(
        session: RadioSession,
        count: Int,
        youtubeOn: Boolean,
        addonsOn: Boolean,
    ): Map<String, Resolution?> {
        val toResolve = LinkedHashMap<String, RadioCandidate>()
        var i = session.cursor
        while (toResolve.size < count && i < session.ordered.size) {
            val cand = session.ordered[i++]
            if (!cand.videoId.isNullOrBlank()) continue
            val id = cand.identity()
            if (id !in session.played) toResolve.putIfAbsent(id, cand)
        }
        if (toResolve.isEmpty()) return emptyMap()
        val startedAt = System.currentTimeMillis()
        val resolved = coroutineScope {
            toResolve.entries.chunked(RESOLVE_PARALLELISM).flatMap { chunk ->
                chunk.map { (id, cand) ->
                    async { id to resolveCandidate(cand, youtubeOn, addonsOn) }
                }.awaitAll()
            }.toMap()
        }
        Log.d(
            TAG,
            "prefetch: wanted=$count searched=${toResolve.size} hits=${resolved.count { it.value != null }} " +
                "in ${System.currentTimeMillis() - startedAt} ms",
        )
        return resolved
    }

    /**
     * Resolves [cand] through the first enabled Eclipse addon. Null when no
     * addon repository is present, no addon is enabled, or the addon missed
     * (including any error) — the caller then falls back to YouTube. The
     * emitted track carries no videoId; playback resolves the addon stream
     * from its title/artist/duration at play time.
     *
     * Non-Cloud addons try `/resolve` first, then `/search` (radio candidates
     * carry no ISRC/duration and `/resolve` misses songs the catalogue still
     * holds — e.g. "You Know You Like It" by "DJ Snake, AlunaGeorge").
     * Eclipse Cloud is the reverse: `/search` is both faster and fresher there,
     * and `/resolve` is only the last resort. A hit on the first leg never
     * pays for the second.
     */
    private suspend fun addonResolve(cand: RadioCandidate): EclipseAddonTrack? {
        val repo = eclipseAddons ?: return null
        val addon = try {
            repo.enabledNow().firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        return if (addon.isEclipseCloud) {
            addonSearch(repo, addon, cand) ?: addonResolveTry(repo, addon, cand)
        } else {
            addonResolveTry(repo, addon, cand) ?: addonSearch(repo, addon, cand)
        }
    }

    /** `/resolve` with its timing log; null on a miss or any error. */
    private suspend fun addonResolveTry(
        repo: EclipseAddonRepository,
        addon: EclipseAddon,
        cand: RadioCandidate,
    ): EclipseAddonTrack? {
        val startedAt = System.currentTimeMillis()
        val match = try {
            repo.resolve(addon, cand.title, cand.artist, null, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        Log.d(
            TAG,
            "addon resolve: ${if (match != null) "HIT" else "MISS"} " +
                "'${cand.artist} - ${cand.title}' in ${System.currentTimeMillis() - startedAt} ms",
        )
        return match
    }

    /**
     * `/search` + [RadioAddonMatcher] with its timing log; null on a miss or any error.
     * The conservative query variants (full title + full artist, cleaned title +
     * primary artist) are issued CONCURRENTLY and the first hit wins — a
     * comma-separated multi-artist credit otherwise makes the catalogue search
     * miss a track it holds (e.g. "BENNETT, Creeds" / "Milky Way"). At most
     * [ADDON_SEARCH_VARIANT_LIMIT] (two) searches per candidate, and both are in
     * flight before either result is inspected.
     */
    private suspend fun addonSearch(
        repo: EclipseAddonRepository,
        addon: EclipseAddon,
        cand: RadioCandidate,
    ): EclipseAddonTrack? {
        val startedAt = System.currentTimeMillis()
        val queries = EclipseAddonQuery.variants(cand.title, cand.artist)
            .take(ADDON_SEARCH_VARIANT_LIMIT)
            .map { (title, artist) -> "$title $artist" }
        if (queries.isEmpty()) return null
        val hit: Pair<Int, EclipseAddonTrack>? = coroutineScope {
            val jobs = queries.mapIndexed { index, query ->
                async {
                    index to try {
                        val hits = repo.search(addon, query, limit = ADDON_SEARCH_LIMIT)
                        RadioAddonMatcher.bestAddonSearchHit(cand, hits)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            var found: Pair<Int, EclipseAddonTrack>? = null
            try {
                // Both were started above; awaiting in variant order only decides
                // WHICH hit wins, never serialises the two network calls.
                for (job in jobs) {
                    val (index, match) = job.await()
                    if (match != null) {
                        found = index to match
                        break
                    }
                }
            } finally {
                jobs.forEach { it.cancel() }
            }
            found
        }
        if (hit != null) {
            Log.d(
                TAG,
                "addon search: HIT '${hit.second.title}' id=${hit.second.id} variant=${hit.first} " +
                    "for '${cand.artist} - ${cand.title}'",
            )
        } else {
            Log.d(
                TAG,
                "addon search: MISS for '${cand.artist} - ${cand.title}' " +
                    "in ${System.currentTimeMillis() - startedAt} ms",
            )
        }
        return hit?.second
    }

    /** One prefetched answer: an addon match or a YouTube search hit. */
    private sealed interface Resolution {
        data class Addon(val track: EclipseAddonTrack) : Resolution
        data class YouTube(val match: CanonicalMatch) : Resolution
    }

    /** The next playable batch. Widens the pool when it nears exhaustion. */
    suspend fun nextBatch(session: RadioSession): List<Track> {
        if (session.cursor >= session.ordered.size - WIDEN_THRESHOLD && !session.exhausted) {
            widen(session)
        }
        return drainBatch(session, GROW_BATCH)
    }

    /** Append more candidates by PAGING the unused neighbor list (never
     *  re-fetching the same top-N, which would only re-return already-played
     *  neighbors). Song radio lazy-loads the neighbor list here on first widen;
     *  artist radio filled it at start. Sets [exhausted] only when no neighbors
     *  remain to try. */
    private suspend fun widen(session: RadioSession) {
        if (!session.neighborsLoaded) {
            val seedArtistName = when (val s = session.seed) {
                is RadioSeed.Artist -> s.name
                is RadioSeed.Song -> s.artist
            }
            session.remainingNeighbors.addAll(
                lastFm.getSimilarArtists(seedArtistName, limit = NEIGHBOR_MAX).getOrDefault(emptyList()),
            )
            session.neighborsLoaded = true
        }
        if (session.remainingNeighbors.isEmpty()) { session.exhausted = true; return }
        // Page the next slice of never-used neighbors.
        val next = ArrayList<LastFmSimilarArtist>()
        while (next.size < NEIGHBOR_POOL && session.remainingNeighbors.isNotEmpty()) {
            next += session.remainingNeighbors.removeFirst()
        }
        val fresh = ArrayList<RadioCandidate>()
        for (n in next) {
            val id = yt.resolveArtist(n.name)?.id ?: continue
            val popular = runCatching { yt.getArtist(id).popular }.getOrDefault(emptyList())
            popular.take(TRACKS_PER_ARTIST).forEach {
                val cand = RadioCandidate(n.name, it.title, it.videoId, n.match.coerceAtLeast(0.05f), it.thumbnailUrl)
                if (cand.identity() !in session.played) fresh += cand
            }
        }
        if (fresh.isEmpty()) {
            // This slice yielded nothing new; only truly done when no neighbors remain.
            if (session.remainingNeighbors.isEmpty()) session.exhausted = true
            return
        }
        session.ordered += RadioInterleaver.order(fresh)
    }

    private fun RadioCandidate.toTrack(videoId: String, resolvedArt: String? = null) = Track(
        id = videoId.hashCode().toLong(),
        title = title,
        artist = artist,
        durationMs = 0L,
        // Album art, best-first: the candidate's own InnerTube cover (artist-radio
        // popular tracks carry a square 544px one), else the square album cover
        // pulled from the YT Music search that resolved the videoId (song-radio /
        // Last.fm neighbours), else the low-res video frame as a last resort. This
        // is the single chokepoint every emitted radio Track flows through.
        albumArtUrl = thumbnailUrl ?: resolvedArt ?: ytThumbnail(videoId),
        youtubeId = videoId,
        source = MusicSource.YOUTUBE,
        isStreamable = true,
    )

    /**
     * Metadata-only track for an Eclipse addon match: no videoId, so playback
     * resolves the addon stream from title/artist/duration at play time. The id
     * is synthetic (the addon item-id hash, same convention as videoId hashes)
     * because the song has no library row. Addon artwork fills in only when the
     * candidate itself carried none.
     */
    private fun RadioCandidate.toAddonTrack(match: EclipseAddonTrack) = Track(
        id = (ADDON_PREFIX + match.id).hashCode().toLong(),
        title = title.ifBlank { match.title },
        artist = artist.ifBlank { match.artist },
        durationMs = match.durationMs,
        albumArtUrl = thumbnailUrl ?: match.artworkUrl,
        youtubeId = null,
        source = MusicSource.YOUTUBE,
        isStreamable = true,
    )

    /** YouTube thumbnail for a videoId, used only when a track has no richer
     *  cover. `maxresdefault` (1280x720) is the full 16:9 frame with NO black
     *  letterbox bars (unlike `hqdefault`/`sddefault`, which pad 16:9 into a 4:3
     *  frame), so it center-crops cleanly to a square without black space —
     *  same reason this used to ask for `mqdefault`, but four times the width.
     *  Videos without one are walked back down by `ArtFallbackInterceptor`.
     *  The player upgrades this to the square Qobuz cover once qbdlx resolves.
     *  Null for an id without the YouTube video-id shape: the id came from a
     *  server response and is only built into a URL when it has it. */
    private fun ytThumbnail(videoId: String): String? =
        videoId.takeIf(YouTubeVideoId::isValid)?.let { "https://i.ytimg.com/vi/$it/maxresdefault.jpg" }

    private suspend fun startSong(seed: RadioSeed.Song): Pair<RadioSession, List<Track>> {
        val startedAt = System.currentTimeMillis()
        // The two Last.fm calls are independent; run them together so the first
        // batch waits for the SLOWER one, not their sum (device log: 3.4 s). Each
        // call also races the full credit against the primary artist (Last.fm has
        // no "BENNETT, Creeds" page; it has "Bennett").
        val (similarResult, seedTopResult) = coroutineScope {
            val similarJob = async {
                firstNonEmptyByArtist(seed.artist) { artist ->
                    lastFm.getSimilarTracks(artist, seed.title, limit = NEIGHBOR_LIMIT)
                        .getOrDefault(emptyList())
                        .take(SONG_SIMILAR_POOL)
                }
            }
            val seedTopJob = async {
                firstNonEmptyByArtist(seed.artist) { artist ->
                    lastFm.getArtistTopTracks(artist, limit = TRACKS_PER_ARTIST)
                        .getOrDefault(emptyList())
                }
            }
            similarJob.await() to seedTopJob.await()
        }
        val similar = similarResult.items
        val seedTop = seedTopResult.items
        val artistVariant =
            if (similarResult.usedPrimary || seedTopResult.usedPrimary) "primary" else "full"
        Log.d(
            TAG,
            "lastfm: similar=${similar.size} seedTop=${seedTop.size} artistVariant=$artistVariant " +
                "in ${System.currentTimeMillis() - startedAt} ms",
        )

        val similarWeightSum = similar.sumOf { it.match.coerceAtLeast(0.05f).toDouble() }.toFloat()
        val seedWeight = if (similarWeightSum <= 0f) 1f
            else (SEED_SHARE / (1f - SEED_SHARE)) * similarWeightSum

        // `track.getSimilar` legitimately returns 0 for many seeds (Anyma,
        // Eli Brown/Pan-Pot — Last.fm's own answer). A pool of just the seed
        // artist's top tracks is not a station, so when there are NO similar
        // tracks, borrow the artist graph's neighbours even if seedTop is full.
        // Only with YouTube on: neighbour candidates come from YT popular lists,
        // the only source for them — with YouTube off the addon-only path over the
        // seed + top tracks (plus search variants) is kept.
        val fallback = ArrayList<RadioCandidate>()
        if (similar.isEmpty()) {
            if (youtubeOn()) {
                val neighborResult = firstNonEmptyByArtist(seed.artist) { artist ->
                    lastFm.getSimilarArtists(artist, limit = NEIGHBOR_MAX).getOrDefault(emptyList())
                }
                val neighbors = neighborResult.items.take(NEIGHBOR_POOL)
                val neighborTracks = coroutineScope {
                    neighbors.map { n -> async { n to resolvePopular(n.name) } }.awaitAll()
                }
                for ((neighbor, popular) in neighborTracks) {
                    val weight = neighbor.match.coerceAtLeast(0.05f)
                    popular.take(TRACKS_PER_ARTIST).forEach {
                        fallback += RadioCandidate(neighbor.name, it.title, it.videoId, weight, it.thumbnailUrl)
                    }
                }
                Log.d(
                    TAG,
                    "song seed fallback: similar=0 neighbours=${neighbors.size} candidates=${fallback.size}",
                )
            } else if (addonsOn()) {
                // YouTube off: there is no artist graph to borrow (neighbour
                // candidates are YT popular lists). Ask the addon for the artist's
                // own catalogue instead — one search, then the normal addon
                // resolution path. Artist-keyed by the SEED so the station stays
                // coherent; a fixed mid weight keeps them present without
                // dominating the seed share.
                val artistHits = addonArtistTracks(seed.artist, limit = ADDON_ARTIST_SEARCH_LIMIT)
                for (hit in artistHits) {
                    fallback += RadioCandidate(
                        seed.artist,
                        hit.title,
                        null,
                        ADDON_ARTIST_WEIGHT,
                        hit.artworkUrl,
                    )
                }
                Log.d(
                    TAG,
                    "song seed fallback: addonArtist=${artistHits.size} candidates=${fallback.size}",
                )
            }
        }

        val pool = ArrayList<RadioCandidate>()
        // Seed share: the seeded track first, then the seed artist's top tracks.
        pool += RadioCandidate(seed.artist, seed.title, seed.ytVideoId, seedWeight)
        seedTop.filterNot { it.title.equals(seed.title, ignoreCase = true) }
            .forEach { pool += RadioCandidate(seed.artist, it.title, null, seedWeight) }
        // Neighbors: each similar track, weighted by match; artist keyed for interleave.
        for (s in similar) {
            pool += RadioCandidate(s.artist, s.title, null, s.match.coerceAtLeast(0.05f))
        }
        pool += fallback
        val session = RadioSession(seed, RadioInterleaver.order(pool).toMutableList(), mutableSetOf())
        return session to drainBatch(session, FIRST_BATCH)
    }

    companion object {
        const val SEED_SHARE = 1f / 3f
        const val NEIGHBOR_LIMIT = 30    // Last.fm page size for song getSimilarTracks
        const val NEIGHBOR_MAX = 100     // full artist-neighbor list (paged by widen)
        const val NEIGHBOR_POOL = 12     // neighbors consumed per pool/widen slice
        const val TRACKS_PER_ARTIST = 6
        const val FIRST_BATCH = 12
        const val GROW_BATCH = 12
        const val WIDEN_THRESHOLD = 6
        const val SONG_SIMILAR_POOL = 40

        /** Diagnostics tag: seeds, pool sizes, resolve counts and stage timings. */
        private const val TAG = "RadioStation"

        /** Extra candidates resolved ahead of a batch, to cover typical misses. */
        const val RESOLVE_SLACK = 3

        /** YouTube searches in flight at once while prefetching. */
        const val RESOLVE_PARALLELISM = 8

        /** Prefix for the synthetic addon track id and the played-set dedup key. */
        private const val ADDON_PREFIX = "addon:"

        /** `/search` hits considered when `/resolve` missed a radio candidate. */
        private const val ADDON_SEARCH_LIMIT = 10

        /** Query variants tried per candidate: full credit and cleaned+primary, in parallel. */
        private const val ADDON_SEARCH_VARIANT_LIMIT = 2

        /** Artist-search limit for the addon-only artist fallback (one call). */
        private const val ADDON_ARTIST_SEARCH_LIMIT = 25

        /** Fixed mid weight for addon artist-fallback candidates. */
        private const val ADDON_ARTIST_WEIGHT = 0.6f
    }
}
