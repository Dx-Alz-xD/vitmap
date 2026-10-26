package com.vitmap.app.feature.fileconvert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Base64
import androidx.heifwriter.HeifWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.vitmap.app.feature.compress.Progress
import com.vitmap.app.feature.scan.Pdf
import com.vitmap.app.media.MediaEngine
import com.vitmap.core.convert.DataFormat
import com.vitmap.core.convert.DataFormats
import com.vitmap.core.convert.SubtitleFormat
import com.vitmap.core.convert.Subtitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

enum class VideoQuality(val label: String, val crf: Int) { HIGH("High", 20), MEDIUM("Balanced", 24), SMALL("Small", 29) }

data class ConvertOptions(
    val quality: Int = 88,
    val maxDim: Int = 0,
    val videoQuality: VideoQuality = VideoQuality.MEDIUM,
    val height: Int = 0,
    val fps: Int = 0,
    val audioKbps: Int = 192,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val trimStart: Double = 0.0,
    val trimEnd: Double = 0.0,
    val noAudio: Boolean = false,
    val frameAt: Double = 1.0,
    val gifWidth: Int = 480,
    val gifFps: Int = 12,
    val dpi: Int = 150,
    val subtitleShiftMs: Long = 0,
    val fastCopy: Boolean = false,
)

/** Routes a conversion to the right engine. Outputs land in the cache; callers save or share them. */
object FileConverters {
    fun needsFfmpeg(input: Fmt?, out: Fmt): Boolean {
        val inNeeds = input?.needsFfmpeg == true && input.family != Family.ARCHIVE
        return when (out.family) {
            Family.VIDEO, Family.AUDIO -> true
            Family.IMAGE -> out.needsFfmpeg || inNeeds || input?.family == Family.VIDEO
            else -> false
        }
    }

    suspend fun convert(context: Context, input: File, name: String, inFmt: Fmt?, out: Fmt, o: ConvertOptions, p: Progress): File = withContext(Dispatchers.Default) {
        val dir = File(context.cacheDir, "converted").apply { mkdirs() }
        val base = name.substringBeforeLast('.').removeSuffix(".tar").ifEmpty { "file" }
        val target = uniqueFile(dir, if (out.id in setOf("gz", "xz", "bz2")) "$name.${out.ext}" else "$base.${out.ext}")
        when {
            out.family == Family.ARCHIVE -> ArchiveConvert.convert(input, name, inFmt, out, target, p)
            out.family == Family.IMAGE && inFmt?.family == Family.VIDEO -> frame(context, input, out, o, target, p)
            out.family == Family.IMAGE -> image(context, input, inFmt, out, o, target, p)
            out.id == "pdf" && inFmt?.family == Family.IMAGE -> imageToPdf(context, input, inFmt, target)
            out.family == Family.VIDEO || out.family == Family.AUDIO -> media(context, input, out, o, target, p)
            out.family == Family.DOCUMENT -> DocConvert.convert(context, input, name, inFmt, out, o, target, p)
            out.family == Family.DATA -> {
                p.update(null, "Converting")
                val from = DataFormat.of(inFmt?.ext ?: "json") ?: throw IOException("Unsupported data format")
                target.writeText(DataFormats.convert(input.readText(), from, DataFormat.of(out.ext)!!))
            }
            out.family == Family.SUBTITLE -> {
                p.update(null, "Converting")
                val from = SubtitleFormat.of(inFmt?.ext ?: "srt") ?: throw IOException("Unsupported subtitle format")
                val cues = Subtitles.retime(Subtitles.parse(input.readText(), from), o.subtitleShiftMs)
                if (cues.isEmpty()) throw IOException("No subtitles found in this file")
                target.writeText(Subtitles.write(cues, if (out.id == "sub-txt") SubtitleFormat.TXT else SubtitleFormat.of(out.ext)!!))
            }
            else -> throw IOException("Can't convert to ${out.label}")
        }
        target
    }

    fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 2
        val stem = name.substringBefore('.')
        val ext = name.substringAfter('.', "")
        while (f.exists()) f = File(dir, "$stem ($i)${if (ext.isEmpty()) "" else ".$ext"}").also { i++ }
        return f
    }

    // ---------------------------------------------------------------- images

    /** Decodes with Android's codecs, falling back to FFmpeg for formats Android can't read. */
    suspend fun decode(context: Context, file: File, fmt: Fmt?, maxDim: Int = 0): Bitmap {
        val android = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    if (maxDim > 0) {
                        val s = maxDim.toFloat() / max(info.size.width, info.size.height)
                        if (s < 1f) d.setTargetSize((info.size.width * s).roundToInt().coerceAtLeast(1), (info.size.height * s).roundToInt().coerceAtLeast(1))
                    }
                }
            } else BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull()
        if (android != null) return if (maxDim > 0) scale(android, maxDim) else android
        if (fmt?.needsFfmpeg == true || fmt == null) {
            val tmp = File(context.cacheDir, "decode-${System.nanoTime()}.png")
            val r = MediaEngine.ffmpeg(context, listOf("-i", file.absolutePath, "-frames:v", "1", tmp.absolutePath))
            if (r.ok && tmp.exists()) {
                val b = BitmapFactory.decodeFile(tmp.absolutePath)
                tmp.delete()
                if (b != null) return if (maxDim > 0) scale(b, maxDim) else b
            }
        }
        throw IOException("Couldn't decode this image")
    }

    private fun scale(b: Bitmap, maxDim: Int): Bitmap {
        val s = maxDim.toFloat() / max(b.width, b.height)
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).roundToInt().coerceAtLeast(1), (b.height * s).roundToInt().coerceAtLeast(1), true)
    }

    private fun flatten(b: Bitmap): Bitmap {
        if (!b.hasAlpha()) return b
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(b, 0f, 0f, null) }
        return out
    }

    private suspend fun image(context: Context, input: File, inFmt: Fmt?, out: Fmt, o: ConvertOptions, target: File, p: Progress) {
        p.update(null, "Decoding")
        val bmp = decode(context, input, inFmt, o.maxDim)
        p.update(null, "Encoding ${out.label}")
        when (out.id) {
            "jpg" -> target.outputStream().use { flatten(bmp).compress(Bitmap.CompressFormat.JPEG, o.quality, it) }
            "png" -> target.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            "webp" -> target.outputStream().use { bmp.compress(if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP, o.quality, it) }
            "webp-lossless" -> target.outputStream().use { bmp.compress(if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP, 100, it) }
            "heic" -> {
                val w = HeifWriter.Builder(target.absolutePath, bmp.width, bmp.height, HeifWriter.INPUT_MODE_BITMAP).setQuality(o.quality).build()
                w.start()
                w.addBitmap(bmp)
                w.stop(30_000)
                w.close()
            }
            "bmp" -> target.writeBytes(bmpBytes(bmp))
            "ico" -> target.writeBytes(icoBytes(bmp))
            "svg" -> {
                val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                val b64 = Base64.encodeToString(png, Base64.NO_WRAP)
                target.writeText("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="${bmp.width}" height="${bmp.height}" viewBox="0 0 ${bmp.width} ${bmp.height}"><image width="${bmp.width}" height="${bmp.height}" xlink:href="data:image/png;base64,$b64"/></svg>""")
            }
            else -> {
                // Everything else goes through FFmpeg from a lossless intermediate.
                val tmp = File(context.cacheDir, "img-${System.nanoTime()}.png")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val args = when (out.id) {
                    "avif" -> listOf("-c:v", "libaom-av1", "-still-picture", "1", "-crf", ((100 - o.quality) * 0.6).roundToInt().coerceIn(10, 50).toString(), "-pix_fmt", "yuv420p")
                    "gif-image" -> listOf("-vf", "split[a][b];[a]palettegen[p];[b][p]paletteuse")
                    "tiff" -> listOf("-c:v", "tiff", "-compression_algo", "deflate")
                    "tga" -> listOf("-c:v", "targa")
                    "qoi" -> listOf("-c:v", "qoi")
                    "ppm" -> listOf("-c:v", "ppm")
                    "pcx" -> listOf("-c:v", "pcx")
                    "sgi" -> listOf("-c:v", "sgi")
                    "jp2" -> listOf("-c:v", "jpeg2000", "-pred", "1")
                    "xbm" -> listOf("-c:v", "xbm", "-pix_fmt", "monob")
                    "dpx" -> listOf("-c:v", "dpx")
                    else -> emptyList()
                }
                try {
                    val r = MediaEngine.ffmpeg(context, listOf("-i", tmp.absolutePath) + args + listOf("-frames:v", "1", "-update", "1", target.absolutePath))
                    if (!r.ok) {
                        // AVIF support differs between builds; try the other AV1 encoders.
                        if (out.id == "avif") {
                            for (enc in listOf("librav1e", "libsvtav1")) {
                                val again = MediaEngine.ffmpeg(context, listOf("-i", tmp.absolutePath, "-c:v", enc, "-frames:v", "1", target.absolutePath))
                                if (again.ok) return
                            }
                        }
                        throw IOException("Conversion failed: ${r.stderr.lines().lastOrNull { it.isNotBlank() } ?: "unknown error"}")
                    }
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    private suspend fun imageToPdf(context: Context, input: File, inFmt: Fmt?, target: File) = imagesToPdf(context, listOf(input to inFmt), target) { _, _ -> }

    /** One page per image, sized so the picture prints at 150 dpi. */
    suspend fun imagesToPdf(context: Context, images: List<Pair<File, Fmt?>>, target: File, onPage: (Int, Int) -> Unit) {
        Pdf.init(context)
        PDDocument().use { doc ->
            for ((i, pair) in images.withIndex()) {
                onPage(i, images.size)
                val (file, fmt) = pair
                val bmp = decode(context, file, fmt, 4000)
                val w = bmp.width * 72f / 150
                val h = bmp.height * 72f / 150
                val page = PDPage(PDRectangle(w, h))
                doc.addPage(page)
                val img = if (bmp.hasAlpha() || fmt?.id == "png") LosslessFactory.createFromImage(doc, bmp) else JPEGFactory.createFromImage(doc, bmp, 0.9f)
                PDPageContentStream(doc, page).use { it.drawImage(img, 0f, 0f, w, h) }
                bmp.recycle()
            }
            doc.save(target)
        }
    }

    /** 24-bit uncompressed Windows bitmap. */
    fun bmpBytes(b: Bitmap): ByteArray {
        val rowSize = (b.width * 3 + 3) / 4 * 4
        val size = 54 + rowSize * b.height
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('B'.code.toByte()).put('M'.code.toByte()).putInt(size).putInt(0).putInt(54)
        buf.putInt(40).putInt(b.width).putInt(b.height).putShort(1).putShort(24).putInt(0).putInt(rowSize * b.height).putInt(2835).putInt(2835).putInt(0).putInt(0)
        val row = IntArray(b.width)
        val pad = ByteArray(rowSize - b.width * 3)
        for (y in b.height - 1 downTo 0) {
            b.getPixels(row, 0, b.width, 0, y, b.width, 1)
            for (c in row) {
                val a = (c ushr 24) / 255f
                fun ch(v: Int) = (v * a + 255 * (1 - a)).roundToInt().toByte()
                buf.put(ch(c and 0xFF)).put(ch(c shr 8 and 0xFF)).put(ch(c shr 16 and 0xFF))
            }
            buf.put(pad)
        }
        return buf.array()
    }

    /** Multi-size Windows icon with PNG-compressed images (256, 128, 64, 48, 32, 16). */
    fun icoBytes(src: Bitmap): ByteArray {
        val side = max(src.width, src.height)
        val square = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also {
            android.graphics.Canvas(it).drawBitmap(src, ((side - src.width) / 2).toFloat(), ((side - src.height) / 2).toFloat(), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        val sizes = listOf(256, 128, 64, 48, 32, 16)
        val images = sizes.map { s -> ByteArrayOutputStream().also { Bitmap.createScaledBitmap(square, s, s, true).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
        val out = ByteArrayOutputStream()
        val le = DataOutputStream(out)
        fun short(v: Int) { le.writeByte(v and 0xFF); le.writeByte(v shr 8 and 0xFF) }
        fun int(v: Int) { short(v and 0xFFFF); short(v ushr 16) }
        short(0); short(1); short(sizes.size)
        var offset = 6 + 16 * sizes.size
        sizes.forEachIndexed { i, s ->
            le.writeByte(if (s >= 256) 0 else s); le.writeByte(if (s >= 256) 0 else s)
            le.writeByte(0); le.writeByte(0)
            short(1); short(32)
            int(images[i].size); int(offset)
            offset += images[i].size
        }
        images.forEach { le.write(it) }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- video & audio

    private suspend fun frame(context: Context, input: File, out: Fmt, o: ConvertOptions, target: File, p: Progress) {
        p.update(null, "Grabbing a frame at ${o.frameAt}s")
        val r = MediaEngine.ffmpeg(context, listOf("-ss", o.frameAt.toString(), "-i", input.absolutePath, "-frames:v", "1", "-q:v", "2", target.absolutePath))
        if (!r.ok || !target.exists()) throw IOException("Couldn't grab a frame: ${r.stderr.lines().lastOrNull { it.isNotBlank() }}")
    }

    private suspend fun media(context: Context, input: File, out: Fmt, o: ConvertOptions, target: File, p: Progress) {
        p.update(null, "Reading")
        val info = MediaEngine.probe(context, input)
        val total = ((if (o.trimEnd > 0) o.trimEnd else info?.durationSec ?: 0.0) - o.trimStart).coerceAtLeast(0.1)
        val trim = buildList {
            if (o.trimStart > 0) addAll(listOf("-ss", o.trimStart.toString()))
        }
        val trimEnd = if (o.trimEnd > o.trimStart) listOf("-t", (o.trimEnd - o.trimStart).toString()) else emptyList()
        val vf = buildList {
            if (o.height > 0) add("scale=-2:${o.height}")
            if (o.fps > 0) add("fps=${o.fps}")
        }
        val vfArgs = if (vf.isNotEmpty()) listOf("-vf", vf.joinToString(",")) else emptyList()
        val audio = buildList {
            if (o.sampleRate > 0) addAll(listOf("-ar", o.sampleRate.toString()))
            if (o.channels > 0) addAll(listOf("-ac", o.channels.toString()))
        }
        val ab = listOf("-b:a", "${o.audioKbps}k")
        val crf = o.videoQuality.crf.toString()
        val x264 = listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", crf, "-pix_fmt", "yuv420p")
        val noA = o.noAudio || info?.hasAudio == false
        fun a(codec: List<String>) = if (noA) listOf("-an") else codec + audio
        val args: List<String> = when (out.id) {
            "mp4", "m4v" -> if (o.fastCopy) listOf("-c", "copy") else x264 + vfArgs + a(listOf("-c:a", "aac") + ab) + listOf("-movflags", "+faststart")
            "mp4-hevc" -> listOf("-c:v", "libx265", "-preset", "fast", "-crf", (o.videoQuality.crf + 4).toString(), "-tag:v", "hvc1", "-pix_fmt", "yuv420p") + vfArgs + a(listOf("-c:a", "aac") + ab) + listOf("-movflags", "+faststart")
            "mkv" -> if (o.fastCopy) listOf("-c", "copy") else x264 + vfArgs + a(listOf("-c:a", "aac") + ab)
            "mov" -> x264 + vfArgs + a(listOf("-c:a", "aac") + ab)
            "webm" -> listOf("-c:v", "libvpx-vp9", "-crf", (o.videoQuality.crf + 8).toString(), "-b:v", "0", "-deadline", "realtime", "-cpu-used", "8", "-row-mt", "1") + vfArgs + a(listOf("-c:a", "libopus") + ab)
            "webm-av1" -> listOf("-c:v", "libaom-av1", "-crf", (o.videoQuality.crf + 10).toString(), "-b:v", "0", "-cpu-used", "8", "-row-mt", "1") + vfArgs + a(listOf("-c:a", "libopus") + ab)
            "avi" -> listOf("-c:v", "mpeg4", "-q:v", (o.videoQuality.ordinal * 3 + 3).toString()) + vfArgs + a(listOf("-c:a", "libmp3lame") + ab)
            "flv" -> x264 + vfArgs + a(listOf("-c:a", "aac") + ab) + listOf("-f", "flv")
            "3gp" -> listOf("-c:v", "libx264", "-profile:v", "baseline", "-preset", "veryfast", "-crf", crf, "-pix_fmt", "yuv420p") + (if (vf.isEmpty()) listOf("-vf", "scale=-2:360") else vfArgs) + a(listOf("-c:a", "aac", "-b:a", "64k", "-ac", "1"))
            "ts" -> x264 + vfArgs + a(listOf("-c:a", "aac") + ab) + listOf("-f", "mpegts")
            "mpg" -> listOf("-c:v", "mpeg2video", "-q:v", (o.videoQuality.ordinal * 3 + 2).toString()) + vfArgs + a(listOf("-c:a", "mp2", "-b:a", "192k")) + listOf("-f", "mpeg")
            "wmv" -> listOf("-c:v", "wmv2", "-q:v", (o.videoQuality.ordinal * 3 + 3).toString()) + vfArgs + a(listOf("-c:a", "wmav2") + ab)
            "ogv" -> listOf("-c:v", "libtheora", "-q:v", (8 - o.videoQuality.ordinal * 2).toString()) + vfArgs + a(listOf("-c:a", "libvorbis", "-q:a", "5"))
            "gif" -> listOf("-vf", "fps=${o.gifFps},scale=${o.gifWidth}:-1:flags=lanczos,split[s0][s1];[s0]palettegen=stats_mode=diff[p];[s1][p]paletteuse=dither=bayer:bayer_scale=4", "-loop", "0", "-an")
            "webp-anim" -> listOf("-vf", "fps=${o.gifFps},scale=${o.gifWidth}:-1:flags=lanczos", "-c:v", "libwebp_anim", "-quality", o.quality.toString(), "-loop", "0", "-an")
            "apng" -> listOf("-vf", "fps=${o.gifFps},scale=${o.gifWidth}:-1:flags=lanczos", "-plays", "0", "-an", "-f", "apng")
            "mp3" -> listOf("-vn", "-c:a", "libmp3lame") + ab + audio
            "m4a" -> listOf("-vn", "-c:a", "aac") + ab + audio
            "aac" -> listOf("-vn", "-c:a", "aac") + ab + audio + listOf("-f", "adts")
            "opus" -> listOf("-vn", "-c:a", "libopus", "-b:a", "${o.audioKbps.coerceAtMost(256)}k") + audio
            "ogg" -> listOf("-vn", "-c:a", "libvorbis") + ab + audio
            "flac" -> listOf("-vn", "-c:a", "flac", "-compression_level", "8") + audio
            "wav" -> listOf("-vn", "-c:a", "pcm_s16le") + audio
            "aiff" -> listOf("-vn", "-c:a", "pcm_s16be") + audio + listOf("-f", "aiff")
            "alac" -> listOf("-vn", "-c:a", "alac") + audio
            "wma" -> listOf("-vn", "-c:a", "wmav2") + ab + audio
            "ac3" -> listOf("-vn", "-c:a", "ac3", "-b:a", "${o.audioKbps.coerceAtLeast(96)}k") + audio
            "mka" -> listOf("-vn", "-c:a", "libopus", "-b:a", "${o.audioKbps.coerceAtMost(256)}k") + audio
            "mp2" -> listOf("-vn", "-c:a", "mp2", "-b:a", "${o.audioKbps.coerceAtLeast(64)}k") + audio
            "au" -> listOf("-vn", "-c:a", "pcm_s16be") + audio + listOf("-f", "au")
            else -> throw IOException("Unsupported output ${out.label}")
        }
        val r = MediaEngine.ffmpeg(
            context,
            trim + listOf("-i", input.absolutePath) + trimEnd + args + target.absolutePath,
            onProgress = { s -> p.update((s / total).toFloat().coerceIn(0f, 1f), "Converting to ${out.label}") },
        )
        if (!r.ok) {
            target.delete()
            val reason = r.stderr.lines().lastOrNull { it.isNotBlank() && !it.startsWith("[") } ?: r.stderr.lines().lastOrNull { it.isNotBlank() } ?: "unknown error"
            throw IOException("FFmpeg couldn't convert this file: $reason")
        }
    }
}
