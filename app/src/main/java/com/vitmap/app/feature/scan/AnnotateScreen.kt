package com.vitmap.app.feature.scan

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.BorderColor
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FormatColorReset
import androidx.compose.material.icons.rounded.HorizontalRule
import androidx.compose.material.icons.rounded.Interests
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.AutoFixNormal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.components.loadBitmap
import com.vitmap.app.ui.theme.Accent
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class AnnotTool(val label: String, val icon: ImageVector) {
    HAND("Move", Icons.Rounded.PanTool),
    PEN("Pen", Icons.Rounded.Edit),
    MARKER("Marker", Icons.Rounded.BorderColor),
    ERASER("Eraser", Icons.Rounded.AutoFixNormal),
    TEXT("Text", Icons.Rounded.TextFields),
    SIGN("Sign", Icons.Rounded.Draw),
    IMAGE("Image", Icons.Rounded.AddPhotoAlternate),
    SHAPE("Shape", Icons.Rounded.Interests),
    WHITEOUT("Whiteout", Icons.Rounded.FormatColorReset),
}

val InkColors = listOf(0xFF111111, 0xFF1E3A8A, 0xFF2563EB, 0xFFDC2626, 0xFF16A34A, 0xFF9333EA, 0xFFF97316, 0xFFFFFFFF)
val MarkerColors = listOf(0xFFFDE047, 0xFF86EFAC, 0xFF7DD3FC, 0xFFF9A8D4, 0xFFFDBA74, 0xFFC4B5FD)

@Composable
fun AnnotateScreen(vm: ScanViewModel, docId: String, startIndex: Int, accent: Accent) {
    val docs by vm.docs.collectAsState()
    val doc = docs.firstOrNull { it.id == docId }
    if (doc == null || doc.pages.isEmpty()) {
        LaunchedEffect(Unit) { vm.pop() }
        return
    }
    var index by remember { mutableStateOf(startIndex.coerceIn(0, doc.pages.lastIndex)) }
    val page = doc.pages[index.coerceIn(0, doc.pages.lastIndex)]
    PageAnnotator(vm, doc, page, index, accent, onIndex = { index = it })
}

@Composable
private fun PageAnnotator(vm: ScanViewModel, doc: ScanDoc, page: DocPage, index: Int, accent: Accent, onIndex: (Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dir = remember(doc.id) { vm.repo.dir(doc.id) }

    var tool by remember { mutableStateOf(AnnotTool.HAND) }
    var shape by remember { mutableStateOf(ShapeKind.RECT) }
    var inkColor by remember { mutableStateOf(InkColors[2]) }
    var markerColor by remember { mutableStateOf(MarkerColors[0]) }
    var penWidth by remember { mutableFloatStateOf(0.004f) }
    var markerWidth by remember { mutableFloatStateOf(0.022f) }
    var showWidth by remember { mutableStateOf(false) }

    val annotations = remember(page.id) { mutableStateListOf<Annot>().apply { addAll(page.annotations) } }
    val undo = remember(page.id) { ArrayDeque<List<Annot>>() }
    val redo = remember(page.id) { ArrayDeque<List<Annot>>() }
    var historyTick by remember { mutableStateOf(0) }
    var selectedId by remember(page.id) { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf<TextAnnot?>(null) }
    var signing by remember { mutableStateOf(false) }

    fun commit(next: List<Annot>) {
        undo.addLast(annotations.toList())
        if (undo.size > 60) undo.removeFirst()
        redo.clear()
        annotations.clear()
        annotations.addAll(next)
        historyTick++
        vm.setPageAnnotations(doc.id, page.id, next)
    }

    fun undoOnce() {
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(annotations.toList())
        annotations.clear()
        annotations.addAll(prev)
        historyTick++
        vm.setPageAnnotations(doc.id, page.id, prev)
    }

    fun redoOnce() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(annotations.toList())
        annotations.clear()
        annotations.addAll(next)
        historyTick++
        vm.setPageAnnotations(doc.id, page.id, next)
    }

    var bitmap by remember(page.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(page.id, page.revision) {
        bitmap = runCatching { PageRenderer.render(context, doc.id, page, 1800) }.getOrNull()
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bmp = loadBitmap(context, uri, 1600) ?: return@launch
            val name = if (bmp.hasAlpha()) vm.repo.putPng(doc.id, bmp) else vm.repo.putImage(doc.id, bmp)
            val b = bitmap ?: return@launch
            val pageAspect = b.height.toFloat() / b.width
            val w = 0.4f
            val h = w * bmp.height / bmp.width / pageAspect
            val a = ImageAnnot(file = name, x = 0.3f, y = (0.5f - h / 2).coerceAtLeast(0f), w = w, h = h)
            commit(annotations + a)
            selectedId = a.id
            tool = AnnotTool.HAND
        }
    }

    BackHandler { vm.pop() }

    ToolScaffold(
        title = doc.name,
        subtitle = "Page ${index + 1} of ${doc.pages.size}",
        accent = accent,
        onBack = { vm.pop() },
        actions = {
            IconButton(onClick = { undoOnce() }, enabled = historyTick >= 0 && undo.isNotEmpty()) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo") }
            IconButton(onClick = { redoOnce() }, enabled = historyTick >= 0 && redo.isNotEmpty()) { Icon(Icons.AutoMirrored.Rounded.Redo, "Redo") }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    // Options for the current tool
                    AnimatedVisibility(tool in setOf(AnnotTool.PEN, AnnotTool.MARKER, AnnotTool.SHAPE, AnnotTool.TEXT)) {
                        Column {
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (tool == AnnotTool.SHAPE) {
                                    for (k in ShapeKind.entries) {
                                        FilterChip(selected = shape == k, onClick = { shape = k }, label = { Text(k.name.lowercase().replaceFirstChar { it.uppercase() }) }, leadingIcon = {
                                            Icon(
                                                when (k) {
                                                    ShapeKind.RECT -> Icons.Rounded.CropSquare
                                                    ShapeKind.OVAL -> Icons.Rounded.RadioButtonUnchecked
                                                    ShapeKind.LINE -> Icons.Rounded.HorizontalRule
                                                    ShapeKind.ARROW -> Icons.AutoMirrored.Rounded.ArrowForward
                                                },
                                                null,
                                                Modifier.size(18.dp),
                                            )
                                        })
                                    }
                                }
                                val palette = if (tool == AnnotTool.MARKER) MarkerColors else InkColors
                                val current = if (tool == AnnotTool.MARKER) markerColor else inkColor
                                for (c in palette) {
                                    Box(
                                        Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(Color(c))
                                            .border(if (c == current) 3.dp else 1.dp, if (c == current) accent.start else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                            .clickable { if (tool == AnnotTool.MARKER) markerColor = c else inkColor = c },
                                    )
                                }
                                if (tool != AnnotTool.TEXT) {
                                    IconButton(onClick = { showWidth = !showWidth }) { Icon(Icons.Rounded.Tune, "Stroke width") }
                                }
                            }
                            AnimatedVisibility(showWidth && tool != AnnotTool.TEXT) {
                                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Width", style = MaterialTheme.typography.labelLarge)
                                    if (tool == AnnotTool.MARKER) Slider(markerWidth, { markerWidth = it }, valueRange = 0.008f..0.06f, modifier = Modifier.weight(1f).padding(start = 12.dp))
                                    else Slider(penWidth, { penWidth = it }, valueRange = 0.001f..0.02f, modifier = Modifier.weight(1f).padding(start = 12.dp))
                                }
                            }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onIndex((index - 1).coerceAtLeast(0)) }, enabled = index > 0) { Icon(Icons.Rounded.ChevronLeft, "Previous page") }
                        for (t in AnnotTool.entries) {
                            ToolButton(t, tool == t, accent) {
                                when (t) {
                                    AnnotTool.SIGN -> signing = true
                                    AnnotTool.IMAGE -> imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    else -> tool = t
                                }
                                if (t != AnnotTool.HAND) selectedId = null
                            }
                        }
                        IconButton(onClick = { onIndex((index + 1).coerceAtMost(doc.pages.lastIndex)) }, enabled = index < doc.pages.lastIndex) { Icon(Icons.Rounded.ChevronRight, "Next page") }
                    }
                }
            }
        },
    ) { padding ->
        val bmp = bitmap
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
            if (bmp == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
                return@BoxWithConstraints
            }
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            val margin = with(density) { 16.dp.toPx() }
            val fit = min((boxW - margin * 2) / bmp.width, (boxH - margin * 2) / bmp.height)
            val pw = bmp.width * fit
            val ph = bmp.height * fit
            val left = (boxW - pw) / 2
            val top = (boxH - ph) / 2
            var scale by remember(page.id) { mutableFloatStateOf(1f) }
            var offset by remember(page.id) { mutableStateOf(Offset.Zero) }
            val live = remember { mutableStateListOf<Float>() }
            var dragStart by remember { mutableStateOf<Offset?>(null) }
            var dragEnd by remember { mutableStateOf<Offset?>(null) }

            val state = rememberUpdatedState(Triple(tool, shape, annotations.toList()))
            fun toPage(p: Offset): Offset = Offset(((p.x - left - offset.x) / scale) / pw, ((p.y - top - offset.y) / scale) / ph)
            val aspect = ph / pw

            fun hit(n: Offset): Annot? = annotations.lastOrNull { a ->
                val b = a.bounds(aspect) ?: return@lastOrNull false
                val pad = 0.02f
                n.x >= b.x - pad && n.x <= b.x + b.w + pad && n.y >= b.y - pad && n.y <= b.y + b.h + pad
            }

            Box(
                Modifier.fillMaxSize().pointerInput(page.id, pw, ph) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val (t, sk, list) = state.value
                        var multi = false
                        var movingId: String? = null
                        var moveTotal = Offset.Zero
                        val startN = toPage(down.position)
                        val startAnnots = list
                        if (t == AnnotTool.HAND) {
                            val h = hit(startN)
                            selectedId = h?.id
                            movingId = h?.id
                        } else if (t == AnnotTool.TEXT) {
                            // Handled on release as a tap.
                        } else {
                            live.clear()
                            live.add(startN.x); live.add(startN.y)
                            dragStart = startN
                            dragEnd = startN
                        }
                        var lastPos = down.position
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size > 1) {
                                if (!multi) {
                                    multi = true
                                    live.clear()
                                    dragStart = null
                                    dragEnd = null
                                    movingId = null
                                }
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val c = event.calculateCentroid()
                                val newScale = (scale * zoom).coerceIn(1f, 6f)
                                val z = newScale / scale
                                offset = offset + (c - Offset(left, top) - offset) * (1 - z) + pan
                                scale = newScale
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            if (multi) continue
                            val ch = pressed.first()
                            if (!ch.positionChanged()) continue
                            val pos = ch.position
                            val delta = pos - lastPos
                            lastPos = pos
                            if ((pos - down.position).getDistance() > 6f) moved = true
                            ch.consume()
                            val n = toPage(pos)
                            when (t) {
                                AnnotTool.HAND -> {
                                    val id = movingId
                                    if (id != null) {
                                        moveTotal += Offset(delta.x / scale / pw, delta.y / scale / ph)
                                        val idx = annotations.indexOfFirst { it.id == id }
                                        if (idx >= 0) {
                                            val orig = startAnnots.firstOrNull { it.id == id }
                                            if (orig != null) annotations[idx] = orig.moved(moveTotal.x, moveTotal.y)
                                        }
                                    } else if (scale > 1f) {
                                        offset += delta
                                    }
                                }
                                AnnotTool.PEN, AnnotTool.MARKER -> {
                                    val lx = live[live.size - 2]
                                    val ly = live[live.size - 1]
                                    if (hypot((n.x - lx) * pw, (n.y - ly) * ph) * scale > 2.5f) {
                                        live.add(n.x); live.add(n.y)
                                    }
                                }
                                AnnotTool.ERASER -> {
                                    val r = 0.025f / scale
                                    val keep = annotations.filterNot { a ->
                                        when (a) {
                                            is InkAnnot -> (a.points.indices step 2).any { i -> abs(a.points[i] - n.x) < r && abs(a.points[i + 1] - n.y) * aspect < r }
                                            is ShapeAnnot, is WhiteoutAnnot -> a.bounds(aspect)?.contains(n.x, n.y) == true
                                            else -> false
                                        }
                                    }
                                    if (keep.size != annotations.size) {
                                        annotations.clear()
                                        annotations.addAll(keep)
                                    }
                                }
                                else -> dragEnd = n
                            }
                        }
                        if (multi) return@awaitEachGesture
                        when (t) {
                            AnnotTool.HAND -> if (movingId != null && moveTotal != Offset.Zero) {
                                val next = annotations.toList()
                                annotations.clear(); annotations.addAll(startAnnots)
                                commit(next)
                            }
                            AnnotTool.PEN, AnnotTool.MARKER -> {
                                if (live.size >= 2) {
                                    val a = if (t == AnnotTool.MARKER) InkAnnot(points = live.toList(), color = markerColor, width = markerWidth, highlighter = true)
                                    else InkAnnot(points = live.toList(), color = inkColor, width = penWidth)
                                    commit(annotations + a)
                                }
                                live.clear()
                            }
                            AnnotTool.ERASER -> if (annotations.size != startAnnots.size) {
                                val next = annotations.toList()
                                annotations.clear(); annotations.addAll(startAnnots)
                                commit(next)
                            }
                            AnnotTool.TEXT -> if (!moved) {
                                val existing = hit(startN) as? TextAnnot
                                editingText = existing ?: TextAnnot(text = "", x = startN.x.coerceIn(0f, 0.95f), y = startN.y.coerceIn(0f, 0.97f), color = inkColor)
                            }
                            AnnotTool.SHAPE, AnnotTool.WHITEOUT -> {
                                val s = dragStart
                                val e = dragEnd
                                if (s != null && e != null && (abs(e.x - s.x) > 0.01f || abs(e.y - s.y) > 0.01f)) {
                                    val a = if (t == AnnotTool.SHAPE) ShapeAnnot(shape = sk, x1 = s.x, y1 = s.y, x2 = e.x, y2 = e.y, color = inkColor, width = penWidth)
                                    else WhiteoutAnnot(x = min(s.x, e.x), y = min(s.y, e.y), w = abs(e.x - s.x), h = abs(e.y - s.y))
                                    commit(annotations + a)
                                }
                                dragStart = null
                                dragEnd = null
                            }
                            else -> {}
                        }
                    }
                },
            ) {
                Box(
                    Modifier
                        .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                        .size(with(density) { pw.toDp() }, with(density) { ph.toDp() })
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .shadow(8.dp, RoundedCornerShape(2.dp)),
                ) {
                    Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    Canvas(Modifier.fillMaxSize()) {
                        drawIntoCanvas { c -> AnnotPainter.draw(c.nativeCanvas, annotations, size.width, size.height, dir) }
                        if (live.size >= 2) {
                            val isMarker = tool == AnnotTool.MARKER
                            val ink = InkAnnot(points = live.toList(), color = if (isMarker) markerColor else inkColor, width = if (isMarker) markerWidth else penWidth, highlighter = isMarker)
                            drawIntoCanvas { c -> AnnotPainter.drawOne(c.nativeCanvas, ink, size.width, size.height, dir) }
                        }
                        val s = dragStart
                        val e = dragEnd
                        if (s != null && e != null && (tool == AnnotTool.SHAPE || tool == AnnotTool.WHITEOUT)) {
                            val preview: Annot = if (tool == AnnotTool.SHAPE) ShapeAnnot(shape = shape, x1 = s.x, y1 = s.y, x2 = e.x, y2 = e.y, color = inkColor, width = penWidth)
                            else WhiteoutAnnot(x = min(s.x, e.x), y = min(s.y, e.y), w = abs(e.x - s.x), h = abs(e.y - s.y), color = 0xFFF1F5F9)
                            drawIntoCanvas { c -> AnnotPainter.drawOne(c.nativeCanvas, preview, size.width, size.height, dir) }
                        }
                        val sel = annotations.firstOrNull { it.id == selectedId }
                        val b = sel?.bounds(size.height / size.width)
                        if (b != null) {
                            drawRect(
                                accent.start,
                                Offset(b.x * size.width - 6, b.y * size.height - 6),
                                Size(b.w * size.width + 12, b.h * size.height + 12),
                                style = Stroke(2.dp.toPx() / scale, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                            )
                        }
                    }
                }
                // Floating controls for the selected annotation
                val sel = annotations.firstOrNull { it.id == selectedId }
                if (sel != null && tool == AnnotTool.HAND) {
                    Row(
                        Modifier.align(Alignment.TopCenter).padding(8.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(MaterialTheme.colorScheme.surface).padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (sel !is InkAnnot) {
                            IconButton(onClick = { commit(annotations.map { if (it.id == sel.id) it.resized(0.87f) else it }) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
                            IconButton(onClick = { commit(annotations.map { if (it.id == sel.id) it.resized(1.15f) else it }) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
                        }
                        if (sel is TextAnnot) IconButton(onClick = { editingText = sel }) { Icon(Icons.Rounded.Edit, "Edit text") }
                        IconButton(onClick = { commit(annotations.filter { it.id != sel.id }); selectedId = null }) { Icon(Icons.Rounded.DeleteOutline, "Delete") }
                    }
                }
            }
        }
    }

    editingText?.let { t ->
        TextAnnotDialog(
            initial = t,
            onDismiss = { editingText = null },
            onDone = { updated ->
                editingText = null
                val exists = annotations.any { it.id == updated.id }
                when {
                    updated.text.isBlank() && exists -> commit(annotations.filter { it.id != updated.id })
                    updated.text.isBlank() -> {}
                    exists -> commit(annotations.map { if (it.id == updated.id) updated else it })
                    else -> commit(annotations + updated)
                }
                selectedId = updated.id
                tool = AnnotTool.HAND
            },
        )
    }
    if (signing) {
        SignatureSheet(vm, onDismiss = { signing = false }) { sig ->
            signing = false
            val b = bitmap ?: return@SignatureSheet
            val pageAspect = b.height.toFloat() / b.width
            val w = 0.36f
            val h = w * sig.aspect / pageAspect
            val a = SignAnnot(strokes = sig.strokes, x = 0.5f, y = 0.72f.coerceAtMost(1f - h), w = w, h = h, color = sig.color)
            commit(annotations + a)
            selectedId = a.id
            tool = AnnotTool.HAND
        }
    }
}

@Composable
private fun ToolButton(t: AnnotTool, selected: Boolean, accent: Accent, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) accent.start.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(t.icon, t.label, tint = if (selected) accent.start else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(2.dp))
        Text(t.label, style = MaterialTheme.typography.labelSmall, color = if (selected) accent.start else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TextAnnotDialog(initial: TextAnnot, onDismiss: () -> Unit, onDone: (TextAnnot) -> Unit) {
    var text by remember { mutableStateOf(initial.text) }
    var size by remember { mutableFloatStateOf(initial.size) }
    var color by remember { mutableStateOf(initial.color) }
    var bold by remember { mutableStateOf(initial.bold) }
    var background by remember { mutableStateOf(initial.background != null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.text.isEmpty()) "Add text" else "Edit text") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.OutlinedTextField(text, { text = it }, minLines = 2, modifier = Modifier.fillMaxWidth(), label = { Text("Text") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Size", style = MaterialTheme.typography.labelLarge)
                    Slider(size, { size = it }, valueRange = 0.012f..0.12f, modifier = Modifier.weight(1f).padding(start = 12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in InkColors) {
                        Box(Modifier.size(26.dp).clip(CircleShape).background(Color(c)).border(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape).clickable { color = c })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = bold, onClick = { bold = !bold }, label = { Text("Bold") })
                    FilterChip(selected = background, onClick = { background = !background }, label = { Text("Highlight box") })
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                onDone(initial.copy(text = text, size = size, color = color, bold = bold, background = if (background) (if (color == 0xFFFFFFFFL) 0xFF111111 else 0xFFFFF59D) else null))
            }) { Text("Done") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
