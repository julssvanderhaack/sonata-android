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

/** One audio file found in the music folders. Filters are applied when reading, so changing them needs no rescan. */
@Entity(tableName = "tracks")
data class Track(
    /** [Scanner.idOf] the file's volume and path. */
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
    /** Storage volume, named like MediaStore does: "external_primary" or the SD card's id ("1234-abcd"). */
    val volume: String,
    /** Folder relative to the volume root, always ending in '/', e.g. "Music/Rock/". */
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "date_added") val dateAdded: Long,
    /** Milliseconds, as the folder listing gives it. */
    @ColumnInfo(name = "date_modified") val dateModified: Long,
    /** Bit set of [Kind] values (MediaStore's categories; 0 for files found by walking the folders). */
    val kinds: Int,
    /** Document URI inside the granted folder: what is played and read. */
    val uri: String = "",
    /** A .lrc (or .txt) with the same name next to the song. */
    @ColumnInfo(name = "lrc_uri") val lrcUri: String? = null,
    /** cover.jpg / folder.jpg… in the song's folder. */
    @ColumnInfo(name = "cover_uri") val coverUri: String? = null,
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

@Database(entities = [Track::class], version = 3)
abstract class LibraryDb : RoomDatabase() {
    abstract fun tracks(): TrackDao
}

/** The library by id, for code that only has an id (cover art, lyrics): kept current by [io.github.julssvanderhaack.sonata.SonataApp]. */
object TrackIndex {
    @Volatile var byId: Map<Long, Track> = emptyMap()
    /** Files opened from another app ("Abrir con Sonata"): playable, but not part of the library. */
    @Volatile var opened: Map<Long, Track> = emptyMap()

    operator fun get(id: Long): Track? = byId[id] ?: opened[id]
}
