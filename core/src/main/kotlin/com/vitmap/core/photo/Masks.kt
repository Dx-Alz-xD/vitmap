package com.vitmap.core.photo

import kotlinx.serialization.Serializable
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One dab path of a mask brush, in image-relative coordinates (0…1). */
@Serializable
data class MaskStroke(val points: List<Float>, val radius: Float, val hardness: Float = 0.5f, val erase: Boolean = false)

/** Where a local adjustment applies. Coordinates are relative (0…1) so they survive resizing. */
@Serializable
sealed interface MaskShape {
    @Serializable
    data class Radial(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val angle: Float = 0f, val feather: Float = 0.5f, val invert: Boolean = false) : MaskShape

    @Serializable
    data class Linear(val x0: Float, val y0: Float, val x1: Float, val y1: Float) : MaskShape

    @Serializable
    data class Brush(val strokes: List<MaskStroke>) : MaskShape
}

/** A masked set of adjustments painted over the photo. */
@Serializable
data class LocalAdjustment(val shape: MaskShape, val adjust: Adjustments, val amount: Float = 1f)

object Masks {
    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /** Renders [shape] to per-pixel weights 0…1 for a w×h image. */
    fun render(shape: MaskShape, w: Int, h: Int): FloatArray = when (shape) {
        is MaskShape.Radial -> radial(shape, w, h)
        is MaskShape.Linear -> linear(shape, w, h)
        is MaskShape.Brush -> brush(shape.strokes, w, h)
    }

    private fun radial(s: MaskShape.Radial, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        val cx = s.cx * w
        val cy = s.cy * h
        val rx = max(1f, s.rx * w)
        val ry = max(1f, s.ry * h)
        val ca = cos(s.angle)
        val sa = sin(s.angle)
        val inner = (1f - s.feather).coerceIn(0f, 0.999f)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - cx
            val dy = y - cy
            val u = (dx * ca + dy * sa) / rx
            val v = (-dx * sa + dy * ca) / ry
            val d = sqrt(u * u + v * v)
            val m = 1f - smoothstep(inner, 1f, d)
            out[y * w + x] = if (s.invert) 1f - m else m
        }
        return out
    }

    /** Full strength at the first point, fading to nothing at the second. */
    private fun linear(s: MaskShape.Linear, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        val x0 = s.x0 * w; val y0 = s.y0 * h
        val dx = s.x1 * w - x0; val dy = s.y1 * h - y0
        val len2 = max(1f, dx * dx + dy * dy)
        for (y in 0 until h) for (x in 0 until w) {
            val t = ((x - x0) * dx + (y - y0) * dy) / len2
            out[y * w + x] = 1f - smoothstep(0f, 1f, t)
        }
        return out
    }

    private fun brush(strokes: List<MaskStroke>, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        val scale = min(w, h).toFloat()
        for (st in strokes) {
            val r = max(1f, st.radius * scale)
            val inner = st.hardness.coerceIn(0f, 0.98f)
            val pts = st.points
            // Dab along the path every quarter radius.
            val dabs = ArrayList<Pair<Float, Float>>()
            var i = 0
            while (i + 1 < pts.size) {
                val ax = pts[i] * w; val ay = pts[i + 1] * h
                if (i + 3 < pts.size) {
                    val bx = pts[i + 2] * w; val by = pts[i + 3] * h
                    val n = max(1, (hypot(bx - ax, by - ay) / (r / 4)).toInt())
                    for (k in 0 until n) dabs += Pair(ax + (bx - ax) * k / n, ay + (by - ay) * k / n)
                } else dabs += Pair(ax, ay)
                i += 2
            }
            for ((cx, cy) in dabs) {
                val x0 = max(0, (cx - r).toInt()); val x1 = min(w - 1, (cx + r).toInt())
                val y0 = max(0, (cy - r).toInt()); val y1 = min(h - 1, (cy + r).toInt())
                for (y in y0..y1) for (x in x0..x1) {
                    val d = hypot(x - cx, y - cy) / r
                    if (d >= 1f) continue
                    val a = 1f - smoothstep(inner, 1f, d)
                    val idx = y * w + x
                    out[idx] = if (st.erase) out[idx] * (1 - a) else max(out[idx], a)
                }
            }
        }
        return out
    }

    /** Mixes [top] over [base] by [mask] × [amount]; both ARGB, same size. */
    fun blend(base: IntArray, top: IntArray, mask: FloatArray, amount: Float = 1f): IntArray {
        val out = IntArray(base.size)
        for (i in base.indices) {
            val k = (mask[i] * amount).coerceIn(0f, 1f)
            if (k <= 0f) { out[i] = base[i]; continue }
            if (k >= 1f) { out[i] = top[i]; continue }
            val a = base[i]; val b = top[i]
            fun ch(s: Int) = (((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * k)).toInt().coerceIn(0, 255)
            out[i] = (a and -0x1000000) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return out
    }
}
