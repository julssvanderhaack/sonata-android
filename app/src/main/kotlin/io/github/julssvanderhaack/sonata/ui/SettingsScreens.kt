package io.github.julssvanderhaack.sonata.ui

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.julssvanderhaack.sonata.R
import io.github.julssvanderhaack.sonata.library.FolderNode
import io.github.julssvanderhaack.sonata.library.LibraryFilter
import io.github.julssvanderhaack.sonata.theme.ThemeRepository
import kotlin.math.roundToInt

@Composable
fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Slider that only commits on release, so dragging doesn't refilter the library on every pixel. */
@Composable
private fun StepSlider(title: String, value: Int, steps: List<Int>, label: (Int) -> String, onChange: (Int) -> Unit) {
    var pos by remember(value) { mutableFloatStateOf(steps.indexOfFirst { it >= value }.coerceAtLeast(0).toFloat()) }
    val shown = steps[pos.roundToInt().coerceIn(0, steps.lastIndex)]
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(label(shown), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = pos,
            onValueChange = { pos = it },
            onValueChangeFinished = { onChange(shown) },
            valueRange = 0f..steps.lastIndex.toFloat(),
            steps = (steps.size - 2).coerceAtLeast(0),
        )
    }
}

private val DURATION_STEPS = listOf(0, 1, 3, 5, 10, 15, 20, 30, 45, 60, 90, 120)
private val SIZE_STEPS = listOf(0, 10, 50, 100, 200, 300, 500, 1000, 2000)

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val lib by vm.library.collectAsStateWithLifecycle()
    val f = s.filter
    val eqOn = vm.eq.collectAsStateWithLifecycle().value.enabled
    BackScaffold(stringResource(R.string.settings), vm::back) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                SectionTitle(stringResource(R.string.appearance))
                LinkRow(stringResource(R.string.theme), ThemeRepository.displayName(s.theme)) { vm.open(Route.Themes) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SwitchRow(stringResource(R.string.dynamic_color), stringResource(R.string.dynamic_color_hint), s.dynamicColor, vm::setDynamicColor)
                }

                SectionTitle(stringResource(R.string.lyrics_section))
                SwitchRow(stringResource(R.string.lyrics_online), stringResource(R.string.lyrics_online_note), s.onlineLyrics, vm::setOnlineLyrics)
                LyricsFolderRow(s.lyricsTree, vm)
                LinkRow(stringResource(R.string.equalizer), stringResource(if (eqOn) R.string.on else R.string.off)) { vm.open(Route.Equalizer) }

                SectionTitle(stringResource(R.string.library))
                Text(
                    pluralStringResource(R.plurals.library_count, lib.totalFiles, lib.songs.size, lib.totalFiles),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                MusicFoldersRows(vm, s.musicFolders)
                RescanRow(vm)
                StepSlider(stringResource(R.string.min_duration), f.minDurationSec, DURATION_STEPS, { if (it == 0) "—" else "$it s" }) { v ->
                    vm.updateFilter { it.copy(minDurationSec = v) }
                }
                StepSlider(stringResource(R.string.min_size), f.minSizeKb, SIZE_STEPS, { if (it == 0) "—" else if (it >= 1000) "${it / 1000} MB" else "$it KB" }) { v ->
                    vm.updateFilter { it.copy(minSizeKb = v) }
                }
                LinkRow(
                    stringResource(R.string.folders),
                    if (f.excludedFolders.isEmpty()) stringResource(R.string.folders_all)
                    else pluralStringResource(R.plurals.folders_hidden, f.excludedFolders.size, f.excludedFolders.size),
                ) { vm.open(Route.Folders) }

                SectionTitle(stringResource(R.string.patterns))
                Text(
                    stringResource(R.string.patterns_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            patternEditor(f) { vm.setFilter(it) }
            item {
                HorizontalDivider(Modifier.padding(top = 16.dp))
                Row(Modifier.padding(16.dp)) {
                    TextButton(onClick = { vm.resetFilter() }) { Text(stringResource(R.string.reset_filters)) }
                }
            }
        }
    }
}

/** Folder (usually Music) whose .lrc files Sonata may read; the audio permission alone doesn't cover them. */
@Composable
private fun LyricsFolderRow(tree: String?, vm: MainViewModel) {
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            vm.setLyricsTree(uri.toString())
        }
    }
    val label = tree?.let { runCatching { DocumentsContract.getTreeDocumentId(it.toUri()) }.getOrNull() }
        ?.replace("primary:", stringResource(R.string.volume_internal) + "/")
    Row(
        Modifier.fillMaxWidth().clickable { pick.launch(null) }.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.lyrics_folder), style = MaterialTheme.typography.bodyLarge)
            Text(
                label ?: stringResource(R.string.lyrics_folder_none), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (tree != null) IconButton(onClick = {
            runCatching { context.contentResolver.releasePersistableUriPermission(tree.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.setLyricsTree(null)
        }) { Icon(Icons.Rounded.Close, stringResource(R.string.remove)) }
    }
}

private fun LazyListScope.patternEditor(f: LibraryFilter, onChange: (LibraryFilter) -> Unit) {
    items(f.excludePatterns, key = { "p:$it" }) { p ->
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(p, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { onChange(f.copy(excludePatterns = f.excludePatterns - p)) }) {
                Icon(Icons.Rounded.Close, stringResource(R.string.remove))
            }
        }
    }
    item(key = "add-pattern") {
        var text by rememberSaveable { mutableStateOf("") }
        val add = {
            val t = text.trim()
            if (t.isNotEmpty() && t !in f.excludePatterns) onChange(f.copy(excludePatterns = f.excludePatterns + t))
            text = ""
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { text = it }, singleLine = true,
                placeholder = { Text("*Voice Notes*", fontFamily = FontFamily.Monospace) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = add) { Icon(Icons.Rounded.Add, stringResource(R.string.add)) }
        }
    }
}

/**
 * Folder tree with a checkbox per folder. Unchecking hides it and all its
 * subfolders; a folder under a hidden one can't be re-enabled on its own.
 */
@Composable
fun FoldersScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val lib by vm.library.collectAsStateWithLifecycle()
    val excluded = s.filter.excludedFolders
    val expanded = rememberSaveable(saver = listSaver()) { mutableListOf<String>().toMutableStateList() }
    fun toggle(key: String, include: Boolean) = vm.updateFilter { f ->
        f.copy(excludedFolders = if (include) f.excludedFolders - key else f.excludedFolders.filterNot { LibraryFilter.hides(key, it) }.toSet() + key)
    }
    val rows = remember(lib.folderChoices, expanded.toList()) {
        buildList {
            fun walk(ns: List<FolderNode>, depth: Int) {
                for (n in ns) {
                    add(n to depth)
                    if (n.key in expanded) walk(n.children, depth + 1)
                }
            }
            walk(lib.folderChoices, 0)
        }
    }
    BackScaffold(stringResource(R.string.folders), vm::back) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Text(
                    stringResource(R.string.folders_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                )
            }
            items(rows, key = { it.first.key }) { (n, depth) ->
                val hiddenByParent = excluded.any { it != n.key && LibraryFilter.hides(it, n.key) }
                val included = excluded.none { LibraryFilter.hides(it, n.key) }
                Row(
                    Modifier.fillMaxWidth().padding(start = (8 + depth * 20).dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (n.children.isNotEmpty()) {
                        IconButton(onClick = { if (n.key in expanded) expanded.remove(n.key) else expanded.add(n.key) }) {
                            Icon(if (n.key in expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                        }
                    } else {
                        Row(Modifier.width(48.dp)) {}
                    }
                    Column(Modifier.weight(1f)) {
                        Text(n.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            pluralStringResource(R.plurals.songs, n.total, n.total),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Checkbox(included, onCheckedChange = { toggle(n.key, it) }, enabled = !hiddenByParent)
                }
            }
        }
    }
}

private fun listSaver() = androidx.compose.runtime.saveable.listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

/** "Volver a buscar" with what it found: new, changed (renamed, moved, retagged) and removed songs. */
@Composable
private fun RescanRow(vm: MainViewModel) {
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val last by vm.lastScan.collectAsStateWithLifecycle()
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = vm::rescanAndReport, enabled = !scanning) {
            Text(stringResource(if (scanning) R.string.rescan_running else R.string.rescan))
        }
        val r = last
        if (r != null && !scanning) Text(
            vm.describe(r),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** The folders the library is walked from, each removable, plus "Añadir carpeta". */
@Composable
private fun MusicFoldersRows(vm: MainViewModel, trees: List<String>) {
    val pick = rememberMusicFolderPicker(vm)
    Text(
        stringResource(R.string.music_folders), style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
    )
    if (trees.isEmpty()) Text(
        stringResource(R.string.music_folders_none), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
    )
    trees.forEach { tree ->
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp))
            Text(vm.folderLabel(tree), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.removeMusicFolder(tree) }) { Icon(Icons.Rounded.Close, stringResource(R.string.remove)) }
        }
    }
    TextButton(onClick = pick, modifier = Modifier.padding(horizontal = 8.dp)) {
        Icon(Icons.Rounded.CreateNewFolder, null, modifier = Modifier.padding(end = 8.dp))
        Text(stringResource(R.string.music_folder_add))
    }
}

/** Opens the system folder picker; the chosen folder joins the library. */
@Composable
fun rememberMusicFolderPicker(vm: MainViewModel): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addMusicFolder(uri)
    }
    return { launcher.launch(null) }
}
