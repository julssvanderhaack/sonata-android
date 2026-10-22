package io.github.julssvanderhaack.sonata.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.julssvanderhaack.sonata.R

val AUDIO_PERMISSION =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
fun AppRoot(vm: MainViewModel) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, AUDIO_PERMISSION) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(granted) {
        if (granted) {
            vm.startLibrary()
            vm.player.connect()
        }
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        if (!granted) {
            PermissionScreen { ask.launch(AUDIO_PERMISSION) }
            return@Surface
        }
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
                }
            }
            if (mini) MiniPlayer(vm, Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun PermissionScreen(onAsk: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.LibraryMusic, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
        Text(
            stringResource(R.string.permission_title), style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            stringResource(R.string.permission_text), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(onClick = onAsk) { Text(stringResource(R.string.permission_button)) }
    }
}
