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
import kotlin.math.sin

class SpectrumTest {
    private val rate = 44100
    private val bands = 48

    @After
    fun reset() {
        SpectrumBus.playing(false)
        SpectrumBus.clear()
    }

    private fun bandOf(hz: Double) = (ln(hz / SPECTRUM_LO_HZ) / ln(spectrumHiHz(rate) / SPECTRUM_LO_HZ) * bands).toInt()

    private fun sine(a: Analyzer, hz: Double, amp: Double) {
        for (i in 0 until a.n) a.samples[i] = (amp * sin(2 * PI * hz * i / rate)).toFloat()
    }

    @Test
    fun sinePeaksInItsBand() {
        val a = Analyzer()
        sine(a, 1000.0, 1.0)
        a.analyse(rate, bands, null)
        val top = a.target.indices.maxBy { a.target[it] }
        assertEquals(bandOf(1000.0), top)
        // full scale at 1 kHz is 0 dB, the top of the view
        assertEquals(1f, a.target[top], 0.02f)
        // a -36 dB sine sits half way up
        sine(a, 1000.0, 10.0.pow(-36.0 / 20))
        a.analyse(rate, bands, null)
        assertEquals(0.5f, a.target[top], 0.03f)
    }

    @Test
    fun equaliserCurveIsShown() {
        val a = Analyzer()
        sine(a, 1000.0, 0.01) // -40 dB
        a.analyse(rate, bands, null)
        val flat = a.target[bandOf(1000.0)]
        val boost = EqSettings(enabled = true, autoPreamp = false, gains = List(BANDS) { if (it == 5) 12f else 0f })
        a.analyse(rate, bands, boost)
        // +12 dB of a 72 dB view
        assertEquals(12f / 72f, a.target[bandOf(1000.0)] - flat, 0.02f)
    }

    @Test
    fun barsRiseAndFall() {
        val a = Analyzer()
        sine(a, 440.0, 0.5)
        a.analyse(rate, bands, null)
        a.step(0.033f)
        assertTrue(a.level.max() > 0.5f)
        a.silence(bands)
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
