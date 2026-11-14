package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.TorboxPointerEntity

/**
 * The phone's own TorBox pointers: which torrent in *this* account holds a
 * file.
 *
 * The key is the infohash, which is the global identity of the file. Two
 * phones with different TorBox accounts keep different rows for the same
 * file - and neither needs the other.
 */
@Dao
interface TorboxPointerDao {
    @Query("SELECT * FROM torbox_pointers WHERE infohash = :infohash LIMIT 1")
    suspend fun get(infohash: String): TorboxPointerEntity?

    @Query("SELECT * FROM torbox_pointers WHERE infohash IN (:infohashes)")
    suspend fun getAll(infohashes: List<String>): List<TorboxPointerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pointer: TorboxPointerEntity)

    @Query("DELETE FROM torbox_pointers WHERE infohash = :infohash")
    suspend fun delete(infohash: String)

    /** Forgets the pointers that have not been used for a while. */
    @Query("DELETE FROM torbox_pointers WHERE updated_at < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT COUNT(*) FROM torbox_pointers")
    suspend fun count(): Int
}
