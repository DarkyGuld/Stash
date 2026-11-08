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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Dedicated DataStore for the installed Eclipse addons. Internal so tests can clear it. */
internal val Context.eclipseAddonsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "eclipse_addons",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The installed Eclipse addon list, persisted as one JSON array in its own
 * DataStore. The manifest, search and stream data are never stored here — only
 * the addon identity needed to ask it again later.
 */
@Singleton
class EclipseAddonPreferences internal constructor(
    private val store: DataStore<Preferences>,
) {
    /** The app's own store; tests hand in a fresh one instead. */
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.eclipseAddonsDataStore)

    private val addonsKey = stringPreferencesKey("addons_json")
    private val cloudAppleQualityMigratedKey = booleanPreferencesKey("cloud_apple_quality_migrated_v1")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** The installed addons, deduplicated by [EclipseAddon.id] and ordered by [EclipseAddon.order]. */
    val addons: Flow<List<EclipseAddon>> = store.data
        .map { prefs -> prefs[addonsKey].decodeAddons() }
        .distinctUntilChanged()
        .catch { emit(emptyList()) }

    /** One-shot read of [addons]. */
    suspend fun addonsNow(): List<EclipseAddon> = addons.first()

    /**
     * Inserts [addon], or replaces the entry with the same [EclipseAddon.id]. A
     * new entry goes last ([EclipseAddon.order] = max + 1) and gets [EclipseAddon.addedAtMs]
     * when the caller left it at 0.
     */
    suspend fun put(addon: EclipseAddon) {
        store.edit { prefs ->
            val current = prefs[addonsKey].decodeAddons().toMutableList()
            val index = current.indexOfFirst { it.id == addon.id }
            if (index >= 0) {
                // Keep the position already chosen for it; a replace never reorders.
                current[index] = addon.copy(order = current[index].order)
            } else {
                val order = (current.maxOfOrNull { it.order } ?: -1) + 1
                current += addon.copy(
                    order = order,
                    addedAtMs = addon.addedAtMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
                )
            }
            prefs[addonsKey] = json.encodeToString(current.sortedBy { it.order })
        }
    }

    suspend fun remove(id: String) {
        store.edit { prefs ->
            val current = prefs[addonsKey].decodeAddons().filterNot { it.id == id }
            prefs[addonsKey] = json.encodeToString(current.renumbered())
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        store.edit { prefs ->
            val current = prefs[addonsKey].decodeAddons().map {
                if (it.id == id) it.copy(enabled = enabled) else it
            }
            prefs[addonsKey] = json.encodeToString(current)
        }
    }

    /** Replaces one addon's current setting values, keeping everything else about it. */
    suspend fun setSettings(id: String, settings: Map<String, String>) {
        store.edit { prefs ->
            val current = prefs[addonsKey].decodeAddons().map {
                if (it.id == id) it.copy(settings = settings) else it
            }
            prefs[addonsKey] = json.encodeToString(current)
        }
    }

    /**
     * One-time migration to Cloud's fast Apple tier. Existing installs stored
     * `appleQuality` as `HI_RES` (the old default) or `LOSSLESS` (the manifest
     * default); both become `HI_RES_MAX` once, which is what makes Cloud answer
     * with a direct Qobuz FLAC instead of Tidal HLS. A later user choice is never
     * touched: the flag stops this from running again.
     */
    suspend fun migrateCloudAppleQualityOnce() {
        store.edit { prefs ->
            if (prefs[cloudAppleQualityMigratedKey] == true) return@edit
            val current = prefs[addonsKey].decodeAddons()
            val migrated = current.map { addon ->
                val appleQuality = addon.settings["appleQuality"]
                if (addon.isEclipseCloud &&
                    (appleQuality == null || appleQuality == "HI_RES" || appleQuality == "LOSSLESS")
                ) {
                    addon.copy(settings = addon.settings + ("appleQuality" to "HI_RES_MAX"))
                } else {
                    addon
                }
            }
            prefs[addonsKey] = json.encodeToString(migrated)
            prefs[cloudAppleQualityMigratedKey] = true
        }
    }

    /**
     * Swaps [id] with its neighbour [delta] places away (usually -1 / +1) and
     * renumbers the list 0..n-1. Nothing moves at either end.
     */
    suspend fun move(id: String, delta: Int) {
        if (delta == 0) return
        store.edit { prefs ->
            val current = prefs[addonsKey].decodeAddons().toMutableList()
            val from = current.indexOfFirst { it.id == id }
            if (from < 0) return@edit
            val to = (from + delta).coerceIn(0, current.lastIndex)
            if (to == from) return@edit
            val moved = current.removeAt(from)
            current.add(to, moved)
            prefs[addonsKey] = json.encodeToString(current.renumbered())
        }
    }

    /** Tolerates a missing, empty, or corrupt array: all read as "no addons". */
    private fun String?.decodeAddons(): List<EclipseAddon> {
        if (this.isNullOrBlank()) return emptyList()
        val decoded = try {
            json.decodeFromString<List<EclipseAddon>>(this)
        } catch (e: Exception) {
            return emptyList()
        }
        return decoded
            .filter { it.id.isNotBlank() && it.manifestUrl.isNotBlank() }
            .distinctBy { it.id }
            .sortedBy { it.order }
    }

    private fun List<EclipseAddon>.renumbered(): List<EclipseAddon> =
        mapIndexed { index, addon -> addon.copy(order = index) }
}
