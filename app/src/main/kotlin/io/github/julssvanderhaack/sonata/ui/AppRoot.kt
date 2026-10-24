package io.github.julssvanderhaack.sonata.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.julssvanderhaack.sonata.R

@Composable
fun AppRoot(vm: MainViewModel) {
    // no permission to ask: each music folder the user picks grants itself
    LaunchedEffect(Unit) {
        vm.startLibrary()
        vm.player.connect()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.rescanIfStale() }
    CompositionLocalProvider(LocalVm provides vm) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        val now by vm.nowPlaying.collectAsStateWithLifecycle()
        val route = vm.stack.last()
        BackHandler(enabled = vm.stack.size > 1) { vm.back() }
        val mini = now.trackId != null && route != Route.NowPlaying && route !is Route.ThemeEditor
        Column(Modifier.fillMaxSize()) {
            // with the mini player below, it owns the navigation-bar inset, not the screen
            Box(Modifier.weight(1f).then(if (mini) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)) {
                when (route) {
                    Route.Library -> LibraryScreen(vm)
                    is Route.AlbumDetail -> AlbumScreen(vm, route.key)
                    is Route.ArtistDetail -> ArtistScreen(vm, route.key)
                    is Route.GenreDetail -> GenreScreen(vm, route.name)
                    is Route.Folder -> FolderScreen(vm, route.key)
                    Route.NowPlaying -> NowPlayingScreen(vm)
                    Route.Settings -> SettingsScreen(vm)
                    Route.Folders -> FoldersScreen(vm)
                    Route.Themes -> ThemesScreen(vm)
                    is Route.ThemeEditor -> ThemeEditorScreen(vm, route.base, route.editing)
                    Route.Equalizer -> EqScreen(vm)
                    Route.Queue -> QueueScreen(vm)
                    is Route.PlaylistDetail -> PlaylistScreen(vm, route.id)
                }
            }
            if (mini) MiniPlayer(vm, Modifier.navigationBarsPadding())
        }
    }
    AddToPlaylistDialog(vm)
    }
}
