package com.vitmap.app.feature.scan

import android.Manifest
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Size
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Grid3x3
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vitmap.app.ui.components.PermissionGate
import com.vitmap.app.ui.theme.Accents
import com.vitmap.core.scan.DocumentDetector
import com.vitmap.core.scan.Pt
import com.vitmap.core.scan.Quad
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

enum class FlashMode(val label: String) { OFF("Off"), AUTO("Auto"), ON("On"), TORCH("Torch") }

/** Live detection, normalised to the upright frame (0..1). */
data class LiveDetection(val quad: Quad, val confidence: Double, val frameAspect: Float)

/** One analysed frame; [seq] makes every frame distinct so misses are counted too. */
data class DetectionFrame(val seq: Long, val detection: LiveDetection?)

class ScanCameraController {
    var camera by mutableStateOf<Camera?>(null)
        internal set
    internal var capture: ImageCapture? = null
    private var seq = 0L
    val frames = MutableStateFlow(DetectionFrame(0, null))

    internal fun publish(d: LiveDetection?) {
        frames.value = DetectionFrame(++seq, d)
    }
    /** Upright frame aspect (height / width), so the overlay matches the preview. */
    val frameAspect = MutableStateFlow(4f / 3f)

    suspend fun takePhoto(executor: java.util.concurrent.Executor): Bitmap {
        val ic = capture ?: error("Camera not ready")
        val result = CompletableDeferred<Bitmap>()
        ic.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val bmp = image.toBitmap()
                    val rot = image.imageInfo.rotationDegrees
                    val upright = if (rot == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                    result.complete(upright)
                } catch (e: Exception) {
                    result.completeExceptionally(e)
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                result.completeExceptionally(exception)
            }
        })
        return result.await()
    }
}

/** Colour matrix for the live contrast / brightness preview; mirrored by the page adjustments. */
fun previewMatrix(brightness: Float, contrast: Float): ColorMatrix {
    val c = 1f + contrast * (if (contrast > 0) 1.5f else 0.9f)
    val t = (0.5f * (1 - c) + brightness * 0.4f) * 255f
    return ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f))
}

@Composable
fun ScanCameraScreen(vm: ScanViewModel, docId: String?, onBack: () -> Unit) {
    PermissionGate(Manifest.permission.CAMERA, Icons.Rounded.AddPhotoAlternate, "Camera access", "The scanner uses the camera to find and capture pages. Photos stay on your device.") {
        ScanCameraContent(vm, docId, onBack)
    }
}

@Composable
private fun ScanCameraContent(vm: ScanViewModel, initialDocId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val prefs by vm.prefs.flow.collectAsState()
    val p = prefs ?: ScanPrefs()
    val controller = remember { ScanCameraController() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    var docId by remember { mutableStateOf(initialDocId) }
    val captured = remember { mutableStateListOf<Bitmap>() }
    var flash by remember { mutableStateOf(FlashMode.OFF) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(0f) }
    var exposure by remember { mutableIntStateOf(0) }
    var adjusting by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }
    val flashAnim = remember { Animatable(0f) }

    // Smoothed outline and stability tracking for auto capture.
    var live by remember { mutableStateOf<Quad?>(null) }
    var stable by remember { mutableIntStateOf(0) }
    var misses by remember { mutableIntStateOf(0) }
    var cooldownUntil by remember { mutableStateOf(0L) }
    var lastCaptured by remember { mutableStateOf<Quad?>(null) }
    val frame by controller.frames.collectAsState()
    val aspect by controller.frameAspect.collectAsState()
    val needed = 9

    fun capture() {
        if (capturing) return
        capturing = true
        scope.launch {
            try {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                launch { flashAnim.snapTo(1f); flashAnim.animateTo(0f, tween(450)) }
                val photo = controller.takePhoto(executor)
                val doc = vm.ensureDoc(docId)
                docId = doc.id
                val pageId = vm.addCapture(doc.id, photo, live, brightness, contrast)
                captured += PageRendererThumb.thumb(photo)
                lastCaptured = live
                cooldownUntil = System.currentTimeMillis() + 1500
                stable = 0
                if (!p.batch) {
                    vm.replace(DocScreen.PageEdit(doc.id, pageId, fromCamera = true))
                }
            } catch (e: Exception) {
                vm.toast("Capture failed: ${e.localizedMessage}")
            } finally {
                capturing = false
            }
        }
    }

    LaunchedEffect(frame) {
        val d = frame.detection
        if (d == null || d.confidence < 0.5) {
            misses++
            if (misses > 4) {
                live = null
                stable = 0
            }
            return@LaunchedEffect
        }
        misses = 0
        val prev = live
        live = if (prev == null) d.quad else prev.lerp(d.quad, 0.4)
        val shift = prev?.maxShift(d.quad) ?: 1.0
        stable = if (shift < 0.018) stable + 1 else 0
        val now = System.currentTimeMillis()
        // Don't re-shoot the same page: wait until the outline changes noticeably.
        val samePage = lastCaptured?.let { it.maxShift(d.quad) < 0.08 } == true && now < cooldownUntil + 4000
        if (p.autoCapture && stable >= needed && !capturing && now > cooldownUntil && !samePage) capture()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val doc = vm.ensureDoc(docId)
                docId = doc.id
                vm.importImages(doc.id, uris, open = false)
                vm.replace(DocScreen.Editor(doc.id))
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = 64.dp, bottom = 190.dp), contentAlignment = Alignment.Center) {
            // FIT_CENTER preview: compute the frame rectangle so the outline lines up.
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            val frameW = min(boxW, boxH / aspect)
            val frameH = frameW * aspect
            val left = (boxW - frameW) / 2
            val top = (boxH - frameH) / 2
            CameraPreview(controller, executor, flash, brightness, contrast, exposure, Modifier.fillMaxSize())
            val progress by animateFloatAsState(if (live != null) (stable.toFloat() / needed).coerceIn(0f, 1f) else 0f, tween(180), label = "stable")
            val accent = Accents.Measure.start
            Canvas(Modifier.fillMaxSize()) {
                if (p.grid) {
                    for (i in 1..2) {
                        drawLine(Color.White.copy(alpha = 0.25f), Offset(left + frameW * i / 3, top), Offset(left + frameW * i / 3, top + frameH), 1f)
                        drawLine(Color.White.copy(alpha = 0.25f), Offset(left, top + frameH * i / 3), Offset(left + frameW, top + frameH * i / 3), 1f)
                    }
                }
                val q = live ?: return@Canvas
                fun pt(pp: Pt) = Offset(left + pp.x.toFloat() * frameW, top + pp.y.toFloat() * frameH)
                val path = Path().apply {
                    moveTo(pt(q.tl).x, pt(q.tl).y)
                    lineTo(pt(q.tr).x, pt(q.tr).y)
                    lineTo(pt(q.br).x, pt(q.br).y)
                    lineTo(pt(q.bl).x, pt(q.bl).y)
                    close()
                }
                val color = if (progress >= 1f) Accents.Measure.end else accent
                drawPath(path, color.copy(alpha = 0.18f + 0.14f * progress))
                drawPath(path, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                for (c in q.corners) drawCircle(Color.White, 6.dp.toPx(), pt(c))
                for (c in q.corners) drawCircle(color, 4.dp.toPx(), pt(c))
            }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = flashAnim.value }.background(Color.White))
        }

        // Top controls
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { flash = FlashMode.entries[(flash.ordinal + 1) % FlashMode.entries.size] }) {
                Icon(
                    when (flash) {
                        FlashMode.OFF -> Icons.Rounded.FlashOff
                        FlashMode.AUTO -> Icons.Rounded.FlashAuto
                        FlashMode.ON -> Icons.Rounded.FlashOn
                        FlashMode.TORCH -> Icons.Rounded.FlashlightOn
                    },
                    "Flash ${flash.label}",
                    tint = Color.White,
                )
            }
            IconButton(onClick = { vm.setPrefs { it.copy(grid = !it.grid) } }) {
                Icon(Icons.Rounded.Grid3x3, "Grid", tint = if (p.grid) Accents.Measure.start else Color.White)
            }
            ModeToggle("Auto", p.autoCapture) { vm.setPrefs { it.copy(autoCapture = !it.autoCapture) } }
            Spacer(Modifier.width(6.dp))
            ModeToggle("Batch", p.batch) { vm.setPrefs { it.copy(batch = !it.batch) } }
        }

        // Status hint
        val hint = when {
            capturing -> "Capturing…"
            live == null -> "Looking for a page…"
            p.autoCapture && stable in 1 until needed -> "Hold steady…"
            p.autoCapture -> "Page found"
            else -> "Page found, tap to capture"
        }
        AnimatedContent(
            hint,
            transitionSpec = { (fadeIn() + slideInVertically { -it / 2 }) togetherWith (fadeOut() + slideOutVertically { it / 2 }) },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 60.dp),
            label = "hint",
        ) { h ->
            Text(
                h,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        // Bottom controls
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVisibility(adjusting != null) {
                val exposureRange = controller.camera?.cameraInfo?.exposureState?.exposureCompensationRange
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (adjusting) {
                        "contrast" -> Slider(contrast, { contrast = it }, valueRange = -1f..1f, modifier = Modifier.weight(1f), colors = sliderColors())
                        "brightness" -> Slider(brightness, { brightness = it }, valueRange = -1f..1f, modifier = Modifier.weight(1f), colors = sliderColors())
                        "exposure" -> if (exposureRange != null && exposureRange.upper > exposureRange.lower) {
                            Slider(exposure.toFloat(), { exposure = it.toInt() }, valueRange = exposureRange.lower.toFloat()..exposureRange.upper.toFloat(), steps = max(0, exposureRange.upper - exposureRange.lower - 1), modifier = Modifier.weight(1f), colors = sliderColors())
                        } else {
                            Text("Exposure control isn't supported by this camera", color = Color.White, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdjustChip("Contrast", Icons.Rounded.Contrast, adjusting == "contrast", contrast != 0f) { adjusting = if (adjusting == "contrast") null else "contrast" }
                AdjustChip("Brightness", Icons.Rounded.BrightnessMedium, adjusting == "brightness", brightness != 0f) { adjusting = if (adjusting == "brightness") null else "brightness" }
                AdjustChip("Exposure", Icons.Rounded.Exposure, adjusting == "exposure", exposure != 0) { adjusting = if (adjusting == "exposure") null else "exposure" }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f))) {
                    Icon(Icons.Rounded.AddPhotoAlternate, "Import photos", tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Shutter(progress = if (p.autoCapture && live != null) (stable.toFloat() / needed).coerceIn(0f, 1f) else 0f, busy = capturing) { capture() }
                Spacer(Modifier.weight(1f))
                ThumbStack(captured, onDone = { docId?.let { vm.replace(DocScreen.Editor(it)) } })
            }
        }
    }
}

/** Tiny thumbnails for the capture stack. */
private object PageRendererThumb {
    fun thumb(b: Bitmap): Bitmap = PageRenderer.scaleDown(b, 240)
}

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accents.Measure.start, inactiveTrackColor = Color.White.copy(alpha = 0.3f))

@Composable
private fun ModeToggle(label: String, on: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = on,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = Color.White,
            selectedContainerColor = Accents.Measure.start,
            selectedLabelColor = Color.White,
            containerColor = Color.White.copy(alpha = 0.12f),
        ),
        border = null,
    )
}

@Composable
private fun AdjustChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, changed: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(if (changed) "$label •" else label) },
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = Color.White,
            iconColor = Color.White,
            selectedContainerColor = Color.White,
            selectedLabelColor = Color.Black,
            selectedLeadingIconColor = Color.Black,
            containerColor = Color.White.copy(alpha = 0.12f),
        ),
        border = null,
    )
}

@Composable
private fun Shutter(progress: Float, busy: Boolean, onClick: () -> Unit) {
    val press = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .size(84.dp)
            .scale(press.value)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    scope.launch { press.animateTo(0.88f, spring()) }
                    tryAwaitRelease()
                    scope.launch { press.animateTo(1f, spring(dampingRatio = 0.4f)) }
                }, onTap = { onClick() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White.copy(alpha = 0.3f), size.minDimension / 2, style = Stroke(5.dp.toPx()))
            if (progress > 0f) {
                drawArc(Accents.Measure.start, -90f, 360f * progress, false, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Box(Modifier.size(64.dp).clip(CircleShape).background(if (busy) Color.White.copy(alpha = 0.6f) else Color.White))
    }
}

@Composable
private fun ThumbStack(captured: List<Bitmap>, onDone: () -> Unit) {
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        AnimatedContent(captured.size, transitionSpec = { scaleIn(spring(dampingRatio = 0.5f), initialScale = 1.4f) + fadeIn() togetherWith fadeOut() }, label = "stack") { n ->
            if (n == 0) {
                Spacer(Modifier.size(56.dp))
            } else {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).border(2.dp, Color.White, RoundedCornerShape(12.dp)).pointerInput(Unit) { detectTapGestures { onDone() } },
                ) {
                    Image(captured[n - 1].asImageBitmap(), "Captured pages", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.align(Alignment.BottomEnd).padding(3.dp).clip(CircleShape).background(Accents.Measure.start).padding(horizontal = 6.dp, vertical = 1.dp)) {
                        Text("$n", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (captured.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp).size(24.dp).clip(CircleShape).background(Color.White).pointerInput(Unit) { detectTapGestures { onDone() } },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Check, "Done", tint = Color.Black, modifier = Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun CameraPreview(
    controller: ScanCameraController,
    executor: java.util.concurrent.ExecutorService,
    flash: FlashMode,
    brightness: Float,
    contrast: Float,
    exposure: Int,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            // TextureView-backed, so the layer paint below can tint the live preview.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    val analysisBuffer = remember { arrayOfNulls<ByteArray>(1) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            val ratio = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
            val preview = Preview.Builder().setResolutionSelector(ratio).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { image -> analyze(image, controller, analysisBuffer) }
            val capture = ImageCapture.Builder()
                .setResolutionSelector(ratio)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            controller.capture = capture
            runCatching {
                p.unbindAll()
                controller.camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            controller.camera = null
            controller.capture = null
        }
    }

    LaunchedEffect(flash, controller.camera) {
        val cam = controller.camera ?: return@LaunchedEffect
        controller.capture?.flashMode = when (flash) {
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        if (cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(flash == FlashMode.TORCH)
    }
    LaunchedEffect(exposure, controller.camera) {
        controller.camera?.let { cam ->
            if (cam.cameraInfo.exposureState.isExposureCompensationSupported) cam.cameraControl.setExposureCompensationIndex(exposure)
        }
    }
    // Live contrast / brightness: a colour filter on the preview's hardware layer.
    LaunchedEffect(brightness, contrast) {
        if (brightness == 0f && contrast == 0f) {
            previewView.setLayerType(View.LAYER_TYPE_NONE, null)
        } else {
            previewView.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(previewMatrix(brightness, contrast)) })
        }
    }

    Box(modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { pos ->
                    val cam = controller.camera ?: return@detectTapGestures
                    val point = previewView.meteringPointFactory.createPoint(pos.x, pos.y)
                    cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                }
            },
        )
    }
}

/** Runs the page detector on the luma plane and publishes an upright, normalised outline. */
private fun analyze(image: ImageProxy, controller: ScanCameraController, buffer: Array<ByteArray?>) {
    try {
        val plane = image.planes[0]
        val buf = plane.buffer
        val size = buf.remaining()
        val bytes = buffer[0]?.takeIf { it.size >= size } ?: ByteArray(size).also { buffer[0] = it }
        buf.get(bytes, 0, size)
        val w = image.width
        val h = image.height
        val rot = image.imageInfo.rotationDegrees
        val uprightW = if (rot % 180 == 0) w else h
        val uprightH = if (rot % 180 == 0) h else w
        controller.frameAspect.value = uprightH.toFloat() / uprightW
        val det = DocumentDetector.detect(bytes, w, h, plane.rowStride)
        if (det == null) {
            controller.publish(null)
            return
        }
        fun up(p: Pt): Pt {
            val (x, y) = when (rot) {
                90 -> (h - p.y) to p.x
                180 -> (w - p.x) to (h - p.y)
                270 -> p.y to (w - p.x)
                else -> p.x to p.y
            }
            return Pt(x / uprightW, y / uprightH)
        }
        val q = Quad.ordered(det.quad.corners.map(::up))
        controller.publish(LiveDetection(q, det.confidence, uprightH.toFloat() / uprightW))
    } catch (_: Exception) {
        controller.publish(null)
    } finally {
        image.close()
    }
}
