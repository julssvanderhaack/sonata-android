package io.github.julssvanderhaack.sonata.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.julssvanderhaack.sonata.R
import io.github.julssvanderhaack.sonata.library.Album
import io.github.julssvanderhaack.sonata.library.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackScaffold(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                },
                actions = { actions() },
            )
        },
        content = content,
    )
}

@Composable
private fun PlayButtons(songs: List<Track>, vm: MainViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
        Button(onClick = { vm.player.play(songs, 0) }) {
            Icon(Icons.Rounded.PlayArrow, null, Modifier.padding(end = 6.dp))
            Text(stringResource(R.string.play))
        }
        FilledTonalButton(onClick = { vm.player.play(songs, songs.indices.randomOrNull() ?: 0, shuffle = true) }) {
            Icon(Icons.Rounded.Shuffle, null, Modifier.padding(end = 6.dp))
            Text(stringResource(R.string.shuffle))
        }
    }
}

@Composable
private fun Missing(onBack: () -> Unit) = BackScaffold("", onBack) { pad ->
    Text(stringResource(R.string.not_found), Modifier.padding(pad).padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun AlbumScreen(vm: MainViewModel, key: String) {
    val lib by vm.library.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    val album = lib.albums.firstOrNull { it.key == key } ?: return Missing(vm::back)
    BackScaffold(album.title, vm::back, actions = { CollectionMenu(vm, album.tracks) }) { pad ->
        Column(Modifier.padding(pad)) {
            SongList(
                songs = album.tracks,
                currentId = now.trackId,
                onPlay = { vm.player.play(album.tracks, it) },
                numbered = true,
                header = {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Art(album.artTrackId, 128.dp, corner = 10.dp)
                            Column(Modifier.padding(start = 16.dp)) {
                                Text(album.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text(albumArtistLabel(album), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    listOfNotNull(
                                        album.year.takeIf { it > 0 }?.toString(),
                                        pluralStringResource(R.plurals.songs, album.tracks.size, album.tracks.size),
                                        formatDuration(album.tracks.sumOf { it.durationMs }),
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        PlayButtons(album.tracks, vm)
                    }
                },
            )
        }
    }
}

@Composable
fun ArtistScreen(vm: MainViewModel, key: String) {
    val lib by vm.library.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    val artist = lib.artists.firstOrNull { it.key == key } ?: return Missing(vm::back)
    BackScaffold(artist.name ?: unknownArtist(), vm::back, actions = { CollectionMenu(vm, artist.tracks) }) { pad ->
        Column(Modifier.padding(pad)) {
            SongList(
                songs = artist.tracks,
                currentId = now.trackId,
                onPlay = { vm.player.play(artist.tracks, it) },
                header = {
                    Column(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 16.dp)) { PlayButtons(artist.tracks, vm) }
                        if (artist.albums.isNotEmpty()) {
                            Text(
                                stringResource(R.string.tab_albums), style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                            )
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(artist.albums, key = { it.key }) { a -> AlbumCard(a) { vm.open(Route.AlbumDetail(a.key)) } }
                            }
                        }
                        Text(
                            stringResource(R.string.tab_songs), style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun AlbumCard(a: Album, onClick: () -> Unit) {
    Column(Modifier.width(120.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Art(a.artTrackId, 120.dp, corner = 8.dp)
        Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        if (a.year > 0) Text(a.year.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun GenreScreen(vm: MainViewModel, name: String) {
    val lib by vm.library.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    val genre = lib.genres.firstOrNull { it.name == name } ?: return Missing(vm::back)
    BackScaffold(genre.name, vm::back, actions = { CollectionMenu(vm, genre.tracks) }) { pad ->
        Column(Modifier.padding(pad)) {
            SongList(
                genre.tracks, now.trackId, { vm.player.play(genre.tracks, it) },
                header = { Column(Modifier.padding(horizontal = 16.dp)) { PlayButtons(genre.tracks, vm) } },
            )
        }
    }
}

@Composable
fun FolderScreen(vm: MainViewModel, key: String) {
    val lib by vm.library.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    val node = lib.findFolder(key) ?: return Missing(vm::back)
    BackScaffold(node.name, vm::back, actions = { CollectionMenu(vm, node.tracks) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            FolderList(node.children, node.tracks, now.trackId, { vm.open(Route.Folder(it.key)) }, { vm.player.play(node.tracks, it) }, 0)
        }
    }
}
