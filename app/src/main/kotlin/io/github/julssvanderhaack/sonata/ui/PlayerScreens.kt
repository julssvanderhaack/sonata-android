package io.github.julssvanderhaack.sonata.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import io.github.julssvanderhaack.sonata.R
import io.github.julssvanderhaack.sonata.player.NowPlaying
import kotlinx.coroutines.delay

/** Polls the position while something plays; the player only reports discrete events. */
@Composable
private fun PositionClock(vm: MainViewModel, playing: Boolean, periodMs: Long) {
    LaunchedEffect(playing, periodMs) {
        while (playing) {
            vm.player.refreshPosition()
            delay(periodMs)
        }
    }
}

@Composable
fun MiniPlayer(vm: MainViewModel, modifier: Modifier = Modifier) {
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    if (now.trackId == null) return
    PositionClock(vm, now.playing, 1000)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column {
            LinearProgressIndicator(
                progress = { if (now.durationMs > 0) now.positionMs.toFloat() / now.durationMs else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
            Row(
                Modifier.clickable { vm.open(Route.NowPlaying) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Art(now.trackId, 44.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(now.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        now.artist ?: unknownArtist(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PlayPause(now, vm, big = false)
                IconButton(onClick = vm.player::next, enabled = now.hasNext) {
                    Icon(Icons.Rounded.SkipNext, stringResource(R.string.next))
                }
            }
        }
    }
}

@Composable
private fun PlayPause(now: NowPlaying, vm: MainViewModel, big: Boolean) {
    val icon = if (now.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
    val desc = stringResource(if (now.playing) R.string.pause else R.string.play)
    if (big) {
        FilledIconButton(onClick = vm.player::toggle, modifier = Modifier.size(72.dp)) {
            Icon(icon, desc, Modifier.size(40.dp))
        }
    } else {
        IconButton(onClick = vm.player::toggle) { Icon(icon, desc) }
    }
}

@Composable
fun NowPlayingScreen(vm: MainViewModel) {
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    val pane by vm.pane.collectAsStateWithLifecycle()
    val lyrics = pane == NowPane.Lyrics
    val eq by vm.eq.collectAsStateWithLifecycle()
    PositionClock(vm, now.playing, 250)
    BackScaffold(
        stringResource(R.string.now_playing), vm::back,
        actions = {
            val on = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
            IconButton(onClick = { vm.pane.value = if (lyrics) NowPane.Cover else NowPane.Lyrics }, colors = if (lyrics) on else IconButtonDefaults.iconButtonColors()) {
                Icon(Icons.Rounded.Lyrics, stringResource(R.string.lyrics))
            }
            val spectrum = pane == NowPane.Spectrum
            IconButton(onClick = { vm.pane.value = if (spectrum) NowPane.Cover else NowPane.Spectrum }, colors = if (spectrum) on else IconButtonDefaults.iconButtonColors()) {
                Icon(Icons.Rounded.GraphicEq, stringResource(R.string.spectrum))
            }
            IconButton(onClick = { vm.open(Route.Queue) }) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.queue))
            }
            IconButton(onClick = { vm.open(Route.Equalizer) }, colors = if (eq.enabled) on else IconButtonDefaults.iconButtonColors()) {
                Icon(Icons.Rounded.Equalizer, stringResource(R.string.equalizer))
            }
        },
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize().padding(horizontal = 24.dp)) {
            val art = min(maxWidth, maxHeight * 0.5f)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (pane != NowPane.Cover) {
                    // small header so the lyrics or the spectrum get the room
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Art(now.trackId, 56.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(now.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                now.artist ?: unknownArtist(), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (lyrics) LyricsPane(vm, Modifier.weight(1f).fillMaxWidth())
                    else SpectrumPane(now.playing, Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp))
                } else {
                    Spacer(Modifier.weight(1f))
                    Art(now.trackId, art, corner = 14.dp)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        now.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(now.artist ?: unknownArtist(), now.album).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                }
                SeekBar(now, vm)
                Controls(now, vm)
                if (pane == NowPane.Cover) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Controls(now: NowPlaying, vm: MainViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val on = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
        val off = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = vm.player::toggleShuffle, colors = if (now.shuffle) on else off) {
            Icon(Icons.Rounded.Shuffle, stringResource(R.string.shuffle))
        }
        IconButton(onClick = vm.player::previous) {
            Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.previous), Modifier.size(36.dp))
        }
        PlayPause(now, vm, big = true)
        IconButton(onClick = vm.player::next, enabled = now.hasNext) {
            Icon(Icons.Rounded.SkipNext, stringResource(R.string.next), Modifier.size(36.dp))
        }
        IconButton(onClick = vm.player::cycleRepeat, colors = if (now.repeat != Player.REPEAT_MODE_OFF) on else off) {
            Icon(
                if (now.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                stringResource(R.string.repeat),
            )
        }
    }
}

@Composable
private fun SeekBar(now: NowPlaying, vm: MainViewModel) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val dur = now.durationMs.coerceAtLeast(1)
    val pos = dragging ?: (now.positionMs.toFloat() / dur).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = pos,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { vm.player.seekTo((it * dur).toLong()) }
                dragging = null
            },
        )
        Box(Modifier.fillMaxWidth()) {
            val style = MaterialTheme.typography.labelSmall
            val color = MaterialTheme.colorScheme.onSurfaceVariant
            Text(formatDuration((pos * dur).toLong()), style = style, color = color, modifier = Modifier.align(Alignment.CenterStart))
            Text(formatDuration(now.durationMs), style = style, color = color, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}
