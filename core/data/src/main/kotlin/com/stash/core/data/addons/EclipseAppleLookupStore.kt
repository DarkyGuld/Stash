package com.stash.core.data.addons

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Dedicated DataStore for the Apple-id lookup cache (no secrets, ids only). */
internal val Context.eclipseAppleLookupDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "eclipse_apple_lookup",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** A cached Apple track id, or a negative (appleId null) with its expiry. */
data class AppleLookup(val appleId: String?, val expiresAtMs: Long)

@Serializable
private data class StoredLookup(
    val appleId: String? = null,
    val expiresAtMs: Long,
    val seenAtMs: Long,
)

@Serializable
private data class StoredLookups(val entries: Map<String, StoredLookup> = emptyMap())

/**
 * Apple ids survive restarts: a Cloud track's iTunes search costs ~200 ms cold,
 * and the answer (an Apple track id, or "not in the Apple catalog") does not
 * change per quality or session. Keyed by addon + track identity, never by the
 * settings snapshot, so a quality change keeps the id.
 *
 * Positive entries are kept effectively forever (an Apple id for a track is
 * stable; a stale one is dropped when a stream 403/404s), negative entries stay
 * short (24 hours), and the map is LRU-bounded at [MAX_ENTRIES]. A compact JSON
 * map in its own DataStore; the main library DB is untouched.
 */
@Singleton
class EclipseAppleLookupStore internal constructor(
    private val store: DataStore<Preferences>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    /** The app's own store; tests hand in a fresh one. */
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.eclipseAppleLookupDataStore)

    private val key = stringPreferencesKey("apple_lookups_json")
    private val negativesDroppedKey = booleanPreferencesKey("apple_negatives_dropped_v1")
    private val json = Json { ignoreUnknownKeys = true }

    /** The live entry for [addonId] + [identity], or null when absent/expired. */
    suspend fun get(addonId: String, identity: String): AppleLookup? {
        val entry = read().entries[entryKey(addonId, identity)] ?: return null
        if (entry.expiresAtMs <= clock()) return null
        return AppleLookup(entry.appleId, entry.expiresAtMs)
    }

    /** Writes a positive id (no expiry) or a negative marker (~24 hours). */
    suspend fun put(addonId: String, identity: String, appleId: String?) {
        val now = clock()
        val expiresAt = if (appleId == null) now + NEGATIVE_TTL_MS else Long.MAX_VALUE
        store.edit { prefs ->
            val current = prefs[key].decode()
            val entries = current.entries.toMutableMap()
            entries[entryKey(addonId, identity)] = StoredLookup(appleId, expiresAt, now)
            // Expired entries go first, then the least recently written, to the cap.
            entries.entries.removeAll { it.value.expiresAtMs <= now }
            if (entries.size > maxEntries) {
                entries.entries
                    .sortedBy { it.value.seenAtMs }
                    .take(entries.size - maxEntries)
                    .forEach { entries.remove(it.key) }
            }
            prefs[key] = json.encodeToString(StoredLookups(entries))
        }
    }

    /** Drops one entry (a dead Apple id, a removed addon). */
    suspend fun remove(addonId: String, identity: String) {
        store.edit { prefs ->
            val entries = prefs[key].decode().entries.toMutableMap()
            if (entries.remove(entryKey(addonId, identity)) != null) {
                prefs[key] = json.encodeToString(StoredLookups(entries))
            }
        }
    }

    /**
     * One-time cleanup: a stored long-lived negative used to suppress the iTunes
     * search for a track the catalog had since gained. Positives are kept; every
     * negative is dropped once, so existing installs are unstuck.
     */
    suspend fun migrateDropNegativesOnce() {
        store.edit { prefs ->
            if (prefs[negativesDroppedKey] == true) return@edit
            val entries = prefs[key].decode().entries.filterValues { it.appleId != null }
            prefs[key] = json.encodeToString(StoredLookups(entries))
            prefs[negativesDroppedKey] = true
        }
    }

    private suspend fun read(): StoredLookups = store.data.first()[key].decode()

    private fun String?.decode(): StoredLookups {
        if (isNullOrBlank()) return StoredLookups()
        return try {
            json.decodeFromString<StoredLookups>(this)
        } catch (_: Exception) {
            StoredLookups()
        }
    }

    private fun entryKey(addonId: String, identity: String): String = "$addonId|$identity"

    private companion object {
        /** Positives do not expire; Long.MAX_VALUE keeps the stored shape finite. */
        /** Only if a caller ever stores one: an hour at most, never a day. */
        const val NEGATIVE_TTL_MS = 60L * 60_000L
        const val MAX_ENTRIES = 5_000
    }
}
