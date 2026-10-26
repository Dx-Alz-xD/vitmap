package com.vitmap.core.scan

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class Pt(val x: Double, val y: Double) {
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun times(k: Double) = Pt(x * k, y * k)
    fun dist(o: Pt) = hypot(x - o.x, y - o.y)
}

/** A page outline: top-left, top-right, bottom-right, bottom-left. */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {
    val corners: List<Pt> get() = listOf(tl, tr, br, bl)

    val area: Double get() = polygonArea(corners)

    fun map(f: (Pt) -> Pt) = Quad(f(tl), f(tr), f(br), f(bl))

    /** Width and height of the rectified page, taking the longer of each pair of opposite edges. */
    fun outputSize(): Pair<Double, Double> = max(tl.dist(tr), bl.dist(br)) to max(tl.dist(bl), tr.dist(br))

    /** Largest corner movement relative to [other], for stability checks. */
    fun maxShift(other: Quad): Double = corners.zip(other.corners).maxOf { (a, b) -> a.dist(b) }

    fun lerp(other: Quad, t: Double) = Quad(
        tl + (other.tl - tl) * t, tr + (other.tr - tr) * t, br + (other.br - br) * t, bl + (other.bl - bl) * t,
    )

    /** Smallest interior angle in degrees; a sanity measure for detections. */
    fun minAngle(): Double {
        val c = corners
        return (0 until 4).minOf { i ->
            val a = c[(i + 3) % 4] - c[i]
            val b = c[(i + 1) % 4] - c[i]
            val cos = (a.x * b.x + a.y * b.y) / (hypot(a.x, a.y) * hypot(b.x, b.y)).coerceAtLeast(1e-9)
            Math.toDegrees(acos(cos.coerceIn(-1.0, 1.0)))
        }
    }

    companion object {
        fun full(w: Double, h: Double) = Quad(Pt(0.0, 0.0), Pt(w, 0.0), Pt(w, h), Pt(0.0, h))

        /** Orders four arbitrary points as TL, TR, BR, BL. */
        fun ordered(points: List<Pt>): Quad {
            require(points.size == 4)
            val cx = points.sumOf { it.x } / 4
            val cy = points.sumOf { it.y } / 4
            val sorted = points.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) }
            // atan2 order from -pi: starts at the left, goes up (top-left), then clockwise on screen.
            var start = 0
            var best = Double.MAX_VALUE
            for (i in 0 until 4) {
                val s = sorted[i].x + sorted[i].y
                if (s < best) {
                    best = s
                    start = i
                }
            }
            val r = (0 until 4).map { sorted[(start + it) % 4] }
            return Quad(r[0], r[1], r[2], r[3])
        }
    }
}

data class Detection(val quad: Quad, val confidence: Double)

/**
 * Finds a document page in a greyscale frame. Two candidate masks are tried (a brightness threshold
 * for pages on darker surfaces, and an edge-enclosure mask for low-contrast scenes); the largest
 * plausible region of each is reduced to its convex hull and then to the largest inscribed
 * quadrilateral, and the best-scoring quad wins.
 */
object DocumentDetector {
    private const val WORK = 192

    /**
     * [luma] holds one byte per pixel (0..255) with [rowStride] bytes per row, like a camera Y plane.
     * Coordinates in the result are in the input's pixel space.
     */
    fun detect(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): Detection? {
        val scale = max(width, height).toDouble() / WORK
        val w = max(8, (width / scale).toInt())
        val h = max(8, (height / scale).toInt())
        val small = IntArray(w * h)
        // Box-average downscale.
        for (y in 0 until h) {
            val y0 = (y * height / h)
            val y1 = max(y0 + 1, ((y + 1) * height / h))
            for (x in 0 until w) {
                val x0 = (x * width / w)
                val x1 = max(x0 + 1, ((x + 1) * width / w))
                var sum = 0
                var n = 0
                var yy = y0
                while (yy < y1) {
                    val row = yy * rowStride
                    var xx = x0
                    while (xx < x1) {
                        sum += luma[row + xx].toInt() and 0xFF
                        n++
                        xx += 1
                    }
                    yy += 1
                }
                small[y * w + x] = sum / n
            }
        }
        val det = detectSmall(small, w, h) ?: return null
        val sx = width.toDouble() / w
        val sy = height.toDouble() / h
        return det.copy(quad = det.quad.map { Pt((it.x + 0.5) * sx, (it.y + 0.5) * sy) })
    }

    /** Same as [detect] for an ARGB image. */
    fun detectArgb(pixels: IntArray, width: Int, height: Int): Detection? {
        val luma = ByteArray(width * height)
        for (i in pixels.indices) {
            val c = pixels[i]
            luma[i] = (((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8).toByte()
        }
        return detect(luma, width, height)
    }

    internal fun detectSmall(src: IntArray, w: Int, h: Int): Detection? {
        val blurred = boxBlur(boxBlur(src, w, h), w, h)
        val candidates = ArrayList<Detection>()
        val t = otsu(blurred)
        brightMask(blurred, t)?.let { evaluate(it, w, h)?.let(candidates::add) }
        edgeMask(blurred, w, h)?.let { evaluate(it, w, h)?.let(candidates::add) }
        return candidates
            .mapNotNull { d -> edgeContrast(blurred, w, h, d.quad)?.let { d.copy(confidence = d.confidence * it) } }
            .maxByOrNull { it.confidence }
            ?.takeIf { it.confidence > 0.35 }
    }

    /**
     * How strongly the image changes across the quad's edges (0..1). A real page edge separates
     * paper from background; a quad that hugs the frame or sits in texture scores low or null.
     */
    private fun edgeContrast(img: IntArray, w: Int, h: Int, q: Quad): Double? {
        val c = q.corners
        val cx = c.sumOf { it.x } / 4
        val cy = c.sumOf { it.y } / 4
        var total = 0.0
        var samples = 0
        for (e in 0 until 4) {
            val a = c[e]
            val b = c[(e + 1) % 4]
            val len = a.dist(b).coerceAtLeast(1e-6)
            var nx = (b.y - a.y) / len
            var ny = -(b.x - a.x) / len
            val mx = (a.x + b.x) / 2
            val my = (a.y + b.y) / 2
            if ((mx - cx) * nx + (my - cy) * ny < 0) { nx = -nx; ny = -ny } // point outwards
            for (k in 1..9) {
                val t = k / 10.0
                val px = a.x + (b.x - a.x) * t
                val py = a.y + (b.y - a.y) * t
                val ix = (px - nx * 3).toInt()
                val iy = (py - ny * 3).toInt()
                val ox = (px + nx * 3).toInt()
                val oy = (py + ny * 3).toInt()
                if (ix !in 0 until w || iy !in 0 until h || ox !in 0 until w || oy !in 0 until h) continue
                total += abs(img[iy * w + ix] - img[oy * w + ox])
                samples++
            }
        }
        if (samples < 6) return null
        return min(1.0, total / samples / 22.0)
    }

    private fun brightMask(img: IntArray, t: Int): BooleanArray? {
        val m = BooleanArray(img.size) { img[it] > t }
        return m
    }

    /** Pixels enclosed by strong edges (not reachable from the border without crossing an edge). */
    private fun edgeMask(img: IntArray, w: Int, h: Int): BooleanArray? {
        val mag = IntArray(w * h)
        var maxMag = 1
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = -img[i - w - 1] - 2 * img[i - 1] - img[i + w - 1] + img[i - w + 1] + 2 * img[i + 1] + img[i + w + 1]
            val gy = -img[i - w - 1] - 2 * img[i - w] - img[i - w + 1] + img[i + w - 1] + 2 * img[i + w] + img[i + w + 1]
            val m = abs(gx) + abs(gy)
            mag[i] = m
            if (m > maxMag) maxMag = m
        }
        val norm = IntArray(mag.size) { mag[it] * 255 / maxMag }
        val thr = max(otsu(norm), 30)
        var edges = BooleanArray(mag.size) { norm[it] > thr }
        edges = dilate(edges, w, h)
        // Flood the background from the border through non-edge pixels.
        val outside = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        fun push(i: Int) {
            if (!outside[i] && !edges[i]) {
                outside[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        val inside = BooleanArray(w * h) { !outside[it] }
        return if (inside.count { it } < w * h / 12) null else inside
    }

    private fun evaluate(mask0: BooleanArray, w: Int, h: Int): Detection? {
        val mask = erode(dilate(dilate(mask0, w, h), w, h), w, h).let { erode(it, w, h) }
        val comp = largestComponent(mask, w, h) ?: return null
        val area = comp.size.toDouble()
        val frac = area / (w * h)
        if (frac < 0.08 || frac > 0.985) return null
        val member = BooleanArray(w * h)
        for (i in comp) member[i] = true
        val boundary = ArrayList<Pt>()
        for (i in comp) {
            val x = i % w
            val y = i / w
            val edge = x == 0 || y == 0 || x == w - 1 || y == h - 1 ||
                !member[i - 1] || !member[i + 1] || !member[i - w] || !member[i + w]
            if (edge) boundary += Pt(x.toDouble(), y.toDouble())
        }
        val hull = convexHull(boundary)
        if (hull.size < 4) return null
        val hullArea = polygonArea(hull)
        val quadPts = maxAreaQuad(decimate(hull, 48)) ?: return null
        val quad = Quad.ordered(quadPts)
        val quadArea = quad.area
        if (quadArea <= 0) return null
        val hullFit = (quadArea / hullArea).coerceAtMost(1.0)
        val fill = (area / quadArea).coerceAtMost(1.0)
        val angle = quad.minAngle()
        if (angle < 35) return null
        val sizeScore = 0.55 + 0.45 * min(1.0, frac / 0.35)
        val angleScore = min(1.0, (angle - 35) / 40)
        val score = hullFit * hullFit * fill * sizeScore * (0.6 + 0.4 * angleScore)
        return Detection(quad, score)
    }

    private fun largestComponent(mask: BooleanArray, w: Int, h: Int): IntArray? {
        val label = IntArray(w * h)
        var best: IntArray? = null
        var next = 1
        val queue = IntArray(w * h)
        for (start in mask.indices) {
            if (!mask[start] || label[start] != 0) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = next
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                if (x > 0 && mask[i - 1] && label[i - 1] == 0) { label[i - 1] = next; queue[tail++] = i - 1 }
                if (x < w - 1 && mask[i + 1] && label[i + 1] == 0) { label[i + 1] = next; queue[tail++] = i + 1 }
                if (y > 0 && mask[i - w] && label[i - w] == 0) { label[i - w] = next; queue[tail++] = i - w }
                if (y < h - 1 && mask[i + w] && label[i + w] == 0) { label[i + w] = next; queue[tail++] = i + w }
            }
            if (best == null || tail > best.size) best = queue.copyOf(tail)
            next++
        }
        return best
    }

    private fun boxBlur(src: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            var n = 0
            for (dy in -1..1) {
                val yy = y + dy
                if (yy < 0 || yy >= h) continue
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx < 0 || xx >= w) continue
                    s += src[yy * w + xx]
                    n++
                }
            }
            out[y * w + x] = s / n
        }
        return out
    }

    private fun dilate(m: BooleanArray, w: Int, h: Int): BooleanArray = BooleanArray(m.size) { i ->
        val x = i % w
        val y = i / w
        m[i] || (x > 0 && m[i - 1]) || (x < w - 1 && m[i + 1]) || (y > 0 && m[i - w]) || (y < h - 1 && m[i + w])
    }

    private fun erode(m: BooleanArray, w: Int, h: Int): BooleanArray = BooleanArray(m.size) { i ->
        val x = i % w
        val y = i / w
        m[i] && (x == 0 || m[i - 1]) && (x == w - 1 || m[i + 1]) && (y == 0 || m[i - w]) && (y == h - 1 || m[i + w])
    }

    /** Otsu's threshold over 0..255 values. */
    fun otsu(values: IntArray): Int {
        val hist = IntArray(256)
        for (v in values) hist[v.coerceIn(0, 255)]++
        val total = values.size.toDouble()
        var sum = 0.0
        for (i in 0 until 256) sum += i * hist[i].toDouble()
        var sumB = 0.0
        var wB = 0.0
        var best = 0.0
        var threshold = 127
        for (t in 0 until 256) {
            wB += hist[t]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold
    }
}

fun polygonArea(p: List<Pt>): Double {
    var a = 0.0
    for (i in p.indices) {
        val j = (i + 1) % p.size
        a += p[i].x * p[j].y - p[j].x * p[i].y
    }
    return abs(a) / 2
}

/** Andrew's monotone chain; returns the hull counter-clockwise (in y-up terms) without repeats. */
fun convexHull(points: List<Pt>): List<Pt> {
    val pts = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
    if (pts.size < 3) return pts
    fun cross(o: Pt, a: Pt, b: Pt) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val lower = ArrayList<Pt>()
    for (p in pts) {
        while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1)
        lower += p
    }
    val upper = ArrayList<Pt>()
    for (p in pts.asReversed()) {
        while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1)
        upper += p
    }
    lower.removeAt(lower.size - 1)
    upper.removeAt(upper.size - 1)
    return lower + upper
}

/** Keeps at most [max] hull vertices, dropping the ones that turn the least. */
private fun decimate(hull: List<Pt>, max: Int): List<Pt> {
    val p = hull.toMutableList()
    while (p.size > max) {
        var worst = 0
        var worstArea = Double.MAX_VALUE
        for (i in p.indices) {
            val a = p[(i - 1 + p.size) % p.size]
            val b = p[i]
            val c = p[(i + 1) % p.size]
            val area = abs((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x))
            if (area < worstArea) {
                worstArea = area
                worst = i
            }
        }
        p.removeAt(worst)
    }
    return p
}

/** Largest-area quadrilateral with vertices on a convex polygon (O(n³), fine for n ≤ 48). */
fun maxAreaQuad(poly: List<Pt>): List<Pt>? {
    val n = poly.size
    if (n < 4) return null
    fun tri(a: Pt, b: Pt, c: Pt) = abs((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) / 2
    var best = -1.0
    var result: List<Pt>? = null
    for (i in 0 until n) {
        for (k in i + 2 until n) {
            if ((i - k + n) % n < 2) continue
            var bestJ = -1
            var a1 = -1.0
            for (j in i + 1 until k) {
                val t = tri(poly[i], poly[j], poly[k])
                if (t > a1) { a1 = t; bestJ = j }
            }
            var bestL = -1
            var a2 = -1.0
            var l = k + 1
            while (l < i + n) {
                val t = tri(poly[k], poly[l % n], poly[i])
                if (t > a2) { a2 = t; bestL = l % n }
                l++
            }
            if (bestJ < 0 || bestL < 0) continue
            if (a1 + a2 > best) {
                best = a1 + a2
                result = listOf(poly[i], poly[bestJ], poly[k], poly[bestL])
            }
        }
    }
    return result
}
