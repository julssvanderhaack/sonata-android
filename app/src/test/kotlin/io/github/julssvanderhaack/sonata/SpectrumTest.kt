package io.github.julssvanderhaack.sonata

import io.github.julssvanderhaack.sonata.eq.BANDS
import io.github.julssvanderhaack.sonata.eq.EqSettings
import io.github.julssvanderhaack.sonata.spectrum.Analyzer
import io.github.julssvanderhaack.sonata.spectrum.SPECTRUM_LO_HZ
import io.github.julssvanderhaack.sonata.spectrum.SpectrumBus
import io.github.julssvanderhaack.sonata.spectrum.spectrumHiHz
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

class SpectrumTest {
    private val rate = 44100
    private val points = 96

    @After
    fun reset() {
        SpectrumBus.playing(false)
        SpectrumBus.clear()
    }

    private fun pointOf(hz: Double) = (ln(hz / SPECTRUM_LO_HZ) / ln(spectrumHiHz(rate) / SPECTRUM_LO_HZ) * (points - 1)).roundToInt()

    private fun sine(a: Analyzer, hz: Double, amp: Double) {
        for (i in 0 until a.n) a.samples[i] = (amp * sin(2 * PI * hz * i / rate)).toFloat()
    }

    @Test
    fun sinePeaksAtItsPoint() {
        val a = Analyzer()
        sine(a, 1000.0, 1.0)
        a.analyse(rate, points, null)
        val top = a.target.indices.maxBy { a.target[it] }
        assertTrue("peak at $top, 1 kHz is ${pointOf(1000.0)}", kotlin.math.abs(top - pointOf(1000.0)) <= 1)
        // a pure tone is a narrow bump (the slice average and the blur spread it, like on desktop),
        // and a quieter one is lower
        val loud = a.target[top]
        sine(a, 1000.0, 0.1)
        a.analyse(rate, points, null)
        assertTrue("0 dB $loud vs -20 dB ${a.target[top]}", a.target[top] < loud - 0.05f)
    }

    @Test
    fun levelsScaleWithLoudness() {
        // broadband (like music): 20 dB quieter is 20/72 of the view lower everywhere above the floor
        val a = Analyzer()
        val rnd = java.util.Random(7)
        val noise = FloatArray(a.n) { rnd.nextGaussian().toFloat() * 0.4f }
        noise.copyInto(a.samples)
        a.analyse(rate, points, null)
        val loud = a.target.copyOf()
        for (i in 0 until a.n) a.samples[i] = noise[i] * 0.1f
        a.analyse(rate, points, null)
        var checked = 0
        for (i in 0 until points) {
            if (a.target[i] < 0.15f) continue // on or near the floor: clamped, so not linear
            assertEquals("point $i", 20f / 72f, loud[i] - a.target[i], 0.02f)
            checked++
        }
        assertTrue("only $checked points above the floor", checked > points / 2)
    }

    @Test
    fun equaliserCurveIsShown() {
        val a = Analyzer()
        // broadband, like music: a pure tone leaves its neighbours on the floor and the blur would dilute the boost
        val rnd = java.util.Random(1)
        for (i in 0 until a.n) a.samples[i] = (rnd.nextGaussian() * 0.05).toFloat()
        a.analyse(rate, points, null)
        val at = pointOf(1000.0)
        val flat = a.target[at]
        val boost = EqSettings(enabled = true, autoPreamp = false, gains = List(BANDS) { if (it == 5) 12f else 0f })
        a.analyse(rate, points, boost)
        // +12 dB of a 72 dB view
        assertEquals(12f / 72f, a.target[at] - flat, 0.03f)
    }

    @Test
    fun axisRunsToNyquist() {
        assertEquals(1.0, Analyzer.xOf(22050.0, 44100), 1e-9)
        assertEquals(1.0, Analyzer.xOf(48000.0, 96000), 1e-9)
        assertEquals(0.0, Analyzer.xOf(20.0, 44100), 1e-9)
    }

    @Test
    fun curveRisesQuicklyAndFallsSlowly() {
        val a = Analyzer()
        sine(a, 440.0, 0.5)
        a.analyse(rate, points, null)
        val at = a.target.indices.maxBy { a.target[it] }
        val t = a.target[at]
        a.step(0.09f) // one rise time constant
        assertEquals(t * 0.632f, a.level[at], 0.02f)
        repeat(30) { a.step(0.033f) }
        val high = a.level[at]
        a.silence(points)
        a.step(0.09f) // the same time falls much less than it rose
        assertTrue("fell to ${a.level[at]} from $high", a.level[at] > high * 0.7f)
        repeat(200) { a.step(0.033f) }
        assertTrue(a.settled)
    }

    @Test
    fun snapshotFollowsTheSpeakerNotTheDecoder() {
        // two 0.5 s buffers: a ramp marks each frame with its index
        val frames = rate / 2
        val buf = FloatArray(frames)
        for (k in 0 until 2) {
            for (i in 0 until frames) buf[i] = (k * frames + i).toFloat()
            SpectrumBus.write(k * 500_000L, rate, buf, frames)
        }
        // the speaker is at 0.25 s although 1 s has been decoded
        SpectrumBus.position(250_000)
        val out = FloatArray(4096)
        assertEquals(rate, SpectrumBus.snapshot(out))
        assertEquals(rate / 4 - 1f, out.last(), 1f)
        assertEquals(out.last() - 4095, out.first(), 0f)
        // in the second buffer
        SpectrumBus.position(750_000)
        SpectrumBus.snapshot(out)
        assertEquals(rate * 3 / 4 - 1f, out.last(), 1f)
    }

    @Test
    fun keepsMovingBetweenSparsePositionReports() {
        // ExoPlayer may report the position only every few hundred ms
        val frames = rate * 2
        val buf = FloatArray(frames) { it.toFloat() }
        SpectrumBus.write(0, rate, buf, frames)
        SpectrumBus.playing(true)
        SpectrumBus.position(100_000)
        val out = FloatArray(4096)
        SpectrumBus.snapshot(out)
        val first = out.last()
        Thread.sleep(300)
        SpectrumBus.snapshot(out)
        val moved = (out.last() - first) / rate
        assertTrue("moved $moved s", moved in 0.28f..0.4f)
        // a report a little behind what was drawn doesn't pull the bars back
        SpectrumBus.position(300_000)
        SpectrumBus.snapshot(out)
        assertTrue(out.last() >= first + 0.28f * rate)
    }
}
