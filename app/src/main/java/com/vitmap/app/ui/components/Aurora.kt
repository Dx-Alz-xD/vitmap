package com.vitmap.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Slowly drifting soft colour blobs, used behind headers. */
@Composable
fun AuroraBackground(colors: List<Color>, modifier: Modifier = Modifier, intensity: Float = 0.55f, periodMs: Int = 22000) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Canvas(modifier) {
        colors.forEachIndexed { i, c ->
            val k = i * 2.13f
            val cx = size.width * (0.5f + 0.38f * cos(phase + k))
            val cy = size.height * (0.38f + 0.28f * sin(phase * 1.4f + k * 0.8f))
            val r = size.minDimension * (0.55f + 0.12f * sin(phase * 2f + k))
            drawCircle(
                brush = Brush.radialGradient(listOf(c.copy(alpha = intensity), Color.Transparent), center = Offset(cx, cy), radius = r),
                radius = r,
                center = Offset(cx, cy),
            )
        }
    }
}
