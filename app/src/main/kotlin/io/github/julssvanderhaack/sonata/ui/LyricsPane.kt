package io.github.julssvanderhaack.sonata.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.julssvanderhaack.sonata.R
import io.github.julssvanderhaack.sonata.lyrics.Lyrics
import io.github.julssvanderhaack.sonata.lyrics.LyricsState
import kotlin.math.abs

@Composable
fun LyricsPane(vm: MainViewModel, modifier: Modifier = Modifier) {
    val state by vm.lyrics.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    Column(modifier) {
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
            when (val s = state) {
                LyricsState.Idle -> Unit
                LyricsState.Loading -> Centered { CircularProgressIndicator() }
                LyricsState.Offline -> Message(stringResource(R.string.lyrics_offline)) {
                    TextButton(onClick = vm::reloadLyrics) { Text(stringResource(R.string.retry)) }
                }
                is LyricsState.None -> Message(
                    stringResource(if (s.onlineOff) R.string.lyrics_none_local else R.string.lyrics_none),
                ) {
                    if (s.onlineOff) {
                        FilledTonalButton(onClick = { vm.setOnlineLyrics(true) }) { Text(stringResource(R.string.lyrics_search_online)) }
                        Text(
                            stringResource(R.string.lyrics_online_note), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                is LyricsState.Ready -> {
                    val l = s.lyrics
                    when {
                        l.instrumental && l.lines.isEmpty() -> Message(stringResource(R.string.lyrics_instrumental)) {}
                        l.synced -> SyncedLyrics(vm, l, Modifier.weight(1f))
                        else -> PlainLyrics(l, Modifier.weight(1f))
                    }
                    LyricsFooter(vm, l, s.searching, settings.onlineLyrics)
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { content() }
}

@Composable
private fun Message(text: String, actions: @Composable () -> Unit) = Centered {
    Text(
        text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 12.dp, start = 16.dp, end = 16.dp),
    )
    actions()
}

/**
 * The line being sung is centred and highlighted; tapping a line seeks to it.
 * After the user scrolls by hand, following pauses for a few seconds.
 */
@Composable
private fun SyncedLyrics(vm: MainViewModel, lyrics: Lyrics, modifier: Modifier) {
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    val offset by vm.lyricsOffset.collectAsStateWithLifecycle()
    // a hair early reads naturally (desktop uses the same 0.15 s)
    val current = lyrics.lineAt(now.positionMs / 1000.0 + offset + 0.15)
    val list = rememberLazyListState()
    var userScrollAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start || it is DragInteraction.Stop) userScrollAt = System.currentTimeMillis() }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // the top padding puts item 0 a third of the way down, so scrolling to a line centres it there
        LaunchedEffect(current) {
            if (current >= 0 && System.currentTimeMillis() - userScrollAt > 4000) list.animateScrollToItem(current)
        }
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(top = maxHeight / 3, bottom = maxHeight / 2),
            modifier = Modifier.fillMaxSize().fadingEdges(),
        ) {
            itemsIndexed(lyrics.lines) { i, line ->
                val cs = MaterialTheme.colorScheme
                val color by animateColorAsState(
                    when {
                        i == current -> cs.primary
                        i < current -> cs.onSurfaceVariant.copy(alpha = 0.6f)
                        else -> cs.onSurface.copy(alpha = if (abs(i - current) <= 2) 0.9f else 0.7f)
                    },
                    label = "line",
                )
                Text(
                    line.text.ifEmpty { "♪" },
                    color = color,
                    style = if (i == current) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            userScrollAt = 0
                            vm.player.seekTo(((line.t - offset).coerceAtLeast(0.0) * 1000).toLong())
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Lines fade out towards the top and bottom instead of being cut off. */
private fun Modifier.fadingEdges() = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = 48.dp.toPx() / size.height
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, edge to Color.Black, 1 - edge to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }

@Composable
private fun PlainLyrics(lyrics: Lyrics, modifier: Modifier) {
    Column(modifier.fillMaxWidth().fadingEdges().verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) {
        lyrics.lines.forEach {
            Text(
                it.text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun LyricsFooter(vm: MainViewModel, lyrics: Lyrics, searching: Boolean, online: Boolean) {
    val offset by vm.lyricsOffset.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val source = when (lyrics.source) {
            Lyrics.Source.FILE -> stringResource(R.string.lyrics_from_file)
            Lyrics.Source.TAG -> stringResource(R.string.lyrics_from_tag)
            Lyrics.Source.LRCLIB -> "lrclib.net"
            Lyrics.Source.NONE -> ""
        }
        Text(
            if (searching) stringResource(R.string.lyrics_searching_better, source) else source,
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (lyrics.synced) {
            TextButton(onClick = { vm.lyricsOffset.value = offset - 0.5 }) { Text("−0,5 s") }
            if (offset != 0.0) Text("%+.1f".format(offset).replace('.', ','), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { vm.lyricsOffset.value = offset + 0.5 }) { Text("+0,5 s") }
        }
        if (online && lyrics.source == Lyrics.Source.LRCLIB) {
            IconButton(onClick = vm::reloadLyrics) { Icon(Icons.Rounded.Refresh, stringResource(R.string.lyrics_reload)) }
        }
    }
}
