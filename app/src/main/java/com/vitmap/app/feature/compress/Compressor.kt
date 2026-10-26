package com.vitmap.app.feature.compress

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.vitmap.app.feature.scan.Pdf
import com.vitmap.app.media.MediaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class FileKind(val label: String) { IMAGE("Image"), VIDEO("Video"), AUDIO("Audio"), PDF("PDF"), OFFICE("Document"), OTHER("File") }

enum class ImageOut(val label: String, val ext: String, val mime: String) {
    KEEP("Same format", "", ""), JPEG("JPEG", "jpg", "image/jpeg"), WEBP("WebP", "webp", "image/webp"),
}

enum class VideoCodec(val label: String, val encoder: String) { H264("H.264", "libx264"), H265("H.265 / HEVC", "libx265") }

enum class AudioOut(val label: String, val ext: String, val encoder: String, val mime: String) {
    KEEP("Same format", "", "", ""), MP3("MP3", "mp3", "libmp3lame", "audio/mpeg"), AAC("AAC (M4A)", "m4a", "aac", "audio/mp4"), OPUS("Opus", "opus", "libopus", "audio/ogg"),
}

enum class ArchiveOut(val label: String, val ext: String, val mime: String) { ZIP("ZIP", "zip", "application/zip"), XZ("XZ (strongest)", "xz", "application/x-xz") }

data class CompressOptions(
    val targetBytes: Long,
    val imageOut: ImageOut = ImageOut.KEEP,
    val keepMetadata: Boolean = false,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val maxHeight: Int = 0,
    val audioKbps: Int = 0,
    val audioOut: AudioOut = AudioOut.KEEP,
    val mono: Boolean = false,
    val rasterizePdf: Boolean = false,
    val grayscale: Boolean = false,
    val archive: ArchiveOut = ArchiveOut.ZIP,
)

data class CompressResult(val file: File, val mime: String, val reachedTarget: Boolean, val note: String? = null)

fun interface Progress {
    fun update(fraction: Float?, stage: String)
}

/** Shrinks a file toward a target size, choosing the right strategy for its type. */
object Compressor {
    fun kindOf(name: String, mime: String): FileKind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("image/") && ext != "svg" || ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "avif") -> FileKind.IMAGE
            mime.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "webm", "avi", "3gp", "m4v", "flv", "wmv", "ts") -> FileKind.VIDEO
            mime.startsWith("audio/") || ext in setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "amr", "aiff") -> FileKind.AUDIO
            mime == "application/pdf" || ext == "pdf" -> FileKind.PDF
            ext in setOf("docx", "xlsx", "pptx", "odt", "ods", "odp", "epub") -> FileKind.OFFICE
            else -> FileKind.OTHER
        }
    }

    suspend fun compress(context: Context, input: File, name: String, kind: FileKind, o: CompressOptions, progress: Progress): CompressResult = withContext(Dispatchers.Default) {
        val dir = File(context.cacheDir, "compress").apply { mkdirs() }
        val base = name.substringBeforeLast('.').ifEmpty { "file" }
        when (kind) {
            FileKind.IMAGE -> image(input, name, base, dir, o, progress)
            FileKind.VIDEO -> video(context, input, base, dir, o, progress)
            FileKind.AUDIO -> audio(context, input, name, base, dir, o, progress)
            FileKind.PDF -> pdf(context, input, base, dir, o, progress)
            FileKind.OFFICE -> office(input, name, base, dir, o, progress)
            FileKind.OTHER -> archive(input, name, dir, o, progress)
        }
    }

    // ------------------------------------------------------------------ images

    fun encode(bmp: Bitmap, format: ImageOut, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val cf = when (format) {
            ImageOut.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
        bmp.compress(cf, quality, out)
        return out.toByteArray()
    }

    private fun decodeFull(file: File, maxDim: Int = 8192): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("This image format can't be decoded on this device")
        val rotation = runCatching { ExifInterface(file.absolutePath).rotationDegrees }.getOrDefault(0)
        return if (rotation == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }, true)
    }

    /** Best (highest quality, then largest size) encoding that fits [target]. */
    fun fitImage(src: Bitmap, format: ImageOut, target: Long, onStep: (String) -> Unit = {}): Pair<ByteArray, Boolean> {
        var bmp = src
        var best: ByteArray? = null
        repeat(8) { round ->
            // Binary search the quality at this size.
            var lo = 8
            var hi = 95
            var found: ByteArray? = null
            while (lo <= hi) {
                val q = (lo + hi) / 2
                val bytes = encode(bmp, format, q)
                onStep("Trying quality $q at ${bmp.width}×${bmp.height}")
                if (bytes.size <= target) {
                    found = bytes
                    lo = q + 1
                } else {
                    hi = q - 1
                }
            }
            if (found != null) {
                // Prefer a larger image at moderate quality over a tiny image at high quality.
                return found to true
            }
            val smallest = encode(bmp, format, 8)
            best = smallest
            val scale = sqrt(target.toDouble() / smallest.size) * 0.95
            val f = scale.coerceIn(0.35, 0.92)
            val w = (bmp.width * f).roundToInt().coerceAtLeast(16)
            val h = (bmp.height * f).roundToInt().coerceAtLeast(16)
            if (w <= 16 || h <= 16 || round == 7) return smallest to false
            bmp = Bitmap.createScaledBitmap(bmp, w, h, true)
        }
        return (best ?: encode(bmp, format, 8)) to false
    }

    private fun image(input: File, name: String, base: String, dir: File, o: CompressOptions, p: Progress): CompressResult {
        p.update(null, "Decoding")
        val ext = name.substringAfterLast('.', "").lowercase()
        val format = when {
            o.imageOut != ImageOut.KEEP -> o.imageOut
            ext == "webp" -> ImageOut.WEBP
            else -> ImageOut.JPEG
        }
        val note = if (o.imageOut == ImageOut.KEEP && ext !in setOf("jpg", "jpeg", "webp")) "Saved as ${format.label}: ${ext.uppercase()} can't be shrunk much without converting" else null
        val src = decodeFull(input)
        val flattened = if (format == ImageOut.JPEG && src.hasAlpha()) {
            Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also { b ->
                android.graphics.Canvas(b).apply { drawColor(Color.WHITE); drawBitmap(src, 0f, 0f, null) }
            }
        } else src
        val (bytes, ok) = fitImage(flattened, format, o.targetBytes) { p.update(null, it) }
        val out = File(dir, "$base (compressed).${format.ext}")
        out.writeBytes(bytes)
        if (o.keepMetadata && format == ImageOut.JPEG && ext in setOf("jpg", "jpeg")) copyExif(input, out)
        return CompressResult(out, format.mime, ok, note)
    }

    private fun copyExif(from: File, to: File) = runCatching {
        val a = ExifInterface(from.absolutePath)
        val b = ExifInterface(to.absolutePath)
        for (tag in listOf(ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT)) {
            a.getAttribute(tag)?.let { b.setAttribute(tag, it) }
        }
        b.saveAttributes()
    }

    // ------------------------------------------------------------------ video & audio

    private suspend fun video(context: Context, input: File, base: String, dir: File, o: CompressOptions, p: Progress): CompressResult {
        p.update(null, "Reading the video")
        val info = MediaEngine.probe(context, input) ?: throw IOException("Couldn't read this video")
        val duration = info.durationSec.takeIf { it > 0.1 } ?: throw IOException("Unknown video length")
        val audioKbps = when {
            !info.hasAudio -> 0
            o.audioKbps > 0 -> o.audioKbps
            else -> (o.targetBytes * 8 / duration / 1000 * 0.12).roundToInt().coerceIn(48, 128)
        }
        var videoKbps = ((o.targetBytes * 8 * 0.96 / duration) / 1000 - audioKbps).roundToInt()
        if (videoKbps < 40) throw IOException("That target is too small for a ${duration.roundToInt()} s video. Try at least ${formatTarget(((40 + audioKbps) * 1000 / 8.0 * duration / 0.96).toLong())}.")
        val v = info.video ?: throw IOException("No video stream found")
        // Lower the resolution when the bitrate can't sustain it (about 0.07 bits per pixel per frame).
        val fps = v.fps.takeIf { it in 1.0..120.0 } ?: 30.0
        var height = if (o.maxHeight > 0) min(o.maxHeight, v.height) else v.height
        while (height > 240 && videoKbps * 1000.0 / (height * height * 16.0 / 9 * fps) < 0.06) height = (height * 0.8).roundToInt()
        height -= height % 2
        val out = File(dir, "$base (compressed).mp4")
        val passLog = File(dir, "pass-$base").absolutePath
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val scale = if (height < v.height) listOf("-vf", "scale=-2:$height") else emptyList()
            val common = listOf("-i", input.absolutePath) + scale + listOf("-c:v", o.videoCodec.encoder, "-b:v", "${videoKbps}k", "-preset", "veryfast")
            val x265 = o.videoCodec == VideoCodec.H265
            p.update(0f, "Pass 1 of 2 at ${height}p, ${videoKbps} kb/s")
            val pass1 = if (x265) listOf("-x265-params", "pass=1:stats=$passLog.x265") else listOf("-pass", "1", "-passlogfile", passLog)
            var r = MediaEngine.ffmpeg(context, common + pass1 + listOf("-an", "-f", "null", "/dev/null"), onProgress = { s -> p.update((s / duration / 2).toFloat().coerceIn(0f, 0.5f), "Pass 1 of 2 at ${height}p") })
            if (!r.ok) throw IOException("Encoding failed: ${r.stderr.lines().lastOrNull { it.isNotBlank() } ?: "unknown error"}")
            val pass2 = if (x265) listOf("-x265-params", "pass=2:stats=$passLog.x265") else listOf("-pass", "2", "-passlogfile", passLog)
            val audio = if (audioKbps > 0) listOf("-c:a", "aac", "-b:a", "${audioKbps}k") + (if (o.mono) listOf("-ac", "1") else emptyList()) else listOf("-an")
            r = MediaEngine.ffmpeg(context, common + pass2 + audio + listOf("-movflags", "+faststart", out.absolutePath), onProgress = { s -> p.update((0.5 + s / duration / 2).toFloat().coerceIn(0.5f, 1f), "Pass 2 of 2 at ${height}p") })
            if (!r.ok) throw IOException("Encoding failed: ${r.stderr.lines().lastOrNull { it.isNotBlank() } ?: "unknown error"}")
            val size = out.length()
            if (size <= o.targetBytes || attempt >= 2) {
                dir.listFiles()?.filter { it.name.startsWith("pass-$base") }?.forEach { it.delete() }
                return CompressResult(out, "video/mp4", size <= o.targetBytes, if (height < v.height) "Scaled to ${height}p to keep it sharp at this size" else null)
            }
            videoKbps = (videoKbps * (o.targetBytes.toDouble() / size) * 0.97).roundToInt().coerceAtLeast(30)
            attempt++
        }
    }

    private suspend fun audio(context: Context, input: File, name: String, base: String, dir: File, o: CompressOptions, p: Progress): CompressResult {
        p.update(null, "Reading the audio")
        val info = MediaEngine.probe(context, input) ?: throw IOException("Couldn't read this audio file")
        val duration = info.durationSec.takeIf { it > 0.1 } ?: throw IOException("Unknown length")
        val ext = name.substringAfterLast('.', "").lowercase()
        val fmt = when {
            o.audioOut != AudioOut.KEEP -> o.audioOut
            ext == "mp3" -> AudioOut.MP3
            ext in setOf("ogg", "opus") -> AudioOut.OPUS
            else -> AudioOut.AAC
        }
        val (minK, maxK) = when (fmt) {
            AudioOut.MP3 -> 8 to 320
            AudioOut.OPUS -> 6 to 256
            else -> 16 to 320
        }
        var kbps = (o.targetBytes * 8 * 0.97 / duration / 1000).roundToInt()
        val reach = kbps >= minK
        kbps = kbps.coerceIn(minK, maxK)
        val out = File(dir, "$base (compressed).${fmt.ext}")
        repeat(3) {
            val args = listOf("-i", input.absolutePath, "-vn", "-c:a", fmt.encoder, "-b:a", "${kbps}k") + (if (o.mono || kbps < 48) listOf("-ac", "1") else emptyList()) +
                (if (fmt == AudioOut.MP3 && kbps < 64) listOf("-ar", "22050") else emptyList()) + out.absolutePath
            val r = MediaEngine.ffmpeg(context, args, onProgress = { s -> p.update((s / duration).toFloat().coerceIn(0f, 1f), "Encoding at $kbps kb/s") })
            if (!r.ok) throw IOException("Encoding failed: ${r.stderr.lines().lastOrNull { it.isNotBlank() }}")
            if (out.length() <= o.targetBytes || kbps <= minK) return CompressResult(out, fmt.mime, out.length() <= o.targetBytes && reach)
            kbps = (kbps * o.targetBytes.toDouble() / out.length() * 0.97).roundToInt().coerceIn(minK, maxK)
        }
        return CompressResult(out, fmt.mime, out.length() <= o.targetBytes)
    }

    // ------------------------------------------------------------------ PDF

    private suspend fun pdf(context: Context, input: File, base: String, dir: File, o: CompressOptions, p: Progress): CompressResult {
        Pdf.init(context)
        val out = File(dir, "$base (compressed).pdf")
        val attempts = listOf(1.0f to 0.75f, 1.0f to 0.55f, 0.75f to 0.5f, 0.6f to 0.42f, 0.45f to 0.35f, 0.33f to 0.3f)
        var best: Long = Long.MAX_VALUE
        for ((i, a) in attempts.withIndex()) {
            currentCoroutineContext().ensureActive()
            val (scale, quality) = a
            p.update(i.toFloat() / attempts.size, "Recompressing images (${(quality * 100).roundToInt()}% quality, ${(scale * 100).roundToInt()}% size)")
            val candidate = File(dir, "attempt.pdf")
            PDDocument.load(input).use { doc ->
                for (page in doc.pages) page.resources?.let { recompressImages(doc, it, scale, quality, o.grayscale, 0) }
                doc.save(candidate)
            }
            if (candidate.length() < best) {
                best = candidate.length()
                candidate.copyTo(out, overwrite = true)
            }
            candidate.delete()
            if (best <= o.targetBytes) return CompressResult(out, "application/pdf", true)
        }
        if (!o.rasterizePdf) return CompressResult(out, "application/pdf", best <= o.targetBytes, "Most of this PDF is text or vector graphics, which can't shrink further without turning pages into images. Enable “Allow rasterising” to go smaller.")
        // Last resort: render each page to a JPEG at decreasing resolution.
        for (dpi in listOf(150, 110, 85, 65, 50)) {
            p.update(null, "Rasterising pages at $dpi dpi")
            val candidate = File(dir, "attempt.pdf")
            rasterize(input, candidate, dpi, 0.6f, o.grayscale)
            if (candidate.length() < best) {
                best = candidate.length()
                candidate.copyTo(out, overwrite = true)
            }
            candidate.delete()
            if (best <= o.targetBytes) return CompressResult(out, "application/pdf", true, "Pages were turned into images at $dpi dpi")
        }
        return CompressResult(out, "application/pdf", false)
    }

    private fun recompressImages(doc: PDDocument, res: PDResources, scale: Float, quality: Float, gray: Boolean, depth: Int) {
        if (depth > 4) return
        for (name in res.xObjectNames.toList()) {
            val x = runCatching { res.getXObject(name) }.getOrNull() ?: continue
            when (x) {
                is PDImageXObject -> {
                    if (x.width * x.height < 40_000) continue
                    val bmp = runCatching { x.image }.getOrNull() ?: continue
                    val w = (bmp.width * scale).roundToInt().coerceAtLeast(8)
                    val h = (bmp.height * scale).roundToInt().coerceAtLeast(8)
                    var scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
                    if (gray) scaled = grayscale(scaled)
                    if (scaled.hasAlpha()) continue // JPEG has no transparency; keep masks intact
                    val replacement = JPEGFactory.createFromImage(doc, scaled, quality)
                    res.put(name, replacement)
                }
                is PDFormXObject -> x.resources?.let { recompressImages(doc, it, scale, quality, gray, depth + 1) }
            }
        }
    }

    private fun grayscale(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) }) }
        android.graphics.Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun rasterize(input: File, output: File, dpi: Int, quality: Float, gray: Boolean) {
        PDDocument().use { doc ->
            ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    for (i in 0 until r.pageCount) {
                        r.openPage(i).use { page ->
                            val s = dpi / 72f
                            var bmp = Bitmap.createBitmap((page.width * s).roundToInt().coerceAtLeast(1), (page.height * s).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            if (gray) bmp = grayscale(bmp)
                            val pd = PDPage(PDRectangle(page.width.toFloat(), page.height.toFloat()))
                            doc.addPage(pd)
                            val img = JPEGFactory.createFromImage(doc, bmp, quality)
                            PDPageContentStream(doc, pd).use { it.drawImage(img, 0f, 0f, page.width.toFloat(), page.height.toFloat()) }
                        }
                    }
                }
            }
            doc.save(output)
        }
    }

    // ------------------------------------------------------------------ Office / EPUB (zip containers)

    private suspend fun office(input: File, name: String, base: String, dir: File, o: CompressOptions, p: Progress): CompressResult {
        val ext = name.substringAfterLast('.', "zip")
        val out = File(dir, "$base (compressed).$ext")
        val levels = listOf(1.0f to 80, 1.0f to 60, 0.75f to 55, 0.55f to 45, 0.4f to 35)
        var best = Long.MAX_VALUE
        ZipFile(input).use { zip ->
            for ((i, lvl) in levels.withIndex()) {
                currentCoroutineContext().ensureActive()
                p.update(i.toFloat() / levels.size, "Recompressing pictures (${lvl.second}% quality)")
                val candidate = File(dir, "attempt.$ext")
                ZipOutputStream(candidate.outputStream().buffered()).use { zos ->
                    zos.setLevel(Deflater.BEST_COMPRESSION)
                    for (e in zip.entries().asSequence()) {
                        val data = zip.getInputStream(e).use { it.readBytes() }
                        val lower = e.name.lowercase()
                        val isImage = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                        val bytes = if (isImage && data.size > 30_000) shrinkEmbedded(data, lower.endsWith(".png"), lvl.first, lvl.second) ?: data else data
                        val entry = ZipEntry(e.name)
                        // EPUB requires "mimetype" first and uncompressed.
                        if (e.name == "mimetype") {
                            entry.method = ZipEntry.STORED
                            entry.size = bytes.size.toLong()
                            entry.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                        }
                        zos.putNextEntry(entry)
                        zos.write(bytes)
                        zos.closeEntry()
                    }
                }
                if (candidate.length() < best) {
                    best = candidate.length()
                    candidate.copyTo(out, overwrite = true)
                }
                candidate.delete()
                if (best <= o.targetBytes) return CompressResult(out, "application/octet-stream", true)
            }
        }
        return CompressResult(out, "application/octet-stream", false, "Pictures were shrunk as far as they reasonably go; the rest is text.")
    }

    private fun shrinkEmbedded(data: ByteArray, png: Boolean, scale: Float, quality: Int): ByteArray? {
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).roundToInt().coerceAtLeast(8), (bmp.height * scale).roundToInt().coerceAtLeast(8), true) else bmp
        val out = ByteArrayOutputStream()
        // PNGs keep their format (documents reference them by name); only their size shrinks.
        scaled.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray().takeIf { it.size < data.size }
    }

    // ------------------------------------------------------------------ anything else

    private suspend fun archive(input: File, name: String, dir: File, o: CompressOptions, p: Progress): CompressResult {
        val out = File(dir, "$name.${o.archive.ext}")
        p.update(0f, "Compressing with ${o.archive.label}")
        val total = input.length().coerceAtLeast(1)
        var done = 0L
        val buf = ByteArray(256 * 1024)
        when (o.archive) {
            ArchiveOut.ZIP -> ZipOutputStream(out.outputStream().buffered()).use { zos ->
                zos.setLevel(Deflater.BEST_COMPRESSION)
                zos.putNextEntry(ZipEntry(name))
                input.inputStream().use { inp ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = inp.read(buf)
                        if (n < 0) break
                        zos.write(buf, 0, n)
                        done += n
                        p.update(done.toFloat() / total, "Compressing with ZIP")
                    }
                }
                zos.closeEntry()
            }
            ArchiveOut.XZ -> XZOutputStream(out.outputStream().buffered(), LZMA2Options(9).apply { mode = LZMA2Options.MODE_NORMAL }).use { xz ->
                input.inputStream().use { inp ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = inp.read(buf)
                        if (n < 0) break
                        xz.write(buf, 0, n)
                        done += n
                        p.update(done.toFloat() / total, "Compressing with XZ")
                    }
                }
            }
        }
        val ok = out.length() <= o.targetBytes
        return CompressResult(
            out, o.archive.mime, ok,
            if (!ok) "Lossless compression can't guarantee a size. This file type is already compact, or its contents don't repeat much." else null,
        )
    }

    fun formatTarget(bytes: Long): String = com.vitmap.app.ui.components.formatBytes(bytes)
}
