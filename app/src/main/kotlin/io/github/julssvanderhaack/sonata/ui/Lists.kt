package io.github.julssvanderhaack.sonata.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.julssvanderhaack.sonata.R
import io.github.julssvanderhaack.sonata.library.Album
import io.github.julssvanderhaack.sonata.library.Artist
import io.github.julssvanderhaack.sonata.library.FolderNode
import io.github.julssvanderhaack.sonata.library.Genre
import io.github.julssvanderhaack.sonata.library.Track
import io.github.julssvanderhaack.sonata.library.albumOrFolder
import io.github.julssvanderhaack.sonata.library.artistOrNull
import kotlinx.coroutines.launch

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun unknownArtist() = stringResource(R.string.unknown_artist)

@Composable
fun albumArtistLabel(a: Album) = when {
    a.various -> stringResource(R.string.various_artists)
    else -> a.artist ?: unknownArtist()
}

@Composable
fun SongRow(t: Track, current: Boolean, onClick: () -> Unit, showArt: Boolean = true, number: Int? = null, endPad: Int = 16) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (current) cs.secondaryContainer else cs.background)
            .padding(start = 16.dp, end = endPad.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            showArt -> Art(t.id, 44.dp)
            number != null -> Text(
                if (number > 0) number.toString() else "–",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                modifier = Modifier.width(28.dp),
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = if (current) cs.onSecondaryContainer else cs.onSurface,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                listOf(t.artistOrNull() ?: unknownArtist(), t.albumOrFolder()).joinToString(" · "),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = if (current) cs.onSecondaryContainer.copy(alpha = 0.75f) else cs.onSurfaceVariant,
            )
        }
        Text(
            formatDuration(t.durationMs), style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * Song list with a letter strip on the right when [sections] is given
 * (alphabetical sorts); dragging over it jumps to the first song of that letter.
 */
@Composable
fun SongList(
    songs: List<Track>,
    currentId: Long?,
    onPlay: (Int) -> Unit,
    sections: ((Track) -> String?)? = null,
    header: (@Composable () -> Unit)? = null,
    state: LazyListState = rememberLazyListState(),
    numbered: Boolean = false,
    bottomPadding: Int = 0,
) {
    val index = remember(songs, sections) {
        if (sections == null) emptyList()
        else buildList {
            var last: String? = null
            songs.forEachIndexed { i, t ->
                val s = sections(t)
                if (s != null && s != last) {
                    add(s to i)
                    last = s
                }
            }
        }
    }
    val offset = if (header != null) 1 else 0
    val strip = index.size > 3
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, contentPadding = PaddingValues(bottom = bottomPadding.dp), modifier = Modifier.fillMaxSize()) {
            if (header != null) item(key = "header") { header() }
            itemsIndexed(songs, key = { _, t -> t.id }) { i, t ->
                SongRow(
                    t, t.id == currentId, onClick = { onPlay(i) }, showArt = !numbered,
                    number = if (numbered) t.trackNo else null, endPad = if (strip) 28 else 16,
                )
            }
        }
        if (strip) {
            LetterStrip(
                letters = index.map { it.first },
                onPick = { i -> index.getOrNull(i)?.let { (_, pos) -> pos + offset } },
                state = state,
                modifier = Modifier.align(Alignment.CenterEnd).padding(bottom = bottomPadding.dp),
            )
        }
    }
}

@Composable
private fun LetterStrip(letters: List<String>, onPick: (Int) -> Int?, state: LazyListState, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf<Int?>(null) }
    var height by remember { mutableStateOf(1) }
    fun pick(y: Float) {
        val i = (y / height * letters.size).toInt().coerceIn(0, letters.lastIndex)
        active = i
        onPick(i)?.let { scope.launch { state.scrollToItem(it) } }
    }
    Column(
        modifier
            .fillMaxHeight()
            .width(22.dp)
            .padding(vertical = 8.dp)
            .pointerInput(letters) {
                height = size.height.coerceAtLeast(1)
                detectTapGestures(onPress = { pick(it.y); tryAwaitRelease(); active = null })
            }
            .pointerInput(letters) {
                detectVerticalDragGestures(
                    onDragStart = { pick(it.y) },
                    onDragEnd = { active = null },
                    onDragCancel = { active = null },
                ) { change, _ -> pick(change.position.y) }
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEachIndexed { i, l ->
            Text(
                l, fontSize = 10.sp, lineHeight = 10.sp,
                color = if (active == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (active == i) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
fun AlbumGrid(albums: List<Album>, onOpen: (Album) -> Unit, bottomPadding: Int) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = (12 + bottomPadding).dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.key }) { a ->
            Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onOpen(a) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    Art(a.artTrackId, 170.dp, Modifier.fillMaxSize(), corner = 8.dp)
                }
                Text(
                    a.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    albumArtistLabel(a), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun NavRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
}

@Composable
fun ArtistList(artists: List<Artist>, onOpen: (Artist) -> Unit, bottomPadding: Int) {
    LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding.dp), modifier = Modifier.fillMaxSize()) {
        itemsIndexed(artists, key = { _, a -> a.key }) { _, a ->
            NavRow(
                title = a.name ?: unknownArtist(),
                subtitle = pluralStringResource(R.plurals.albums, a.albums.size, a.albums.size) + " · " +
                    pluralStringResource(R.plurals.songs, a.tracks.size, a.tracks.size),
                onClick = { onOpen(a) },
            ) {
                val art = a.albums.firstOrNull()?.artTrackId
                if (art != null) Art(art, 44.dp, corner = 22.dp) else RoundIcon(Icons.Rounded.Person)
            }
        }
    }
}

@Composable
fun GenreList(genres: List<Genre>, onOpen: (Genre) -> Unit, bottomPadding: Int) {
    LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding.dp), modifier = Modifier.fillMaxSize()) {
        itemsIndexed(genres, key = { _, g -> g.name }) { _, g ->
            NavRow(g.name, pluralStringResource(R.plurals.songs, g.tracks.size, g.tracks.size), { onOpen(g) }) {
                RoundIcon(Icons.Rounded.GraphicEq)
            }
        }
    }
}

/** Subfolders first, then the folder's own songs. */
@Composable
fun FolderList(
    folders: List<FolderNode>,
    songs: List<Track>,
    currentId: Long?,
    onOpen: (FolderNode) -> Unit,
    onPlay: (Int) -> Unit,
    bottomPadding: Int,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding.dp), modifier = Modifier.fillMaxSize()) {
        itemsIndexed(folders, key = { _, f -> "f:" + f.key }) { _, f ->
            NavRow(f.name, pluralStringResource(R.plurals.songs, f.total, f.total), { onOpen(f) }) {
                RoundIcon(Icons.Rounded.Folder)
            }
        }
        itemsIndexed(songs, key = { _, t -> t.id }) { i, t ->
            SongRow(t, t.id == currentId, onClick = { onPlay(i) })
        }
        item { Spacer(Modifier.size(1.dp)) }
    }
}
