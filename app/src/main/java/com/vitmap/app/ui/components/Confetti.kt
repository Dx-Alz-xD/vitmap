package com.vitmap.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.random.Random

private class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var rot: Float,
    val vr: Float,
    val color: Color,
    val w: Float,
    val h: Float,
)

/** Burst of confetti every time [trigger] changes to a new non-zero value. */
@Composable
fun ConfettiOverlay(trigger: Int, modifier: Modifier = Modifier, colors: List<Color> = DefaultConfetti) {
    var particles by remember { mutableStateOf<List<Particle>>(emptyList()) }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        val rnd = Random(trigger)
        particles = List(140) {
            val angle = Math.toRadians(rnd.nextDouble(200.0, 340.0))
            val speed = rnd.nextDouble(0.9, 2.2).toFloat()
            Particle(
                x = 0.5f + rnd.nextFloat() * 0.1f - 0.05f,
                y = 0.55f,
                vx = (kotlin.math.cos(angle) * speed).toFloat() * 0.6f,
                vy = (kotlin.math.sin(angle) * speed).toFloat(),
                rot = rnd.nextFloat() * 360f,
                vr = rnd.nextFloat() * 720f - 360f,
                color = colors[rnd.nextInt(colors.size)],
                w = 8f + rnd.nextFloat() * 8f,
                h = 4f + rnd.nextFloat() * 6f,
            )
        }
        var last = withFrameNanos { it }
        val start = last
        while (last - start < 3_200_000_000L) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1e9f
            last = now
            for (p in particles) {
                p.vy += 2.4f * dt
                p.vx *= 1f - 0.9f * dt
                p.x += p.vx * dt
                p.y += p.vy * dt
                p.rot += p.vr * dt
            }
            frame = now
        }
        particles = emptyList()
    }
    if (particles.isEmpty()) return
    Canvas(modifier) {
        if (frame == -1L) return@Canvas
        for (p in particles) {
            val c = Offset(p.x * size.width, p.y * size.height)
            rotate(p.rot, c) {
                drawRect(p.color, Offset(c.x - p.w / 2, c.y - p.h / 2), Size(p.w * density / 2, p.h * density / 2))
            }
        }
    }
}

val DefaultConfetti = listOf(
    Color(0xFFFF5D8F), Color(0xFFFFC145), Color(0xFF4ADE80), Color(0xFF60A5FA), Color(0xFFA78BFA), Color(0xFFF97316),
)
