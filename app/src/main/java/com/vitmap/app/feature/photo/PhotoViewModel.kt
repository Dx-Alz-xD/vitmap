package com.vitmap.app.feature.photo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vitmap.app.vitmap
import com.vitmap.core.photo.Adjustments
import com.vitmap.core.photo.Inpaint
import com.vitmap.core.photo.LocalAdjustment
import com.vitmap.core.photo.MaskShape
import com.vitmap.core.photo.MaskStroke
import com.vitmap.core.photo.Masks
import com.vitmap.core.photo.PhotoPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class PhotoTool(val label: String) {
    PRESETS("Looks"), ADJUST("Adjust"), CURVES("Curves"), HSL("Colour mix"), GRADE("Grading"), DETAIL("Detail"), EFFECTS("Effects"),
    CROP("Crop"), HEAL("Heal"), MASKS("Masks"), BACKGROUND("Background"), TEXT("Text"), DRAW("Draw"), STICKERS("Stickers"),
    SHAPES("Shapes"), LAYERS("Images"), FRAME("Frame"),
}

data class CutOut(val mode: BackgroundMode, val image: String?)

data class PhotoUi(
    val project: PhotoProject? = null,
    val edit: PhotoEdit = PhotoEdit(),
    /** The edit without overlays and frame, at preview size. */
    val pixels: Bitmap? = null,
    /** Base after geometry only, for before/after. */
    val original: Bitmap? = null,
    /** Base with rotation but no crop, for the crop tool. */
    val uncropped: Bitmap? = null,
    val rendering: Boolean = false,
    val tool: PhotoTool = PhotoTool.ADJUST,
    val busy: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val histogram: Array<IntArray>? = null,
    val selected: String? = null,
    val selectedLocal: Int = -1,
    val message: String? = null,
    /** A cut-out waiting for the subject-detection module to be installed. */
    val needSegmentation: CutOut? = null,
    val exported: PhotoStore.Exported? = null,
)

class PhotoViewModel(app: Application) : AndroidViewModel(app) {
    val store = PhotoStore.get(app)
    val library = store.library
    private val _ui = MutableStateFlow(PhotoUi())
    val ui: StateFlow<PhotoUi> = _ui.asStateFlow()
    private val undo = ArrayDeque<PhotoEdit>()
    private val redo = ArrayDeque<PhotoEdit>()
    private var base: Bitmap? = null
    private var subject: Bitmap? = null
    private var renderJob: Job? = null
    @Volatile private var dirty = false
    private var saveJob: Job? = null

    companion object {
        const val PREVIEW = 1600
    }

    init {
        viewModelScope.launch { store.load() }
    }

    // ------------------------------------------------------------ projects

    fun import(uri: Uri) {
        _ui.value = PhotoUi(busy = "Opening photo")
        viewModelScope.launch {
            try {
                open(store.create(uri))
            } catch (e: Exception) {
                _ui.value = PhotoUi(message = e.message ?: "Couldn't open that photo")
            }
        }
    }

    fun openProject(id: String) {
        viewModelScope.launch {
            val p = store.load().projects.firstOrNull { it.id == id } ?: return@launch
            open(p)
        }
    }

    private suspend fun open(p: PhotoProject) {
        _ui.value = PhotoUi(project = p, edit = p.edit, busy = "Opening photo")
        undo.clear(); redo.clear()
        try {
            base = store.base(p.id, p.edit.baseVersion, PREVIEW)
            subject = store.subject(p.id)
            _ui.update { it.copy(busy = null) }
            render()
        } catch (e: Exception) {
            _ui.update { it.copy(busy = null, message = e.message ?: "Couldn't open the photo") }
        }
    }

    fun close() {
        flushSave()
        base = null
        subject = null
        _ui.value = PhotoUi()
    }

    fun deleteProject(id: String) {
        viewModelScope.launch { store.delete(id) }
    }

    fun consumeMessage() = _ui.update { it.copy(message = null) }
    fun dismissExported() = _ui.update { it.copy(exported = null) }

    // ------------------------------------------------------------ editing & history

    fun tool(t: PhotoTool) = _ui.update { it.copy(tool = t, selected = if (t in setOf(PhotoTool.TEXT, PhotoTool.STICKERS, PhotoTool.SHAPES, PhotoTool.LAYERS, PhotoTool.DRAW)) it.selected else null) }

    /**
     * Changes the edit. [commit] = false is for live dragging (no history entry); the gesture's
     * end calls [commit] once.
     */
    fun change(commit: Boolean = true, f: (PhotoEdit) -> PhotoEdit) {
        val before = _ui.value.edit
        val after = f(before)
        if (after == before) return
        if (commit) pushHistory(before)
        _ui.update { it.copy(edit = after, canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty()) }
        if (needsPixels(before, after)) render() else scheduleSave()
    }

    private var gestureStart: PhotoEdit? = null

    /** Marks the start of a slider drag or gesture, so its end makes one history entry. */
    fun beginGesture() {
        if (gestureStart == null) gestureStart = _ui.value.edit
    }

    fun endGesture() {
        val start = gestureStart ?: return
        gestureStart = null
        if (start != _ui.value.edit) {
            pushHistory(start)
            _ui.update { it.copy(canUndo = true, canRedo = false) }
            scheduleSave()
        }
    }

    private fun pushHistory(e: PhotoEdit) {
        undo.addLast(e)
        if (undo.size > 100) undo.removeFirst()
        redo.clear()
    }

    fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(_ui.value.edit)
        apply(prev)
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(_ui.value.edit)
        apply(next)
    }

    /** Jumps to any history state, e.g. "reset all". */
    fun reset() = change { PhotoEdit(baseVersion = 0) }

    private fun apply(e: PhotoEdit) {
        val before = _ui.value.edit
        _ui.update { it.copy(edit = e, canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty()) }
        if (e.baseVersion != before.baseVersion) {
            viewModelScope.launch {
                base = _ui.value.project?.let { store.base(it.id, e.baseVersion, PREVIEW) }
                render()
            }
        } else if (needsPixels(before, e)) render() else scheduleSave()
    }

    private fun needsPixels(a: PhotoEdit, b: PhotoEdit) =
        a.adjust != b.adjust || a.geometry != b.geometry || a.locals != b.locals || a.background != b.background || a.baseVersion != b.baseVersion

    fun select(id: String?) = _ui.update { it.copy(selected = id) }
    fun selectLocal(i: Int) = _ui.update { it.copy(selectedLocal = i) }

    fun adjust(commit: Boolean = false, f: (Adjustments) -> Adjustments) = change(commit) { it.copy(adjust = f(it.adjust)) }

    fun applyPreset(p: Preset?, amount: Float = 1f) = change {
        it.copy(adjust = if (p == null) Adjustments() else Adjustments().mix(p.adjust, amount), preset = p?.id, presetAmount = amount)
    }

    fun autoEnhance() {
        val px = _ui.value.original ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val pixels = IntArray(px.width * px.height).also { px.getPixels(it, 0, px.width, 0, 0, px.width, px.height) }
            val a = PhotoPipeline.autoEnhance(pixels)
            withContext(Dispatchers.Main) { change { e -> e.copy(adjust = a, preset = null) } }
        }
    }

    // ------------------------------------------------------------ overlays

    fun addOverlay(o: Overlay) {
        change { it.copy(overlays = it.overlays + o) }
        select(o.id)
    }

    fun updateOverlay(id: String, commit: Boolean = true, f: (Overlay) -> Overlay) = change(commit) { e -> e.copy(overlays = e.overlays.map { if (it.id == id) f(it) else it }) }

    fun removeOverlay(id: String) {
        change { e -> e.copy(overlays = e.overlays.filter { it.id != id }) }
        select(null)
    }

    fun reorderOverlay(id: String, up: Boolean) = change { e ->
        val list = e.overlays.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = if (up) i + 1 else i - 1
        if (i < 0 || j !in list.indices) e else e.copy(overlays = list.apply { add(j, removeAt(i)) })
    }

    fun duplicateOverlay(id: String) {
        val o = _ui.value.edit.overlays.firstOrNull { it.id == id } ?: return
        val nid = newId()
        val copy = when (o) {
            is Overlay.TextLayer -> o.copy(id = nid, x = o.x + 0.04f, y = o.y + 0.04f)
            is Overlay.StickerLayer -> o.copy(id = nid, x = o.x + 0.04f, y = o.y + 0.04f)
            is Overlay.ShapeLayer -> o.copy(id = nid, x = o.x + 0.04f, y = o.y + 0.04f)
            is Overlay.ImageLayer -> o.copy(id = nid, x = o.x + 0.04f, y = o.y + 0.04f)
            is Overlay.DrawingLayer -> o.copy(id = nid, x = o.x + 0.02f, y = o.y + 0.02f)
        }
        addOverlay(copy)
    }

    fun newId() = UUID.randomUUID().toString().take(12)

    fun addImageLayer(uri: Uri) {
        val p = _ui.value.project ?: return
        viewModelScope.launch {
            runCatching { store.addAsset(p.id, uri) }.onSuccess { (name, aspect) -> addOverlay(Overlay.ImageLayer(newId(), name, aspect)) }
                .onFailure { e -> _ui.update { it.copy(message = e.message) } }
        }
    }

    fun setBackgroundImage(uri: Uri) {
        val p = _ui.value.project ?: return
        viewModelScope.launch {
            runCatching { store.addAsset(p.id, uri) }.onSuccess { (name, _) -> cutOut(BackgroundMode.IMAGE, name) }
                .onFailure { e -> _ui.update { it.copy(message = e.message) } }
        }
    }

    // ------------------------------------------------------------ local adjustments

    fun addLocal(shape: MaskShape) {
        change { it.copy(locals = it.locals + LocalAdjustment(shape, Adjustments(exposure = 20f))) }
        selectLocal(_ui.value.edit.locals.lastIndex)
    }

    fun updateLocal(i: Int, commit: Boolean = false, f: (LocalAdjustment) -> LocalAdjustment) =
        change(commit) { e -> e.copy(locals = e.locals.mapIndexed { idx, l -> if (idx == i) f(l) else l }) }

    fun removeLocal(i: Int) {
        change { e -> e.copy(locals = e.locals.filterIndexed { idx, _ -> idx != i }) }
        selectLocal(-1)
    }

    // ------------------------------------------------------------ retouch

    /**
     * Fills the painted area from its surroundings on the full-size base, saving the result as a
     * new base version. [strokes] are relative to the edited (cropped) view.
     */
    fun heal(strokes: List<MaskStroke>) {
        val p = _ui.value.project ?: return
        if (strokes.isEmpty()) return
        val edit = _ui.value.edit
        _ui.update { it.copy(busy = "Retouching…") }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val full = store.base(p.id, edit.baseVersion, 6000)
                val w = full.width; val h = full.height
                // Map the strokes from the edited view back onto the base photo.
                val (m, ow, oh) = PhotoRenderer.geometryMatrix(w, h, edit.geometry)
                val inv = Matrix().also { m.invert(it) }
                val scale = minOf(ow, oh).toFloat() / minOf(w, h)
                val mapped = strokes.map { st ->
                    val pts = FloatArray(st.points.size) { i -> if (i % 2 == 0) st.points[i] * ow else st.points[i] * oh }
                    inv.mapPoints(pts)
                    MaskStroke(pts.mapIndexed { i, v -> if (i % 2 == 0) v / w else v / h }, st.radius * scale, 0.9f)
                }
                val mask = Masks.render(MaskShape.Brush(mapped), w, h)
                val hole = BooleanArray(w * h) { mask[it] > 0.3f }
                val px = IntArray(w * h).also { full.getPixels(it, 0, w, 0, 0, w, h) }
                val filled = Inpaint.fill(px, w, h, hole)
                val out = Bitmap.createBitmap(filled, w, h, Bitmap.Config.ARGB_8888)
                // Versions are never overwritten, so undo can always go back to an older base.
                val newVersion = store.addBase(p.id, out, store.nextVersion(p.id))
                base = store.base(p.id, newVersion, PREVIEW)
                withContext(Dispatchers.Main) {
                    _ui.update { it.copy(busy = null) }
                    change { it.copy(baseVersion = newVersion) }
                }
            } catch (e: OutOfMemoryError) {
                _ui.update { it.copy(busy = null, message = "Not enough memory for this retouch") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, message = e.message ?: "Retouch failed") }
            }
        }
    }

    /** Finds the subject with ML Kit and switches the background to [mode] (with [image] behind it, for IMAGE). */
    fun cutOut(mode: BackgroundMode, image: String? = null) {
        val p = _ui.value.project ?: return
        fun switch() = change { it.copy(background = it.background.copy(mode = mode, image = image ?: it.background.image)) }
        if (subject != null) {
            switch()
            return
        }
        val b = base ?: return
        _ui.update { it.copy(busy = "Finding the subject…") }
        viewModelScope.launch {
            try {
                if (!getApplication<Application>().vitmap.packs.isReady(Segmentation.pack)) {
                    _ui.update { it.copy(busy = null, needSegmentation = CutOut(mode, image)) }
                    return@launch
                }
                val (mask, mw, mh) = Segmentation.mask(getApplication(), b)
                store.saveSubject(p.id, mask, mw, mh)
                subject = store.subject(p.id)
                _ui.update { it.copy(busy = null) }
                switch()
            } catch (e: SegmentationMissingException) {
                _ui.update { it.copy(busy = null, needSegmentation = CutOut(mode, image)) }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, message = e.message ?: "Couldn't find a subject") }
            }
        }
    }

    fun segmentationHandled() = _ui.update { it.copy(needSegmentation = null) }

    /** Forgets the cut-out so it's computed again (after retouching, for example). */
    fun redetectSubject(mode: BackgroundMode) {
        val p = _ui.value.project ?: return
        store.subjectFile(p.id).delete()
        subject = null
        cutOut(mode)
    }

    // ------------------------------------------------------------ rendering

    private fun render() {
        dirty = true
        if (renderJob?.isActive == true) return
        renderJob = viewModelScope.launch(Dispatchers.Default) {
            while (dirty) {
                dirty = false
                val b = base ?: break
                val edit = _ui.value.edit
                val project = _ui.value.project ?: break
                _ui.update { it.copy(rendering = true) }
                try {
                    val inputs = PhotoRenderer.Inputs(b, subject, store.dir(project.id))
                    val pixels = PhotoRenderer.render(inputs, edit, includeOverlays = false, includeFrame = false)
                    val original = PhotoRenderer.geometry(b, edit.geometry)
                    val uncropped = if (_ui.value.tool == PhotoTool.CROP || _ui.value.uncropped == null) PhotoRenderer.geometry(b, edit.geometry.copy(left = 0f, top = 0f, right = 1f, bottom = 1f)) else _ui.value.uncropped
                    val hist = IntArray(pixels.width * pixels.height).also { pixels.getPixels(it, 0, pixels.width, 0, 0, pixels.width, pixels.height) }.let { PhotoPipeline.histogram(it) }
                    _ui.update { it.copy(pixels = pixels, original = original, uncropped = uncropped, histogram = hist) }
                } catch (e: OutOfMemoryError) {
                    _ui.update { it.copy(message = "Out of memory while rendering") }
                }
            }
            _ui.update { it.copy(rendering = false) }
            scheduleSave()
        }
    }

    /** The uncropped view is only refreshed while cropping; call when entering the tool. */
    fun refreshUncropped() {
        val b = base ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val u = PhotoRenderer.geometry(b, _ui.value.edit.geometry.copy(left = 0f, top = 0f, right = 1f, bottom = 1f))
            _ui.update { it.copy(uncropped = u) }
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            val p = _ui.value.project ?: return@launch
            store.save(p.copy(edit = _ui.value.edit))
            _ui.value.pixels?.let { store.saveThumb(p.id, it) }
        }
    }

    private fun flushSave() {
        val p = _ui.value.project ?: return
        val e = _ui.value.edit
        saveJob?.cancel()
        getApplication<Application>().vitmap.appScope.launch { store.save(p.copy(edit = e)) }
    }

    // ------------------------------------------------------------ export

    fun export(o: ExportOptions) {
        val p = _ui.value.project ?: return
        _ui.update { it.copy(busy = "Exporting…") }
        viewModelScope.launch {
            try {
                val r = store.export(p.copy(edit = _ui.value.edit), o) { step -> _ui.update { it.copy(busy = step) } }
                _ui.update { it.copy(busy = null, exported = r, message = r.note) }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, message = e.message ?: "Export failed") }
            }
        }
    }

    override fun onCleared() {
        flushSave()
        super.onCleared()
    }
}
