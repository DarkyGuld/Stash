package com.stash.core.data.addons

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.store.EncryptedTokenStore
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Install/dedupe/validate against a real EclipseAddonClient (the mock server
 * stands in for the addon, see [EclipseAddonClientTest]) and the real DataStore.
 */
@RunWith(RobolectricTestRunner::class)
class EclipseAddonRepositoryTest {
    private lateinit var context: Context
    private lateinit var file: File
    private lateinit var server: MockWebServer
    private lateinit var serverUrl: okhttp3.HttpUrl
    private lateinit var repository: EclipseAddonRepository
    private lateinit var prefs: EclipseAddonPreferences

    private val manifestUrl = "https://addons.test/tok/manifest.json?token=abc"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // A fresh store per test: the app's own file is shared by every test class in the JVM.
        file = File(context.cacheDir, "eclipse_addons_TEST_${System.nanoTime()}.preferences_pb")
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { file },
        )
        prefs = EclipseAddonPreferences(store)
        server = MockWebServer().apply { start() }
        serverUrl = server.url("/")
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request()
            val target = serverUrl.newBuilder()
                .encodedPath(original.url.encodedPath)
                .encodedQuery(original.url.encodedQuery)
                .build()
            chain.proceed(original.newBuilder().url(target).build())
        }.build()
        repository = EclipseAddonRepository(
            EclipseAddonClient(http, mockk<EncryptedTokenStore>(relaxed = true)),
            prefs,
        )
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        if (file.exists()) file.delete()
    }

    private suspend fun stored(): List<EclipseAddon> = repository.addons.first()

    private fun manifest(id: String = "nebula", name: String = "Nebula") =
        MockResponse().setBody("""{"id":"$id","name":"$name","version":"1.0","resources":["search","stream"]}""")

    @Test
    fun `add fetches and persists a valid addon`() = runTest {
        server.enqueue(manifest())

        val result = repository.add("https://addons.test/tok?token=abc")

        assertThat(result.isSuccess).isTrue()
        val addon = result.getOrThrow()
        assertThat(addon.id).isEqualTo("nebula")
        assertThat(addon.name).isEqualTo("Nebula")
        assertThat(addon.manifestUrl).isEqualTo("https://addons.test/tok/manifest.json?token=abc")
        assertThat(addon.enabled).isTrue()
        assertThat(addon.addedAtMs).isGreaterThan(0)
        assertThat(repository.enabledNow().map { it.id }).containsExactly("nebula")
    }

    @Test
    fun `add rejects a duplicate id or manifest URL`() = runTest {
        server.enqueue(manifest())
        server.enqueue(manifest())
        server.enqueue(manifest())
        repository.add(manifestUrl)

        val duplicateUrl = repository.add(manifestUrl)
        assertThat((duplicateUrl.exceptionOrNull() as AddAddonException).error)
            .isEqualTo(AddAddonError.Duplicate)

        val duplicateId = repository.add("https://addons.test/other/manifest.json")
        assertThat((duplicateId.exceptionOrNull() as AddAddonException).error)
            .isEqualTo(AddAddonError.Duplicate)
        assertThat(repository.enabledNow()).hasSize(1)
    }

    @Test
    fun `add rejects a manifest without a way in or without stream`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"n","name":"N","version":"1","resources":["search"]}"""))

        val result = repository.add(manifestUrl)

        assertThat((result.exceptionOrNull() as AddAddonException).error)
            .isEqualTo(AddAddonError.InvalidManifest)

        server.enqueue(MockResponse().setBody("""{"id":"n2","name":"N2","version":"1","resources":["stream"]}"""))
        val streamOnly = repository.add("https://addons.test/stream-only/manifest.json")
        assertThat((streamOnly.exceptionOrNull() as AddAddonException).error)
            .isEqualTo(AddAddonError.InvalidManifest)
        assertThat(repository.enabledNow()).isEmpty()
    }

    @Test
    fun `a resolve-only addon installs and keeps its resources and settings defaults`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"addon","name":"Addon","version":"2.0","contentType":"music",
                   "resources":["stream","settings","resolve"],
                   "settings":[{"key":"quality","default":"LOSSLESS"},{"key":"appleQuality","default":"LOSSLESS"}]}""",
            ),
        )

        val result = repository.add("https://addons.test/tok?token=abc")

        assertThat(result.isSuccess).isTrue()
        val addon = result.getOrThrow()
        assertThat(addon.resources).containsExactly("stream", "settings", "resolve")
        assertThat(addon.settings).containsExactly("quality", "LOSSLESS", "appleQuality", "LOSSLESS")
        assertThat(addon.supportsResolve).isTrue()
        assertThat(addon.supportsSearch).isFalse()
        assertThat(repository.enabledNow().map { it.id }).containsExactly("addon")
    }

    @Test
    fun `a cloud manifest installs with its quality options and the manifest default`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"eclipse-music-cloud","name":"Cloud","version":"1.0","contentType":"music",
                   "resources":["stream","settings","resolve"],
                   "settings":[{"key":"quality","type":"select","default":"LOSSLESS",
                                "options":["HI_RES_LOSSLESS","LOSSLESS","HIGH"]}]}""",
            ),
        )

        val addon = repository.add("https://api.eclipsemusic.app/addon/abc?token=t").getOrThrow()

        assertThat(addon.isEclipseCloud).isTrue()
        assertThat(addon.settingOptions["quality"]).containsExactly("HI_RES_LOSSLESS", "LOSSLESS", "HIGH")
        // LOSSLESS (16/44.1 FLAC), the manifest's default; hi-res is a user choice.
        assertThat(addon.settings["quality"]).isEqualTo("LOSSLESS")
        assertThat(addon.cloudQuality).isEqualTo("LOSSLESS")
    }

    @Test
    fun `a cloud stream uses stream with trackId and quality, not the by-path route`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"url":"https://api.eclipsemusic.app/api/tidal/hls/1.m3u8?exp=1762000000",
                   "quality":"HI_RES_LOSSLESS","codec":"flac","bitDepth":24,"sampleRate":44100,
                   "format":"hls","container":"fmp4","manifest":"hls"}""",
            ),
        )
        val cloud = EclipseAddon(
            id = "eclipse-music-cloud",
            name = "Cloud",
            version = "1",
            manifestUrl = "https://api.eclipsemusic.app/addon/abc/manifest.json",
            resources = listOf("stream", "resolve"),
            settingOptions = mapOf("quality" to listOf("HI_RES_LOSSLESS", "LOSSLESS")),
            settings = mapOf("quality" to "LOSSLESS"),
        )

        val stream = repository.stream(cloud, "t1")

        assertThat(stream.codec).isEqualTo("flac")
        val request = server.takeRequest().requestUrl!!
        assertThat(request.encodedPath).isEqualTo("/addon/abc/stream")
        assertThat(request.queryParameter("trackId")).isEqualTo("t1")
        assertThat(request.queryParameter("quality")).isEqualTo("LOSSLESS")
    }

    private val settingsManifest = """
        {"id":"eclipse-music-cloud","name":"Cloud","version":"1.0",
         "resources":["stream","settings","resolve"],
         "settings":[
           {"key":"quality","type":"select","label":"Quality","default":"LOSSLESS",
            "options":[{"value":"HI_RES_LOSSLESS","label":"Hi-Res"},{"value":"LOSSLESS","label":"CD"}]},
           {"key":"offline","type":"toggle","label":"Offline","default":"false"}
         ]}
    """.trimIndent()

    @Test
    fun `cloud installs at the manifest default and a valid setting change is persisted`() = runTest {
        server.enqueue(MockResponse().setBody(settingsManifest))
        val addon = repository.add("https://api.eclipsemusic.app/addon/abc?token=t").getOrThrow()

        assertThat(addon.settings["quality"]).isEqualTo("LOSSLESS")
        assertThat(addon.cloudQuality).isEqualTo("LOSSLESS")
        assertThat(addon.settingDefs.map { it.key }).containsExactly("quality", "offline").inOrder()

        repository.setSetting(addon.id, "quality", "HI_RES_LOSSLESS")

        val updated = stored().first { it.id == addon.id }
        assertThat(updated.settings["quality"]).isEqualTo("HI_RES_LOSSLESS")
        assertThat(updated.cloudQuality).isEqualTo("HI_RES_LOSSLESS")
        // Untouched settings and the addon itself survive.
        assertThat(updated.settings["offline"]).isEqualTo("false")
        assertThat(updated.order).isEqualTo(addon.order)
        assertThat(updated.enabled).isTrue()
    }

    @Test
    fun `an unknown key or a value outside the schema is ignored`() = runTest {
        server.enqueue(MockResponse().setBody(settingsManifest))
        val addon = repository.add("https://api.eclipsemusic.app/addon/abc?token=t").getOrThrow()

        repository.setSetting(addon.id, "nope", "x")
        repository.setSetting(addon.id, "quality", "BOGUS")
        repository.setSetting(addon.id, "offline", "maybe")

        val updated = stored().first { it.id == addon.id }
        assertThat(updated.settings["quality"]).isEqualTo("LOSSLESS")
        assertThat(updated.settings["offline"]).isEqualTo("false")
        assertThat(updated.settings).doesNotContainKey("nope")

        repository.setSetting(addon.id, "offline", "true")
        assertThat(stored().first { it.id == addon.id }.settings["offline"]).isEqualTo("true")
    }

    @Test
    fun `a normal addon's settings are unchanged by the cloud quality preference`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"nebula","name":"Nebula","resources":["search","stream"],
                   "settings":[{"key":"quality","type":"select","default":"LOSSLESS",
                                "options":["HI_RES_LOSSLESS","LOSSLESS"]}]}""",
            ),
        )
        val addon = repository.add("https://addons.test/tok?token=abc").getOrThrow()

        assertThat(addon.isEclipseCloud).isFalse()
        assertThat(addon.settings["quality"]).isEqualTo("LOSSLESS")
    }

    @Test
    fun `the synchronous addon hint follows enabled addons`() = runTest {
        assertThat(repository.hasEnabledAddonNow()).isFalse()

        server.enqueue(manifest())
        val addon = repository.add(manifestUrl).getOrThrow()
        repository.hasEnabledAddon.first { it }
        assertThat(repository.hasEnabledAddonNow()).isTrue()

        repository.setEnabled(addon.id, false)
        repository.hasEnabledAddon.first { !it }
        assertThat(repository.hasEnabledAddonNow()).isFalse()
    }

    @Test
    fun `a new Cloud addon starts on HI_RES_MAX for Apple, whatever the manifest default`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"eclipse-music-cloud","name":"Cloud","version":"1.0",
                   "resources":["stream","settings","resolve"],
                   "settings":[{"key":"quality","type":"select","default":"LOSSLESS","options":["LOSSLESS"]},
                               {"key":"appleQuality","type":"select","default":"LOSSLESS",
                                "options":["HI_RES_MAX","HI_RES","LOSSLESS"]}]}""",
            ),
        )

        val addon = repository.add("https://api.eclipsemusic.app/addon/abc?token=t").getOrThrow()

        assertThat(addon.settings["appleQuality"]).isEqualTo("HI_RES_MAX")
        assertThat(addon.appleQuality).isEqualTo("HI_RES_MAX")
        // The Tidal tier still follows the manifest.
        assertThat(addon.settings["quality"]).isEqualTo("LOSSLESS")
    }

    @Test
    fun `the Apple-quality migration upgrades old Cloud defaults once and respects a later choice`() = runTest {
        val cloud = EclipseAddon(
            id = "eclipse-music-cloud",
            name = "Cloud",
            version = "1",
            manifestUrl = "https://api.eclipsemusic.app/addon/abc/manifest.json",
            settings = mapOf("appleQuality" to "LOSSLESS"),
        )
        val cloudOldDefault = cloud.copy(id = "eclipse-music-cloud-2", settings = mapOf("appleQuality" to "HI_RES"))
        val normal = EclipseAddon(
            id = "nebula",
            name = "Nebula",
            version = "1",
            manifestUrl = "https://addons.test/nebula/manifest.json",
            settings = mapOf("appleQuality" to "HI_RES"),
        )
        prefs.put(cloud)
        prefs.put(cloudOldDefault)
        prefs.put(normal)

        prefs.migrateCloudAppleQualityOnce()

        val after = prefs.addonsNow().associateBy { it.id }
        assertThat(after.getValue("eclipse-music-cloud").settings["appleQuality"]).isEqualTo("HI_RES_MAX")
        assertThat(after.getValue("eclipse-music-cloud-2").settings["appleQuality"]).isEqualTo("HI_RES_MAX")
        // A third-party addon is not Cloud and keeps its own value.
        assertThat(after.getValue("nebula").settings["appleQuality"]).isEqualTo("HI_RES")

        // A user choice made afterwards survives the (already run) migration.
        prefs.setSettings("eclipse-music-cloud", mapOf("appleQuality" to "LOSSLESS"))
        prefs.migrateCloudAppleQualityOnce()
        assertThat(prefs.addonsNow().first { it.id == "eclipse-music-cloud" }.settings["appleQuality"])
            .isEqualTo("LOSSLESS")
    }

    @Test
    fun `re-adding an installed addon is a duplicate and keeps the user's chosen quality`() = runTest {
        server.enqueue(MockResponse().setBody(settingsManifest))
        val addon = repository.add("https://api.eclipsemusic.app/addon/abc?token=t").getOrThrow()
        assertThat(addon.settings["quality"]).isEqualTo("LOSSLESS")
        repository.setSetting(addon.id, "quality", "HI_RES_LOSSLESS")

        // The manifest is fetched first, then the duplicate check refuses it: no overwrite.
        server.enqueue(MockResponse().setBody(settingsManifest))
        val second = repository.add("https://api.eclipsemusic.app/addon/abc?token=t")

        assertThat((second.exceptionOrNull() as AddAddonException).error).isEqualTo(AddAddonError.Duplicate)
        assertThat(stored().first { it.id == addon.id }.settings["quality"]).isEqualTo("HI_RES_LOSSLESS")
    }

    @Test
    fun `add rejects a blank id or name as an invalid manifest`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"","name":"No id","resources":["search","stream"]}"""))

        val result = repository.add(manifestUrl)

        assertThat((result.exceptionOrNull() as AddAddonException).error)
            .isEqualTo(AddAddonError.InvalidManifest)
    }

    @Test
    fun `add maps an http error to unreachable and a bad URL to invalid`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val unreachable = repository.add(manifestUrl)
        assertThat((unreachable.exceptionOrNull() as AddAddonException).error)
            .isEqualTo(AddAddonError.Unreachable)

        val invalid = repository.add("ftp://addons.test/manifest.json")
        assertThat((invalid.exceptionOrNull() as AddAddonException).error)
            .isEqualTo(AddAddonError.InvalidUrl)
    }

    @Test
    fun `order follows installs, move swaps neighbours, and remove closes the gap`() = runTest {
        server.enqueue(manifest("a", "A"))
        server.enqueue(manifest("b", "B"))
        server.enqueue(manifest("c", "C"))
        repository.add("https://addons.test/a/manifest.json")
        repository.add("https://addons.test/b/manifest.json")
        repository.add("https://addons.test/c/manifest.json")

        // The newest addon leads: the list is the order the chain tries them in,
        // and the one just added is the one the user wants heard first.
        assertThat(stored().map { it.id }).containsExactly("c", "b", "a").inOrder()
        assertThat(stored().map { it.order }).containsExactly(0, 1, 2).inOrder()

        repository.move("a", -1)
        assertThat(stored().map { it.id }).containsExactly("c", "a", "b").inOrder()
        assertThat(stored().map { it.order }).containsExactly(0, 1, 2).inOrder()

        repository.move("c", 1)
        assertThat(stored().map { it.id }).containsExactly("a", "c", "b").inOrder()

        repository.setEnabled("a", false)
        assertThat(repository.enabledNow().map { it.id }).containsExactly("c", "b").inOrder()

        repository.remove("c")
        assertThat(stored().map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(stored().map { it.order }).containsExactly(0, 1).inOrder()
    }

    @Test
    fun `a new addon goes on top of the list`() = runTest {
        server.enqueue(manifest("a", "A"))
        server.enqueue(manifest("b", "B"))
        repository.add("https://addons.test/a/manifest.json")
        repository.add("https://addons.test/b/manifest.json")

        assertThat(stored().map { it.id }).containsExactly("b", "a").inOrder()
        assertThat(stored().map { it.order }).containsExactly(0, 1).inOrder()
    }

    @Test
    fun `a dragged addon goes where it was dropped`() = runTest {
        server.enqueue(manifest("a", "A"))
        server.enqueue(manifest("b", "B"))
        server.enqueue(manifest("c", "C"))
        repository.add("https://addons.test/a/manifest.json")
        repository.add("https://addons.test/b/manifest.json")
        repository.add("https://addons.test/c/manifest.json")
        // The list now reads c, b, a.

        repository.moveTo("a", 0)

        assertThat(stored().map { it.id }).containsExactly("a", "c", "b").inOrder()
        assertThat(stored().map { it.order }).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `a reload brings in the addon's new settings and keeps its place`() = runTest {
        server.enqueue(manifest("a", "A"))
        server.enqueue(manifest("b", "B"))
        repository.add("https://addons.test/a/manifest.json")
        repository.add("https://addons.test/b/manifest.json")
        // The list reads b, a.
        // The manifest now declares a setting it did not declare before.
        server.enqueue(
            MockResponse().setBody(
                """{"id":"a","name":"A","version":"2.0","resources":["search","stream"],
                   "settings":[{"key":"quality","type":"select","default":"lossless",
                                "options":[{"value":"lossless"},{"value":"hires"}]}]}""",
            ),
        )

        val result = repository.reloadManifest("a")

        assertThat(result.isSuccess).isTrue()
        val reloaded = result.getOrThrow()
        assertThat(reloaded.version).isEqualTo("2.0")
        assertThat(reloaded.settingDefs.map { it.key }).containsExactly("quality")
        assertThat(reloaded.settings["quality"]).isEqualTo("lossless")
        // The place it had is its own: a reload is not an install.
        assertThat(stored().map { it.id }).containsExactly("b", "a").inOrder()
        assertThat(stored().map { it.order }).containsExactly(0, 1).inOrder()
    }

    @Test
    fun `a reload keeps the user's own setting values`() = runTest {
        server.enqueue(manifest("a", "A"))
        repository.add("https://addons.test/a/manifest.json")
        val withSettings = MockResponse().setBody(
            """{"id":"a","name":"A","version":"1.0","resources":["search","stream"],
               "settings":[{"key":"quality","type":"select","default":"lossless",
                            "options":[{"value":"lossless"},{"value":"hires"}]}]}""",
        )
        server.enqueue(withSettings)
        repository.reloadManifest("a")
        repository.setSetting("a", "quality", "hires")

        // The manifest comes back with the same setting, and the user's choice
        // is still theirs.
        server.enqueue(withSettings)
        val reloaded = repository.reloadManifest("a").getOrThrow()

        assertThat(reloaded.settings["quality"]).isEqualTo("hires")
    }

    @Test
    fun `a reload of an addon that is not installed fails`() = runTest {
        val result = repository.reloadManifest("nope")

        assertThat((result.exceptionOrNull() as AddAddonException).error)
            .isEqualTo(AddAddonError.NotInstalled)
    }

    @Test
    fun `a reload of an addon that cannot be reached fails without changing anything`() = runTest {
        server.enqueue(manifest("a", "A"))
        repository.add("https://addons.test/a/manifest.json")
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.reloadManifest("a")

        assertThat((result.exceptionOrNull() as AddAddonException).error)
            .isEqualTo(AddAddonError.Unreachable)
        assertThat(stored().map { it.id }).containsExactly("a")
        assertThat(stored().first().version).isEqualTo("1.0")
    }
}
