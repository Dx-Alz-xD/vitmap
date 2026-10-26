package com.vitmap.core.photo

import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Applies [Adjustments] to ARGB pixels. Per-channel tone work is folded into lookup tables, colour
 * work runs per pixel, and the few spatial effects (noise reduction, clarity, sharpening, bloom)
 * use separable box blurs. Rows are processed in parallel.
 */
object PhotoPipeline {
    private val srgbToLinear = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }
    private const val LIN_SIZE = 4096
    private val linearToSrgb = FloatArray(LIN_SIZE) { i ->
        val c = i / (LIN_SIZE - 1f)
        if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
    }
    private const val TONE_SIZE = 4096

    private fun toSrgb(lin: Float): Float = if (lin >= 1f) 1f else if (lin <= 0f) 0f else linearToSrgb[(lin * (LIN_SIZE - 1)).toInt()]

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun parallelRows(h: Int, block: (Int, Int) -> Unit) {
        val band = 32
        val bands = (h + band - 1) / band
        IntStream.range(0, bands).parallel().forEach { b -> block(b * band, min(h, (b + 1) * band)) }
    }

    // ---------------------------------------------------------------- lookup tables

    /** White balance and exposure for one channel, in linear light, back to sRGB 0…1. */
    private fun exposureLut(gain: Float): FloatArray = FloatArray(256) { toSrgb(srgbToLinear[it] * gain) }

    /** Dehaze, levels, contrast, brightness, curves and fade for one channel. */
    private fun toneLut(a: Adjustments, channel: List<CurvePoint>): FloatArray {
        val master = if (a.curves.rgb == Curves.IDENTITY) null else Curve.lut(a.curves.rgb, 1024)
        val own = if (channel == Curves.IDENTITY) null else Curve.lut(channel, 1024)
        val haze = a.dehaze / 100f * 0.18f
        val bp = -a.blacks / 100f * 0.15f
        val wp = 1f - a.whites / 100f * 0.15f
        val c = a.contrast / 100f
        val gamma = 2f.pow(-a.brightness / 100f)
        val fade = a.fade / 100f
        fun sample(lut: FloatArray, v: Float) = lut[(v.coerceIn(0f, 1f) * (lut.size - 1)).roundToInt()]
        return FloatArray(TONE_SIZE) { i ->
            var v = i / (TONE_SIZE - 1f)
            v = if (haze >= 0) (v - haze) / (1 - haze) else v * (1 + haze) - haze
            v = (v - bp) / max(1e-3f, wp - bp)
            v = v.coerceIn(0f, 1f)
            v = if (c >= 0) v + (v * v * (3 - 2 * v) - v) * c else v + (0.5f + (v - 0.5f) * 0.4f - v) * -c
            v = v.coerceIn(0f, 1f).pow(gamma)
            if (master != null) v = sample(master, v)
            if (own != null) v = sample(own, v)
            v = v * (1 - fade * 0.22f) + fade * 0.1f
            v.coerceIn(0f, 1f)
        }
    }

    // ---------------------------------------------------------------- colour helpers

    private val bandCenters = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 280f, 320f)
    private val bandWidths = floatArrayOf(30f, 25f, 30f, 55f, 45f, 45f, 35f, 35f)

    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    /** Unit RGB direction of a hue, zero-mean, for tinting. */
    private fun tintVector(hue: Float): FloatArray {
        val h = Math.toRadians(hue.toDouble())
        val r = (0.5 + 0.5 * cos(h)).toFloat()
        val g = (0.5 + 0.5 * cos(h - 2.0944)).toFloat()
        val b = (0.5 + 0.5 * cos(h + 2.0944)).toFloat()
        val m = (r + g + b) / 3f
        return floatArrayOf(r - m, g - m, b - m)
    }

    // ---------------------------------------------------------------- main entry

    fun render(src: IntArray, w: Int, h: Int, a: Adjustments, seed: Int = 7): IntArray {
        require(src.size >= w * h)
        var px = src.copyOf(w * h)
        if (a.isIdentity) return px
        if (a.noiseReduction > 0) px = denoise(px, w, h, a.noiseReduction / 100f)
        colorPass(px, w, h, a)
        if (a.clarity != 0f) clarity(px, w, h, a.clarity / 100f)
        if (a.sharpen > 0) px = sharpen(px, w, h, a.sharpen / 100f)
        if (a.bloom > 0) bloom(px, w, h, a.bloom / 100f)
        if (a.chromatic > 0) px = chromatic(px, w, h, a.chromatic / 100f)
        if (a.vignette != 0f || a.grain > 0) finishPass(px, w, h, a, seed)
        return px
    }

    private fun colorPass(px: IntArray, w: Int, h: Int, a: Adjustments) {
        val ev = 2f.pow(a.exposure / 100f * 2.5f)
        val t = a.temperature / 100f
        val tn = a.tint / 100f
        val lr = exposureLut(ev * (1 + t * 0.25f) * (1 + tn * 0.08f))
        val lg = exposureLut(ev * (1 - tn * 0.18f))
        val lb = exposureLut(ev * (1 - t * 0.25f) * (1 + tn * 0.08f))
        val tr = toneLut(a, a.curves.red)
        val tg = toneLut(a, a.curves.green)
        val tb = toneLut(a, a.curves.blue)
        val sh = a.shadows / 100f
        val hl = a.highlights / 100f
        val sat = 1f + a.saturation / 100f + a.dehaze / 100f * 0.25f
        val vib = a.vibrance / 100f
        val hueShift = a.hue / 100f * 180f
        val bands = a.hsl.withIndex().filter { !it.value.isZero }
        val needHue = hueShift != 0f || bands.isNotEmpty()
        val g = a.grading
        val grade = !g.isZero
        val ts = tintVector(g.shadowHue)
        val tm = tintVector(g.midHue)
        val th = tintVector(g.highHue)
        val bal = g.balance / 100f
        val poster = if (a.posterize > 0) (2 + (1 - a.posterize / 100f) * 14).roundToInt().toFloat() else 0f
        parallelRows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val c = px[i]
                var r = lr[c shr 16 and 0xFF]
                var gg = lg[c shr 8 and 0xFF]
                var b = lb[c and 0xFF]
                if (sh != 0f || hl != 0f) {
                    val l = 0.2126f * r + 0.7152f * gg + 0.0722f * b
                    val fs = 4f * l * (1 - l) * (1 - l)
                    val fh = 4f * l * l * (1 - l)
                    val nl = (l + sh * 0.5f * fs + hl * 0.5f * fh).coerceIn(0f, 1.5f)
                    val gain = if (l > 1e-4f) nl / l else 1f
                    r *= gain; gg *= gain; b *= gain
                }
                r = tr[(r.coerceIn(0f, 1f) * (TONE_SIZE - 1)).toInt()]
                gg = tg[(gg.coerceIn(0f, 1f) * (TONE_SIZE - 1)).toInt()]
                b = tb[(b.coerceIn(0f, 1f) * (TONE_SIZE - 1)).toInt()]
                if (needHue || sat != 1f || vib != 0f) {
                    val mx = max(r, max(gg, b))
                    val mn = min(r, min(gg, b))
                    val l = 0.2126f * r + 0.7152f * gg + 0.0722f * b
                    var s = sat
                    if (vib != 0f) s *= 1 + vib * (1 - (mx - mn)).coerceIn(0f, 1f) * 0.9f
                    if (needHue && mx - mn > 1e-4f) {
                        // HSV hue of the pixel
                        val d = mx - mn
                        var hue = when (mx) {
                            r -> 60f * (((gg - b) / d) % 6f)
                            gg -> 60f * ((b - r) / d + 2f)
                            else -> 60f * ((r - gg) / d + 4f)
                        }
                        if (hue < 0) hue += 360f
                        var dh = hueShift
                        var bandSat = 1f
                        var dl = 0f
                        for ((idx, band) in bands) {
                            val wgt = (1f - hueDistance(hue, bandCenters[idx]) / bandWidths[idx]).coerceIn(0f, 1f)
                            if (wgt <= 0f) continue
                            val sw = wgt * wgt * (3 - 2 * wgt)
                            dh += band.hue / 100f * 30f * sw
                            bandSat *= 1f + band.sat / 100f * sw
                            dl += band.lum / 100f * 0.35f * sw * (d / max(mx, 1e-3f))
                        }
                        if (dh != 0f) {
                            // Rotate around the grey axis (YIQ-style rotation keeps luminance).
                            val rad = Math.toRadians(dh.toDouble())
                            val cs = cos(rad).toFloat()
                            val sn = sin(rad).toFloat()
                            val i0 = 0.596f * r - 0.274f * gg - 0.322f * b
                            val q0 = 0.211f * r - 0.523f * gg + 0.312f * b
                            val yy = 0.299f * r + 0.587f * gg + 0.114f * b
                            val i1 = i0 * cs - q0 * sn
                            val q1 = i0 * sn + q0 * cs
                            r = yy + 0.956f * i1 + 0.621f * q1
                            gg = yy - 0.272f * i1 - 0.647f * q1
                            b = yy - 1.106f * i1 + 1.703f * q1
                        }
                        s *= bandSat
                        if (dl != 0f) { r += dl; gg += dl; b += dl }
                    }
                    val l2 = 0.2126f * r + 0.7152f * gg + 0.0722f * b
                    r = l2 + (r - l2) * s
                    gg = l2 + (gg - l2) * s
                    b = l2 + (b - l2) * s
                }
                if (a.monochrome) {
                    val l = 0.2126f * r + 0.7152f * gg + 0.0722f * b
                    r = l; gg = l; b = l
                }
                if (grade) {
                    val l = (0.2126f * r + 0.7152f * gg + 0.0722f * b).coerceIn(0f, 1f)
                    val ws = (1 - smoothstep(0f, 0.5f + bal * 0.3f, l))
                    val wh = smoothstep(0.5f + bal * 0.3f, 1f, l)
                    val wm = (1 - ws - wh).coerceIn(0f, 1f)
                    val ks = g.shadowSat / 100f * 0.35f * ws
                    val km = g.midSat / 100f * 0.3f * wm
                    val kh = g.highSat / 100f * 0.3f * wh
                    r += ts[0] * ks + tm[0] * km + th[0] * kh
                    gg += ts[1] * ks + tm[1] * km + th[1] * kh
                    b += ts[2] * ks + tm[2] * km + th[2] * kh
                }
                if (poster > 0) {
                    r = (r * (poster - 1)).roundToInt() / (poster - 1)
                    gg = (gg * (poster - 1)).roundToInt() / (poster - 1)
                    b = (b * (poster - 1)).roundToInt() / (poster - 1)
                }
                if (a.invert) { r = 1 - r; gg = 1 - gg; b = 1 - b }
                px[i] = (c and -0x1000000) or (to8(r) shl 16) or (to8(gg) shl 8) or to8(b)
            }
        }
    }

    private fun to8(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun finishPass(px: IntArray, w: Int, h: Int, a: Adjustments, seed: Int) {
        val v = a.vignette / 100f
        val mid = a.vignetteMidpoint / 100f
        val grain = a.grain / 100f * 0.12f
        val cx = w / 2f
        val cy = h / 2f
        val inner = 0.25f + mid * 0.6f
        parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val c = px[i]
                    var r = (c shr 16 and 0xFF) / 255f
                    var g = (c shr 8 and 0xFF) / 255f
                    var b = (c and 0xFF) / 255f
                    if (v != 0f) {
                        val dx = (x - cx) / cx
                        val dy = (y - cy) / cy
                        val d = sqrt(dx * dx + dy * dy) / 1.4142f
                        val f = smoothstep(inner, 1.15f, d)
                        if (v < 0) { val k = 1 + v * f; r *= k; g *= k; b *= k } else { val k = v * f; r += (1 - r) * k; g += (1 - g) * k; b += (1 - b) * k }
                    }
                    if (grain > 0) {
                        var hsh = x * 374761393 + y * 668265263 + seed * 2147483647
                        hsh = (hsh xor (hsh ushr 13)) * 1274126177
                        val n1 = ((hsh xor (hsh ushr 16)) and 0xFFFF) / 65535f
                        var hs2 = hsh * 31 + 17
                        hs2 = (hs2 xor (hs2 ushr 15)) * 2246822519.toInt()
                        val n2 = ((hs2 xor (hs2 ushr 16)) and 0xFFFF) / 65535f
                        val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                        val n = (n1 + n2 - 1f) * grain * (0.4f + 2.4f * l * (1 - l))
                        r += n; g += n; b += n
                    }
                    px[i] = (c and -0x1000000) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
                }
            }
        }
    }

    // ---------------------------------------------------------------- spatial effects

    /** Separable box blur of one float plane, in place, [passes] times. */
    fun boxBlur(plane: FloatArray, w: Int, h: Int, radius: Int, passes: Int = 2) {
        if (radius < 1) return
        val tmp = FloatArray(plane.size)
        repeat(passes) {
            parallelRows(h) { y0, y1 ->
                for (y in y0 until y1) {
                    val row = y * w
                    var acc = 0f
                    for (k in -radius..radius) acc += plane[row + k.coerceIn(0, w - 1)]
                    for (x in 0 until w) {
                        tmp[row + x] = acc / (2 * radius + 1)
                        acc += plane[row + (x + radius + 1).coerceAtMost(w - 1)] - plane[row + (x - radius).coerceAtLeast(0)]
                    }
                }
            }
            val colBand = 32
            IntStream.range(0, (w + colBand - 1) / colBand).parallel().forEach { band ->
                for (x in band * colBand until min(w, (band + 1) * colBand)) {
                    var acc = 0f
                    for (k in -radius..radius) acc += tmp[k.coerceIn(0, h - 1) * w + x]
                    for (y in 0 until h) {
                        plane[y * w + x] = acc / (2 * radius + 1)
                        acc += tmp[(y + radius + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - radius).coerceAtLeast(0) * w + x]
                    }
                }
            }
        }
    }

    private fun planes(px: IntArray): Array<FloatArray> {
        val r = FloatArray(px.size)
        val g = FloatArray(px.size)
        val b = FloatArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            r[i] = (c shr 16 and 0xFF).toFloat()
            g[i] = (c shr 8 and 0xFF).toFloat()
            b[i] = (c and 0xFF).toFloat()
        }
        return arrayOf(r, g, b)
    }

    /** Edge-aware smoothing: blends towards a blur where neighbours are similar. */
    private fun denoise(px: IntArray, w: Int, h: Int, amount: Float): IntArray {
        val (r, g, b) = planes(px)
        val radius = 1 + (amount * 2).roundToInt()
        val br = r.copyOf(); val bg = g.copyOf(); val bb = b.copyOf()
        boxBlur(br, w, h, radius); boxBlur(bg, w, h, radius); boxBlur(bb, w, h, radius)
        val threshold = 12f + amount * 40f
        val out = IntArray(px.size)
        parallelRows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val diff = abs(r[i] - br[i]) + abs(g[i] - bg[i]) + abs(b[i] - bb[i])
                val k = amount * (1 - (diff / (threshold * 3)).coerceIn(0f, 1f))
                out[i] = (px[i] and -0x1000000) or
                    ((r[i] + (br[i] - r[i]) * k).roundToInt().coerceIn(0, 255) shl 16) or
                    ((g[i] + (bg[i] - g[i]) * k).roundToInt().coerceIn(0, 255) shl 8) or
                    (b[i] + (bb[i] - b[i]) * k).roundToInt().coerceIn(0, 255)
            }
        }
        return out
    }

    /** Local contrast on luminance with a large blur, strongest in the midtones. */
    private fun clarity(px: IntArray, w: Int, h: Int, amount: Float) {
        val lum = FloatArray(px.size) { i -> val c = px[i]; (0.2126f * (c shr 16 and 0xFF) + 0.7152f * (c shr 8 and 0xFF) + 0.0722f * (c and 0xFF)) }
        val blur = lum.copyOf()
        boxBlur(blur, w, h, max(2, max(w, h) / 60), passes = 3)
        parallelRows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val l = lum[i] / 255f
                val m = 1 - (2 * l - 1) * (2 * l - 1)
                val d = (lum[i] - blur[i]) * amount * 1.1f * m
                val c = px[i]
                px[i] = (c and -0x1000000) or
                    (((c shr 16 and 0xFF) + d).roundToInt().coerceIn(0, 255) shl 16) or
                    (((c shr 8 and 0xFF) + d).roundToInt().coerceIn(0, 255) shl 8) or
                    ((c and 0xFF) + d).roundToInt().coerceIn(0, 255)
            }
        }
    }

    private fun sharpen(px: IntArray, w: Int, h: Int, amount: Float): IntArray {
        val (r, g, b) = planes(px)
        val br = r.copyOf(); val bg = g.copyOf(); val bb = b.copyOf()
        boxBlur(br, w, h, 1, passes = 1); boxBlur(bg, w, h, 1, passes = 1); boxBlur(bb, w, h, 1, passes = 1)
        val k = amount * 1.6f
        val out = IntArray(px.size)
        parallelRows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                out[i] = (px[i] and -0x1000000) or
                    ((r[i] + (r[i] - br[i]) * k).roundToInt().coerceIn(0, 255) shl 16) or
                    ((g[i] + (g[i] - bg[i]) * k).roundToInt().coerceIn(0, 255) shl 8) or
                    (b[i] + (b[i] - bb[i]) * k).roundToInt().coerceIn(0, 255)
            }
        }
        return out
    }

    /** Soft glow from bright areas, screen-blended. */
    private fun bloom(px: IntArray, w: Int, h: Int, amount: Float) {
        val (r, g, b) = planes(px)
        for (i in r.indices) {
            val l = (0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i]) / 255f
            val k = smoothstep(0.6f, 1f, l)
            r[i] *= k; g[i] *= k; b[i] *= k
        }
        val radius = max(2, max(w, h) / 45)
        boxBlur(r, w, h, radius, 3); boxBlur(g, w, h, radius, 3); boxBlur(b, w, h, radius, 3)
        parallelRows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val c = px[i]
                fun screen(base: Int, glow: Float): Int {
                    val a = base / 255f
                    val s = (glow / 255f * amount * 1.2f).coerceIn(0f, 1f)
                    return to8(1 - (1 - a) * (1 - s))
                }
                px[i] = (c and -0x1000000) or (screen(c shr 16 and 0xFF, r[i]) shl 16) or (screen(c shr 8 and 0xFF, g[i]) shl 8) or screen(c and 0xFF, b[i])
            }
        }
    }

    /** Lens-style colour fringing: red scaled out, blue scaled in, from the centre. */
    private fun chromatic(px: IntArray, w: Int, h: Int, amount: Float): IntArray {
        val out = IntArray(px.size)
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val k = amount * 0.012f
        parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val dx = x - cx
                val dy = y - cy
                val rx = (cx + dx * (1 + k)).roundToInt().coerceIn(0, w - 1)
                val ry = (cy + dy * (1 + k)).roundToInt().coerceIn(0, h - 1)
                val bx = (cx + dx * (1 - k)).roundToInt().coerceIn(0, w - 1)
                val by = (cy + dy * (1 - k)).roundToInt().coerceIn(0, h - 1)
                val c = px[y * w + x]
                out[y * w + x] = (c and -0xff0100) or (px[ry * w + rx] and 0xFF0000) or (px[by * w + bx] and 0xFF)
            }
        }
        return out
    }

    // ---------------------------------------------------------------- analysis

    /** 256-bin histograms: red, green, blue, luminance. */
    fun histogram(px: IntArray): Array<IntArray> {
        val r = IntArray(256); val g = IntArray(256); val b = IntArray(256); val l = IntArray(256)
        for (c in px) {
            val rr = c shr 16 and 0xFF; val gg = c shr 8 and 0xFF; val bb = c and 0xFF
            r[rr]++; g[gg]++; b[bb]++
            l[((rr * 54 + gg * 183 + bb * 19) shr 8).coerceIn(0, 255)]++
        }
        return arrayOf(r, g, b, l)
    }

    /** One-tap fix: levels from the histogram ends, exposure from the median, neutral greys. */
    fun autoEnhance(px: IntArray): Adjustments {
        val (r, g, b, l) = histogram(px)
        val n = px.size.toFloat()
        fun percentile(hist: IntArray, p: Float): Int {
            var acc = 0f
            for (i in 0..255) { acc += hist[i]; if (acc / n >= p) return i }
            return 255
        }
        val lo = percentile(l, 0.005f)
        val hi = percentile(l, 0.995f)
        val med = percentile(l, 0.5f) / 255f
        val mr = (0..255).sumOf { it.toLong() * r[it] } / n
        val mg = (0..255).sumOf { it.toLong() * g[it] } / n
        val mb = (0..255).sumOf { it.toLong() * b[it] } / n
        val temp = ((mb - mr) / max(1f, (mr + mb) / 2f) * 160f).coerceIn(-35f, 35f)
        val tint = ((mg - (mr + mb) / 2f) / max(1f, mg) * 200f).coerceIn(-25f, 25f)
        val exposure = (kotlin.math.ln(0.46f / max(0.04f, med)) / kotlin.math.ln(2f) / 2.5f * 100f * 0.6f).coerceIn(-40f, 40f)
        return Adjustments(
            exposure = exposure,
            blacks = -(lo / 255f / 0.15f * 100f * 0.8f).coerceIn(0f, 60f),
            whites = ((255 - hi) / 255f / 0.15f * 100f * 0.8f).coerceIn(0f, 60f),
            temperature = temp,
            tint = tint,
            contrast = 8f,
            vibrance = 15f,
            clarity = 6f,
        )
    }
}
