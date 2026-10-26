package com.vitmap.core

import com.vitmap.core.photo.Adjustments
import com.vitmap.core.photo.Curve
import com.vitmap.core.photo.CurvePoint
import com.vitmap.core.photo.Curves
import com.vitmap.core.photo.HslBand
import com.vitmap.core.photo.Inpaint
import com.vitmap.core.photo.MaskShape
import com.vitmap.core.photo.MaskStroke
import com.vitmap.core.photo.Masks
import com.vitmap.core.photo.PhotoPipeline
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhotoTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun r(c: Int) = c shr 16 and 0xFF
    private fun g(c: Int) = c shr 8 and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun luma(c: Int) = 0.2126 * r(c) + 0.7152 * g(c) + 0.0722 * b(c)

    /** A 32×32 test card: a horizontal grey ramp with a red and a blue patch. */
    private fun card(): IntArray = IntArray(32 * 32) { i ->
        val x = i % 32; val y = i / 32
        when {
            y < 8 && x < 8 -> argb(200, 40, 40)
            y < 8 && x >= 24 -> argb(40, 60, 200)
            else -> (x * 8).let { argb(it, it, it) }
        }
    }

    @Test
    fun identityLeavesPixelsAlone() {
        val px = card()
        val out = PhotoPipeline.render(px, 32, 32, Adjustments())
        assertTrue(px.contentEquals(out))
        // Neutral settings that still take the full path must round-trip within a level.
        val full = PhotoPipeline.render(px, 32, 32, Adjustments(vignetteMidpoint = 49.9f))
        for (i in px.indices) assertTrue(abs(r(px[i]) - r(full[i])) <= 1 && abs(b(px[i]) - b(full[i])) <= 1, "pixel $i: ${px[i]} vs ${full[i]}")
    }

    @Test
    fun lightAdjustmentsMoveTheRightWay() {
        val px = card()
        val mid = 32 * 20 + 16
        fun l(a: Adjustments) = luma(PhotoPipeline.render(px, 32, 32, a)[mid])
        val base = luma(px[mid])
        assertTrue(l(Adjustments(exposure = 40f)) > base + 20)
        assertTrue(l(Adjustments(exposure = -40f)) < base - 20)
        assertTrue(l(Adjustments(shadows = 100f)) > base)
        assertTrue(l(Adjustments(brightness = 50f)) > base)
        val dark = 32 * 20 + 4
        val bright = 32 * 20 + 28
        val c = PhotoPipeline.render(px, 32, 32, Adjustments(contrast = 80f))
        assertTrue(luma(c[dark]) < luma(px[dark]) && luma(c[bright]) > luma(px[bright]), "contrast spreads tones")
    }

    @Test
    fun colourAdjustments() {
        val px = card()
        val red = 32 * 2 + 2
        val grey = PhotoPipeline.render(px, 32, 32, Adjustments(saturation = -100f))[red]
        assertTrue(abs(r(grey) - g(grey)) <= 2 && abs(g(grey) - b(grey)) <= 2, "desaturated red is grey")
        val warm = PhotoPipeline.render(px, 32, 32, Adjustments(temperature = 80f))[32 * 20 + 16]
        assertTrue(r(warm) > b(warm) + 10, "warmer means redder than blue")
        val reds = Adjustments(hsl = List(8) { if (it == 0) HslBand(sat = -100f) else HslBand() })
        val out = PhotoPipeline.render(px, 32, 32, reds)
        assertTrue(abs(r(out[red]) - g(out[red])) < 30, "red band desaturated")
        val blue = 2 * 32 + 28
        assertTrue(b(out[blue]) > r(out[blue]) + 100, "blue untouched by the red band")
        val mono = PhotoPipeline.render(px, 32, 32, Adjustments(monochrome = true))
        assertTrue(mono.all { r(it) == g(it) && g(it) == b(it) })
    }

    @Test
    fun curvesAreMonotoneAndHitTheirPoints() {
        val pts = listOf(CurvePoint(0f, 0f), CurvePoint(0.25f, 0.4f), CurvePoint(0.75f, 0.7f), CurvePoint(1f, 1f))
        assertEquals(0.4f, Curve.evaluate(pts, 0.25f), 1e-4f)
        val lut = Curve.lut(pts, 256)
        for (i in 1 until 256) assertTrue(lut[i] >= lut[i - 1] - 1e-6f)
        val inverted = Curves(rgb = listOf(CurvePoint(0f, 1f), CurvePoint(1f, 0f)))
        val out = PhotoPipeline.render(intArrayOf(argb(255, 255, 255)), 1, 1, Adjustments(curves = inverted))
        assertTrue(r(out[0]) < 5)
    }

    @Test
    fun presetMixingInterpolates() {
        val a = Adjustments(exposure = 0f)
        val b = Adjustments(exposure = 40f, contrast = 20f)
        val m = a.mix(b, 0.5f)
        assertEquals(20f, m.exposure)
        assertEquals(10f, m.contrast)
        assertEquals(a, a.mix(b, 0f).copy())
    }

    @Test
    fun masks() {
        val radial = Masks.render(MaskShape.Radial(0.5f, 0.5f, 0.25f, 0.25f, feather = 0.2f), 40, 40)
        assertTrue(radial[20 * 40 + 20] > 0.99f && radial[0] < 0.01f)
        val linear = Masks.render(MaskShape.Linear(0f, 0f, 0f, 1f), 10, 10)
        assertTrue(linear[0] > 0.99f && linear[99] < 0.05f, "fades out by the far edge")
        val brush = Masks.render(MaskShape.Brush(listOf(MaskStroke(listOf(0.1f, 0.5f, 0.9f, 0.5f), 0.1f, 0.8f))), 50, 50)
        assertTrue(brush[25 * 50 + 25] > 0.9f && brush[2 * 50 + 25] == 0f)
        val blended = Masks.blend(intArrayOf(argb(0, 0, 0)), intArrayOf(argb(200, 200, 200)), floatArrayOf(0.5f))
        assertEquals(100, r(blended[0]))
    }

    @Test
    fun inpaintContinuesStripes() {
        val w = 64; val h = 64
        val truth = IntArray(w * h) { i -> if ((i % w) / 4 % 2 == 0) argb(230, 230, 230) else argb(30, 30, 30) }
        val hole = BooleanArray(w * h) { i -> val x = i % w; val y = i / w; x in 26..37 && y in 26..37 }
        val damaged = truth.copyOf().also { p -> for (i in p.indices) if (hole[i]) p[i] = argb(255, 0, 0) }
        val out = Inpaint.fill(damaged, w, h, hole)
        var err = 0.0
        var n = 0
        for (i in out.indices) if (hole[i]) { err += abs(luma(out[i]) - luma(truth[i])); n++ }
        assertTrue(err / n < 40, "mean error ${err / n}")
        assertTrue((0 until w * h).filter { hole[it] }.none { r(out[it]) == 255 && g(out[it]) == 0 }, "no red left")
    }

    @Test
    fun autoEnhanceBrightensDarkPhotos() {
        val dark = IntArray(64) { argb(20 + it, 20 + it, 25 + it) }
        val a = PhotoPipeline.autoEnhance(dark)
        assertTrue(a.exposure > 10, "exposure ${a.exposure}")
        val hist = PhotoPipeline.histogram(dark)
        assertEquals(64, hist[3].sum())
    }
}
