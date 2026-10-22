package io.github.julssvanderhaack.sonata.spectrum

import io.github.julssvanderhaack.sonata.eq.EqSettings
import io.github.julssvanderhaack.sonata.eq.curveDb
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

const val SPECTRUM_LO_HZ = 30.0
const val SPECTRUM_HI_HZ = 18000.0

/** Top of the frequency axis for this rate. */
fun spectrumHiHz(rate: Int) = min(SPECTRUM_HI_HZ, rate / 2.0 * 0.95)

/**
 * Same analysis as the desktop spectrum: a 4096-point FFT, log-spaced bands
 * from 30 Hz to 18 kHz, +3 dB/octave tilt and a -72 dB floor, then quick-up,
 * gentle-fall bars with peak caps that hold for a moment.
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
    private val mag = FloatArray(n / 2)

    var target = FloatArray(0); private set
    var level = FloatArray(0); private set
    var peak = FloatArray(0); private set
    private var hold = FloatArray(0)
    var rate = 44100; private set

    /** True once every bar and cap has fallen to the floor. */
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
        for (k in 0 until n / 2) mag[k] = hypot(re[k], im[k])
    }

    /** Fills [target] (0..1 per band) from [samples] at [sampleRate]. */
    fun analyse(sampleRate: Int, bands: Int, eq: EqSettings?) {
        rate = sampleRate
        fft()
        // amplitude of a full-scale sine = 0 dB (Hann window sums to N/2)
        val norm = 2f / (n / 2f)
        val binHz = sampleRate.toDouble() / n
        val hi = spectrumHiHz(sampleRate)
        val key = Triple(eq?.takeIf { it.enabled && !it.flat }, sampleRate, bands)
        if (key != eqKey) {
            eqKey = key
            val e = key.first
            val pre = if (e != null) 20 * log10(e.preampLinear().toDouble()) else 0.0
            eqDb = DoubleArray(bands) { b ->
                if (e == null) 0.0 else pre + curveDb(e.gains, centre(b, bands, hi), sampleRate.toDouble())
            }
        }
        if (target.size != bands) target = FloatArray(bands)
        for (b in 0 until bands) {
            val f0 = SPECTRUM_LO_HZ * (hi / SPECTRUM_LO_HZ).pow(b.toDouble() / bands)
            val f1 = SPECTRUM_LO_HZ * (hi / SPECTRUM_LO_HZ).pow((b + 1).toDouble() / bands)
            val k0 = ceil(f0 / binHz).toInt()
            val k1 = (f1 / binHz).toInt()
            var m = 0f
            if (k1 >= k0) {
                var k = k0
                while (k <= k1 && k < n / 2) { m = max(m, mag[k]); k++ }
            } else {
                // narrow low bands: interpolate between the two nearest bins
                val k = sqrt(f0 * f1) / binHz
                val i = min(k.toInt(), n / 2 - 2)
                val fr = (k - i).toFloat()
                m = (1 - fr) * mag[i] + fr * mag[i + 1]
            }
            val fc = sqrt(f0 * f1)
            // music falls ~3 dB per octave; tilt it back so the treble isn't flat on the floor
            val db = 20 * log10(max(1e-9f, m * norm).toDouble()) + 3 * log2(fc / 1000) + eqDb[b]
            target[b] = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0.0, 1.0).toFloat()
        }
    }

    /** No sound: the bars fall to the floor. */
    fun silence(bands: Int) {
        if (target.size != bands) target = FloatArray(bands) else target.fill(0f)
    }

    /** Moves the bars [dt] seconds towards [target]. */
    fun step(dt: Float) {
        val bands = target.size
        if (level.size != bands) {
            level = FloatArray(bands)
            peak = FloatArray(bands)
            hold = FloatArray(bands)
        }
        var moving = false
        for (b in 0 until bands) {
            val t = target[b]
            var l = level[b]
            l = if (t > l) l + (t - l) * 0.7f else max(t, l - dt * 1.6f) // quick up, gentle fall
            var p = peak[b]
            if (l >= p) {
                p = l
                hold[b] = 0.45f
            } else {
                hold[b] -= dt
                if (hold[b] < 0) p = max(l, p - dt * 0.7f)
            }
            level[b] = l
            peak[b] = p
            moving = moving || l > 0 || p > 0
        }
        settled = !moving
    }

    companion object {
        const val FLOOR_DB = -72.0

        private fun centre(b: Int, bands: Int, hi: Double) =
            SPECTRUM_LO_HZ * (hi / SPECTRUM_LO_HZ).pow((b + 0.5) / bands)

        /** x of [hz] on the axis, 0..1. */
        fun xOf(hz: Double, rate: Int) = ln(hz / SPECTRUM_LO_HZ) / ln(spectrumHiHz(rate) / SPECTRUM_LO_HZ)
    }
}
