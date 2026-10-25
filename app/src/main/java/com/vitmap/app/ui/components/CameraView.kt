package com.vitmap.app.ui.components

import android.graphics.Bitmap
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** Handle for grabbing the current preview frame. */
class CameraHandle {
    internal var view: PreviewView? = null
    internal var camera: Camera? = null

    /** The frame exactly as shown on screen (cropped like the preview). */
    fun snapshot(): Bitmap? = view?.bitmap
}

/**
 * Back-camera preview with optional frame analysis, tap to focus and pinch to zoom.
 */
@Composable
fun CameraView(
    modifier: Modifier = Modifier,
    handle: CameraHandle = remember { CameraHandle() },
    analyzer: ImageAnalysis.Analyzer? = null,
    rgbaAnalysis: Boolean = true,
    torch: Boolean = false,
    paused: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val analyzerState = rememberUpdatedState(analyzer)
    val pausedState = rememberUpdatedState(paused)

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val useCases = ArrayList<UseCase>()
            useCases += preview
            if (analyzer != null) {
                val builder = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                if (rgbaAnalysis) builder.setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                val analysis = builder.build()
                analysis.setAnalyzer(analysisExecutor) { image ->
                    val a = analyzerState.value
                    if (a == null || pausedState.value) image.close() else a.analyze(image)
                }
                useCases += analysis
            }
            runCatching {
                p.unbindAll()
                val cam = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
                camera = cam
                handle.camera = cam
            }
        }, ContextCompat.getMainExecutor(context))
        handle.view = previewView
        onDispose {
            provider?.unbindAll()
            analysisExecutor.shutdown()
            handle.camera = null
        }
    }

    LaunchedEffect(camera, torch) {
        camera?.let { if (it.cameraInfo.hasFlashUnit()) it.cameraControl.enableTorch(torch) }
    }

    val scope = rememberCoroutineScope()
    val focusRing = remember { Animatable(0f) }
    var focusAt by remember { mutableStateOf<Offset?>(null) }

    Box(modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        val cam = camera ?: return@detectTapGestures
                        val point = previewView.meteringPointFactory.createPoint(p.x, p.y)
                        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                        focusAt = p
                        scope.launch {
                            focusRing.snapTo(1f)
                            focusRing.animateTo(0f, tween(900))
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val cam = camera ?: return@detectTransformGestures
                        val state = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                        val ratio = (state.zoomRatio * zoom).coerceIn(state.minZoomRatio, state.maxZoomRatio)
                        cam.cameraControl.setZoomRatio(ratio)
                    }
                },
        )
        val at = focusAt
        if (at != null && focusRing.value > 0f) {
            Canvas(Modifier.fillMaxSize()) {
                val r = 36.dp.toPx() * (1.3f - 0.3f * focusRing.value)
                drawCircle(Color.White.copy(alpha = focusRing.value), r, at, style = Stroke(2.dp.toPx()))
            }
        }
    }
}
