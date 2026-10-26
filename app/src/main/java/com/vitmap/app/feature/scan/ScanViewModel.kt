package com.vitmap.app.feature.scan

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vitmap.app.data.FileSaver
import com.vitmap.app.data.SaveCollection
import com.vitmap.core.scan.DocumentDetector
import com.vitmap.core.scan.PageFilter
import com.vitmap.core.scan.Pt
import com.vitmap.core.scan.Quad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Screens inside the scanner / PDF tools flow. */
sealed interface DocScreen {
    data object Home : DocScreen
    data class Camera(val docId: String?) : DocScreen
    data class PageEdit(val docId: String, val pageId: String, val fromCamera: Boolean = false) : DocScreen
    data class Editor(val docId: String) : DocScreen
    data class Annotate(val docId: String, val pageIndex: Int) : DocScreen
}

data class ExportResult(val uri: Uri?, val file: File, val name: String)

class ScanViewModel(app: Application) : AndroidViewModel(app) {
    val repo = ScanRepository.get(app)
    val docs: StateFlow<List<ScanDoc>> = repo.docs
    val prefs = repo.prefs

    private val _stack = MutableStateFlow<List<DocScreen>>(listOf(DocScreen.Home))
    val stack: StateFlow<List<DocScreen>> = _stack.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch { repo.load() }
    }

    fun push(screen: DocScreen) = _stack.update { it + screen }

    /** Replaces the top screen. */
    fun replace(screen: DocScreen) = _stack.update { it.dropLast(1) + screen }

    fun pop(): Boolean {
        if (_stack.value.size <= 1) return false
        _stack.update { it.dropLast(1) }
        return true
    }

    fun resetTo(vararg screens: DocScreen) {
        _stack.value = listOf(DocScreen.Home) + screens
    }

    fun toast(msg: String) {
        _message.value = msg
    }

    fun consumeMessage() {
        _message.value = null
    }

    private suspend fun <T> busyWith(label: String, block: suspend () -> T): T? {
        _busy.value = label
        return try {
            block()
        } catch (e: Exception) {
            _message.value = e.localizedMessage ?: "Something went wrong"
            null
        } finally {
            _busy.value = null
        }
    }

    // ---------------------------------------------------------------- documents

    suspend fun ensureDoc(docId: String?, name: String = ScanRepository.defaultName()): ScanDoc =
        docId?.let { repo.get(it) } ?: repo.create(name)

    fun rename(docId: String, name: String) = viewModelScope.launch {
        repo.update(docId) { it.copy(name = name.trim().ifEmpty { it.name }) }
    }

    fun deleteDoc(docId: String) = viewModelScope.launch { repo.delete(docId) }

    fun duplicateDoc(docId: String) = viewModelScope.launch { repo.duplicate(docId) }

    /** Adds a captured photo as a page, auto-cropped. Returns the page id. */
    suspend fun addCapture(docId: String, photo: Bitmap, liveQuad: Quad?, brightness: Float, contrast: Float): String {
        val defaultFilter = prefs.load().defaultFilter
        val name = repo.putImage(docId, photo)
        val quad = withContext(Dispatchers.Default) { detectNormalized(photo) } ?: liveQuad
        val page = DocPage(
            kind = PageKind.IMAGE,
            image = name,
            quad = quad?.let { DocPage.quadToList(it) },
            filter = defaultFilter,
            brightness = brightness,
            contrast = contrast,
        )
        repo.update(docId) { it.copy(pages = it.pages + page) }
        return page.id
    }

    fun importImages(docId: String?, uris: List<Uri>, autoCrop: Boolean = true, filter: PageFilter? = null, open: Boolean = true) = viewModelScope.launch {
        busyWith("Importing ${uris.size} image${if (uris.size == 1) "" else "s"}…") {
            val doc = ensureDoc(docId)
            val defaultFilter = filter?.name ?: prefs.load().defaultFilter
            val pages = ArrayList<DocPage>()
            for (u in uris) {
                val name = repo.importImage(doc.id, u) ?: continue
                val quad = if (autoCrop) {
                    PageRenderer.decodeSampled(repo.file(doc.id, name), 900)?.let { detectNormalized(it) }
                } else null
                pages += DocPage(kind = PageKind.IMAGE, image = name, quad = quad?.let { DocPage.quadToList(it) }, filter = defaultFilter)
            }
            repo.update(doc.id) { it.copy(pages = it.pages + pages) }
            if (open && docId == null) push(DocScreen.Editor(doc.id))
        }
    }

    fun importPdfs(docId: String?, uris: List<Uri>, name: String? = null, open: Boolean = true) = viewModelScope.launch {
        busyWith("Opening PDF…") {
            val doc = ensureDoc(docId, name ?: uris.firstOrNull()?.let { FileSaver.displayName(getApplication(), it).removeSuffix(".pdf") } ?: ScanRepository.defaultName("Document"))
            val pages = ArrayList<DocPage>()
            for (u in uris) {
                val (file, count) = repo.importPdf(doc.id, u)
                for (i in 0 until count) pages += DocPage(kind = PageKind.PDF, pdf = file, pdfIndex = i, filter = PageFilter.ORIGINAL.name)
            }
            repo.update(doc.id) { it.copy(pages = it.pages + pages) }
            if (open && docId == null) push(DocScreen.Editor(doc.id))
        }
    }

    fun addBlankPage(docId: String, at: Int? = null) = viewModelScope.launch {
        repo.update(docId) { d ->
            val aspect = d.pages.lastOrNull()?.let { if (it.kind == PageKind.BLANK) it.blankAspect else null } ?: 1.4142f
            val page = DocPage(kind = PageKind.BLANK, filter = PageFilter.ORIGINAL.name, blankAspect = aspect)
            val list = d.pages.toMutableList()
            list.add((at ?: list.size).coerceIn(0, list.size), page)
            d.copy(pages = list)
        }
    }

    fun updatePage(docId: String, page: DocPage) = viewModelScope.launch {
        repo.update(docId) { d -> d.copy(pages = d.pages.map { if (it.id == page.id) page.bump() else it }) }
    }

    fun deletePages(docId: String, ids: Set<String>) = viewModelScope.launch {
        repo.update(docId) { d -> d.copy(pages = d.pages.filter { it.id !in ids }) }
    }

    fun rotatePages(docId: String, ids: Set<String>, by: Int) = viewModelScope.launch {
        repo.update(docId) { d -> d.copy(pages = d.pages.map { if (it.id in ids) it.copy(rotation = ((it.rotation + by) % 360 + 360) % 360).bump() else it }) }
    }

    fun duplicatePages(docId: String, ids: Set<String>) = viewModelScope.launch {
        repo.update(docId) { d ->
            d.copy(pages = d.pages.flatMap { if (it.id in ids) listOf(it, it.copy(id = newId())) else listOf(it) })
        }
    }

    fun movePage(docId: String, from: Int, to: Int) = viewModelScope.launch {
        repo.update(docId) { d ->
            if (from !in d.pages.indices || to !in d.pages.indices) return@update d
            val list = d.pages.toMutableList()
            list.add(to, list.removeAt(from))
            d.copy(pages = list)
        }
    }

    /** Moves selected pages into a new document. */
    fun extractPages(docId: String, ids: Set<String>) = viewModelScope.launch {
        busyWith("Extracting pages…") {
            val src = repo.get(docId) ?: return@busyWith
            val copy = repo.duplicate(docId) ?: return@busyWith
            repo.save(copy.copy(name = src.name + " (extract)", pages = copy.pages.filter { it.id in ids }))
            _message.value = "Copied ${ids.size} page${if (ids.size == 1) "" else "s"} into a new document"
        }
    }

    fun setPageAnnotations(docId: String, pageId: String, annotations: List<Annot>) = viewModelScope.launch {
        repo.update(docId) { d -> d.copy(pages = d.pages.map { if (it.id == pageId) it.copy(annotations = annotations) else it }) }
    }

    fun saveSignature(sig: SavedSignature) = viewModelScope.launch {
        prefs.update { it.copy(signatures = listOf(sig) + it.signatures.filter { s -> s.id != sig.id }.take(11)) }
    }

    fun deleteSignature(id: String) = viewModelScope.launch {
        prefs.update { it.copy(signatures = it.signatures.filter { s -> s.id != id }) }
    }

    fun setPrefs(change: (ScanPrefs) -> ScanPrefs) = viewModelScope.launch { prefs.update(change) }

    // ---------------------------------------------------------------- export

    private val _export = MutableStateFlow<Float?>(null)
    val exportProgress: StateFlow<Float?> = _export.asStateFlow()

    /** Builds the PDF in the cache. */
    suspend fun buildPdf(docId: String, options: ExportOptions, fileName: String): File? {
        val doc = repo.get(docId) ?: return null
        _export.value = 0f
        return try {
            val out = File(getApplication<Application>().cacheDir, "exports/${FileSaver.safeName(fileName)}.pdf")
            PdfExporter.exportToFile(getApplication(), doc, options, out) { done, total -> _export.value = if (total == 0) 1f else done.toFloat() / total }
        } catch (e: Exception) {
            _message.value = "Couldn't create the PDF: ${e.localizedMessage}"
            null
        } finally {
            _export.value = null
        }
    }

    /** Saves the PDF to Downloads/Vitmap (Android 10+). */
    suspend fun saveToDownloads(file: File, name: String): Uri? =
        runCatching { FileSaver.saveFile(getApplication(), SaveCollection.DOCUMENTS, file, "${FileSaver.safeName(name)}.pdf", "application/pdf") }
            .onFailure { _message.value = it.localizedMessage }
            .getOrNull()

    /** Exports every page as an image into Pictures/Vitmap/<doc>. */
    fun exportImages(docId: String, png: Boolean) = viewModelScope.launch {
        val doc = repo.get(docId) ?: return@launch
        busyWith("Saving images…") {
            var saved = 0
            doc.pages.forEachIndexed { i, page ->
                val bmp = PageRenderer.renderWithAnnotations(getApplication(), docId, page, 3000)
                val name = "${FileSaver.safeName(doc.name)} - ${i + 1}.${if (png) "png" else "jpg"}"
                val uri = FileSaver.save(getApplication(), SaveCollection.PICTURES, name, if (png) "image/png" else "image/jpeg", FileSaver.safeName(doc.name)) { out ->
                    bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, out)
                }
                if (uri != null) saved++
            }
            _message.value = if (saved > 0) "Saved $saved image${if (saved == 1) "" else "s"} to Pictures/Vitmap" else "Saving images needs Android 10 or newer"
        }
    }

    companion object {
        /** Detects a page in [bmp] and returns it normalised to 0..1, or null. */
        fun detectNormalized(bmp: Bitmap): Quad? {
            val small = PageRenderer.scaleDown(bmp, 640)
            val px = IntArray(small.width * small.height)
            small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
            val det = DocumentDetector.detectArgb(px, small.width, small.height) ?: return null
            if (det.confidence < 0.45) return null
            val w = small.width.toDouble()
            val h = small.height.toDouble()
            return det.quad.map { Pt((it.x / w).coerceIn(0.0, 1.0), (it.y / h).coerceIn(0.0, 1.0)) }
        }
    }
}
