package com.vitmap.app.feature.photo

import android.graphics.Bitmap
import android.graphics.Canvas as NativeCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vitmap.core.photo.LocalAdjustment
import com.vitmap.core.photo.MaskShape
import com.vitmap.core.photo.MaskStroke
import com.vitmap.core.photo.Masks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Editor state that lives in the UI: view zoom, brush settings and in-progress strokes. */
@Stable
class EditorState {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    var comparing by mutableStateOf(false)
    var brushSize by mutableFloatStateOf(0.04f)
    val healStrokes = mutableStateListOf<MaskStroke>()
    var drawColor by mutableStateOf(0xFFEF4444)
    var drawWidth by mutableFloatStateOf(0.012f)
    var drawKind by mutableStateOf(BrushKind.PEN)
    var eraser by mutableStateOf(false)
    var maskErase by mutableStateOf(false)
    var showMask by mutableStateOf(false)
    /** Width/height of the crop, or null for free. */
    var cropAspect by mutableStateOf<Float?>(null)
    var editingText by mutableStateOf<String?>(null)
    var live by mutableStateOf<List<Float>>(emptyList())
    /** Last tap target and time, for double-tap detection. */
    var lastTap: Pair<String, Long> = "" to 0L

    fun resetView() {
        zoom = 1f
        pan = Offset.Zero
    }
}

private fun fitRect(box: Size, w: Int, h: Int, zoom: Float, pan: Offset): Rect {
    val fit = min(box.width / w, box.height / h)
    val dw = w * fit * zoom
    val dh = h * fit * zoom
    val left = (box.width - dw) / 2 + pan.x
    val top = (box.height - dh) / 2 + pan.y
    return Rect(left, top, left + dw, top + dh)
}

/** Where an overlay sits, in preview pixels, for hit-testing. */
private fun hit(o: Overlay, u: Float, v: Float, w: Int, h: Int): Boolean {
    val s = min(w, h).toFloat()
    val dx = (u - o.x) * w
    val dy = (v - o.y) * h
    val a = Math.toRadians(-o.rotation.toDouble())
    val lx = (dx * cos(a) - dy * sin(a)).toFloat()
    val ly = (dx * sin(a) + dy * cos(a)).toFloat()
    val slack = s * 0.03f
    return when (o) {
        is Overlay.TextLayer -> PhotoRenderer.textBounds(o, s).let { lx in it.left - slack..it.right + slack && ly in it.top - slack..it.bottom + slack }
        is Overlay.StickerLayer -> abs(lx) < o.scale * s / 2 + slack && abs(ly) < o.scale * s / 2 + slack
        is Overlay.ShapeLayer -> abs(lx) < o.scale * s * o.aspect / 2 + slack && abs(ly) < o.scale * s / 2 + slack
        is Overlay.ImageLayer -> abs(lx) < o.scale * s * o.aspect / 2 + slack && abs(ly) < o.scale * s / 2 + slack
        is Overlay.DrawingLayer -> {
            val px = lx / o.scale + w / 2f
            val py = ly / o.scale + h / 2f
            o.strokes.any { st -> st.points.chunked(2).any { (x, y) -> hypot(x * w - px, y * h - py) < max(st.width * s * 2, slack * 2) } }
        }
    }
}

@Composable
fun PhotoCanvas(ui: PhotoUi, vm: PhotoViewModel, state: EditorState, onEditText: (String) -> Unit) {
    val tool = ui.tool
    val pixels = ui.pixels ?: return
    val edit = ui.edit
    val dir = ui.project?.let { vm.store.dir(it.id) } ?: return
    val overlayTool = tool in setOf(PhotoTool.TEXT, PhotoTool.STICKERS, PhotoTool.SHAPES, PhotoTool.LAYERS, PhotoTool.DRAW)
    val cropping = tool == PhotoTool.CROP && ui.uncropped != null
    // With a frame, outside the overlay tools, show the finished composite.
    val framed by produceState<Bitmap?>(null, pixels, edit.overlays, edit.frame, overlayTool) {
        value = if (edit.frame.kind == FrameKind.NONE || overlayTool) null else withContext(Dispatchers.Default) {
            val c = pixels.copy(Bitmap.Config.ARGB_8888, true)
            PhotoRenderer.drawOverlays(NativeCanvas(c), c.width, c.height, edit.overlays, dir)
            PhotoRenderer.frame(c, edit.frame)
        }
    }
    val selectedLocal = edit.locals.getOrNull(ui.selectedLocal)
    val maskPreview by produceState<Bitmap?>(null, selectedLocal, state.showMask, pixels.width, pixels.height) {
        value = if (!state.showMask || selectedLocal == null) null else withContext(Dispatchers.Default) {
            val s = 320f / max(pixels.width, pixels.height)
            val w = max(1, (pixels.width * s).roundToInt())
            val h = max(1, (pixels.height * s).roundToInt())
            val m = Masks.render(selectedLocal.shape, w, h)
            Bitmap.createBitmap(IntArray(w * h) { ((m[it] * 150).roundToInt() shl 24) or 0xFF3355 }, w, h, Bitmap.Config.ARGB_8888)
        }
    }
    val shown: Bitmap = when {
        cropping -> ui.uncropped!!
        state.comparing -> ui.original ?: pixels
        framed != null -> framed!!
        else -> pixels
    }
    val image = shown.asImageBitmap()
    val currentEdit by rememberUpdatedState(edit)
    val currentUi by rememberUpdatedState(ui)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val box = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val rect = fitRect(box, shown.width, shown.height, state.zoom, state.pan)
        val rectNow by rememberUpdatedState(rect)
        Canvas(
            Modifier.fillMaxSize().pointerInput(tool, ui.selected, ui.selectedLocal, cropping) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val r0 = rectNow
                    fun rel(o: Offset) = Offset((o.x - r0.left) / r0.width, (o.y - r0.top) / r0.height)
                    val start = rel(down.position)
                    val w = pixels.width
                    val h = pixels.height
                    val e0 = currentEdit
                    val ui0 = currentUi
                    val painting = !cropping && (tool == PhotoTool.HEAL || tool == PhotoTool.DRAW ||
                        (tool == PhotoTool.MASKS && e0.locals.getOrNull(ui0.selectedLocal)?.shape is MaskShape.Brush))
                    var target: Overlay? = if (overlayTool && tool != PhotoTool.DRAW) e0.overlays.lastOrNull { hit(it, start.x, start.y, w, h) } else null
                    if (target != null && target.id != ui0.selected) vm.select(target.id)
                    if (target == null && overlayTool && tool != PhotoTool.DRAW) target = e0.overlays.firstOrNull { it.id == ui0.selected && hit(it, start.x, start.y, w, h) }
                    // Crop handle: 0..3 corners (tl,tr,br,bl), 4..7 edges (l,t,r,b), 8 = move.
                    val g0 = e0.geometry
                    val cropHandle = if (cropping) {
                        val cr = Rect(r0.left + g0.left * r0.width, r0.top + g0.top * r0.height, r0.left + g0.right * r0.width, r0.top + g0.bottom * r0.height)
                        val p = down.position
                        val t = 64f
                        val corners = listOf(cr.topLeft, cr.topRight, cr.bottomRight, cr.bottomLeft)
                        corners.indexOfFirst { hypot(it.x - p.x, it.y - p.y) < t }.takeIf { it >= 0 }
                            ?: when {
                                abs(p.x - cr.left) < t && p.y in cr.top..cr.bottom -> 4
                                abs(p.y - cr.top) < t && p.x in cr.left..cr.right -> 5
                                abs(p.x - cr.right) < t && p.y in cr.top..cr.bottom -> 6
                                abs(p.y - cr.bottom) < t && p.x in cr.left..cr.right -> 7
                                cr.contains(p) -> 8
                                else -> -1
                            }
                    } else -1
                    // Radial/linear mask handles.
                    val local = e0.locals.getOrNull(ui0.selectedLocal)
                    var maskHandle = -1
                    if (tool == PhotoTool.MASKS && local != null) {
                        when (val sh = local.shape) {
                            is MaskShape.Radial -> {
                                val cx = sh.cx; val cy = sh.cy
                                val ex = cx + sh.rx; val ey = cy + sh.ry
                                maskHandle = when {
                                    hypot((start.x - ex) * r0.width, (start.y - cy) * r0.height) < 60f -> 1
                                    hypot((start.x - cx) * r0.width, (start.y - ey) * r0.height) < 60f -> 2
                                    else -> 0
                                }
                            }
                            is MaskShape.Linear -> maskHandle = if (hypot((start.x - sh.x0) * r0.width, (start.y - sh.y0) * r0.height) < hypot((start.x - sh.x1) * r0.width, (start.y - sh.y1) * r0.height)) 0 else 1
                            else -> {}
                        }
                    }
                    var multi = false
                    var moved = false
                    val stroke = ArrayList<Float>()
                    if (painting) { stroke += start.x; stroke += start.y; state.live = stroke.toList() }
                    var gestureBegun = false
                    fun begin() { if (!gestureBegun) { gestureBegun = true; vm.beginGesture() } }
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            multi = true
                            if (painting) { stroke.clear(); state.live = emptyList() }
                            val zoom = event.calculateZoom()
                            val panBy = event.calculatePan()
                            val rot = event.calculateRotation()
                            val sel = currentEdit.overlays.firstOrNull { it.id == currentUi.selected }
                            if (overlayTool && sel != null) {
                                begin()
                                val r = rectNow
                                vm.updateOverlay(sel.id, commit = false) { o ->
                                    o.moved(o.x + panBy.x / r.width, o.y + panBy.y / r.height, (o.scale * zoom).coerceIn(0.01f, 6f), o.rotation + rot)
                                }
                            } else {
                                val c = event.calculateCentroid()
                                val newZoom = (state.zoom * zoom).coerceIn(1f, 8f)
                                val k = newZoom / state.zoom
                                val centre = Offset(box.width / 2, box.height / 2)
                                state.pan = (state.pan + panBy - (c - centre - state.pan) * (k - 1))
                                state.zoom = newZoom
                                if (newZoom == 1f) state.pan = Offset.Zero
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val ch = pressed.first()
                        val delta = ch.positionChange()
                        if (hypot(ch.position.x - down.position.x, ch.position.y - down.position.y) > viewConfiguration.touchSlop) moved = true
                        val r = rectNow
                        val p = Offset((ch.position.x - r.left) / r.width, (ch.position.y - r.top) / r.height)
                        when {
                            multi -> {}
                            painting -> {
                                stroke += p.x; stroke += p.y
                                state.live = stroke.toList()
                            }
                            cropping && cropHandle >= 0 && moved -> {
                                begin()
                                val (uw, uh) = (ui0.uncropped!!.width.toFloat()) to (ui0.uncropped.height.toFloat())
                                vm.change(commit = false) { e -> e.copy(geometry = dragCrop(e.geometry, cropHandle, delta.x / r.width, delta.y / r.height, state.cropAspect?.let { it * uh / uw })) }
                            }
                            maskHandle >= 0 && local != null && moved -> {
                                begin()
                                val idx = ui0.selectedLocal
                                vm.updateLocal(idx) { l ->
                                    l.copy(shape = when (val sh = l.shape) {
                                        is MaskShape.Radial -> when (maskHandle) {
                                            1 -> sh.copy(rx = abs(p.x - sh.cx).coerceAtLeast(0.02f))
                                            2 -> sh.copy(ry = abs(p.y - sh.cy).coerceAtLeast(0.02f))
                                            else -> sh.copy(cx = sh.cx + delta.x / r.width, cy = sh.cy + delta.y / r.height)
                                        }
                                        is MaskShape.Linear -> if (maskHandle == 0) sh.copy(x0 = p.x, y0 = p.y) else sh.copy(x1 = p.x, y1 = p.y)
                                        else -> sh
                                    })
                                }
                            }
                            target != null && moved -> {
                                begin()
                                vm.updateOverlay(target.id, commit = false) { o -> o.moved(o.x + delta.x / r.width, o.y + delta.y / r.height, o.scale, o.rotation) }
                            }
                            state.zoom > 1f && moved -> state.pan += delta
                        }
                        ch.consume()
                    }
                    if (gestureBegun) vm.endGesture()
                    if (painting && stroke.size >= 2 && !multi) {
                        val pts = stroke.toList()
                        when (tool) {
                            PhotoTool.HEAL -> state.healStrokes += MaskStroke(pts, state.brushSize, 0.9f)
                            PhotoTool.DRAW -> {
                                val st = Stroke(pts, state.drawColor, state.drawWidth, state.drawKind, state.eraser)
                                val layer = currentEdit.overlays.lastOrNull { it.id == currentUi.selected } as? Overlay.DrawingLayer
                                if (layer != null && layer.x == 0.5f && layer.y == 0.5f && layer.scale == 1f && layer.rotation == 0f) {
                                    vm.updateOverlay(layer.id) { (it as Overlay.DrawingLayer).copy(strokes = it.strokes + st) }
                                } else if (!st.erase) {
                                    // The eraser only works on the drawing being added to; on its own it has nothing to erase.
                                    vm.addOverlay(Overlay.DrawingLayer(vm.newId(), listOf(st)))
                                }
                            }
                            PhotoTool.MASKS -> {
                                val idx = currentUi.selectedLocal
                                vm.updateLocal(idx, commit = true) { l ->
                                    val sh = l.shape as? MaskShape.Brush ?: return@updateLocal l
                                    l.copy(shape = sh.copy(strokes = sh.strokes + MaskStroke(pts, state.brushSize, 0.5f, state.maskErase)))
                                }
                            }
                            else -> {}
                        }
                    }
                    state.live = emptyList()
                    if (!moved && !multi && !painting) {
                        // A tap: select (or deselect) an overlay; double-tap text to edit; elsewhere reset zoom.
                        if (overlayTool) {
                            val tapped = currentEdit.overlays.lastOrNull { hit(it, start.x, start.y, w, h) }
                            vm.select(tapped?.id)
                            val now = System.currentTimeMillis()
                            if (tapped is Overlay.TextLayer && state.lastTap.first == tapped.id && now - state.lastTap.second < 350) onEditText(tapped.id)
                            state.lastTap = (tapped?.id ?: "") to now
                        } else if (!cropping && maskHandle < 0) {
                            val now = System.currentTimeMillis()
                            if (state.lastTap.first == "view" && now - state.lastTap.second < 350) state.resetView()
                            state.lastTap = "view" to now
                        }
                    }
                }
            },
        ) {
            if (edit.background.mode == BackgroundMode.TRANSPARENT && !state.comparing) drawChecker(rect)
            drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(shown.width, shown.height), dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()), dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()))
            if (cropping) {
                drawCrop(rect, edit.geometry)
                return@Canvas
            }
            if (framed == null && !state.comparing && edit.overlays.isNotEmpty()) {
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    nc.save()
                    nc.translate(rect.left, rect.top)
                    nc.scale(rect.width / pixels.width, rect.height / pixels.height)
                    PhotoRenderer.drawOverlays(nc, pixels.width, pixels.height, edit.overlays, dir)
                    nc.restore()
                }
                ui.selected?.let { id -> edit.overlays.firstOrNull { it.id == id }?.let { drawSelection(it, rect, pixels.width, pixels.height) } }
            }
            maskPreview?.let { m -> drawImage(m.asImageBitmap(), srcOffset = IntOffset.Zero, srcSize = IntSize(m.width, m.height), dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()), dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt())) }
            if (tool == PhotoTool.MASKS && selectedLocal != null) drawMaskHandles(selectedLocal, rect)
            if (tool == PhotoTool.HEAL) {
                val s = min(rect.width, rect.height)
                for (st in state.healStrokes) drawRelStroke(st.points, rect, st.radius * s * 2, Color(0x88FF3355))
            }
            if (state.live.size >= 2) {
                val s = min(rect.width, rect.height)
                when (tool) {
                    PhotoTool.DRAW -> if (state.eraser) drawRelStroke(state.live, rect, state.drawWidth * s, Color(0x88FFFFFF)) else drawIntoCanvas { c ->
                        val nc = c.nativeCanvas
                        nc.save()
                        nc.translate(rect.left, rect.top)
                        nc.scale(rect.width / pixels.width, rect.height / pixels.height)
                        PhotoRenderer.drawStroke(nc, Stroke(state.live, state.drawColor, state.drawWidth, state.drawKind, false), pixels.width, pixels.height, min(pixels.width, pixels.height).toFloat())
                        nc.restore()
                    }
                    else -> drawRelStroke(state.live, rect, state.brushSize * s * 2, if (state.maskErase && tool == PhotoTool.MASKS) Color(0x8800AAFF) else Color(0x88FF3355))
                }
            }
        }
    }
}

/** Applies a crop-handle drag in relative units, keeping [aspect] (relative w/h) if set. */
private fun dragCrop(g: Geometry, handle: Int, dx: Float, dy: Float, aspect: Float?): Geometry {
    var l = g.left; var t = g.top; var r = g.right; var b = g.bottom
    val min = 0.05f
    when (handle) {
        0 -> { l += dx; t += dy }
        1 -> { r += dx; t += dy }
        2 -> { r += dx; b += dy }
        3 -> { l += dx; b += dy }
        4 -> l += dx
        5 -> t += dy
        6 -> r += dx
        7 -> b += dy
        8 -> {
            val mx = dx.coerceIn(-l, 1f - r)
            val my = dy.coerceIn(-t, 1f - b)
            return g.copy(left = l + mx, right = r + mx, top = t + my, bottom = b + my)
        }
    }
    l = l.coerceIn(0f, r - min); t = t.coerceIn(0f, b - min)
    r = r.coerceIn(l + min, 1f); b = b.coerceIn(t + min, 1f)
    if (aspect != null) {
        val w = r - l
        val h = b - t
        // Grow or shrink the side the handle doesn't control.
        if (handle == 5 || handle == 7) {
            val nw = (h * aspect).coerceAtMost(1f)
            val cx = (l + r) / 2
            l = (cx - nw / 2).coerceIn(0f, 1f - nw); r = l + nw
        } else {
            val nh = (w / aspect).coerceAtMost(1f)
            if (handle == 0 || handle == 1) t = (b - nh).coerceAtLeast(0f) else b = (t + nh).coerceAtMost(1f)
            if (b - t < nh - 1e-3f) {
                val fw = (b - t) * aspect
                if (handle == 0 || handle == 3) l = r - fw else r = l + fw
            }
        }
    }
    return g.copy(left = l, top = t, right = r, bottom = b)
}

private fun DrawScope.drawCrop(rect: Rect, g: Geometry) {
    val cr = Rect(rect.left + g.left * rect.width, rect.top + g.top * rect.height, rect.left + g.right * rect.width, rect.top + g.bottom * rect.height)
    val dim = Color(0xAA000000)
    drawRect(dim, Offset(rect.left, rect.top), Size(rect.width, cr.top - rect.top))
    drawRect(dim, Offset(rect.left, cr.bottom), Size(rect.width, rect.bottom - cr.bottom))
    drawRect(dim, Offset(rect.left, cr.top), Size(cr.left - rect.left, cr.height))
    drawRect(dim, Offset(cr.right, cr.top), Size(rect.right - cr.right, cr.height))
    drawRect(Color.White, cr.topLeft, cr.size, style = DrawStroke(2f))
    for (k in 1..2) {
        drawLine(Color(0x99FFFFFF), Offset(cr.left + cr.width * k / 3, cr.top), Offset(cr.left + cr.width * k / 3, cr.bottom), 1f)
        drawLine(Color(0x99FFFFFF), Offset(cr.left, cr.top + cr.height * k / 3), Offset(cr.right, cr.top + cr.height * k / 3), 1f)
    }
    val len = 28f
    for ((p, sx, sy) in listOf(Triple(cr.topLeft, 1, 1), Triple(cr.topRight, -1, 1), Triple(cr.bottomRight, -1, -1), Triple(cr.bottomLeft, 1, -1))) {
        drawLine(Color.White, p, Offset(p.x + len * sx, p.y), 6f)
        drawLine(Color.White, p, Offset(p.x, p.y + len * sy), 6f)
    }
}

/** The grey-and-white squares that show where a photo is transparent. */
private fun DrawScope.drawChecker(rect: Rect) {
    val cell = 12.dp.toPx()
    // Only the part on screen: zoomed in, the photo can be many screens wide.
    val visible = rect.intersect(Rect(Offset.Zero, size))
    if (visible.isEmpty) return
    drawRect(Color.White, visible.topLeft, visible.size)
    clipRect(visible.left, visible.top, visible.right, visible.bottom) {
        val firstRow = ((visible.top - rect.top) / cell).toInt()
        val firstCol = ((visible.left - rect.left) / cell).toInt()
        var row = firstRow
        while (rect.top + row * cell < visible.bottom) {
            var col = firstCol + (row + firstCol) % 2
            while (rect.left + col * cell < visible.right) {
                drawRect(Color(0xFFD4D4D8), Offset(rect.left + col * cell, rect.top + row * cell), Size(cell, cell))
                col += 2
            }
            row++
        }
    }
}

private fun DrawScope.drawRelStroke(points: List<Float>, rect: Rect, width: Float, color: Color) {
    val path = Path()
    var i = 0
    while (i + 1 < points.size) {
        val x = rect.left + points[i] * rect.width
        val y = rect.top + points[i + 1] * rect.height
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        i += 2
    }
    if (points.size == 2) path.lineTo(rect.left + points[0] * rect.width + 0.5f, rect.top + points[1] * rect.height)
    drawPath(path, color, style = DrawStroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawSelection(o: Overlay, rect: Rect, w: Int, h: Int) {
    val sx = rect.width / w
    val s = min(w, h).toFloat()
    val (bw, bh) = when (o) {
        is Overlay.TextLayer -> PhotoRenderer.textBounds(o, s).let { it.width() to it.height() }
        is Overlay.StickerLayer -> (o.scale * s) to (o.scale * s)
        is Overlay.ShapeLayer -> (o.scale * s * o.aspect) to (o.scale * s)
        is Overlay.ImageLayer -> (o.scale * s * o.aspect) to (o.scale * s)
        is Overlay.DrawingLayer -> return
    }
    val cx = rect.left + o.x * rect.width
    val cy = rect.top + o.y * rect.height
    withTransform({ rotate(o.rotation, Offset(cx, cy)) }) {
        drawRect(Color.White, Offset(cx - bw * sx / 2, cy - bh * sx / 2), Size(bw * sx, bh * sx), style = DrawStroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
    }
}

private fun DrawScope.drawMaskHandles(local: LocalAdjustment, rect: Rect) {
    val c = Color.White
    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        when (val sh = local.shape) {
            is MaskShape.Radial -> {
                val cx = rect.left + sh.cx * rect.width
                val cy = rect.top + sh.cy * rect.height
                val rx = sh.rx * rect.width
                val ry = sh.ry * rect.height
                drawOval(c, Offset(cx - rx, cy - ry), Size(2 * rx, 2 * ry), style = DrawStroke(2f))
                drawOval(Color(0x88FFFFFF), Offset(cx - rx * (1 - sh.feather), cy - ry * (1 - sh.feather)), Size(2 * rx * (1 - sh.feather), 2 * ry * (1 - sh.feather)), style = DrawStroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                drawCircle(c, 10f, Offset(cx, cy))
                drawCircle(c, 9f, Offset(cx + rx, cy))
                drawCircle(c, 9f, Offset(cx, cy + ry))
            }
            is MaskShape.Linear -> {
                val a = Offset(rect.left + sh.x0 * rect.width, rect.top + sh.y0 * rect.height)
                val b = Offset(rect.left + sh.x1 * rect.width, rect.top + sh.y1 * rect.height)
                drawLine(c, a, b, 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
                drawCircle(c, 12f, a)
                drawCircle(Color(0xFF9CA3AF), 12f, b)
            }
            is MaskShape.Brush -> {}
        }
    }
}
