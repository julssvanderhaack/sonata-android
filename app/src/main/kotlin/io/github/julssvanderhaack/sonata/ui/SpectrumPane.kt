package io.github.julssvanderhaack.sonata.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import io.github.julssvanderhaack.sonata.R
import io.github.julssvanderhaack.sonata.eq.EqBus
import io.github.julssvanderhaack.sonata.spectrum.Analyzer
import io.github.julssvanderhaack.sonata.spectrum.SpectrumBus
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

private val GRID_HZ = doubleArrayOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)

/**
 * Live spectrum of what is playing, like the desktop one: log-spaced bars with
 * falling peak caps. Analyses once per display frame (capped near 60 fps) and
 * only while on screen; with the music paused it stops once the bars have fallen.
 */
@Composable
fun SpectrumPane(playing: Boolean, modifier: Modifier = Modifier) {
    val analyzer = remember { Analyzer() }
    val isPlaying by rememberUpdatedState(playing)
    var bands by remember { mutableIntStateOf(0) }
    var frame by remember { mutableLongStateOf(0L) } // read while drawing: a new frame only redraws
    var heard by remember { mutableIntStateOf(0) }   // rate of the last block shown, 0 = nothing yet

    LifecycleStartEffect(Unit) {
        SpectrumBus.enabled = true
        onStopOrDispose { SpectrumBus.enabled = false }
    }
    LaunchedEffect(analyzer) {
        var last = 0L
        while (true) {
            if (!isPlaying && analyzer.settled) {
                snapshotFlow { isPlaying }.first { it }
                last = 0L
            }
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.1f)
            if (last != 0L && dt < 0.014f) continue // 90/120 Hz screens: no need to go past ~60
            last = now
            if (bands == 0) continue
            val rate = if (isPlaying) SpectrumBus.snapshot(analyzer.samples) else 0
            if (rate > 0) {
                analyzer.analyse(rate, bands, EqBus.settings.value)
                heard = rate
            } else analyzer.silence(bands)
            analyzer.step(dt)
            frame++
        }
    }

    val scheme = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        bands = (maxWidth.value / 8).roundToInt().coerceIn(16, 72)
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val labelH = 18.dp.toPx()
                val top = 4.dp.toPx()
                val areaH = size.height - labelH - top
                val bottom = top + areaH
                val rate = if (heard > 0) heard else 44100
                val grid = scheme.outlineVariant
                val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
                val labels = GRID_HZ.map { hz ->
                    val x = (Analyzer.xOf(hz, rate) * size.width).toFloat()
                    val text = if (hz >= 1000) "${(hz / 1000).toInt()}k" else hz.toInt().toString()
                    x to measurer.measure(text, labelStyle)
                }
                val bars = Brush.verticalGradient(
                    0f to scheme.primary, 1f to scheme.primary.copy(alpha = 0.55f), startY = top, endY = bottom,
                )
                val capH = 2.dp.toPx()
                onDrawBehind {
                    for ((x, text) in labels) {
                        drawLine(grid, Offset(x, top), Offset(x, bottom), 1.dp.toPx(), pathEffect = dash)
                        val w = text.size.width
                        if (x - w / 2 >= 0 && x + w / 2 <= size.width) drawText(text, topLeft = Offset(x - w / 2, bottom + 2.dp.toPx()))
                    }
                    drawLine(grid, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
                    frame // subscribe: redraw on every analysed frame
                    val level = analyzer.level
                    val peak = analyzer.peak
                    val n = level.size
                    if (n == 0) return@onDrawBehind
                    val slot = size.width / n
                    val gap = (slot * 0.22f).coerceIn(1.dp.toPx(), 3.dp.toPx())
                    for (b in 0 until n) {
                        val x0 = b * slot + gap / 2
                        val w = slot - gap
                        val h = level[b] * areaH
                        if (h > 0.5f) drawRect(bars, Offset(x0, bottom - h), Size(w, h))
                        if (peak[b] > 0.01f) drawRect(scheme.onSurface, Offset(x0, bottom - peak[b] * areaH - capH), Size(w, capH))
                    }
                }
            },
        )
        if (heard == 0 && !playing) {
            Text(
                stringResource(R.string.spectrum_idle), style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
