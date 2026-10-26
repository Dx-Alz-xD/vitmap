package com.vitmap.core.convert

/** One subtitle cue, times in milliseconds. */
data class Cue(val start: Long, val end: Long, val text: String)

enum class SubtitleFormat(val ext: String, val label: String) {
    SRT("srt", "SubRip (SRT)"),
    VTT("vtt", "WebVTT"),
    ASS("ass", "Advanced SubStation (ASS)"),
    SSA("ssa", "SubStation Alpha (SSA)"),
    SBV("sbv", "YouTube SBV"),
    LRC("lrc", "Lyrics (LRC)"),
    TXT("txt", "Plain text"),
    ;

    companion object {
        fun of(ext: String): SubtitleFormat? = entries.firstOrNull { it.ext == ext.lowercase() }
    }
}

object Subtitles {
    private val timeRe = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})""")

    private fun parseTime(s: String): Long? {
        val m = timeRe.find(s.trim()) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: 0
        val min = m.groupValues[2].toLong()
        val sec = m.groupValues[3].toLong()
        val frac = m.groupValues[4].padEnd(3, '0').take(3).toLong()
        return ((h * 60 + min) * 60 + sec) * 1000 + frac
    }

    fun parse(text: String, format: SubtitleFormat): List<Cue> = when (format) {
        SubtitleFormat.SRT, SubtitleFormat.VTT -> parseBlocks(text)
        SubtitleFormat.ASS, SubtitleFormat.SSA -> parseAss(text)
        SubtitleFormat.SBV -> parseSbv(text)
        SubtitleFormat.LRC -> parseLrc(text)
        SubtitleFormat.TXT -> text.lines().filter { it.isNotBlank() }.mapIndexed { i, l -> Cue(i * 3000L, i * 3000L + 2900, l.trim()) }
    }

    private fun parseBlocks(text: String): List<Cue> {
        val cues = ArrayList<Cue>()
        val blocks = text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n\\s*\n"))
        for (b in blocks) {
            val lines = b.lines().filter { it.isNotBlank() }
            val idx = lines.indexOfFirst { "-->" in it }
            if (idx < 0) continue
            val (a, z) = lines[idx].split("-->", limit = 2)
            val start = parseTime(a) ?: continue
            val end = parseTime(z.trim().split(' ').first()) ?: continue
            cues += Cue(start, end, lines.drop(idx + 1).joinToString("\n").replace(Regex("</?[a-zA-Z][^>]*>"), ""))
        }
        return cues
    }

    private fun parseAss(text: String): List<Cue> {
        var fields = listOf("Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV", "Effect", "Text")
        val cues = ArrayList<Cue>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("Format:", true) && cues.isEmpty()) {
                fields = line.substringAfter(':').split(',').map { it.trim() }
            } else if (line.startsWith("Dialogue:", true)) {
                val parts = line.substringAfter(':').split(',', limit = fields.size)
                fun f(name: String) = parts.getOrNull(fields.indexOfFirst { it.equals(name, true) })?.trim()
                val start = f("Start")?.let { parseAssTime(it) } ?: continue
                val end = f("End")?.let { parseAssTime(it) } ?: continue
                val t = (f("Text") ?: "").replace(Regex("\\{[^}]*\\}"), "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                cues += Cue(start, end, t)
            }
        }
        return cues
    }

    private fun parseAssTime(s: String): Long? {
        val m = Regex("""(\d+):(\d{2}):(\d{2})[.](\d{1,2})""").find(s) ?: return null
        return ((m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 60 + m.groupValues[3].toLong()) * 1000 + m.groupValues[4].padEnd(2, '0').toLong() * 10
    }

    private fun parseSbv(text: String): List<Cue> {
        val cues = ArrayList<Cue>()
        for (b in text.replace("\r\n", "\n").split(Regex("\n\\s*\n"))) {
            val lines = b.lines().filter { it.isNotBlank() }
            if (lines.isEmpty() || ',' !in lines[0]) continue
            val (a, z) = lines[0].split(',', limit = 2)
            val start = parseTime(a) ?: continue
            val end = parseTime(z) ?: continue
            cues += Cue(start, end, lines.drop(1).joinToString("\n"))
        }
        return cues
    }

    private fun parseLrc(text: String): List<Cue> {
        val tag = Regex("""\[(\d+):(\d{2})(?:[.:](\d{1,3}))?]""")
        val stamped = ArrayList<Pair<Long, String>>()
        for (line in text.lines()) {
            val stamps = tag.findAll(line).toList()
            if (stamps.isEmpty()) continue
            val lyric = line.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) {
                val t = (m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000 + (m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0)
                stamped += t to lyric
            }
        }
        stamped.sortBy { it.first }
        return stamped.mapIndexed { i, (t, l) -> Cue(t, stamped.getOrNull(i + 1)?.first ?: (t + 4000), l) }.filter { it.text.isNotEmpty() }
    }

    private fun fmt(ms: Long, sep: Char, hourDigits: Int = 2): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        val f = ms % 1000
        return "%0${hourDigits}d:%02d:%02d%c%03d".format(h, m, s, sep, f)
    }

    fun write(cues: List<Cue>, format: SubtitleFormat): String = buildString {
        when (format) {
            SubtitleFormat.SRT -> cues.forEachIndexed { i, c ->
                append(i + 1).append('\n').append(fmt(c.start, ',')).append(" --> ").append(fmt(c.end, ',')).append('\n').append(c.text).append("\n\n")
            }
            SubtitleFormat.VTT -> {
                append("WEBVTT\n\n")
                for (c in cues) append(fmt(c.start, '.')).append(" --> ").append(fmt(c.end, '.')).append('\n').append(c.text).append("\n\n")
            }
            SubtitleFormat.ASS, SubtitleFormat.SSA -> {
                val ass = format == SubtitleFormat.ASS
                append("[Script Info]\nScriptType: ").append(if (ass) "v4.00+" else "v4.00").append("\nPlayResX: 1920\nPlayResY: 1080\n\n")
                append(if (ass) "[V4+ Styles]\n" else "[V4 Styles]\n")
                append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
                append("Style: Default,Arial,64,&H00FFFFFF,&H000000FF,&H00000000,&H64000000,0,0,0,0,100,100,0,0,1,3,1,2,40,40,50,1\n\n")
                append("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
                for (c in cues) {
                    fun t(ms: Long) = "%d:%02d:%02d.%02d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000 / 10)
                    append("Dialogue: 0,").append(t(c.start)).append(',').append(t(c.end)).append(",Default,,0,0,0,,").append(c.text.replace("\n", "\\N")).append('\n')
                }
            }
            SubtitleFormat.SBV -> for (c in cues) {
                fun t(ms: Long) = "%d:%02d:%02d.%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
                append(t(c.start)).append(',').append(t(c.end)).append('\n').append(c.text).append("\n\n")
            }
            SubtitleFormat.LRC -> for (c in cues) {
                append("[%02d:%02d.%02d]".format(c.start / 60_000, c.start / 1000 % 60, c.start % 1000 / 10)).append(c.text.replace('\n', ' ')).append('\n')
            }
            SubtitleFormat.TXT -> for (c in cues) append(c.text).append('\n')
        }
    }

    /** Shifts every cue by [offsetMs] and stretches by [factor] (for frame-rate fixes). */
    fun retime(cues: List<Cue>, offsetMs: Long = 0, factor: Double = 1.0): List<Cue> =
        cues.map { Cue(((it.start * factor) + offsetMs).toLong().coerceAtLeast(0), ((it.end * factor) + offsetMs).toLong().coerceAtLeast(0), it.text) }
}
