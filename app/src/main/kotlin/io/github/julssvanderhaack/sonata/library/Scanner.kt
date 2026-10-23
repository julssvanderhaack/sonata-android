package io.github.julssvanderhaack.sonata.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Builds the library from the folders the user picked (SAF trees), walking
 * them and their subfolders and reading the tags itself. It doesn't rely on
 * MediaStore, which misses files that reach the phone some ways (SFTP, adb,
 * apps that don't announce them). Tags are only read again for files whose
 * size or date changed, so a rescan mostly costs the folder listing.
 */
class Scanner(private val context: Context, private val dao: TrackDao) {

    data class Result(val total: Int, val added: Int, val updated: Int, val removed: Int)

    /** What to write so the library matches [found]. */
    data class Diff(val upsert: List<Track>, val delete: List<Long>, val added: Int)

    /** Tag reading on the way: (done, total), null when idle. */
    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val progress: StateFlow<Pair<Int, Int>?> = _progress

    /** A file seen while walking, before its tags are read. */
    private data class Found(
        val id: Long,
        val uri: Uri,
        val name: String,
        val size: Long,
        val modified: Long,
        val mime: String?,
        val volume: String,
        val relativePath: String,
        val lrcUri: String?,
        val coverUri: String?,
    )

    suspend fun scan(trees: List<String>): Result = withContext(Dispatchers.IO) {
        val files = LinkedHashMap<Long, Found>()
        // a folder deleted or a permission revoked is skipped, not fatal
        for (t in trees) runCatching { walk(t.toUri(), files) }
        val known = dao.snapshot()
        val byId = known.associateBy { it.id }
        val out = ArrayList<Track>(files.size)
        val toRead = ArrayList<Found>()
        for (f in files.values) {
            val old = byId[f.id]
            // same size and date: keep the tags read last time
            if (old != null && old.size == f.size && old.dateModified == f.modified) out += f.toTrack(old)
            else toRead += f
        }
        _progress.value = 0 to toRead.size
        try {
            // a few at a time, saved as they come so a first scan fills the library progressively
            for (chunk in toRead.chunked(48)) {
                val read = coroutineScope { chunk.map { f -> async(TAGS) { readTags(f, byId[f.id]) } }.awaitAll() }
                dao.upsert(read)
                out += read
                _progress.value = out.size - (files.size - toRead.size) to toRead.size
            }
        } finally {
            _progress.value = null
        }
        val d = diff(out, known)
        if (d.upsert.isNotEmpty()) d.upsert.chunked(500).forEach { dao.upsert(it) }
        if (d.delete.isNotEmpty()) d.delete.chunked(500).forEach { dao.delete(it) }
        Result(out.size, d.added, d.upsert.size - d.added, d.delete.size)
    }

    /** A single file another app asked us to open: its name and size from the provider, tags from the file. */
    suspend fun readOne(uri: Uri): Track = withContext(Dispatchers.IO) {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val f = Found(
            id = idOf("opened", uri.toString()), uri = uri, name = name, size = size, modified = 0,
            mime = runCatching { context.contentResolver.getType(uri) }.getOrNull(),
            volume = "external_primary", relativePath = "", lrcUri = null, coverUri = null,
        )
        readTags(f, null)
    }

    /** Breadth-first over one granted tree, one provider query per folder. */
    private fun walk(tree: Uri, into: MutableMap<Long, Found>) {
        val rootDoc = DocumentsContract.getTreeDocumentId(tree)
        val (volume, rootRel) = locate(rootDoc)
        val queue = ArrayDeque<Pair<String, String>>().apply { add(rootDoc to rootRel) }
        while (queue.isNotEmpty()) {
            val (docId, rel) = queue.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val entries = ArrayList<Entry>()
            context.contentResolver.query(children, COLUMNS, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (name.startsWith('.')) continue // hidden folders and files
                    entries += Entry(c.getString(0), name, c.getString(2), if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4))
                }
            }
            val byName = entries.associateBy { it.name.lowercase() }
            val cover = COVER_NAMES.firstNotNullOfOrNull { byName[it] }?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.docId).toString() }
            for (e in entries) {
                if (e.mime == Document.MIME_TYPE_DIR) {
                    queue += e.docId to "$rel${e.name}/"
                    continue
                }
                if (!isAudio(e.name, e.mime)) continue
                val base = e.name.substringBeforeLast('.').lowercase()
                val lrc = LYRIC_EXTS.firstNotNullOfOrNull { byName["$base$it"] }
                val id = idOf(volume, rel + e.name)
                into[id] = Found(
                    id = id,
                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, e.docId),
                    name = e.name, size = e.size, modified = e.modified, mime = e.mime,
                    volume = volume, relativePath = rel,
                    lrcUri = lrc?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.docId).toString() },
                    coverUri = cover,
                )
            }
        }
    }

    private class Entry(val docId: String, val name: String, val mime: String?, val size: Long, val modified: Long)

    private fun Found.toTrack(old: Track) = old.copy(
        uri = uri.toString(), fileName = name, volume = volume, relativePath = relativePath,
        lrcUri = lrcUri, coverUri = coverUri, mime = mime ?: old.mime,
    )

    private fun readTags(f: Found, old: Track?): Track {
        val r = MediaMetadataRetriever()
        val tags = try {
            r.setDataSource(context, f.uri)
            Tags(
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                albumArtist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                genre = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                year = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                    ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE),
                track = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                disc = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
            )
        } catch (e: Exception) {
            Tags() // unreadable or not really audio: still listed by its name, the filters decide
        } finally {
            r.release()
        }
        return trackOf(
            id = f.id, uri = f.uri.toString(), name = f.name, size = f.size, modified = f.modified, mime = f.mime,
            volume = f.volume, relativePath = f.relativePath, lrcUri = f.lrcUri, coverUri = f.coverUri,
            tags = tags, dateAdded = old?.dateAdded ?: (f.modified / 1000),
        )
    }

    /** Raw tag strings as the retriever gives them. */
    data class Tags(
        val title: String? = null,
        val artist: String? = null,
        val albumArtist: String? = null,
        val album: String? = null,
        val genre: String? = null,
        val year: String? = null,
        val track: String? = null,
        val disc: String? = null,
        val durationMs: Long = 0,
    )

    companion object {
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        private val TAGS = Dispatchers.IO.limitedParallelism(4)
        private val LYRIC_EXTS = listOf(".lrc", ".txt")
        private val COVER_NAMES = listOf("cover", "folder", "front", "albumart", "album")
            .flatMap { n -> listOf("jpg", "jpeg", "png", "webp").map { "$n.$it" } }
        private val AUDIO_EXTS = setOf(
            "mp3", "flac", "ogg", "oga", "opus", "m4a", "m4b", "aac", "wav", "wma", "ape", "alac",
            "aiff", "aif", "mka", "wv", "amr", "3ga",
        )

        fun isAudio(name: String, mime: String?) =
            mime?.startsWith("audio/") == true || name.substringAfterLast('.', "").lowercase() in AUDIO_EXTS

        /**
         * Volume and folder of a tree's root document. ExternalStorageProvider ids
         * are "primary:Music/Rock" (internal storage), "1234-ABCD:Music" (SD card or
         * USB) and "home:…" (Documents); others are opaque, so their files sit at
         * the root of internal storage in the folder view.
         */
        fun locate(docId: String): Pair<String, String> {
            val colon = docId.indexOf(':')
            if (colon < 0) return "external_primary" to ""
            val vol = docId.substring(0, colon)
            val path = docId.substring(colon + 1).trim('/')
            val rel = if (path.isEmpty()) "" else "$path/"
            return when (vol) {
                "primary" -> "external_primary" to rel
                "home" -> "external_primary" to "Documents/$rel"
                "raw", "msf", "msd" -> "external_primary" to ""
                else -> vol.lowercase() to rel
            }
        }

        /** Stable id from the file's place: the same song reached through two picked folders is one track. */
        fun idOf(volume: String, path: String): Long {
            var h = -0x340d631b7bdddcdbL // FNV-1a 64
            for (ch in "$volume:$path") {
                h = h xor ch.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }

        fun trackOf(
            id: Long, uri: String, name: String, size: Long, modified: Long, mime: String?,
            volume: String, relativePath: String, lrcUri: String?, coverUri: String?, tags: Tags, dateAdded: Long,
        ): Track {
            fun s(v: String?) = v?.let(::fixMojibake)?.trim()?.takeIf { it.isNotEmpty() }
            // "3/12" → 3
            fun n(v: String?) = v?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
            return Track(
                id = id,
                title = cleanTag(s(tags.title)) ?: name.substringBeforeLast('.'),
                artist = s(tags.artist),
                albumArtist = s(tags.albumArtist),
                album = s(tags.album),
                genre = s(tags.genre),
                year = tags.year?.let { Regex("\\d{4}").find(it)?.value?.toInt() } ?: 0,
                trackNo = n(tags.track),
                discNo = n(tags.disc),
                durationMs = tags.durationMs,
                size = size,
                mime = mime,
                volume = volume,
                relativePath = relativePath,
                fileName = name,
                dateAdded = dateAdded,
                dateModified = modified,
                kinds = 0,
                uri = uri,
                lrcUri = lrcUri,
                coverUri = coverUri,
            )
        }

        /**
         * Compares whole rows, not just the modification date: a moved or
         * renamed file, a new .lrc or cover next to it, all change the row.
         */
        fun diff(found: List<Track>, known: List<Track>): Diff {
            val byId = known.associateBy { it.id }
            val upsert = found.filter { byId[it.id] != it }
            val added = upsert.count { it.id !in byId }
            val seen = found.mapTo(HashSet()) { it.id }
            return Diff(upsert, known.map { it.id }.filter { it !in seen }, added)
        }
    }
}
