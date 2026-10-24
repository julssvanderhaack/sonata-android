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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
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
import java.util.Locale
import kotlin.math.roundToInt

private val GRID_HZ = doubleArrayOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0, 50000.0, 100000.0)

/** "500", "2k", "22,05k": like the desktop axis, Spanish decimal comma. */
private fun khz(hz: Double): String {
    if (hz < 1000) return hz.roundToInt().toString()
    val s = String.format(Locale.forLanguageTag("es-ES"), "%.2f", hz / 1000).trimEnd('0').trimEnd(',')
    return "${s}k"
}

/**
 * Live spectrum of what is playing, like the desktop one: a smooth filled
 * curve on a log axis from 20 Hz to the track's Nyquist frequency, with a
 * slowly falling peak line. Analyses ~30 times a second, the desktop's rate,
 * and only while on screen; with the music paused it stops once the curve has fallen.
 */
@Composable
fun SpectrumPane(playing: Boolean, modifier: Modifier = Modifier) {
    val analyzer = remember { Analyzer() }
    val isPlaying by rememberUpdatedState(playing)
    var points by remember { mutableIntStateOf(0) }
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
            val dt = if (last == 0L) 0.033f else ((now - last) / 1e9f).coerceAtMost(0.1f)
            if (last != 0L && dt < 0.028f) continue // ~30 fps like the desktop; faster looks jittery
            last = now
            if (points == 0) continue
            val rate = if (isPlaying) SpectrumBus.snapshot(analyzer.samples) else 0
            if (rate > 0) {
                analyzer.analyse(rate, points, EqBus.settings.value)
                heard = rate
            } else analyzer.silence(points)
            analyzer.step(dt)
            frame++
        }
    }

    val scheme = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        points = (maxWidth.value / 6).roundToInt().coerceIn(32, 260)
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val labelH = 18.dp.toPx()
                val top = 4.dp.toPx()
                val areaH = size.height - labelH - top
                val bottom = top + areaH
                val rate = if (heard > 0) heard else 44100
                val grid = scheme.outlineVariant
                val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
                val labelY = bottom + 2.dp.toPx()
                // leave room at the right edge for the Nyquist label
                val labels = GRID_HZ.map { hz -> (Analyzer.xOf(hz, rate) * size.width).toFloat() to hz }
                    .filter { (x, _) -> x <= size.width - 44.dp.toPx() }
                    .map { (x, hz) -> x to measurer.measure(khz(hz), labelStyle) }
                val nyquist = measurer.measure(khz(rate / 2.0), labelStyle)
                val dark = scheme.background.luminance() < 0.5f
                val accent = scheme.primary
                val bright = if (dark) lerp(accent, Color.White, 0.25f) else accent
                val fill = Brush.verticalGradient(
                    0f to lerp(accent, Color.White, if (dark) 0.3f else 0.05f).copy(alpha = 0.55f),
                    1f to accent.copy(alpha = 0.12f),
                    startY = top, endY = bottom,
                )
                val peakColor = scheme.onSurface.copy(alpha = 0.35f)
                val lineWidth = 1.6.dp.toPx()
                val peakWidth = 1.dp.toPx()
                val curve = Path()
                val area = Path()
                fun trace(path: Path, v: FloatArray) {
                    val n = v.size
                    for (i in 0 until n) {
                        val x = size.width * i / (n - 1)
                        val y = bottom - v[i] * areaH
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                }
                onDrawBehind {
                    for ((x, text) in labels) {
                        drawLine(grid, Offset(x, top), Offset(x, bottom), 1.dp.toPx(), pathEffect = dash)
                        val w = text.size.width
                        if (x - w / 2 >= 0) drawText(text, topLeft = Offset(x - w / 2, labelY))
                    }
                    // the right edge is the highest frequency this sample rate can hold
                    drawText(nyquist, topLeft = Offset(size.width - nyquist.size.width, labelY))
                    drawLine(grid, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
                    frame // subscribe: redraw on every analysed frame
                    val level = analyzer.level
                    val peak = analyzer.peak
                    if (level.size < 2) return@onDrawBehind
                    // the points are already smoothed (blur + easing): straight segments read as a curve
                    curve.reset()
                    trace(curve, level)
                    area.reset()
                    area.addPath(curve)
                    area.lineTo(size.width, bottom)
                    area.lineTo(0f, bottom)
                    area.close()
                    drawPath(area, fill)
                    curve.reset()
                    trace(curve, peak)
                    drawPath(curve, peakColor, style = Stroke(peakWidth))
                    curve.reset()
                    trace(curve, level)
                    drawPath(curve, bright, style = Stroke(lineWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
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
