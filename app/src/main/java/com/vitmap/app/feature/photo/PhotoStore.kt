package com.vitmap.app.feature.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import androidx.heifwriter.HeifWriter
import com.vitmap.app.data.FileSaver
import com.vitmap.app.data.JsonFileStore
import com.vitmap.app.data.SaveCollection
import com.vitmap.app.ui.components.loadBitmap
import com.vitmap.app.vitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

@Serializable
data class PhotoLibrary(val projects: List<PhotoProject> = emptyList(), val lastExport: ExportOptions = ExportOptions())

/**
 * Photo projects: the untouched original, retouched base versions, the subject mask and assets
 * live in `files/photo/<id>/`; the edit itself is JSON in a store.
 */
class PhotoStore private constructor(private val context: Context) {
    private val store: JsonFileStore<PhotoLibrary> = context.vitmap.store("photo", PhotoLibrary.serializer()) { PhotoLibrary() }
    val library: StateFlow<PhotoLibrary?> = store.flow

    fun dir(id: String) = File(context.filesDir, "photo/$id").apply { mkdirs() }
    private fun original(id: String) = dir(id).listFiles { f -> f.name.startsWith("original.") }?.firstOrNull() ?: File(dir(id), "original.jpg")
    fun baseFile(id: String, version: Int): File = if (version <= 0) original(id) else File(dir(id), "base-$version.jpg")
    fun subjectFile(id: String) = File(dir(id), "subject.png")
    fun thumbFile(id: String) = File(dir(id), "thumb.jpg")

    suspend fun load(): PhotoLibrary = store.load()

    suspend fun create(uri: Uri): PhotoProject = withContext(Dispatchers.IO) {
        val name = FileSaver.displayName(context, uri)
        val ext = name.substringAfterLast('.', "jpg").lowercase().take(5).ifEmpty { "jpg" }
        val id = UUID.randomUUID().toString()
        val file = File(dir(id), "original.$ext")
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it, 256 * 1024) } } ?: throw IOException("Couldn't read the photo")
        val probe = loadBitmap(context, Uri.fromFile(file), 64) ?: run {
            dir(id).deleteRecursively()
            throw IOException("That file isn't a photo Android can open")
        }
        // Full size, with EXIF rotation applied.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val rotated = runCatching { ExifInterface(file.absolutePath).rotationDegrees % 180 != 0 }.getOrDefault(false)
        var w = if (rotated) bounds.outHeight else bounds.outWidth
        var h = if (rotated) bounds.outWidth else bounds.outHeight
        if (w <= 0 || h <= 0) { w = probe.width; h = probe.height }
        val project = PhotoProject(id, name.substringBeforeLast('.').ifBlank { "Photo" }, w, h)
        store.update { it.copy(projects = listOf(project) + it.projects) }
        project
    }

    suspend fun save(project: PhotoProject) {
        store.update { lib -> lib.copy(projects = lib.projects.map { if (it.id == project.id) project.copy(updated = System.currentTimeMillis()) else it }) }
    }

    suspend fun delete(id: String) {
        store.update { lib -> lib.copy(projects = lib.projects.filter { it.id != id }) }
        withContext(Dispatchers.IO) { dir(id).deleteRecursively() }
    }

    suspend fun rememberExport(o: ExportOptions) = store.update { it.copy(lastExport = o) }

    /** The base photo at [version], upright, no larger than [maxSide]. */
    suspend fun base(id: String, version: Int, maxSide: Int): Bitmap = withContext(Dispatchers.IO) {
        val file = baseFile(id, version).takeIf { it.exists() } ?: original(id)
        loadBitmap(context, Uri.fromFile(file), maxSide)?.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
            ?: throw IOException("Couldn't open the photo")
    }

    suspend fun subject(id: String): Bitmap? = withContext(Dispatchers.IO) { subjectFile(id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) } }

    /** Saves a subject mask (confidence 0…1) as the alpha channel of a PNG. */
    suspend fun saveSubject(id: String, mask: FloatArray, w: Int, h: Int) = withContext(Dispatchers.IO) {
        val px = IntArray(w * h) { ((mask[it].coerceIn(0f, 1f) * 255).roundToInt() shl 24) or 0xFFFFFF }
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        subjectFile(id).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** One more than the highest base version on disk. */
    fun nextVersion(id: String): Int =
        (dir(id).listFiles().orEmpty().mapNotNull { Regex("base-(\\d+)\\.jpg").matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1

    /** Stores a retouched full-size base and returns its version number. */
    suspend fun addBase(id: String, bitmap: Bitmap, version: Int): Int = withContext(Dispatchers.IO) {
        File(dir(id), "base-$version.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it) }
        version
    }

    /** Copies an image used as a layer or background into the project and returns its file name. */
    suspend fun addAsset(id: String, uri: Uri): Pair<String, Float> = withContext(Dispatchers.IO) {
        val bmp = loadBitmap(context, uri, 2048) ?: throw IOException("Couldn't open that image")
        val name = "asset-${UUID.randomUUID().toString().take(8)}.png"
        File(dir(id), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        name to bmp.width.toFloat() / bmp.height
    }

    suspend fun saveThumb(id: String, preview: Bitmap) = withContext(Dispatchers.IO) {
        val s = 360f / max(preview.width, preview.height)
        val t = if (s < 1f) Bitmap.createScaledBitmap(preview, (preview.width * s).roundToInt().coerceAtLeast(1), (preview.height * s).roundToInt().coerceAtLeast(1), true) else preview
        thumbFile(id).outputStream().use { t.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    // ---------------------------------------------------------------- export

    data class Exported(val file: File, val uri: Uri?, val width: Int, val height: Int, val note: String?)

    /**
     * Renders [project] at export size and saves it to Pictures/Vitmap (Android 10+). Falls back to
     * a smaller size if the phone runs out of memory.
     */
    suspend fun export(project: PhotoProject, o: ExportOptions, progress: (String) -> Unit): Exported = withContext(Dispatchers.Default) {
        val limit = if (o.maxSize > 0) o.maxSize else 8000
        var note: String? = null
        var side = limit
        var out: Bitmap? = null
        while (out == null) {
            try {
                progress("Rendering full size")
                val base = base(project.id, project.edit.baseVersion, side)
                out = PhotoRenderer.render(PhotoRenderer.Inputs(base, subject(project.id), dir(project.id)), project.edit)
            } catch (e: OutOfMemoryError) {
                System.gc()
                side = (side * 0.7f).roundToInt()
                note = "Saved at a smaller size to fit in memory"
                if (side < 800) throw IOException("Not enough memory to export")
            }
        }
        val bmp = out!!
        progress("Encoding ${o.format.label}")
        val cacheDir = File(context.cacheDir, "photo-export").apply { mkdirs() }
        val base = FileSaver.safeName(o.name.ifBlank { project.name })
        val file = File(cacheDir, "$base.${o.format.ext}")
        val hasAlpha = project.edit.background.mode == BackgroundMode.TRANSPARENT
        when (o.format) {
            ExportFormat.HEIC -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) throw IOException("HEIC needs Android 9 or newer")
                val wr = HeifWriter.Builder(file.absolutePath, bmp.width, bmp.height, HeifWriter.INPUT_MODE_BITMAP).setQuality(o.quality).build()
                wr.start(); wr.addBitmap(bmp); wr.stop(60_000); wr.close()
            }
            else -> file.outputStream().use { stream ->
                val fmt = when (o.format) {
                    ExportFormat.PNG -> Bitmap.CompressFormat.PNG
                    ExportFormat.WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                    ExportFormat.WEBP_LOSSLESS -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                    else -> Bitmap.CompressFormat.JPEG
                }
                val src = if (fmt == Bitmap.CompressFormat.JPEG && hasAlpha) flatten(bmp) else bmp
                src.compress(fmt, if (o.format.lossy) o.quality else 100, stream)
            }
        }
        if (o.keepExif && o.format in setOf(ExportFormat.JPEG, ExportFormat.PNG, ExportFormat.WEBP, ExportFormat.WEBP_LOSSLESS)) copyExif(original(project.id), file)
        progress("Saving to Pictures")
        val uri = FileSaver.saveFile(context, SaveCollection.PICTURES, file, mime = o.format.mime)
        rememberExport(o)
        Exported(file, uri, bmp.width, bmp.height, note)
    }

    private fun flatten(b: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(b, 0f, 0f, null) }
        return out
    }

    private val exifTags = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
    )

    private fun copyExif(from: File, to: File) {
        runCatching {
            val src = ExifInterface(from.absolutePath)
            val dst = ExifInterface(to.absolutePath)
            for (t in exifTags) src.getAttribute(t)?.let { dst.setAttribute(t, it) }
            dst.setAttribute(ExifInterface.TAG_SOFTWARE, "Vitmap")
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dst.saveAttributes()
        }
    }

    companion object {
        @Volatile private var instance: PhotoStore? = null

        fun get(context: Context): PhotoStore = instance ?: synchronized(this) {
            instance ?: PhotoStore(context.applicationContext).also { instance = it }
        }
    }
}
