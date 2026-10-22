package io.github.julssvanderhaack.sonata.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.julssvanderhaack.sonata.R
import io.github.julssvanderhaack.sonata.eq.BANDS
import io.github.julssvanderhaack.sonata.eq.BAND_HZ
import io.github.julssvanderhaack.sonata.eq.BUILTIN_PRESETS
import io.github.julssvanderhaack.sonata.eq.EqPreset
import io.github.julssvanderhaack.sonata.eq.MAX_DB
import io.github.julssvanderhaack.sonata.eq.curveDb
import io.github.julssvanderhaack.sonata.eq.curvePeakDb
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private fun hzLabel(hz: Double) = if (hz >= 1000) "${(hz / 1000).roundToInt()}k" else hz.roundToInt().toString()

private fun dbLabel(db: Float) = when {
    db == 0f -> "0"
    db % 1f == 0f -> "%+d".format(db.toInt())
    else -> "%+.1f".format(db).replace('.', ',')
}

@Composable
fun EqScreen(vm: MainViewModel) {
    val eq by vm.eq.collectAsStateWithLifecycle()
    var saving by remember { mutableStateOf(false) }
    BackScaffold(
        stringResource(R.string.equalizer), vm::back,
        actions = { Switch(eq.enabled, { on -> vm.updateEq { it.copy(enabled = on) } }, Modifier.padding(end = 12.dp)) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            PresetPicker(
                current = eq.presetName(),
                custom = eq.custom,
                onPick = { p -> vm.updateEq { it.copy(gains = p.gains, enabled = true) } },
                onDelete = { p -> vm.updateEq { it.copy(custom = it.custom - p) } },
                onSave = { saving = true },
            )
            Column(Modifier.alpha(if (eq.enabled) 1f else 0.5f)) {
                Curve(eq.gains, Modifier.fillMaxWidth().height(120.dp).padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (b in 0 until BANDS) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text(dbLabel(eq.gains[b]), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            VerticalSlider(
                                value = eq.gains[b],
                                onChange = { v -> vm.updateEq { s -> s.copy(enabled = true, gains = s.gains.toMutableList().also { it[b] = v }) } },
                                modifier = Modifier.height(220.dp).width(36.dp),
                            )
                            Text(hzLabel(BAND_HZ[b]), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.preamp), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    val auto = if (eq.autoPreamp) -curvePeakDb(eq.gains).toFloat() else 0f
                    Text(
                        dbLabel(((eq.preamp + auto) * 2).roundToInt() / 2f) + " dB",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = eq.preamp,
                    onValueChange = { v -> vm.updateEq { it.copy(preamp = (v * 2).roundToInt() / 2f) } },
                    valueRange = -MAX_DB..MAX_DB,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.auto_preamp), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.auto_preamp_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(eq.autoPreamp, { on -> vm.updateEq { it.copy(autoPreamp = on) } })
                }
                OutlinedButton(
                    onClick = { vm.updateEq { it.copy(gains = List(BANDS) { 0f }, preamp = 0f) } },
                    modifier = Modifier.padding(vertical = 16.dp),
                ) { Text(stringResource(R.string.eq_reset)) }
            }
        }
    }
    if (saving) SavePresetDialog(
        onDismiss = { saving = false },
        onSave = { name ->
            vm.updateEq { s -> s.copy(custom = s.custom.filterNot { it.name == name } + EqPreset(name, s.gains)) }
            saving = false
        },
    )
}

@Composable
private fun PresetPicker(
    current: String?,
    custom: List<EqPreset>,
    onPick: (EqPreset) -> Unit,
    onDelete: (EqPreset) -> Unit,
    onSave: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { open = true }) {
                Text(current ?: stringResource(R.string.eq_custom))
                Icon(Icons.Rounded.ArrowDropDown, null)
            }
            DropdownMenu(open, { open = false }) {
                (BUILTIN_PRESETS + custom).forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name) },
                        onClick = { onPick(p); open = false },
                        trailingIcon = if (p in custom) ({
                            IconButton(onClick = { onDelete(p); open = false }) { Icon(Icons.Rounded.Close, stringResource(R.string.delete)) }
                        }) else null,
                    )
                }
            }
        }
        if (current == null) TextButton(onClick = onSave) { Text(stringResource(R.string.eq_save_preset)) }
    }
}

@Composable
private fun SavePresetDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.eq_save_preset)) },
        text = {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.theme_name)) })
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Summed response from 20 Hz to 20 kHz on a log axis, ±12 dB. */
@Composable
private fun Curve(gains: List<Float>, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val points = remember(gains) { List(121) { i -> curveDb(gains, 20 * 1000.0.pow(i / 120.0)) } }
    Canvas(modifier.clip(RoundedCornerShape(10.dp)).background(cs.surfaceContainerLow)) {
        fun y(db: Double) = (size.height / 2 - db / MAX_DB * (size.height / 2 - 6)).toFloat()
        val grid = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        for (db in listOf(-6.0, 6.0)) drawLine(cs.outlineVariant, Offset(0f, y(db)), Offset(size.width, y(db)), 1f, pathEffect = grid)
        drawLine(cs.outline, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1f)
        for (hz in BAND_HZ) {
            val x = (log10(hz / 20) / 3 * size.width).toFloat()
            drawLine(cs.outlineVariant, Offset(x, 0f), Offset(x, size.height), 1f, pathEffect = grid)
        }
        val path = Path()
        points.forEachIndexed { i, db ->
            val x = i / 120f * size.width
            if (i == 0) path.moveTo(x, y(db)) else path.lineTo(x, y(db))
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(size.width, y(0.0))
            lineTo(0f, y(0.0))
            close()
        }
        drawPath(fill, cs.primary.copy(alpha = 0.18f))
        drawPath(path, cs.primary, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** ±12 dB in half-dB steps; double tap returns to 0. */
@Composable
private fun VerticalSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    fun fromY(y: Float, h: Float): Float {
        val v = MAX_DB - (y / h).coerceIn(0f, 1f) * 2 * MAX_DB
        return (v * 2).roundToInt() / 2f
    }
    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onChange(0f) }, onTap = { onChange(fromY(it.y, size.height.toFloat())) })
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, _ ->
                    change.consume()
                    onChange(fromY(change.position.y, size.height.toFloat()))
                }
            },
    ) {
        val cx = size.width / 2
        val pad = 10.dp.toPx()
        fun y(v: Float) = pad + (MAX_DB - v) / (2 * MAX_DB) * (size.height - 2 * pad)
        drawLine(cs.surfaceContainerHighest, Offset(cx, pad), Offset(cx, size.height - pad), 4.dp.toPx(), StrokeCap.Round)
        drawLine(cs.primary, Offset(cx, y(0f)), Offset(cx, y(value)), 4.dp.toPx(), StrokeCap.Round)
        drawLine(cs.outline, Offset(cx - 6.dp.toPx(), y(0f)), Offset(cx + 6.dp.toPx(), y(0f)), 1.dp.toPx())
        drawCircle(cs.primary, 9.dp.toPx(), Offset(cx, y(value)))
        drawCircle(cs.onPrimary, 3.dp.toPx(), Offset(cx, y(value)))
    }
}
