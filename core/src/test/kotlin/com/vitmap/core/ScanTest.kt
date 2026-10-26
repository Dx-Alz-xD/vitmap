package com.vitmap.core

import com.vitmap.core.scan.DocumentDetector
import com.vitmap.core.scan.PageFilter
import com.vitmap.core.scan.PageFilters
import com.vitmap.core.scan.Pt
import com.vitmap.core.scan.Quad
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScanTest {
    private fun inside(q: Quad, x: Double, y: Double): Boolean {
        val c = q.corners
        var sign = 0
        for (i in 0 until 4) {
            val a = c[i]
            val b = c[(i + 1) % 4]
            val cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    private fun rotatedQuad(cx: Double, cy: Double, w: Double, h: Double, deg: Double): Quad {
        val a = Math.toRadians(deg)
        fun p(dx: Double, dy: Double) = Pt(cx + dx * cos(a) - dy * sin(a), cy + dx * sin(a) + dy * cos(a))
        return Quad(p(-w / 2, -h / 2), p(w / 2, -h / 2), p(w / 2, h / 2), p(-w / 2, h / 2))
    }

    private fun scene(w: Int, h: Int, q: Quad, bg: Int, fg: Int, seed: Int = 7, gradient: Boolean = false): ByteArray {
        val rnd = Random(seed)
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val inDoc = inside(q, x + 0.5, y + 0.5)
            var v = if (inDoc) fg else bg
            if (inDoc && y % 16 in 0..2 && (x / 6) % 5 != 0) v = 70 // lines of "text"
            if (gradient) v = (v * (0.6 + 0.4 * x / w)).toInt()
            v += rnd.nextInt(-10, 11)
            out[y * w + x] = v.coerceIn(0, 255).toByte()
        }
        return out
    }

    private fun assertClose(expected: Quad, actual: Quad, tolerance: Double) {
        val err = expected.maxShift(actual)
        assertTrue(err < tolerance, "corner error $err > $tolerance\nexpected $expected\nactual   $actual")
    }

    @Test
    fun findsRotatedPageOnDarkTable() {
        val q = rotatedQuad(330.0, 250.0, 300.0, 380.0, 12.0)
        val det = assertNotNull(DocumentDetector.detect(scene(640, 480 + 60, q, bg = 55, fg = 225), 640, 540))
        assertClose(q, det.quad, 640 * 0.025)
        assertTrue(det.confidence > 0.5, "confidence ${det.confidence}")
    }

    @Test
    fun findsLowContrastPageByItsEdges() {
        val q = rotatedQuad(320.0, 240.0, 380.0, 300.0, -7.0)
        val det = assertNotNull(DocumentDetector.detect(scene(640, 480, q, bg = 175, fg = 235), 640, 480))
        assertClose(q, det.quad, 640 * 0.03)
    }

    @Test
    fun handlesUnevenLighting() {
        val q = rotatedQuad(400.0, 300.0, 420.0, 480.0, 4.0)
        val det = assertNotNull(DocumentDetector.detect(scene(800, 600, q, bg = 40, fg = 230, gradient = true), 800, 600))
        assertClose(q, det.quad, 800 * 0.03)
    }

    @Test
    fun ignoresAnEmptyScene() {
        val rnd = Random(3)
        val noise = ByteArray(320 * 240) { (120 + rnd.nextInt(-25, 26)).toByte() }
        val det = DocumentDetector.detect(noise, 320, 240)
        assertTrue(det == null || det.confidence < 0.5, "unexpected detection $det")
    }

    @Test
    fun orderedCornersStartTopLeftClockwise() {
        val q = Quad.ordered(listOf(Pt(10.0, 90.0), Pt(100.0, 5.0), Pt(5.0, 8.0), Pt(95.0, 99.0)))
        assertTrue(q.tl == Pt(5.0, 8.0) && q.tr == Pt(100.0, 5.0) && q.br == Pt(95.0, 99.0) && q.bl == Pt(10.0, 90.0), q.toString())
    }

    private fun page(w: Int, h: Int): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val shade = 0.55 + 0.45 * x / w // shadow from the left
        val ink = y % 20 in 8..11 && x in 20 until w - 20
        val base = if (ink) 30 else 240
        val v = (base * shade).toInt()
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    @Test
    fun magicWhitensShadowedPaperAndKeepsInk() {
        val w = 300
        val h = 200
        val px = page(w, h)
        PageFilters.apply(px, w, h, PageFilter.MAGIC)
        val paper = PageFilters.luma(px[5 * w + 5]) // shadowed corner, no ink
        val ink = PageFilters.luma(px[9 * w + 40])
        assertTrue(paper > 235, "paper $paper")
        assertTrue(ink < 90, "ink $ink")
    }

    @Test
    fun blackAndWhiteIsBinary() {
        val w = 300
        val h = 200
        val px = page(w, h)
        PageFilters.apply(px, w, h, PageFilter.BW)
        assertTrue(px.all { it == -1 || it == 0xFF000000.toInt() })
        assertTrue(px[9 * w + 40] == 0xFF000000.toInt())
        assertTrue(px[3 * w + 150] == -1)
    }
}
