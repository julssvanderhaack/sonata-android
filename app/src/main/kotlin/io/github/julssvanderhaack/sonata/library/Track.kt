package io.github.julssvanderhaack.sonata.library

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Everything MediaStore knows about one audio file. Filters are applied when reading, so changing them needs no rescan. */
@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String?,
    @ColumnInfo(name = "album_artist") val albumArtist: String?,
    val album: String?,
    val genre: String?,
    val year: Int,
    @ColumnInfo(name = "track_no") val trackNo: Int,
    @ColumnInfo(name = "disc_no") val discNo: Int,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    val size: Long,
    val mime: String?,
    /** MediaStore volume name, e.g. "external_primary". */
    val volume: String,
    /** Folder relative to the volume root, always ending in '/', e.g. "Music/Rock/". */
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "date_added") val dateAdded: Long,
    @ColumnInfo(name = "date_modified") val dateModified: Long,
    /** Bit set of [Kind] values. */
    val kinds: Int,
) {
    val folderKey get() = "$volume:$relativePath"
    val path get() = relativePath + fileName
    fun isKind(k: Kind) = (kinds and k.bit) != 0

    enum class Kind(val bit: Int) {
        RINGTONE(1), NOTIFICATION(2), ALARM(4), RECORDING(8), PODCAST(16), AUDIOBOOK(32)
    }
}

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks")
    fun all(): Flow<List<Track>>

    @Query("SELECT * FROM tracks")
    suspend fun snapshot(): List<Track>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tracks: List<Track>)

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}

@Database(entities = [Track::class], version = 2)
abstract class LibraryDb : RoomDatabase() {
    abstract fun tracks(): TrackDao
}
