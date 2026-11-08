package com.stash.core.data.addons

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The signed-in Eclipse Music account, as far as the UI needs it (never the token). */
@Serializable
data class EclipseUser(
    val id: String = "",
    val email: String? = null,
    val name: String? = null,
)

/** Why Eclipse login or token verification failed. */
sealed class EclipseAuthError {
    /** Wrong email/password, or the account token is no longer valid. */
    data object InvalidCredentials : EclipseAuthError()

    /** Offline, timeout, DNS. */
    data object Network : EclipseAuthError()

    /** A non-2xx answer, or a body that isn't the shape Eclipse promises. */
    data object Server : EclipseAuthError()
}

/** [Result] failure carrying a typed [EclipseAuthError]. Never contains credentials. */
class EclipseAuthException(val error: EclipseAuthError) : Exception(error.toString())

/** What a successful password login yields. */
data class EclipseLogin(val token: String, val user: EclipseUser)

/** What a successful token verification yields; [token] is present when Eclipse rotated it. */
data class EclipseVerify(val user: EclipseUser, val token: String?)

/**
 * Eclipse Music's mobile account endpoints:
 *  - `POST /api/auth/mobile/login` `{email,password}` → `{success,token,user}`
 *  - `GET  /api/auth/mobile/verify` Bearer token → `{success,user,token?}`
 *
 * Never logs: not the email, not the password, not the token.
 */
@Singleton
class EclipseAuthClient @Inject constructor(http: OkHttpClient) {
    private val http = http.newBuilder()
        .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loginWithPassword(email: String, password: String): Result<EclipseLogin> =
        try {
            val body = buildJsonObject {
                put("email", email)
                put("password", password)
            }.toString().toRequestBody(JSON)
            val request = Request.Builder()
                .url(LOGIN_URL)
                .header("User-Agent", EclipseAddonClient.USER_AGENT)
                .header("X-Client-Platform", EclipseAddonClient.CLIENT_PLATFORM)
                .post(body)
                .build()
            val (code, text) = call(request)
            when {
                code == 401 || code == 403 -> failure(EclipseAuthError.InvalidCredentials)
                code !in 200..299 -> failure(EclipseAuthError.Server)
                else -> parseLogin(text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EclipseAuthException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(EclipseAuthException(EclipseAuthError.Server))
        }

    suspend fun verify(token: String): Result<EclipseVerify> =
        try {
            val request = Request.Builder()
                .url(VERIFY_URL)
                .header("User-Agent", EclipseAddonClient.USER_AGENT)
                .header("X-Client-Platform", EclipseAddonClient.CLIENT_PLATFORM)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            val (code, text) = call(request)
            when {
                code == 401 || code == 403 -> failure(EclipseAuthError.InvalidCredentials)
                code !in 200..299 -> failure(EclipseAuthError.Server)
                else -> parseVerify(text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EclipseAuthException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(EclipseAuthException(EclipseAuthError.Server))
        }

    private fun parseLogin(text: String): Result<EclipseLogin> {
        val root = json.parseToJsonElement(text) as? JsonObject
            ?: return failure(EclipseAuthError.Server)
        if (!root.succeeded()) return failure(EclipseAuthError.InvalidCredentials)
        val token = root.string("token")?.takeIf { it.isNotBlank() }
            ?: return failure(EclipseAuthError.Server)
        return Result.success(EclipseLogin(token, root.user()))
    }

    private fun parseVerify(text: String): Result<EclipseVerify> {
        val root = json.parseToJsonElement(text) as? JsonObject
            ?: return failure(EclipseAuthError.Server)
        if (!root.succeeded()) return failure(EclipseAuthError.InvalidCredentials)
        return Result.success(EclipseVerify(user = root.user(), token = root.string("token")))
    }

    /** `success` is absent on some answers, in which case a token/user is the answer. */
    private fun JsonObject.succeeded(): Boolean =
        (this["success"] as? JsonPrimitive)?.contentOrNull?.toBoolean() ?: true

    private fun JsonObject.user(): EclipseUser {
        val user = this["user"] as? JsonObject ?: return EclipseUser()
        return EclipseUser(
            id = user.string("id").orEmpty(),
            email = user.string("email"),
            name = user.string("name") ?: user.string("displayName"),
        )
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun <T> failure(error: EclipseAuthError): Result<T> =
        Result.failure(EclipseAuthException(error))

    /** Returns the status and body; IO failures become a typed [EclipseAuthError.Network]. */
    private suspend fun call(request: Request): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            // callTimeout bounds the blocking call; a coroutine timeout here would
            // surface as a cancellation rather than "network", and cannot interrupt it.
            try {
                http.newCall(request).execute().use { response ->
                    response.code to response.body?.string().orEmpty()
                }
            } catch (e: IOException) {
                throw EclipseAuthException(EclipseAuthError.Network)
            }
        }

    companion object {
        private const val LOGIN_URL = "https://api.eclipsemusic.app/api/auth/mobile/login"
        private const val VERIFY_URL = "https://api.eclipsemusic.app/api/auth/mobile/verify"
        private const val TIMEOUT_MS = 8_000L
        private val JSON = "application/json".toMediaType()
    }
}
