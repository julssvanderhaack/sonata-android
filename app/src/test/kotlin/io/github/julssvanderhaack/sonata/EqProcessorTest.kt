package io.github.julssvanderhaack.sonata

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import io.github.julssvanderhaack.sonata.eq.BANDS
import io.github.julssvanderhaack.sonata.eq.EqBus
import io.github.julssvanderhaack.sonata.eq.EqProcessor
import io.github.julssvanderhaack.sonata.eq.EqSettings
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Feeds a real sine through the processor ExoPlayer uses and measures what comes out. */
@UnstableApi
class EqProcessorTest {
    private val rate = 44100

    @After
    fun reset() {
        EqBus.settings.value = EqSettings()
    }

    private fun sine(hz: Double, seconds: Double, amp: Double): ShortArray {
        val n = (rate * seconds).toInt()
        return ShortArray(n * 2) { i -> (amp * 32767 * sin(2 * PI * hz * (i / 2) / rate)).toInt().toShort() }
    }

    private fun run(input: ShortArray): ShortArray {
        val p = EqProcessor()
        p.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val out = ArrayList<Short>()
        // in chunks, like the audio sink does
        for (chunk in input.toList().chunked(4096)) {
            val buf = ByteBuffer.allocateDirect(chunk.size * 2).order(ByteOrder.nativeOrder())
            chunk.forEach { buf.putShort(it) }
            buf.flip()
            p.queueInput(buf)
            val o = p.output.order(ByteOrder.nativeOrder())
            while (o.hasRemaining()) out += o.getShort()
        }
        return out.toShortArray()
    }

    /** RMS in dB of the second half (filters settled). */
    private fun rmsDb(s: ShortArray): Double {
        val half = s.copyOfRange(s.size / 2, s.size)
        return 20 * log10(sqrt(half.sumOf { (it / 32768.0) * (it / 32768.0) } / half.size))
    }

    @Test
    fun offIsBitPerfect() {
        val x = sine(1000.0, 0.5, 0.5)
        assertArrayEquals(x, run(x))
        EqBus.settings.value = EqSettings(enabled = true) // on but flat
        assertArrayEquals(x, run(x))
    }

    @Test
    fun boostsItsBand() {
        val x = sine(1000.0, 0.5, 0.25)
        EqBus.settings.value = EqSettings(enabled = true, autoPreamp = false, gains = List(BANDS) { if (it == 5) 6f else 0f })
        assertEquals(6.0, rmsDb(run(x)) - rmsDb(x), 0.3)
        // far from 1 kHz the same setting barely changes anything
        val low = sine(40.0, 0.5, 0.25)
        assertEquals(0.0, rmsDb(run(low)) - rmsDb(low), 0.5)
    }

    @Test
    fun autoPreampCancelsTheBoost() {
        val x = sine(1000.0, 0.5, 0.25)
        EqBus.settings.value = EqSettings(enabled = true, autoPreamp = true, gains = List(BANDS) { if (it == 5) 6f else 0f })
        assertEquals(0.0, rmsDb(run(x)) - rmsDb(x), 0.5)
    }
}
