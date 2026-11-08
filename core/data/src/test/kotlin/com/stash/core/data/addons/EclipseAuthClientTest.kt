package com.stash.core.data.addons

import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.store.EncryptedTokenStore
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Eclipse's auth endpoints are absolute production URLs; the interceptor records
 * each real request and rewrites only the transport to the mock server.
 */
class EclipseAuthClientTest {
    private lateinit var server: MockWebServer
    private lateinit var serverUrl: HttpUrl
    private lateinit var auth: EclipseAuthClient
    private val tokenStore: EncryptedTokenStore = mockk(relaxed = true)
    private val accountPreferences: EclipseAccountPreferences = mockk(relaxed = true)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        serverUrl = server.url("/")
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request()
            val target = serverUrl.newBuilder()
                .encodedPath(original.url.encodedPath)
                .encodedQuery(original.url.encodedQuery)
                .build()
            chain.proceed(original.newBuilder().url(target).build())
        }.build()
        auth = EclipseAuthClient(http)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun `password login posts JSON and parses token and user`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"token":"tok-fake",
                   "user":{"id":"u1","email":"listener@addons.test","name":"Listener"}}""",
            ),
        )

        val login = auth.loginWithPassword("listener@addons.test", "hunter2").getOrThrow()

        assertThat(login.token).isEqualTo("tok-fake")
        assertThat(login.user.id).isEqualTo("u1")
        assertThat(login.user.email).isEqualTo("listener@addons.test")
        assertThat(login.user.name).isEqualTo("Listener")

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/api/auth/mobile/login")
        assertThat(request.getHeader("Content-Type")).contains("application/json")
        assertThat(request.body.readUtf8()).contains("listener@addons.test")
    }

    @Test
    fun `401 and success false are both invalid credentials, without the password in the error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val rejected = auth.loginWithPassword("listener@addons.test", "wrong-secret").exceptionOrNull()
        assertThat((rejected as EclipseAuthException).error).isEqualTo(EclipseAuthError.InvalidCredentials)
        assertThat(rejected.toString()).doesNotContain("wrong-secret")

        server.enqueue(MockResponse().setBody("""{"success":false}"""))
        val failed = auth.loginWithPassword("listener@addons.test", "wrong-secret").exceptionOrNull()
        assertThat((failed as EclipseAuthException).error).isEqualTo(EclipseAuthError.InvalidCredentials)
        assertThat(failed.message.orEmpty()).doesNotContain("wrong-secret")
    }

    @Test
    fun `verify sends the Bearer, reads the user, and returns a rotated token when present`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"token":"tok-rotated",
                   "user":{"id":"u1","email":"listener@addons.test"}}""",
            ),
        )

        val verified = auth.verify("tok-fake").getOrThrow()

        assertThat(verified.user.id).isEqualTo("u1")
        assertThat(verified.token).isEqualTo("tok-rotated")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/api/auth/mobile/verify")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer tok-fake")
    }

    @Test
    fun `verify 401 is invalid credentials`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val failure = auth.verify("tok-fake").exceptionOrNull()

        assertThat((failure as EclipseAuthException).error).isEqualTo(EclipseAuthError.InvalidCredentials)
    }

    @Test
    fun `a dropped connection is a network error`() = runTest {
        server.shutdown()

        val failure = auth.loginWithPassword("listener@addons.test", "hunter2").exceptionOrNull()

        assertThat((failure as EclipseAuthException).error).isEqualTo(EclipseAuthError.Network)
    }

    @Test
    fun `manager login stores the token encrypted and the profile without it`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"token":"tok-fake",
                   "user":{"id":"u1","email":"listener@addons.test","name":"Listener"}}""",
            ),
        )
        val manager = EclipseAccountManager(
            tokenStore = tokenStore,
            accountPreferences = accountPreferences,
            authClient = auth,
            client = mockk(relaxed = true),
        )

        val user = manager.loginWithPassword("listener@addons.test", "hunter2").getOrThrow()

        assertThat(user.email).isEqualTo("listener@addons.test")
        coVerify { tokenStore.saveEclipseToken("tok-fake") }
        coVerify { accountPreferences.saveUser(user) }
    }

    @Test
    fun `blank committed token signs out instead of storing anything`() = runTest {
        val manager = EclipseAccountManager(
            tokenStore = tokenStore,
            accountPreferences = accountPreferences,
            authClient = auth,
            client = mockk(relaxed = true),
        )

        assertThat(manager.saveToken("  ")).isFalse()
        coVerify { tokenStore.clearEclipseToken() }
        coVerify { accountPreferences.clear() }
        assertThat(server.requestCount).isEqualTo(0)
    }
}
