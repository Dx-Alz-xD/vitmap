package com.vitmap.app.data.packs

import android.content.Context
import android.os.Build
import android.system.Os
import com.google.android.gms.common.api.OptionalModuleApi
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.vitmap.core.zip.ZipExtractor
import com.vitmap.core.zip.ZipIndex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/** Where a pack's files come from and how they're laid out once installed. */
sealed interface PackState {
    data object Checking : PackState
    data object Missing : PackState
    data class Working(val done: Long, val total: Long, val step: String) : PackState {
        val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
    }
    data class Ready(val bytes: Long) : PackState
    data class Failed(val message: String) : PackState
}

enum class PackGroup(val title: String) {
    LANGUAGE("Translator"),
    MEDIA("Media engine"),
    CODE("Code runtimes"),
    VISION("Vision models"),
}

/**
 * An optional download (a model, a dictionary, a runtime). Nothing in a pack ships inside the
 * APK; it's fetched the first time a feature needs it and can be removed again from Settings.
 */
class Pack(
    val id: String,
    val title: String,
    val description: String,
    /** Approximate download size, shown before the user commits. */
    val approxBytes: Long,
    val group: PackGroup,
    val installer: PackInstaller,
    /** Other packs that must be installed first. */
    val requires: List<Pack> = emptyList(),
) {
    /** Download size including packs this one depends on. */
    val totalBytes: Long get() = approxBytes + requires.sumOf { it.approxBytes }
}

interface PackInstaller {
    suspend fun isInstalled(context: Context): Boolean
    suspend fun install(context: Context, report: (PackState.Working) -> Unit)
    suspend fun uninstall(context: Context)
    suspend fun diskBytes(context: Context): Long
    /** False when removal is handed to the system (Play services modules). */
    val removable: Boolean get() = true
}

/** A file to fetch. Several [urls] act as mirrors, tried in order. */
data class RemoteFile(
    val urls: List<String>,
    /** Destination inside the pack folder: a file, or a folder when [unzip] is set. */
    val path: String,
    val sha256: String? = null,
    val gunzip: Boolean = false,
    /** Download only this entry out of a remote zip (via byte ranges) instead of the whole archive. */
    val zipEntry: String? = null,
    /** Extract the downloaded zip (keeping symlinks) into [path]. */
    val unzip: Boolean = false,
    val label: String? = null,
)

/** Installs a set of [RemoteFile]s into the pack's private folder. */
class FilesInstaller(private val id: String, private val version: String, private val files: (Context) -> List<RemoteFile>) : PackInstaller {
    fun dir(context: Context) = File(context.noBackupFilesDir, "packs/$id")
    private fun marker(context: Context) = File(dir(context), ".installed")

    override suspend fun isInstalled(context: Context): Boolean = withContext(Dispatchers.IO) {
        marker(context).let { it.exists() && it.readText().trim() == version }
    }

    override suspend fun install(context: Context, report: (PackState.Working) -> Unit) = withContext(Dispatchers.IO) {
        val dir = dir(context)
        val staging = File(context.noBackupFilesDir, "packs/.staging-$id")
        staging.mkdirs()
        val list = files(context)
        list.forEachIndexed { i, f ->
            val step = f.label ?: if (list.size > 1) "File ${i + 1} of ${list.size}" else "Downloading"
            val download = File(staging, f.path.replace('/', '_') + ".download")
            if (f.zipEntry != null) {
                fetchZipEntry(f, download) { d, t -> report(PackState.Working(d, t, step)) }
            } else {
                Http.download(f.urls, download, f.sha256) { d, t -> report(PackState.Working(d, t, step)) }
            }
            val dest = File(staging, f.path)
            dest.parentFile?.mkdirs()
            when {
                f.unzip -> {
                    report(PackState.Working(0, 0, "Unpacking"))
                    dest.deleteRecursively()
                    dest.mkdirs()
                    ZipExtractor.extract(download, dest, symlink = { target, link -> Os.symlink(target, link.absolutePath) }) { d, t ->
                        report(PackState.Working(d, t, "Unpacking"))
                    }
                    download.delete()
                }
                f.gunzip -> {
                    report(PackState.Working(0, 0, "Unpacking"))
                    GZIPInputStream(download.inputStream().buffered(256 * 1024)).use { input -> dest.outputStream().use { input.copyTo(it, 256 * 1024) } }
                    download.delete()
                }
                else -> {
                    dest.delete()
                    if (!download.renameTo(dest)) throw IOException("Couldn't move ${dest.name}")
                }
            }
        }
        dir.deleteRecursively()
        dir.parentFile?.mkdirs()
        if (!staging.renameTo(dir)) throw IOException("Couldn't finish installing $id")
        File(dir, ".installed").writeText(version)
    }

    private suspend fun fetchZipEntry(f: RemoteFile, dest: File, progress: (Long, Long) -> Unit) {
        var last: Exception? = null
        for (url in f.urls) {
            try {
                val size = Http.contentLength(url)
                if (size <= 0) throw IOException("Unknown archive size")
                val index = ZipIndex.read(size) { off, len -> blockingRange(url, off until off + len) }
                val item = index[f.zipEntry!!] ?: throw IOException("${f.zipEntry} not found in archive")
                val header = blockingRange(url, item.localHeaderOffset until item.localHeaderOffset + 30)
                val start = ZipIndex.dataOffset(item, header)
                val raw = File(dest.parentFile, dest.name + ".raw")
                Http.download(listOf(url), raw, range = start until start + item.compressedSize) { d, _ -> progress(d, item.compressedSize) }
                ZipIndex.decode(item, raw.inputStream().buffered(256 * 1024)).use { input -> dest.outputStream().use { input.copyTo(it, 256 * 1024) } }
                raw.delete()
                if (dest.length() != item.size) throw IOException("Unpacked size mismatch for ${f.zipEntry}")
                return
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("No download location")
    }

    private fun blockingRange(url: String, range: LongRange): ByteArray {
        val c = Http.connect(url, range)
        try {
            if (c.responseCode != 206) throw IOException("Server ignored the byte range (HTTP ${c.responseCode})")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    override suspend fun uninstall(context: Context) {
        withContext(Dispatchers.IO) {
            dir(context).deleteRecursively()
            File(context.noBackupFilesDir, "packs/.staging-$id").deleteRecursively()
        }
    }

    override suspend fun diskBytes(context: Context): Long = withContext(Dispatchers.IO) {
        dir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}

/** A Google Play services optional module (ML Kit models delivered by the system). */
class PlayModuleInstaller(private val api: (Context) -> OptionalModuleApi) : PackInstaller {
    override val removable: Boolean get() = false

    override suspend fun isInstalled(context: Context): Boolean = runCatching {
        ModuleInstall.getClient(context).areModulesAvailable(api(context)).await().areModulesAvailable()
    }.getOrDefault(false)

    override suspend fun install(context: Context, report: (PackState.Working) -> Unit) {
        val client = ModuleInstall.getClient(context)
        val done = CompletableDeferred<Unit>()
        val listener = InstallStatusListener { update ->
            update.progressInfo?.let { report(PackState.Working(it.bytesDownloaded, it.totalBytesToDownload, "Downloading via Google Play")) }
            when (update.installState) {
                ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> done.complete(Unit)
                ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> done.completeExceptionally(IOException("Download cancelled"))
                ModuleInstallStatusUpdate.InstallState.STATE_FAILED -> done.completeExceptionally(IOException("Google Play couldn't install the model (error ${update.errorCode})"))
            }
        }
        val request = ModuleInstallRequest.newBuilder().addApi(api(context)).setListener(listener).build()
        try {
            report(PackState.Working(0, 0, "Asking Google Play"))
            val response = client.installModules(request).await()
            if (!response.areModulesAlreadyInstalled()) done.await()
        } finally {
            runCatching { client.unregisterListener(listener) }
        }
    }

    override suspend fun uninstall(context: Context) {
        runCatching { ModuleInstall.getClient(context).releaseModules(api(context)).await() }
    }

    override suspend fun diskBytes(context: Context): Long = 0
}

/** Tracks install state for every pack and runs downloads in the application scope. */
class PackManager(private val context: Context, private val scope: CoroutineScope) {
    private val states = ConcurrentHashMap<String, MutableStateFlow<PackState>>()
    private val jobs = ConcurrentHashMap<String, Job>()

    private fun flow(pack: Pack): MutableStateFlow<PackState> {
        var created = false
        val f = states.getOrPut(pack.id) { created = true; MutableStateFlow<PackState>(PackState.Checking) }
        if (created) refresh(pack)
        return f
    }

    fun state(pack: Pack): StateFlow<PackState> = flow(pack).asStateFlow()

    fun refresh(pack: Pack) {
        val f = states.getOrPut(pack.id) { MutableStateFlow<PackState>(PackState.Checking) }
        if (jobs[pack.id]?.isActive == true) return
        scope.launch(Dispatchers.IO) {
            f.value = if (pack.installer.isInstalled(context)) PackState.Ready(pack.installer.diskBytes(context)) else PackState.Missing
        }
    }

    suspend fun isReady(pack: Pack): Boolean {
        val f = flow(pack)
        return f.first { it !is PackState.Checking } is PackState.Ready
    }

    fun install(pack: Pack) {
        if (jobs[pack.id]?.isActive == true) return
        val f = flow(pack)
        jobs[pack.id] = scope.launch(Dispatchers.IO) {
            try {
                for (dep in pack.requires) {
                    if (!dep.installer.isInstalled(context)) {
                        val df = flow(dep)
                        dep.installer.install(context) { w ->
                            df.value = w
                            f.value = w.copy(step = "${dep.title}: ${w.step}")
                        }
                        df.value = PackState.Ready(dep.installer.diskBytes(context))
                    }
                }
                f.value = PackState.Working(0, 0, "Starting")
                pack.installer.install(context) { f.value = it }
                f.value = PackState.Ready(pack.installer.diskBytes(context))
            } catch (e: kotlinx.coroutines.CancellationException) {
                f.value = PackState.Missing
                throw e
            } catch (e: Exception) {
                f.value = PackState.Failed(e.localizedMessage ?: e.javaClass.simpleName)
            }
        }
    }

    /** Installs [pack] (and its dependencies) and suspends until it's ready. Returns false on failure. */
    suspend fun ensure(pack: Pack): Boolean {
        if (isReady(pack)) return true
        install(pack)
        return flow(pack).first { it is PackState.Ready || it is PackState.Failed || it is PackState.Missing } is PackState.Ready
    }

    fun cancel(pack: Pack) {
        jobs.remove(pack.id)?.cancel()
        flow(pack).value = PackState.Missing
    }

    fun remove(pack: Pack) {
        cancel(pack)
        scope.launch(Dispatchers.IO) {
            pack.installer.uninstall(context)
            refresh(pack)
        }
    }
}

/** ABI folder name matching the native libraries the system extracted for this install. */
fun Context.nativeAbi(): String {
    val dir = File(applicationInfo.nativeLibraryDir).name
    return when (dir) {
        "arm64" -> "arm64-v8a"
        "arm" -> "armeabi-v7a"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
    }
}
