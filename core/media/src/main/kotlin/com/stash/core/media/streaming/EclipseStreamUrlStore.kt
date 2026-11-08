package com.stash.core.media.streaming

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
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Dedicated DataStore for resolved addon stream URLs (signed URLs, app-private only). */
internal val Context.eclipseStreamUrlDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "eclipse_stream_urls",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Serializable
private data class StoredStreamUrl(
    val url: String,
    val expiresAtMs: Long,
    val codec: String? = null,
    val bitsPerSample: Int? = null,
    val sampleRateHz: Int? = null,
    val bitrateKbps: Int? = null,
    val origin: String? = null,
    val isHls: Boolean = false,
    val isDash: Boolean = false,
    val seenAtMs: Long,
)

@Serializable
private data class StoredStreamUrls(val entries: Map<String, StoredStreamUrl> = emptyMap())

/**
 * Resolved addon stream URLs, on disk beneath [StreamUrlCache]. The Cloud
 * `/applemusic/stream` call costs 0.3–1.1 s on a cold track; its signed URL
 * stays valid for ~38 min (`etsp`), so remembering it turns a repeat play (or a
 * restart) into a cache hit.
 *
 * Keyed by addon + settings + track identity: a quality change is a different
 * key, as it should be. Entries live only until their own expiry, and the map
 * is LRU-bounded. Signed URLs are secrets of a sort: never logged.
 */
@Singleton
class EclipseStreamUrlStore internal constructor(
    private val store: DataStore<Preferences>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.eclipseStreamUrlDataStore)

    private val key = stringPreferencesKey("stream_urls_json")
    private val json = Json { ignoreUnknownKeys = true }

    /** The live URL for this addon/settings/track, or null when absent/expired. */
    suspend fun get(addonId: String, settingsKey: String, identity: String): StreamUrl? {
        val entry = read().entries[entryKey(addonId, settingsKey, identity)] ?: return null
        if (entry.expiresAtMs <= clock()) return null
        return StreamUrl(
            url = entry.url,
            expiresAtMs = entry.expiresAtMs,
            codec = entry.codec,
            bitsPerSample = entry.bitsPerSample,
            sampleRateHz = entry.sampleRateHz,
            bitrateKbps = entry.bitrateKbps,
            origin = entry.origin,
            isDash = entry.isDash,
            isHls = entry.isHls,
        )
    }

    suspend fun put(addonId: String, settingsKey: String, identity: String, url: StreamUrl) {
        val now = clock()
        store.edit { prefs ->
            val entries = prefs[key].decode().entries.toMutableMap()
            entries[entryKey(addonId, settingsKey, identity)] = StoredStreamUrl(
                url = url.url,
                expiresAtMs = url.expiresAtMs,
                codec = url.codec,
                bitsPerSample = url.bitsPerSample,
                sampleRateHz = url.sampleRateHz,
                bitrateKbps = url.bitrateKbps,
                origin = url.origin,
                isHls = url.isHls,
                isDash = url.isDash,
                seenAtMs = now,
            )
            entries.entries.removeAll { it.value.expiresAtMs <= now }
            if (entries.size > maxEntries) {
                entries.entries.sortedBy { it.value.seenAtMs }
                    .take(entries.size - maxEntries)
                    .forEach { entries.remove(it.key) }
            }
            prefs[key] = json.encodeToString(StoredStreamUrls(entries))
        }
    }

    suspend fun remove(addonId: String, settingsKey: String, identity: String) {
        store.edit { prefs ->
            val entries = prefs[key].decode().entries.toMutableMap()
            if (entries.remove(entryKey(addonId, settingsKey, identity)) != null) {
                prefs[key] = json.encodeToString(StoredStreamUrls(entries))
            }
        }
    }

    suspend fun clear() {
        store.edit { prefs -> prefs.remove(key) }
    }

    private suspend fun read(): StoredStreamUrls = store.data.first()[key].decode()

    private fun String?.decode(): StoredStreamUrls {
        if (isNullOrBlank()) return StoredStreamUrls()
        return try {
            json.decodeFromString<StoredStreamUrls>(this)
        } catch (_: Exception) {
            StoredStreamUrls()
        }
    }

    private fun entryKey(addonId: String, settingsKey: String, identity: String): String =
        "$addonId|$settingsKey|$identity"

    private companion object {
        const val MAX_ENTRIES = 2_000
    }
}
