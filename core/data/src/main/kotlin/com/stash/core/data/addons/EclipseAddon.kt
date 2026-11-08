package com.stash.core.data.addons

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One selectable value of a manifest setting. */
@Serializable
data class EclipseAddonOption(
    val value: String,
    val label: String = value,
)

/** One setting an addon's manifest declares (Settings renders these). */
@Serializable
data class EclipseAddonSetting(
    val key: String,
    /** `select`, `toggle`, `text`, `number`; unknown types render as text. */
    val type: String = "text",
    val label: String = "",
    val help: String? = null,
    val default: String? = null,
    val perNetwork: Boolean = false,
    val options: List<EclipseAddonOption> = emptyList(),
    val maxLength: Int? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
)

/**
 * One installed Eclipse Music addon (https://eclipsemusic.app/docs).
 *
 * [manifestUrl] is the addon's full `manifest.json` address, including any token
 * path or query the user pasted: every later search/stream request is derived
 * from it and must keep that part, or the addon refuses the call.
 *
 * [resources] is what the manifest advertised at install time (`search`,
 * `stream`, `resolve`, `isrc`, …), [settings]/[settingOptions] are its setting
 * declarations, and [settings]' defaults are what Eclipse expects on each request.
 */
@Serializable
data class EclipseAddon(
    val id: String,
    val name: String,
    val version: String,
    val manifestUrl: String,
    val enabled: Boolean = true,
    /** Position in the Search/stream fallback walk; lower goes first. */
    val order: Int = 0,
    val addedAtMs: Long = 0,
    /** Manifest `resources`, lowercased. */
    val resources: List<String> = emptyList(),
    /** Manifest setting declarations, in manifest order. */
    val settingDefs: List<EclipseAddonSetting> = emptyList(),
    /** Manifest setting key → default value, sent as query parameters on every request. */
    val settings: Map<String, String> = emptyMap(),
    /** Manifest setting key → its allowed values, when the manifest declared them. */
    val settingOptions: Map<String, List<String>> = emptyMap(),
) {
    /** The host to show in the UI, never the token-bearing full URL. */
    val displayHost: String
        get() = manifestUrl.toHttpUrlOrNull()?.host ?: manifestUrl

    /** The addon's base address: its manifest URL without the trailing `manifest.json`. */
    val baseUrl: String
        get() = manifestUrl.substringBeforeLast("/manifest.json", manifestUrl)

    val supportsSearch: Boolean get() = "search" in resources
    val supportsResolve: Boolean get() = "resolve" in resources
    val supportsIsrc: Boolean get() = "isrc" in resources

    /** Cloud's Apple Music tier; HI_RES_MAX unlocks its direct Qobuz FLAC (the fastest route). */
    val appleQuality: String
        get() = settings["appleQuality"]?.trim()?.takeIf { it.isNotEmpty() } ?: "HI_RES_MAX"

    /** Eclipse's own account-bound Cloud addon (`eclipse-music-*`). */
    val isEclipseCloud: Boolean get() = id.startsWith(ECLIPSE_CLOUD_ID_PREFIX)

    /**
     * The quality Cloud streams at: the user's chosen value when it is a valid
     * option, otherwise hi-res where the manifest offers it, CD quality where not.
     */
    val cloudQuality: String
        get() {
            val options = settingOptions["quality"].orEmpty()
            val chosen = settings["quality"]
            return when {
                chosen != null && (options.isEmpty() || chosen in options) -> chosen
                "HI_RES_LOSSLESS" in options -> "HI_RES_LOSSLESS"
                options.isEmpty() -> "HI_RES_LOSSLESS"
                else -> "LOSSLESS"
            }
        }

    companion object {
        const val ECLIPSE_CLOUD_ID_PREFIX = "eclipse-music-"
    }
}
