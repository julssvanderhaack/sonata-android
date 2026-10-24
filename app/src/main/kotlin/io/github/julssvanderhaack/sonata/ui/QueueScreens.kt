package io.github.julssvanderhaack.sonata.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.julssvanderhaack.sonata.R
import io.github.julssvanderhaack.sonata.library.PlaylistItem
import io.github.julssvanderhaack.sonata.library.Track
import io.github.julssvanderhaack.sonata.library.TrackIndex
import io.github.julssvanderhaack.sonata.library.artistOrNull
import io.github.julssvanderhaack.sonata.player.QueueEntry
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Lets song rows anywhere offer the long-press menu without passing the view model down every list. */
val LocalVm = staticCompositionLocalOf<MainViewModel?> { null }

/** "Reproducir a continuación", "Añadir a la cola", "Añadir a una lista…" for [songs]. */
@Composable
fun SongActionItems(vm: MainViewModel, songs: List<Track>, onDone: () -> Unit) {
    DropdownMenuItem(
        { Text(stringResource(R.string.play_next)) }, { onDone(); vm.playNext(songs) },
        leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) },
    )
    DropdownMenuItem(
        { Text(stringResource(R.string.enqueue)) }, { onDone(); vm.enqueue(songs) },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
    )
    DropdownMenuItem(
        { Text(stringResource(R.string.add_to_playlist)) }, { onDone(); vm.addingToPlaylist.value = songs },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
    )
}

/** ⋮ in the toolbar of an album, artist, genre or folder: the same actions for all its songs. */
@Composable
fun CollectionMenu(vm: MainViewModel, songs: List<Track>) {
    if (songs.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more)) }
        DropdownMenu(open, { open = false }) { SongActionItems(vm, songs) { open = false } }
    }
}

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { text = it }, singleLine = true, label = { Text(stringResource(R.string.playlist_name)) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Shown from AppRoot while songs are waiting to go into a playlist: pick one or make a new one. */
@Composable
fun AddToPlaylistDialog(vm: MainViewModel) {
    val songs by vm.addingToPlaylist.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val pending = songs ?: return
    var naming by remember { mutableStateOf(false) }
    val close = { vm.addingToPlaylist.value = null }
    if (naming) {
        NameDialog(stringResource(R.string.new_playlist), "", stringResource(R.string.create), onDismiss = { naming = false }) {
            vm.createPlaylist(it, pending)
            close()
        }
        return
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.add_to_playlist).trimEnd('…')) },
        text = {
            LazyColumn {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { naming = true }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.new_playlist), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                items(playlists, key = { it.id }) { p ->
                    Column(Modifier.fillMaxWidth().clickable { vm.addToPlaylist(p, pending); close() }.padding(vertical = 10.dp)) {
                        Text(p.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            pluralStringResource(R.plurals.songs, p.count, p.count), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A row with its list key: the key travels with it when it's dragged, so the list never reads a stale one. */
private data class Keyed<T>(val key: String, val value: T)

/**
 * Stable keys for rows that may repeat (the same song twice in a queue):
 * the song plus how many times it appeared before.
 */
private fun <T> keyed(list: List<T>, id: (T) -> Any?): List<Keyed<T>> {
    val seen = HashMap<Any?, Int>()
    return list.map { e ->
        val n = seen.getOrDefault(id(e), 0)
        seen[id(e)] = n + 1
        Keyed("${id(e)}#$n", e)
    }
}

private fun <T> List<T>.moved(from: Int, to: Int) = toMutableList().apply { add(to, removeAt(from)) }

/** A row of the queue or of a playlist: drag handle, title, artist, remove. */
@Composable
private fun EditableRow(
    title: String, subtitle: String, current: Boolean, dim: Boolean, handle: Modifier?, onClick: (() -> Unit)?, onRemove: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .background(if (current) cs.secondaryContainer else cs.background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (handle != null) IconButton(onClick = {}, modifier = handle) {
            Icon(Icons.Rounded.DragHandle, stringResource(R.string.drag), tint = cs.onSurfaceVariant)
        } else Box(Modifier.size(12.dp))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    current -> cs.onSecondaryContainer
                    dim -> cs.onSurfaceVariant
                    else -> cs.onSurface
                },
            )
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, stringResource(R.string.remove), tint = cs.onSurfaceVariant) }
    }
}

/** The play queue: tap to jump, ✕ to drop, drag to reorder (in play order, so not while shuffling). */
@Composable
fun QueueScreen(vm: MainViewModel) {
    val queue by vm.queue.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    // a local copy so a drag moves at once; the player confirms each step and the flow catches up
    var rows by remember { mutableStateOf(emptyList<Keyed<QueueEntry>>()) }
    LaunchedEffect(queue) { rows = keyed(queue) { it.trackId ?: it.title } }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        rows = rows.moved(from.index, to.index)
        vm.player.move(from.index, to.index)
    }
    // open with the current song in view
    LaunchedEffect(Unit) {
        val i = queue.indexOfFirst { it.current }
        if (i > 2) listState.scrollToItem(i - 2)
    }
    BackScaffold(stringResource(R.string.queue), vm::back, actions = {
        if (queue.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(open, { open = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.queue_save)) }, { open = false; naming = true })
                    DropdownMenuItem({ Text(stringResource(R.string.queue_clear)) }, { open = false; vm.player.clearUpcoming() })
                }
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (queue.isEmpty()) {
                Text(stringResource(R.string.queue_empty), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            if (now.shuffle) Text(
                stringResource(R.string.queue_shuffled), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(rows, key = { it.key }) { row ->
                    val e = row.value
                    ReorderableItem(reorder, key = row.key, enabled = !now.shuffle) {
                        EditableRow(
                            e.title, e.artist ?: unknownArtist(), e.current, dim = false,
                            handle = if (now.shuffle) null else Modifier.draggableHandle(),
                            onClick = { vm.player.jumpTo(e.index) },
                            onRemove = { vm.player.removeAt(e.index) },
                        )
                    }
                }
            }
        }
    }
    if (naming) NameDialog(stringResource(R.string.queue_save), "", stringResource(R.string.save), onDismiss = { naming = false }) {
        vm.createPlaylist(it, vm.queueTracks())
        naming = false
    }
}

/** The Listas tab: "Nueva lista" and the saved playlists. */
@Composable
fun PlaylistsTab(vm: MainViewModel) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().clickable { naming = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.new_playlist), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 14.dp))
            }
            HorizontalDivider()
        }
        if (playlists.isEmpty()) item {
            Text(stringResource(R.string.playlists_empty), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(playlists, key = { it.id }) { p ->
            NavRow(p.name, pluralStringResource(R.plurals.songs, p.count, p.count), { vm.open(Route.PlaylistDetail(p.id)) }) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            }
        }
    }
    if (naming) NameDialog(stringResource(R.string.new_playlist), "", stringResource(R.string.create), onDismiss = { naming = false }) {
        vm.createPlaylist(it)
        naming = false
    }
}

/** One playlist: play / shuffle, reorder by dragging, ✕ to take a song out, rename or delete it. */
@Composable
fun PlaylistScreen(vm: MainViewModel, id: Long) {
    val playlist by vm.playlist(id).collectAsStateWithLifecycle(initialValue = null)
    val items by vm.playlistItems(id).collectAsStateWithLifecycle(initialValue = emptyList())
    val lib by vm.library.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf(emptyList<Keyed<PlaylistItem>>()) }
    LaunchedEffect(items) { rows = keyed(items) { it.trackId } }
    // songs that left the library stay in the list by name, but can't play
    fun trackOf(it: PlaylistItem) = lib.byId[it.trackId] ?: TrackIndex[it.trackId]
    val playable = rows.mapNotNull { trackOf(it.value) }
    val listState = rememberLazyListState()
    fun save(list: List<Keyed<PlaylistItem>>) = vm.setPlaylistItems(id, list.map { it.value })
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        // the header is item 0; saved at every step, not only when the finger lifts
        rows = rows.moved(from.index - 1, to.index - 1)
        save(rows)
    }
    val name = playlist?.name ?: ""
    BackScaffold(name, vm::back, actions = {
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more)) }
            DropdownMenu(open, { open = false }) {
                if (playable.isNotEmpty()) {
                    DropdownMenuItem({ Text(stringResource(R.string.play_next)) }, { open = false; vm.playNext(playable) })
                    DropdownMenuItem({ Text(stringResource(R.string.enqueue)) }, { open = false; vm.enqueue(playable) })
                    HorizontalDivider()
                }
                DropdownMenuItem({ Text(stringResource(R.string.rename)) }, { open = false; renaming = true })
                DropdownMenuItem({ Text(stringResource(R.string.delete_playlist)) }, { open = false; deleting = true })
            }
        }
    }) { pad ->
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.padding(pad).fillMaxSize()) {
            item(key = "header") {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        pluralStringResource(R.plurals.songs, rows.size, rows.size) +
                            " · " + formatDuration(playable.sumOf { it.durationMs }),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (playable.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                        Button(onClick = { vm.player.play(playable, 0) }) {
                            Icon(Icons.Rounded.PlayArrow, null, Modifier.padding(end = 6.dp))
                            Text(stringResource(R.string.play))
                        }
                        FilledTonalButton(onClick = { vm.player.play(playable, playable.indices.random(), shuffle = true) }) {
                            Icon(Icons.Rounded.Shuffle, null, Modifier.padding(end = 6.dp))
                            Text(stringResource(R.string.shuffle))
                        }
                    }
                    if (rows.isEmpty()) Text(
                        stringResource(R.string.playlist_empty), Modifier.padding(top = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(rows, key = { it.key }) { row ->
                val item = row.value
                val t = trackOf(item)
                ReorderableItem(reorder, key = row.key) {
                    EditableRow(
                        t?.title ?: item.title,
                        if (t == null) stringResource(R.string.missing_song) else t.artistOrNull() ?: unknownArtist(),
                        current = t != null && t.id == now.trackId, dim = t == null,
                        handle = Modifier.draggableHandle(),
                        onClick = t?.let { { vm.player.play(playable, playable.indexOf(it).coerceAtLeast(0)) } },
                        onRemove = {
                            rows = rows - row
                            save(rows)
                        },
                    )
                }
            }
        }
    }
    if (renaming) NameDialog(stringResource(R.string.rename), name, stringResource(R.string.save), onDismiss = { renaming = false }) {
        vm.renamePlaylist(id, it)
        renaming = false
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        text = { Text(stringResource(R.string.delete_playlist_confirm, name)) },
        confirmButton = {
            TextButton(onClick = {
                deleting = false
                vm.deletePlaylist(id)
                vm.back()
            }) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
