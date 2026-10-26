package com.vitmap.app.feature.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.vitmap.app.ui.components.loadBitmap
import com.vitmap.app.vitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Scanned and imported documents, each in its own folder under `files/scans/<id>` with a
 * `doc.json`, the original photos and copies of imported PDFs. Shared by the scanner and PDF tools.
 */
class ScanRepository private constructor(private val context: Context) {
    private val root = File(context.filesDir, "scans").apply { mkdirs() }
    private val json = context.vitmap.json
    private val mutex = Mutex()
    private val _docs = MutableStateFlow<List<ScanDoc>>(emptyList())
    val docs: StateFlow<List<ScanDoc>> = _docs.asStateFlow()
    private var loaded = false

    val prefs = context.vitmap.store("scan-prefs", ScanPrefs.serializer()) { ScanPrefs() }

    fun dir(id: String) = File(root, id)
    fun file(docId: String, name: String) = File(dir(docId), name)

    suspend fun load() = mutex.withLock {
        if (loaded) return@withLock
        val list = withContext(Dispatchers.IO) {
            root.listFiles()?.mapNotNull { d ->
                val f = File(d, "doc.json")
                if (!f.exists()) null else runCatching { json.decodeFromString(ScanDoc.serializer(), f.readText()) }.getOrNull()
            }.orEmpty()
        }
        _docs.value = list.sortedByDescending { it.updated }
        loaded = true
    }

    fun get(id: String): ScanDoc? = _docs.value.firstOrNull { it.id == id }

    suspend fun save(doc: ScanDoc): ScanDoc {
        val d = doc.copy(updated = System.currentTimeMillis())
        withContext(Dispatchers.IO) {
            val dir = dir(d.id).apply { mkdirs() }
            val tmp = File(dir, "doc.json.tmp")
            tmp.writeText(json.encodeToString(ScanDoc.serializer(), d))
            tmp.renameTo(File(dir, "doc.json"))
        }
        _docs.update { list -> (listOf(d) + list.filter { it.id != d.id }).sortedByDescending { it.updated } }
        return d
    }

    suspend fun update(id: String, change: (ScanDoc) -> ScanDoc): ScanDoc? {
        val current = get(id) ?: return null
        return save(change(current))
    }

    suspend fun create(name: String = defaultName()): ScanDoc = save(ScanDoc(name = name))

    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) { dir(id).deleteRecursively() }
        _docs.update { list -> list.filter { it.id != id } }
        PageRenderer.forget(id)
    }

    suspend fun duplicate(id: String): ScanDoc? {
        val src = get(id) ?: return null
        val copy = src.copy(id = newId(), name = src.name + " (copy)", created = System.currentTimeMillis())
        withContext(Dispatchers.IO) {
            dir(src.id).copyRecursively(dir(copy.id), overwrite = true)
        }
        return save(copy)
    }

    /** Stores a photo (bitmap) for [docId] and returns its file name. */
    suspend fun putImage(docId: String, bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val name = "img-${newId()}.jpg"
        val f = file(docId, name)
        f.parentFile?.mkdirs()
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 93, it) }
        name
    }

    /** Copies an image from another app into [docId] (upright, at most 4000 px). */
    suspend fun importImage(docId: String, uri: Uri): String? {
        val bmp = loadBitmap(context, uri, 4000) ?: return null
        return putImage(docId, bmp)
    }

    /** Stores a PNG with transparency (signatures, stickers) and returns its file name. */
    suspend fun putPng(docId: String, bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val name = "art-${newId()}.png"
        file(docId, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        name
    }

    /** Copies a PDF into [docId]; returns the stored name and its page count. */
    suspend fun importPdf(docId: String, uri: Uri): Pair<String, Int> = withContext(Dispatchers.IO) {
        val name = "src-${newId()}.pdf"
        val f = file(docId, name)
        f.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } } ?: throw IOException("Couldn't read the PDF")
        val count = try {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd -> PdfRenderer(pfd).use { it.pageCount } }
        } catch (e: SecurityException) {
            f.delete()
            throw IOException("This PDF is password-protected. Unlock it in PDF Tools first.")
        } catch (e: Exception) {
            f.delete()
            throw IOException("That file isn't a readable PDF")
        }
        name to count
    }

    suspend fun importPdfFile(docId: String, file: File): Pair<String, Int> = importPdf(docId, Uri.fromFile(file))

    companion object {
        @Volatile private var instance: ScanRepository? = null

        fun get(context: Context): ScanRepository = instance ?: synchronized(this) {
            instance ?: ScanRepository(context.applicationContext).also { instance = it }
        }

        fun defaultName(prefix: String = "Scan"): String =
            "$prefix ${SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date())}"
    }
}
