package com.vitmap.app.feature.scan

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateLeft
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FilterBAndW
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.theme.Accents
import com.vitmap.core.scan.PageFilter
import com.vitmap.core.scan.Pt
import com.vitmap.core.scan.Quad
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class EditTab(val label: String) { CROP("Crop"), FILTER("Filters"), ADJUST("Adjust") }

@Composable
fun PageEditScreen(vm: ScanViewModel, docId: String, pageId: String, fromCamera: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val docs by vm.docs.collectAsState()
    val doc = docs.firstOrNull { it.id == docId }
    val stored = doc?.pages?.firstOrNull { it.id == pageId }
    if (doc == null || stored == null) {
        LaunchedEffect(Unit) { vm.pop() }
        return
    }
    var work by remember(pageId) { mutableStateOf(stored) }
    var tab by remember { mutableStateOf(if (stored.kind == PageKind.IMAGE) EditTab.CROP else EditTab.FILTER) }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    val thumbs = remember { mutableStateMapOf<PageFilter, Bitmap>() }
    val isImage = work.kind == PageKind.IMAGE

    LaunchedEffect(pageId) {
        if (isImage) original = PageRenderer.original(context, docId, work, 1800)
    }
    LaunchedEffect(work.quad, work.rotation, work.filter, work.brightness, work.contrast, work.saturation, work.sharpness, tab) {
        if (tab == EditTab.CROP && isImage) return@LaunchedEffect
        delay(90)
        preview = runCatching { PageRenderer.render(context, docId, work.copy(revision = -1), 1500) }.getOrNull()
    }
    LaunchedEffect(work.quad, work.rotation, tab) {
        if (tab != EditTab.FILTER || !isImage) return@LaunchedEffect
        thumbs.clear()
        for (f in PageFilter.entries) {
            val t = runCatching { PageRenderer.render(context, docId, work.copy(filter = f.name, brightness = 0f, contrast = 0f, saturation = 0f, sharpness = 0f, revision = -2), 260) }.getOrNull()
            if (t != null) thumbs[f] = t
        }
    }

    fun done() {
        vm.updatePage(docId, work)
        if (fromCamera) vm.replace(DocScreen.Editor(docId)) else vm.pop()
    }

    BackHandler { done() }

    ToolScaffold(
        title = if (isImage) "Edit page" else "Page",
        subtitle = "Page ${doc.pages.indexOfFirst { it.id == pageId } + 1} of ${doc.pages.size}",
        accent = Accents.Measure,
        onBack = { done() },
        actions = {
            IconButton(onClick = { work = work.copy(rotation = (work.rotation + 270) % 360) }) { Icon(Icons.AutoMirrored.Rounded.RotateLeft, "Rotate left") }
            IconButton(onClick = { work = work.copy(rotation = (work.rotation + 90) % 360) }) { Icon(Icons.AutoMirrored.Rounded.RotateRight, "Rotate right") }
        },
        bottomBar = {
            Column {
                if (isImage) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        for (t in EditTab.entries) {
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = {
                                    Icon(
                                        when (t) {
                                            EditTab.CROP -> Icons.Rounded.Crop
                                            EditTab.FILTER -> Icons.Rounded.FilterBAndW
                                            EditTab.ADJUST -> Icons.Rounded.Tune
                                        },
                                        null,
                                    )
                                },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(tab == EditTab.CROP && isImage, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "editMode") { cropping ->
                    if (cropping) {
                        val bmp = original
                        if (bmp == null) {
                            CircularProgressIndicator()
                        } else {
                            val quad = work.cropQuad() ?: Quad.full(1.0, 1.0)
                            QuadEditor(bmp, quad, onChange = { work = work.copy(quad = DocPage.quadToList(it)) }, rotation = work.rotation, modifier = Modifier.fillMaxSize())
                        }
                    } else {
                        val bmp = preview
                        if (bmp == null) CircularProgressIndicator() else {
                            Image(bmp.asImageBitmap(), "Page preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        }
                    }
                }
            }
            when {
                isImage && tab == EditTab.CROP -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        scope.launch {
                            val bmp = original ?: return@launch
                            val q = ScanViewModel.detectNormalized(bmp)
                            if (q != null) work = work.copy(quad = DocPage.quadToList(q)) else vm.toast("No page edges found. Drag the corners instead.")
                        }
                    }) {
                        Icon(Icons.Rounded.AutoFixHigh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Auto")
                    }
                    OutlinedButton(onClick = { work = work.copy(quad = null) }) {
                        Icon(Icons.Rounded.CropFree, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Whole photo")
                    }
                    Spacer(Modifier.weight(1f))
                    if (fromCamera) {
                        TextButton(onClick = {
                            vm.deletePages(docId, setOf(pageId))
                            vm.replace(DocScreen.Camera(docId))
                        }) {
                            Icon(Icons.Rounded.CameraAlt, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Retake")
                        }
                    }
                }
                isImage && tab == EditTab.FILTER -> LazyRow(
                    Modifier.fillMaxWidth().height(132.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(PageFilter.entries) { f ->
                        val selected = work.pageFilter == f
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(78.dp)) {
                            Box(
                                Modifier
                                    .size(78.dp, 96.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(if (selected) 3.dp else 1.dp, if (selected) Accents.Measure.start else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .pointerInput(f) { androidx.compose.foundation.gestures.detectTapGestures { work = work.copy(filter = f.name) } },
                                contentAlignment = Alignment.Center,
                            ) {
                                val t = thumbs[f]
                                if (t != null) Image(t.asImageBitmap(), f.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(f.label, style = MaterialTheme.typography.labelMedium, color = if (selected) Accents.Measure.start else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                isImage && tab == EditTab.ADJUST -> Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    AdjustSlider("Brightness", work.brightness) { work = work.copy(brightness = it) }
                    AdjustSlider("Contrast", work.contrast) { work = work.copy(contrast = it) }
                    AdjustSlider("Saturation", work.saturation) { work = work.copy(saturation = it) }
                    AdjustSlider("Sharpness", work.sharpness, 0f..1f) { work = work.copy(sharpness = it) }
                    TextButton(onClick = { work = work.copy(brightness = 0f, contrast = 0f, saturation = 0f, sharpness = 0f) }, modifier = Modifier.align(Alignment.End)) { Text("Reset") }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    vm.deletePages(docId, setOf(pageId))
                    if (fromCamera) vm.replace(DocScreen.Camera(docId)) else vm.pop()
                }) {
                    Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete")
                }
                Spacer(Modifier.weight(1f))
                if (fromCamera) {
                    OutlinedButton(onClick = {
                        vm.updatePage(docId, work)
                        vm.replace(DocScreen.Camera(docId))
                    }) { Text("Scan more") }
                }
                Button(onClick = { done() }) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun AdjustSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float> = -1f..1f, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(92.dp))
        Slider(value, onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text("${(value * 100).roundToInt()}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(40.dp))
    }
}

/**
 * Shows [image] with a draggable page outline. Corners and edge midpoints can be dragged; a
 * magnifier appears while dragging so corners can be placed precisely. [rotation] only rotates the
 * view, the quad stays in the photo's own coordinates.
 */
@Composable
fun QuadEditor(image: Bitmap, quad: Quad, onChange: (Quad) -> Unit, modifier: Modifier = Modifier, rotation: Int = 0) {
    val img = remember(image) { image.asImageBitmap() }
    val density = LocalDensity.current
    val handleR = with(density) { 12.dp.toPx() }
    val touchR = with(density) { 40.dp.toPx() }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var touch by remember { mutableStateOf(Offset.Zero) }
    val current by androidx.compose.runtime.rememberUpdatedState(quad)
    val accent = Accents.Measure.start
    BoxWithConstraints(modifier) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val rotated = rotation % 180 != 0
        val iw = if (rotated) image.height.toFloat() else image.width.toFloat()
        val ih = if (rotated) image.width.toFloat() else image.height.toFloat()
        val scale = min(boxW / iw, boxH / ih)
        val dw = iw * scale
        val dh = ih * scale
        val left = (boxW - dw) / 2
        val top = (boxH - dh) / 2
        // Normalised photo coordinates <-> screen, through the view rotation.
        fun toScreen(p: Pt): Offset {
            val (u, v) = when ((rotation % 360 + 360) % 360) {
                90 -> (1 - p.y) to p.x
                180 -> (1 - p.x) to (1 - p.y)
                270 -> p.y to (1 - p.x)
                else -> p.x to p.y
            }
            return Offset(left + u.toFloat() * dw, top + v.toFloat() * dh)
        }
        fun toPhoto(o: Offset): Pt {
            val u = ((o.x - left) / dw).toDouble().coerceIn(0.0, 1.0)
            val v = ((o.y - top) / dh).toDouble().coerceIn(0.0, 1.0)
            return when ((rotation % 360 + 360) % 360) {
                90 -> Pt(v, 1 - u)
                180 -> Pt(1 - u, 1 - v)
                270 -> Pt(1 - v, u)
                else -> Pt(u, v)
            }
        }
        Canvas(
            Modifier.fillMaxSize().pointerInput(rotation, image) {
                detectDragGestures(
                    onDragStart = { start ->
                        val q = current
                        val c = q.corners.map(::toScreen)
                        val mids = (0 until 4).map { (c[it] + c[(it + 1) % 4]) / 2f }
                        val nearestCorner = c.indices.minBy { (c[it] - start).getDistance() }
                        val nearestMid = mids.indices.minBy { (mids[it] - start).getDistance() }
                        dragging = when {
                            (c[nearestCorner] - start).getDistance() < touchR -> nearestCorner
                            (mids[nearestMid] - start).getDistance() < touchR -> 4 + nearestMid
                            else -> null
                        }
                        touch = start
                    },
                    onDragEnd = { dragging = null },
                    onDragCancel = { dragging = null },
                    onDrag = { change, drag ->
                        val d = dragging ?: return@detectDragGestures
                        change.consume()
                        touch += drag
                        val q = current
                        val pts = q.corners.toMutableList()
                        if (d < 4) {
                            pts[d] = toPhoto(touch)
                        } else {
                            val e = d - 4
                            val a = toScreen(pts[e]) + drag
                            val b = toScreen(pts[(e + 1) % 4]) + drag
                            pts[e] = toPhoto(a)
                            pts[(e + 1) % 4] = toPhoto(b)
                        }
                        onChange(Quad(pts[0], pts[1], pts[2], pts[3]))
                    },
                )
            },
        ) {
            withRotation(rotation, left, top, dw, dh) {
                drawImage(img, dstOffset = IntOffset(0, 0), dstSize = IntSize(if (rotated) dh.roundToInt() else dw.roundToInt(), if (rotated) dw.roundToInt() else dh.roundToInt()))
            }
            val c = quad.corners.map(::toScreen)
            val outline = Path().apply {
                moveTo(c[0].x, c[0].y)
                for (i in 1..3) lineTo(c[i].x, c[i].y)
                close()
            }
            val shade = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(left, top, left + dw, top + dh))
                addPath(outline)
            }
            drawPath(shade, Color.Black.copy(alpha = 0.45f))
            drawPath(outline, accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            for (i in 0 until 4) {
                val m = (c[i] + c[(i + 1) % 4]) / 2f
                drawCircle(Color.White, handleR * 0.55f, m)
                drawCircle(accent, handleR * 0.38f, m)
            }
            for ((i, p) in c.withIndex()) {
                drawCircle(Color.White, handleR, p)
                drawCircle(if (dragging == i) Accents.Measure.end else accent, handleR * 0.7f, p)
            }
            val d = dragging
            if (d != null && d < 4) drawLoupe(img, image, c[d], toPhoto(c[d]), size)
        }
    }
}

private fun DrawScope.withRotation(rotation: Int, left: Float, top: Float, dw: Float, dh: Float, block: DrawScope.() -> Unit) {
    val r = (rotation % 360 + 360) % 360
    drawContext.canvas.save()
    drawContext.transform.translate(left, top)
    when (r) {
        90 -> { drawContext.transform.translate(dw, 0f); drawContext.transform.rotate(90f, Offset.Zero) }
        180 -> { drawContext.transform.translate(dw, dh); drawContext.transform.rotate(180f, Offset.Zero) }
        270 -> { drawContext.transform.translate(0f, dh); drawContext.transform.rotate(270f, Offset.Zero) }
    }
    block()
    drawContext.canvas.restore()
}

/** A circular magnifier in the corner away from the finger. */
private fun DrawScope.drawLoupe(img: ImageBitmap, bmp: Bitmap, at: Offset, photo: Pt, canvasSize: Size) {
    val r = 56.dp.toPx()
    val margin = 16.dp.toPx()
    val center = if (at.x < canvasSize.width / 2) Offset(canvasSize.width - r - margin, r + margin) else Offset(r + margin, r + margin)
    val srcSize = (max(bmp.width, bmp.height) * 0.06f).roundToInt().coerceAtLeast(24)
    val sx = (photo.x * bmp.width).roundToInt() - srcSize / 2
    val sy = (photo.y * bmp.height).roundToInt() - srcSize / 2
    val clip = Path().apply { addOval(Rect(center, r)) }
    drawCircle(Color.Black, r + 3.dp.toPx(), center)
    clipPath(clip) {
        drawRect(Color.Black, Offset(center.x - r, center.y - r), Size(r * 2, r * 2))
        drawImage(
            img,
            srcOffset = IntOffset(sx.coerceIn(0, max(0, bmp.width - srcSize)), sy.coerceIn(0, max(0, bmp.height - srcSize))),
            srcSize = IntSize(min(srcSize, bmp.width), min(srcSize, bmp.height)),
            dstOffset = IntOffset((center.x - r).roundToInt(), (center.y - r).roundToInt()),
            dstSize = IntSize((r * 2).roundToInt(), (r * 2).roundToInt()),
        )
    }
    drawCircle(Color.White, r, center, style = Stroke(3.dp.toPx()))
    drawLine(Accents.Measure.start, Offset(center.x - 10.dp.toPx(), center.y), Offset(center.x + 10.dp.toPx(), center.y), 2.dp.toPx())
    drawLine(Accents.Measure.start, Offset(center.x, center.y - 10.dp.toPx()), Offset(center.x, center.y + 10.dp.toPx()), 2.dp.toPx())
}
