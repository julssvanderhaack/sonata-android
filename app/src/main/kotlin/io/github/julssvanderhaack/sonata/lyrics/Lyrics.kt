package io.github.julssvanderhaack.sonata.lyrics

import java.nio.charset.Charset
import kotlin.math.abs

data class LyricLine(val t: Double, val text: String)

data class Lyrics(
    val lines: List<LyricLine> = emptyList(),
    val synced: Boolean = false,
    val instrumental: Boolean = false,
    val source: Source = Source.NONE,
) {
    val isEmpty get() = lines.isEmpty() && !instrumental

    enum class Source { NONE, FILE, TAG, LRCLIB }

    /** Index of the line being sung at [sec] (already offset), or -1 before the first one. */
    fun lineAt(sec: Double): Int {
        if (!synced || lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].t <= sec) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)]""")
private val OFFSET = Regex("""^\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)
private val META = Regex("""^\[[a-zA-Z#]+:.*]$""")
private val WORD_STAMP = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

/** LRC if it has timestamps, plain text otherwise. Same rules as desktop Sonata (lyrics.cpp). */
fun parseLyrics(text: String, source: Lyrics.Source = Lyrics.Source.NONE): Lyrics {
    var offset = 0.0
    val timed = ArrayList<LyricLine>()
    val plain = ArrayList<LyricLine>()
    for (raw in text.replace("\r", "").split('\n')) {
        val line = raw.trim()
        OFFSET.find(line)?.let {
            offset = it.groupValues[1].toDouble() / 1000.0
            continue
        }
        val ts = ArrayList<Double>()
        var pos = 0
        while (true) {
            val m = STAMP.matchAt(line, pos) ?: break
            ts += m.groupValues[1].toInt() * 60 + m.groupValues[2].replace(':', '.').toDouble()
            pos = m.range.last + 1
            while (pos < line.length && line[pos].isWhitespace()) pos++
        }
        if (ts.isNotEmpty()) {
            val body = line.substring(pos).replace(WORD_STAMP, "").trim()
            ts.forEach { timed += LyricLine(it, body) }
        } else if (!META.matches(line)) {
            plain += LyricLine(0.0, line)
        }
    }
    if (timed.isNotEmpty()) {
        return Lyrics(timed.sortedBy { it.t }.map { it.copy(t = it.t - offset) }, synced = true, source = source)
    }
    val lines = plain.dropWhile { it.text.isEmpty() }.dropLastWhile { it.text.isEmpty() }
    return Lyrics(lines, synced = false, source = source)
}

/** One lrclib.net search hit. */
data class Candidate(val synced: String, val plain: String, val instrumental: Boolean, val duration: Double)

/** The closest version by length (±15 s), preferring synced lyrics — same scoring as desktop. */
fun chooseCandidate(list: List<Candidate>, duration: Double): Candidate? =
    list.filter { it.synced.isNotEmpty() || it.plain.isNotEmpty() || it.instrumental }
        .map { c -> c to (if (duration > 0) abs(c.duration - duration) else 0.0) }
        .filter { it.second <= 15 }
        .minByOrNull { (c, diff) -> diff + if (c.synced.isEmpty()) 30 else 0 }
        ?.first

/** Text of an ID3v2 USLT frame body: encoding, language, descriptor, then the lyrics. */
fun parseUslt(data: ByteArray): String? {
    if (data.size < 5) return null
    val enc = data[0].toInt()
    val charset: Charset = when (enc) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        else -> Charsets.UTF_8
    }
    val wide = enc == 1 || enc == 2
    var i = 4 // skip encoding + 3-byte language
    // skip the content descriptor up to its terminator (one or two zero bytes)
    if (wide) {
        while (i + 1 < data.size && !(data[i].toInt() == 0 && data[i + 1].toInt() == 0)) i += 2
        i += 2
    } else {
        while (i < data.size && data[i].toInt() != 0) i++
        i += 1
    }
    if (i >= data.size) return null
    // UTF-16 with BOM: the descriptor's BOM doesn't carry over, the text has its own
    return String(data, i, data.size - i, charset).trimEnd('\u0000').takeIf { it.isNotBlank() }
}
