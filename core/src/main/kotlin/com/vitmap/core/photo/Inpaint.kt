package com.vitmap.core.photo

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Exemplar-based hole filling for the heal and remove tools. The hole is filled from its edge
 * inwards ("onion peel"); each pixel copies the centre of the best-matching patch from the
 * surrounding photo. Candidates that continue where a neighbour's source came from are tried
 * first, so stripes and textures carry on instead of turning to mush.
 */
object Inpaint {
    fun fill(px: IntArray, w: Int, h: Int, hole: BooleanArray, cancelled: () -> Boolean = { false }, seed: Int = 1): IntArray {
        val out = px.copyOf()
        var count = 0
        var minX = w; var maxX = -1; var minY = h; var maxY = -1
        for (i in 0 until w * h) if (hole[i]) {
            count++
            val x = i % w; val y = i / w
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
        }
        if (count == 0 || count == w * h) return out
        val half = if (count > 40_000) 3 else 4
        val area = (2 * half + 1) * (2 * half + 1)
        val samples = (150_000_000L / (count.toLong() * area)).toInt().coerceIn(16, 120)
        val margin = max(24, max(maxX - minX, maxY - minY))
        val sx0 = max(half, minX - margin); val sx1 = min(w - 1 - half, maxX + margin)
        val sy0 = max(half, minY - margin); val sy1 = min(h - 1 - half, maxY + margin)
        val known = BooleanArray(w * h) { !hole[it] }
        val source = IntArray(w * h) { -1 }
        val rnd = Random(seed)

        fun cost(p: Int, q: Int, limit: Long): Long {
            val px0 = p % w; val py0 = p / w
            val qx0 = q % w; val qy0 = q / w
            var sum = 0L
            var n = 0
            for (dy in -half..half) {
                val py = py0 + dy; val qy = qy0 + dy
                if (py < 0 || py >= h || qy < 0 || qy >= h) continue
                for (dx in -half..half) {
                    val pxx = px0 + dx; val qx = qx0 + dx
                    if (pxx < 0 || pxx >= w || qx < 0 || qx >= w) continue
                    val pi = py * w + pxx
                    val qi = qy * w + qx
                    if (!known[pi] || hole[qi]) continue
                    val a = out[pi]; val b = out[qi]
                    val dr = (a shr 16 and 0xFF) - (b shr 16 and 0xFF)
                    val dg = (a shr 8 and 0xFF) - (b shr 8 and 0xFF)
                    val db = (a and 0xFF) - (b and 0xFF)
                    sum += dr * dr + dg * dg + db * db
                    n++
                }
                if (n > 0 && sum / n > limit) return Long.MAX_VALUE
            }
            return if (n < area / 4) Long.MAX_VALUE else sum / n
        }

        var frontier = ArrayList<Int>()
        for (i in 0 until w * h) if (hole[i] && neighbours(i, w, h).any { known[it] }) frontier += i
        val queued = BooleanArray(w * h)
        frontier.forEach { queued[it] = true }
        var steps = 0
        while (frontier.isNotEmpty()) {
            val next = ArrayList<Int>()
            for (p in frontier) {
                if (++steps and 255 == 0 && cancelled()) return out
                var best = -1
                var bestCost = Long.MAX_VALUE
                fun consider(qx: Int, qy: Int) {
                    if (qx < 0 || qy < 0 || qx >= w || qy >= h) return
                    val q = qy * w + qx
                    if (hole[q] || q == p) return
                    val c = cost(p, q, bestCost)
                    if (c < bestCost) { bestCost = c; best = q }
                }
                val px0 = p % w; val py0 = p / w
                // Coherence: continue the copy offset of already-filled or known neighbours.
                for (n in neighbours(p, w, h)) {
                    val s = source[n]
                    if (s >= 0) consider(s % w + (px0 - n % w), s / w + (py0 - n / w))
                }
                repeat(samples) { consider(rnd.nextInt(sx0, sx1 + 1), rnd.nextInt(sy0, sy1 + 1)) }
                if (best >= 0) {
                    var r = 8
                    while (r >= 1) {
                        val bx = best % w; val by = best / w
                        for ((ox, oy) in listOf(r to 0, -r to 0, 0 to r, 0 to -r)) consider(bx + ox, by + oy)
                        r /= 2
                    }
                }
                if (best < 0) best = neighbours(p, w, h).firstOrNull { known[it] } ?: continue
                out[p] = (out[p] and -0x1000000) or (out[best] and 0xFFFFFF)
                known[p] = true
                source[p] = if (hole[best]) source[best] else best
                for (n in neighbours(p, w, h)) if (hole[n] && !known[n] && !queued[n]) { queued[n] = true; next += n }
            }
            frontier = next
        }
        smoothSeams(out, w, h, hole)
        return out
    }

    private fun neighbours(i: Int, w: Int, h: Int): List<Int> {
        val x = i % w; val y = i / w
        val list = ArrayList<Int>(8)
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = x + dx; val ny = y + dy
            if (nx in 0 until w && ny in 0 until h) list += ny * w + nx
        }
        return list
    }

    /** One light 3×3 average over the filled area, so copied pixels don't show hard edges. */
    private fun smoothSeams(px: IntArray, w: Int, h: Int, hole: BooleanArray) {
        val src = px.copyOf()
        for (i in 0 until w * h) {
            if (!hole[i]) continue
            val x = i % w; val y = i / w
            var r = 0; var g = 0; var b = 0; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx; val ny = y + dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                val c = src[ny * w + nx]
                val wgt = if (dx == 0 && dy == 0) 4 else 1
                r += (c shr 16 and 0xFF) * wgt; g += (c shr 8 and 0xFF) * wgt; b += (c and 0xFF) * wgt; n += wgt
            }
            px[i] = (src[i] and -0x1000000) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
    }
}
