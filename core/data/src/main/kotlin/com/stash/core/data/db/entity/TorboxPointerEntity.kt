package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Where a TorBox file lives in *this* phone's TorBox account.
 *
 * The infohash is global - the same file has the same hash in every TorBox
 * account in the world - so it is what the server's catalogue is keyed by.
 * The torrent id is not: it says which torrent in one account holds the file,
 * and it belongs to the user who owns that account. So it lives here, on the
 * phone, and never travels to a server that serves other people.
 *
 * The row is a cache: a torrent id that TorBox no longer knows is forgotten
 * when a request comes back 404 or 410, and the file is added again.
 */
@Entity(
    tableName = "torbox_pointers",
    indices = [Index(value = ["updated_at"])],
)
data class TorboxPointerEntity(
    /** TorBox's 40-character torrent hash. The global identity of the file. */
    @PrimaryKey
    @ColumnInfo(name = "infohash")
    val infohash: String,

    /** The torrent in this account that holds the file. Private to this phone. */
    @ColumnInfo(name = "torrent_id")
    val torrentId: String,

    /** Which file inside the torrent. Null when the torrent holds one. */
    @ColumnInfo(name = "file_id")
    val fileId: String? = null,

    /** The file's name, for display and for the addon's own naming. */
    @ColumnInfo(name = "file_name")
    val fileName: String? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)
