package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.auth.store.EncryptedTokenStore
import com.stash.core.data.addons.EclipseAddonClient
import com.stash.core.data.addons.EclipseAddonRepository
import com.stash.core.data.prefs.DataSaver
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-shot, idle-time TLS/DNS warm for Eclipse addons: fetch the first enabled
 * addon's manifest through the existing browser-UA client, short timeout, no
 * audio/foreground service/notification/polling. Skipped with no addon, signed
 * out (account-bound addon), or Data saver. Qobuz/Tidal CDNs are not touched;
 * only the addon host, which every later resolve needs.
 */
@Singleton
class EclipseAddonWarmup @Inject constructor(
    private val repository: EclipseAddonRepository,
    private val client: EclipseAddonClient,
    private val tokenStore: EncryptedTokenStore,
    private val dataSaver: DataSaver,
) {
    suspend fun warm() {
        if (dataSaver.appliesNow()) {
            Log.d(TAG, "warmup skipped: data saver")
            return
        }
        val addon = try {
            repository.enabledNow().firstOrNull { it.isEclipseCloud || it.supportsResolve }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (addon == null) {
            Log.d(TAG, "warmup skipped: no enabled addon")
            return
        }
        if (addon.isEclipseCloud && tokenStore.getEclipseToken() == null) {
            Log.d(TAG, "warmup skipped: not signed in")
            return
        }
        val startedNs = System.nanoTime()
        val ok = try {
            withTimeoutOrNull(TIMEOUT_MS) { client.fetchManifest(addon.manifestUrl) } != null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        Log.d(
            TAG,
            "addon host warm ${if (ok) "ok" else "failed"} in " +
                "${(System.nanoTime() - startedNs) / 1_000_000} ms",
        )
    }

    private companion object {
        const val TAG = "EclipseAddonWarmup"
        const val TIMEOUT_MS = 3_000L
    }
}
