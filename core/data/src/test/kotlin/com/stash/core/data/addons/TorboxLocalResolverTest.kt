package com.stash.core.data.addons

import com.stash.core.data.db.dao.TorboxPointerDao
import com.stash.core.data.db.entity.TorboxPointerEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The phone's own TorBox account.
 *
 * The server says what the file is (the infohash, the same in every account
 * in the world). Which torrent in *this* account holds it is the phone's own
 * business, and it lives here.
 */
class TorboxLocalResolverTest {
    private val dao: TorboxPointerDao = mockk(relaxed = true)
    private val client: TorboxClient = mockk()
    private val resolver = TorboxLocalResolver(dao, client)

    private val file = EclipseTorboxFile(
        infohash = "a".repeat(40),
        file = "Song - Artist [NullBeat 1 - HiFi CD].flac",
        quality = "HiFi CD",
    )

    @Test
    fun `a file already in this account is asked for by its torrent id`() = runTest {
        coEvery { dao.get(file.infohash) } returns TorboxPointerEntity(
            infohash = file.infohash,
            torrentId = "4242",
            fileId = null,
        )
        coEvery { client.requestdl(any(), any(), any(), any()) } returns "https://cdn.example.test/a.flac"

        val url = resolver.resolve(file, "Song", "the-key")

        assertEquals("https://cdn.example.test/a.flac", url)
        // The account's own torrent id is what was asked for.
        coVerify { client.requestdl("the-key", "4242", null, null) }
        // And nothing was added to the account.
        coVerify(exactly = 0) { client.createtorrent(any(), any(), any(), any()) }
    }

    @Test
    fun `a file this account does not have is added, and remembered`() = runTest {
        coEvery { dao.get(file.infohash) } returns null
        coEvery { client.createtorrent(any(), any(), any(), any()) } returns "777"
        coEvery { client.requestdl(any(), any(), any(), any()) } returns "https://cdn.example.test/b.flac"

        val url = resolver.resolve(file, "Song", "the-key")

        assertEquals("https://cdn.example.test/b.flac", url)
        coVerify { client.createtorrent("the-key", file.infohash, file.file!!, 3) }
        // The torrent id is kept here, on this phone.
        val saved = slot<TorboxPointerEntity>()
        coVerify { dao.upsert(capture(saved)) }
        assertEquals("777", saved.captured.torrentId)
        assertEquals(file.infohash, saved.captured.infohash)
    }

    @Test
    fun `a torrent TorBox has forgotten is forgotten here too, and added again`() = runTest {
        coEvery { dao.get(file.infohash) } returns TorboxPointerEntity(
            infohash = file.infohash,
            torrentId = "dead",
            fileId = null,
        )
        // The old torrent gives no url.
        coEvery { client.requestdl("the-key", "dead", null, null) } returns null
        coEvery { client.createtorrent(any(), any(), any(), any()) } returns "888"
        coEvery { client.requestdl("the-key", "888", null, null) } returns "https://cdn.example.test/c.flac"

        val url = resolver.resolve(file, "Song", "the-key")

        assertEquals("https://cdn.example.test/c.flac", url)
        coVerify { dao.delete(file.infohash) }
    }

    @Test
    fun `without a key nothing is asked of TorBox`() = runTest {
        val url = resolver.resolve(file, "Song", "")
        assertNull(url)
        coVerify(exactly = 0) { client.requestdl(any(), any(), any(), any()) }
        coVerify(exactly = 0) { client.createtorrent(any(), any(), any(), any()) }
    }

    @Test
    fun `a name that is not an infohash is refused`() = runTest {
        val url = resolver.resolve(EclipseTorboxFile(infohash = "nope"), "Song", "the-key")
        assertNull(url)
        coVerify(exactly = 0) { client.createtorrent(any(), any(), any(), any()) }
    }
}
