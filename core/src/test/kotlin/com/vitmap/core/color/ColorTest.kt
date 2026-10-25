package com.vitmap.core.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorTest {
    @Test
    fun conversionsMatchReferenceValues() {
        val c = Rgb(255, 87, 51) // #FF5733
        assertEquals("#FF5733", ColorMath.hex(c))
        val hsl = ColorMath.toHsl(c)
        assertEquals(10.6, hsl.h, 0.1)
        assertEquals(100.0, hsl.s, 0.1)
        assertEquals(60.0, hsl.l, 0.1)
        val cmyk = ColorMath.toCmyk(c)
        assertEquals(0.0, cmyk.c, 0.01); assertEquals(65.9, cmyk.m, 0.1); assertEquals(80.0, cmyk.y, 0.1); assertEquals(0.0, cmyk.k, 0.01)
        val ok = ColorMath.toOklch(Rgb(255, 0, 0))
        assertEquals(0.628, ok.l, 0.002)
        assertEquals(0.2577, ok.c, 0.002)
        assertEquals(29.23, ok.h, 0.2)
        val lab = ColorMath.toLab(Rgb(255, 255, 255))
        assertEquals(100.0, lab.l, 0.01)
    }

    @Test
    fun roundTrips() {
        for (argb in listOf(0xFF336699.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFAB12EF.toInt(), 0xFF7F7F7F.toInt())) {
            val c = Rgb.fromArgb(argb)
            val h = ColorMath.toHsl(c)
            assertEquals(c, ColorMath.fromHsl(h.h, h.s, h.l))
            val v = ColorMath.toHsv(c)
            assertEquals(c, ColorMath.fromHsv(v.h, v.s, v.v))
            val w = ColorMath.toHwb(c)
            assertEquals(c, ColorMath.fromHwb(w.h, w.w, w.b))
            val o = ColorMath.toOklch(c)
            assertEquals(c, ColorMath.fromOklch(o.l, o.c, o.h))
            val l = ColorMath.toLab(c)
            assertEquals(c, ColorMath.fromLab(l.l, l.a, l.b))
            assertEquals(c, ColorMath.parse(ColorMath.hex(c)))
        }
    }

    @Test
    fun parsesCssSyntax() {
        assertEquals(Rgb(255, 0, 0), ColorMath.parse("red"))
        assertEquals(Rgb(0x11, 0x22, 0x33), ColorMath.parse("#123"))
        assertEquals(Rgb(10, 20, 30), ColorMath.parse("rgb(10, 20, 30)"))
        assertEquals(Rgb(255, 0, 0), ColorMath.parse("hsl(0, 100%, 50%)"))
        assertEquals(Rgb(0, 0, 255), ColorMath.parse("hwb(240 0% 0%)"))
        assertEquals(Rgb(0, 255, 255), ColorMath.parse("cmyk(100%, 0%, 0%, 0%)"))
    }

    @Test
    fun differenceAndContrast() {
        assertEquals(21.0, ColorMath.contrastRatio(Rgb(0, 0, 0), Rgb(255, 255, 255)), 0.01)
        assertEquals(0.0, ColorMath.deltaE2000(Rgb(1, 2, 3), Rgb(1, 2, 3)), 1e-9)
        // Sharma et al. test pair 1
        assertEquals(2.0425, ColorMath.deltaE2000(Lab(50.0, 2.6772, -79.7751), Lab(50.0, 0.0, -82.7485)), 1e-4)
        assertTrue(ColorMath.deltaE2000(Rgb(255, 0, 0), Rgb(0, 0, 255)) > 30)
    }

    @Test
    fun namesAndPalette() {
        assertEquals("tomato", ColorNamer.css.nearest(Rgb(255, 99, 71))!!.first.name)
        val px = IntArray(1000) { if (it < 700) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val pal = PaletteExtractor.extract(px, 2)
        assertEquals(2, pal.size)
        assertEquals(Rgb(255, 0, 0), pal[0].first)
        assertEquals(0.7, pal[0].second, 0.01)
    }
}
