package io.github.julssvanderhaack.sonata.spectrum

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteOrder

/**
 * What the spectrum reads: the decoded audio as mono floats, each buffer
 * tagged with its media time, plus where the speaker is right now. The UI
 * takes the block that ends at the speaker's position, so the bars match what
 * is heard and not what was decoded ~0.5 s earlier (the AudioTrack buffer).
 */
object SpectrumBus {
    /** Set by the spectrum view while it is on screen; nothing is copied otherwise. */
    @Volatile var enabled = false

    private const val RING = 1 shl 17          // ~3 s at 44.1 kHz, well past the sink's lag
    private const val ANCHORS = 512
    private val lock = Any()
    private val ring = FloatArray(RING)
    private var head = 0L                      // frames written since the last flush
    private val anchorFrame = LongArray(ANCHORS)
    private val anchorUs = LongArray(ANCHORS)
    private var anchors = 0L
    private var rate = 44100

    @Volatile private var positionUs = C.TIME_UNSET
    @Volatile private var positionAtNs = 0L
    @Volatile private var playing = false
    private var shownUs = C.TIME_UNSET          // last position drawn, so the view never steps back

    internal fun write(pts: Long, sampleRate: Int, mono: FloatArray, frames: Int) = synchronized(lock) {
        if (sampleRate != rate) clearLocked()
        rate = sampleRate
        val a = (anchors % ANCHORS).toInt()
        anchorFrame[a] = head
        anchorUs[a] = pts
        anchors++
        for (i in 0 until frames) ring[((head + i) and (RING - 1).toLong()).toInt()] = mono[i]
        head += frames
    }

    internal fun position(us: Long) {
        positionUs = us
        positionAtNs = System.nanoTime()
    }

    internal fun playing(on: Boolean) {
        playing = on
    }

    internal fun clear() = synchronized(lock) { clearLocked() }

    private fun clearLocked() {
        head = 0
        anchors = 0
        positionUs = C.TIME_UNSET
        shownUs = C.TIME_UNSET
    }

    /**
     * Copies the [out].size frames heard last into [out] and returns the
     * sample rate, or 0 when there is nothing to show yet.
     */
    fun snapshot(out: FloatArray): Int = synchronized(lock) {
        val n = out.size
        var pos = positionUs
        if (pos == C.TIME_UNSET || anchors == 0L || head < n) return 0
        // ExoPlayer's dynamic scheduling only asks for the position when the
        // AudioTrack wants data, which can be 0.5 s apart: run on the wall clock in between
        if (playing) pos += ((System.nanoTime() - positionAtNs) / 1000).coerceIn(0, 1_000_000)
        // a fresh, slightly earlier report mustn't make the bars stutter backwards
        if (shownUs != C.TIME_UNSET && pos < shownUs && shownUs - pos < 150_000) pos = shownUs
        shownUs = pos
        // latest buffer that started at or before the speaker's position
        val oldest = maxOf(0L, anchors - ANCHORS)
        var i = anchors - 1
        while (i > oldest && anchorUs[(i % ANCHORS).toInt()] > pos) i--
        val slot = (i % ANCHORS).toInt()
        var end = anchorFrame[slot] + (pos - anchorUs[slot]).coerceAtLeast(0) * rate / 1_000_000
        end = end.coerceIn(n.toLong(), head)
        if (end - n < head - RING) end = head - RING + n
        for (k in 0 until n) out[k] = ring[((end - n + k) and (RING - 1).toLong()).toInt()]
        rate
    }
}

/**
 * Wraps ExoPlayer's sink to feed [SpectrumBus]: decoded PCM going in (with its
 * media time) and the playback position coming out. Audio passes through untouched.
 */
@UnstableApi
class SpectrumSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    private var encoding = C.ENCODING_INVALID
    private var channels = 0
    private var rate = 0
    private var fresh = true // the next handleBuffer call brings a new buffer, not a retry
    private var mono = FloatArray(0)

    override fun configure(config: AudioSink.AudioSinkConfig) {
        super.configure(config)
        encoding = config.format.pcmEncoding
        channels = config.format.channelCount
        rate = config.format.sampleRate
        fresh = true
    }

    override fun handleBuffer(buffer: java.nio.ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (fresh && SpectrumBus.enabled && channels > 0 && rate > 0) capture(buffer, presentationTimeUs)
        val done = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        fresh = done
        return done
    }

    private fun capture(buffer: java.nio.ByteBuffer, pts: Long) {
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val p = b.position()
        val ch = channels
        val frames = when (encoding) {
            C.ENCODING_PCM_16BIT -> b.remaining() / (2 * ch)
            C.ENCODING_PCM_FLOAT -> b.remaining() / (4 * ch)
            else -> return // 24/32-bit integer PCM doesn't come out of the platform decoders
        }
        if (mono.size < frames) mono = FloatArray(frames)
        if (encoding == C.ENCODING_PCM_16BIT) {
            for (i in 0 until frames) {
                var s = 0
                for (c in 0 until ch) s += b.getShort(p + (i * ch + c) * 2)
                mono[i] = s / (32768f * ch)
            }
        } else {
            for (i in 0 until frames) {
                var s = 0f
                for (c in 0 until ch) s += b.getFloat(p + (i * ch + c) * 4)
                mono[i] = s / ch
            }
        }
        SpectrumBus.write(pts, rate, mono, frames)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val us = super.getCurrentPositionUs(sourceEnded)
        if (us != AudioSink.CURRENT_POSITION_NOT_SET) SpectrumBus.position(us)
        return us
    }

    override fun play() {
        super.play()
        SpectrumBus.playing(true)
    }

    override fun pause() {
        super.pause()
        SpectrumBus.playing(false)
    }

    override fun flush() {
        super.flush()
        fresh = true
        SpectrumBus.clear()
    }

    override fun reset() {
        super.reset()
        fresh = true
        SpectrumBus.playing(false)
        SpectrumBus.clear()
    }
}
