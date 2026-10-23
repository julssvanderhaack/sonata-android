package io.github.julssvanderhaack.sonata.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.Audio.AudioColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mirrors MediaStore's audio table into Room. MediaStore already indexes all
 * shared storage (internal + SD) and leaves out system sounds and other apps'
 * private folders, so a full query takes well under a second; only rows that
 * changed are written.
 */
class Scanner(private val context: Context, private val dao: TrackDao) {

    data class Result(val total: Int, val added: Int, val updated: Int, val removed: Int)

    /** What to write so the library matches [found]. */
    data class Diff(val upsert: List<Track>, val delete: List<Long>, val added: Int)

    suspend fun scan(): Result = withContext(Dispatchers.IO) {
        val found = query()
        val d = diff(found, dao.snapshot())
        if (d.upsert.isNotEmpty()) d.upsert.chunked(500).forEach { dao.upsert(it) }
        if (d.delete.isNotEmpty()) d.delete.chunked(500).forEach { dao.delete(it) }
        Result(found.size, d.added, d.upsert.size - d.added, d.delete.size)
    }

    private fun query(): List<Track> {
        val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val r = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val s = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val cols = buildList<String> {
            addAll(
                listOf(
                    AudioColumns._ID, AudioColumns.TITLE, AudioColumns.ARTIST, AudioColumns.ALBUM,
                    AudioColumns.YEAR, AudioColumns.TRACK, AudioColumns.DURATION, AudioColumns.SIZE,
                    AudioColumns.MIME_TYPE, AudioColumns.DISPLAY_NAME, AudioColumns.DATE_ADDED,
                    AudioColumns.DATE_MODIFIED, AudioColumns.IS_RINGTONE, AudioColumns.IS_NOTIFICATION,
                    AudioColumns.IS_ALARM, AudioColumns.IS_PODCAST, "album_artist",
                ),
            )
            if (q) addAll(listOf(AudioColumns.RELATIVE_PATH, AudioColumns.VOLUME_NAME, AudioColumns.IS_AUDIOBOOK))
            else add(@Suppress("DEPRECATION") AudioColumns.DATA)
            if (r) addAll(listOf(AudioColumns.GENRE, AudioColumns.DISC_NUMBER, AudioColumns.CD_TRACK_NUMBER))
            if (s) add(AudioColumns.IS_RECORDING)
        }.toMutableList()
        val uri = if (q) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val out = ArrayList<Track>()
        // album_artist is only documented from Android 11; older builds may reject it
        val cursor = try {
            context.contentResolver.query(uri, cols.toTypedArray(), null, null, null)
        } catch (e: IllegalArgumentException) {
            cols.remove("album_artist")
            context.contentResolver.query(uri, cols.toTypedArray(), null, null, null)
        }
        cursor?.use { c ->
            val ix = cols.associateWith { c.getColumnIndex(it) }
            fun str(k: String) = ix[k]?.takeIf { it >= 0 }?.let { fixMojibake(c.getString(it)) }
            fun long(k: String) = ix[k]?.takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) } ?: 0L
            fun flag(k: String) = long(k) != 0L
            while (c.moveToNext()) {
                val name = str(AudioColumns.DISPLAY_NAME) ?: continue
                val (volume, rel) = if (q) {
                    (str(AudioColumns.VOLUME_NAME) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                        (str(AudioColumns.RELATIVE_PATH) ?: "")
                } else {
                    splitLegacyPath(str(@Suppress("DEPRECATION") AudioColumns.DATA) ?: continue)
                }
                // TRACK packs the disc in the thousands (1003 = disc 1, track 3)
                val packed = long(AudioColumns.TRACK).toInt()
                val trackNo = if (r) str(AudioColumns.CD_TRACK_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull() ?: (packed % 1000)
                else packed % 1000
                val discNo = if (r) str(AudioColumns.DISC_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull() ?: (packed / 1000)
                else packed / 1000
                var kinds = 0
                if (flag(AudioColumns.IS_RINGTONE)) kinds = kinds or Track.Kind.RINGTONE.bit
                if (flag(AudioColumns.IS_NOTIFICATION)) kinds = kinds or Track.Kind.NOTIFICATION.bit
                if (flag(AudioColumns.IS_ALARM)) kinds = kinds or Track.Kind.ALARM.bit
                if (flag(AudioColumns.IS_PODCAST)) kinds = kinds or Track.Kind.PODCAST.bit
                if (q && flag(AudioColumns.IS_AUDIOBOOK)) kinds = kinds or Track.Kind.AUDIOBOOK.bit
                if (s && flag(AudioColumns.IS_RECORDING)) kinds = kinds or Track.Kind.RECORDING.bit
                out += Track(
                    id = long(AudioColumns._ID),
                    title = cleanTag(str(AudioColumns.TITLE)) ?: name.substringBeforeLast('.'),
                    artist = str(AudioColumns.ARTIST),
                    albumArtist = str("album_artist"),
                    album = str(AudioColumns.ALBUM),
                    genre = if (r) str(AudioColumns.GENRE) else null,
                    year = long(AudioColumns.YEAR).toInt(),
                    trackNo = trackNo,
                    discNo = discNo,
                    durationMs = long(AudioColumns.DURATION),
                    size = long(AudioColumns.SIZE),
                    mime = str(AudioColumns.MIME_TYPE),
                    volume = volume,
                    relativePath = if (rel.isEmpty() || rel.endsWith('/')) rel else "$rel/",
                    fileName = name,
                    dateAdded = long(AudioColumns.DATE_ADDED),
                    dateModified = long(AudioColumns.DATE_MODIFIED),
                    kinds = kinds,
                )
            }
        }
        return out
    }

    /** Before Android 10 there is only the absolute path: /storage/emulated/0/Music/a.mp3 or /storage/1234-ABCD/… */
    private fun splitLegacyPath(abs: String): Pair<String, String> {
        @Suppress("DEPRECATION")
        val primary = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/') + "/"
        val dir = abs.substringBeforeLast('/') + "/"
        if (dir.startsWith(primary)) return "external_primary" to dir.removePrefix(primary)
        val m = Regex("^/storage/([^/]+)/(.*)$").find(dir)
        if (m != null) return m.groupValues[1].lowercase() to m.groupValues[2]
        return "external_primary" to dir.trimStart('/')
    }

    companion object {
        /**
         * Compares whole rows, not just the modification date: renaming a file
         * or moving its folder keeps MediaStore's id and date but changes the
         * name and path, and MediaStore may re-read tags without touching the
         * date either.
         */
        fun diff(found: List<Track>, known: List<Track>): Diff {
            val byId = known.associateBy { it.id }
            val upsert = found.filter { byId[it.id] != it }
            val added = upsert.count { it.id !in byId }
            val seen = found.mapTo(HashSet()) { it.id }
            return Diff(upsert, known.map { it.id }.filter { it !in seen }, added)
        }

        fun uriOf(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    }
}
