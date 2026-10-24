package io.github.julssvanderhaack.sonata.spectrum

import io.github.julssvanderhaack.sonata.eq.EqSettings
import io.github.julssvanderhaack.sonata.eq.curveDb
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

const val SPECTRUM_LO_HZ = 20.0

/** Top of the frequency axis: the track's Nyquist frequency, half its sample rate. */
fun spectrumHiHz(rate: Int) = rate / 2.0

/**
 * Same analysis as the desktop spectrum: a 4096-point FFT, log-spaced points
 * from 20 Hz to Nyquist, each the average energy of its slice of spectrum,
 * +3 dB/octave tilt and a -72 dB floor, lightly blurred along the axis so it
 * reads as one curve. The curve eases in time (~90 ms up, ~300 ms down) and a
 * peak line holds and then falls slowly.
 */
class Analyzer(val n: Int = 4096) {
    val samples = FloatArray(n)
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / (n - 1))).toFloat() } // Hann
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val cosT = FloatArray(n / 2) { cos(-2 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { sin(-2 * PI * it / n).toFloat() }
    private val bitrev = IntArray(n).also { r ->
        val bits = log2(n.toDouble()).toInt()
        for (i in 0 until n) {
            var v = 0
            for (b in 0 until bits) v = v or (((i shr b) and 1) shl (bits - 1 - b))
            r[i] = v
        }
    }
    private val power = FloatArray(n / 2)

    var target = FloatArray(0); private set
    var level = FloatArray(0); private set
    var peak = FloatArray(0); private set
    private var hold = FloatArray(0)
    var rate = 44100; private set

    /** True once the curve and the peak line have fallen to the floor. */
    var settled = true; private set

    // the EQ isn't in the captured audio (it's taken before the sink's processors), so its curve is added here
    private var eqKey: Any? = null
    private var eqDb = DoubleArray(0)

    private fun fft() {
        for (i in 0 until n) {
            val j = bitrev[i]
            re[j] = samples[i] * window[i]
            im[j] = 0f
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val stride = n / len
            var i = 0
            while (i < n) {
                for (j in 0 until half) {
                    val a = i + j
                    val b = a + half
                    val wr = cosT[j * stride]
                    val wi = sinT[j * stride]
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                i += len
            }
            len = len shl 1
        }
        for (k in 0 until n / 2) power[k] = re[k] * re[k] + im[k] * im[k]
    }

    /** Fills [target] (0..1 per curve point) from [samples] at [sampleRate]. */
    fun analyse(sampleRate: Int, points: Int, eq: EqSettings?) {
        rate = sampleRate
        fft()
        // amplitude of a full-scale sine = 0 dB (Hann window sums to N/2)
        val norm = 2f / (n / 2f)
        val binHz = sampleRate.toDouble() / n
        val hi = spectrumHiHz(sampleRate)
        val key = Triple(eq?.takeIf { it.enabled && !it.flat }, sampleRate, points)
        if (key != eqKey) {
            eqKey = key
            val e = key.first
            val pre = if (e != null) 20 * log10(e.preampLinear().toDouble()) else 0.0
            eqDb = DoubleArray(points) { i ->
                if (e == null) 0.0 else pre + curveDb(e.gains, at(i, points, hi), sampleRate.toDouble())
            }
        }
        if (target.size != points) target = FloatArray(points)
        val du = 0.5 / (points - 1)
        for (i in 0 until points) {
            // one value per point: the energy of the slice of spectrum around it
            // (averaged, not the loudest bin, so the curve doesn't jitter)
            val u = i.toDouble() / (points - 1)
            val fc = SPECTRUM_LO_HZ * (hi / SPECTRUM_LO_HZ).pow(u)
            val f0 = SPECTRUM_LO_HZ * (hi / SPECTRUM_LO_HZ).pow(u - du)
            val f1 = SPECTRUM_LO_HZ * (hi / SPECTRUM_LO_HZ).pow(u + du)
            val k0 = ceil(f0 / binHz).toInt()
            val k1 = min((f1 / binHz).toInt(), n / 2 - 1)
            val m = if (k1 >= k0) {
                var sum = 0.0
                for (k in k0..k1) sum += power[k]
                sqrt(sum / (k1 - k0 + 1)).toFloat()
            } else {
                // narrow low slices: interpolate between the two nearest bins
                val k = fc / binHz
                val j = min(k.toInt(), n / 2 - 2)
                val fr = (k - j).toFloat()
                (1 - fr) * sqrt(power[j]) + fr * sqrt(power[j + 1])
            }
            // music falls ~3 dB per octave; tilt it back so the treble isn't flat on the floor
            val db = 20 * log10(max(1e-9f, m * norm).toDouble()) + 3 * log2(fc / 1000) + eqDb[i]
            target[i] = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0.0, 1.0).toFloat()
        }
        // a light [1 2 1] blur along the axis: one continuous line, not a comb
        repeat(2) {
            var prev = target[0]
            for (i in 1 until points - 1) {
                val cur = target[i]
                target[i] = (prev + 2 * cur + target[i + 1]) / 4
                prev = cur
            }
        }
    }

    /** No sound: the curve falls to the floor. */
    fun silence(points: Int) {
        if (target.size != points) target = FloatArray(points) else target.fill(0f)
    }

    /** Eases the curve [dt] seconds towards [target]: it breathes with the music instead of following every frame. */
    fun step(dt: Float) {
        val points = target.size
        if (level.size != points) {
            level = FloatArray(points)
            peak = FloatArray(points)
            hold = FloatArray(points)
        }
        // ~90 ms to rise, ~300 ms to fall, whatever the frame rate
        val up = 1 - exp(-dt / 0.09f)
        val down = 1 - exp(-dt / 0.30f)
        var moving = false
        for (i in 0 until points) {
            val t = target[i]
            var l = level[i]
            l += (t - l) * (if (t > l) up else down)
            if (l < 0.002f) l = 0f
            var p = peak[i]
            if (l >= p) {
                p = l
                hold[i] = 0.8f
            } else {
                hold[i] -= dt
                if (hold[i] < 0) p = max(l, p - dt * 0.25f)
            }
            level[i] = l
            peak[i] = p
            moving = moving || l > 0 || p > 0.01f
        }
        settled = !moving
    }

    companion object {
        const val FLOOR_DB = -72.0

        /** Frequency of curve point [i] of [points]. */
        private fun at(i: Int, points: Int, hi: Double) =
            SPECTRUM_LO_HZ * (hi / SPECTRUM_LO_HZ).pow(i.toDouble() / (points - 1))

        /** x of [hz] on the axis, 0..1. */
        fun xOf(hz: Double, rate: Int) = ln(hz / SPECTRUM_LO_HZ) / ln(spectrumHiHz(rate) / SPECTRUM_LO_HZ)
    }
}
