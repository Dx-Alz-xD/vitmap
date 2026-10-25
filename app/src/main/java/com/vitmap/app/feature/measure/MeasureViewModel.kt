package com.vitmap.app.feature.measure

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vitmap.app.vitmap
import com.vitmap.core.measure.Calibration
import com.vitmap.core.measure.Geometry
import com.vitmap.core.measure.LengthUnit
import com.vitmap.core.measure.Pt
import com.vitmap.core.measure.RefShape
import com.vitmap.core.measure.ReferenceObject
import com.vitmap.core.measure.References
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import kotlin.math.min

enum class MTool(val title: String) { LINE("Line"), PATH("Path"), AREA("Area"), ANGLE("Angle"), CIRCLE("Circle") }

enum class Stage { SOURCE, CALIBRATE, MEASURE }

data class MItem(val id: Int, val tool: MTool, val points: List<Pt>)

@Serializable
data class SPt(val x: Double, val y: Double)

@Serializable
data class CustomRef(val id: String, val name: String, val shape: String, val widthMm: Double, val heightMm: Double = 0.0) {
    fun toRef() = ReferenceObject(id, name, "Your references", runCatching { RefShape.valueOf(shape) }.getOrDefault(RefShape.LENGTH), widthMm, heightMm, custom = true)
}

@Serializable
data class SavedItem(val tool: String, val points: List<SPt>)

@Serializable
data class SavedSession(
    val id: String,
    val image: String,
    val refName: String,
    val calibPoints: List<SPt>,
    val calibW: Double,
    val calibH: Double,
    val items: List<SavedItem>,
    val time: Long,
)

@Serializable
data class MeasurePrefs(
    val refs: List<CustomRef> = emptyList(),
    val sessions: List<SavedSession> = emptyList(),
    val unit: String = LengthUnit.CM.name,
    val rulerScale: Double = 1.0,
    val lastRef: String = "card",
)

data class MeasureState(
    val stage: Stage = Stage.SOURCE,
    val bitmap: Bitmap? = null,
    val reference: ReferenceObject = References.builtIn.first(),
    /** Calibration handles in image pixels: 2 for lines/circles, 4 for rectangles. */
    val calibPoints: List<Pt> = emptyList(),
    /** For rectangles, which edge is the width (0→1) — rotating swaps width and height. */
    val rectRotated: Boolean = false,
    val useTwoPoints: Boolean = false,
    val calibration: Calibration? = null,
    val items: List<MItem> = emptyList(),
    val selected: Int? = null,
    val tool: MTool = MTool.LINE,
    val unit: LengthUnit = LengthUnit.CM,
    val prefs: MeasurePrefs = MeasurePrefs(),
    val sessionId: String? = null,
) {
    val allReferences: List<ReferenceObject> get() = prefs.refs.map { it.toRef() } + References.builtIn
}

class MeasureViewModel(app: Application) : AndroidViewModel(app) {
    private val store = app.vitmap.store("measure", MeasurePrefs.serializer()) { MeasurePrefs() }
    private val _state = MutableStateFlow(MeasureState())
    val state: StateFlow<MeasureState> = _state.asStateFlow()
    private var nextId = 1

    init {
        viewModelScope.launch {
            val p = store.load()
            val unit = runCatching { LengthUnit.valueOf(p.unit) }.getOrDefault(LengthUnit.CM)
            _state.update { s ->
                val ref = (p.refs.map { it.toRef() } + References.builtIn).firstOrNull { it.id == p.lastRef } ?: s.reference
                s.copy(prefs = p, unit = unit, reference = ref)
            }
        }
    }

    private fun savePrefs(t: (MeasurePrefs) -> MeasurePrefs) {
        val next = t(_state.value.prefs)
        _state.update { it.copy(prefs = next) }
        viewModelScope.launch { store.set(next) }
    }

    fun setImage(bmp: Bitmap) {
        _state.update { it.copy(bitmap = bmp, stage = Stage.CALIBRATE, items = emptyList(), calibration = null, selected = null, sessionId = null) }
        resetCalibrationHandles()
    }

    fun backToSource() = _state.update { it.copy(stage = Stage.SOURCE, bitmap = null, items = emptyList(), calibration = null) }

    fun recalibrate() = _state.update { it.copy(stage = Stage.CALIBRATE) }

    fun chooseReference(ref: ReferenceObject) {
        _state.update { it.copy(reference = ref, rectRotated = false) }
        savePrefs { it.copy(lastRef = ref.id) }
        resetCalibrationHandles()
    }

    fun setTwoPoints(on: Boolean) {
        _state.update { it.copy(useTwoPoints = on) }
        resetCalibrationHandles()
    }

    fun rotateRect() = _state.update { it.copy(rectRotated = !it.rectRotated) }

    private fun needsRect(s: MeasureState) = s.reference.shape == RefShape.RECTANGLE && !s.useTwoPoints

    private fun resetCalibrationHandles() {
        val s = _state.value
        val b = s.bitmap ?: return
        val w = b.width.toDouble()
        val h = b.height.toDouble()
        val cx = w / 2
        val cy = h / 2
        val pts = if (needsRect(s)) {
            val ref = s.reference
            val aspect = ref.heightMm / ref.widthMm
            val rw = min(w, h) * 0.45
            val rh = rw * aspect
            listOf(Pt(cx - rw / 2, cy - rh / 2), Pt(cx + rw / 2, cy - rh / 2), Pt(cx + rw / 2, cy + rh / 2), Pt(cx - rw / 2, cy + rh / 2))
        } else {
            val len = min(w, h) * 0.3
            listOf(Pt(cx - len / 2, cy), Pt(cx + len / 2, cy))
        }
        _state.update { it.copy(calibPoints = pts) }
    }

    fun moveCalibPoint(index: Int, p: Pt) = _state.update { s ->
        s.copy(calibPoints = s.calibPoints.mapIndexed { i, old -> if (i == index) clamp(s, p) else old })
    }

    private fun clamp(s: MeasureState, p: Pt): Pt {
        val b = s.bitmap ?: return p
        return Pt(p.x.coerceIn(0.0, b.width - 1.0), p.y.coerceIn(0.0, b.height - 1.0))
    }

    /** Builds the calibration from the handles; returns an error message if it can't. */
    fun calibrate(): String? {
        val s = _state.value
        val ref = s.reference
        val cal = if (needsRect(s)) {
            val (wMm, hMm) = if (s.rectRotated) ref.heightMm to ref.widthMm else ref.widthMm to ref.heightMm
            Calibration.fromRectangle(s.calibPoints, wMm, hMm)
        } else {
            Calibration.fromLine(s.calibPoints[0], s.calibPoints[1], ref.widthMm)
        } ?: return "Those points don't form a usable reference — spread the handles out."
        _state.update { it.copy(calibration = cal, stage = Stage.MEASURE) }
        return null
    }

    fun setTool(t: MTool) = _state.update { it.copy(tool = t) }

    fun setUnit(u: LengthUnit) {
        _state.update { it.copy(unit = u) }
        savePrefs { it.copy(unit = u.name) }
    }

    /** Drops a ready-made shape of the current tool around [at] (image pixels). */
    fun addItem(at: Pt) {
        val s = _state.value
        val b = s.bitmap ?: return
        val r = min(b.width, b.height) * 0.12
        val p = { dx: Double, dy: Double -> clamp(s, Pt(at.x + dx, at.y + dy)) }
        val pts = when (s.tool) {
            MTool.LINE -> listOf(p(-r, 0.0), p(r, 0.0))
            MTool.PATH -> listOf(p(-r, r * 0.5), p(0.0, -r * 0.5), p(r, r * 0.5))
            MTool.AREA -> listOf(p(-r, -r), p(r, -r), p(r, r), p(-r, r))
            MTool.ANGLE -> listOf(p(r, -r), p(0.0, 0.0), p(r, r * 0.2))
            MTool.CIRCLE -> listOf(p(0.0, 0.0), p(r, 0.0))
        }
        val id = nextId++
        _state.update { it.copy(items = it.items + MItem(id, s.tool, pts), selected = id) }
    }

    fun movePoint(itemId: Int, index: Int, p: Pt) = _state.update { s ->
        s.copy(items = s.items.map { item -> if (item.id != itemId) item else item.copy(points = item.points.mapIndexed { i, old -> if (i == index) clamp(s, p) else old }) }, selected = itemId)
    }

    fun select(id: Int?) = _state.update { it.copy(selected = id) }

    fun addPathPoint() = _state.update { s ->
        val sel = s.selected ?: return@update s
        s.copy(items = s.items.map { item ->
            if (item.id != sel || (item.tool != MTool.PATH && item.tool != MTool.AREA)) item else {
                val a = item.points[item.points.size - 2]
                val b = item.points.last()
                val nb = Pt(b.x + (b.x - a.x) * 0.6, b.y + (b.y - a.y) * 0.6)
                if (item.tool == MTool.AREA) item.copy(points = item.points + Pt((b.x + item.points.first().x) / 2, (b.y + item.points.first().y) / 2))
                else item.copy(points = item.points + clamp(s, nb))
            }
        })
    }

    fun deleteSelected() = _state.update { s -> s.copy(items = s.items.filter { it.id != s.selected }, selected = null) }

    fun clearItems() = _state.update { it.copy(items = emptyList(), selected = null) }

    /** Primary value of a measurement in millimetres (or degrees for angles). */
    fun valueOf(item: MItem, cal: Calibration): Double = when (item.tool) {
        MTool.LINE -> Geometry.distance(cal, item.points[0], item.points[1])
        MTool.PATH -> Geometry.pathLength(cal, item.points)
        MTool.AREA -> Geometry.area(cal, item.points)
        MTool.ANGLE -> Geometry.angle(cal, item.points[0], item.points[1], item.points[2])
        MTool.CIRCLE -> Geometry.circle(cal, item.points[0], item.points[1]).first
    }

    fun label(item: MItem, cal: Calibration, unit: LengthUnit): String = when (item.tool) {
        MTool.LINE, MTool.PATH -> unit.format(valueOf(item, cal))
        MTool.AREA -> unit.formatArea(valueOf(item, cal))
        MTool.ANGLE -> "%.1f°".format(valueOf(item, cal))
        MTool.CIRCLE -> "Ø " + unit.format(valueOf(item, cal))
    }

    fun details(item: MItem, cal: Calibration, unit: LengthUnit): String = when (item.tool) {
        MTool.LINE -> "Length ${unit.format(valueOf(item, cal))} · ${LengthUnit.IN.formatImperial(valueOf(item, cal))}"
        MTool.PATH -> "Path of ${item.points.size} points · ${unit.format(valueOf(item, cal))}"
        MTool.AREA -> "Area ${unit.formatArea(Geometry.area(cal, item.points))} · perimeter ${unit.format(Geometry.perimeter(cal, item.points))}"
        MTool.ANGLE -> "Angle %.1f° (%.1f° reflex)".format(valueOf(item, cal), 360 - valueOf(item, cal))
        MTool.CIRCLE -> {
            val (d, area, circ) = Geometry.circle(cal, item.points[0], item.points[1])
            "Ø ${unit.format(d)} · circumference ${unit.format(circ)} · area ${unit.formatArea(area)}"
        }
    }

    /** Saves the selected line as a new reference so it can calibrate future photos. */
    fun saveAsReference(item: MItem, name: String) {
        val cal = _state.value.calibration ?: return
        val mm = when (item.tool) {
            MTool.CIRCLE -> valueOf(item, cal)
            MTool.LINE, MTool.PATH -> valueOf(item, cal)
            else -> return
        }
        val shape = if (item.tool == MTool.CIRCLE) RefShape.CIRCLE else RefShape.LENGTH
        val ref = CustomRef(UUID.randomUUID().toString(), name.trim().ifEmpty { "My reference" }, shape.name, (mm * 100).toLong() / 100.0)
        savePrefs { it.copy(refs = listOf(ref) + it.refs) }
    }

    fun addCustomReference(name: String, shape: RefShape, w: Double, h: Double) {
        savePrefs { it.copy(refs = listOf(CustomRef(UUID.randomUUID().toString(), name.trim(), shape.name, w, h)) + it.refs) }
    }

    fun deleteReference(id: String) = savePrefs { p -> p.copy(refs = p.refs.filter { it.id != id }) }

    fun setRulerScale(f: Double) = savePrefs { it.copy(rulerScale = f) }

    fun saveSession() {
        val s = _state.value
        val bmp = s.bitmap ?: return
        if (s.calibration == null) return
        viewModelScope.launch {
            val id = s.sessionId ?: UUID.randomUUID().toString()
            val file = File(getApplication<Application>().filesDir, "measure/$id.jpg")
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }
            val (w, h) = if (needsRect(s)) (if (s.rectRotated) s.reference.heightMm to s.reference.widthMm else s.reference.widthMm to s.reference.heightMm) else s.reference.widthMm to 0.0
            val session = SavedSession(
                id, file.absolutePath, s.reference.name, s.calibPoints.map { SPt(it.x, it.y) }, w, h,
                s.items.map { SavedItem(it.tool.name, it.points.map { p -> SPt(p.x, p.y) }) }, System.currentTimeMillis(),
            )
            _state.update { it.copy(sessionId = id) }
            savePrefs { p -> p.copy(sessions = listOf(session) + p.sessions.filter { it.id != id }) }
        }
    }

    fun openSession(session: SavedSession) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(session.image) } ?: return@launch
            val pts = session.calibPoints.map { Pt(it.x, it.y) }
            val cal = if (pts.size == 4) Calibration.fromRectangle(pts, session.calibW, session.calibH) else Calibration.fromLine(pts[0], pts[1], session.calibW)
            val items = session.items.map { MItem(nextId++, runCatching { MTool.valueOf(it.tool) }.getOrDefault(MTool.LINE), it.points.map { p -> Pt(p.x, p.y) }) }
            _state.update {
                it.copy(
                    bitmap = bmp, calibPoints = pts, calibration = cal, items = items, stage = if (cal != null) Stage.MEASURE else Stage.CALIBRATE,
                    sessionId = session.id, selected = null, useTwoPoints = pts.size == 2,
                )
            }
        }
    }

    fun deleteSession(session: SavedSession) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { File(session.image).delete() } }
        savePrefs { p -> p.copy(sessions = p.sessions.filter { it.id != session.id }) }
    }
}
