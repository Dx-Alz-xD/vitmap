package com.vitmap.app.feature.fileconvert

enum class Family(val label: String) {
    IMAGE("Images"),
    VIDEO("Video"),
    AUDIO("Audio"),
    DOCUMENT("Documents"),
    DATA("Data"),
    SUBTITLE("Subtitles"),
    ARCHIVE("Archives"),
}

enum class Engine { ANDROID, FFMPEG, PDF, KOTLIN }

/** A file format the converter can read and/or write. */
data class Fmt(
    val id: String,
    val ext: String,
    val label: String,
    val family: Family,
    val mime: String,
    val read: Boolean = true,
    val write: Boolean = true,
    /** True when writing this format needs the FFmpeg pack. */
    val needsFfmpeg: Boolean = false,
    val aliases: List<String> = emptyList(),
)

object Formats {
    val all: List<Fmt> = listOf(
        // Images: Android codecs where possible, FFmpeg for the rest.
        Fmt("jpg", "jpg", "JPEG", Family.IMAGE, "image/jpeg", aliases = listOf("jpeg", "jpe", "jfif")),
        Fmt("png", "png", "PNG", Family.IMAGE, "image/png"),
        Fmt("webp", "webp", "WebP", Family.IMAGE, "image/webp"),
        Fmt("webp-lossless", "webp", "WebP lossless", Family.IMAGE, "image/webp", read = false),
        Fmt("heic", "heic", "HEIC", Family.IMAGE, "image/heic", aliases = listOf("heif")),
        Fmt("avif", "avif", "AVIF", Family.IMAGE, "image/avif", needsFfmpeg = true),
        Fmt("gif-image", "gif", "GIF", Family.IMAGE, "image/gif", needsFfmpeg = true, aliases = listOf("gif")),
        Fmt("bmp", "bmp", "BMP", Family.IMAGE, "image/bmp", aliases = listOf("dib")),
        Fmt("tiff", "tiff", "TIFF", Family.IMAGE, "image/tiff", needsFfmpeg = true, aliases = listOf("tif")),
        Fmt("tga", "tga", "Targa", Family.IMAGE, "image/x-tga", needsFfmpeg = true),
        Fmt("ico", "ico", "Icon (ICO)", Family.IMAGE, "image/x-icon"),
        Fmt("qoi", "qoi", "QOI", Family.IMAGE, "image/qoi", needsFfmpeg = true),
        Fmt("ppm", "ppm", "PPM", Family.IMAGE, "image/x-portable-pixmap", needsFfmpeg = true, aliases = listOf("pgm", "pbm", "pnm", "pam")),
        Fmt("pcx", "pcx", "PCX", Family.IMAGE, "image/x-pcx", needsFfmpeg = true),
        Fmt("sgi", "sgi", "SGI", Family.IMAGE, "image/sgi", needsFfmpeg = true, aliases = listOf("rgb")),
        Fmt("jp2", "jp2", "JPEG 2000", Family.IMAGE, "image/jp2", needsFfmpeg = true, aliases = listOf("j2k")),
        Fmt("xbm", "xbm", "XBM", Family.IMAGE, "image/x-xbitmap", needsFfmpeg = true),
        Fmt("dpx", "dpx", "DPX", Family.IMAGE, "image/x-dpx", needsFfmpeg = true),
        Fmt("exr", "exr", "OpenEXR", Family.IMAGE, "image/x-exr", write = false, needsFfmpeg = true),
        Fmt("psd", "psd", "Photoshop (flattened)", Family.IMAGE, "image/vnd.adobe.photoshop", write = false, needsFfmpeg = true),
        Fmt("svg", "svg", "SVG (embedded image)", Family.IMAGE, "image/svg+xml", read = false),
        Fmt("dng", "dng", "Camera raw (DNG)", Family.IMAGE, "image/x-adobe-dng", write = false),

        // Video
        Fmt("mp4", "mp4", "MP4 (H.264)", Family.VIDEO, "video/mp4", needsFfmpeg = true),
        Fmt("mp4-hevc", "mp4", "MP4 (H.265)", Family.VIDEO, "video/mp4", read = false, needsFfmpeg = true),
        Fmt("mkv", "mkv", "Matroska (MKV)", Family.VIDEO, "video/x-matroska", needsFfmpeg = true),
        Fmt("webm", "webm", "WebM (VP9)", Family.VIDEO, "video/webm", needsFfmpeg = true),
        Fmt("webm-av1", "webm", "WebM (AV1)", Family.VIDEO, "video/webm", read = false, needsFfmpeg = true),
        Fmt("mov", "mov", "QuickTime (MOV)", Family.VIDEO, "video/quicktime", needsFfmpeg = true),
        Fmt("avi", "avi", "AVI", Family.VIDEO, "video/x-msvideo", needsFfmpeg = true),
        Fmt("flv", "flv", "Flash video (FLV)", Family.VIDEO, "video/x-flv", needsFfmpeg = true),
        Fmt("3gp", "3gp", "3GP", Family.VIDEO, "video/3gpp", needsFfmpeg = true, aliases = listOf("3g2")),
        Fmt("ts", "ts", "MPEG-TS", Family.VIDEO, "video/mp2t", needsFfmpeg = true, aliases = listOf("mts", "m2ts")),
        Fmt("mpg", "mpg", "MPEG-2", Family.VIDEO, "video/mpeg", needsFfmpeg = true, aliases = listOf("mpeg", "vob")),
        Fmt("wmv", "wmv", "Windows Media (WMV)", Family.VIDEO, "video/x-ms-wmv", needsFfmpeg = true, aliases = listOf("asf")),
        Fmt("ogv", "ogv", "Ogg Theora", Family.VIDEO, "video/ogg", needsFfmpeg = true),
        Fmt("m4v", "m4v", "M4V", Family.VIDEO, "video/x-m4v", needsFfmpeg = true),
        Fmt("gif", "gif", "Animated GIF", Family.VIDEO, "image/gif", needsFfmpeg = true),
        Fmt("webp-anim", "webp", "Animated WebP", Family.VIDEO, "image/webp", read = false, needsFfmpeg = true),
        Fmt("apng", "apng", "Animated PNG", Family.VIDEO, "image/apng", needsFfmpeg = true),

        // Audio
        Fmt("mp3", "mp3", "MP3", Family.AUDIO, "audio/mpeg", needsFfmpeg = true),
        Fmt("m4a", "m4a", "AAC (M4A)", Family.AUDIO, "audio/mp4", needsFfmpeg = true),
        Fmt("aac", "aac", "AAC (ADTS)", Family.AUDIO, "audio/aac", needsFfmpeg = true),
        Fmt("opus", "opus", "Opus", Family.AUDIO, "audio/ogg", needsFfmpeg = true),
        Fmt("ogg", "ogg", "Ogg Vorbis", Family.AUDIO, "audio/ogg", needsFfmpeg = true, aliases = listOf("oga")),
        Fmt("flac", "flac", "FLAC", Family.AUDIO, "audio/flac", needsFfmpeg = true),
        Fmt("wav", "wav", "WAV", Family.AUDIO, "audio/wav", needsFfmpeg = true),
        Fmt("aiff", "aiff", "AIFF", Family.AUDIO, "audio/aiff", needsFfmpeg = true, aliases = listOf("aif")),
        Fmt("alac", "m4a", "Apple Lossless (ALAC)", Family.AUDIO, "audio/mp4", read = false, needsFfmpeg = true),
        Fmt("wma", "wma", "Windows Media Audio", Family.AUDIO, "audio/x-ms-wma", needsFfmpeg = true),
        Fmt("ac3", "ac3", "Dolby Digital (AC-3)", Family.AUDIO, "audio/ac3", needsFfmpeg = true),
        Fmt("mka", "mka", "Matroska audio", Family.AUDIO, "audio/x-matroska", needsFfmpeg = true),
        Fmt("mp2", "mp2", "MPEG Layer II", Family.AUDIO, "audio/mpeg", needsFfmpeg = true),
        Fmt("au", "au", "Sun AU", Family.AUDIO, "audio/basic", needsFfmpeg = true),
        Fmt("amr", "amr", "AMR", Family.AUDIO, "audio/amr", write = false, needsFfmpeg = true, aliases = listOf("awb")),

        // Documents
        Fmt("pdf", "pdf", "PDF", Family.DOCUMENT, "application/pdf"),
        Fmt("docx", "docx", "Word (DOCX)", Family.DOCUMENT, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        Fmt("odt", "odt", "OpenDocument text", Family.DOCUMENT, "application/vnd.oasis.opendocument.text", write = false),
        Fmt("rtf", "rtf", "Rich Text (RTF)", Family.DOCUMENT, "application/rtf"),
        Fmt("html", "html", "Web page (HTML)", Family.DOCUMENT, "text/html", aliases = listOf("htm", "xhtml")),
        Fmt("md", "md", "Markdown", Family.DOCUMENT, "text/markdown", aliases = listOf("markdown")),
        Fmt("txt", "txt", "Plain text", Family.DOCUMENT, "text/plain", aliases = listOf("text", "log")),
        Fmt("epub", "epub", "EPUB e-book", Family.DOCUMENT, "application/epub+zip", write = false),
        Fmt("pdf-images", "zip", "PDF pages as images", Family.DOCUMENT, "application/zip", read = false),

        // Data
        Fmt("json", "json", "JSON", Family.DATA, "application/json"),
        Fmt("yaml", "yaml", "YAML", Family.DATA, "application/yaml", aliases = listOf("yml")),
        Fmt("xml", "xml", "XML", Family.DATA, "application/xml"),
        Fmt("csv", "csv", "CSV", Family.DATA, "text/csv"),
        Fmt("tsv", "tsv", "TSV", Family.DATA, "text/tab-separated-values"),
        Fmt("toml", "toml", "TOML", Family.DATA, "application/toml", read = false),
        Fmt("properties", "properties", "Java properties", Family.DATA, "text/plain"),

        // Subtitles
        Fmt("srt", "srt", "SubRip (SRT)", Family.SUBTITLE, "application/x-subrip"),
        Fmt("vtt", "vtt", "WebVTT", Family.SUBTITLE, "text/vtt"),
        Fmt("ass", "ass", "ASS", Family.SUBTITLE, "text/x-ssa"),
        Fmt("ssa", "ssa", "SSA", Family.SUBTITLE, "text/x-ssa"),
        Fmt("sbv", "sbv", "YouTube SBV", Family.SUBTITLE, "text/plain"),
        Fmt("lrc", "lrc", "Lyrics (LRC)", Family.SUBTITLE, "text/plain"),
        Fmt("sub-txt", "txt", "Subtitle text only", Family.SUBTITLE, "text/plain", read = false),

        // Archives
        Fmt("zip", "zip", "ZIP", Family.ARCHIVE, "application/zip", aliases = listOf("jar", "apk", "cbz")),
        Fmt("7z", "7z", "7-Zip", Family.ARCHIVE, "application/x-7z-compressed"),
        Fmt("tar", "tar", "TAR", Family.ARCHIVE, "application/x-tar"),
        Fmt("tgz", "tar.gz", "TAR + gzip", Family.ARCHIVE, "application/gzip", aliases = listOf("tar.gz")),
        Fmt("txz", "tar.xz", "TAR + XZ", Family.ARCHIVE, "application/x-xz", aliases = listOf("tar.xz")),
        Fmt("tbz", "tar.bz2", "TAR + bzip2", Family.ARCHIVE, "application/x-bzip2", aliases = listOf("tar.bz2", "tbz2")),
        Fmt("gz", "gz", "gzip (single file)", Family.ARCHIVE, "application/gzip"),
        Fmt("xz", "xz", "XZ (single file)", Family.ARCHIVE, "application/x-xz"),
        Fmt("bz2", "bz2", "bzip2 (single file)", Family.ARCHIVE, "application/x-bzip2"),
    )

    private val byExt: Map<String, Fmt> = buildMap {
        for (f in all.filter { it.read }) {
            putIfAbsent(f.ext, f)
            f.aliases.forEach { putIfAbsent(it, f) }
        }
    }

    fun byId(id: String): Fmt? = all.firstOrNull { it.id == id }

    /** Detects a file's format from its name (handles double extensions like .tar.gz). */
    fun detect(name: String, mime: String): Fmt? {
        val lower = name.lowercase()
        for (double in listOf("tar.gz", "tar.xz", "tar.bz2")) if (lower.endsWith(".$double")) return byExt[double]
        val ext = lower.substringAfterLast('.', "")
        byExt[ext]?.let { return it }
        return when {
            mime.startsWith("image/") -> byExt["png"]
            mime.startsWith("video/") -> byExt["mp4"]
            mime.startsWith("audio/") -> byExt["mp3"]
            mime == "application/pdf" -> byExt["pdf"]
            mime.startsWith("text/") -> byExt["txt"]
            else -> null
        }
    }

    /** Formats a file of [input] can be turned into. Anything can also be packed into an archive. */
    fun targets(input: Fmt?): List<Fmt> {
        val w = all.filter { it.write }
        val archives = w.filter { it.family == Family.ARCHIVE && it.id !in setOf("tar") }
        if (input == null) return archives
        val out = when (input.family) {
            Family.IMAGE -> w.filter { it.family == Family.IMAGE } + w.filter { it.id == "pdf" } +
                (if (input.id in setOf("gif-image", "webp")) w.filter { it.family == Family.VIDEO } else emptyList())
            Family.VIDEO -> w.filter { it.family == Family.VIDEO || it.family == Family.AUDIO } + w.filter { it.id in setOf("jpg", "png", "webp") }
            Family.AUDIO -> w.filter { it.family == Family.AUDIO }
            Family.DOCUMENT -> when (input.id) {
                "pdf" -> w.filter { it.id in setOf("txt", "md", "html", "docx", "rtf", "pdf-images") }
                else -> w.filter { it.family == Family.DOCUMENT && it.id != "pdf-images" }
            }
            Family.DATA -> w.filter { it.family == Family.DATA }
            Family.SUBTITLE -> w.filter { it.family == Family.SUBTITLE }
            Family.ARCHIVE -> w.filter { it.family == Family.ARCHIVE && it.id !in setOf("gz", "xz", "bz2") }
        }
        return (out + archives).distinctBy { it.id }.filter { it.id != input.id || input.family == Family.SUBTITLE }
    }
}
