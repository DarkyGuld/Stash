package com.stash.core.data.addons

import android.util.Log
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.spotify.SpotifyApiClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Best-effort ISRC for Eclipse's Cloud Apple route: some tracks only stream when
 * the `/applemusic/stream` call carries the ISRC (Eclipse web sends it). The
 * Spotify Web API's `external_ids.isrc` is authoritative; when there is no
 * Spotify URI/token, Deezer's public search is the fallback. A found ISRC is
 * backfilled into the track row and remembered in memory, so a second play
 * never asks again. Every failure is just "no ISRC".
 */
@Singleton
class EclipseIsrcEnricher @Inject constructor(
    private val spotify: SpotifyApiClient,
    private val trackDao: TrackDao,
    private val gate: SpotifyIsrcGate,
    httpClient: OkHttpClient,
) {
    private val http = httpClient.newBuilder().build()
    private val json = Json { ignoreUnknownKeys = true }
    private val memory = ConcurrentHashMap<String, String>()

    /** Log the backoff decision once per process, not once per track. */
    private val backoffLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun isrcFor(track: TrackEntity): String? {
        track.isrc?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val key = cacheKey(track)
        memory[key]?.let { return it }

        val backoffUntil = gate.backoffUntilMs()
        val spotifyCoolingDown = System.currentTimeMillis() < backoffUntil
        if (spotifyCoolingDown && backoffLogged.compareAndSet(false, true)) {
            Log.d(TAG, "Spotify ISRC backoff active; using Deezer for lookups until it lifts")
        }
        val fromSpotify = if (spotifyCoolingDown) null else try {
            val id = spotifyTrackId(track.spotifyUri)
            if (id == null) null else {
                gate.beginForeground()
                try {
                    when (val batch = spotify.getTracksIsrc(listOf(id))) {
                        is SpotifyApiClient.IsrcBatch.Success -> batch.isrcById[id]
                        is SpotifyApiClient.IsrcBatch.RateLimited -> {
                            gate.setBackoff(batch.retryAfterSeconds)
                            Log.d(TAG, "Spotify 429 for the per-play ISRC; using the fallback")
                            null
                        }
                        SpotifyApiClient.IsrcBatch.Failed -> null
                    }
                } finally {
                    gate.endForeground()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val isrc = fromSpotify ?: try {
            deezerIsrc(track)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (isrc != null) {
            memory[key] = isrc
            if (track.id > 0) {
                runCatching { trackDao.backfillIsrcIfMissing(track.id, isrc) }
            }
            Log.d(TAG, "ISRC found for ${track.id} (${if (fromSpotify != null) "spotify" else "deezer"})")
        }
        return isrc
    }

    private fun cacheKey(track: TrackEntity): String =
        track.spotifyUri?.takeIf { it.isNotBlank() }
            ?: "meta:${track.title.lowercase()}|${track.artist.lowercase()}|${track.durationMs}"

    private fun spotifyTrackId(uri: String?): String? {
        val raw = uri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.startsWith("spotify:track:")) return raw.removePrefix("spotify:track:").takeIf { it.isNotBlank() }
        val url = raw.toHttpUrlOrNull() ?: return null
        if (url.host != "open.spotify.com") return null
        return url.pathSegments.lastOrNull()?.takeIf { it.isNotBlank() }
    }

    private suspend fun deezerIsrc(track: TrackEntity): String? = withContext(Dispatchers.IO) {
        // The full "title + every credited artist" query is often empty at Deezer;
        // mirror the iTunes variant order, plus a title-only last resort.
        val queries = EclipseAddonQuery.variants(track.title, track.artist)
            .map { (title, artist) -> "$title $artist".trim() }
            .plus(track.title.trim())
            .filter { it.isNotEmpty() }
            .distinct()
        for (query in queries) {
            val found = deezerSearch(query, track)
            if (found != null) return@withContext found
        }
        null
    }

    private suspend fun deezerSearch(query: String, track: TrackEntity): String? {
        val url = "https://api.deezer.com/search".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", "10")
            .build()
        val body = deezerGet(url) ?: return null
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val rows = root["data"] as? JsonArray ?: return null
        val wantTitle = normalize(track.title)
        val cleanedTitle = normalize(EclipseAddonQuery.cleanedTitle(track.title))
        val wantArtist = normalize(track.artist)
        val primaryArtist = normalize(EclipseAddonQuery.primaryArtist(track.artist))
        val wantMs = track.durationMs
        for (row in rows) {
            val obj = row as? JsonObject ?: continue
            val title = (obj["title"] as? JsonPrimitive)?.contentOrNull ?: continue
            val artist = ((obj["artist"] as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull ?: continue
            val isrc = (obj["isrc"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
            val candidateTitle = normalize(title)
            if (candidateTitle != wantTitle && candidateTitle != cleanedTitle) continue
            val candidateArtist = normalize(artist)
            val artistOk = candidateArtist == wantArtist || candidateArtist == primaryArtist ||
                candidateArtist.contains(wantArtist) || wantArtist.contains(candidateArtist) ||
                candidateArtist.contains(primaryArtist) || primaryArtist.contains(candidateArtist)
            if (!artistOk) continue
            val seconds = (obj["duration"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0
            if (wantMs > 0 && seconds > 0 && kotlin.math.abs(wantMs - seconds * 1_000) > 10_000) continue
            return isrc
        }
        return null
    }

    /**
     * One Deezer GET on OkHttp's own threads: cancelling the coroutine drops the request,
     * and the per-call timeout stops a stalled answer instead of holding the Cloud resolve.
     */
    private suspend fun deezerGet(url: okhttp3.HttpUrl): String? =
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(Request.Builder().url(url).get().build())
            call.timeout().timeout(DEEZER_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWith(Result.success<String?>(null))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = runCatching {
                            response.use { if (it.isSuccessful) it.body?.string() else null }
                        }.getOrNull()
                        if (cont.isActive) cont.resumeWith(Result.success<String?>(body))
                    }
                },
            )
        }

    private fun normalize(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private companion object {
        const val TAG = "EclipseIsrcEnricher"

        /** A stalled Deezer query is dropped instead of holding the Apple route. */
        const val DEEZER_CALL_TIMEOUT_MS = 1_000L
    }
}
