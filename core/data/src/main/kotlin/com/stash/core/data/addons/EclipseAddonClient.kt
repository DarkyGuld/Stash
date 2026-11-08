package com.stash.core.data.addons

import android.util.Log
import com.stash.core.auth.store.EncryptedTokenStore
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What an Eclipse addon advertises at its `manifest.json`. */
@Serializable
data class EclipseAddonManifest(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val description: String? = null,
    val icon: String? = null,
    /** Which protocol routes it serves: `search`, `stream`, `resolve`, `isrc`, … */
    val resources: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val contentType: String? = null,
    /** Its own settings. Array of `{key, default, …}` or an object of `{key: {default}}`. */
    val settings: JsonElement? = null,
    /** Advertised capabilities; unknown shapes are ignored. */
    val capabilities: JsonElement? = null,
) {
    /**
     * The manifest's setting declarations, whichever shape it used: an array of
     * `{key, type, label, default, options:[{value,label}]}` objects, or an object
     * of `{key: {…}}` (a primitive value is shorthand for its default).
     */
    val settingDefinitions: List<EclipseAddonSetting>
        get() {
            val element = settings ?: return emptyList()
            return when (element) {
                is JsonArray -> element.mapNotNull { it as? JsonObject }.mapNotNull { it.toSettingDefinition() }

                is JsonObject -> element.mapNotNull { (key, value) ->
                    val obj = value as? JsonObject
                    if (obj == null) {
                        value.asQueryValue()?.let { EclipseAddonSetting(key = key, default = it) }
                    } else {
                        obj.toSettingDefinition(key = key)
                    }
                }

                else -> emptyList()
            }
        }

    /** Setting key → default value. */
    val settingDefaults: Map<String, String>
        get() = settingDefinitions.mapNotNull { def -> def.default?.let { def.key to it } }.toMap()

    /** Setting key → its allowed values, for the settings that declare `options`. */
    val settingOptions: Map<String, List<String>>
        get() = settingDefinitions.filter { it.options.isNotEmpty() }.associate { def ->
            def.key to def.options.map { it.value }
        }

    private fun JsonObject.toSettingDefinition(key: String? = null): EclipseAddonSetting? {
        val resolvedKey = key ?: (this["key"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return null
        return EclipseAddonSetting(
            key = resolvedKey,
            type = (this["type"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: "text",
            label = (this["label"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            help = (this["help"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() },
            default = (this["default"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() },
            perNetwork = (this["perNetwork"] as? JsonPrimitive)?.contentOrNull?.toBoolean() == true,
            options = this["options"].asOptions(),
            maxLength = (this["maxLength"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            min = (this["min"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
            max = (this["max"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
            step = (this["step"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
        )
    }

    private fun JsonElement?.asOptions(): List<EclipseAddonOption> =
        (this as? JsonArray)?.mapNotNull { option ->
            when (option) {
                is JsonPrimitive -> option.contentOrNull?.takeIf { it.isNotEmpty() }
                    ?.let { EclipseAddonOption(value = it) }
                is JsonObject -> (option["value"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                    ?.let { value ->
                        val label = (option["label"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                        EclipseAddonOption(value = value, label = label ?: value)
                    }
                else -> null
            }
        } ?: emptyList()

    private fun JsonElement.asQueryValue(): String? =
        (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
}

/** One search hit, or one `/resolve` item. [duration] is seconds per the protocol; see [durationMs]. */
@Serializable
data class EclipseAddonTrack(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val duration: Long = 0,
    @SerialName("artworkURL") val artworkUrl: String? = null,
    val isrc: String? = null,
    val format: String? = null,
    @SerialName("streamURL") val streamUrl: String? = null,
) {
    /** [duration] in milliseconds, whichever unit the addon used (seconds, or already ms). */
    val durationMs: Long
        get() = when {
            duration <= 0 -> 0
            duration < 10_000 -> duration * 1_000
            else -> duration
        }
}

/** One song hit from Apple's public iTunes Search API (Cloud's Apple Music route needs its trackId). */
@Serializable
data class EclipseAppleTrack(
    @SerialName("trackId") val trackId: Long = 0,
    @SerialName("trackName") val trackName: String = "",
    @SerialName("artistName") val artistName: String = "",
    @SerialName("collectionName") val collectionName: String? = null,
    @SerialName("trackTimeMillis") val trackTimeMillis: Long = 0,
    val isrc: String? = null,
)

/** The playable answer from `stream/{id}`. */
@Serializable
data class EclipseAddonStream(
    val url: String = "",
    val format: String? = null,
    val quality: String? = null,
    /** Unix seconds per the protocol; some addons send milliseconds. */
    val expiresAt: Long? = null,
    val codec: String? = null,
    val container: String? = null,
    /** `none`, `hls`, or `dash`. */
    val manifest: String? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
)

/** A typed failure from one addon call, so callers can tell a miss from "preparing". */
sealed class EclipseAddonException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The addon's URL is unusable (not https, unparseable). */
    class InvalidUrl(message: String) : EclipseAddonException(message)

    /** 503: the addon is preparing the stream (e.g. a TorBox cache miss). Treat as a miss for now. */
    class Preparing(val retryAfterSeconds: Int? = null) : EclipseAddonException("addon is preparing the stream")

    /** Any other non-2xx answer. */
    class HttpFailure(val code: Int) : EclipseAddonException("HTTP $code")

    /** Offline, timeout, DNS, or a connection the addon dropped. */
    class Unreachable(cause: Throwable? = null) : EclipseAddonException("addon unreachable", cause)

    /** A 2xx whose body is not the JSON shape the protocol promises. */
    class InvalidResponse(cause: Throwable? = null) : EclipseAddonException("invalid addon response", cause)
}

/**
 * Talks the Eclipse Music addon protocol (https://eclipsemusic.app/docs):
 * `GET {base}/manifest.json`, `/search?q=…`, `/resolve?…`, `/resolve-isrc?isrc=…`,
 * `/stream/{id}`.
 *
 * Every request is derived from the installed manifest URL with [HttpUrl], so a
 * token in the path (`/abc123/manifest.json`) or the query (`?token=…`) is kept
 * on every call — an addon that needs it answers 401 without it. The addon's
 * [EclipseAddonManifest.settingDefaults] go on each request too, as Eclipse expects.
 *
 * Account-bound Eclipse addons (`api.eclipsemusic.app/addon/…`) additionally get
 * `Authorization: Bearer <account token>` and a short-lived `X-Eclipse-Access`
 * header minted for the addon id. Both are sent ONLY to that host; a third-party
 * addon never sees the user's Eclipse credentials. No account token means
 * neither header and no minting.
 */
@Singleton
class EclipseAddonClient @Inject constructor(
    http: OkHttpClient,
    private val tokenStore: EncryptedTokenStore,
) {
    // Bounds the blocking call itself, not just the coroutine awaiting it.
    private val http = http.newBuilder()
        .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Stream lookups only: TorBox-backed addons can cache a release for longer than
     * the metadata timeout, and an 8 s cut-off turned a slow hit into a chain miss.
     * Manifest/search/resolve keep the short bound.
     */
    private val streamHttp = http.newBuilder()
        .callTimeout(STREAM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** Minted `X-Eclipse-Access` values, per addon id. */
    private val accessTokens = ConcurrentHashMap<String, AccessToken>()

    /** Per addon id: no mint before this instant (a failed mint cools down). */
    private val accessCooldownUntilMs = ConcurrentHashMap<String, Long>()

    private val mintMutex = Mutex()

    suspend fun fetchManifest(manifestUrl: String): EclipseAddonManifest =
        getJson(manifestUrlOrThrow(manifestUrl)) { body ->
            json.decodeFromString<EclipseAddonManifest>(body)
        }

    /** Searches one addon. Accepts `{"tracks":[…]}` or a bare array. */
    suspend fun search(
        manifestUrl: String,
        query: String,
        limit: Int = 25,
        settings: Map<String, String> = emptyMap(),
        addonId: String? = null,
    ): List<EclipseAddonTrack> {
        val url = endpoint(manifestUrl, listOf("search"), settings)
            .newBuilder()
            .setQueryParameter("q", query)
            .build()
        return getJson(url, addonId) { body -> parseTracks(body).take(limit) }
    }

    /**
     * `GET /resolve` (Eclipse's metadata→item lookup). Null on a miss: `{"item":null}`,
     * 403 `Unavailable`, or 404. [isrc] is authoritative when the addon has one;
     * the title/artist/duration are the fallback key.
     */
    suspend fun resolve(
        manifestUrl: String,
        title: String,
        artist: String?,
        durationMs: Long?,
        isrc: String?,
        settings: Map<String, String> = emptyMap(),
        addonId: String? = null,
        quality: String? = null,
    ): EclipseAddonTrack? {
        val base = endpoint(manifestUrl, listOf("resolve"), settings).newBuilder()
            .setQueryParameter("title", title)
        if (!artist.isNullOrBlank()) base.setQueryParameter("artist", artist)
        if (durationMs != null && durationMs > 0) base.setQueryParameter("durationMs", durationMs.toString())
        if (!isrc.isNullOrBlank()) base.setQueryParameter("isrc", isrc)
        if (!quality.isNullOrBlank()) base.setQueryParameter("quality", quality)
        val body = getBody(base.build(), addonId, missCodes = MISS_CODES) ?: return null
        return parseBody(body) { parseResolvedTrack(it) }
    }

    /**
     * `GET {base}/…/resolve-isrc?isrc=…`; null when the addon says 404/403 or has
     * no id. [path] is `resolve-isrc` for a normal addon and `tidal/resolve-isrc`
     * for Eclipse's Cloud addon.
     */
    suspend fun resolveIsrc(
        manifestUrl: String,
        isrc: String,
        settings: Map<String, String> = emptyMap(),
        addonId: String? = null,
        path: List<String> = listOf("resolve-isrc"),
    ): EclipseAddonTrack? {
        val url = endpoint(manifestUrl, path, settings).newBuilder()
            .setQueryParameter("isrc", isrc)
            .build()
        val body = getBody(url, addonId, missCodes = MISS_CODES) ?: return null
        return parseBody(body) { parseResolvedId(it, isrc) }
    }

    suspend fun stream(
        manifestUrl: String,
        trackId: String,
        settings: Map<String, String> = emptyMap(),
        addonId: String? = null,
    ): EclipseAddonStream =
        getJson(endpoint(manifestUrl, listOf("stream", trackId), settings), addonId, client = streamHttp) { body ->
            json.decodeFromString<EclipseAddonStream>(body)
        }

    /**
     * Eclipse Cloud's stream route: `GET {base}/stream?trackId=…&quality=…`
     * (there is no `/stream/{id}` there). A non-200 at [quality] is retried once
     * at CD quality ([LOSSLESS_QUALITY]); anything else propagates.
     */
    suspend fun streamCloud(
        manifestUrl: String,
        trackId: String,
        quality: String,
        settings: Map<String, String> = emptyMap(),
        addonId: String? = null,
    ): EclipseAddonStream {
        suspend fun fetch(q: String): EclipseAddonStream {
            val url = endpoint(manifestUrl, listOf("stream"), settings).newBuilder()
                .setQueryParameter("trackId", trackId)
                .setQueryParameter("quality", q)
                .build()
            return getJson(url, addonId, client = streamHttp) { body ->
                json.decodeFromString<EclipseAddonStream>(body)
            }
        }

        return try {
            fetch(quality)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (quality == LOSSLESS_QUALITY) throw e
            // Hi-res refused: CD quality is the same catalog's safe tier.
            fetch(LOSSLESS_QUALITY)
        }
    }

    /**
     * Cloud's Apple Music route, part two: the playable stream for an Apple track
     * id. Same Cloud headers as every addon call (browser UA, Authorization,
     * `X-Client-Platform`); the response is the usual stream shape.
     */
    suspend fun streamApple(
        manifestUrl: String,
        appleTrackId: String,
        appleQuality: String,
        isrc: String? = null,
        addonId: String? = null,
    ): EclipseAddonStream {
        // Cloud's manifest already lives under `/music`; the Apple route hangs
        // directly off that base (`…/music/applemusic/stream`).
        val builder = endpoint(manifestUrl, listOf("applemusic", "stream"), emptyMap())
            .newBuilder()
            .setQueryParameter("trackId", appleTrackId)
            .setQueryParameter("appleQuality", appleQuality)
        if (!isrc.isNullOrBlank()) builder.setQueryParameter("isrc", isrc)
        return getJson(builder.build(), addonId, client = streamHttp) { body ->
            json.decodeFromString<EclipseAddonStream>(body)
        }
    }

    /**
     * Cloud's Apple Music route, part one: the public iTunes Search API (no
     * Eclipse auth). The device's country and the US fallback run concurrently
     * when they differ — the first non-empty result wins, the other is cancelled —
     * so a catalog miss costs one search, not two sequential ones.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun searchAppleTracks(title: String, artist: String): List<EclipseAppleTrack> {
        val term = "$title $artist".trim()
        if (term.isEmpty()) return emptyList()
        val localeCountry = java.util.Locale.getDefault().country.takeIf { it.length == 2 } ?: APPLE_FALLBACK_COUNTRY
        if (localeCountry == APPLE_FALLBACK_COUNTRY) return fetchAppleSearch(term, localeCountry)

        return coroutineScope {
            val locale = async { runAppleSearch(term, localeCountry) }
            val fallback = async { runAppleSearch(term, APPLE_FALLBACK_COUNTRY) }
            val first = select<Pair<Deferred<List<EclipseAppleTrack>>, List<EclipseAppleTrack>>> {
                locale.onAwait { locale to it }
                fallback.onAwait { fallback to it }
            }
            if (first.second.isNotEmpty()) {
                (if (first.first === locale) fallback else locale).cancel()
                return@coroutineScope first.second
            }
            val other = if (first.first === locale) fallback else locale
            val second = try {
                other.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            second
        }
    }

    /** One country's search; a failed answer is an empty list so it never cancels its sibling. */
    private suspend fun runAppleSearch(term: String, country: String): List<EclipseAppleTrack> =
        try {
            fetchAppleSearch(term, country)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    private suspend fun fetchAppleSearch(term: String, country: String): List<EclipseAppleTrack> {
        val url = ITUNES_SEARCH_URL.newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", APPLE_SEARCH_LIMIT.toString())
            .addQueryParameter("country", country)
            .build()
        val body = getBody(url) ?: return emptyList()
        return parseBody(body) { text ->
            val root = json.parseToJsonElement(text) as? JsonObject
                ?: return@parseBody emptyList()
            (root["results"] as? JsonArray)?.mapNotNull { element ->
                runCatching {
                    json.decodeFromJsonElement(EclipseAppleTrack.serializer(), element)
                }.getOrNull()
            } ?: emptyList()
        }
    }

    /**
     * Mints a fresh `X-Eclipse-Access` for [addonId] now, bypassing the cache and
     * any cooldown: the Settings screen's "Test" for a pasted account token.
     * False when no account token is stored or the mint fails. Never throws.
     */
    suspend fun testAccess(addonId: String): Boolean {
        val accountToken = tokenStore.getEclipseToken() ?: return false
        accessTokens.remove(addonId)
        accessCooldownUntilMs.remove(addonId)
        return try {
            mintAccess(addonId, accountToken)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Status/type only: never the URL or a token.
            Log.w(TAG, "eclipse access test failed: ${failureSummary(e)}")
            false
        }
    }

    private fun parseTracks(body: String): List<EclipseAddonTrack> {
        val element = json.parseToJsonElement(body)
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> element["tracks"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return array.map { json.decodeFromJsonElement(EclipseAddonTrack.serializer(), it) }
    }

    /** `{"item":{…}}`, `{"item":null}`, or a bare item object. */
    private fun parseResolvedTrack(body: String): EclipseAddonTrack? {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        if (root.containsKey("item") && root["item"] !is JsonObject) return null
        val item = root["item"] as? JsonObject ?: root
        val track = json.decodeFromJsonElement(EclipseAddonTrack.serializer(), item)
        return track.takeIf { it.id.isNotBlank() }
    }

    /** `{"trackId":"…"}` or `{"id":"…"}`. */
    private fun parseResolvedId(body: String, isrc: String): EclipseAddonTrack? {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        val id = root.string("trackId") ?: root.string("id") ?: return null
        return EclipseAddonTrack(id = id, isrc = isrc)
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private suspend fun <T> getJson(
        url: HttpUrl,
        addonId: String? = null,
        client: OkHttpClient = http,
        parse: (String) -> T,
    ): T {
        val body = getBody(url, addonId, client = client) ?: throw EclipseAddonException.InvalidResponse()
        return parseBody(body, parse)
    }

    private fun <T> parseBody(body: String, parse: (String) -> T): T =
        try {
            parse(body)
        } catch (e: SerializationException) {
            throw EclipseAddonException.InvalidResponse(e)
        } catch (e: IllegalArgumentException) {
            throw EclipseAddonException.InvalidResponse(e)
        }

    /**
     * [missCodes] answer a normal miss (null) rather than a failure: `/resolve`
     * uses 403 `Unavailable` for "not in this catalog right now", and 404 for
     * "no such route/item".
     */
    private suspend fun getBody(
        url: HttpUrl,
        addonId: String? = null,
        missCodes: Set<Int> = emptySet(),
        client: OkHttpClient = http,
    ): String? =
        withContext(Dispatchers.IO) {
            // The client's callTimeout bounds this blocking call in wall-clock time; a
            // coroutine withTimeout could not interrupt it anyway, and its cancellation
            // would read as tap-preemption all the way up the resolve chain.
            val request = requestBuilder(url, addonId).get().build()
            try {
                executeAsync(client, request).use { response ->
                    if (response.code == 503) {
                        throw EclipseAddonException.Preparing(parseRetryAfter(response.header("Retry-After")))
                    }
                    if (response.code in missCodes) return@use null
                    if (!response.isSuccessful) throw EclipseAddonException.HttpFailure(response.code)
                    response.body?.string().orEmpty()
                }
            } catch (e: IOException) {
                throw EclipseAddonException.Unreachable(e)
            }
        }

    /** `Retry-After` in whole seconds, capped: a proxied addon may say 300. */
    private fun parseRetryAfter(header: String?): Int? =
        header?.trim()?.toIntOrNull()?.coerceIn(0, MAX_RETRY_AFTER_SECONDS)

    /**
     * OkHttp's blocking `execute()` cannot be interrupted by coroutine
     * cancellation; this enqueues the call and cancels it on cancellation, so a
     * losing Tidal lookup (Cloud fast mode) stops at once instead of running to
     * completion.
     */
    private suspend fun executeAsync(client: OkHttpClient, request: Request): okhttp3.Response =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
        }

    /**
     * Eclipse's own host only: a browser UA, or its WAF answers 403 even on
     * `manifest.json`. Third-party addons keep Stash's honest UA.
     */
    private fun userAgentFor(url: HttpUrl): String =
        if (url.host == ECLIPSE_HOST) BROWSER_USER_AGENT else USER_AGENT

    /**
     * The common headers. `X-Client-Platform: web` on every addon request (Eclipse's
     * addons check it); the account's Bearer and the minted `X-Eclipse-Access` only
     * when this really is an Eclipse-hosted `/addon/` URL and a token is configured.
     */
    private suspend fun requestBuilder(url: HttpUrl, addonId: String?): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgentFor(url))
            .header("X-Client-Platform", CLIENT_PLATFORM)

        val accountToken = tokenStore.getEclipseToken() ?: return builder
        if (!isEclipseAccountUrl(url)) return builder

        builder.header("Authorization", "Bearer $accountToken")
        // Cloud does not use X-Eclipse-Access (the web client sends none), so skip
        // the mint entirely for it: one less round trip on every request.
        if (addonId != null && !isCloudAddonId(addonId)) {
            accessToken(addonId, accountToken)?.let { builder.header("X-Eclipse-Access", it) }
        }
        return builder
    }

    /**
     * The cached `X-Eclipse-Access` for [addonId], minting when it is within 30 s
     * of expiry. A failed mint starts a 60 s cooldown and returns null: the call
     * still goes out, just without the header (the addon answers 403, a miss).
     */
    private suspend fun accessToken(addonId: String, accountToken: String): String? {
        val now = System.currentTimeMillis()
        accessTokens[addonId]?.takeIf { it.expiresAtMs - EXPIRY_SKEW_MS > now }?.let { return it.token }
        if (now < (accessCooldownUntilMs[addonId] ?: 0L)) return null

        return mintMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            accessTokens[addonId]?.takeIf { it.expiresAtMs - EXPIRY_SKEW_MS > lockedNow }?.let { return@withLock it.token }
            if (lockedNow < (accessCooldownUntilMs[addonId] ?: 0L)) return@withLock null
            try {
                mintAccess(addonId, accountToken).token
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                accessCooldownUntilMs[addonId] = lockedNow + MINT_COOLDOWN_MS
                Log.w(TAG, "eclipse access mint failed: ${failureSummary(e)}")
                null
            }
        }
    }

    /** `POST /api/addon-access/token?addon=<id>` with the account's Bearer. */
    private suspend fun mintAccess(addonId: String, accountToken: String): AccessToken =
        withContext(Dispatchers.IO) {
            val url = MINT_URL.newBuilder().addQueryParameter("addon", addonId).build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("X-Client-Platform", CLIENT_PLATFORM)
                .header("Authorization", "Bearer $accountToken")
                .post(EMPTY_BODY)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw EclipseAddonException.HttpFailure(response.code)
                val body = response.body?.string().orEmpty()
                val root = parseBody(body) { json.parseToJsonElement(it) as? JsonObject }
                    ?: throw EclipseAddonException.InvalidResponse()
                val token = root.string("token") ?: throw EclipseAddonException.InvalidResponse()
                val expiresAtMs = root.long("expiresAt")?.let { epochToMs(it) }
                    ?: root.long("expiresIn")?.let { System.currentTimeMillis() + it * 1_000 }
                    ?: (System.currentTimeMillis() + DEFAULT_ACCESS_TTL_MS)
                val access = AccessToken(token, expiresAtMs)
                accessTokens[addonId] = access
                access
            }
        }

    private fun JsonObject.long(name: String): Long? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun epochToMs(value: Long): Long = if (value < 10_000_000_000L) value * 1_000 else value

    private fun failureSummary(e: Exception): String =
        (e as? EclipseAddonException.HttpFailure)?.let { "HTTP ${it.code}" } ?: e::class.java.simpleName

    private data class AccessToken(val token: String, val expiresAtMs: Long)

    companion object {
        const val USER_AGENT: String = "Stash/0.9.123"

        /**
         * Eclipse's WAF answers 403 to a non-browser UA on `api.eclipsemusic.app`
         * (the manifest/install path included), while accepting the web client's.
         * Sent only to that host; everywhere else keeps [USER_AGENT].
         */
        const val BROWSER_USER_AGENT: String =
            "Mozilla/5.0 (Linux; Android 14; SM-S938B) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        /** Eclipse's web client identity; its addons gate on this. */
        const val CLIENT_PLATFORM: String = "web"

        private const val TAG = "EclipseAddonClient"

        private const val TIMEOUT_MS = 8_000L

        /** Stream lookups only: backend caching can take longer than a metadata call. */
        private const val STREAM_TIMEOUT_MS = 30_000L

        private const val MAX_RETRY_AFTER_SECONDS = 30
        private const val MANIFEST = "manifest.json"
        private val MISS_CODES = setOf(403, 404)

        const val LOSSLESS_QUALITY: String = "LOSSLESS"

        private const val ECLIPSE_HOST = "api.eclipsemusic.app"
        private val ECLIPSE_PATH_PREFIX = listOf("addon")
        private val MINT_URL = "https://api.eclipsemusic.app/api/addon-access/token".toHttpUrlOrNull()!!

        /** Apple's public catalog search (Cloud's Apple Music fallback). */
        private val ITUNES_SEARCH_URL = "https://itunes.apple.com/search".toHttpUrlOrNull()!!
        private const val APPLE_SEARCH_LIMIT = 10
        private const val APPLE_FALLBACK_COUNTRY = "US"

        /** Minted access tokens are truncated by this much before expiry. */
        private const val EXPIRY_SKEW_MS = 30_000L
        private const val MINT_COOLDOWN_MS = 60_000L
        private const val DEFAULT_ACCESS_TTL_MS = 5 * 60_000L

        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

        /**
         * The addon's full `manifest.json` URL: [raw] trimmed, and `manifest.json`
         * appended when the user pasted only the addon's base. Null when it is not
         * an https URL. This is the one place addon URLs are validated.
         */
        fun manifestUrlOrNull(raw: String?): String? {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val url = trimmed.toHttpUrlOrNull() ?: return null
            if (!url.isHttps) return null
            if (url.pathSegments.lastOrNull() == MANIFEST) return url.toString()
            return url.newBuilder().addPathSegment(MANIFEST).build().toString()
        }

        /** True only for Eclipse's own account-bound addons under `https://api.eclipsemusic.app/addon/…`. */
        fun isEclipseAccountUrl(url: HttpUrl): Boolean =
            url.host == ECLIPSE_HOST && url.pathSegments.take(ECLIPSE_PATH_PREFIX.size) == ECLIPSE_PATH_PREFIX

        /** Eclipse's own Cloud addon; identified by its manifest id. */
        fun isCloudAddonId(addonId: String): Boolean =
            addonId.startsWith(EclipseAddon.ECLIPSE_CLOUD_ID_PREFIX)

        private fun manifestUrlOrThrow(raw: String): HttpUrl =
            manifestUrlOrNull(raw)?.toHttpUrlOrNull()
                ?: throw EclipseAddonException.InvalidUrl("not an https addon URL")

        /**
         * [manifestUrl]'s directory plus [segments], keeping every query parameter
         * (addon tokens) and adding the addon's setting defaults where absent.
         */
        private fun endpoint(
            manifestUrl: String,
            segments: List<String>,
            settings: Map<String, String>,
        ): HttpUrl {
            val manifest = manifestUrlOrThrow(manifestUrl)
            val builder = HttpUrl.Builder()
                .scheme(manifest.scheme)
                .host(manifest.host)
                .port(manifest.port)
            manifest.pathSegments.dropLast(1).forEach { builder.addPathSegment(it) }
            segments.forEach { builder.addPathSegment(it) }
            val existing = manifest.queryParameterNames
            manifest.queryParameterNames.forEach { name ->
                manifest.queryParameterValues(name).forEach { builder.addQueryParameter(name, it) }
            }
            settings.forEach { (key, value) ->
                if (key !in existing && value.isNotEmpty()) builder.addQueryParameter(key, value)
            }
            return builder.build()
        }
    }
}
