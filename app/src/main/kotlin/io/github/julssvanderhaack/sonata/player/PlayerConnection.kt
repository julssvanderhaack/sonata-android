package io.github.julssvanderhaack.sonata.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.julssvanderhaack.sonata.library.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NowPlaying(
    val trackId: Long? = null,
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
)

/** One entry of the play queue, in the order it will play. [index] is its place in the player's list. */
data class QueueEntry(val index: Int, val trackId: Long?, val title: String, val artist: String?, val current: Boolean)

/** UI-side handle on [PlaybackService]: a MediaController plus its state as a flow. */
class PlayerConnection(private val context: Context) {
    private var controller: MediaController? = null
    private var connecting = false
    private val pending = ArrayList<(MediaController) -> Unit>()
    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state
    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queue: StateFlow<List<QueueEntry>> = _queue

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
                publishQueue(player)
            }
        }
    }

    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            publish(c)
            publishQueue(c)
            pending.forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let(block) ?: run {
            pending += block
            connect()
        }
    }

    /** Called by the UI clock while visible; the position isn't an event. */
    fun refreshPosition() {
        controller?.let { c -> _state.value = _state.value.copy(positionMs = c.currentPosition, durationMs = c.duration.coerceAtLeast(0)) }
    }

    private fun publish(p: Player) {
        val item = p.currentMediaItem
        val md = p.mediaMetadata
        _state.value = NowPlaying(
            trackId = item?.mediaId?.toLongOrNull(),
            title = md.title?.toString() ?: "",
            artist = md.artist?.toString(),
            album = md.albumTitle?.toString(),
            playing = p.isPlaying,
            positionMs = p.currentPosition,
            durationMs = p.duration.coerceAtLeast(0),
            shuffle = p.shuffleModeEnabled,
            repeat = p.repeatMode,
            hasNext = p.hasNextMediaItem(),
            hasPrevious = p.hasPreviousMediaItem(),
        )
    }

    /** The queue in play order (shuffled when shuffle is on). */
    private fun publishQueue(p: Player) {
        val t = p.currentTimeline
        val out = ArrayList<QueueEntry>(p.mediaItemCount)
        if (!t.isEmpty) {
            var i = t.getFirstWindowIndex(p.shuffleModeEnabled)
            while (i != C.INDEX_UNSET && out.size < t.windowCount) {
                val item = p.getMediaItemAt(i)
                out += QueueEntry(
                    i, item.mediaId.toLongOrNull(), item.mediaMetadata.title?.toString() ?: "",
                    item.mediaMetadata.artist?.toString(), i == p.currentMediaItemIndex,
                )
                i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
            }
        }
        _queue.value = out
    }

    fun play(queue: List<Track>, start: Int, shuffle: Boolean = false, external: Boolean = false) = withController { c ->
        c.setMediaItems(queue.map { it.toMediaItem(external) }, start.coerceIn(0, (queue.size - 1).coerceAtLeast(0)), 0)
        c.shuffleModeEnabled = shuffle
        c.prepare()
        c.play()
    }

    fun toggle() = withController {
        if (it.isPlaying) it.pause()
        else {
            // a queue restored after a restart, or one that ran out, needs preparing first
            if (it.playbackState == Player.STATE_IDLE) it.prepare()
            it.play()
        }
    }

    /** "Reproducir a continuación": right after the current song; with nothing queued, plays them. */
    fun playNext(tracks: List<Track>) = withController { c ->
        if (tracks.isEmpty()) return@withController
        if (c.mediaItemCount == 0) return@withController play(tracks, 0)
        c.addMediaItems(c.currentMediaItemIndex + 1, tracks.map { it.toMediaItem() })
    }

    /** "Añadir a la cola": at the end. */
    fun enqueue(tracks: List<Track>) = withController { c ->
        if (tracks.isEmpty()) return@withController
        if (c.mediaItemCount == 0) return@withController play(tracks, 0)
        c.addMediaItems(tracks.map { it.toMediaItem() })
    }

    fun jumpTo(index: Int) = withController {
        it.seekTo(index, 0)
        if (it.playbackState == Player.STATE_IDLE) it.prepare()
        it.play()
    }

    fun removeAt(index: Int) = withController { it.removeMediaItem(index) }
    fun move(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }

    /** Everything but the song playing now. */
    fun clearUpcoming() = withController { c ->
        val cur = c.currentMediaItemIndex
        if (cur + 1 < c.mediaItemCount) c.removeMediaItems(cur + 1, c.mediaItemCount)
        if (cur > 0) c.removeMediaItems(0, cur)
    }
    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
}
