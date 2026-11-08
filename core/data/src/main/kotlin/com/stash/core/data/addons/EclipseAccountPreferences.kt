package com.stash.core.data.addons

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Dedicated DataStore for the non-secret Eclipse account profile. */
internal val Context.eclipseAccountDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "eclipse_account",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The Eclipse account profile for display: email, name and id only — never the
 * token (that lives encrypted in [com.stash.core.auth.store.EncryptedTokenStore]).
 */
@Singleton
class EclipseAccountPreferences internal constructor(
    private val store: DataStore<Preferences>,
) {
    /** The app's own store; tests hand in a fresh one instead. */
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.eclipseAccountDataStore)

    private val userKey = stringPreferencesKey("user_json")
    private val json = Json { ignoreUnknownKeys = true }

    val user: Flow<EclipseUser?> = store.data
        .map { prefs -> prefs[userKey]?.let(::decode) }
        .distinctUntilChanged()
        .catch { emit(null) }

    suspend fun userNow(): EclipseUser? = user.first()

    suspend fun saveUser(user: EclipseUser) {
        store.edit { prefs -> prefs[userKey] = json.encodeToString(user) }
    }

    suspend fun clear() {
        store.edit { prefs -> prefs.remove(userKey) }
    }

    private fun decode(text: String): EclipseUser? = try {
        json.decodeFromString<EclipseUser>(text)
    } catch (_: Exception) {
        null
    }
}
