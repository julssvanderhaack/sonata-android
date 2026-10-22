package io.github.julssvanderhaack.sonata

import io.github.julssvanderhaack.sonata.eq.BANDS
import io.github.julssvanderhaack.sonata.eq.BUILTIN_PRESETS
import io.github.julssvanderhaack.sonata.eq.EqPreset
import io.github.julssvanderhaack.sonata.eq.EqSettings
import io.github.julssvanderhaack.sonata.eq.curveDb
import io.github.julssvanderhaack.sonata.eq.curvePeakDb
import io.github.julssvanderhaack.sonata.eq.peaking
import io.github.julssvanderhaack.sonata.lyrics.Candidate
import io.github.julssvanderhaack.sonata.lyrics.chooseCandidate
import io.github.julssvanderhaack.sonata.lyrics.parseLyrics
import io.github.julssvanderhaack.sonata.lyrics.parseUslt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LyricsParseTest {
    @Test
    fun syncedLrc() {
        val l = parseLyrics(
            """
            [ar:Queen]
            [offset:+500]
            [00:12.50]Is this the real life?
            [00:17.00][01:30.00]Is this just fantasy?
            [00:20.1]<00:20.10>Caught <00:21.00>in a landslide
            """.trimIndent(),
        )
        assertTrue(l.synced)
        assertEquals(4, l.lines.size)
        assertEquals(12.0, l.lines[0].t, 1e-9) // offset +500 ms → earlier
        assertEquals("Caught in a landslide", l.lines[2].text)
        assertEquals(89.5, l.lines[3].t, 1e-9)
        assertEquals(-1, l.lineAt(5.0))
        assertEquals(0, l.lineAt(13.0))
        assertEquals(2, l.lineAt(60.0))
        assertEquals(3, l.lineAt(200.0))
    }

    @Test
    fun plainText() {
        val l = parseLyrics("\n\nLínea uno\n\nLínea dos\n\n")
        assertFalse(l.synced)
        assertEquals(listOf("Línea uno", "", "Línea dos"), l.lines.map { it.text })
    }

    @Test
    fun usltFrames() {
        val latin = byteArrayOf(0) + "spa".toByteArray() + "desc".toByteArray() + 0 + "Hola".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Hola", parseUslt(latin))
        val utf8 = byteArrayOf(3) + "eng".toByteArray() + 0 + "Canción ñ".toByteArray()
        assertEquals("Canción ñ", parseUslt(utf8))
        val utf16 = byteArrayOf(1) + "eng".toByteArray() + "d".toByteArray(Charsets.UTF_16) + 0 + 0 + "Letra".toByteArray(Charsets.UTF_16)
        assertEquals("Letra", parseUslt(utf16))
        assertNull(parseUslt(byteArrayOf(0, 1)))
    }

    @Test
    fun searchPrefersSyncedCloseVersion() {
        val list = listOf(
            Candidate("", "plain", false, 200.0),
            Candidate("[00:01]x", "", false, 205.0),
            Candidate("[00:01]live", "", false, 260.0), // a live version: too long
        )
        assertEquals(205.0, chooseCandidate(list, 201.0)!!.duration, 0.0)
        assertNull(chooseCandidate(listOf(Candidate("[00:01]x", "", false, 300.0)), 200.0))
    }
}

class EqTest {
    @Test
    fun zeroGainIsIdentity() {
        assertTrue(peaking(1000.0, 0.0, 44100.0).identity)
        assertFalse(peaking(1000.0, 3.0, 44100.0).identity)
    }

    @Test
    fun curveHitsTheBandGain() {
        val g = List(BANDS) { if (it == 5) 6f else 0f } // +6 dB at 1 kHz
        assertEquals(6.0, curveDb(g, 1000.0), 0.05)
        assertTrue(abs(curveDb(g, 20.0)) < 0.5)
        assertEquals(6.0, curvePeakDb(g), 0.2)
        assertEquals(0.0, curvePeakDb(List(BANDS) { -3f }), 0.0)
    }

    @Test
    fun autoPreampPreventsClipping() {
        val rock = BUILTIN_PRESETS.first { it.name == "Rock" }
        val s = EqSettings(enabled = true, gains = rock.gains)
        // boosted curve × preamp stays at or below unity everywhere
        assertTrue(s.preampLinear() < 1f)
        assertEquals(1f, EqSettings(enabled = false, gains = rock.gains).preampLinear())
        assertEquals("Rock", s.presetName())
        assertNull(s.copy(gains = s.gains.toMutableList().also { it[0] = 1.5f }).presetName())
    }

    @Test
    fun serializationRoundTrip() {
        val s = EqSettings(
            enabled = true, preamp = -1.5f, autoPreamp = false,
            gains = List(BANDS) { it * 0.5f - 2f },
            custom = listOf(EqPreset("Mi coche", List(BANDS) { 1f }), EqPreset("Con|barra", List(BANDS) { 0f })),
        )
        val back = EqSettings.parse(s.serialize())
        assertEquals(s.copy(custom = listOf(s.custom[0], EqPreset("Con/barra", s.custom[1].gains))), back)
        assertEquals(EqSettings(), EqSettings.parse("basura"))
        assertEquals(EqSettings(), EqSettings.parse(null))
    }
}
