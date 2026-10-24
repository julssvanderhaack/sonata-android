package io.github.julssvanderhaack.sonata.player

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import io.github.julssvanderhaack.sonata.library.Track
import io.github.julssvanderhaack.sonata.library.albumOrFolder
import io.github.julssvanderhaack.sonata.library.artistOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Extra on items opened from another app: their read grant ends with the process, so they aren't saved. */
private const val EXTERNAL = "sonata.external"

/** What the player plays for a library track. The media id is the track id, so the UI can find it again. */
fun Track.toMediaItem(external: Boolean = false): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(uri.toUri())
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artistOrNull())
            .setAlbumTitle(albumOrFolder())
            .setAlbumArtist(albumArtist)
            .setTrackNumber(trackNo.takeIf { it > 0 })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .apply { if (external) setExtras(Bundle().apply { putBoolean(EXTERNAL, true) }) }
            .build(),
    )
    .build()

/** The queue as it was: restored when the service starts and offered to the system to resume from. */
data class SavedQueue(
    val items: List<MediaItem>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeat: Int,
)

/**
 * Keeps the queue in files/queue.json: the items (enough to play them without
 * the library: id, uri and the tags shown), where it was and the shuffle and
 * repeat modes. Written on every change and every few seconds while playing.
 */
class QueueStore(dir: File) {
    private val file = File(dir, "queue.json")

    fun save(p: Player) {
        val items = ArrayList<MediaItem>()
        var index = 0
        for (i in 0 until p.mediaItemCount) {
            val item = p.getMediaItemAt(i)
            if (item.mediaMetadata.extras?.getBoolean(EXTERNAL) == true) continue
            if (i == p.currentMediaItemIndex) index = items.size
            items += item
        }
        // the current song was opened from another app: resume from the start of what's left
        val currentKept = p.currentMediaItem?.mediaMetadata?.extras?.getBoolean(EXTERNAL) != true
        val q = SavedQueue(items, index, if (currentKept) p.currentPosition else 0, p.shuffleModeEnabled, p.repeatMode)
        write(q)
    }

    fun write(q: SavedQueue) {
        if (q.items.isEmpty()) {
            file.delete()
            return
        }
        val arr = JSONArray()
        for (item in q.items) {
            val md = item.mediaMetadata
            arr.put(
                JSONObject()
                    .put("id", item.mediaId)
                    .put("uri", item.localConfiguration?.uri?.toString() ?: item.requestMetadata.mediaUri?.toString() ?: "")
                    .put("title", md.title?.toString() ?: "")
                    .putOpt("artist", md.artist?.toString())
                    .putOpt("album", md.albumTitle?.toString())
                    .putOpt("albumArtist", md.albumArtist?.toString())
                    .put("track", md.trackNumber ?: 0),
            )
        }
        val o = JSONObject()
            .put("items", arr)
            .put("index", q.index)
            .put("position", q.positionMs)
            .put("shuffle", q.shuffle)
            .put("repeat", q.repeat)
        // write then rename: a crash mid-write must not leave half a queue
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }

    fun load(): SavedQueue? = runCatching {
        val o = JSONObject(file.readText())
        val arr = o.getJSONArray("items")
        val items = (0 until arr.length()).mapNotNull { i ->
            val e = arr.getJSONObject(i)
            val uri = e.optString("uri").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            MediaItem.Builder()
                .setMediaId(e.getString("id"))
                .setUri(uri.toUri())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(e.optString("title"))
                        .setArtist(e.optString("artist").takeIf { it.isNotEmpty() })
                        .setAlbumTitle(e.optString("album").takeIf { it.isNotEmpty() })
                        .setAlbumArtist(e.optString("albumArtist").takeIf { it.isNotEmpty() })
                        .setTrackNumber(e.optInt("track").takeIf { it > 0 })
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build(),
                )
                .build()
        }
        if (items.isEmpty()) return null
        SavedQueue(
            items,
            o.optInt("index").coerceIn(0, items.size - 1),
            o.optLong("position").coerceAtLeast(0),
            o.optBoolean("shuffle"),
            o.optInt("repeat", Player.REPEAT_MODE_OFF),
        )
    }.getOrNull()
}
