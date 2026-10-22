package io.github.julssvanderhaack.sonata.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.julssvanderhaack.sonata.library.Scanner
import io.github.julssvanderhaack.sonata.library.Track
import io.github.julssvanderhaack.sonata.library.albumOrFolder
import io.github.julssvanderhaack.sonata.library.artistOrNull
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

/** UI-side handle on [PlaybackService]: a MediaController plus its state as a flow. */
class PlayerConnection(private val context: Context) {
    private var controller: MediaController? = null
    private var connecting = false
    private val pending = ArrayList<(MediaController) -> Unit>()
    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
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

    fun play(queue: List<Track>, start: Int, shuffle: Boolean = false) = withController { c ->
        c.setMediaItems(queue.map(::toItem), start.coerceIn(0, (queue.size - 1).coerceAtLeast(0)), 0)
        c.shuffleModeEnabled = shuffle
        c.prepare()
        c.play()
    }

    fun toggle() = withController { if (it.isPlaying) it.pause() else it.play() }
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

    private fun toItem(t: Track) = MediaItem.Builder()
        .setMediaId(t.id.toString())
        .setUri(Scanner.uriOf(t.id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artistOrNull())
                .setAlbumTitle(t.albumOrFolder())
                .setAlbumArtist(t.albumArtist)
                .setTrackNumber(t.trackNo.takeIf { it > 0 })
                .build(),
        )
        .build()
}
