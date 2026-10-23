package io.github.julssvanderhaack.sonata.lyrics

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.provider.DocumentsContract
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.inspector.MetadataRetriever
import io.github.julssvanderhaack.sonata.library.Track
import io.github.julssvanderhaack.sonata.library.albumArtistOrNull
import io.github.julssvanderhaack.sonata.library.artistOrNull
import io.github.julssvanderhaack.sonata.library.cleanTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    /** [searching] = shown while lrclib may still bring a synced version. */
    data class Ready(val lyrics: Lyrics, val searching: Boolean = false) : LyricsState
    /** Nothing found; [onlineOff] = internet search is disabled, so offer it. */
    data class None(val onlineOff: Boolean) : LyricsState
    data object Offline : LyricsState
}

/**
 * Lyrics like on desktop: a .lrc next to the song (if the user granted the
 * folder) → the file's own tag → the disk cache → lrclib.net. Online search
 * is opt-in: only artist, title, album and duration are sent.
 */
class LyricsRepository(private val context: Context) {
    private val cacheDir = File(context.cacheDir, "lyrics")

    fun load(t: Track, online: Boolean, lrcTree: String?, skipCache: Boolean): Flow<LyricsState> = flow {
        emit(LyricsState.Loading)
        // 1. sidecar .lrc / .txt: next to the song (found while scanning), or in the folder granted for lyrics
        (t.lrcUri?.let(::readUri) ?: lrcTree?.let { readSidecar(t, it.toUri()) })?.let { text ->
            val l = parseLyrics(text, Lyrics.Source.FILE)
            if (!l.isEmpty) return@flow emit(LyricsState.Ready(l))
        }
        // 2. embedded; prefer it only if synced, an online synced copy is nicer
        val embedded = readEmbedded(t)?.let { parseLyrics(it, Lyrics.Source.TAG) }?.takeUnless { it.isEmpty }
        if (embedded?.synced == true) return@flow emit(LyricsState.Ready(embedded))
        if (!online) return@flow emit(embedded?.let { LyricsState.Ready(it) } ?: LyricsState.None(onlineOff = true))

        // 3. cache
        val artist = t.artistOrNull() ?: t.albumArtistOrNull() ?: ""
        val seconds = (t.durationMs / 1000.0)
        val cache = File(cacheDir, sha1("${artist.lowercase()}\n${t.title.lowercase()}\n${seconds.roundToInt()}") + ".json")
        if (!skipCache) readCache(cache)?.let { o ->
            val none = o.optBoolean("none")
            val age = System.currentTimeMillis() / 1000 - o.optLong("time")
            if (!none || age < 3 * 86400) {
                if (none) return@flow emit(embedded?.let { LyricsState.Ready(it) } ?: LyricsState.None(onlineOff = false))
                return@flow emit(fromJson(o))
            }
        }
        // 4. lrclib.net
        emit(embedded?.let { LyricsState.Ready(it, searching = true) } ?: LyricsState.Loading)
        val found = try {
            fetch(t, artist, seconds)
        } catch (e: IOException) {
            return@flow emit(embedded?.let { LyricsState.Ready(it) } ?: LyricsState.Offline) // don't cache the miss
        }
        if (found == null) {
            writeCache(cache, JSONObject().put("none", true).put("time", System.currentTimeMillis() / 1000))
            return@flow emit(embedded?.let { LyricsState.Ready(it) } ?: LyricsState.None(onlineOff = false))
        }
        val o = JSONObject()
            .put("synced", found.synced).put("plain", found.plain)
            .put("instrumental", found.instrumental).put("time", System.currentTimeMillis() / 1000)
        writeCache(cache, o)
        emit(fromJson(o))
    }.flowOn(Dispatchers.IO)

    private fun fromJson(o: JSONObject): LyricsState {
        val synced = o.optString("synced")
        val l = parseLyrics(synced.ifEmpty { o.optString("plain") }, Lyrics.Source.LRCLIB)
            .copy(instrumental = o.optBoolean("instrumental"))
        return if (l.isEmpty) LyricsState.None(onlineOff = false) else LyricsState.Ready(l)
    }

    /** get (exact match) first, then search; lrclib is sometimes briefly overloaded, so 5xx is retried. */
    private suspend fun fetch(t: Track, artist: String, seconds: Double): Candidate? {
        val q = buildList {
            add("artist_name" to artist)
            add("track_name" to t.title)
            cleanTag(t.album)?.let { add("album_name" to it) }
            if (seconds > 0) add("duration" to seconds.roundToInt().toString())
        }
        httpGet("https://lrclib.net/api/get?" + query(q))?.let { body ->
            val o = JSONObject(body)
            val c = Candidate(o.optString("syncedLyrics"), o.optString("plainLyrics"), o.optBoolean("instrumental"), o.optDouble("duration", 0.0))
            if (c.synced.isNotEmpty() || c.plain.isNotEmpty() || c.instrumental) return c
        }
        val sq = buildList {
            add("track_name" to t.title)
            if (artist.isNotEmpty()) add("artist_name" to artist)
        }
        val arr = JSONArray(httpGet("https://lrclib.net/api/search?" + query(sq)) ?: return null)
        val list = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Candidate(o.optString("syncedLyrics"), o.optString("plainLyrics"), o.optBoolean("instrumental"), o.optDouble("duration", 0.0))
        }
        return chooseCandidate(list, seconds)
    }

    /** Body on 200, null on 404, IOException when offline or the server keeps failing. */
    private suspend fun httpGet(url: String): String? {
        var attempt = 0
        while (true) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 8000
                c.readTimeout = 12000
                c.setRequestProperty("User-Agent", USER_AGENT)
                val code = c.responseCode
                when {
                    code == 200 -> return c.inputStream.bufferedReader().use { it.readText() }
                    code == 404 -> return null
                    code >= 500 && attempt < 2 -> Unit
                    else -> throw IOException("HTTP $code")
                }
            } finally {
                c.disconnect()
            }
            attempt++
            delay(2500L * attempt)
        }
    }

    private fun query(q: List<Pair<String, String>>) =
        q.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }

    /**
     * A .lrc with the song's name inside the granted folder tree. The tree's
     * document ids are "primary:Music/…" (or "1234-ABCD:…" for an SD card), so the
     * song's MediaStore volume + relative path give the id directly, no listing.
     */
    private fun readUri(uri: String): String? = try {
        context.contentResolver.openInputStream(uri.toUri())?.use { it.readBytes().toString(Charsets.UTF_8) }?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    private fun readSidecar(t: Track, tree: Uri): String? {
        val root = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        val volume = if (t.volume == "external_primary" || t.volume == "external") "primary" else t.volume.uppercase()
        val base = t.fileName.substringBeforeLast('.')
        for (ext in listOf(".lrc", ".LRC", ".txt")) {
            val id = "$volume:${t.relativePath}$base$ext"
            // "primary:" is the whole volume; "primary:Music" covers Music/ and below
            val inside = if (root.endsWith(":")) id.startsWith(root) else id.startsWith("$root/")
            if (!inside) continue
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            val text = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: Exception) { // FileNotFound, SecurityException…
                null
            }
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun readEmbedded(t: Track): String? = try {
        MetadataRetriever.Builder(context, MediaItem.fromUri(t.uri.toUri())).build().use { r ->
            val groups = r.retrieveTrackGroups().get(10, TimeUnit.SECONDS)
            var found: String? = null
            for (g in 0 until groups.length) {
                val group = groups[g]
                for (f in 0 until group.length) {
                    val md = group.getFormat(f).metadata ?: continue
                    for (i in 0 until md.length()) {
                        val e = md[i]
                        found = when {
                            e is BinaryFrame && e.id == "USLT" -> parseUslt(e.data)
                            // MP4 ©lyr comes through as a text frame with the USLT id
                            e is TextInformationFrame && e.id == "USLT" -> e.values.firstOrNull()
                            e is TextInformationFrame && e.id == "TXXX" && e.description?.uppercase() in LYRIC_KEYS -> e.values.firstOrNull()
                            e is VorbisComment && e.key.uppercase() in LYRIC_KEYS -> e.value
                            else -> null
                        }?.takeIf { it.isNotBlank() } ?: found
                    }
                }
            }
            found
        }
    } catch (e: Exception) {
        null
    }

    private fun readCache(f: File): JSONObject? = runCatching { JSONObject(f.readText()) }.getOrNull()

    private fun writeCache(f: File, o: JSONObject) {
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(o.toString())
        }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val USER_AGENT = "Sonata-Android/0.2 (https://github.com/julssvanderhaack/sonata)"
        private val LYRIC_KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS")
    }
}
