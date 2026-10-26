package com.vitmap.core.ytdl

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

// ---------------------------------------------------------------- what yt-dlp reports

data class YtFormat(
    val id: String,
    val ext: String,
    val vcodec: String?,
    val acodec: String?,
    val width: Int,
    val height: Int,
    val fps: Double,
    /** Total, video and audio bitrates in kbit/s; 0 when unknown. */
    val tbr: Double,
    val vbr: Double,
    val abr: Double,
    /** Exact or approximate size in bytes; 0 when unknown. */
    val size: Long,
    val note: String,
    val dynamicRange: String?,
    val language: String?,
    val protocol: String,
) {
    val hasVideo: Boolean get() = vcodec != null && vcodec != "none"
    val hasAudio: Boolean get() = acodec != null && acodec != "none"

    /** Quality as people say it: the short side of the frame, so 1080×1920 shorts are "1080p". */
    val quality: Int get() = if (width > 0 && height > 0) min(width, height) else height

    val codecLabel: String get() = Ytdl.codecName(if (hasVideo) vcodec else acodec)

    fun sizeFor(durationSec: Double): Long = when {
        size > 0 -> size
        tbr > 0 && durationSec > 0 -> (tbr * 1000 / 8 * durationSec).roundToLong()
        else -> 0
    }
}

data class YtChapter(val start: Double, val end: Double, val title: String)

data class YtEntry(val index: Int, val id: String, val title: String, val url: String, val duration: Double, val uploader: String?)

sealed interface YtInfo {
    val title: String
    val webpageUrl: String
}

data class YtVideo(
    val id: String,
    override val title: String,
    val uploader: String?,
    val channel: String?,
    val durationSec: Double,
    val thumbnail: String?,
    val views: Long,
    val uploadDate: String?,
    override val webpageUrl: String,
    val site: String,
    val isLive: Boolean,
    val formats: List<YtFormat>,
    val subtitles: List<String>,
    val autoCaptions: List<String>,
    val chapters: List<YtChapter>,
) : YtInfo {
    val videoFormats: List<YtFormat> get() = formats.filter { it.hasVideo }
    val audioFormats: List<YtFormat> get() = formats.filter { it.hasAudio && !it.hasVideo }

    /** Distinct qualities on offer, best first. */
    val qualities: List<Int> get() = videoFormats.map { it.quality }.filter { it > 0 }.distinct().sortedDescending()

    /** Rough download size for the best video at or under [quality] plus the best audio. */
    fun estimate(quality: Int, audioOnly: Boolean = false): Long {
        val audio = audioFormats.maxByOrNull { if (it.abr > 0) it.abr else it.tbr }?.sizeFor(durationSec) ?: 0L
        if (audioOnly) return audio
        val candidates = videoFormats.filter { quality <= 0 || it.quality in 1..quality }
        val bestQ = candidates.maxOfOrNull { it.quality } ?: return 0
        val video = candidates.filter { it.quality == bestQ }.map { it.sizeFor(durationSec) }.filter { it > 0 }.minOrNull() ?: return 0
        val muxed = candidates.firstOrNull { it.quality == bestQ }?.hasAudio == true
        return video + if (muxed) 0 else audio
    }
}

data class YtPlaylist(
    val id: String,
    override val title: String,
    val uploader: String?,
    override val webpageUrl: String,
    val thumbnail: String?,
    val entries: List<YtEntry>,
) : YtInfo

// ---------------------------------------------------------------- what the user asks for

@Serializable
enum class YtMode { VIDEO, AUDIO, CUSTOM }

@Serializable
enum class YtContainer(val ext: String, val label: String) {
    MP4("mp4", "MP4"), MKV("mkv", "MKV"), WEBM("webm", "WebM"), MOV("mov", "MOV")
}

@Serializable
enum class YtCodec(val label: String) {
    COMPATIBLE("Plays everywhere (H.264)"), BEST("Best quality (AV1/VP9)"), SMALLEST("Smallest file")
}

@Serializable
enum class YtAudio(val arg: String, val ext: String, val label: String, val lossless: Boolean, val coverArt: Boolean) {
    MP3("mp3", "mp3", "MP3", false, true),
    M4A("m4a", "m4a", "M4A (AAC)", false, true),
    OPUS("opus", "opus", "Opus", false, true),
    VORBIS("vorbis", "ogg", "Ogg Vorbis", false, true),
    FLAC("flac", "flac", "FLAC", true, true),
    WAV("wav", "wav", "WAV", true, false),
    ORIGINAL("best", "", "Original (no re-encode)", false, true),
}

@Serializable
enum class YtSubs { OFF, EMBED, FILE }

@Serializable
enum class YtSponsor { OFF, MARK, REMOVE }

@Serializable
data class YtRequest(
    val url: String,
    val mode: YtMode = YtMode.VIDEO,
    /** Maximum quality (short side in pixels); 0 = best available. */
    val quality: Int = 1080,
    val container: YtContainer = YtContainer.MP4,
    val codec: YtCodec = YtCodec.COMPATIBLE,
    val highFps: Boolean = true,
    val hdr: Boolean = false,
    val audio: YtAudio = YtAudio.MP3,
    /** 0 = best variable bitrate. */
    val audioKbps: Int = 0,
    /** Exact yt-dlp format selector for [YtMode.CUSTOM], e.g. "137+140". */
    val formatId: String = "",
    val nameTemplate: String = "{title}",
    val subs: YtSubs = YtSubs.OFF,
    val subLangs: List<String> = listOf("en"),
    val autoSubs: Boolean = false,
    val embedThumbnail: Boolean = true,
    val embedMetadata: Boolean = true,
    val embedChapters: Boolean = true,
    val sponsor: YtSponsor = YtSponsor.OFF,
    val sponsorCategories: List<String> = listOf("sponsor", "selfpromo", "interaction"),
    val sectionStart: Double = 0.0,
    val sectionEnd: Double = 0.0,
    val playlist: Boolean = false,
    /** yt-dlp --playlist-items syntax, e.g. "1,3,5-7"; empty = all. */
    val playlistItems: String = "",
    val splitChapters: Boolean = false,
    val rateLimitKbps: Int = 0,
    val fragments: Int = 4,
    val extraArgs: String = "",
)

/** Live progress parsed from our --progress-template lines. */
data class YtProgress(
    val fraction: Float?,
    val downloaded: Long,
    val total: Long,
    val speed: Double,
    val etaSec: Long,
    val playlistIndex: Int,
    val audioStream: Boolean,
)

object Ytdl {
    const val PROGRESS_PREFIX = "[vp]"

    val sponsorCategories = listOf(
        "sponsor" to "Sponsor",
        "selfpromo" to "Self-promotion",
        "interaction" to "Like/subscribe reminders",
        "intro" to "Intro",
        "outro" to "Endcards/credits",
        "preview" to "Previews/recaps",
        "music_offtopic" to "Non-music parts",
        "filler" to "Filler tangents",
    )

    val templateTokens = listOf(
        "{title}" to "Title",
        "{channel}" to "Channel",
        "{date}" to "Upload date",
        "{id}" to "Video ID",
        "{quality}" to "Quality",
        "{index}" to "Playlist #",
        "{playlist}" to "Playlist",
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun codecName(codec: String?): String {
        val c = codec?.lowercase() ?: return ""
        return when {
            c == "none" -> ""
            c.startsWith("avc") || c.startsWith("h264") -> "H.264"
            c.startsWith("hev") || c.startsWith("hvc") || c.startsWith("h265") -> "H.265"
            c.startsWith("vp09") || c.startsWith("vp9") -> "VP9"
            c.startsWith("av01") || c.startsWith("av1") -> "AV1"
            c.startsWith("vp8") -> "VP8"
            c.startsWith("mp4a") || c == "aac" -> "AAC"
            c.startsWith("opus") -> "Opus"
            c.startsWith("vorbis") -> "Vorbis"
            c.startsWith("mp3") -> "MP3"
            c.startsWith("flac") -> "FLAC"
            c.startsWith("ac-3") || c == "ac3" -> "AC-3"
            c.startsWith("ec-3") || c == "eac3" -> "E-AC-3"
            else -> c.substringBefore('.').uppercase()
        }
    }

    // ------------------------------------------------------------ parsing -J output

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.num(k: String): Double = (this[k] as? JsonPrimitive)?.doubleOrNull ?: 0.0
    private fun JsonObject.long(k: String): Long = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0L

    fun parse(text: String): YtInfo {
        // Skip any warnings printed before the JSON document.
        val start = Regex("(?m)^\\s*\\{").find(text)?.range?.first ?: throw IllegalArgumentException("yt-dlp returned no information")
        val end = text.lastIndexOf('}')
        if (end < start) throw IllegalArgumentException("yt-dlp returned incomplete information")
        val root = json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        return if (root.str("_type") == "playlist") playlist(root) else video(root)
    }

    private fun video(o: JsonObject): YtVideo {
        val duration = o.num("duration")
        val formats = (o["formats"] as? JsonArray).orEmpty().mapNotNull { el ->
            val f = el as? JsonObject ?: return@mapNotNull null
            val ext = f.str("ext") ?: ""
            val note = f.str("format_note") ?: ""
            if (ext == "mhtml" || note.contains("storyboard", true)) return@mapNotNull null
            YtFormat(
                id = f.str("format_id") ?: return@mapNotNull null,
                ext = ext,
                vcodec = f.str("vcodec"),
                acodec = f.str("acodec"),
                width = f.num("width").toInt(),
                height = f.num("height").toInt(),
                fps = f.num("fps"),
                tbr = f.num("tbr"),
                vbr = f.num("vbr"),
                abr = f.num("abr"),
                size = f.long("filesize").takeIf { it > 0 } ?: f.long("filesize_approx"),
                note = note,
                dynamicRange = f.str("dynamic_range"),
                language = f.str("language"),
                protocol = f.str("protocol") ?: "",
            )
        }.let { list ->
            // Some extractors give one format with codec fields missing; assume it has both.
            if (list.size == 1 && list[0].vcodec == null && list[0].acodec == null) listOf(list[0].copy(vcodec = "unknown", acodec = "unknown")) else list
        }
        val fallback = if (formats.isEmpty() && o.str("url") != null) {
            listOf(YtFormat(o.str("format_id") ?: "0", o.str("ext") ?: "mp4", o.str("vcodec") ?: "unknown", o.str("acodec") ?: "unknown", o.num("width").toInt(), o.num("height").toInt(), o.num("fps"), o.num("tbr"), 0.0, 0.0, o.long("filesize"), "", null, null, ""))
        } else formats
        fun langs(k: String) = (o[k] as? JsonObject)?.keys?.filter { it != "live_chat" }?.sorted().orEmpty()
        val chapters = (o["chapters"] as? JsonArray).orEmpty().mapNotNull { el ->
            val c = el as? JsonObject ?: return@mapNotNull null
            YtChapter(c.num("start_time"), c.num("end_time"), c.str("title") ?: "")
        }
        return YtVideo(
            id = o.str("id") ?: "",
            title = o.str("title") ?: o.str("fulltitle") ?: "Untitled",
            uploader = o.str("uploader") ?: o.str("channel"),
            channel = o.str("channel") ?: o.str("uploader"),
            durationSec = duration,
            thumbnail = o.str("thumbnail") ?: bestThumbnail(o),
            views = o.long("view_count"),
            uploadDate = o.str("upload_date"),
            webpageUrl = o.str("webpage_url") ?: o.str("original_url") ?: "",
            site = o.str("extractor_key") ?: o.str("extractor") ?: "",
            isLive = (o["is_live"] as? JsonPrimitive)?.contentOrNull == "true",
            formats = fallback,
            subtitles = langs("subtitles"),
            autoCaptions = langs("automatic_captions"),
            chapters = chapters,
        )
    }

    private fun bestThumbnail(o: JsonObject): String? =
        (o["thumbnails"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.maxByOrNull { it.num("width") * it.num("height") + it.num("preference") }?.str("url")

    private fun playlist(o: JsonObject): YtPlaylist {
        val entries = (o["entries"] as? JsonArray).orEmpty().mapIndexedNotNull { i, el ->
            val e = el as? JsonObject ?: return@mapIndexedNotNull null
            YtEntry(
                index = e.num("playlist_index").toInt().takeIf { it > 0 } ?: (i + 1),
                id = e.str("id") ?: "",
                title = e.str("title") ?: "Item ${i + 1}",
                url = e.str("url") ?: e.str("webpage_url") ?: "",
                duration = e.num("duration"),
                uploader = e.str("uploader") ?: e.str("channel"),
            )
        }
        return YtPlaylist(
            id = o.str("id") ?: "",
            title = o.str("title") ?: "Playlist",
            uploader = o.str("uploader") ?: o.str("channel"),
            webpageUrl = o.str("webpage_url") ?: o.str("original_url") ?: "",
            thumbnail = bestThumbnail(o),
            entries = entries,
        )
    }

    // ------------------------------------------------------------ file names

    /** Turns "{title} - {channel}" into a yt-dlp output template (literal % escaped). */
    fun outputTemplate(template: String): String {
        val t = template.ifBlank { "{title}" }
        val sb = StringBuilder()
        var i = 0
        while (i < t.length) {
            val token = templateTokens.firstOrNull { t.startsWith(it.first, i) }?.first
            if (token != null) {
                sb.append(
                    when (token) {
                        "{title}" -> "%(title)s"
                        "{channel}" -> "%(channel,uploader|Unknown)s"
                        "{date}" -> "%(upload_date>%Y-%m-%d|)s"
                        "{id}" -> "%(id)s"
                        "{quality}" -> "%(height&{}p|audio)s"
                        "{index}" -> "%(playlist_index|)s"
                        else -> "%(playlist_title,playlist|)s"
                    },
                )
                i += token.length
                continue
            }
            when (val c = t[i]) {
                '%' -> sb.append("%%")
                '/', '\\' -> sb.append('-')
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString().trim()
    }

    /** What [template] will produce for [video], for the live preview under the name field. */
    fun previewName(template: String, video: YtVideo?, playlist: YtPlaylist?, quality: Int, audioOnly: Boolean): String {
        val date = video?.uploadDate?.takeIf { it.length == 8 }?.let { "${it.substring(0, 4)}-${it.substring(4, 6)}-${it.substring(6)}" } ?: ""
        val q = if (audioOnly) "audio" else video?.qualities?.firstOrNull { quality <= 0 || it <= quality }?.let { "${it}p" } ?: if (quality > 0) "${quality}p" else ""
        var out = template.ifBlank { "{title}" }
        val values = mapOf(
            "{title}" to (video?.title ?: playlist?.entries?.firstOrNull()?.title ?: "Title"),
            "{channel}" to (video?.channel ?: playlist?.uploader ?: "Channel"),
            "{date}" to date,
            "{id}" to (video?.id ?: playlist?.entries?.firstOrNull()?.id ?: ""),
            "{quality}" to q,
            "{index}" to if (playlist != null) "1" else "",
            "{playlist}" to (playlist?.title ?: ""),
        )
        for ((k, v) in values) out = out.replace(k, v)
        return out.replace('/', '-').replace('\\', '-').trim()
    }

    // ------------------------------------------------------------ building the command line

    fun formatTime(sec: Double): String {
        val total = sec.coerceAtLeast(0.0)
        val h = (total / 3600).toInt()
        val m = ((total % 3600) / 60).toInt()
        val s = total % 60
        return "%d:%02d:%06.3f".format(h, m, s)
    }

    /** Splits [s] like a shell would: spaces separate, quotes group, backslash escapes. */
    fun splitArgs(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var any = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && quote != '\'' -> { cur.append(s[i + 1]); any = true; i++ }
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') -> { quote = c; any = true }
                quote == null && c.isWhitespace() -> { if (any) { out += cur.toString(); cur.setLength(0); any = false } }
                else -> { cur.append(c); any = true }
            }
            i++
        }
        if (any) out += cur.toString()
        return out
    }

    fun progressTemplate(): String =
        "download:$PROGRESS_PREFIX%(progress._percent_str)s|%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s|%(info.playlist_index)s|%(info.vcodec)s"

    fun parseProgress(line: String): YtProgress? {
        val at = line.indexOf(PROGRESS_PREFIX)
        if (at < 0) return null
        val p = line.substring(at + PROGRESS_PREFIX.length).split('|')
        if (p.size < 8) return null
        fun n(s: String) = s.trim().toDoubleOrNull()
        val downloaded = n(p[1])?.toLong() ?: 0
        val total = (n(p[2]) ?: n(p[3]))?.toLong() ?: 0
        val pct = n(p[0].trim().removeSuffix("%"))
        val fraction = when {
            total > 0 -> (downloaded.toDouble() / total).toFloat()
            pct != null -> (pct / 100).toFloat()
            else -> null
        }?.coerceIn(0f, 1f)
        return YtProgress(fraction, downloaded, total, n(p[4]) ?: 0.0, n(p[5])?.toLong() ?: -1, n(p[6])?.toInt() ?: 0, p[7].trim() == "none")
    }

    /** Maps yt-dlp's log prefixes to a human stage name. */
    fun stageFor(line: String): String? {
        val t = line.trim()
        return when {
            t.startsWith("[Merger]") -> "Merging video and audio"
            t.startsWith("[ExtractAudio]") -> "Converting audio"
            t.startsWith("[EmbedThumbnail]") || t.startsWith("[ThumbnailsConvertor]") -> "Adding cover art"
            t.startsWith("[Metadata]") -> "Writing tags and chapters"
            t.startsWith("[SponsorBlock]") || t.startsWith("[ModifyChapters]") -> "Handling sponsor segments"
            t.startsWith("[VideoRemuxer]") -> "Changing container"
            t.startsWith("[VideoConvertor]") -> "Re-encoding video"
            t.startsWith("[EmbedSubtitle]") -> "Embedding subtitles"
            t.startsWith("[SubtitlesConvertor]") -> "Converting subtitles"
            t.startsWith("[SplitChapters]") -> "Splitting chapters"
            t.startsWith("[Fixup") -> "Fixing up the file"
            t.startsWith("[info]") && "Downloading" in t && "format" in t -> "Picking formats"
            t.startsWith("[download] Downloading item") || t.startsWith("[download] Downloading video") -> t.removePrefix("[download] ").substringBefore(" of ").let { it.replace("Downloading item", "Item").replace("Downloading video", "Video") } + " of " + t.substringAfter(" of ", "").trim()
            t.startsWith("[download] Destination:") -> "Downloading"
            t.startsWith("[youtube]") || t.startsWith("[generic]") || (t.startsWith("[") && "Downloading webpage" in t) -> "Reading the page"
            else -> null
        }
    }

    fun args(r: YtRequest, outDir: String): List<String> = buildList {
        addAll(listOf("--newline", "--progress", "--color", "never", "--progress-template", progressTemplate()))
        addAll(listOf("-P", outDir, "-P", "temp:$outDir/.parts", "-o", outputTemplate(r.nameTemplate) + ".%(ext)s"))
        addAll(listOf("--no-mtime", "--windows-filenames", "--trim-filenames", "150", "-N", r.fragments.coerceIn(1, 16).toString()))
        add(if (r.playlist) "--yes-playlist" else "--no-playlist")
        if (r.playlist && r.playlistItems.isNotBlank()) addAll(listOf("--playlist-items", r.playlistItems.filter { it.isDigit() || it in ",-:" }))
        val audioOnly = r.mode == YtMode.AUDIO
        when (r.mode) {
            YtMode.VIDEO -> {
                val sort = buildList {
                    if (!r.hdr) add("hdr:SDR")
                    add(if (r.quality > 0) "res:${r.quality}" else "res")
                    add(if (r.highFps) "fps" else "fps:30")
                    when {
                        r.container == YtContainer.WEBM -> { add("vcodec:vp9"); add("acodec:opus") }
                        r.codec == YtCodec.COMPATIBLE -> add("+codec:avc:m4a")
                        r.codec == YtCodec.SMALLEST -> { add("+size"); add("+br") }
                        else -> {}
                    }
                }
                addAll(listOf("-f", "bv*+ba/b", "-S", sort.joinToString(",")))
                addAll(listOf("--merge-output-format", r.container.ext))
                if (r.container != YtContainer.WEBM) addAll(listOf("--remux-video", r.container.ext))
            }
            YtMode.AUDIO -> {
                addAll(listOf("-f", "ba/b", "-x", "--audio-format", r.audio.arg))
                if (!r.audio.lossless) addAll(listOf("--audio-quality", if (r.audioKbps > 0) "${r.audioKbps}K" else "0"))
            }
            YtMode.CUSTOM -> {
                addAll(listOf("-f", r.formatId.ifBlank { "bv*+ba/b" }))
                addAll(listOf("--merge-output-format", r.container.ext))
            }
        }
        val thumbOk = if (audioOnly) r.audio.coverArt else r.container != YtContainer.WEBM
        if (r.embedThumbnail && thumbOk) addAll(listOf("--embed-thumbnail", "--convert-thumbnails", "jpg"))
        if (r.embedMetadata) add("--embed-metadata")
        if (r.embedChapters) add("--embed-chapters")
        if (r.subs != YtSubs.OFF && r.subLangs.isNotEmpty()) {
            add("--write-subs")
            if (r.autoSubs) add("--write-auto-subs")
            addAll(listOf("--sub-langs", r.subLangs.joinToString(",")))
            if (r.subs == YtSubs.EMBED && !audioOnly) add("--embed-subs") else addAll(listOf("--convert-subs", "srt"))
        }
        if (r.sponsor != YtSponsor.OFF && r.sponsorCategories.isNotEmpty()) {
            addAll(listOf(if (r.sponsor == YtSponsor.REMOVE) "--sponsorblock-remove" else "--sponsorblock-mark", r.sponsorCategories.joinToString(",")))
        }
        if (r.sectionStart > 0 || r.sectionEnd > 0) {
            val end = if (r.sectionEnd > r.sectionStart) formatTime(r.sectionEnd) else "inf"
            addAll(listOf("--download-sections", "*${formatTime(r.sectionStart)}-$end", "--force-keyframes-at-cuts"))
        }
        if (r.splitChapters) add("--split-chapters")
        if (r.rateLimitKbps > 0) addAll(listOf("-r", "${r.rateLimitKbps}K"))
        addAll(splitArgs(r.extraArgs))
        add("--")
        add(r.url.trim())
    }

    /** Arguments for reading a link's details without downloading. */
    fun infoArgs(url: String, playlist: Boolean): List<String> = listOf("-J", "--flat-playlist", "--no-warnings", if (playlist) "--yes-playlist" else "--no-playlist", "--", url.trim())

    fun looksLikeUrl(s: String): Boolean = Regex("^(https?://)?([\\w-]+\\.)+[a-z]{2,}(/\\S*)?$", RegexOption.IGNORE_CASE).matches(s.trim())

    /** Pulls the first link out of shared text like "Watch this! https://youtu.be/abc". */
    fun extractUrl(text: String): String? = Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', ']', '"', '\'')

    fun hasPlaylist(url: String): Boolean = Regex("[?&]list=[\\w-]+").containsMatchIn(url) || "/playlist" in url

    fun formatSpeed(bytesPerSec: Double): String = when {
        bytesPerSec <= 0 -> ""
        bytesPerSec >= 1048576 -> "%.1f MB/s".format(bytesPerSec / 1048576)
        else -> "%.0f KB/s".format(max(1.0, bytesPerSec / 1024))
    }

    fun formatEta(sec: Long): String = when {
        sec < 0 -> ""
        sec >= 3600 -> "%d:%02d:%02d left".format(sec / 3600, sec % 3600 / 60, sec % 60)
        else -> "%d:%02d left".format(sec / 60, sec % 60)
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
