package io.github.julssvanderhaack.sonata.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.julssvanderhaack.sonata.MainActivity
import io.github.julssvanderhaack.sonata.R
import io.github.julssvanderhaack.sonata.SonataApp
import io.github.julssvanderhaack.sonata.eq.EqProcessor
import io.github.julssvanderhaack.sonata.library.AlbumSort
import io.github.julssvanderhaack.sonata.library.Track
import io.github.julssvanderhaack.sonata.library.groupAlbums
import io.github.julssvanderhaack.sonata.library.groupArtists
import io.github.julssvanderhaack.sonata.library.songComparator
import io.github.julssvanderhaack.sonata.library.SongSort
import io.github.julssvanderhaack.sonata.spectrum.SpectrumSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the player so music keeps going with the screen off. Media3 provides
 * the notification, lock-screen and Bluetooth/headset controls from the session.
 *
 * It is a library service so the system can find it with the app closed: the
 * "resume" card in the media controls, a headset's play button and Android
 * Auto connect to it, get the saved queue back (onPlaybackResumption) and can
 * browse playlists, albums, artists and songs.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private lateinit var queue: QueueStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val sonata get() = application as SonataApp

    /** While playing, the position is saved every few seconds so a killed app resumes close to where it was. */
    private val savePosition = object : Runnable {
        override fun run() {
            session?.player?.let { p ->
                saveNow(p)
                if (p.isPlaying) handler.postDelayed(this, 5_000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        queue = QueueStore(filesDir)
        // the equaliser runs as an audio processor inside ExoPlayer's sink; the spectrum listens at its door
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                SpectrumSink(
                    DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(arrayOf(EqProcessor()))
                        .build(),
                )
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        // back where it was, paused: the mini player shows the last song and play carries on from there
        queue.load()?.let { q ->
            player.setMediaItems(q.items, q.index, q.positionMs)
            player.shuffleModeEnabled = q.shuffle
            player.repeatMode = q.repeat
            player.prepare()
        }
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_POSITION_DISCONTINUITY,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_IS_PLAYING_CHANGED,
                    )
                ) saveNow(p)
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && p.isPlaying) {
                    handler.removeCallbacks(savePosition)
                    handler.postDelayed(savePosition, 5_000)
                }
            }
        })
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).setSessionActivity(open).build()
    }

    private fun saveNow(p: Player) {
        runCatching { queue.save(p) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        p?.let(::saveNow)
        // swiping the app away while paused shouldn't leave a dead notification behind
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(savePosition)
        session?.run {
            saveNow(player)
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    // ---- what the system and other controllers see -----------------------

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            // "recent" is the system asking what to offer on its resume card
            Futures.immediateFuture(LibraryResult.ofItem(folder(if (params?.isRecent == true) RECENT else ROOT, getString(R.string.app_name)), params))

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String) =
            async { mediaId.toLongOrNull()?.let { tracksById()[it] }?.let { LibraryResult.ofItem(it.toMediaItem(), null) } ?: notFound() }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?,
        ) = async {
            val items = children(parentId) ?: return@async notFound()
            val from = (page * pageSize).coerceAtMost(items.size)
            LibraryResult.ofItemList(items.subList(from, (from + pageSize).coerceAtMost(items.size)), params)
        }

        /** Items from the browser arrive with just an id; the app's own controller already sends playable ones. */
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            if (mediaItems.all { it.localConfiguration != null }) return Futures.immediateFuture(mediaItems)
            val f = SettableFuture.create<MutableList<MediaItem>>()
            scope.launch {
                val byId = tracksById()
                f.set(mediaItems.mapNotNull { item -> if (item.localConfiguration != null) item else item.mediaId.toLongOrNull()?.let { byId[it] }?.toMediaItem() }.toMutableList())
            }
            return f
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val q = queue.load() ?: return Futures.immediateFailedFuture(UnsupportedOperationException("no saved queue"))
            mediaSession.player.shuffleModeEnabled = q.shuffle
            mediaSession.player.repeatMode = q.repeat
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(q.items, q.index, q.positionMs))
        }
    }

    private fun <T> notFound() = LibraryResult.ofError<T>(SessionError.ERROR_BAD_VALUE)

    private fun <T : Any> async(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
        val f = SettableFuture.create<LibraryResult<T>>()
        scope.launch { f.set(runCatching { block() }.getOrElse { LibraryResult.ofError(SessionError.ERROR_UNKNOWN) }) }
        return f
    }

    private suspend fun tracksById(): Map<Long, Track> = withContext(Dispatchers.IO) { sonata.visibleTracks().associateBy { it.id } }

    /** Root → Listas / Álbumes / Artistas / Canciones, then down to playable songs. */
    private suspend fun children(parentId: String): List<MediaItem>? = withContext(Dispatchers.IO) {
        val tracks = sonata.visibleTracks()
        when {
            parentId == ROOT -> listOf(
                folder(PLAYLISTS, getString(R.string.tab_playlists)),
                folder(ALBUMS, getString(R.string.tab_albums)),
                folder(ARTISTS, getString(R.string.tab_artists)),
                folder(SONGS, getString(R.string.tab_songs)),
            )
            parentId == RECENT -> queue.load()?.let { q -> listOf(q.items[q.index]) } ?: emptyList()
            parentId == SONGS -> tracks.sortedWith(songComparator(SongSort.TITLE)).map { it.toMediaItem() }
            parentId == ALBUMS -> groupAlbums(tracks, AlbumSort.ARTIST).map { folder("$ALBUM${it.key}", it.title, it.artist) }
            parentId == ARTISTS -> groupArtists(tracks).map { folder("$ARTIST${it.key}", it.name ?: getString(R.string.unknown_artist)) }
            parentId == PLAYLISTS -> sonata.playlists.playlists().allNow().map { folder("$PLAYLIST${it.id}", it.name) }
            parentId.startsWith(ALBUM) -> groupAlbums(tracks).firstOrNull { it.key == parentId.removePrefix(ALBUM) }?.tracks?.map { it.toMediaItem() }
            parentId.startsWith(ARTIST) -> groupArtists(tracks).firstOrNull { it.key == parentId.removePrefix(ARTIST) }?.tracks?.map { it.toMediaItem() }
            parentId.startsWith(PLAYLIST) -> {
                val byId = tracks.associateBy { it.id }
                sonata.playlists.playlists().itemsNow(parentId.removePrefix(PLAYLIST).toLongOrNull() ?: -1)
                    .mapNotNull { byId[it.trackId]?.toMediaItem() }
            }
            else -> null
        }
    }

    private fun folder(id: String, title: String, subtitle: String? = null) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    private companion object {
        const val ROOT = "root"
        const val RECENT = "recent"
        const val SONGS = "songs"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
        const val PLAYLISTS = "playlists"
        const val ALBUM = "album:"
        const val ARTIST = "artist:"
        const val PLAYLIST = "playlist:"
    }
}
