package com.vitmap.app.media

import android.content.Context
import com.vitmap.app.data.packs.FilesInstaller
import com.vitmap.app.data.packs.Pack
import com.vitmap.app.data.packs.PackGroup
import com.vitmap.app.data.packs.RemoteFile
import com.vitmap.app.data.packs.nativeAbi
import com.vitmap.app.vitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

data class StreamInfo(val index: Int, val type: String, val codec: String, val width: Int, val height: Int, val fps: Double, val sampleRate: Int, val channels: Int, val bitrate: Long, val language: String?)

data class MediaInfo(val durationSec: Double, val formatName: String, val bitrate: Long, val size: Long, val streams: List<StreamInfo>, val tags: Map<String, String>) {
    val video: StreamInfo? get() = streams.firstOrNull { it.type == "video" && it.codec !in setOf("mjpeg", "png", "bmp") }
    val audio: StreamInfo? get() = streams.firstOrNull { it.type == "audio" }
    val hasVideo: Boolean get() = video != null
    val hasAudio: Boolean get() = audio != null
}

/**
 * FFmpeg, ffprobe, Python and QuickJS built for Android (from the youtubedl-android project).
 * The small launcher executables live in the APK's native library folder, which Android allows to
 * run; the large shared libraries they load are packs downloaded on first use straight from the
 * published Maven Central artifacts.
 */
object MediaEngine {
    private const val VERSION = "0.18.1"
    private const val GROUP = "io/github/junkfood02/youtubedl-android"

    private fun mavenUrls(module: String): List<String> = listOf(
        "https://repo1.maven.org/maven2/$GROUP/$module/$VERSION/$module-$VERSION.aar",
        "https://maven-central.storage-download.googleapis.com/maven2/$GROUP/$module/$VERSION/$module-$VERSION.aar",
        "https://repo.maven.apache.org/maven2/$GROUP/$module/$VERSION/$module-$VERSION.aar",
    )

    private val ffmpegInstaller = FilesInstaller("ffmpeg", VERSION) { ctx ->
        listOf(RemoteFile(mavenUrls("ffmpeg"), path = "root", zipEntry = "jni/${ctx.nativeAbi()}/libffmpeg.zip.so", unzip = true, label = "Downloading FFmpeg"))
    }

    private val pythonInstaller = FilesInstaller("python", VERSION) { ctx ->
        listOf(RemoteFile(mavenUrls("library"), path = "root", zipEntry = "jni/${ctx.nativeAbi()}/libpython.zip.so", unzip = true, label = "Downloading Python"))
    }

    private val ytdlpInstaller = FilesInstaller("ytdlp", "stable") {
        listOf(
            RemoteFile(
                listOf("https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"),
                path = "yt-dlp",
                label = "Downloading yt-dlp",
            ),
        )
    }

    val ffmpeg = Pack(
        id = "ffmpeg",
        title = "FFmpeg media engine",
        description = "Converts and compresses video, audio and images between hundreds of formats (H.264, H.265, VP9, AV1, MP3, AAC, Opus, FLAC and more).",
        approxBytes = 36_000_000,
        group = PackGroup.MEDIA,
        installer = ffmpegInstaller,
    )

    val python = Pack(
        id = "python",
        title = "Python 3.12 runtime",
        description = "A full CPython interpreter with its standard library. Runs yt-dlp and Python code in Code Writer.",
        approxBytes = 14_100_000,
        group = PackGroup.CODE,
        installer = pythonInstaller,
    )

    val ytdlp = Pack(
        id = "ytdlp",
        title = "yt-dlp downloader",
        description = "The open-source video downloader, kept current from its official releases. Needs Python and FFmpeg.",
        approxBytes = 3_200_000,
        group = PackGroup.MEDIA,
        installer = ytdlpInstaller,
        requires = listOf(python, ffmpeg),
    )

    fun nativeDir(context: Context) = File(context.applicationInfo.nativeLibraryDir)
    fun ffmpegBin(context: Context) = File(nativeDir(context), "libffmpeg.so")
    fun ffprobeBin(context: Context) = File(nativeDir(context), "libffprobe.so")
    fun pythonBin(context: Context) = File(nativeDir(context), "libpython.so")
    fun quickJsBin(context: Context) = File(nativeDir(context), "libqjs.so")
    fun ytdlpScript(context: Context) = File(ytdlpInstaller.dir(context), "yt-dlp")
    private fun ffmpegRoot(context: Context) = File(ffmpegInstaller.dir(context), "root")
    private fun pythonRoot(context: Context) = File(pythonInstaller.dir(context), "root")

    /** Environment the Termux-built binaries need: their libraries, CA certificates and Python home. */
    fun environment(context: Context): Map<String, String> {
        val py = pythonRoot(context)
        return mapOf(
            "LD_LIBRARY_PATH" to listOf(File(py, "usr/lib"), File(ffmpegRoot(context), "usr/lib")).joinToString(":") { it.absolutePath },
            "SSL_CERT_FILE" to File(py, "usr/etc/tls/cert.pem").absolutePath,
            "PYTHONHOME" to File(py, "usr").absolutePath,
            "PYTHONIOENCODING" to "utf-8",
            "PYTHONUNBUFFERED" to "1",
            "HOME" to context.filesDir.absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath,
            "PATH" to (System.getenv("PATH").orEmpty() + ":" + nativeDir(context).absolutePath),
            "LANG" to "C.UTF-8",
        )
    }

    /**
     * Runs a process, streaming its output line by line. Cancelling the calling coroutine kills
     * the process. [stdin] is written then closed.
     */
    suspend fun run(
        context: Context,
        command: List<String>,
        workDir: File = context.cacheDir,
        stdin: String? = null,
        extraEnv: Map<String, String> = emptyMap(),
        onStdout: (String) -> Unit = {},
        onStderr: (String) -> Unit = {},
    ): ProcessResult = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(command).directory(workDir)
        pb.environment().putAll(environment(context))
        pb.environment().putAll(extraEnv)
        val process = try {
            pb.start()
        } catch (e: IOException) {
            throw IOException("Couldn't start ${File(command.first()).name}: ${e.message}")
        }
        val out = StringBuilder()
        val err = StringBuilder()
        try {
            coroutineScope {
                launch {
                    process.outputStream.use { os -> if (stdin != null) os.write(stdin.toByteArray()) }
                }
                val a = async {
                    process.inputStream.bufferedReader().useLines { lines -> lines.forEach { l -> if (out.length < 2_000_000) out.appendLine(l); onStdout(l) } }
                }
                val b = async {
                    // ffmpeg rewrites its status line with \r, so split on both.
                    val reader = process.errorStream.bufferedReader()
                    val line = StringBuilder()
                    while (true) {
                        val c = reader.read()
                        if (c < 0) break
                        if (c == '\n'.code || c == '\r'.code) {
                            if (line.isNotEmpty()) {
                                val l = line.toString()
                                if (err.length < 2_000_000) err.appendLine(l)
                                onStderr(l)
                                line.setLength(0)
                            }
                        } else {
                            line.append(c.toChar())
                        }
                    }
                    if (line.isNotEmpty()) { err.appendLine(line); onStderr(line.toString()) }
                }
                try {
                    while (process.isAlive) kotlinx.coroutines.delay(50)
                } catch (e: CancellationException) {
                    // Killing the process closes its pipes, which lets the reader coroutines finish.
                    process.destroy()
                    if (process.isAlive) process.destroyForcibly()
                    throw e
                }
                a.await()
                b.await()
            }
            ProcessResult(process.waitFor(), out.toString(), err.toString())
        } catch (e: CancellationException) {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            throw e
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    suspend fun ffmpeg(context: Context, args: List<String>, onProgress: (seconds: Double) -> Unit = {}, onLog: (String) -> Unit = {}): ProcessResult {
        requireReady(context, ffmpeg)
        return run(
            context,
            listOf(ffmpegBin(context).absolutePath, "-hide_banner", "-nostdin", "-y", "-progress", "pipe:1", "-nostats") + args,
            onStdout = { line ->
                if (line.startsWith("out_time_us=") || line.startsWith("out_time_ms=")) {
                    line.substringAfter('=').toLongOrNull()?.let { onProgress(it / 1_000_000.0) }
                }
            },
            onStderr = onLog,
        )
    }

    suspend fun probe(context: Context, file: File): MediaInfo? {
        requireReady(context, ffmpeg)
        val r = run(context, listOf(ffprobeBin(context).absolutePath, "-v", "error", "-print_format", "json", "-show_format", "-show_streams", file.absolutePath))
        if (!r.ok) return null
        return runCatching { parseProbe(r.stdout, file.length()) }.getOrNull()
    }

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parseProbe(text: String, size: Long): MediaInfo {
        val root = lenient.parseToJsonElement(text).jsonObject
        val format = root["format"]?.jsonObject
        fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
        val streams = root["streams"]?.jsonArray?.map { el ->
            val s = el.jsonObject
            val rate = s.str("avg_frame_rate")?.split('/')?.let { p -> if (p.size == 2) (p[0].toDoubleOrNull() ?: 0.0) / (p[1].toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0) else 0.0 } ?: 0.0
            StreamInfo(
                index = s["index"]?.jsonPrimitive?.intOrNull ?: 0,
                type = s.str("codec_type") ?: "",
                codec = s.str("codec_name") ?: "",
                width = s["width"]?.jsonPrimitive?.intOrNull ?: 0,
                height = s["height"]?.jsonPrimitive?.intOrNull ?: 0,
                fps = rate,
                sampleRate = s.str("sample_rate")?.toIntOrNull() ?: 0,
                channels = s["channels"]?.jsonPrimitive?.intOrNull ?: 0,
                bitrate = s.str("bit_rate")?.toLongOrNull() ?: 0,
                language = (s["tags"] as? JsonObject)?.str("language"),
            )
        }.orEmpty()
        val tags = (format?.get("tags") as? JsonObject)?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }.orEmpty()
        return MediaInfo(
            durationSec = format?.get("duration")?.jsonPrimitive?.doubleOrNull ?: format?.str("duration")?.toDoubleOrNull() ?: 0.0,
            formatName = format?.str("format_name") ?: "",
            bitrate = format?.str("bit_rate")?.toLongOrNull() ?: 0,
            size = size,
            streams = streams,
            tags = tags,
        )
    }

    suspend fun python(context: Context, args: List<String>, workDir: File = context.cacheDir, stdin: String? = null, onStdout: (String) -> Unit = {}, onStderr: (String) -> Unit = {}): ProcessResult {
        requireReady(context, python)
        return run(context, listOf(pythonBin(context).absolutePath) + args, workDir, stdin, onStdout = onStdout, onStderr = onStderr)
    }

    suspend fun ytdlp(context: Context, args: List<String>, workDir: File, onStdout: (String) -> Unit = {}, onStderr: (String) -> Unit = {}): ProcessResult {
        requireReady(context, ytdlp)
        val full = listOf(
            pythonBin(context).absolutePath, ytdlpScript(context).absolutePath,
            "--ffmpeg-location", ffmpegBin(context).absolutePath,
            "--js-runtimes", "quickjs:${quickJsBin(context).absolutePath}",
            // YouTube's player challenges are solved by small scripts; fetch them if they aren't bundled.
            "--remote-components", "ejs:github",
            "--no-cache-dir",
        ) + args
        return run(context, full, workDir, onStdout = onStdout, onStderr = onStderr)
    }

    suspend fun ytdlpVersion(context: Context): String? {
        requireReady(context, ytdlp)
        val r = run(context, listOf(pythonBin(context).absolutePath, ytdlpScript(context).absolutePath, "--version"))
        return r.stdout.trim().takeIf { r.ok && it.isNotEmpty() }
    }

    /** Self-updates the yt-dlp zipapp in place; returns its last line of output. */
    suspend fun updateYtdlp(context: Context): String {
        requireReady(context, ytdlp)
        val r = run(context, listOf(pythonBin(context).absolutePath, ytdlpScript(context).absolutePath, "-U"))
        val last = (r.stdout + "\n" + r.stderr).lines().lastOrNull { it.isNotBlank() }?.removePrefix("ERROR:")?.trim()
        if (!r.ok) throw IOException(last ?: "Update failed")
        return last ?: "yt-dlp is up to date"
    }

    private suspend fun requireReady(context: Context, pack: Pack) {
        if (!context.vitmap.packs.isReady(pack)) throw EngineMissingException(pack)
        for (p in pack.requires) if (!context.vitmap.packs.isReady(p)) throw EngineMissingException(pack)
    }
}

class EngineMissingException(val pack: Pack) : Exception("${pack.title} isn't downloaded yet")
