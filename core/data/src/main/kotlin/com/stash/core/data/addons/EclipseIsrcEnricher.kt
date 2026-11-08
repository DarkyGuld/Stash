package com.stash.core.data.addons

import android.util.Log
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.spotify.SpotifyApiClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

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

    suspend fun isrcFor(track: TrackEntity): String? {
        track.isrc?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val key = cacheKey(track)
        memory[key]?.let { return it }

        val fromSpotify = try {
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
        val url = "https://api.deezer.com/search".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("q", "${track.title} ${track.artist}".trim())
            .addQueryParameter("limit", "10")
            .build()
        val body = runCatching {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.getOrNull() ?: return@withContext null
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return@withContext null
        val rows = root["data"] as? JsonArray ?: return@withContext null
        val wantTitle = normalize(track.title)
        val wantArtist = normalize(track.artist)
        val wantMs = track.durationMs
        for (row in rows) {
            val obj = row as? JsonObject ?: continue
            val title = (obj["title"] as? JsonPrimitive)?.contentOrNull ?: continue
            val artist = ((obj["artist"] as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull ?: continue
            val isrc = (obj["isrc"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
            if (normalize(title) != wantTitle) continue
            val candidateArtist = normalize(artist)
            if (candidateArtist != wantArtist &&
                !candidateArtist.contains(wantArtist) && !wantArtist.contains(candidateArtist)
            ) continue
            val seconds = (obj["duration"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0
            if (wantMs > 0 && seconds > 0 && kotlin.math.abs(wantMs - seconds * 1_000) > 10_000) continue
            return@withContext isrc
        }
        null
    }

    private fun normalize(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private companion object {
        const val TAG = "EclipseIsrcEnricher"
    }
}
