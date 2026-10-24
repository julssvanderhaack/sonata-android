package io.github.julssvanderhaack.sonata.library

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val created: Long = System.currentTimeMillis(),
)

/**
 * One song in a playlist. It points at the track id (volume + path), and keeps
 * the title and artist so a song that left the library still shows by name.
 */
@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlist_id", "pos"],
    foreignKeys = [ForeignKey(Playlist::class, ["id"], ["playlist_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("playlist_id")],
)
data class PlaylistItem(
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    val pos: Int,
    @ColumnInfo(name = "track_id") val trackId: Long,
    val title: String,
    val artist: String?,
)

data class PlaylistSummary(val id: Long, val name: String, val count: Int)

@Dao
interface PlaylistDao {
    @Query(
        "SELECT p.id, p.name, COUNT(i.track_id) AS count FROM playlists p " +
            "LEFT JOIN playlist_items i ON i.playlist_id = p.id GROUP BY p.id ORDER BY p.name COLLATE NOCASE",
    )
    fun summaries(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun playlist(id: Long): Flow<Playlist?>

    @Query("SELECT * FROM playlist_items WHERE playlist_id = :id ORDER BY pos")
    fun items(id: Long): Flow<List<PlaylistItem>>

    @Query("SELECT * FROM playlist_items WHERE playlist_id = :id ORDER BY pos")
    suspend fun itemsNow(id: Long): List<PlaylistItem>

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    suspend fun allNow(): List<Playlist>

    @Insert
    suspend fun insert(p: Playlist): Long

    @Insert
    suspend fun insertItems(items: List<PlaylistItem>)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM playlist_items WHERE playlist_id = :id")
    suspend fun clear(id: Long)

    /** Replaces the songs of a playlist (reordering and removing are just a new list). */
    @Transaction
    suspend fun setItems(id: Long, items: List<PlaylistItem>) {
        clear(id)
        insertItems(items.mapIndexed { i, it -> it.copy(playlistId = id, pos = i) })
    }

    @Transaction
    suspend fun append(id: Long, songs: List<Track>) {
        val start = itemsNow(id).size
        insertItems(songs.mapIndexed { i, t -> PlaylistItem(id, start + i, t.id, t.title, t.artistOrNull()) })
    }

    @Transaction
    suspend fun create(name: String, songs: List<Track>): Long {
        val id = insert(Playlist(name = name))
        insertItems(songs.mapIndexed { i, t -> PlaylistItem(id, i, t.id, t.title, t.artistOrNull()) })
        return id
    }
}

/**
 * Playlists live apart from the library database: that one is a cache of the
 * music folders and is thrown away when its schema changes; these are the
 * user's own and must survive every update.
 */
@Database(entities = [Playlist::class, PlaylistItem::class], version = 1)
abstract class PlaylistDb : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
}
