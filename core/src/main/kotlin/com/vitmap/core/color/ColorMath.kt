package com.vitmap.core.color

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** sRGB colour with 8-bit channels and alpha in 0..1. */
data class Rgb(val r: Int, val g: Int, val b: Int, val alpha: Double = 1.0) {
    init {
        require(r in 0..255 && g in 0..255 && b in 0..255) { "Channel out of range" }
    }

    val argb: Int get() = ((alpha * 255).roundToInt().coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b

    companion object {
        fun fromArgb(argb: Int): Rgb = Rgb((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, ((argb ushr 24) and 0xFF) / 255.0)

        fun fromUnit(r: Double, g: Double, b: Double, alpha: Double = 1.0): Rgb = Rgb(
            (r.coerceIn(0.0, 1.0) * 255).roundToInt(),
            (g.coerceIn(0.0, 1.0) * 255).roundToInt(),
            (b.coerceIn(0.0, 1.0) * 255).roundToInt(),
            alpha.coerceIn(0.0, 1.0),
        )
    }
}

data class Hsl(val h: Double, val s: Double, val l: Double)
data class Hsv(val h: Double, val s: Double, val v: Double)
data class Hwb(val h: Double, val w: Double, val b: Double)
data class Cmyk(val c: Double, val m: Double, val y: Double, val k: Double)
data class Lab(val l: Double, val a: Double, val b: Double)
data class Lch(val l: Double, val c: Double, val h: Double)
data class Oklab(val l: Double, val a: Double, val b: Double)
data class Oklch(val l: Double, val c: Double, val h: Double)
data class Xyz(val x: Double, val y: Double, val z: Double)

object ColorMath {
    // ------------------------------------------------------------- transfer functions

    fun srgbToLinear(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    fun linearToSrgb(c: Double): Double = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055

    private fun unit(rgb: Rgb) = doubleArrayOf(rgb.r / 255.0, rgb.g / 255.0, rgb.b / 255.0)

    // ------------------------------------------------------------- to other models

    fun hex(rgb: Rgb, withAlpha: Boolean = rgb.alpha < 1.0): String {
        val base = "#%02X%02X%02X".format(rgb.r, rgb.g, rgb.b)
        return if (withAlpha) base + "%02X".format((rgb.alpha * 255).roundToInt()) else base
    }

    fun toHsl(rgb: Rgb): Hsl {
        val (r, g, b) = unit(rgb).let { Triple(it[0], it[1], it[2]) }
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        val d = mx - mn
        if (d == 0.0) return Hsl(0.0, 0.0, l * 100)
        val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
        return Hsl(hue(r, g, b, mx, d), s * 100, l * 100)
    }

    fun toHsv(rgb: Rgb): Hsv {
        val (r, g, b) = unit(rgb).let { Triple(it[0], it[1], it[2]) }
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = if (d == 0.0) 0.0 else hue(r, g, b, mx, d)
        val s = if (mx == 0.0) 0.0 else d / mx
        return Hsv(h, s * 100, mx * 100)
    }

    fun toHwb(rgb: Rgb): Hwb {
        val hsv = toHsv(rgb)
        val (r, g, b) = unit(rgb).let { Triple(it[0], it[1], it[2]) }
        val w = min(r, min(g, b))
        val bl = 1 - max(r, max(g, b))
        return Hwb(hsv.h, w * 100, bl * 100)
    }

    private fun hue(r: Double, g: Double, b: Double, mx: Double, d: Double): Double {
        val h = when (mx) {
            r -> ((g - b) / d) % 6
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } * 60
        return if (h < 0) h + 360 else h
    }

    fun toCmyk(rgb: Rgb): Cmyk {
        val (r, g, b) = unit(rgb).let { Triple(it[0], it[1], it[2]) }
        val k = 1 - max(r, max(g, b))
        if (k >= 1.0) return Cmyk(0.0, 0.0, 0.0, 100.0)
        return Cmyk((1 - r - k) / (1 - k) * 100, (1 - g - k) / (1 - k) * 100, (1 - b - k) / (1 - k) * 100, k * 100)
    }

    fun toXyz(rgb: Rgb): Xyz {
        val c = unit(rgb).map { srgbToLinear(it) }
        return Xyz(
            0.4124564 * c[0] + 0.3575761 * c[1] + 0.1804375 * c[2],
            0.2126729 * c[0] + 0.7151522 * c[1] + 0.0721750 * c[2],
            0.0193339 * c[0] + 0.1191920 * c[1] + 0.9503041 * c[2],
        )
    }

    /** CIELAB with a D65 white point. */
    fun toLab(rgb: Rgb): Lab {
        val xyz = toXyz(rgb)
        fun f(t: Double) = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16) / 116
        val fx = f(xyz.x / 0.95047)
        val fy = f(xyz.y / 1.0)
        val fz = f(xyz.z / 1.08883)
        return Lab(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    fun toLch(rgb: Rgb): Lch {
        val lab = toLab(rgb)
        return Lch(lab.l, hypot(lab.a, lab.b), normHue(Math.toDegrees(atan2(lab.b, lab.a))))
    }

    fun toOklab(rgb: Rgb): Oklab {
        val c = unit(rgb).map { srgbToLinear(it) }
        val l = 0.4122214708 * c[0] + 0.5363325363 * c[1] + 0.0514459929 * c[2]
        val m = 0.2119034982 * c[0] + 0.6806995451 * c[1] + 0.1073969566 * c[2]
        val s = 0.0883024619 * c[0] + 0.2817188376 * c[1] + 0.6299787005 * c[2]
        val l1 = cbrt(l)
        val m1 = cbrt(m)
        val s1 = cbrt(s)
        return Oklab(
            0.2104542553 * l1 + 0.7936177850 * m1 - 0.0040720468 * s1,
            1.9779984951 * l1 - 2.4285922050 * m1 + 0.4505937099 * s1,
            0.0259040371 * l1 + 0.7827717662 * m1 - 0.8086757660 * s1,
        )
    }

    fun toOklch(rgb: Rgb): Oklch {
        val o = toOklab(rgb)
        val c = hypot(o.a, o.b)
        val h = if (c < 1e-4) 0.0 else normHue(Math.toDegrees(atan2(o.b, o.a)))
        return Oklch(o.l, c, h)
    }

    private fun normHue(h: Double): Double = ((h % 360) + 360) % 360

    // ------------------------------------------------------------- from other models

    fun fromHsl(h: Double, s: Double, l: Double, alpha: Double = 1.0): Rgb {
        val ss = s / 100
        val ll = l / 100
        val c = (1 - abs(2 * ll - 1)) * ss
        val hp = normHue(h) / 60
        val x = c * (1 - abs(hp % 2 - 1))
        val (r1, g1, b1) = sector(hp, c, x)
        val m = ll - c / 2
        return Rgb.fromUnit(r1 + m, g1 + m, b1 + m, alpha)
    }

    fun fromHsv(h: Double, s: Double, v: Double, alpha: Double = 1.0): Rgb {
        val ss = s / 100
        val vv = v / 100
        val c = vv * ss
        val hp = normHue(h) / 60
        val x = c * (1 - abs(hp % 2 - 1))
        val (r1, g1, b1) = sector(hp, c, x)
        val m = vv - c
        return Rgb.fromUnit(r1 + m, g1 + m, b1 + m, alpha)
    }

    fun fromHwb(h: Double, w: Double, b: Double, alpha: Double = 1.0): Rgb {
        val ww = w / 100
        val bb = b / 100
        if (ww + bb >= 1) {
            val gray = ww / (ww + bb)
            return Rgb.fromUnit(gray, gray, gray, alpha)
        }
        val pure = fromHsv(h, 100.0, 100.0)
        fun ch(v: Int) = v / 255.0 * (1 - ww - bb) + ww
        return Rgb.fromUnit(ch(pure.r), ch(pure.g), ch(pure.b), alpha)
    }

    private fun sector(hp: Double, c: Double, x: Double): Triple<Double, Double, Double> = when {
        hp < 1 -> Triple(c, x, 0.0)
        hp < 2 -> Triple(x, c, 0.0)
        hp < 3 -> Triple(0.0, c, x)
        hp < 4 -> Triple(0.0, x, c)
        hp < 5 -> Triple(x, 0.0, c)
        else -> Triple(c, 0.0, x)
    }

    fun fromCmyk(c: Double, m: Double, y: Double, k: Double): Rgb {
        val kk = k / 100
        return Rgb.fromUnit((1 - c / 100) * (1 - kk), (1 - m / 100) * (1 - kk), (1 - y / 100) * (1 - kk))
    }

    fun fromOklab(l: Double, a: Double, b: Double, alpha: Double = 1.0): Rgb {
        val l1 = l + 0.3963377774 * a + 0.2158037573 * b
        val m1 = l - 0.1055613458 * a - 0.0638541728 * b
        val s1 = l - 0.0894841775 * a - 1.2914855480 * b
        val ll = l1 * l1 * l1
        val mm = m1 * m1 * m1
        val ss = s1 * s1 * s1
        val r = 4.0767416621 * ll - 3.3077115913 * mm + 0.2309699292 * ss
        val g = -1.2684380046 * ll + 2.6097574011 * mm - 0.3413193965 * ss
        val bl = -0.0041960863 * ll - 0.7034186147 * mm + 1.7076147010 * ss
        return Rgb.fromUnit(linearToSrgb(r), linearToSrgb(g), linearToSrgb(bl), alpha)
    }

    fun fromOklch(l: Double, c: Double, h: Double, alpha: Double = 1.0): Rgb {
        val hr = Math.toRadians(h)
        return fromOklab(l, c * cos(hr), c * sin(hr), alpha)
    }

    fun fromLab(l: Double, a: Double, b: Double): Rgb {
        val fy = (l + 16) / 116
        val fx = fy + a / 500
        val fz = fy - b / 200
        fun inv(t: Double): Double { val t3 = t * t * t; return if (t3 > 216.0 / 24389.0) t3 else (116 * t - 16) / (24389.0 / 27.0) }
        val x = inv(fx) * 0.95047
        val y = inv(fy)
        val z = inv(fz) * 1.08883
        val r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        val g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        val bl = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
        return Rgb.fromUnit(linearToSrgb(r), linearToSrgb(g), linearToSrgb(bl))
    }

    // ------------------------------------------------------------- comparisons

    /** WCAG relative luminance. */
    fun luminance(rgb: Rgb): Double {
        val c = unit(rgb).map { srgbToLinear(it) }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
    }

    fun contrastRatio(a: Rgb, b: Rgb): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    fun deltaE76(a: Rgb, b: Rgb): Double {
        val x = toLab(a)
        val y = toLab(b)
        return sqrt((x.l - y.l).pow(2) + (x.a - y.a).pow(2) + (x.b - y.b).pow(2))
    }

    /** CIEDE2000 colour difference. */
    fun deltaE2000(c1: Rgb, c2: Rgb): Double = deltaE2000(toLab(c1), toLab(c2))

    fun deltaE2000(x: Lab, y: Lab): Double {
        val l1 = x.l; val a1 = x.a; val b1 = x.b
        val l2 = y.l; val a2 = y.a; val b2 = y.b
        val c1 = hypot(a1, b1)
        val c2 = hypot(a2, b2)
        val cBar = (c1 + c2) / 2
        val g = 0.5 * (1 - sqrt(cBar.pow(7) / (cBar.pow(7) + 25.0.pow(7))))
        val a1p = (1 + g) * a1
        val a2p = (1 + g) * a2
        val c1p = hypot(a1p, b1)
        val c2p = hypot(a2p, b2)
        val h1p = if (c1p == 0.0) 0.0 else normHue(Math.toDegrees(atan2(b1, a1p)))
        val h2p = if (c2p == 0.0) 0.0 else normHue(Math.toDegrees(atan2(b2, a2p)))
        val dLp = l2 - l1
        val dCp = c2p - c1p
        var dhp = h2p - h1p
        if (c1p * c2p == 0.0) dhp = 0.0 else if (dhp > 180) dhp -= 360 else if (dhp < -180) dhp += 360
        val dHp = 2 * sqrt(c1p * c2p) * sin(Math.toRadians(dhp / 2))
        val lBarP = (l1 + l2) / 2
        val cBarP = (c1p + c2p) / 2
        val hBarP = when {
            c1p * c2p == 0.0 -> h1p + h2p
            abs(h1p - h2p) <= 180 -> (h1p + h2p) / 2
            h1p + h2p < 360 -> (h1p + h2p + 360) / 2
            else -> (h1p + h2p - 360) / 2
        }
        val t = 1 - 0.17 * cos(Math.toRadians(hBarP - 30)) + 0.24 * cos(Math.toRadians(2 * hBarP)) +
            0.32 * cos(Math.toRadians(3 * hBarP + 6)) - 0.20 * cos(Math.toRadians(4 * hBarP - 63))
        val dTheta = 30 * exp(-((hBarP - 275) / 25).pow(2))
        val rc = 2 * sqrt(cBarP.pow(7) / (cBarP.pow(7) + 25.0.pow(7)))
        val sl = 1 + 0.015 * (lBarP - 50).pow(2) / sqrt(20 + (lBarP - 50).pow(2))
        val sc = 1 + 0.045 * cBarP
        val sh = 1 + 0.015 * cBarP * t
        val rt = -sin(Math.toRadians(2 * dTheta)) * rc
        return sqrt((dLp / sl).pow(2) + (dCp / sc).pow(2) + (dHp / sh).pow(2) + rt * (dCp / sc) * (dHp / sh))
    }

    fun describeDeltaE(de: Double): String = when {
        de < 1.0 -> "Imperceptible difference"
        de < 2.0 -> "Barely perceptible up close"
        de < 3.5 -> "Perceptible at a glance"
        de < 5.0 -> "Clear difference"
        de < 10.0 -> "Very different"
        else -> "Different colours"
    }

    // ------------------------------------------------------------- generation

    fun mix(a: Rgb, b: Rgb, t: Double): Rgb = Rgb.fromUnit(
        (a.r + (b.r - a.r) * t) / 255,
        (a.g + (b.g - a.g) * t) / 255,
        (a.b + (b.b - a.b) * t) / 255,
        a.alpha + (b.alpha - a.alpha) * t,
    )

    /** Perceptual mix in OKLab. */
    fun mixOklab(a: Rgb, b: Rgb, t: Double): Rgb {
        val x = toOklab(a)
        val y = toOklab(b)
        return fromOklab(x.l + (y.l - x.l) * t, x.a + (y.a - x.a) * t, x.b + (y.b - x.b) * t)
    }

    fun rotateHue(rgb: Rgb, degrees: Double): Rgb {
        val o = toOklch(rgb)
        return fromOklch(o.l, o.c, normHue(o.h + degrees), rgb.alpha)
    }

    fun harmony(rgb: Rgb, kind: Harmony): List<Rgb> = kind.offsets.map { rotateHue(rgb, it) }

    fun tints(rgb: Rgb, steps: Int = 5): List<Rgb> = (1..steps).map { mixOklab(rgb, Rgb(255, 255, 255), it / (steps + 1.0)) }
    fun shades(rgb: Rgb, steps: Int = 5): List<Rgb> = (1..steps).map { mixOklab(rgb, Rgb(0, 0, 0), it / (steps + 1.0)) }

    /** Best text colour (black or white) to show on top of [rgb]. */
    fun onColor(rgb: Rgb): Rgb = if (contrastRatio(rgb, Rgb(0, 0, 0)) >= contrastRatio(rgb, Rgb(255, 255, 255))) Rgb(0, 0, 0) else Rgb(255, 255, 255)

    // ------------------------------------------------------------- formatting

    private fun f(v: Double, digits: Int = 0): String =
        if (digits == 0) v.roundToInt().toString() else "%.${digits}f".format(v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    fun formatRgb(c: Rgb): String = if (c.alpha < 1) "rgba(${c.r}, ${c.g}, ${c.b}, ${f(c.alpha, 2)})" else "rgb(${c.r}, ${c.g}, ${c.b})"

    fun formatHsl(c: Rgb): String {
        val h = toHsl(c)
        return "hsla(${f(h.h)}, ${f(h.s)}%, ${f(h.l)}%, ${f(c.alpha, 2)})"
    }

    fun formatHsv(c: Rgb): String {
        val h = toHsv(c)
        return "hsv(${f(h.h)}, ${f(h.s)}%, ${f(h.v)}%)"
    }

    fun formatHwb(c: Rgb): String {
        val h = toHwb(c)
        return "hwb(${f(h.h)} ${f(h.w)}% ${f(h.b)}%)"
    }

    fun formatCmyk(c: Rgb): String {
        val k = toCmyk(c)
        return "cmyk(${f(k.c)}%, ${f(k.m)}%, ${f(k.y)}%, ${f(k.k)}%)"
    }

    fun formatOklch(c: Rgb): String {
        val o = toOklch(c)
        return "oklch(${f(o.l * 100, 1)}% ${f(o.c, 3)} ${f(o.h, 1)})"
    }

    fun formatLab(c: Rgb): String {
        val l = toLab(c)
        return "lab(${f(l.l, 1)} ${f(l.a, 1)} ${f(l.b, 1)})"
    }

    // ------------------------------------------------------------- parsing

    /** Parses hex, rgb(), hsl(), hsv(), hwb(), cmyk(), oklch(), lab() or a CSS colour name. */
    fun parse(input: String): Rgb? {
        val s = input.trim().lowercase()
        if (s.isEmpty()) return null
        CssColors.byName(s)?.let { return it }
        val hex = s.removePrefix("#")
        if (hex.all { it.isDigit() || it in 'a'..'f' } && hex.length in setOf(3, 4, 6, 8)) {
            val full = if (hex.length <= 4) hex.map { "$it$it" }.joinToString("") else hex
            val r = full.substring(0, 2).toInt(16)
            val g = full.substring(2, 4).toInt(16)
            val b = full.substring(4, 6).toInt(16)
            val a = if (full.length == 8) full.substring(6, 8).toInt(16) / 255.0 else 1.0
            return Rgb(r, g, b, a)
        }
        val fn = s.substringBefore('(').trim()
        if (!s.contains('(') || !s.endsWith(")")) return null
        val args = s.substringAfter('(').removeSuffix(")").split(',', ' ', '/').map { it.trim() }.filter { it.isNotEmpty() }
        fun num(i: Int, scale: Double = 1.0): Double? {
            val t = args.getOrNull(i) ?: return null
            return if (t.endsWith("%")) t.removeSuffix("%").toDoubleOrNull()?.let { it / 100 * scale } else t.removeSuffix("deg").toDoubleOrNull()
        }
        return runCatching {
            when (fn) {
                "rgb", "rgba" -> {
                    val r = num(0, 255.0)!!; val g = num(1, 255.0)!!; val b = num(2, 255.0)!!
                    Rgb(r.roundToInt().coerceIn(0, 255), g.roundToInt().coerceIn(0, 255), b.roundToInt().coerceIn(0, 255), num(3, 1.0) ?: 1.0)
                }
                "hsl", "hsla" -> fromHsl(num(0)!!, num(1, 100.0)!!, num(2, 100.0)!!, num(3, 1.0) ?: 1.0)
                "hsv", "hsb" -> fromHsv(num(0)!!, num(1, 100.0)!!, num(2, 100.0)!!)
                "hwb" -> fromHwb(num(0)!!, num(1, 100.0)!!, num(2, 100.0)!!, num(3, 1.0) ?: 1.0)
                "cmyk" -> fromCmyk(num(0, 100.0)!!, num(1, 100.0)!!, num(2, 100.0)!!, num(3, 100.0)!!)
                "oklch" -> {
                    val l = args[0].let { if (it.endsWith("%")) it.removeSuffix("%").toDouble() / 100 else it.toDouble() }
                    fromOklch(l, num(1)!!, num(2)!!, num(3, 1.0) ?: 1.0)
                }
                "lab" -> fromLab(num(0, 100.0)!!, num(1)!!, num(2)!!)
                else -> null
            }
        }.getOrNull()
    }
}

enum class Harmony(val title: String, val offsets: List<Double>) {
    COMPLEMENTARY("Complementary", listOf(0.0, 180.0)),
    ANALOGOUS("Analogous", listOf(-30.0, 0.0, 30.0)),
    TRIADIC("Triadic", listOf(0.0, 120.0, 240.0)),
    SPLIT("Split complementary", listOf(0.0, 150.0, 210.0)),
    TETRADIC("Tetradic", listOf(0.0, 90.0, 180.0, 270.0)),
}

data class NamedColor(val name: String, val rgb: Rgb) {
    internal val lab: Lab = ColorMath.toLab(rgb)
}

/** Nearest-name lookup over a list of named colours using CIEDE2000. */
class ColorNamer(private val colors: List<NamedColor>) {
    fun nearest(rgb: Rgb): Pair<NamedColor, Double>? {
        if (colors.isEmpty()) return null
        val lab = ColorMath.toLab(rgb)
        // Cheap pre-filter in Lab space, then exact ΔE2000 on the closest candidates.
        val candidates = colors.sortedBy { (it.lab.l - lab.l).pow(2) + (it.lab.a - lab.a).pow(2) + (it.lab.b - lab.b).pow(2) }.take(12)
        return candidates.map { it to ColorMath.deltaE2000(lab, it.lab) }.minByOrNull { it.second }
    }

    companion object {
        /** Parses "name,#hex" lines (header optional). */
        fun fromCsv(text: String): ColorNamer {
            val list = ArrayList<NamedColor>()
            for (line in text.lineSequence()) {
                val i = line.lastIndexOf(',')
                if (i <= 0) continue
                val name = line.substring(0, i).trim()
                val rgb = ColorMath.parse(line.substring(i + 1).trim()) ?: continue
                list += NamedColor(name, rgb)
            }
            return ColorNamer(list)
        }

        val css: ColorNamer by lazy { ColorNamer(CssColors.all.map { NamedColor(it.first, it.second) }) }
    }
}

/** k-means palette extraction over ARGB pixels. */
object PaletteExtractor {
    fun extract(pixels: IntArray, k: Int = 6, iterations: Int = 12): List<Pair<Rgb, Double>> {
        if (pixels.isEmpty()) return emptyList()
        val step = max(1, pixels.size / 4000)
        val sample = (pixels.indices step step).map { pixels[it] }.filter { (it ushr 24) > 128 }
        if (sample.isEmpty()) return emptyList()
        val pts = sample.map { val o = ColorMath.toOklab(Rgb.fromArgb(it or (0xFF shl 24))); doubleArrayOf(o.l, o.a, o.b) }
        val kk = min(k, pts.size)
        // k-means++ style seeding (deterministic: farthest point)
        val centers = ArrayList<DoubleArray>()
        centers += pts[pts.size / 2].copyOf()
        while (centers.size < kk) {
            var best = pts[0]
            var bestD = -1.0
            for (p in pts) {
                val d = centers.minOf { dist(p, it) }
                if (d > bestD) { bestD = d; best = p }
            }
            centers += best.copyOf()
        }
        val assign = IntArray(pts.size)
        repeat(iterations) {
            for (i in pts.indices) assign[i] = centers.indices.minBy { dist(pts[i], centers[it]) }
            for (c in centers.indices) {
                var n = 0
                val sum = DoubleArray(3)
                for (i in pts.indices) if (assign[i] == c) { n++; for (j in 0..2) sum[j] += pts[i][j] }
                if (n > 0) for (j in 0..2) centers[c][j] = sum[j] / n
            }
        }
        val counts = IntArray(centers.size)
        for (a in assign) counts[a]++
        return centers.indices.filter { counts[it] > 0 }
            .map { ColorMath.fromOklab(centers[it][0], centers[it][1], centers[it][2]) to counts[it].toDouble() / pts.size }
            .sortedByDescending { it.second }
    }

    private fun dist(a: DoubleArray, b: DoubleArray): Double =
        (a[0] - b[0]).pow(2) + (a[1] - b[1]).pow(2) + (a[2] - b[2]).pow(2)
}
