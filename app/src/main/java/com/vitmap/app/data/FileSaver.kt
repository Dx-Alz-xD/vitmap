package com.vitmap.app.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Shared-storage folders a tool can save into. */
enum class SaveCollection(val folder: String) {
    DOWNLOADS(Environment.DIRECTORY_DOWNLOADS),
    PICTURES(Environment.DIRECTORY_PICTURES),
    MOVIES(Environment.DIRECTORY_MOVIES),
    MUSIC(Environment.DIRECTORY_MUSIC),
    DOCUMENTS(Environment.DIRECTORY_DOCUMENTS),
}

/** Saving, sharing and reading files across tools. */
object FileSaver {
    /**
     * Writes a new file into shared storage under `<collection>/Vitmap/<subfolder>` and returns its
     * content Uri. Needs Android 10+; on older versions returns null and callers fall back to the
     * system file picker.
     */
    suspend fun save(
        context: Context,
        collection: SaveCollection,
        displayName: String,
        mime: String,
        subfolder: String? = null,
        write: (OutputStream) -> Unit,
    ): Uri? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
        val resolver = context.contentResolver
        val base = when (collection) {
            SaveCollection.PICTURES -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            SaveCollection.MOVIES -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            SaveCollection.MUSIC -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            SaveCollection.DOWNLOADS, SaveCollection.DOCUMENTS -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        // Audio/video/image collections reject mismatched mime types; anything else goes to Downloads.
        val ok = when (collection) {
            SaveCollection.PICTURES -> mime.startsWith("image/")
            SaveCollection.MOVIES -> mime.startsWith("video/")
            SaveCollection.MUSIC -> mime.startsWith("audio/")
            else -> true
        }
        val target = if (ok) base else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val folder = if (ok && collection != SaveCollection.DOCUMENTS) collection.folder else Environment.DIRECTORY_DOWNLOADS
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, listOfNotNull(folder, "Vitmap", subfolder).joinToString("/"))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(target, values) ?: throw IOException("Couldn't create $displayName")
        try {
            resolver.openOutputStream(uri)?.use(write) ?: throw IOException("Couldn't write $displayName")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    suspend fun saveFile(context: Context, collection: SaveCollection, file: File, displayName: String = file.name, mime: String = mimeFor(file.name), subfolder: String? = null): Uri? =
        save(context, collection, displayName, mime, subfolder) { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }

    /** Copies [file] to a Uri the user picked with the system "create document" picker. */
    suspend fun writeTo(context: Context, uri: Uri, file: File) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
            ?: throw IOException("Couldn't open the destination")
    }

    /** A content Uri for a private file, for sharing or opening in another app. */
    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun share(context: Context, uri: Uri, mime: String, title: String = "Share") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareFiles(context: Context, uris: List<Uri>, mime: String, title: String = "Share") {
        if (uris.size == 1) return share(context, uris[0], mime, title)
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun open(context: Context, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path ?: "file").name
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    fun size(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") return File(uri.path ?: "").length()
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull() ?: runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1
    }

    fun mime(context: Context, uri: Uri): String =
        context.contentResolver.getType(uri) ?: mimeFor(displayName(context, uri))

    fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: EXTRA_MIMES[ext] ?: "application/octet-stream"
    }

    private val EXTRA_MIMES = mapOf(
        "mkv" to "video/x-matroska", "webm" to "video/webm", "opus" to "audio/ogg", "flac" to "audio/flac", "m4a" to "audio/mp4",
        "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif", "qoi" to "image/qoi", "tga" to "image/x-tga",
        "7z" to "application/x-7z-compressed", "xz" to "application/x-xz", "md" to "text/markdown", "yaml" to "text/yaml", "yml" to "text/yaml",
        "toml" to "application/toml", "srt" to "application/x-subrip", "vtt" to "text/vtt", "ass" to "text/x-ssa",
    )

    /** Copies a content Uri into the cache so libraries that need a real file can read it. */
    suspend fun copyToCache(context: Context, uri: Uri, name: String = displayName(context, uri), folder: String = "imports"): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, folder).apply { mkdirs() }
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
        val out = File(dir, "${System.nanoTime()}-$safe")
        context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it, 256 * 1024) } }
            ?: throw IOException("Couldn't read $name")
        out
    }

    /** A file name with characters other apps choke on removed. */
    fun safeName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().ifBlank { "Untitled" }
}
