package com.vitmap.app.feature.ytdl

import android.content.Context
import android.os.Environment
import com.vitmap.app.VitmapApp
import com.vitmap.app.data.FileSaver
import com.vitmap.app.data.SaveCollection
import com.vitmap.app.media.EngineMissingException
import com.vitmap.app.media.MediaEngine
import com.vitmap.app.vitmap
import com.vitmap.core.ytdl.YtRequest
import com.vitmap.core.ytdl.Ytdl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class YtState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

@Serializable
data class YtFile(val name: String, val uri: String, val mime: String, val size: Long)

@Serializable
data class YtItem(
    val id: String,
    val request: YtRequest,
    val title: String,
    val uploader: String? = null,
    val thumbnail: String? = null,
    val durationSec: Double = 0.0,
    val summary: String = "",
    val created: Long = System.currentTimeMillis(),
    val state: YtState = YtState.QUEUED,
    val files: List<YtFile> = emptyList(),
    val error: String? = null,
)

@Serializable
data class YtHistory(val items: List<YtItem> = emptyList(), val defaults: YtRequest? = null)

/** Progress that changes many times a second, kept in memory instead of on disk. */
data class YtLive(val stage: String = "Starting", val item: String = "", val progress: Float? = null, val speed: String = "", val eta: String = "", val audio: Boolean = false)

/**
 * Runs downloads one at a time in the application scope, so they continue when the screen is
 * closed. History and the last-used options are persisted; live progress is not.
 */
class YtQueue private constructor(private val app: VitmapApp) {
    private val store = app.store("ytdl", YtHistory.serializer()) { YtHistory() }
    val history: StateFlow<YtHistory?> = store.flow
    private val _live = MutableStateFlow<Map<String, YtLive>>(emptyMap())
    val live: StateFlow<Map<String, YtLive>> = _live.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()
    private var runner: Job? = null
    private val recovered = CompletableDeferred<Unit>()

    init {
        app.appScope.launch {
            // Anything mid-flight when the process died can't be resumed blindly; let the user retry.
            store.update { h ->
                h.copy(items = h.items.map { if (it.state == YtState.RUNNING || it.state == YtState.QUEUED) it.copy(state = YtState.FAILED, error = "Interrupted when the app was closed") else it })
            }
            recovered.complete(Unit)
        }
    }

    fun enqueue(request: YtRequest, title: String, uploader: String?, thumbnail: String?, durationSec: Double, summary: String) {
        val item = YtItem(UUID.randomUUID().toString(), request, title, uploader, thumbnail, durationSec, summary)
        app.appScope.launch {
            recovered.await()
            store.update { it.copy(items = listOf(item) + it.items, defaults = request.copy(url = "", playlistItems = "", sectionStart = 0.0, sectionEnd = 0.0, formatId = "")) }
            kick()
            // Started after the item is stored, so the service never sees an empty queue and stops.
            DownloadService.start(app)
        }
    }

    fun saveDefaults(request: YtRequest) {
        app.appScope.launch { store.update { it.copy(defaults = request.copy(url = "", playlistItems = "", sectionStart = 0.0, sectionEnd = 0.0, formatId = "")) } }
    }

    fun retry(id: String) {
        app.appScope.launch {
            recovered.await()
            setItem(id) { it.copy(state = YtState.QUEUED, error = null) }
            kick()
            DownloadService.start(app)
        }
    }

    fun cancel(id: String) {
        val job = jobs[id]
        if (job != null) job.cancel() else app.appScope.launch { setItem(id) { if (it.state == YtState.QUEUED) it.copy(state = YtState.CANCELLED) else it } }
    }

    fun remove(id: String) {
        jobs[id]?.cancel()
        app.appScope.launch { store.update { h -> h.copy(items = h.items.filter { it.id != id }) } }
    }

    fun clearFinished() {
        app.appScope.launch { store.update { h -> h.copy(items = h.items.filter { it.state == YtState.QUEUED || it.state == YtState.RUNNING }) } }
    }

    suspend fun load(): YtHistory = store.load()

    private suspend fun setItem(id: String, change: (YtItem) -> YtItem) {
        store.update { h -> h.copy(items = h.items.map { if (it.id == id) change(it) else it }) }
    }

    private fun setLive(id: String, change: (YtLive) -> YtLive) = _live.update { m -> m + (id to change(m[id] ?: YtLive())) }

    @Synchronized
    private fun kick() {
        if (runner?.isActive == true) return
        runner = app.appScope.launch {
            while (true) {
                // Items are stored newest first, so the oldest queued one is last.
                val next = store.load().items.lastOrNull { it.state == YtState.QUEUED } ?: break
                val job = launch { download(next) }
                jobs[next.id] = job
                job.join()
                jobs.remove(next.id)
            }
        }
    }

    private suspend fun download(item: YtItem) {
        val dir = File(app.cacheDir, "ytdl/${item.id}").apply { deleteRecursively(); mkdirs() }
        setItem(item.id) { it.copy(state = YtState.RUNNING, error = null) }
        setLive(item.id) { YtLive() }
        var lastError: String? = null
        try {
            val r = MediaEngine.ytdlp(
                app,
                Ytdl.args(item.request, dir.absolutePath),
                workDir = dir,
                onStdout = { line -> onLine(item.id, line) },
                onStderr = { line ->
                    onLine(item.id, line)
                    if (line.startsWith("ERROR")) lastError = line
                },
            )
            if (!r.ok) throw IOException(friendlyError(lastError ?: r.stderr.lines().lastOrNull { it.isNotBlank() } ?: "yt-dlp stopped with code ${r.exitCode}"))
            setLive(item.id) { it.copy(stage = "Saving to your device", progress = null, speed = "", eta = "") }
            val outputs = dir.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && it.length() > 0 }.sortedBy { it.name }
            if (outputs.isEmpty()) throw IOException("yt-dlp finished without producing a file")
            val saved = outputs.map { save(it) }
            setItem(item.id) { it.copy(state = YtState.DONE, files = saved, error = null) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { setItem(item.id) { it.copy(state = YtState.CANCELLED) } }
            throw e
        } catch (e: EngineMissingException) {
            setItem(item.id) { it.copy(state = YtState.FAILED, error = "Download the yt-dlp pack first") }
        } catch (e: Exception) {
            setItem(item.id) { it.copy(state = YtState.FAILED, error = e.message ?: e.javaClass.simpleName) }
        } finally {
            _live.update { it - item.id }
            withContext(NonCancellable) { dir.deleteRecursively() }
        }
    }

    private fun onLine(id: String, line: String) {
        val p = Ytdl.parseProgress(line)
        if (p != null) {
            setLive(id) {
                it.copy(
                    stage = if (p.audioStream) "Downloading audio" else "Downloading video",
                    progress = p.fraction,
                    speed = Ytdl.formatSpeed(p.speed),
                    eta = Ytdl.formatEta(p.etaSec),
                    audio = p.audioStream,
                )
            }
            return
        }
        val stage = Ytdl.stageFor(line) ?: return
        setLive(id) {
            when {
                stage.startsWith("Item ") || stage.startsWith("Video ") -> it.copy(item = stage)
                stage == "Downloading" -> it.copy(stage = stage)
                else -> it.copy(stage = stage, progress = null, speed = "", eta = "")
            }
        }
    }

    private fun friendlyError(raw: String): String {
        val msg = raw.removePrefix("ERROR:").trim().replace(Regex("^\\[[^]]+] [\\w-]+: "), "")
        return when {
            "Sign in to confirm" in msg -> "YouTube wants a sign-in to confirm you're not a bot. Try again later, or pass cookies under Advanced → Extra options (--cookies FILE)."
            "Private video" in msg -> "This video is private."
            "Video unavailable" in msg -> "This video is unavailable in your region or was removed."
            "Unsupported URL" in msg -> "This link isn't from a supported site."
            "Unable to download" in msg && "HTTP Error 403" in msg -> "The site refused the download (HTTP 403). Updating yt-dlp usually fixes this."
            "is not a valid URL" in msg -> "That doesn't look like a link."
            else -> msg
        }
    }

    private suspend fun save(f: File): YtFile {
        val mime = FileSaver.mimeFor(f.name)
        val collection = when {
            mime.startsWith("video/") -> SaveCollection.MOVIES
            mime.startsWith("audio/") -> SaveCollection.MUSIC
            else -> SaveCollection.DOWNLOADS
        }
        val uri = FileSaver.saveFile(app, collection, f, mime = mime)
        if (uri != null) return YtFile(f.name, uri.toString(), mime, f.length())
        // Before Android 10 there's no MediaStore folder to write to; keep it in the app's media folder.
        val folder = when (collection) {
            SaveCollection.MOVIES -> Environment.DIRECTORY_MOVIES
            SaveCollection.MUSIC -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        val dest = File(app.getExternalFilesDir(folder) ?: File(app.filesDir, "downloads").apply { mkdirs() }, f.name)
        withContext(Dispatchers.IO) { f.copyTo(dest, overwrite = true) }
        return YtFile(f.name, FileSaver.uriFor(app, dest).toString(), mime, dest.length())
    }

    companion object {
        @Volatile private var instance: YtQueue? = null

        fun get(context: Context): YtQueue = instance ?: synchronized(this) {
            instance ?: YtQueue(context.vitmap).also { instance = it }
        }
    }
}
