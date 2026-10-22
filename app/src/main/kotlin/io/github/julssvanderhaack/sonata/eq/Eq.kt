package io.github.julssvanderhaack.sonata.eq

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/* 10-band graphic equaliser, a port of desktop Sonata's eq.cpp. */

const val BANDS = 10
val BAND_HZ = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
const val MAX_DB = 12f

data class EqSettings(
    val enabled: Boolean = false,
    /** dB, user-set. */
    val preamp: Float = 0f,
    /** Pull the level down by the curve's peak, so boosts never clip. */
    val autoPreamp: Boolean = true,
    val gains: List<Float> = List(BANDS) { 0f },
    val custom: List<EqPreset> = emptyList(),
) {
    val flat get() = preamp == 0f && gains.all { it == 0f }

    /** Linear gain applied before the filters. */
    fun preampLinear(): Float {
        if (!enabled) return 1f
        val db = preamp - if (autoPreamp) curvePeakDb(gains).toFloat() else 0f
        return 10f.pow(db / 20f)
    }

    /** Built-in or custom preset whose gains match, or null = "Personalizado". */
    fun presetName(): String? = (BUILTIN_PRESETS + custom).firstOrNull { it.gains == gains }?.name

    /** "1;0;1;g0,g1,…|name\tg0,…|…" — compact and free of Android's org.json, so it can be unit tested. */
    fun serialize(): String = buildString {
        append(if (enabled) 1 else 0).append(';').append(preamp).append(';').append(if (autoPreamp) 1 else 0).append(';')
        append(gains.joinToString(","))
        for (p in custom) append('|').append(p.name.replace("|", "/").replace("\t", " ")).append('\t').append(p.gains.joinToString(","))
    }

    companion object {
        fun parse(s: String?): EqSettings {
            if (s.isNullOrEmpty()) return EqSettings()
            return runCatching {
                val parts = s.split('|')
                val head = parts[0].split(';')
                EqSettings(
                    enabled = head[0] == "1",
                    preamp = head[1].toFloat(),
                    autoPreamp = head[2] == "1",
                    gains = head[3].split(',').map { it.toFloat() }.also { require(it.size == BANDS) },
                    custom = parts.drop(1).mapNotNull { p ->
                        val (name, g) = p.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
                        g.split(',').map { it.toFloat() }.takeIf { it.size == BANDS }?.let { EqPreset(name, it) }
                    },
                )
            }.getOrDefault(EqSettings())
        }
    }
}

data class EqPreset(val name: String, val gains: List<Float>)

private fun p(name: String, vararg g: Int) = EqPreset(name, g.map { it.toFloat() })

/** Same presets and names as desktop. */
val BUILTIN_PRESETS = listOf(
    p("Plano", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
    p("Rock", 5, 4, 3, 1, -1, -1, 1, 3, 4, 5),
    p("Pop", -1, 1, 3, 4, 3, 0, -1, -1, 1, 2),
    p("Jazz", 3, 2, 1, 2, -1, -1, 0, 1, 2, 3),
    p("Clásica", 4, 3, 2, 1, -1, -1, 0, 2, 3, 4),
    p("Electrónica", 5, 4, 1, 0, -2, 2, 1, 1, 4, 5),
    p("Hip-hop", 5, 4, 1, 3, -1, -1, 1, -1, 2, 3),
    p("Acústica", 4, 3, 2, 1, 1, 1, 2, 3, 3, 2),
    p("Voz", -2, -3, -2, 1, 4, 4, 3, 1, 0, -2),
    p("Más graves", 7, 6, 4, 2, 0, 0, 0, 0, 0, 0),
    p("Más agudos", 0, 0, 0, 0, 0, 1, 2, 4, 6, 7),
    p("Sonoridad", 6, 4, 1, 0, -2, -1, 0, 1, 4, 5),
    p("Altavoz del móvil", -6, -3, 2, 3, 1, 0, 0, 1, 2, 1),
    p("Auriculares", 3, 2, 1, 0, -1, 0, 1, 2, 3, 2),
    p("Noche (suave)", -2, -1, 0, 1, 2, 2, 1, -1, -3, -4),
)

/** Gentle overlap: smooth curve, no ripple between bands. */
private const val Q = 1.0

class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    /** A 0 dB band is exactly the identity; skipping it keeps the signal bit-identical. */
    val identity get() = b0 == 1.0 && b1 == a1 && b2 == a2
}

/** RBJ cookbook peaking filter. */
fun peaking(hz: Double, gainDb: Double, rate: Double): Biquad {
    val a = 10.0.pow(gainDb / 40.0)
    val w0 = 2 * PI * min(hz, 0.45 * rate) / rate
    val alpha = sin(w0) / (2 * Q)
    val c = cos(w0)
    val a0 = 1 + alpha / a
    return Biquad((1 + alpha * a) / a0, -2 * c / a0, (1 - alpha * a) / a0, -2 * c / a0, (1 - alpha / a) / a0)
}

/** Summed response of all bands at [hz], in dB. */
fun curveDb(gains: List<Float>, hz: Double, rate: Double = 48000.0): Double {
    val w = -2 * PI * hz / rate
    // z = e^{jw}; evaluate |B(z)/A(z)| with plain complex arithmetic
    val zr = cos(w)
    val zi = sin(w)
    val z2r = zr * zr - zi * zi
    val z2i = 2 * zr * zi
    var db = 0.0
    for (b in 0 until BANDS) {
        if (gains[b] == 0f) continue
        val k = peaking(BAND_HZ[b], gains[b].toDouble(), rate)
        val nr = k.b0 + k.b1 * zr + k.b2 * z2r
        val ni = k.b1 * zi + k.b2 * z2i
        val dr = 1 + k.a1 * zr + k.a2 * z2r
        val di = k.a1 * zi + k.a2 * z2i
        db += 20 * log10(hypot(nr, ni) / hypot(dr, di))
    }
    return db
}

/** Highest point of the curve between 20 Hz and 20 kHz. */
fun curvePeakDb(gains: List<Float>): Double {
    if (gains.all { it <= 0f }) return 0.0
    var peak = 0.0
    for (i in 0..96) peak = maxOf(peak, curveDb(gains, 20 * 1000.0.pow(i / 96.0)))
    return peak
}
