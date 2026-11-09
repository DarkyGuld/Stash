package com.stash.core.data.addons

import com.stash.core.auth.store.EncryptedTokenStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The user's Eclipse Music account, only as far as addons need it: an encrypted
 * account token and the non-secret profile for display.
 *
 * The token never leaves the app except as a Bearer to
 * `api.eclipsemusic.app/addon/…` or Eclipse's own auth endpoints (see
 * [EclipseAddonClient] and [EclipseAuthClient]); it is never logged, put in an
 * error, or stored anywhere but the encrypted [EncryptedTokenStore].
 */
@Singleton
class EclipseAccountManager @Inject constructor(
    private val tokenStore: EncryptedTokenStore,
    private val accountPreferences: EclipseAccountPreferences,
    private val authClient: EclipseAuthClient,
    private val client: EclipseAddonClient,
) {
    /** True when an account token is stored. */
    val tokenPresent: Flow<Boolean> = tokenStore.eclipseToken
        .map { !it.isNullOrBlank() }
        .distinctUntilChanged()

    /** The signed-in profile (email/name/id) for display; null when signed out. */
    val user: Flow<EclipseUser?> = accountPreferences.user

    private val _accountGeneration = MutableStateFlow(0L)

    /**
     * Bumped when the signed-in account changes (sign in, token change, sign out).
     * Session-only caches keyed by account observe this and start clean.
     */
    val accountGeneration: StateFlow<Long> = _accountGeneration.asStateFlow()

    private fun accountChanged() {
        _accountGeneration.value += 1
    }

    /**
     * Signs in with email/password and stores the returned token plus profile.
     * Never throws; a failure is a typed [EclipseAuthException].
     */
    suspend fun loginWithPassword(email: String, password: String): Result<EclipseUser> =
        authClient.loginWithPassword(email.trim(), password).mapCatching { login ->
            tokenStore.saveEclipseToken(login.token)
            accountPreferences.saveUser(login.user)
            accountChanged()
            login.user
        }

    /**
     * Stores [raw] as the Eclipse account token. A blank value (or one that is
     * only a `Bearer ` prefix) clears it and the profile. Returns true when a
     * token is now stored. Best-effort verification fills in the profile.
     */
    suspend fun saveToken(raw: String): Boolean {
        val token = raw.trim().removePrefix("Bearer ").trim()
        if (token.isEmpty()) {
            signOut()
            return false
        }
        tokenStore.saveEclipseToken(token)
        accountChanged()
        refreshUser(token)
        return true
    }

    /**
     * Verifies the stored token with Eclipse and stores the profile (and any
     * rotated token). Null when there is no token or verification failed.
     */
    suspend fun refreshUser(): EclipseUser? {
        val token = tokenStore.getEclipseToken() ?: return null
        return refreshUser(token)
    }

    private suspend fun refreshUser(token: String): EclipseUser? {
        val verified = authClient.verify(token).getOrNull() ?: return null
        verified.token?.takeIf { it.isNotBlank() }?.let { tokenStore.saveEclipseToken(it) }
        accountPreferences.saveUser(verified.user)
        return verified.user
    }

    /** Forgets the token and the profile. */
    suspend fun signOut() {
        tokenStore.clearEclipseToken()
        accountPreferences.clear()
        accountChanged()
    }

    /**
     * Mints an access token for [addon] right now, bypassing the cache: the
     * Settings screen's "Test". False without a stored token or when the mint fails.
     */
    suspend fun verify(addon: EclipseAddon): Boolean = client.testAccess(addon.id)
}
