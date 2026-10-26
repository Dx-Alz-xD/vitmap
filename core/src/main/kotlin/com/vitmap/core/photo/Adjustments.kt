package com.vitmap.core.photo

import kotlinx.serialization.Serializable

@Serializable
data class CurvePoint(val x: Float, val y: Float)

/** Tone curves: a master curve and one per channel, each through (0,0)…(1,1) by default. */
@Serializable
data class Curves(
    val rgb: List<CurvePoint> = IDENTITY,
    val red: List<CurvePoint> = IDENTITY,
    val green: List<CurvePoint> = IDENTITY,
    val blue: List<CurvePoint> = IDENTITY,
) {
    val isIdentity: Boolean get() = listOf(rgb, red, green, blue).all { it == IDENTITY }

    companion object {
        val IDENTITY = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
    }
}

/** Hue, saturation and luminance shifts for one colour band, each -100…100. */
@Serializable
data class HslBand(val hue: Float = 0f, val sat: Float = 0f, val lum: Float = 0f) {
    val isZero: Boolean get() = hue == 0f && sat == 0f && lum == 0f
}

/** Split-tone style colour grading. Hues are 0…360, amounts 0…100, balance -100…100. */
@Serializable
data class Grading(
    val shadowHue: Float = 220f,
    val shadowSat: Float = 0f,
    val midHue: Float = 30f,
    val midSat: Float = 0f,
    val highHue: Float = 45f,
    val highSat: Float = 0f,
    val balance: Float = 0f,
) {
    val isZero: Boolean get() = shadowSat == 0f && midSat == 0f && highSat == 0f
}

/**
 * Every global adjustment. Sliders use -100…100 (or 0…100 for one-sided effects) so presets and
 * UI share one scale; [PhotoPipeline] maps them to the actual maths.
 */
@Serializable
data class Adjustments(
    // Light
    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    // Colour
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val hue: Float = 0f,
    // Detail
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    val sharpen: Float = 0f,
    val noiseReduction: Float = 0f,
    // Effects
    val vignette: Float = 0f,
    val vignetteMidpoint: Float = 50f,
    val grain: Float = 0f,
    val fade: Float = 0f,
    val bloom: Float = 0f,
    val chromatic: Float = 0f,
    val posterize: Float = 0f,
    val monochrome: Boolean = false,
    val invert: Boolean = false,
    val curves: Curves = Curves(),
    /** Red, orange, yellow, green, aqua, blue, purple, magenta. */
    val hsl: List<HslBand> = List(8) { HslBand() },
    val grading: Grading = Grading(),
) {
    val isIdentity: Boolean get() = this == Adjustments()

    /** Blends towards [other] by [t] (0 = this, 1 = other); used for preset strength. */
    fun mix(other: Adjustments, t: Float): Adjustments {
        fun f(a: Float, b: Float) = a + (b - a) * t
        fun pts(a: List<CurvePoint>, b: List<CurvePoint>): List<CurvePoint> =
            if (t >= 0.5f) b.map { p -> CurvePoint(p.x, f(Curve.at(a, p.x), p.y)) } else a.map { p -> CurvePoint(p.x, f(p.y, Curve.at(b, p.x))) }
        return Adjustments(
            f(exposure, other.exposure), f(brightness, other.brightness), f(contrast, other.contrast), f(highlights, other.highlights),
            f(shadows, other.shadows), f(whites, other.whites), f(blacks, other.blacks), f(temperature, other.temperature), f(tint, other.tint),
            f(saturation, other.saturation), f(vibrance, other.vibrance), f(hue, other.hue), f(clarity, other.clarity), f(dehaze, other.dehaze),
            f(sharpen, other.sharpen), f(noiseReduction, other.noiseReduction), f(vignette, other.vignette), f(vignetteMidpoint, other.vignetteMidpoint),
            f(grain, other.grain), f(fade, other.fade), f(bloom, other.bloom), f(chromatic, other.chromatic), f(posterize, other.posterize),
            if (t >= 0.5f) other.monochrome else monochrome, if (t >= 0.5f) other.invert else invert,
            Curves(pts(curves.rgb, other.curves.rgb), pts(curves.red, other.curves.red), pts(curves.green, other.curves.green), pts(curves.blue, other.curves.blue)),
            hsl.zip(other.hsl) { a, b -> HslBand(f(a.hue, b.hue), f(a.sat, b.sat), f(a.lum, b.lum)) },
            Grading(
                other.grading.shadowHue.takeIf { other.grading.shadowSat > 0 } ?: grading.shadowHue, f(grading.shadowSat, other.grading.shadowSat),
                other.grading.midHue.takeIf { other.grading.midSat > 0 } ?: grading.midHue, f(grading.midSat, other.grading.midSat),
                other.grading.highHue.takeIf { other.grading.highSat > 0 } ?: grading.highHue, f(grading.highSat, other.grading.highSat),
                f(grading.balance, other.grading.balance),
            ),
        )
    }
}

/** Monotone cubic (Fritsch–Carlson) interpolation through curve points. */
object Curve {
    fun at(points: List<CurvePoint>, x: Float): Float = evaluate(points, x)

    fun evaluate(points: List<CurvePoint>, x: Float): Float {
        val p = points.sortedBy { it.x }
        if (p.isEmpty()) return x
        if (x <= p.first().x) return p.first().y
        if (x >= p.last().x) return p.last().y
        val n = p.size
        val dx = FloatArray(n - 1) { p[it + 1].x - p[it].x }
        val m = FloatArray(n - 1) { if (dx[it] == 0f) 0f else (p[it + 1].y - p[it].y) / dx[it] }
        val t = FloatArray(n)
        t[0] = m[0]
        t[n - 1] = m[n - 2]
        for (i in 1 until n - 1) t[i] = if (m[i - 1] * m[i] <= 0f) 0f else (m[i - 1] + m[i]) / 2f
        for (i in 0 until n - 1) {
            if (m[i] == 0f) { t[i] = 0f; t[i + 1] = 0f; continue }
            val a = t[i] / m[i]
            val b = t[i + 1] / m[i]
            val s = a * a + b * b
            if (s > 9f) {
                val k = 3f / kotlin.math.sqrt(s)
                t[i] = k * a * m[i]
                t[i + 1] = k * b * m[i]
            }
        }
        var i = 0
        while (i < n - 2 && x > p[i + 1].x) i++
        val h = dx[i]
        if (h == 0f) return p[i].y
        val u = (x - p[i].x) / h
        val u2 = u * u
        val u3 = u2 * u
        return ((2 * u3 - 3 * u2 + 1) * p[i].y + (u3 - 2 * u2 + u) * h * t[i] + (-2 * u3 + 3 * u2) * p[i + 1].y + (u3 - u2) * h * t[i + 1]).coerceIn(0f, 1f)
    }

    /** Samples the curve into [size] entries over 0…1. */
    fun lut(points: List<CurvePoint>, size: Int): FloatArray = FloatArray(size) { evaluate(points, it / (size - 1f)) }
}
