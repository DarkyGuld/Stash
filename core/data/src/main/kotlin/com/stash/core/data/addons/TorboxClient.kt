package com.stash.core.data.addons

import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import javax.inject.Inject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * TorBox, from the phone, with the user's own key.
 *
 * The server's catalogue says *what* a file is: its infohash, which is the
 * same in every TorBox account in the world. What lives in one account is
 * which torrent holds the file - and that is what this phone keeps to
 * itself.
 *
 * So the phone asks TorBox itself:
 *
 *  - `requestdl` turns the torrent id into a CDN url.
 *  - `createtorrent` adds the file to this account, so the next play has a
 *    torrent id to work with.
 *
 * Both are cheap: a cached file is there in a moment.
 */
class TorboxClient @Inject constructor(
    http: OkHttpClient,
) {
    private val http = http.newBuilder()
        .callTimeout(CALL_TIMEOUT_MS, TimeUnit.SECONDS)
        .build()

    private val baseUrl: String = DEFAULT_BASE_URL
    /**
     * The CDN url for a file in this account, or null.
     *
     * @param torrentId which torrent in this account holds the file
     * @param fileId which file inside it, when the torrent holds several
     */
    suspend fun requestdl(apiKey: String, torrentId: String, fileId: String? = null, userIp: String? = null): String? =
        withContext(Dispatchers.IO) {
            val url = buildUrl {
                addPathSegments("torrents/requestdl")
                addQueryParameter("token", apiKey)
                addQueryParameter("torrent_id", torrentId)
                if (!fileId.isNullOrBlank()) addQueryParameter("file_id", fileId)
                addQueryParameter("redirect", "true")
                if (!userIp.isNullOrBlank()) addQueryParameter("user_ip", userIp)
            }
            try {
                val request = Request.Builder().url(url).head().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.i(TAG, "requestdl said no: HTTP ${response.code}")
                        return@use null
                    }
                    val location = response.header("Location")
                    if (location.isNullOrBlank() || location == "None") {
                        Log.i(TAG, "requestdl has no location yet")
                        null
                    } else {
                        location
                    }
                }
            } catch (e: IOException) {
                Log.i(TAG, "requestdl failed: ${e.message}")
                null
            }
        }

    /**
     * Adds a file to this account and returns its torrent id.
     *
     * The magnet carries the infohash, so TorBox finds the file in its own
     * cache when it has it.
     */
    suspend fun createtorrent(apiKey: String, infohash: String, name: String, seed: Int = 3): String? =
        withContext(Dispatchers.IO) {
            if (!infohash.matches(INFOHASH)) {
                Log.i(TAG, "not an infohash: ${infohash.take(12)}")
                return@withContext null
            }
            val body = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("seed", seed.toString())
                .addFormDataPart("allow_zip", "false")
                .addFormDataPart("name", name)
                .addFormDataPart("magnet", "magnet:?xt=urn:btih:$infohash")
                .build()
            try {
                val request = Request.Builder()
                    .url(buildUrl { addPathSegments("torrents/createtorrent") })
                    .header("Authorization", "Bearer $apiKey")
                    .post(body)
                    .build()
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.i(TAG, "createtorrent said no: HTTP ${response.code}")
                        return@use null
                    }
                    val id = Regex("\"torrent_id\"\\s*:\\s*\"?([0-9]+)").find(text)?.groupValues?.get(1)
                    if (id == null) Log.i(TAG, "createtorrent answered without a torrent id")
                    id
                }
            } catch (e: IOException) {
                Log.i(TAG, "createtorrent failed: ${e.message}")
                null
            }
        }

    /**
     * Is the file in TorBox's own cache? Then adding it is instant.
     */
    suspend fun isCached(apiKey: String, infohash: String): Boolean = withContext(Dispatchers.IO) {
        if (!infohash.matches(INFOHASH)) return@withContext false
        val payload = """{"hashes":["$infohash"]}"""
            .toRequestBody(JSON)
        try {
            val request = Request.Builder()
                .url(buildUrl { addPathSegments("torrents/checkcached"); addQueryParameter("format", "object") })
                .post(payload)
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@use false
                Regex("\"$infohash\"").containsMatchIn(text)
            }
        } catch (e: IOException) {
            Log.i(TAG, "checkcached failed: ${e.message}")
            false
        }
    }

    private fun buildUrl(block: okhttp3.HttpUrl.Builder.() -> Unit): String {
        val builder = baseUrl.toHttpUrl().newBuilder()
        builder.block()
        return builder.build().toString()
    }

    companion object {
        private const val TAG = "TorboxClient"
        private const val DEFAULT_BASE_URL = "https://api.torbox.app/v1/api"
        private val INFOHASH = Regex("^[0-9a-f]{40}$")
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** A play must not hang on TorBox. */
        private const val CALL_TIMEOUT_MS = 10L
    }
}
