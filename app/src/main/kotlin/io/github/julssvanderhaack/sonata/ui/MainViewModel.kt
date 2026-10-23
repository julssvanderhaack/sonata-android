package io.github.julssvanderhaack.sonata.ui

import android.app.Application
import androidx.core.net.toUri
import android.provider.DocumentsContract
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.julssvanderhaack.sonata.R
import io.github.julssvanderhaack.sonata.SonataApp
import io.github.julssvanderhaack.sonata.eq.EqBus
import io.github.julssvanderhaack.sonata.eq.EqSettings
import io.github.julssvanderhaack.sonata.library.Album
import io.github.julssvanderhaack.sonata.library.AlbumSort
import io.github.julssvanderhaack.sonata.library.Artist
import io.github.julssvanderhaack.sonata.library.FolderNode
import io.github.julssvanderhaack.sonata.library.Genre
import io.github.julssvanderhaack.sonata.library.LibraryFilter
import io.github.julssvanderhaack.sonata.library.SongSort
import io.github.julssvanderhaack.sonata.library.Track
import io.github.julssvanderhaack.sonata.library.folderTree
import io.github.julssvanderhaack.sonata.library.foldText
import io.github.julssvanderhaack.sonata.library.genresWorthShowing
import io.github.julssvanderhaack.sonata.library.groupAlbums
import io.github.julssvanderhaack.sonata.library.groupArtists
import io.github.julssvanderhaack.sonata.library.groupGenres
import io.github.julssvanderhaack.sonata.library.matchesQuery
import io.github.julssvanderhaack.sonata.library.songComparator
import io.github.julssvanderhaack.sonata.library.Scanner
import io.github.julssvanderhaack.sonata.library.TrackIndex
import io.github.julssvanderhaack.sonata.lyrics.LyricsState
import io.github.julssvanderhaack.sonata.settings.AppSettings
import io.github.julssvanderhaack.sonata.theme.Palette
import io.github.julssvanderhaack.sonata.theme.ResolvedPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Route {
    data object Library : Route
    data class AlbumDetail(val key: String) : Route
    data class ArtistDetail(val key: String) : Route
    data class GenreDetail(val name: String) : Route
    data class Folder(val key: String) : Route
    data object NowPlaying : Route
    data object Settings : Route
    data object Folders : Route
    data object Themes : Route
    /** [base] is the theme to start from; [editing] = overwrite it instead of saving a copy. */
    data class ThemeEditor(val base: String, val editing: Boolean) : Route
    data object Equalizer : Route
}

data class LibraryView(
    val loaded: Boolean = false,
    /** Audio files found in the music folders, before filtering. */
    val totalFiles: Int = 0,
    val songs: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val showGenres: Boolean = false,
    val folders: List<FolderNode> = emptyList(),
    /** Tree for the Carpetas filter screen: everything except the folder exclusions themselves. */
    val folderChoices: List<FolderNode> = emptyList(),
    /** Every track found, filtered or not: the queue may outlive a filter change. */
    val byId: Map<Long, Track> = emptyMap(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val sonata = app as SonataApp
    val player = sonata.player
    val themes = sonata.themes
    val scanning = sonata.scanning

    val stack = mutableStateListOf<Route>(Route.Library)
    fun open(r: Route) { stack += r }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false

    val settings: StateFlow<AppSettings> = sonata.settings.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val palette: StateFlow<ResolvedPalette> = combine(themes.themes, settings) { list, s ->
        (list.firstOrNull { it.name == s.theme } ?: list.firstOrNull { it.name == AppSettings.DEFAULT_THEME })
            ?.resolve() ?: Palette("", emptyMap()).resolve()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Palette("", emptyMap()).resolve())

    val query = MutableStateFlow("")
    val albumSort = MutableStateFlow(AlbumSort.ARTIST)

    private val app get() = getApplication<Application>()
    private fun volumeLabel(v: String) =
        if (v == "external_primary" || v == "external") app.getString(R.string.volume_internal)
        else app.getString(R.string.volume_sd, v.uppercase())

    val library: StateFlow<LibraryView> =
        combine(sonata.db.tracks().all(), settings, query, albumSort) { all, s, q, aSort ->
            val f = s.filter
            val keep = f.compile()
            val songs = all.filter(keep)
            val folded = foldText(q.trim())
            val shown = if (folded.isEmpty()) songs else songs.filter { matchesQuery(it, folded) }
            val keepIgnoringFolders = f.copy(excludedFolders = emptySet()).compile()
            LibraryView(
                loaded = true,
                totalFiles = all.size,
                songs = shown.sortedWith(songComparator(s.songSort)),
                albums = groupAlbums(shown, aSort),
                artists = groupArtists(shown),
                genres = groupGenres(shown),
                showGenres = genresWorthShowing(songs),
                folders = folderTree(shown, ::volumeLabel),
                folderChoices = folderTree(all.filter(keepIgnoringFolders), ::volumeLabel),
                byId = all.associateBy { it.id },
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryView())

    val nowPlaying = player.state

    // --- lyrics ---------------------------------------------------------------

    /** What the Sonando screen shows: the cover, the lyrics or the spectrum. */
    val pane = MutableStateFlow(NowPane.Cover)
    /** Seconds added to the song position, ±0.5 s per tap; resets with each song like on desktop. */
    val lyricsOffset = MutableStateFlow(0.0)
    private val lyricsReload = MutableStateFlow(0L to 0) // (trackId, counter): skip the cache once

    @OptIn(ExperimentalCoroutinesApi::class)
    val lyrics: StateFlow<LyricsState> =
        combine(
            nowPlaying.map { it.trackId }.distinctUntilChanged(),
            library.map { it.byId }.distinctUntilChanged(),
            settings.map { it.onlineLyrics to it.lyricsTree }.distinctUntilChanged(),
            lyricsReload,
        ) { id, byId, (online, tree), reload -> Triple(id?.let { byId[it] ?: TrackIndex.opened[it] }, online to tree, reload) }
            .distinctUntilChanged()
            .flatMapLatest { (track, opts, reload) ->
                if (track == null) flowOf(LyricsState.Idle)
                else sonata.lyrics.load(track, opts.first, opts.second, skipCache = reload.first == track.id && reload.second > 0)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LyricsState.Idle)

    init {
        viewModelScope.launch { nowPlaying.map { it.trackId }.distinctUntilChanged().collect { lyricsOffset.value = 0.0 } }
    }

    fun reloadLyrics() {
        val id = nowPlaying.value.trackId ?: return
        lyricsReload.value = id to lyricsReload.value.second + 1
    }

    fun setOnlineLyrics(on: Boolean) = viewModelScope.launch { sonata.settings.setOnlineLyrics(on) }
    fun setLyricsTree(uri: String?) = viewModelScope.launch { sonata.settings.setLyricsTree(uri) }

    // --- equaliser ------------------------------------------------------------

    val eq = EqBus.settings
    fun updateEq(block: (EqSettings) -> EqSettings) { EqBus.settings.value = block(EqBus.settings.value) }

    fun startLibrary() = sonata.startLibrary()
    fun rescanIfStale() = sonata.rescanIfStale()
    val scanProgress = sonata.scanner.progress

    /** Keeps read access to [tree] across reboots and adds it to the folders the library is built from. */
    fun addMusicFolder(tree: Uri) = viewModelScope.launch {
        runCatching { app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val now = settings.value.musicFolders
        if (tree.toString() !in now) sonata.settings.setMusicFolders(now + tree.toString())
    }

    fun removeMusicFolder(tree: String) = viewModelScope.launch {
        sonata.settings.setMusicFolders(settings.value.musicFolders - tree)
        // the lyrics folder may be the same tree: keep the grant while it's still used
        if (settings.value.lyricsTree != tree) runCatching {
            app.contentResolver.releasePersistableUriPermission(tree.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** "Almacenamiento interno/Music" for a picked tree. */
    fun folderLabel(tree: String): String {
        val doc = runCatching { DocumentsContract.getTreeDocumentId(tree.toUri()) }.getOrNull() ?: return tree
        val (volume, rel) = Scanner.locate(doc)
        return listOf(volumeLabel(volume), rel.trimEnd('/')).filter { it.isNotEmpty() }.joinToString("/")
    }
    fun rescan() = sonata.rescan()

    /** Result of the last scan asked for with the button, shown under it. */
    val lastScan = MutableStateFlow<Scanner.Result?>(null)

    /** One message per finished scan asked for by the user, for the library's snackbar. */
    val scanReports = MutableSharedFlow<Scanner.Result>(extraBufferCapacity = 1)

    fun rescanAndReport() = viewModelScope.launch {
        val r = sonata.scanNow()
        lastScan.value = r
        scanReports.tryEmit(r)
    }

    fun describe(r: Scanner.Result): String =
        if (r.added + r.updated + r.removed == 0) app.getString(R.string.rescan_nothing)
        else app.getString(R.string.rescan_result, r.added, r.updated, r.removed)

    /** "Abrir con Sonata" from a file manager: play that file now and show it. */
    fun openExternal(uri: Uri) = viewModelScope.launch {
        val t = sonata.scanner.readOne(uri)
        TrackIndex.opened = TrackIndex.opened + (t.id to t)
        player.play(listOf(t), 0)
        if (stack.lastOrNull() != Route.NowPlaying) open(Route.NowPlaying)
    }

    fun setSongSort(s: SongSort) = viewModelScope.launch { sonata.settings.setSongSort(s) }
    fun setTheme(name: String) = viewModelScope.launch { sonata.settings.setTheme(name) }
    fun setDynamicColor(on: Boolean) = viewModelScope.launch { sonata.settings.setDynamicColor(on) }
    fun setFilter(f: LibraryFilter) = viewModelScope.launch { sonata.settings.setFilter(f) }
    fun updateFilter(block: (LibraryFilter) -> LibraryFilter) = setFilter(block(settings.value.filter))
    fun resetFilter() = viewModelScope.launch { sonata.settings.resetFilter() }

    fun saveTheme(p: Palette, replacing: String?) = viewModelScope.launch {
        val name = themes.save(p, replacing)
        sonata.settings.setTheme(name)
    }

    fun deleteTheme(name: String) = viewModelScope.launch {
        themes.delete(name)
        if (settings.value.theme == name) sonata.settings.setTheme(AppSettings.DEFAULT_THEME)
    }

    fun importTheme(uri: Uri, fileName: String?, onDone: (String?) -> Unit) = viewModelScope.launch {
        val base = fileName?.substringBeforeLast('.')?.takeUnless { it.isBlank() || it == "colors" } ?: "importado"
        onDone(themes.import(uri, base))
    }

    fun exportTheme(p: Palette, uri: Uri) = viewModelScope.launch { themes.export(p, uri) }
}

fun LibraryView.findFolder(key: String): FolderNode? {
    fun walk(ns: List<FolderNode>): FolderNode? {
        for (n in ns) {
            if (n.key == key) return n
            walk(n.children)?.let { return it }
        }
        return null
    }
    return walk(folders)
}

enum class NowPane { Cover, Lyrics, Spectrum }
