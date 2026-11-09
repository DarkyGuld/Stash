package com.stash.core.network.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt module that provides networking dependencies shared across all modules.
 *
 * The [OkHttpClient] singleton is configured with sensible timeouts, a logging
 * interceptor (body-level in debug builds, none in release), and enforced
 * TLS 1.2+ via [ConnectionSpec.MODERN_TLS] to prevent cipher-downgrade attacks.
 *
 * **Certificate pinning note:** Full certificate pinning with SHA-256 pin hashes
 * for Spotify / YouTube endpoints is a future enhancement. Pinned hashes must be
 * rotated whenever the service rotates its leaf or intermediate certificates,
 * which risks hard-locking users out of the app if an update is missed. For now,
 * MODERN_TLS ensures only strong cipher suites and TLS 1.2+ are negotiated.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val TIMEOUT_SECONDS = 30L

    /**
     * Headers never printed, should the log level ever show headers: credentials, edit and
     * Community keys, the relay request signature and the Ko-fi supporter token.
     */
    internal val REDACTED_HEADERS = listOf(
        "Authorization",
        "Cookie",
        "X-Stash-Edit-Key",
        "X-Stash-Community-Key",
        "X-Stash-Auth",
        "X-Stash-Entitlement",
    )

    @Provides
    @Singleton
    fun provideOkHttpClient(
        appInterceptors: Set<@JvmSuppressWildcards Interceptor>,
    ): OkHttpClient {
        // BASIC prints each request URL; credential query parameters (Qobuz's
        // login sends email + MD5(password) as a GET) are masked first.
        val loggingInterceptor = HttpLoggingInterceptor(
            object : HttpLoggingInterceptor.Logger {
                override fun log(message: String) {
                    HttpLoggingInterceptor.Logger.DEFAULT.log(LogRedaction.redactSecrets(message))
                }
            },
        ).apply {
            level = if (com.stash.core.network.BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            REDACTED_HEADERS.forEach(::redactHeader)
        }

        return OkHttpClient.Builder()
            // Long-lived pool: the Eclipse addon host is one socket per app run.
            // The default 5-minute idle eviction forces a fresh DNS+TCP+TLS
            // handshake (measured ~550 ms on 5G) after any pause; warm requests
            // are RTT-bound at ~200 ms. HTTP/2 pings keep the connection alive.
            .connectionPool(okhttp3.ConnectionPool(5, 10, TimeUnit.MINUTES))
            .pingInterval(30, TimeUnit.SECONDS)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            // Survive the device resolver failing under the sync's 8-way download
            // burst (UnknownHostException for several simultaneous lookups of the
            // same host, while single requests resolve fine — the reason downloads
            // "fell flat" while streaming worked). Caches + coalesces + retries.
            .dns(com.stash.core.network.dns.ResilientDns())
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .apply { appInterceptors.forEach { addInterceptor(it) } }
            .build()
    }
}

/**
 * Declares the multibinding consumed by [NetworkModule.provideOkHttpClient].
 *
 * Lives in a separate interface module because [NetworkModule] is an `object`
 * and cannot host the abstract `@Multibinds` method.
 */
@Module
@InstallIn(SingletonComponent::class)
interface NetworkInterceptorsModule {
    /**
     * Walks a 404'd cover down to a variant that exists. Bound here
     * rather than at a call site because the client is shared: Coil and
     * media3's bitmap loader both need it, and neither knows about the other.
     */
    @Binds
    @IntoSet
    fun artFallback(
        interceptor: com.stash.core.network.art.ArtFallbackInterceptor,
    ): Interceptor

    /**
     * App-contributed OkHttp interceptors installed on the shared client.
     * Resolves to an empty set when nothing contributes.
     */
    @Multibinds
    fun appInterceptors(): Set<@JvmSuppressWildcards Interceptor>
}
