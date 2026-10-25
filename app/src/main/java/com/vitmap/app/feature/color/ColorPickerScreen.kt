package com.vitmap.app.feature.color

import android.Manifest
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitmap.app.ui.components.CameraView
import com.vitmap.app.ui.components.CopyRow
import com.vitmap.app.ui.components.EmptyState
import com.vitmap.app.ui.components.PermissionGate
import com.vitmap.app.ui.components.SectionTitle
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.components.bouncyClickable
import com.vitmap.app.ui.components.loadBitmap
import com.vitmap.app.ui.components.readable
import com.vitmap.app.ui.components.shareText
import com.vitmap.app.ui.theme.Accents
import com.vitmap.app.ui.theme.MonoStyle
import com.vitmap.core.color.ColorMath
import com.vitmap.core.color.Harmony
import com.vitmap.core.color.PaletteExtractor
import com.vitmap.core.color.Rgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

fun Rgb.toColor(): Color = Color(r, g, b, (alpha * 255).roundToInt())

@Composable
fun ColorPickerScreen(onBack: () -> Unit) {
    val vm: ColorViewModel = viewModel()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Camera", "Photo", "Compare", "Saved")
    ToolScaffold(title = "Color Picker", subtitle = tabs[tab], accent = Accents.ColorPicker, onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            AnimatedContent(tab, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "colorTab") { t ->
                when (t) {
                    0 -> LiveTab(vm) { tab = 2 }
                    1 -> PhotoTab(vm) { tab = 2 }
                    2 -> CompareTab(vm)
                    else -> SavedTab(vm) { tab = 2 }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------- live

@Composable
private fun LiveTab(vm: ColorViewModel, onCompare: () -> Unit) {
    val live by vm.live.collectAsStateWithLifecycle()
    val frozen by vm.frozen.collectAsStateWithLifecycle()
    val radius by vm.sampleRadius.collectAsStateWithLifecycle()
    var torch by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    PermissionGate(
        Manifest.permission.CAMERA,
        Icons.Rounded.PhotoCamera,
        "Camera access",
        "Point your camera at anything to identify its colour in real time. Nothing leaves your device.",
    ) {
        Box(Modifier.fillMaxSize()) {
            CameraView(Modifier.fillMaxSize(), analyzer = vm.analyzer, torch = torch, paused = frozen)
            Reticle(live?.toColor() ?: Color.Gray, frozen, radius, Modifier.align(Alignment.Center).offset(y = (-90).dp))
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp).navigationBarsPadding()) {
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(onClick = { torch = !torch }) { Icon(if (torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff, "Torch") }
                    SampleSizeButton(radius) { vm.sampleRadius.value = it }
                }
                Spacer(Modifier.height(8.dp))
                live?.let { c ->
                    ColorCard(
                        rgb = c,
                        name = vm.nameOf(c),
                        actions = {
                            FilledTonalButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); vm.toggleFreeze() }) {
                                Icon(if (frozen) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null)
                                Spacer(Modifier.width(4.dp))
                                Text(if (frozen) "Resume" else "Freeze")
                            }
                            SaveButton { vm.save(c) }
                            CompareButtons(vm, c, onCompare)
                        },
                    )
                } ?: Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f), modifier = Modifier.fillMaxWidth()) {
                    Text("Starting camera…", Modifier.padding(20.dp))
                }
            }
        }
    }
}

@Composable
private fun Reticle(color: Color, frozen: Boolean, radius: Int, modifier: Modifier) {
    val animColor by animateColorAsState(color, tween(120), label = "reticle")
    val pulse = rememberInfiniteTransition(label = "reticlePulse")
    val breathe by pulse.animateFloat(1f, 1.08f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "breathe")
    val freezeScale by animateFloatAsState(if (frozen) 1.25f else 1f, spring(dampingRatio = 0.45f, stiffness = 380f), label = "freeze")
    val inner = (6 + radius * 1.5f).dp
    Canvas(modifier.size(140.dp).graphicsLayer { val s = freezeScale * (if (frozen) 1f else breathe); scaleX = s; scaleY = s }) {
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Color.Black.copy(alpha = 0.25f), size.minDimension * 0.36f, c, style = Stroke(8.dp.toPx()))
        drawCircle(animColor, size.minDimension * 0.36f, c, style = Stroke(6.dp.toPx()))
        drawCircle(Color.White, size.minDimension * 0.36f + 4.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
        val gap = inner.toPx() + 4.dp.toPx()
        val len = 16.dp.toPx()
        for ((dx, dy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
            drawLine(Color.White, Offset(c.x + dx * gap, c.y + dy * gap), Offset(c.x + dx * (gap + len), c.y + dy * (gap + len)), 2.dp.toPx())
        }
        drawCircle(Color.White, inner.toPx(), c, style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun SampleSizeButton(radius: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    FilledTonalIconButton(onClick = { open = true }) { Icon(Icons.Rounded.Tune, "Sample size") }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Sample size") },
            text = {
                Column {
                    Text("Average the colour over a small area to reduce noise.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((r, label) in listOf(0 to "1 px", 2 to "5 px", 4 to "9 px", 8 to "17 px")) {
                            FilterChip(selected = radius == r, onClick = { onChange(r) }, label = { Text(label) })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } },
        )
    }
}

@Composable
private fun SaveButton(onSave: () -> Unit) {
    val pop = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    FilledTonalIconButton(onClick = {
        onSave()
        scope.launch {
            pop.snapTo(1.5f)
            pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 500f))
        }
    }) {
        Icon(Icons.Rounded.Favorite, "Save", Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }, tint = Color(0xFFFF4D6D))
    }
}

@Composable
private fun CompareButtons(vm: ColorViewModel, c: Rgb, onCompare: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        FilledTonalIconButton(onClick = { menu = true }) { Icon(Icons.Rounded.Compare, "Compare") }
        androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
            androidx.compose.material3.DropdownMenuItem(text = { Text("Compare as A") }, onClick = { vm.compareA.value = c; menu = false; onCompare() })
            androidx.compose.material3.DropdownMenuItem(text = { Text("Compare as B") }, onClick = { vm.compareB.value = c; menu = false; onCompare() })
        }
    }
}

/** Swatch, name and every colour format, each tap-to-copy. */
@Composable
fun ColorCard(rgb: Rgb, name: String, modifier: Modifier = Modifier, expandedByDefault: Boolean = false, actions: @Composable () -> Unit = {}) {
    var expanded by rememberSaveable { mutableStateOf(expandedByDefault) }
    val color by animateColorAsState(rgb.toColor(), tween(150), label = "card")
    Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.bouncyClickable(pressedScale = 0.98f, haptic = false) { expanded = !expanded }) {
                Box(Modifier.size(58.dp).shadow(6.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(color))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    AnimatedContent(name, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) }, label = "name") { n ->
                        Text(n, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                    Text(ColorMath.hex(rgb), style = MonoStyle.copy(fontSize = MaterialTheme.typography.titleSmall.fontSize, fontWeight = FontWeight.Bold))
                }
                Text(if (expanded) "Less" else "All formats", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
            AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(top = 6.dp)) { ColorValues(rgb) }
            }
        }
    }
}

@Composable
fun ColorValues(rgb: Rgb) {
    CopyRow("HEX", ColorMath.hex(rgb))
    CopyRow("RGB", ColorMath.formatRgb(rgb))
    CopyRow("CMYK", ColorMath.formatCmyk(rgb))
    CopyRow("HSLA", ColorMath.formatHsl(rgb))
    CopyRow("HSV", ColorMath.formatHsv(rgb))
    CopyRow("HWB", ColorMath.formatHwb(rgb))
    CopyRow("OKLCH", ColorMath.formatOklch(rgb))
    CopyRow("LAB", ColorMath.formatLab(rgb))
    CopyRow("Android", "0x" + ColorMath.hex(rgb.copy(alpha = 1.0)).removePrefix("#").let { "FF$it" })
}

// ---------------------------------------------------------------------------------------- photo

@Composable
private fun PhotoTab(vm: ColorViewModel, onCompare: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var picked by remember { mutableStateOf<Rgb?>(null) }
    var palette by remember { mutableStateOf<List<Pair<Rgb, Double>>>(emptyList()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val b = loadBitmap(context, uri)?.readable()
            bitmap = b
            picked = null
            palette = emptyList()
            if (b != null) {
                palette = withContext(Dispatchers.Default) {
                    val small = Bitmap.createScaledBitmap(b, 96, (96f * b.height / b.width).roundToInt().coerceAtLeast(1), true)
                    val px = IntArray(small.width * small.height)
                    small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
                    PaletteExtractor.extract(px, 6)
                }
            }
        }
    }
    val bmp = bitmap
    if (bmp == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(Icons.Rounded.AddPhotoAlternate, "Pick a photo", "Choose any image, then drag the loupe to sample exact pixels. A palette is extracted automatically.") {
                Button(onClick = { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Choose photo") }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LoupeImage(bmp, onPick = { picked = it })
        if (palette.isNotEmpty()) {
            SectionTitle("Palette")
            Row(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp))) {
                for ((c, share) in palette) {
                    Box(
                        Modifier
                            .weight(share.toFloat().coerceAtLeast(0.05f))
                            .fillMaxSize()
                            .background(c.toColor())
                            .bouncyClickable(pressedScale = 0.92f) { picked = c },
                    )
                }
            }
        }
        picked?.let { c ->
            ColorCard(c, vm.nameOf(c), expandedByDefault = true, actions = {
                SaveButton { vm.save(c) }
                CompareButtons(vm, c, onCompare)
            })
        } ?: Text("Touch the photo to pick a colour", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Icon(Icons.Rounded.AddPhotoAlternate, null)
            Spacer(Modifier.width(6.dp))
            Text("Another photo")
        }
    }
}

/** Shows the bitmap; touching it shows a magnifying loupe and picks the pixel under the crosshair. */
@Composable
private fun LoupeImage(bmp: Bitmap, onPick: (Rgb) -> Unit) {
    val image = remember(bmp) { bmp.asImageBitmap() }
    var touch by remember { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(RoundedCornerShape(20.dp))) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        fun pixelAt(p: Offset): Rgb {
            val x = (p.x / w * bmp.width).toInt().coerceIn(0, bmp.width - 1)
            val y = (p.y / h * bmp.height).toInt().coerceIn(0, bmp.height - 1)
            return Rgb.fromArgb(bmp.getPixel(x, y) or (0xFF shl 24))
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(bmp) {
                    detectTapGestures { p -> touch = p; onPick(pixelAt(p)) }
                }
                .pointerInput(bmp) {
                    detectDragGestures(
                        onDragStart = { p -> touch = p; onPick(pixelAt(p)) },
                        onDrag = { change, _ ->
                            change.consume()
                            val p = Offset(change.position.x.coerceIn(0f, w - 1), change.position.y.coerceIn(0f, h - 1))
                            touch = p
                            onPick(pixelAt(p))
                        },
                    )
                },
        ) {
            drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Medium)
            val t = touch ?: return@Canvas
            val loupe = with(density) { 54.dp.toPx() }
            val zoomPx = 9
            val center = Offset(t.x, (t.y - loupe * 1.6f).let { if (it < loupe) t.y + loupe * 1.6f else it })
            val srcX = (t.x / w * bmp.width).toInt()
            val srcY = (t.y / h * bmp.height).toInt()
            val half = loupe.toInt() / zoomPx
            val path = androidx.compose.ui.graphics.Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(center, loupe))
            }
            drawCircle(Color.Black.copy(alpha = 0.3f), loupe + 6f, center.copy(y = center.y + 4f))
            clipPath(path) {
                drawImage(
                    image,
                    srcOffset = IntOffset((srcX - half).coerceIn(0, bmp.width - 1), (srcY - half).coerceIn(0, bmp.height - 1)),
                    srcSize = IntSize(min(half * 2, bmp.width), min(half * 2, bmp.height)),
                    dstOffset = IntOffset((center.x - loupe).toInt(), (center.y - loupe).toInt()),
                    dstSize = IntSize((loupe * 2).toInt(), (loupe * 2).toInt()),
                    filterQuality = FilterQuality.None,
                )
            }
            drawCircle(Color.White, loupe, center, style = Stroke(4f))
            drawRect(Color.White, Offset(center.x - zoomPx / 2f, center.y - zoomPx / 2f), Size(zoomPx.toFloat(), zoomPx.toFloat()), style = Stroke(2f))
            drawCircle(Color.White, 10f, t, style = Stroke(3f))
        }
    }
}

// ---------------------------------------------------------------------------------------- compare

@Composable
private fun CompareTab(vm: ColorViewModel) {
    val a by vm.compareA.collectAsStateWithLifecycle()
    val b by vm.compareB.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Boolean?>(null) }
    val saved by vm.saved.collectAsStateWithLifecycle()
    var swaps by remember { mutableIntStateOf(0) }
    val spin by animateFloatAsState(swaps * 180f, spring(dampingRatio = 0.6f, stiffness = 250f), label = "swap")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box {
            Row(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(28.dp))) {
                val ca by animateColorAsState(a.toColor(), label = "a")
                val cb by animateColorAsState(b.toColor(), label = "b")
                for ((c, rgb, isA) in listOf(Triple(ca, a, true), Triple(cb, b, false))) {
                    Box(
                        Modifier.weight(1f).fillMaxSize().background(c).bouncyClickable(pressedScale = 0.97f) { editing = isA },
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        val on = ColorMath.onColor(rgb).toColor()
                        Column(Modifier.padding(14.dp)) {
                            Text(if (isA) "A" else "B", style = MaterialTheme.typography.headlineMedium, color = on)
                            Text(ColorMath.hex(rgb), style = MonoStyle, color = on)
                            Text(vm.nameOf(rgb), style = MaterialTheme.typography.labelMedium, color = on.copy(alpha = 0.85f), maxLines = 1)
                        }
                    }
                }
            }
            FilledTonalIconButton(
                onClick = { swaps++; val t = vm.compareA.value; vm.compareA.value = vm.compareB.value; vm.compareB.value = t },
                modifier = Modifier.align(Alignment.Center).graphicsLayer { rotationZ = spin },
            ) { Icon(Icons.Rounded.SwapHoriz, "Swap") }
        }
        Text("Tap a side to change it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val de = ColorMath.deltaE2000(a, b)
        val ratio = ColorMath.contrastRatio(a, b)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("ΔE 2000", "%.2f".format(de), ColorMath.describeDeltaE(de), Modifier.weight(1f))
            MetricCard("Contrast", "%.2f:1".format(ratio), when {
                ratio >= 7 -> "AAA for all text"
                ratio >= 4.5 -> "AA normal · AAA large"
                ratio >= 3 -> "AA large text only"
                else -> "Fails WCAG"
            }, Modifier.weight(1f))
        }
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((fg, bg) in listOf(a to b, b to a)) {
                    Box(Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(16.dp)).background(bg.toColor()), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Aa", style = MaterialTheme.typography.headlineMedium, color = fg.toColor())
                            Text("Sample text", style = MaterialTheme.typography.bodySmall, color = fg.toColor())
                        }
                    }
                }
            }
        }
        SectionTitle("Channel differences")
        val ha = ColorMath.toHsl(a)
        val hb = ColorMath.toHsl(b)
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DiffBar("Red", a.r, b.r, 255, Color(0xFFEF4444))
                DiffBar("Green", a.g, b.g, 255, Color(0xFF22C55E))
                DiffBar("Blue", a.b, b.b, 255, Color(0xFF3B82F6))
                DiffBar("Hue", ha.h.roundToInt(), hb.h.roundToInt(), 360, MaterialTheme.colorScheme.primary)
                DiffBar("Saturation", ha.s.roundToInt(), hb.s.roundToInt(), 100, MaterialTheme.colorScheme.secondary)
                DiffBar("Lightness", ha.l.roundToInt(), hb.l.roundToInt(), 100, MaterialTheme.colorScheme.tertiary)
            }
        }
        SectionTitle("Blend")
        Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp))) {
            for (i in 0..8) {
                val m = ColorMath.mixOklab(a, b, i / 8.0)
                Box(Modifier.weight(1f).fillMaxSize().background(m.toColor()))
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    editing?.let { isA ->
        ColorEditSheet(
            initial = if (isA) a else b,
            saved = saved,
            onDismiss = { editing = null },
            onPick = { c -> if (isA) vm.compareA.value = c else vm.compareB.value = c; editing = null },
        )
    }
}

@Composable
private fun MetricCard(title: String, value: String, note: String, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AnimatedContent(value, label = "metric") { v -> Text(v, style = MaterialTheme.typography.headlineSmall) }
            Text(note, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DiffBar(label: String, a: Int, b: Int, max: Int, color: Color) {
    val fa by animateFloatAsState(a / max.toFloat(), label = "fa")
    val fb by animateFloatAsState(b / max.toFloat(), label = "fb")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(78.dp))
        Canvas(Modifier.weight(1f).height(18.dp)) {
            val h = size.height / 2 - 2
            drawRoundRect(color.copy(alpha = 0.15f), size = Size(size.width, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f))
            drawRoundRect(color, size = Size(size.width * fa, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f))
            drawRoundRect(color.copy(alpha = 0.55f), topLeft = Offset(0f, h + 4), size = Size(size.width * fb, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f))
        }
        Text("${if (b - a >= 0) "+" else ""}${b - a}", style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize), modifier = Modifier.width(52.dp).padding(start = 8.dp))
    }
}

@Composable
private fun ColorEditSheet(initial: Rgb, saved: List<SavedColor>, onDismiss: () -> Unit, onPick: (Rgb) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf(ColorMath.hex(initial)) }
    val parsed = ColorMath.parse(text)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Set colour", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text("HEX, rgb(), hsl(), hwb(), cmyk(), oklch() or a name") },
                isError = parsed == null,
                leadingIcon = { Box(Modifier.size(24.dp).clip(CircleShape).background(parsed?.toColor() ?: Color.Transparent).border(1.dp, Color.Gray, CircleShape)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(enabled = parsed != null, onClick = { parsed?.let(onPick) }, modifier = Modifier.fillMaxWidth()) { Text("Use colour") }
            if (saved.isNotEmpty()) {
                SectionTitle("From saved")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in saved.take(7)) {
                        Box(Modifier.size(40.dp).bouncyClickable(shape = CircleShape) { onPick(c.rgb) }.background(c.rgb.toColor()))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------- saved

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedTab(vm: ColorViewModel, onCompare: () -> Unit) {
    val saved by vm.saved.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var detail by remember { mutableStateOf<SavedColor?>(null) }
    if (saved.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(Icons.Rounded.Palette, "No saved colours yet", "Tap the heart on any colour you find to keep it here.")
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${saved.size} colours", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { shareText(context, saved.joinToString("\n") { "${it.name}: ${ColorMath.hex(it.rgb)}" }, "Share palette") }) {
                Icon(Icons.Rounded.Share, "Share palette")
            }
            IconButton(onClick = vm::clearAll) { Icon(Icons.Rounded.Delete, "Clear all") }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(saved, key = { it.time }) { c ->
                Column(
                    Modifier
                        .animateItem()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .combinedClickable(onClick = { detail = c }, onLongClick = { vm.delete(c) }),
                ) {
                    Box(Modifier.fillMaxWidth().height(76.dp).background(c.rgb.toColor()))
                    Column(Modifier.padding(8.dp)) {
                        Text(c.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        Text(ColorMath.hex(c.rgb), style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize))
                    }
                }
            }
        }
    }
    detail?.let { c ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { detail = null }, sheetState = sheet) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ColorCard(c.rgb, c.name, expandedByDefault = true, actions = {
                    CompareButtons(vm, c.rgb) { detail = null; onCompare() }
                    FilledTonalIconButton(onClick = { vm.delete(c); detail = null }) { Icon(Icons.Rounded.Delete, "Delete") }
                })
                for (h in Harmony.entries) {
                    SectionTitle(h.title)
                    SwatchStrip(ColorMath.harmony(c.rgb, h))
                }
                SectionTitle("Tints")
                SwatchStrip(ColorMath.tints(c.rgb, 6))
                SectionTitle("Shades")
                SwatchStrip(ColorMath.shades(c.rgb, 6))
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun SwatchStrip(colors: List<Rgb>) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))) {
        colors.forEachIndexed { i, c ->
            val appear = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(i * 40L)
                appear.animateTo(1f, spring(dampingRatio = 0.7f))
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .graphicsLayer { scaleY = appear.value }
                    .background(c.toColor())
                    .bouncyClickable(pressedScale = 0.9f) { com.vitmap.app.ui.components.copyToClipboard(context, ColorMath.hex(c), ColorMath.hex(c)) },
                contentAlignment = Alignment.Center,
            ) {
                Text(ColorMath.hex(c).removePrefix("#"), style = MaterialTheme.typography.labelSmall, color = ColorMath.onColor(c).toColor().copy(alpha = 0.8f))
            }
        }
    }
}
