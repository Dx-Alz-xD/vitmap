package com.vitmap.core.scan

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Looks for scanned pages. Each works in place on ARGB pixels. */
enum class PageFilter(val label: String) {
    ORIGINAL("Original"),
    MAGIC("Magic"),
    ENHANCE("Enhance"),
    GRAY("Grayscale"),
    BW("B&W"),
    INK("Ink saver"),
}

/** Manual adjustments layered on top of a filter; 0 means unchanged. */
data class PageAdjust(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val sharpness: Float = 0f,
) {
    val isIdentity: Boolean get() = brightness == 0f && contrast == 0f && saturation == 0f && sharpness == 0f
}

object PageFilters {
    fun apply(px: IntArray, w: Int, h: Int, filter: PageFilter, adjust: PageAdjust = PageAdjust()) {
        when (filter) {
            PageFilter.ORIGINAL -> {}
            PageFilter.MAGIC -> magic(px, w, h)
            PageFilter.ENHANCE -> autoLevels(px)
            PageFilter.GRAY -> { autoLevels(px); grayscale(px) }
            PageFilter.BW -> { magic(px, w, h); blackWhite(px, w, h) }
            PageFilter.INK -> { magic(px, w, h); grayscale(px); tone(px, 1.25f, 0.06f) }
        }
        if (!adjust.isIdentity) adjust(px, w, h, adjust)
    }

    fun luma(c: Int): Int = ((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8

    fun grayscale(px: IntArray) {
        for (i in px.indices) {
            val c = px[i]
            val l = luma(c)
            px[i] = (c and 0xFF000000.toInt()) or (l shl 16) or (l shl 8) or l
        }
    }

    /** Stretches each channel between its 0.5th and 99.5th percentile. */
    fun autoLevels(px: IntArray) {
        val hr = IntArray(256)
        val hg = IntArray(256)
        val hb = IntArray(256)
        val step = max(1, px.size / 200_000)
        var n = 0
        var i = 0
        while (i < px.size) {
            val c = px[i]
            hr[c shr 16 and 0xFF]++
            hg[c shr 8 and 0xFF]++
            hb[c and 0xFF]++
            n++
            i += step
        }
        fun bounds(hst: IntArray): Pair<Int, Int> {
            val lowCut = n * 0.005
            val highCut = n * 0.995
            var acc = 0
            var lo = 0
            var hi = 255
            for (v in 0..255) {
                acc += hst[v]
                if (acc > lowCut) { lo = v; break }
            }
            acc = 0
            for (v in 0..255) {
                acc += hst[v]
                if (acc >= highCut) { hi = v; break }
            }
            if (hi - lo < 32) return 0 to 255
            return lo to hi
        }
        val (rl, rh) = bounds(hr)
        val (gl, gh) = bounds(hg)
        val (bl, bh) = bounds(hb)
        val lr = lut(rl, rh)
        val lg = lut(gl, gh)
        val lb = lut(bl, bh)
        for (j in px.indices) {
            val c = px[j]
            px[j] = (c and 0xFF000000.toInt()) or (lr[c shr 16 and 0xFF] shl 16) or (lg[c shr 8 and 0xFF] shl 8) or lb[c and 0xFF]
        }
    }

    private fun lut(lo: Int, hi: Int) = IntArray(256) { v -> ((v - lo) * 255 / max(1, hi - lo)).coerceIn(0, 255) }

    /**
     * Removes shadows and uneven lighting: estimates the paper colour with a coarse max filter
     * and divides it out, so the page becomes white while ink keeps its colour.
     */
    fun magic(px: IntArray, w: Int, h: Int) {
        val cell = max(8, max(w, h) / 48)
        val gw = (w + cell - 1) / cell
        val gh = (h + cell - 1) / cell
        // Per-cell paper estimate: a high percentile of each channel (robust to text).
        val br = FloatArray(gw * gh)
        val bg = FloatArray(gw * gh)
        val bb = FloatArray(gw * gh)
        val hist = IntArray(256)
        for (cy in 0 until gh) for (cx in 0 until gw) {
            for (ch in 0 until 3) {
                hist.fill(0)
                var count = 0
                val shift = 16 - ch * 8
                var y = cy * cell
                val yEnd = min(h, y + cell)
                while (y < yEnd) {
                    var x = cx * cell
                    val xEnd = min(w, x + cell)
                    while (x < xEnd) {
                        hist[px[y * w + x] shr shift and 0xFF]++
                        count++
                        x += 2
                    }
                    y += 2
                }
                var acc = 0
                var v = 255
                val target = count * 0.9
                for (k in 0..255) {
                    acc += hist[k]
                    if (acc >= target) { v = k; break }
                }
                val out = when (ch) { 0 -> br; 1 -> bg; else -> bb }
                out[cy * gw + cx] = max(v, 40).toFloat()
            }
        }
        // Grow bright cells into dark ones (text-heavy cells) and smooth the field.
        for (field in listOf(br, bg, bb)) {
            repeat(2) { maxFilter(field, gw, gh) }
            repeat(3) { blur(field, gw, gh) }
        }
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / cell - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(gh - 1, y0 + 1)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / cell - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(gw - 1, x0 + 1)
                val tx = fx - x0
                fun sample(f: FloatArray): Float {
                    val a = f[y0 * gw + x0] * (1 - tx) + f[y0 * gw + x1] * tx
                    val b = f[y1 * gw + x0] * (1 - tx) + f[y1 * gw + x1] * tx
                    return a * (1 - ty) + b * ty
                }
                val i = y * w + x
                val c = px[i]
                val r = ((c shr 16 and 0xFF) * 255f / sample(br)).coerceAtMost(255f)
                val g = ((c shr 8 and 0xFF) * 255f / sample(bg)).coerceAtMost(255f)
                val b = ((c and 0xFF) * 255f / sample(bb)).coerceAtMost(255f)
                px[i] = (c and 0xFF000000.toInt()) or (curve(r) shl 16) or (curve(g) shl 8) or curve(b)
            }
        }
    }

    /** Deepens ink after background normalisation; values near white snap to white. */
    private fun curve(v: Float): Int {
        val n = v / 255f
        val o = if (n > 0.93f) 1f else (n / 0.93f).pow(1.6f)
        return (o * 255f).roundToInt().coerceIn(0, 255)
    }

    private fun maxFilter(f: FloatArray, w: Int, h: Int) {
        val src = f.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            var m = src[y * w + x]
            if (x > 0) m = max(m, src[y * w + x - 1])
            if (x < w - 1) m = max(m, src[y * w + x + 1])
            if (y > 0) m = max(m, src[(y - 1) * w + x])
            if (y < h - 1) m = max(m, src[(y + 1) * w + x])
            f[y * w + x] = m
        }
    }

    private fun blur(f: FloatArray, w: Int, h: Int) {
        val src = f.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) {
                    s += src[yy * w + xx]
                    n++
                }
            }
            f[y * w + x] = s / n
        }
    }

    /** Bradley–Roth adaptive threshold: crisp black text on white regardless of lighting. */
    fun blackWhite(px: IntArray, w: Int, h: Int, windowFraction: Float = 1f / 14, t: Float = 0.13f) {
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += luma(px[y * w + x])
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row
            }
        }
        val s = max(8, (max(w, h) * windowFraction).toInt()) / 2
        for (y in 0 until h) {
            val y0 = max(0, y - s)
            val y1 = min(h - 1, y + s)
            for (x in 0 until w) {
                val x0 = max(0, x - s)
                val x1 = min(w - 1, x + s)
                val count = (x1 - x0 + 1).toLong() * (y1 - y0 + 1)
                val sum = integral[(y1 + 1) * (w + 1) + x1 + 1] - integral[y0 * (w + 1) + x1 + 1] - integral[(y1 + 1) * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                val i = y * w + x
                val l = luma(px[i])
                val black = l * count < sum * (1 - t) && l < 200
                px[i] = if (black) 0xFF000000.toInt() else -1
            }
        }
    }

    /** Contrast around mid-grey and a lift, for the ink-saver look. */
    private fun tone(px: IntArray, contrast: Float, lift: Float) {
        val lut = IntArray(256) { v -> (((v / 255f - 0.5f) * contrast + 0.5f + lift) * 255f).roundToInt().coerceIn(0, 255) }
        for (i in px.indices) {
            val c = px[i]
            px[i] = (c and 0xFF000000.toInt()) or (lut[c shr 16 and 0xFF] shl 16) or (lut[c shr 8 and 0xFF] shl 8) or lut[c and 0xFF]
        }
    }

    fun adjust(px: IntArray, w: Int, h: Int, a: PageAdjust) {
        val contrast = 1f + a.contrast * (if (a.contrast > 0) 1.5f else 0.9f)
        val bright = a.brightness * 0.4f
        val lut = IntArray(256) { v -> (((v / 255f - 0.5f) * contrast + 0.5f + bright) * 255f).roundToInt().coerceIn(0, 255) }
        val sat = 1f + a.saturation
        for (i in px.indices) {
            val c = px[i]
            var r = lut[c shr 16 and 0xFF]
            var g = lut[c shr 8 and 0xFF]
            var b = lut[c and 0xFF]
            if (sat != 1f) {
                val l = (r * 77 + g * 150 + b * 29) shr 8
                r = (l + (r - l) * sat).roundToInt().coerceIn(0, 255)
                g = (l + (g - l) * sat).roundToInt().coerceIn(0, 255)
                b = (l + (b - l) * sat).roundToInt().coerceIn(0, 255)
            }
            px[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        if (a.sharpness > 0f) sharpen(px, w, h, a.sharpness)
    }

    /** 3×3 unsharp mask. */
    fun sharpen(px: IntArray, w: Int, h: Int, amount: Float) {
        val src = px.copyOf()
        val k = amount * 1.5f
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val c = src[i]
            var out = c and 0xFF000000.toInt()
            for (shift in intArrayOf(16, 8, 0)) {
                val center = c shr shift and 0xFF
                val avg = ((src[i - 1] shr shift and 0xFF) + (src[i + 1] shr shift and 0xFF) + (src[i - w] shr shift and 0xFF) + (src[i + w] shr shift and 0xFF)) / 4f
                val v = (center + (center - avg) * k).roundToInt().coerceIn(0, 255)
                out = out or (v shl shift)
            }
            px[i] = out
        }
    }
}
