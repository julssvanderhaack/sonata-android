package io.github.julssvanderhaack.sonata.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.julssvanderhaack.sonata.R
import io.github.julssvanderhaack.sonata.library.AlbumSort
import io.github.julssvanderhaack.sonata.library.SongSort
import io.github.julssvanderhaack.sonata.library.sectionOf

private enum class Tab(val label: Int) {
    SONGS(R.string.tab_songs), ALBUMS(R.string.tab_albums), ARTISTS(R.string.tab_artists),
    FOLDERS(R.string.tab_folders), GENRES(R.string.tab_genres),
}

private val SongSort.label
    get() = when (this) {
        SongSort.TITLE -> R.string.sort_title
        SongSort.ARTIST -> R.string.sort_artist
        SongSort.ALBUM -> R.string.sort_album
        SongSort.YEAR -> R.string.sort_year
        SongSort.DURATION -> R.string.sort_duration
        SongSort.ADDED -> R.string.sort_added
    }

private val AlbumSort.label
    get() = when (this) {
        AlbumSort.TITLE -> R.string.sort_title
        AlbumSort.ARTIST -> R.string.sort_artist
        AlbumSort.YEAR -> R.string.sort_year
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: MainViewModel) {
    val lib by vm.library.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val albumSort by vm.albumSort.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var tabName by rememberSaveable { mutableStateOf(Tab.SONGS.name) }
    val tabs = Tab.entries.filter { it != Tab.GENRES || lib.showGenres }
    val tab = tabs.firstOrNull { it.name == tabName } ?: Tab.SONGS
    var sortMenu by remember { mutableStateOf(false) }

    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.scanReports.collect { snack.showSnackbar(vm.describe(it)) } }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = {
                    if (searching) SearchField(query) { vm.query.value = it }
                    else Text(stringResource(R.string.app_name))
                },
                navigationIcon = {
                    if (searching) IconButton(onClick = { searching = false; vm.query.value = "" }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.close_search))
                    }
                },
                actions = {
                    if (!searching) IconButton(onClick = { searching = true }) {
                        Icon(Icons.Rounded.Search, stringResource(R.string.search))
                    } else if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.clear))
                    }
                    if (tab == Tab.SONGS || tab == Tab.ALBUMS) Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.sort))
                        }
                        DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                            if (tab == Tab.SONGS) SongSort.entries.forEach { s ->
                                SortItem(stringResource(s.label), s == settings.songSort) { vm.setSongSort(s); sortMenu = false }
                            } else AlbumSort.entries.forEach { s ->
                                SortItem(stringResource(s.label), s == albumSort) { vm.albumSort.value = s; sortMenu = false }
                            }
                        }
                    }
                    // search the music folders again; the snackbar says what changed
                    if (!searching && settings.musicFolders.isNotEmpty()) IconButton(onClick = vm::rescanAndReport, enabled = !scanning) {
                        Icon(Icons.Rounded.Refresh, stringResource(R.string.rescan))
                    }
                    if (!searching) IconButton(onClick = { vm.open(Route.Settings) }) {
                        Icon(Icons.Rounded.Settings, stringResource(R.string.settings))
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOf(tab), edgePadding = 8.dp) {
                tabs.forEach { t ->
                    Tab(selected = t == tab, onClick = { tabName = t.name }, text = { Text(stringResource(t.label)) })
                }
            }
            val progress by vm.scanProgress.collectAsStateWithLifecycle()
            ScanProgress(scanning, progress)
            if (settings.musicFolders.isEmpty()) {
                ChooseFolders(rememberMusicFolderPicker(vm))
                return@Column
            }
            if (lib.loaded && lib.songs.isEmpty() && query.isBlank() && !scanning) {
                EmptyLibrary(lib.totalFiles, onSettings = { vm.open(Route.Settings) }, onRescan = vm::rescan)
                return@Column
            }
            when (tab) {
                Tab.SONGS -> SongList(
                    songs = lib.songs,
                    currentId = now.trackId,
                    onPlay = { vm.player.play(lib.songs, it) },
                    sections = { sectionOf(it, settings.songSort) },
                    header = {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                pluralStringResource(R.plurals.songs, lib.songs.size, lib.songs.size),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            FilledTonalButton(onClick = { vm.player.play(lib.songs, lib.songs.indices.randomOrNull() ?: 0, shuffle = true) }) {
                                Icon(Icons.Rounded.Shuffle, null, Modifier.padding(end = 6.dp))
                                Text(stringResource(R.string.shuffle_all))
                            }
                        }
                    },
                )
                Tab.ALBUMS -> AlbumGrid(lib.albums, { vm.open(Route.AlbumDetail(it.key)) }, 0)
                Tab.ARTISTS -> ArtistList(lib.artists, { vm.open(Route.ArtistDetail(it.key)) }, 0)
                Tab.GENRES -> GenreList(lib.genres, { vm.open(Route.GenreDetail(it.name)) }, 0)
                Tab.FOLDERS -> FolderList(lib.folders, emptyList(), now.trackId, { vm.open(Route.Folder(it.key)) }, {}, 0)
            }
        }
    }
}

@Composable
private fun SortItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = { if (selected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) },
    )
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
}

/** A bar while scanning; while tags are read (a first scan, new songs) also how far it has got. */
@Composable
private fun ScanProgress(scanning: Boolean, progress: Pair<Int, Int>?) {
    if (!scanning) return
    val p = progress?.takeIf { it.second > 0 }
    if (p == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    LinearProgressIndicator(progress = { p.first.toFloat() / p.second }, modifier = Modifier.fillMaxWidth())
    Text(
        stringResource(R.string.scan_reading, p.first, p.second), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** First run: the library is built only from folders the user points at. */
@Composable
private fun ChooseFolders(onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.LibraryMusic, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
        Text(
            stringResource(R.string.choose_title), style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            stringResource(R.string.choose_text), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(onClick = onPick) { Text(stringResource(R.string.music_folder_add)) }
    }
}

@Composable
private fun EmptyLibrary(totalFiles: Int, onSettings: () -> Unit, onRescan: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            if (totalFiles > 0) pluralStringResource(R.plurals.empty_filtered, totalFiles, totalFiles)
            else stringResource(R.string.empty_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRescan) { Text(stringResource(R.string.rescan)) }
            FilledTonalButton(onClick = onSettings) { Text(stringResource(R.string.library_filters)) }
        }
    }
}
