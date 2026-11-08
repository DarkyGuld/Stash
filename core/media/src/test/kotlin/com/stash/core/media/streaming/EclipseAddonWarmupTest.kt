package com.stash.core.media.streaming

import com.stash.core.auth.store.EncryptedTokenStore
import com.stash.core.data.addons.EclipseAddon
import com.stash.core.data.addons.EclipseAddonClient
import com.stash.core.data.addons.EclipseAddonManifest
import com.stash.core.data.addons.EclipseAddonRepository
import com.stash.core.data.prefs.DataSaver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The idle-time addon-host warm: one manifest fetch only when the gate allows it. */
class EclipseAddonWarmupTest {
    private val addon = EclipseAddon(
        id = "addon.test",
        name = "Test Addon",
        version = "1.0.0",
        manifestUrl = "https://addons.test/manifest.json",
        resources = listOf("resolve"),
    )
    private val manifest = EclipseAddonManifest(id = "addon.test", version = "1.0.0")

    private fun warmup(
        addons: List<EclipseAddon>?,
        signedIn: Boolean = true,
        dataSaver: DataSaver = DataSaver({ false }, { false }),
        fetch: suspend () -> EclipseAddonManifest = { manifest },
    ): Pair<EclipseAddonWarmup, EclipseAddonClient> {
        val repository = mockk<EclipseAddonRepository>()
        coEvery { repository.enabledNow() } returns (addons ?: emptyList())
        val client = mockk<EclipseAddonClient>()
        coEvery { client.fetchManifest(any()) } coAnswers { fetch() }
        val tokens = mockk<EncryptedTokenStore>(relaxed = true)
        coEvery { tokens.getEclipseToken() } returns if (signedIn) "tok" else null
        return EclipseAddonWarmup(repository, client, tokens, dataSaver) to client
    }

    @Test
    fun `warms one enabled addon with a single manifest fetch`() = runTest {
        val (warmup, client) = warmup(addons = listOf(addon))

        warmup.warm()

        coVerify(exactly = 1) { client.fetchManifest("https://addons.test/manifest.json") }
    }

    @Test
    fun `no enabled addon does no network work`() = runTest {
        val (warmup, client) = warmup(addons = emptyList())

        warmup.warm()

        coVerify(exactly = 0) { client.fetchManifest(any()) }
    }

    @Test
    fun `data saver skips before looking at addons`() = runTest {
        val (warmup, client) = warmup(
            addons = listOf(addon),
            dataSaver = DataSaver({ true }, { true }),
        )

        warmup.warm()

        coVerify(exactly = 0) { client.fetchManifest(any()) }
    }

    @Test
    fun `signed-out cloud addon is skipped but a plain addon is not`() = runTest {
        val cloud = addon.copy(id = "eclipse-music-addon.test")
        val (cloudWarmup, cloudClient) = warmup(addons = listOf(cloud), signedIn = false)
        cloudWarmup.warm()
        coVerify(exactly = 0) { cloudClient.fetchManifest(any()) }

        val (plainWarmup, plainClient) = warmup(addons = listOf(addon), signedIn = false)
        plainWarmup.warm()
        coVerify(exactly = 1) { plainClient.fetchManifest(any()) }
    }

    @Test
    fun `a fetch that outlives the timeout is abandoned without throwing`() = runTest {
        val (warmup, client) = warmup(addons = listOf(addon)) {
            delay(60_000)
            manifest
        }

        warmup.warm()

        coVerify(exactly = 1) { client.fetchManifest(any()) }
    }

    @Test
    fun `a failing fetch is swallowed`() = runTest {
        val (warmup, _) = warmup(addons = listOf(addon)) {
            throw java.io.IOException("offline")
        }

        warmup.warm()
    }
}
