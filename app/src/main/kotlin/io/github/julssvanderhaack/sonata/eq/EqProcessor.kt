package io.github.julssvanderhaack.sonata.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** The live settings, shared by the UI (writes) and the audio thread (reads). Same process, so a flow is enough. */
object EqBus {
    val settings = MutableStateFlow(EqSettings())
}

/**
 * Runs the equaliser inside ExoPlayer's audio sink, after decoding and before
 * the AudioTrack. With the EQ off, or on but flat, samples pass through
 * untouched. Settings changes are picked up at the next buffer.
 */
@UnstableApi
class EqProcessor : BaseAudioProcessor() {
    private var applied: EqSettings? = null
    private var appliedRate = 0
    private var coef: Array<Biquad> = emptyArray()
    private var active = false
    private var targetGain = 1f
    private var gain = 1f
    private var z1 = DoubleArray(0)
    private var z2 = DoubleArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        z1 = DoubleArray(BANDS * inputAudioFormat.channelCount)
        z2 = DoubleArray(BANDS * inputAudioFormat.channelCount)
        applied = null
        return inputAudioFormat
    }

    private fun refresh() {
        val s = EqBus.settings.value
        val rate = inputAudioFormat.sampleRate
        if (s === applied && rate == appliedRate) return
        val newCoef = Array(BANDS) { b -> peaking(BAND_HZ[b], if (s.enabled) s.gains[b].toDouble() else 0.0, rate.toDouble()) }
        val ch = inputAudioFormat.channelCount
        for (b in 0 until BANDS) {
            // a band coming back from 0 dB restarts clean
            if (coef.isEmpty() || coef[b].identity) for (c in 0 until ch) { z1[b * ch + c] = 0.0; z2[b * ch + c] = 0.0 }
        }
        coef = newCoef
        active = s.enabled && !s.flat
        targetGain = s.preampLinear()
        applied = s
        appliedRate = rate
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        refresh()
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        if (!active && gain == 1f && targetGain == 1f) {
            out.put(inputBuffer) // bit-perfect path
            out.flip()
            return
        }
        val ch = inputAudioFormat.channelCount
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerFrame = ch * if (float) 4 else 2
        val frames = size / bytesPerFrame
        val inBuf = inputBuffer.order(ByteOrder.nativeOrder())
        // ramp the gain over the buffer so preset changes don't click
        val step = (targetGain - gain) / frames.coerceAtLeast(1)
        var g = gain
        for (i in 0 until frames) {
            g += step
            for (c in 0 until ch) {
                var x = if (float) inBuf.getFloat().toDouble() else inBuf.getShort() / 32768.0
                x *= g
                if (active) for (b in 0 until BANDS) {
                    val k = coef[b]
                    if (k.identity) continue
                    val idx = b * ch + c
                    val y = k.b0 * x + z1[idx]
                    z1[idx] = k.b1 * x - k.a1 * y + z2[idx]
                    z2[idx] = k.b2 * x - k.a2 * y
                    x = y
                }
                // safety clip so a hot EQ never wraps
                val y = x.coerceIn(-1.0, 1.0)
                if (float) out.putFloat(y.toFloat()) else out.putShort((y * 32767.0).toInt().toShort())
            }
        }
        gain = targetGain
        // flush denormals so silence doesn't cost CPU
        for (i in z1.indices) {
            if (abs(z1[i]) < 1e-25) z1[i] = 0.0
            if (abs(z2[i]) < 1e-25) z2[i] = 0.0
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    @Deprecated("Media3 still calls this on older paths")
    override fun onFlush() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    override fun onReset() {
        z1 = DoubleArray(0)
        z2 = DoubleArray(0)
        coef = emptyArray()
        applied = null
        gain = 1f
        targetGain = 1f
    }
}
