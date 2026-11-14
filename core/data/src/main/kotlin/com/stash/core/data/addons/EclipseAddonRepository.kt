package com.stash.core.data.addons

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Why [EclipseAddonRepository.add] refused an addon, for the Settings UI. */
sealed class AddAddonError {
    /** Not a parseable `https://` addon URL. */
    data object InvalidUrl : AddAddonError()

    /** The addon could not be reached (offline, timeout, non-2xx). */
    data object Unreachable : AddAddonError()

    /** The URL answered, but not with a music addon's manifest (missing id/name/resources). */
    data object InvalidManifest : AddAddonError()

    /** An addon with the same id or manifest URL is already installed. */
    data object Duplicate : AddAddonError()

    /** The addon is not installed (nothing to reload). */
    data object NotInstalled : AddAddonError()
}

/** [Result] failure carrying a typed [AddAddonError]. */
class AddAddonException(val error: AddAddonError) : Exception(error.toString())

/**
 * Installed Eclipse addons: install/uninstall/reorder, plus the resolve, search
 * and stream passthrough the [com.stash.core.media.streaming.EclipseAddonStreamResolver]
 * walks. Installation fetches and validates the manifest, so a stored addon is
 * known to speak the protocol before it is ever asked to serve a track.
 */
@Singleton
class EclipseAddonRepository @Inject constructor(
    private val client: EclipseAddonClient,
    private val prefs: EclipseAddonPreferences,
) {
    val addons: Flow<List<EclipseAddon>> = prefs.addons

    private val _addonGeneration = MutableStateFlow(0L)

    /**
     * Bumped on every addon mutation (install, enable/disable, remove, reorder,
     * setting change). A next-up prefetch records it before resolving and skips
     * its cache write if it changed: an addon disabled mid-flight must not leave
     * a URL behind for the player to open.
     */
    val addonGeneration: StateFlow<Long> = _addonGeneration.asStateFlow()

    private fun addonsChanged() {
        _addonGeneration.value += 1
    }

    /**
     * Synchronous "is there at least one enabled addon?", cached from [addons].
     * The media-source factory reads it on the playback thread before choosing a
     * source (an addon may answer with an HLS manifest), so it must not suspend.
     * False until the DataStore's first read lands; the collection starts eagerly
     * with this repository, at app start.
     */
    val hasEnabledAddon: StateFlow<Boolean> = addons
        .map { list -> list.any { it.enabled } }
        .stateIn(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    /** Synchronous form of [hasEnabledAddon], for the player's media-source decision. */
    fun hasEnabledAddonNow(): Boolean = hasEnabledAddon.value

    /**
     * Installs [raw] (a base addon URL or a full `manifest.json` URL, with any
     * token path/query). Never throws: an expected failure is a [Result] failure
     * whose exception is an [AddAddonException].
     */
    suspend fun add(raw: String): Result<EclipseAddon> {
        val manifestUrl = EclipseAddonClient.manifestUrlOrNull(raw)
            ?: return failure(AddAddonError.InvalidUrl)

        val manifest = try {
            client.fetchManifest(manifestUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EclipseAddonException.InvalidResponse) {
            return failure(AddAddonError.InvalidManifest)
        } catch (e: EclipseAddonException) {
            return failure(AddAddonError.Unreachable)
        } catch (e: Exception) {
            return failure(AddAddonError.Unreachable)
        }

        if (manifest.id.isBlank() || manifest.name.isBlank() || !manifest.supportsStash()) {
            return failure(AddAddonError.InvalidManifest)
        }

        val existing = prefs.addonsNow()
        if (existing.any { it.id == manifest.id || it.manifestUrl == manifestUrl }) {
            return failure(AddAddonError.Duplicate)
        }

        val addon = EclipseAddon(
            id = manifest.id.trim(),
            name = manifest.name.trim(),
            version = manifest.version.trim(),
            manifestUrl = manifestUrl,
            enabled = true,
            addedAtMs = System.currentTimeMillis(),
            resources = manifest.resources.map { it.lowercase() }.distinct(),
            settingDefs = manifest.settingDefinitions,
            settings = initialSettings(manifest),
            settingOptions = manifest.settingOptions,
        )
        prefs.put(addon)
        addonsChanged()
        // Re-read so the caller gets the assigned order.
        return Result.success(prefs.addonsNow().firstOrNull { it.id == addon.id } ?: addon)
    }

    suspend fun remove(id: String) {
        prefs.remove(id)
        addonsChanged()
    }

    /**
     * Fetches the addon's manifest again and updates what it declares.
     *
     * A reload brings in new settings without the user having to remove the
     * addon and add it again - which would also lose its place in the list.
     * So the place, the enabled state and the user's own setting values stay:
     * only what the manifest declares is taken from the manifest. A setting
     * the manifest no longer declares is dropped; a new one gets the
     * manifest's default.
     */
    suspend fun reloadManifest(id: String): Result<EclipseAddon> {
        val addon = prefs.addonsNow().firstOrNull { it.id == id }
            ?: return failure(AddAddonError.NotInstalled)
        val manifest = try {
            client.fetchManifest(addon.manifestUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EclipseAddonException.InvalidResponse) {
            return failure(AddAddonError.InvalidManifest)
        } catch (e: EclipseAddonException) {
            return failure(AddAddonError.Unreachable)
        } catch (e: Exception) {
            return failure(AddAddonError.Unreachable)
        }
        if (manifest.id.isBlank() || manifest.name.isBlank() || !manifest.supportsStash()) {
            return failure(AddAddonError.InvalidManifest)
        }
        val updated = addon.copy(
            name = manifest.name.trim(),
            version = manifest.version.trim(),
            resources = manifest.resources.map { it.lowercase() }.distinct(),
            settingDefs = manifest.settingDefinitions,
            settingOptions = manifest.settingOptions,
            settings = mergedSettings(addon, manifest),
        )
        // The same id: the addon keeps its place in the list.
        prefs.put(updated)
        addonsChanged()
        return Result.success(prefs.addonsNow().firstOrNull { it.id == id } ?: updated)
    }

    /**
     * The settings after a manifest reload: the user's own values where the
     * manifest still allows them, the manifest's defaults for the rest.
     */
    private fun mergedSettings(addon: EclipseAddon, manifest: EclipseAddonManifest): Map<String, String> {
        val kept = addon.settings.filter { (key, value) ->
            val def = manifest.settingDefinitions.firstOrNull { it.key == key } ?: return@filter false
            def.allows(value)
        }
        return initialSettings(manifest) + kept
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        prefs.setEnabled(id, enabled)
        addonsChanged()
    }

    suspend fun move(id: String, delta: Int) {
        prefs.move(id, delta)
        addonsChanged()
    }

    /**
     * Moves the addon [id] to [toIndex] in the list: the drag-and-drop's move.
     */
    suspend fun moveTo(id: String, toIndex: Int) {
        prefs.moveTo(id, toIndex)
        addonsChanged()
    }

    /**
     * Updates one setting of one installed addon. Ignores an unknown key and a
     * value its declaration does not allow (a select's options, a toggle's
     * true/false); leaves order and enabled state alone.
     */
    suspend fun setSetting(id: String, key: String, value: String) {
        val addon = prefs.addonsNow().firstOrNull { it.id == id } ?: return
        val def = addon.settingDefs.firstOrNull { it.key == key } ?: return
        if (!def.allows(value)) return
        prefs.setSettings(id, addon.settings + (key to value))
        addonsChanged()
    }

    /** Enabled addons only, in priority order. */
    suspend fun enabledNow(): List<EclipseAddon> = prefs.addonsNow().filter { it.enabled }

    suspend fun search(addon: EclipseAddon, query: String, limit: Int): List<EclipseAddonTrack> =
        client.search(addon.manifestUrl, query, limit, addon.settings, addonId = addon.id)

    suspend fun resolve(
        addon: EclipseAddon,
        title: String,
        artist: String?,
        durationMs: Long?,
        isrc: String?,
    ): EclipseAddonTrack? = resolve(addon, title, artist, durationMs, isrc, null)

    /**
     * Same as the five-argument resolve, plus the exact Spotify URI when Stash
     * has one. Our addon turns that into an ISRC before it reaches TIDAL, so the
     * result is not a title/artist guess.
     */
    suspend fun resolve(
        addon: EclipseAddon,
        title: String,
        artist: String?,
        durationMs: Long?,
        isrc: String?,
        spotifyUri: String?,
    ): EclipseAddonTrack? = resolve(addon, title, artist, durationMs, isrc, spotifyUri, null)

    /**
     * Same as the six-argument resolve, plus the exact YouTube video id when
     * Stash has one. A YouTube video has no ISRC in YouTube's API, so the id is
     * the only exact key a pure YouTube row can offer; the addon maps it from
     * its identity index instead of guessing by title.
     */
    suspend fun resolve(
        addon: EclipseAddon,
        title: String,
        artist: String?,
        durationMs: Long?,
        isrc: String?,
        spotifyUri: String?,
        youtubeId: String?,
    ): EclipseAddonTrack? =
        client.resolve(
            manifestUrl = addon.manifestUrl,
            title = title,
            artist = artist,
            durationMs = durationMs,
            isrc = isrc,
            settings = addon.settings,
            addonId = addon.id,
            quality = addon.qualityForResolve(),
            spotifyUri = spotifyUri,
            youtubeId = youtubeId,
        )

    suspend fun resolveIsrc(addon: EclipseAddon, isrc: String): EclipseAddonTrack? =
        client.resolveIsrc(
            manifestUrl = addon.manifestUrl,
            isrc = isrc,
            settings = addon.settings,
            addonId = addon.id,
            path = if (addon.isEclipseCloud) listOf("tidal", "resolve-isrc") else listOf("resolve-isrc"),
        )

    suspend fun stream(addon: EclipseAddon, trackId: String): EclipseAddonStream =
        if (addon.isEclipseCloud) {
            client.streamCloud(
                manifestUrl = addon.manifestUrl,
                trackId = trackId,
                quality = addon.cloudQuality,
                settings = addon.settings,
                addonId = addon.id,
            )
        } else {
            client.stream(addon.manifestUrl, trackId, addon.settings, addon.id)
        }

    /** Cloud only: the Apple Music route's catalog search (public iTunes API). */
    suspend fun searchApple(addon: EclipseAddon, title: String, artist: String): List<EclipseAppleTrack> =
        client.searchAppleTracks(title, artist)

    /** Cloud only: the Apple Music route's stream for an Apple track id. */
    suspend fun streamApple(
        addon: EclipseAddon,
        appleTrackId: String,
        isrc: String?,
    ): EclipseAddonStream =
        streamAppleAtTier(addon, appleTrackId, isrc, addon.appleQuality)

    /** One request at an explicit Apple tier (the resolver's 404 fallback walks them). */
    suspend fun streamAppleAtTier(
        addon: EclipseAddon,
        appleTrackId: String,
        isrc: String?,
        quality: String,
    ): EclipseAddonStream =
        client.streamApple(
            manifestUrl = addon.manifestUrl,
            appleTrackId = appleTrackId,
            appleQuality = quality,
            isrc = isrc,
            addonId = addon.id,
        )

    /** Cloud asks `/resolve` for its preferred tier; other addons have no quality parameter. */
    private fun EclipseAddon.qualityForResolve(): String? = if (isEclipseCloud) cloudQuality else null

    /**
     * The manifest's own defaults, except Cloud's Apple tier: HI_RES_MAX is the
     * fast route (Cloud answers it with a direct Qobuz FLAC instead of Tidal HLS),
     * so a new Cloud addon starts there whatever the manifest's LOSSLESS default
     * says. Tidal `quality` keeps the manifest default. A user's later choice is
     * kept by [setSetting] and by [add] refusing re-installs.
     */
    private fun initialSettings(manifest: EclipseAddonManifest): Map<String, String> {
        val defaults = manifest.settingDefaults.toMutableMap()
        if (manifest.id.startsWith(EclipseAddon.ECLIPSE_CLOUD_ID_PREFIX)) {
            defaults["appleQuality"] = "HI_RES_MAX"
        }
        return defaults
    }

    private fun EclipseAddonSetting.allows(value: String): Boolean =
        when (type) {
            "select", "choice", "enum" -> options.any { it.value == value }
            "toggle", "boolean", "switch" -> value == "true" || value == "false"
            else -> value.isNotBlank()
        }

    private fun failure(error: AddAddonError): Result<EclipseAddon> =
        Result.failure(AddAddonException(error))

    /**
     * What the resolver can walk: it must stream, and it must offer at least one
     * way in — metadata search, `/resolve`, or `/resolve-isrc`. Resolve-only
     * addons are legitimate (and are what Stash's own Eclipse addon is).
     */
    private fun EclipseAddonManifest.supportsStash(): Boolean {
        val resources = resources.map { it.lowercase() }
        return "stream" in resources &&
            ("search" in resources || "resolve" in resources || "isrc" in resources)
    }
}
