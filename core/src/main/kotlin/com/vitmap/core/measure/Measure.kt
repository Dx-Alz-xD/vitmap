package com.vitmap.core.measure

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

data class Pt(val x: Double, val y: Double) {
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    fun dist(o: Pt) = hypot(x - o.x, y - o.y)
}

/** 3×3 projective transform, row-major. */
class Homography(private val m: DoubleArray) {
    fun map(p: Pt): Pt {
        val w = m[6] * p.x + m[7] * p.y + m[8]
        return Pt((m[0] * p.x + m[1] * p.y + m[2]) / w, (m[3] * p.x + m[4] * p.y + m[5]) / w)
    }

    fun inverse(): Homography? {
        val a = m
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
        if (abs(det) < 1e-12) return null
        val inv = doubleArrayOf(
            a[4] * a[8] - a[5] * a[7], a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
            a[5] * a[6] - a[3] * a[8], a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
            a[3] * a[7] - a[4] * a[6], a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3],
        ).map { it / det }.toDoubleArray()
        return Homography(inv)
    }

    companion object {
        /** Solves the homography mapping four source points onto four destination points. */
        fun fromPoints(src: List<Pt>, dst: List<Pt>): Homography? {
            require(src.size == 4 && dst.size == 4)
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val (x, y) = src[i]
                val (u, v) = dst[i]
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            val h = solve(a) ?: return null
            return Homography(doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0))
        }

        /** Gaussian elimination with partial pivoting on an augmented n×(n+1) matrix. */
        private fun solve(a: Array<DoubleArray>): DoubleArray? {
            val n = a.size
            for (col in 0 until n) {
                var pivot = col
                for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
                if (abs(a[pivot][col]) < 1e-12) return null
                val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
                for (r in 0 until n) {
                    if (r == col) continue
                    val f = a[r][col] / a[col][col]
                    for (c in col..n) a[r][c] -= f * a[col][c]
                }
            }
            return DoubleArray(n) { a[it][n] / a[it][it] }
        }
    }
}

/** Maps image pixels to real-world millimetres on the measured plane. */
sealed interface Calibration {
    fun toWorld(p: Pt): Pt

    /** Uniform scale from a reference length: good when the camera faces the surface squarely. */
    data class Scale(val mmPerPixel: Double, val origin: Pt = Pt(0.0, 0.0)) : Calibration {
        override fun toWorld(p: Pt) = Pt((p.x - origin.x) * mmPerPixel, (p.y - origin.y) * mmPerPixel)
    }

    /** Perspective-corrected plane from a known rectangle's four corners. */
    class Plane(private val h: Homography) : Calibration {
        override fun toWorld(p: Pt) = h.map(p)
    }

    companion object {
        fun fromLine(a: Pt, b: Pt, lengthMm: Double): Calibration? {
            val px = a.dist(b)
            if (px < 1e-6 || lengthMm <= 0) return null
            return Scale(lengthMm / px, a)
        }

        /**
         * [corners] in image space, ordered around the rectangle starting at any corner; the first
         * edge (corner 0 → 1) corresponds to [widthMm].
         */
        fun fromRectangle(corners: List<Pt>, widthMm: Double, heightMm: Double): Calibration? {
            if (corners.size != 4 || widthMm <= 0 || heightMm <= 0) return null
            val world = listOf(Pt(0.0, 0.0), Pt(widthMm, 0.0), Pt(widthMm, heightMm), Pt(0.0, heightMm))
            val h = Homography.fromPoints(corners, world) ?: return null
            return Plane(h)
        }
    }
}

object Geometry {
    fun distance(cal: Calibration, a: Pt, b: Pt): Double = cal.toWorld(a).dist(cal.toWorld(b))

    fun pathLength(cal: Calibration, pts: List<Pt>): Double = pts.zipWithNext { a, b -> distance(cal, a, b) }.sum()

    /** Shoelace area in mm² of a polygon given in image coordinates. */
    fun area(cal: Calibration, pts: List<Pt>): Double {
        if (pts.size < 3) return 0.0
        val w = pts.map { cal.toWorld(it) }
        var s = 0.0
        for (i in w.indices) {
            val p = w[i]
            val q = w[(i + 1) % w.size]
            s += p.x * q.y - q.x * p.y
        }
        return abs(s) / 2
    }

    fun perimeter(cal: Calibration, pts: List<Pt>): Double =
        if (pts.size < 2) 0.0 else pathLength(cal, pts + pts.first())

    /** Angle at [vertex] in degrees, measured on the real plane. */
    fun angle(cal: Calibration, a: Pt, vertex: Pt, b: Pt): Double {
        val v = cal.toWorld(vertex)
        val p = cal.toWorld(a) - v
        val q = cal.toWorld(b) - v
        val d = hypot(p.x, p.y) * hypot(q.x, q.y)
        if (d < 1e-9) return 0.0
        return Math.toDegrees(acos(((p.x * q.x + p.y * q.y) / d).coerceIn(-1.0, 1.0)))
    }

    /** Circle through a centre and a rim point (radius measured in the plane). */
    fun circle(cal: Calibration, centre: Pt, rim: Pt): Triple<Double, Double, Double> {
        val r = distance(cal, centre, rim)
        return Triple(r * 2, Math.PI * r * r, 2 * Math.PI * r)
    }
}

enum class RefShape { RECTANGLE, CIRCLE, LENGTH }

data class ReferenceObject(
    val id: String,
    val name: String,
    val category: String,
    val shape: RefShape,
    /** Width (or diameter / length) in millimetres. */
    val widthMm: Double,
    /** Height in millimetres for rectangles. */
    val heightMm: Double = 0.0,
    val custom: Boolean = false,
) {
    val description: String
        get() = when (shape) {
            RefShape.RECTANGLE -> "${fmt(widthMm)} × ${fmt(heightMm)} mm"
            RefShape.CIRCLE -> "Ø ${fmt(widthMm)} mm"
            RefShape.LENGTH -> "${fmt(widthMm)} mm long"
        }

    private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')
}

object References {
    val builtIn: List<ReferenceObject> = listOf(
        ReferenceObject("card", "Credit / debit / ID card", "Cards", RefShape.RECTANGLE, 85.60, 53.98),
        ReferenceObject("bizcard_us", "Business card (US)", "Cards", RefShape.RECTANGLE, 88.9, 50.8),
        ReferenceObject("bizcard_eu", "Business card (EU)", "Cards", RefShape.RECTANGLE, 85.0, 55.0),
        ReferenceObject("playing", "Playing card (poker)", "Cards", RefShape.RECTANGLE, 63.5, 88.9),
        ReferenceObject("sim", "SIM card (mini)", "Cards", RefShape.RECTANGLE, 25.0, 15.0),
        ReferenceObject("sd", "SD memory card", "Cards", RefShape.RECTANGLE, 24.0, 32.0),
        ReferenceObject("microsd", "microSD card", "Cards", RefShape.RECTANGLE, 11.0, 15.0),
        ReferenceObject("a4", "A4 paper", "Paper", RefShape.RECTANGLE, 210.0, 297.0),
        ReferenceObject("a5", "A5 paper", "Paper", RefShape.RECTANGLE, 148.0, 210.0),
        ReferenceObject("a3", "A3 paper", "Paper", RefShape.RECTANGLE, 297.0, 420.0),
        ReferenceObject("letter", "US Letter paper", "Paper", RefShape.RECTANGLE, 215.9, 279.4),
        ReferenceObject("legal", "US Legal paper", "Paper", RefShape.RECTANGLE, 215.9, 355.6),
        ReferenceObject("postit", "Sticky note (3 × 3 in)", "Paper", RefShape.RECTANGLE, 76.0, 76.0),
        ReferenceObject("envelope_dl", "DL envelope", "Paper", RefShape.RECTANGLE, 220.0, 110.0),
        ReferenceObject("usd", "US dollar bill", "Banknotes", RefShape.RECTANGLE, 156.0, 66.3),
        ReferenceObject("eur5", "€5 note", "Banknotes", RefShape.RECTANGLE, 120.0, 62.0),
        ReferenceObject("eur10", "€10 note", "Banknotes", RefShape.RECTANGLE, 127.0, 67.0),
        ReferenceObject("eur20", "€20 note", "Banknotes", RefShape.RECTANGLE, 133.0, 72.0),
        ReferenceObject("eur50", "€50 note", "Banknotes", RefShape.RECTANGLE, 140.0, 77.0),
        ReferenceObject("inr100", "₹100 note (new)", "Banknotes", RefShape.RECTANGLE, 142.0, 66.0),
        ReferenceObject("inr500", "₹500 note", "Banknotes", RefShape.RECTANGLE, 150.0, 66.0),
        ReferenceObject("inr200", "₹200 note", "Banknotes", RefShape.RECTANGLE, 146.0, 66.0),
        ReferenceObject("gbp10", "£10 note (polymer)", "Banknotes", RefShape.RECTANGLE, 132.0, 69.0),
        ReferenceObject("quarter", "US quarter", "Coins", RefShape.CIRCLE, 24.26),
        ReferenceObject("penny", "US penny", "Coins", RefShape.CIRCLE, 19.05),
        ReferenceObject("nickel", "US nickel", "Coins", RefShape.CIRCLE, 21.21),
        ReferenceObject("dime", "US dime", "Coins", RefShape.CIRCLE, 17.91),
        ReferenceObject("eur1", "€1 coin", "Coins", RefShape.CIRCLE, 23.25),
        ReferenceObject("eur2", "€2 coin", "Coins", RefShape.CIRCLE, 25.75),
        ReferenceObject("eur50c", "50 euro cent", "Coins", RefShape.CIRCLE, 24.25),
        ReferenceObject("gbp1", "£1 coin", "Coins", RefShape.CIRCLE, 23.43),
        ReferenceObject("gbp2", "£2 coin", "Coins", RefShape.CIRCLE, 28.4),
        ReferenceObject("inr1", "₹1 coin", "Coins", RefShape.CIRCLE, 20.0),
        ReferenceObject("inr2", "₹2 coin", "Coins", RefShape.CIRCLE, 23.0),
        ReferenceObject("inr5", "₹5 coin", "Coins", RefShape.CIRCLE, 25.0),
        ReferenceObject("inr10", "₹10 coin", "Coins", RefShape.CIRCLE, 27.0),
        ReferenceObject("cad1", "Canadian loonie", "Coins", RefShape.CIRCLE, 26.5),
        ReferenceObject("aud1", "Australian $1", "Coins", RefShape.CIRCLE, 25.0),
        ReferenceObject("jpy100", "¥100 coin", "Coins", RefShape.CIRCLE, 22.6),
        ReferenceObject("jpy500", "¥500 coin", "Coins", RefShape.CIRCLE, 26.5),
        ReferenceObject("cd", "CD / DVD", "Everyday", RefShape.CIRCLE, 120.0),
        ReferenceObject("aa", "AA battery (length)", "Everyday", RefShape.LENGTH, 50.5),
        ReferenceObject("aaa", "AAA battery (length)", "Everyday", RefShape.LENGTH, 44.5),
        ReferenceObject("cr2032", "CR2032 coin cell", "Everyday", RefShape.CIRCLE, 20.0),
        ReferenceObject("pencil", "Standard pencil (new)", "Everyday", RefShape.LENGTH, 190.0),
        ReferenceObject("can", "Soda can (height)", "Everyday", RefShape.LENGTH, 122.0),
        ReferenceObject("can_d", "Soda can (diameter)", "Everyday", RefShape.CIRCLE, 66.0),
        ReferenceObject("brick", "Standard brick (US)", "Everyday", RefShape.RECTANGLE, 203.0, 57.0),
        ReferenceObject("lego", "LEGO 2×4 brick (top)", "Everyday", RefShape.RECTANGLE, 31.8, 15.8),
        ReferenceObject("usb_a", "USB-A plug (width)", "Tech", RefShape.LENGTH, 12.0),
        ReferenceObject("usb_c", "USB-C plug (width)", "Tech", RefShape.LENGTH, 8.25),
        ReferenceObject("hdmi", "HDMI plug (width)", "Tech", RefShape.LENGTH, 14.0),
        ReferenceObject("floppy", "3½\" floppy disk", "Tech", RefShape.RECTANGLE, 90.0, 94.0),
        ReferenceObject("cassette", "Audio cassette", "Tech", RefShape.RECTANGLE, 100.0, 63.0),
        ReferenceObject("ruler30", "30 cm ruler", "Tools", RefShape.LENGTH, 300.0),
        ReferenceObject("ruler15", "15 cm ruler", "Tools", RefShape.LENGTH, 150.0),
        ReferenceObject("tile", "Floor tile 30 × 30 cm", "Tools", RefShape.RECTANGLE, 300.0, 300.0),
        ReferenceObject("door", "Interior door (EU)", "Home", RefShape.RECTANGLE, 800.0, 2000.0),
        ReferenceObject("door_us", "Interior door (US)", "Home", RefShape.RECTANGLE, 812.8, 2032.0),
        ReferenceObject("outlet", "Light switch plate (US)", "Home", RefShape.RECTANGLE, 70.0, 115.0),
    )
}

/** Unit options for showing measurements. */
enum class LengthUnit(val symbol: String, val mm: Double) {
    MM("mm", 1.0),
    CM("cm", 10.0),
    M("m", 1000.0),
    IN("in", 25.4),
    FT("ft", 304.8),
    ;

    fun format(valueMm: Double): String {
        val v = valueMm / mm
        val digits = when {
            v >= 100 -> 1
            v >= 10 -> 1
            else -> 2
        }
        return "%.${digits}f %s".format(v, symbol)
    }

    fun formatArea(valueMm2: Double): String {
        val v = valueMm2 / (mm * mm)
        return "%.2f %s²".format(v, symbol)
    }

    /** Feet and inches like 5′ 7.3″ for the imperial view. */
    fun formatImperial(valueMm: Double): String {
        val inches = valueMm / 25.4
        val ft = (inches / 12).toInt()
        val rest = inches - ft * 12
        return if (ft > 0) "$ft′ ${"%.1f".format(rest)}″" else "${"%.2f".format(rest)}″"
    }
}

/** Physical size of the display, for the on-screen ruler. */
fun pixelsPerMm(xdpi: Float): Double = xdpi / 25.4
