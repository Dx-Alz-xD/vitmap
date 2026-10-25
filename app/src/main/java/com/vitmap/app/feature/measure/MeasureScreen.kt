package com.vitmap.app.feature.measure

import android.Manifest
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Camera
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitmap.app.ui.components.CameraHandle
import com.vitmap.app.ui.components.CameraView
import com.vitmap.app.ui.components.EmptyState
import com.vitmap.app.ui.components.PermissionGate
import com.vitmap.app.ui.components.SectionTitle
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.components.bouncyClickable
import com.vitmap.app.ui.components.loadBitmap
import com.vitmap.app.ui.components.readable
import com.vitmap.app.ui.theme.Accents
import com.vitmap.core.measure.LengthUnit
import com.vitmap.core.measure.Pt
import com.vitmap.core.measure.RefShape
import com.vitmap.core.measure.ReferenceObject
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.min

private val Accent = Color(0xFF2DD4BF)
private val Warn = Color(0xFFFFC145)

@Composable
fun MeasureScreen(onBack: () -> Unit) {
    val vm: MeasureViewModel = viewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    BackHandler(enabled = tab == 0 && s.stage != Stage.SOURCE) {
        if (s.stage == Stage.MEASURE) vm.recalibrate() else vm.backToSource()
    }
    val tabs = listOf("Measure", "Ruler", "References", "History")
    ToolScaffold(
        title = "Measure",
        subtitle = when {
            tab != 0 -> tabs[tab]
            s.stage == Stage.SOURCE -> "Capture a photo with a reference"
            s.stage == Stage.CALIBRATE -> "Align with: ${s.reference.name}"
            else -> "Calibrated with ${s.reference.name}"
        },
        accent = Accents.Measure,
        onBack = onBack,
        snackbarHostState = snackbar,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            AnimatedContent(tab, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "measureTab") { t ->
                when (t) {
                    0 -> MeasureFlow(vm, s, snackbar)
                    1 -> RulerTab(vm, s)
                    2 -> ReferencesTab(vm, s)
                    else -> HistoryTab(vm, s) { tab = 0 }
                }
            }
        }
    }
}

@Composable
private fun MeasureFlow(vm: MeasureViewModel, s: MeasureState, snackbar: SnackbarHostState) {
    AnimatedContent(s.stage, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) }, label = "stage") { stage ->
        when (stage) {
            Stage.SOURCE -> SourceStage(vm)
            Stage.CALIBRATE -> CalibrateStage(vm, s, snackbar)
            Stage.MEASURE -> MeasureStage(vm, s, snackbar)
        }
    }
}

@Composable
private fun SourceStage(vm: MeasureViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val handle = remember { CameraHandle() }
    val shutter = remember { Animatable(0f) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { loadBitmap(context, uri, 2400)?.readable()?.let(vm::setImage) }
    }
    PermissionGate(Manifest.permission.CAMERA, Icons.Rounded.PhotoCamera, "Camera access", "Take a photo of the object next to a reference like a bank card or coin. You can also pick an existing photo.") {
        Box(Modifier.fillMaxSize()) {
            CameraView(Modifier.fillMaxSize(), handle = handle)
            Canvas(Modifier.fillMaxSize()) {
                // Rule-of-thirds guides help keep the phone parallel to the surface.
                val c = Color.White.copy(alpha = 0.35f)
                for (i in 1..2) {
                    drawLine(c, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.5f)
                    drawLine(c, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.5f)
                }
                drawRect(Color.White.copy(alpha = shutter.value), size = size)
            }
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
            ) {
                Text(
                    "Lay a reference (card, coin, A4…) flat beside the object and hold the phone parallel to the surface.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp).navigationBarsPadding(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.AddPhotoAlternate, "Pick photo")
                }
                LargeFloatingActionButton(onClick = {
                    scope.launch {
                        shutter.snapTo(0.8f)
                        shutter.animateTo(0f, tween(350))
                    }
                    handle.snapshot()?.let { vm.setImage(it.readable()) }
                }, shape = CircleShape) {
                    Icon(Icons.Rounded.Camera, "Capture", Modifier.size(36.dp))
                }
                Spacer(Modifier.size(56.dp))
            }
        }
    }
}

/** Maps between bitmap pixels and screen pixels for a fitted image. */
private class Fit(val scale: Float, val offset: Offset) {
    fun toScreen(p: Pt) = Offset(offset.x + p.x.toFloat() * scale, offset.y + p.y.toFloat() * scale)
    fun toImage(o: Offset) = Pt(((o.x - offset.x) / scale).toDouble(), ((o.y - offset.y) / scale).toDouble())
}

@Composable
private fun CalibrateStage(vm: MeasureViewModel, s: MeasureState, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var showRefs by remember { mutableStateOf(false) }
    val bmp = s.bitmap ?: return
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            HandleCanvas(
                bitmap = bmp,
                handles = s.calibPoints,
                onMove = vm::moveCalibPoint,
                drawOverlay = { fit ->
                    val pts = s.calibPoints.map { fit.toScreen(it) }
                    if (pts.size == 4) {
                        val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (k in 1..3) lineTo(pts[k].x, pts[k].y); close() }
                        drawPath(path, Accent.copy(alpha = 0.18f))
                        drawPath(path, Accent, style = Stroke(3.dp.toPx()))
                        // Highlight the edge that maps to the reference width.
                        drawLine(Warn, pts[0], pts[1], 5.dp.toPx(), StrokeCap.Round)
                    } else if (pts.size == 2) {
                        drawLine(Accent, pts[0], pts[1], 4.dp.toPx(), StrokeCap.Round)
                        if (s.reference.shape == RefShape.CIRCLE) {
                            val c = Offset((pts[0].x + pts[1].x) / 2, (pts[0].y + pts[1].y) / 2)
                            drawCircle(Accent.copy(alpha = 0.5f), (pts[0] - pts[1]).getDistance() / 2, c, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))))
                        }
                    }
                },
            )
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { showRefs = true }.padding(6.dp)) {
                        Text(s.reference.name, style = MaterialTheme.typography.titleMedium)
                        Text(s.reference.description + " · tap to change", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (s.reference.shape == RefShape.RECTANGLE && !s.useTwoPoints) {
                        FilledTonalIconButton(onClick = vm::rotateRect) { Icon(Icons.Rounded.RotateRight, "Swap width and height") }
                    }
                }
                Text(
                    when {
                        s.reference.shape == RefShape.RECTANGLE && !s.useTwoPoints ->
                            "Drag the 4 corners onto the reference. The yellow edge is ${if (s.rectRotated) s.reference.heightMm else s.reference.widthMm} mm. Perspective is corrected automatically."
                        s.reference.shape == RefShape.CIRCLE -> "Drag both ends across the widest part of the coin (${s.reference.widthMm} mm)."
                        else -> "Drag both ends onto the reference length (${s.reference.widthMm} mm)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (s.reference.shape == RefShape.RECTANGLE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Use two points (width only)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(s.useTwoPoints, vm::setTwoPoints)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = vm::backToSource, modifier = Modifier.weight(1f)) { Text("Retake") }
                    Button(onClick = { vm.calibrate()?.let { msg -> scope.launch { snackbar.showSnackbar(msg) } } }, modifier = Modifier.weight(1f)) {
                        Text("Calibrate")
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
                    }
                }
            }
        }
    }
    if (showRefs) ReferencePickerSheet(s, onDismiss = { showRefs = false }) { vm.chooseReference(it); showRefs = false }
}

@Composable
private fun MeasureStage(vm: MeasureViewModel, s: MeasureState, snackbar: SnackbarHostState) {
    val bmp = s.bitmap ?: return
    val cal = s.calibration ?: return
    val scope = rememberCoroutineScope()
    var saveRef by remember { mutableStateOf<MItem?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val handles = s.items.flatMap { item -> item.points.mapIndexed { i, p -> Triple(item.id, i, p) } }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            HandleCanvas(
                bitmap = bmp,
                handles = handles.map { it.third },
                onMove = { idx, p -> val h = handles[idx]; vm.movePoint(h.first, h.second, p) },
                onTapEmpty = { p -> vm.addItem(p) },
                drawOverlay = { fit ->
                    for (item in s.items) {
                        val selected = item.id == s.selected
                        val color = if (selected) Warn else Accent
                        val pts = item.points.map { fit.toScreen(it) }
                        val w = (if (selected) 4.dp else 3.dp).toPx()
                        when (item.tool) {
                            MTool.LINE -> drawMeasureLine(pts[0], pts[1], color, w)
                            MTool.PATH -> for (k in 0 until pts.size - 1) drawMeasureLine(pts[k], pts[k + 1], color, w)
                            MTool.AREA -> {
                                val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (k in 1 until pts.size) lineTo(pts[k].x, pts[k].y); close() }
                                drawPath(path, color.copy(alpha = 0.2f))
                                drawPath(path, color, style = Stroke(w))
                            }
                            MTool.ANGLE -> {
                                drawLine(color, pts[1], pts[0], w, StrokeCap.Round)
                                drawLine(color, pts[1], pts[2], w, StrokeCap.Round)
                                drawCircle(color.copy(alpha = 0.3f), 28.dp.toPx(), pts[1])
                            }
                            MTool.CIRCLE -> {
                                val r = (pts[1] - pts[0]).getDistance()
                                drawCircle(color.copy(alpha = 0.15f), r, pts[0])
                                drawCircle(color, r, pts[0], style = Stroke(w))
                                drawLine(color, pts[0], pts[1], w * 0.7f, StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                            }
                        }
                        val anchor = when (item.tool) {
                            MTool.CIRCLE, MTool.ANGLE -> pts[if (item.tool == MTool.CIRCLE) 0 else 1]
                            else -> Offset(pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat())
                        }
                        val label = vm.label(item, cal, s.unit)
                        val style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        val m = textMeasurer.measure(label, style)
                        val pad = 6.dp.toPx()
                        val tl = Offset(anchor.x - m.size.width / 2 - pad, anchor.y - m.size.height - 14.dp.toPx())
                        drawRoundRect(color, tl, Size(m.size.width + pad * 2, m.size.height + pad), androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
                        drawText(textMeasurer, label, Offset(tl.x + pad, tl.y + pad / 2), style)
                    }
                },
            )
            if (s.items.isEmpty()) {
                Surface(shape = RoundedCornerShape(18.dp), color = Color.Black.copy(alpha = 0.55f), modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)) {
                    Text("Tap the photo to add a ${s.tool.title.lowercase()}, then drag its handles.", color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in MTool.entries) FilterChip(selected = s.tool == t, onClick = { vm.setTool(t) }, label = { Text(t.title) })
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    LengthUnit.entries.forEachIndexed { i, u ->
                        SegmentedButton(s.unit == u, { vm.setUnit(u) }, SegmentedButtonDefaults.itemShape(i, LengthUnit.entries.size)) { Text(u.symbol) }
                    }
                }
                val sel = s.items.firstOrNull { it.id == s.selected }
                AnimatedVisibility(sel != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    if (sel != null) {
                        Column {
                            Text(vm.details(sel, cal, s.unit), style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (sel.tool == MTool.PATH || sel.tool == MTool.AREA) {
                                    TextButton(onClick = vm::addPathPoint) { Icon(Icons.Rounded.Add, null); Text("Point") }
                                }
                                if (sel.tool == MTool.LINE || sel.tool == MTool.CIRCLE || sel.tool == MTool.PATH) {
                                    TextButton(onClick = { saveRef = sel }) { Icon(Icons.Rounded.BookmarkAdd, null); Spacer(Modifier.width(4.dp)); Text("Use as reference") }
                                }
                                TextButton(onClick = vm::deleteSelected) { Icon(Icons.Rounded.Delete, null); Text("Delete") }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = vm::recalibrate, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Tune, null); Spacer(Modifier.width(4.dp)); Text("Recalibrate") }
                    Button(onClick = { vm.saveSession(); scope.launch { snackbar.showSnackbar("Saved to history") } }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Save, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Save")
                    }
                }
            }
        }
    }
    saveRef?.let { item ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saveRef = null },
            title = { Text("Save as reference") },
            text = {
                Column {
                    Text("Measured ${vm.label(item, cal, LengthUnit.MM)}. Use this object to calibrate future photos.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. My phone width") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveAsReference(item, name)
                    saveRef = null
                    scope.launch { snackbar.showSnackbar("Added to your references") }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saveRef = null }) { Text("Cancel") } },
        )
    }
}

private fun DrawScope.drawMeasureLine(a: Offset, b: Offset, color: Color, w: Float) {
    drawLine(Color.Black.copy(alpha = 0.35f), a, b, w + 3f, StrokeCap.Round)
    drawLine(color, a, b, w, StrokeCap.Round)
    // End ticks perpendicular to the line, like a tape measure.
    val d = b - a
    val len = d.getDistance().coerceAtLeast(1f)
    val n = Offset(-d.y / len, d.x / len) * 9.dp.toPx()
    drawLine(color, a - n, a + n, w * 0.8f, StrokeCap.Round)
    drawLine(color, b - n, b + n, w * 0.8f, StrokeCap.Round)
}

/**
 * Displays [bitmap] fitted to the box with draggable [handles] (in bitmap pixels). While dragging,
 * a loupe in the corner shows a magnified view for precise placement.
 */
@Composable
private fun HandleCanvas(
    bitmap: Bitmap,
    handles: List<Pt>,
    onMove: (Int, Pt) -> Unit,
    onTapEmpty: ((Pt) -> Unit)? = null,
    drawOverlay: DrawScope.(Fit) -> Unit,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    val handlesState = rememberUpdatedState(handles)
    val moveState = rememberUpdatedState(onMove)
    val tapState = rememberUpdatedState(onTapEmpty)
    val density = LocalDensity.current
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pulse.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 200f)) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val scale = min(w / bitmap.width, h / bitmap.height)
        val fit = Fit(scale, Offset((w - bitmap.width * scale) / 2, (h - bitmap.height * scale) / 2))
        val grab = with(density) { 34.dp.toPx() }

        fun nearest(p: Offset): Int? {
            var best: Int? = null
            var bestD = grab
            handlesState.value.forEachIndexed { i, hp ->
                val d = (fit.toScreen(hp) - p).getDistance()
                if (d < bestD) { bestD = d; best = i }
            }
            return best
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(fit.scale, fit.offset) {
                    detectTapGestures { p ->
                        if (nearest(p) == null) tapState.value?.invoke(fit.toImage(p))
                    }
                }
                .pointerInput(fit.scale, fit.offset) {
                    detectDragGestures(
                        onDragStart = { p -> dragging = nearest(p); finger = p },
                        onDrag = { change, amount ->
                            val i = dragging ?: return@detectDragGestures
                            change.consume()
                            finger += amount
                            // Move by the drag delta so the handle doesn't jump under the finger.
                            val cur = fit.toScreen(handlesState.value[i]) + amount
                            moveState.value(i, fit.toImage(cur))
                        },
                        onDragEnd = { dragging = null },
                        onDragCancel = { dragging = null },
                    )
                },
        ) {
            drawImage(
                image,
                dstOffset = IntOffset(fit.offset.x.toInt(), fit.offset.y.toInt()),
                dstSize = IntSize((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt()),
                filterQuality = FilterQuality.Medium,
            )
            drawOverlay(fit)
            handles.forEachIndexed { i, hp ->
                val c = fit.toScreen(hp)
                val active = dragging == i
                val r = (if (active) 14.dp else 10.dp).toPx() * pulse.value
                drawCircle(Color.Black.copy(alpha = 0.35f), r + 3f, c)
                drawCircle(Color.White, r, c)
                drawCircle(Accent, r * 0.55f, c)
            }
            val di = dragging
            if (di != null && di < handles.size) {
                val target = fit.toScreen(handles[di])
                val lr = 64.dp.toPx()
                val center = if (target.x < size.width / 2 && target.y < size.height / 2) Offset(size.width - lr - 16.dp.toPx(), lr + 16.dp.toPx()) else Offset(lr + 16.dp.toPx(), lr + 16.dp.toPx())
                val zoom = 4f
                val src = handles[di]
                val half = (lr / scale / zoom).toInt().coerceAtLeast(4)
                val path = Path().apply { addOval(Rect(center, lr)) }
                drawCircle(Color.Black.copy(alpha = 0.4f), lr + 5f, center)
                clipPath(path) {
                    drawRect(Color.Black, Offset(center.x - lr, center.y - lr), Size(lr * 2, lr * 2))
                    drawImage(
                        image,
                        srcOffset = IntOffset((src.x.toInt() - half).coerceIn(0, bitmap.width - 1), (src.y.toInt() - half).coerceIn(0, bitmap.height - 1)),
                        srcSize = IntSize(min(half * 2, bitmap.width), min(half * 2, bitmap.height)),
                        dstOffset = IntOffset((center.x - lr).toInt(), (center.y - lr).toInt()),
                        dstSize = IntSize((lr * 2).toInt(), (lr * 2).toInt()),
                        filterQuality = FilterQuality.Low,
                    )
                }
                drawCircle(Color.White, lr, center, style = Stroke(3.dp.toPx()))
                drawLine(Accent, Offset(center.x - 12.dp.toPx(), center.y), Offset(center.x + 12.dp.toPx(), center.y), 2.dp.toPx())
                drawLine(Accent, Offset(center.x, center.y - 12.dp.toPx()), Offset(center.x, center.y + 12.dp.toPx()), 2.dp.toPx())
            }
        }
    }
}

@Composable
private fun ReferencePickerSheet(s: MeasureState, onDismiss: () -> Unit, onPick: (ReferenceObject) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var q by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Reference object", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(q, { q = it }, placeholder = { Text("Search") }, singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            val grouped = s.allReferences.filter { it.name.contains(q, true) || it.category.contains(q, true) }.groupBy { it.category }
            LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
                for ((cat, list) in grouped) {
                    item(key = "c-$cat") { SectionTitle(cat, Modifier.padding(top = 8.dp)) }
                    items(list, key = { it.id }) { r -> ReferenceRow(r, selected = r.id == s.reference.id) { onPick(r) } }
                }
            }
        }
    }
}

@Composable
private fun ReferenceRow(r: ReferenceObject, selected: Boolean, onDelete: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShapeIcon(r)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, style = MaterialTheme.typography.bodyLarge)
            Text(r.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Delete") }
    }
}

@Composable
private fun ShapeIcon(r: ReferenceObject) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(34.dp)) {
        when (r.shape) {
            RefShape.RECTANGLE -> {
                val aspect = (r.heightMm / r.widthMm).toFloat().coerceIn(0.3f, 3f)
                val w = if (aspect <= 1f) size.width else size.width / aspect
                val h = w * aspect
                drawRoundRect(color, Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h), androidx.compose.ui.geometry.CornerRadius(6f), style = Stroke(3f))
            }
            RefShape.CIRCLE -> drawCircle(color, size.minDimension / 2.2f, style = Stroke(3f))
            RefShape.LENGTH -> {
                drawLine(color, Offset(2f, size.height / 2), Offset(size.width - 2f, size.height / 2), 4f, StrokeCap.Round)
                drawLine(color, Offset(2f, size.height * 0.3f), Offset(2f, size.height * 0.7f), 3f)
                drawLine(color, Offset(size.width - 2f, size.height * 0.3f), Offset(size.width - 2f, size.height * 0.7f), 3f)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------- ruler

@Composable
private fun RulerTab(vm: MeasureViewModel, s: MeasureState) {
    val context = LocalContext.current
    val dm = context.resources.displayMetrics
    val factor = s.prefs.rulerScale.toFloat()
    val pxPerMm = dm.ydpi / 25.4f * factor
    var a by remember { mutableFloatStateOf(120f) }
    var b by remember { mutableFloatStateOf(520f) }
    var calibrating by remember { mutableStateOf(false) }
    val textMeasurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceContainerLow

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .background(surface)
                .pointerInput(Unit) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        // Move whichever marker is closer to the finger.
                        val y = change.position.y
                        if (kotlin.math.abs(y - a) < kotlin.math.abs(y - b)) a = (a + amount.y).coerceIn(0f, size.height.toFloat()) else b = (b + amount.y).coerceIn(0f, size.height.toFloat())
                    }
                },
        ) {
            val style = TextStyle(fontSize = 11.sp, color = onSurface)
            var mm = 0
            while (mm * pxPerMm < size.height) {
                val y = mm * pxPerMm
                val len = when {
                    mm % 10 == 0 -> 34.dp.toPx()
                    mm % 5 == 0 -> 22.dp.toPx()
                    else -> 12.dp.toPx()
                }
                drawLine(onSurface, Offset(0f, y), Offset(len, y), if (mm % 10 == 0) 2.5f else 1.5f)
                if (mm % 10 == 0 && mm > 0) drawText(textMeasurer, "${mm / 10}", Offset(len + 4.dp.toPx(), y - 7.dp.toPx()), style)
                mm++
            }
            // Inches on the right edge, in sixteenths.
            val pxPerIn = pxPerMm * 25.4f
            var k = 0
            while (k * pxPerIn / 16 < size.height) {
                val y = k * pxPerIn / 16
                val len = when {
                    k % 16 == 0 -> 34.dp.toPx()
                    k % 8 == 0 -> 26.dp.toPx()
                    k % 4 == 0 -> 18.dp.toPx()
                    else -> 10.dp.toPx()
                }
                drawLine(onSurface, Offset(size.width - len, y), Offset(size.width, y), if (k % 16 == 0) 2.5f else 1.2f)
                if (k % 16 == 0 && k > 0) {
                    val m = textMeasurer.measure("${k / 16}\"", style)
                    drawText(textMeasurer, "${k / 16}\"", Offset(size.width - len - m.size.width - 4.dp.toPx(), y - 7.dp.toPx()), style)
                }
                k++
            }
            for (y in listOf(a, b)) {
                drawLine(primary, Offset(0f, y), Offset(size.width, y), 3f)
                drawCircle(primary, 9.dp.toPx(), Offset(size.width / 2, y))
            }
            drawRect(primary.copy(alpha = 0.08f), Offset(0f, min(a, b)), Size(size.width, kotlin.math.abs(b - a)))
        }
        val distMm = kotlin.math.abs(b - a) / pxPerMm
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.align(Alignment.Center)) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.unit.format(distMm.toDouble()), style = MaterialTheme.typography.headlineMedium)
                Text(LengthUnit.IN.formatImperial(distMm.toDouble()), style = MaterialTheme.typography.bodyMedium)
                Text("Drag the lines", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FilledTonalButton(onClick = { calibrating = true }, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).navigationBarsPadding()) {
            Icon(Icons.Rounded.Straighten, null)
            Spacer(Modifier.width(6.dp))
            Text("Calibrate ruler")
        }
    }
    if (calibrating) {
        var f by remember { mutableFloatStateOf(factor) }
        AlertDialog(
            onDismissRequest = { calibrating = false },
            title = { Text("Calibrate with a bank card") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Hold a credit/debit card against the screen and adjust until the outline matches its width (85.6 mm).", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    val wPx = 85.6f * dm.xdpi / 25.4f * f
                    val wDp = with(LocalDensity.current) { wPx.toDp() }
                    Box(Modifier.width(wDp).height(wDp * (53.98f / 85.6f)).clip(RoundedCornerShape(10.dp)).background(primary.copy(alpha = 0.25f)))
                    Slider(f, { f = it }, valueRange = 0.8f..1.2f)
                }
            },
            confirmButton = { TextButton(onClick = { vm.setRulerScale(f.toDouble()); calibrating = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { vm.setRulerScale(1.0); calibrating = false }) { Text("Reset") } },
        )
    }
}

// ---------------------------------------------------------------------------------------- references

@Composable
private fun ReferencesTab(vm: MeasureViewModel, s: MeasureState) {
    var adding by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
            val grouped = s.allReferences.groupBy { it.category }
            for ((cat, list) in grouped) {
                item(key = "c-$cat") { SectionTitle(cat, Modifier.padding(top = 8.dp)) }
                items(list, key = { it.id }) { r ->
                    ReferenceRow(r, selected = r.id == s.reference.id, onDelete = if (r.custom) ({ vm.deleteReference(r.id) }) else null) { vm.chooseReference(r) }
                }
            }
        }
        Button(onClick = { adding = true }, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).navigationBarsPadding()) {
            Icon(Icons.Rounded.Add, null)
            Spacer(Modifier.width(6.dp))
            Text("Add your own reference")
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var shape by remember { mutableStateOf(RefShape.RECTANGLE) }
        var w by remember { mutableStateOf("") }
        var h by remember { mutableStateOf("") }
        val wv = w.toDoubleOrNull()
        val hv = h.toDoubleOrNull()
        val ok = name.isNotBlank() && wv != null && wv > 0 && (shape != RefShape.RECTANGLE || (hv != null && hv > 0))
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("New reference") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    SingleChoiceSegmentedButtonRow {
                        RefShape.entries.forEachIndexed { i, sh ->
                            SegmentedButton(shape == sh, { shape = sh }, SegmentedButtonDefaults.itemShape(i, RefShape.entries.size)) {
                                Text(sh.name.lowercase().replaceFirstChar { it.uppercase() })
                            }
                        }
                    }
                    OutlinedTextField(w, { w = it }, label = { Text(if (shape == RefShape.CIRCLE) "Diameter (mm)" else if (shape == RefShape.LENGTH) "Length (mm)" else "Width (mm)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    if (shape == RefShape.RECTANGLE) {
                        OutlinedTextField(h, { h = it }, label = { Text("Height (mm)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                }
            },
            confirmButton = { TextButton(enabled = ok, onClick = { vm.addCustomReference(name, shape, wv!!, hv ?: 0.0); adding = false }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------------------- history

@Composable
private fun HistoryTab(vm: MeasureViewModel, s: MeasureState, onOpened: () -> Unit) {
    if (s.prefs.sessions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(Icons.Rounded.History, "No saved measurements", "Measurements you save appear here with their photo, so you can revisit or adjust them later.")
        }
        return
    }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(s.prefs.sessions, key = { it.id }) { session ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().animateItem().bouncyClickable(pressedScale = 0.98f) { vm.openSession(session); onOpened() },
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Straighten, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${session.items.size} measurement${if (session.items.size == 1) "" else "s"}", style = MaterialTheme.typography.titleSmall)
                        Text("${session.refName} · ${fmt.format(Date(session.time))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.deleteSession(session) }) { Icon(Icons.Rounded.Delete, "Delete") }
                }
            }
        }
    }
}
