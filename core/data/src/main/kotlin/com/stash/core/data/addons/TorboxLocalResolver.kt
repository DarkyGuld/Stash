package com.stash.core.data.addons

import android.util.Log
import com.stash.core.data.db.dao.TorboxPointerDao
import com.stash.core.data.db.entity.TorboxPointerEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

/**
 * Plays a TorBox file from *this* phone's TorBox account.
 *
 * The server's catalogue says what the file is: its infohash, which is the
 * same in every account in the world. Which torrent holds the file is this
 * user's business, so it lives on this phone.
 *
 * The flow:
 *
 *  1. Is the file already in this account? Then ask TorBox for the CDN url -
 *     a moment's work.
 *  2. Is not: add the file to this account (TorBox has it in its own cache
 *     when it is a popular release, so this is quick), remember the torrent
 *     id here, and ask for the url.
 *
 * A torrent id TorBox no longer knows is forgotten, and the file is added
 * again.
 */
@Singleton
class TorboxLocalResolver @Inject constructor(
    private val dao: TorboxPointerDao,
    private val client: TorboxClient,
) {
    /**
     * The CDN url for a file, from this phone's account.
     *
     * @param file the global file the server's catalogue named
     * @param name a name for the torrent, when the server did not have one
     * @param apiKey the user's own TorBox key
     * @param userIp the phone's address, so TorBox can bind the url to it
     * @return the url, or null when this account cannot serve the file
     */
    suspend fun resolve(
        file: EclipseTorboxFile,
        name: String,
        apiKey: String,
        userIp: String? = null,
    ): String? {
        if (apiKey.isBlank()) return null
        val infohash = file.infohash.trim().lowercase()
        if (!infohash.matches(INFOHASH)) return null

        // 1. Already in this account?
        val known = dao.get(infohash)
        if (known != null) {
            val url = client.requestdl(apiKey, known.torrentId, known.fileId, userIp)
            if (url != null) {
                dao.upsert(known.copy(updatedAt = System.currentTimeMillis()))
                return url
            }
            // TorBox forgot the torrent: forget it here too, and add it again.
            Log.i(TAG, "the torrent for ${infohash.take(12)} is gone; adding it again")
            dao.delete(infohash)
        }

        // 2. Add it to this account and remember where it landed.
        val torrentId = client.createtorrent(apiKey, infohash, file.file ?: name)
            ?: return null
        dao.upsert(
            TorboxPointerEntity(
                infohash = infohash,
                torrentId = torrentId,
                fileId = null,
                fileName = file.file,
            ),
        )

        // A file that TorBox has in its own cache is downloadable at once; one
        // it has to fetch is not, and the wait belongs to the player ("forbereder…").
        for (attempt in 1..MAX_PUBLISH_ATTEMPTS) {
            val url = client.requestdl(apiKey, torrentId, null, userIp)
            if (url != null) return url
            if (attempt < MAX_PUBLISH_ATTEMPTS) delay(PUBLISH_WAIT_MS)
        }
        return null
    }

    companion object {
        private const val TAG = "TorboxLocal"
        private val INFOHASH = Regex("^[0-9a-f]{40}$")

        /** How many times to ask for a url that TorBox has not published yet. */
        private const val MAX_PUBLISH_ATTEMPTS = 3

        /** The gap between the attempts. Three of them fit in the player's wait. */
        private const val PUBLISH_WAIT_MS = 1_500L
    }
}
