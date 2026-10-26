package com.vitmap.app.feature.photo

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import com.vitmap.core.photo.Masks
import com.vitmap.core.photo.PhotoPipeline
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Turns a base photo plus a [PhotoEdit] into pixels. Works at any resolution (preview or export). */
object PhotoRenderer {
    // ---------------------------------------------------------------- geometry

    /** Output size and the matrix mapping base pixels to output pixels for [g]. */
    fun geometryMatrix(w: Int, h: Int, g: Geometry, withCrop: Boolean = true): Triple<Matrix, Int, Int> {
        val q = ((g.quarter % 4) + 4) % 4
        val rw = if (q % 2 == 1) h else w
        val rh = if (q % 2 == 1) w else h
        val m = Matrix()
        m.postTranslate(-w / 2f, -h / 2f)
        m.postScale(if (g.flipH) -1f else 1f, if (g.flipV) -1f else 1f)
        m.postRotate(q * 90f + g.straighten)
        // Zoom so a straightened photo still fills the frame without empty corners.
        val th = Math.toRadians(abs(g.straighten).toDouble())
        val cover = max((rw * cos(th) + rh * sin(th)) / rw, (rw * sin(th) + rh * cos(th)) / rh).toFloat()
        val kv = g.vertical / 100f * 0.18f
        val kh = g.horizontal / 100f * 0.18f
        val keystone = 1f / (1f - 2f * max(abs(kv), abs(kh))).coerceAtLeast(0.5f)
        m.postScale(cover * keystone, cover * keystone)
        m.postTranslate(rw / 2f, rh / 2f)
        if (kv != 0f || kh != 0f) {
            val src = floatArrayOf(0f, 0f, rw.toFloat(), 0f, rw.toFloat(), rh.toFloat(), 0f, rh.toFloat())
            val dx = abs(kv) * rw
            val dy = abs(kh) * rh
            val dst = src.copyOf()
            // Vertical keystone narrows the top (or bottom); horizontal narrows the left (or right).
            if (kv > 0) { dst[0] += dx; dst[2] -= dx } else if (kv < 0) { dst[6] += dx; dst[4] -= dx }
            if (kh > 0) { dst[1] += dy; dst[7] -= dy } else if (kh < 0) { dst[3] += dy; dst[5] -= dy }
            val p = Matrix()
            p.setPolyToPoly(src, 0, dst, 0, 4)
            m.postConcat(p)
        }
        if (!withCrop) return Triple(m, rw, rh)
        val cl = (g.left * rw).roundToInt().coerceIn(0, rw - 1)
        val ct = (g.top * rh).roundToInt().coerceIn(0, rh - 1)
        val cw = ((g.right - g.left) * rw).roundToInt().coerceIn(1, rw - cl)
        val ch = ((g.bottom - g.top) * rh).roundToInt().coerceIn(1, rh - ct)
        m.postTranslate(-cl.toFloat(), -ct.toFloat())
        return Triple(m, cw, ch)
    }

    fun geometry(src: Bitmap, g: Geometry, withCrop: Boolean = true): Bitmap {
        if (g.isIdentity) return src
        val (m, ow, oh) = geometryMatrix(src.width, src.height, g, withCrop)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    // ---------------------------------------------------------------- full render

    data class Inputs(
        val base: Bitmap,
        /** Subject mask (alpha = confidence) at any size, or null. */
        val subject: Bitmap?,
        val projectDir: File,
    )

    fun render(inputs: Inputs, edit: PhotoEdit, includeOverlays: Boolean = true, includeFrame: Boolean = true): Bitmap {
        var img = geometry(inputs.base, edit.geometry).let { if (it === inputs.base) it.copy(Bitmap.Config.ARGB_8888, true) else it }
        val w = img.width
        val h = img.height
        val px = IntArray(w * h)
        img.getPixels(px, 0, w, 0, 0, w, h)
        var pixels = px
        if (edit.background.mode != BackgroundMode.KEEP && inputs.subject != null) pixels = background(pixels, w, h, inputs, edit)
        pixels = PhotoPipeline.render(pixels, w, h, edit.adjust)
        for (local in edit.locals) {
            val mask = Masks.render(local.shape, w, h)
            val top = PhotoPipeline.render(pixels, w, h, local.adjust)
            pixels = Masks.blend(pixels, top, mask, local.amount)
        }
        if (!img.isMutable) img = img.copy(Bitmap.Config.ARGB_8888, true)
        img.setPixels(pixels, 0, w, 0, 0, w, h)
        if (includeOverlays && edit.overlays.isNotEmpty()) drawOverlays(Canvas(img), w, h, edit.overlays, inputs.projectDir)
        return if (includeFrame) frame(img, edit.frame) else img
    }

    /** Subject confidence at the edited geometry, softened by the edge setting. */
    fun subjectAlpha(inputs: Inputs, edit: PhotoEdit, w: Int, h: Int): FloatArray {
        val subject = inputs.subject ?: return FloatArray(w * h) { 1f }
        val scaled = Bitmap.createScaledBitmap(subject, inputs.base.width, inputs.base.height, true)
        val geo = geometry(scaled, edit.geometry)
        val sized = if (geo.width != w || geo.height != h) Bitmap.createScaledBitmap(geo, w, h, true) else geo
        val px = IntArray(w * h)
        sized.getPixels(px, 0, w, 0, 0, w, h)
        val alpha = FloatArray(w * h) { (px[it] ushr 24) / 255f }
        val radius = (edit.background.edge / 100f * min(w, h) * 0.008f).roundToInt()
        if (radius > 0) PhotoPipeline.boxBlur(alpha, w, h, radius, passes = 2)
        return alpha
    }

    private fun background(px: IntArray, w: Int, h: Int, inputs: Inputs, edit: PhotoEdit): IntArray {
        val bgSpec = edit.background
        val a = subjectAlpha(inputs, edit, w, h)
        val bg: IntArray = when (bgSpec.mode) {
            BackgroundMode.TRANSPARENT -> IntArray(w * h)
            BackgroundMode.COLOR -> IntArray(w * h) { bgSpec.color.toInt() }
            BackgroundMode.GRAY -> IntArray(w * h) { i ->
                val c = px[i]
                val l = ((c shr 16 and 0xFF) * 54 + (c shr 8 and 0xFF) * 183 + (c and 0xFF) * 19) shr 8
                (c and -0x1000000) or (l shl 16) or (l shl 8) or l
            }
            BackgroundMode.BLUR -> blurred(px, w, h, bgSpec.blur / 100f)
            BackgroundMode.IMAGE -> bgSpec.image?.let { name -> imageCover(File(inputs.projectDir, name), w, h) } ?: IntArray(w * h) { bgSpec.color.toInt() }
            BackgroundMode.KEEP -> return px
        }
        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val k = a[i].coerceIn(0f, 1f)
            val f = px[i]
            val b = bg[i]
            val fa = (f ushr 24) * k
            val ba = (b ushr 24) * (1 - k)
            val oa = fa + ba
            if (oa <= 0f) { out[i] = 0; continue }
            fun ch(s: Int) = ((((f shr s) and 0xFF) * fa + ((b shr s) and 0xFF) * ba) / oa).roundToInt().coerceIn(0, 255)
            out[i] = (oa.roundToInt().coerceIn(0, 255) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return out
    }

    /** A strong, fast blur: shrink, blur the small copy, enlarge with filtering. */
    private fun blurred(px: IntArray, w: Int, h: Int, amount: Float): IntArray {
        val src = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val factor = (4 + amount * 20).roundToInt()
        val sw = max(1, w / factor)
        val sh = max(1, h / factor)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val sp = IntArray(sw * sh)
        small.getPixels(sp, 0, sw, 0, 0, sw, sh)
        val planes = Array(3) { c -> FloatArray(sw * sh) { i -> ((sp[i] shr (16 - c * 8)) and 0xFF).toFloat() } }
        for (p in planes) PhotoPipeline.boxBlur(p, sw, sh, 2, passes = 3)
        for (i in sp.indices) sp[i] = (0xFF shl 24) or (planes[0][i].roundToInt().coerceIn(0, 255) shl 16) or (planes[1][i].roundToInt().coerceIn(0, 255) shl 8) or planes[2][i].roundToInt().coerceIn(0, 255)
        small.setPixels(sp, 0, sw, 0, 0, sw, sh)
        val big = Bitmap.createScaledBitmap(small, w, h, true)
        val out = IntArray(w * h)
        big.getPixels(out, 0, w, 0, 0, w, h)
        return out
    }

    private fun imageCover(file: File, w: Int, h: Int): IntArray? {
        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val s = max(w / bmp.width.toFloat(), h / bmp.height.toFloat())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply {
            postScale(s, s)
            postTranslate((w - bmp.width * s) / 2f, (h - bmp.height * s) / 2f)
        }
        Canvas(out).drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return IntArray(w * h).also { out.getPixels(it, 0, w, 0, 0, w, h) }
    }

    // ---------------------------------------------------------------- overlays

    fun typeface(font: TextFont, bold: Boolean, italic: Boolean): Typeface {
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val family = when (font) {
            TextFont.SANS -> "sans-serif"
            TextFont.SERIF -> "serif"
            TextFont.MONO -> "monospace"
            TextFont.CONDENSED -> "sans-serif-condensed"
            TextFont.ROUNDED -> "sans-serif-medium"
            TextFont.HANDWRITING -> "cursive"
            TextFont.DISPLAY -> "sans-serif-black"
        }
        return Typeface.create(family, style)
    }

    private val imageCache = object : android.util.LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun drawOverlays(canvas: Canvas, w: Int, h: Int, overlays: List<Overlay>, dir: File) {
        val s = min(w, h).toFloat()
        for (o in overlays) {
            canvas.save()
            canvas.translate(o.x * w, o.y * h)
            canvas.rotate(o.rotation)
            when (o) {
                is Overlay.TextLayer -> drawText(canvas, o, s)
                is Overlay.StickerLayer -> {
                    val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = o.scale * s; textAlign = Paint.Align.CENTER; alpha = (o.opacity * 255).roundToInt() }
                    val fm = p.fontMetrics
                    canvas.drawText(o.emoji, 0f, -(fm.ascent + fm.descent) / 2f, p)
                }
                is Overlay.ShapeLayer -> drawShape(canvas, o, s)
                is Overlay.ImageLayer -> {
                    val key = File(dir, o.file).absolutePath
                    val bmp = imageCache.get(key) ?: android.graphics.BitmapFactory.decodeFile(key)?.also { imageCache.put(key, it) }
                    if (bmp != null) {
                        val bh = o.scale * s
                        val bw = bh * o.aspect
                        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
                            alpha = (o.opacity * 255).roundToInt()
                            blend(this, o.blend)
                        }
                        canvas.drawBitmap(bmp, null, RectF(-bw / 2, -bh / 2, bw / 2, bh / 2), p)
                    }
                }
                is Overlay.DrawingLayer -> {
                    canvas.scale(o.scale, o.scale)
                    canvas.translate(-w / 2f, -h / 2f)
                    val layer = canvas.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), (o.opacity * 255).roundToInt())
                    for (st in o.strokes) drawStroke(canvas, st, w, h, s)
                    canvas.restoreToCount(layer)
                }
            }
            canvas.restore()
        }
    }

    private fun blend(p: Paint, mode: BlendMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            p.blendMode = when (mode) {
                BlendMode.NORMAL -> null
                BlendMode.MULTIPLY -> android.graphics.BlendMode.MULTIPLY
                BlendMode.SCREEN -> android.graphics.BlendMode.SCREEN
                BlendMode.OVERLAY -> android.graphics.BlendMode.OVERLAY
                BlendMode.SOFT_LIGHT -> android.graphics.BlendMode.SOFT_LIGHT
                BlendMode.DARKEN -> android.graphics.BlendMode.DARKEN
                BlendMode.LIGHTEN -> android.graphics.BlendMode.LIGHTEN
                BlendMode.DIFFERENCE -> android.graphics.BlendMode.DIFFERENCE
                BlendMode.COLOR -> android.graphics.BlendMode.COLOR
                BlendMode.LUMINOSITY -> android.graphics.BlendMode.LUMINOSITY
            }
        } else {
            val pd = when (mode) {
                BlendMode.MULTIPLY -> PorterDuff.Mode.MULTIPLY
                BlendMode.SCREEN -> PorterDuff.Mode.SCREEN
                BlendMode.OVERLAY -> PorterDuff.Mode.OVERLAY
                BlendMode.DARKEN -> PorterDuff.Mode.DARKEN
                BlendMode.LIGHTEN -> PorterDuff.Mode.LIGHTEN
                else -> null
            }
            p.xfermode = pd?.let { PorterDuffXfermode(it) }
        }
    }

    fun textPaint(o: Overlay.TextLayer, s: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = o.scale * s
        typeface = typeface(o.font, o.bold, o.italic)
        letterSpacing = o.letterSpacing
        color = o.color.toInt()
        alpha = (o.opacity * (o.color ushr 24 and 0xFF)).roundToInt()
        textAlign = when (o.align) { 0 -> Paint.Align.LEFT; 2 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
    }

    /** Size of a text layer's box in pixels, for hit-testing and handles. */
    fun textBounds(o: Overlay.TextLayer, s: Float): RectF {
        val p = textPaint(o, s)
        val lines = o.text.ifEmpty { " " }.split('\n')
        val lh = p.fontSpacing
        val width = lines.maxOf { p.measureText(it) }
        val height = lh * lines.size
        val pad = p.textSize * 0.25f
        return RectF(-width / 2 - pad, -height / 2 - pad, width / 2 + pad, height / 2 + pad)
    }

    private fun drawText(canvas: Canvas, o: Overlay.TextLayer, s: Float) {
        val p = textPaint(o, s)
        val lines = o.text.split('\n')
        val lh = p.fontSpacing
        val box = textBounds(o, s)
        o.background?.let { bg ->
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg.toInt(); alpha = (o.opacity * (bg ushr 24 and 0xFF)).roundToInt() }
            canvas.drawRoundRect(box, p.textSize * 0.25f, p.textSize * 0.25f, bp)
        }
        val xFor = when (o.align) { 0 -> box.left + p.textSize * 0.25f; 2 -> box.right - p.textSize * 0.25f; else -> 0f }
        val firstBaseline = -lh * lines.size / 2f - p.fontMetrics.ascent
        if (o.shadow) p.setShadowLayer(p.textSize * 0.08f, 0f, p.textSize * 0.04f, Color.argb((150 * o.opacity).roundToInt(), 0, 0, 0))
        for ((i, line) in lines.withIndex()) {
            val y = firstBaseline + i * lh
            if (o.curve != 0f && lines.size == 1) {
                drawCurved(canvas, line, p, o)
                continue
            }
            o.outline?.let { oc ->
                val sp = TextPaint(p).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = textSize * o.outlineWidth
                    strokeJoin = Paint.Join.ROUND
                    color = oc.toInt()
                    alpha = (o.opacity * (oc ushr 24 and 0xFF)).roundToInt()
                }
                canvas.drawText(line, xFor, y, sp)
                p.clearShadowLayer()
            }
            canvas.drawText(line, xFor, y, p)
        }
    }

    private fun drawCurved(canvas: Canvas, line: String, p: TextPaint, o: Overlay.TextLayer) {
        val width = p.measureText(line)
        val sweep = (o.curve / 100f * 300f).coerceIn(-300f, 300f).let { if (abs(it) < 1f) 1f else it }
        val radius = width / Math.toRadians(abs(sweep).toDouble()).toFloat()
        val path = Path()
        val oval = if (sweep > 0) RectF(-radius, 0f, radius, 2 * radius) else RectF(-radius, -2 * radius, radius, 0f)
        if (sweep > 0) path.addArc(oval, 270f - sweep / 2, sweep) else path.addArc(oval, 90f - sweep / 2, sweep)
        val cp = TextPaint(p).apply { textAlign = Paint.Align.CENTER }
        o.outline?.let { oc ->
            canvas.drawTextOnPath(line, path, 0f, 0f, TextPaint(cp).apply { style = Paint.Style.STROKE; strokeWidth = textSize * o.outlineWidth; color = oc.toInt() })
        }
        canvas.drawTextOnPath(line, path, 0f, if (sweep > 0) p.textSize / 3f else -p.textSize / 3f, cp)
    }

    private fun drawShape(canvas: Canvas, o: Overlay.ShapeLayer, s: Float) {
        val bh = o.scale * s
        val bw = bh * o.aspect
        val r = RectF(-bw / 2, -bh / 2, bw / 2, bh / 2)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = o.color.toInt()
            alpha = (o.opacity * (o.color ushr 24 and 0xFF)).roundToInt()
            style = if (o.fill && o.kind != ShapeKind.LINE && o.kind != ShapeKind.ARROW) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = o.strokeWidth * s
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        when (o.kind) {
            ShapeKind.RECT -> canvas.drawRoundRect(r, p.strokeWidth, p.strokeWidth, p)
            ShapeKind.ELLIPSE -> canvas.drawOval(r, p)
            ShapeKind.LINE -> canvas.drawLine(r.left, 0f, r.right, 0f, p)
            ShapeKind.ARROW -> {
                canvas.drawLine(r.left, 0f, r.right, 0f, p)
                val head = max(p.strokeWidth * 3, bw * 0.12f)
                val path = Path().apply {
                    moveTo(r.right - head, -head * 0.7f)
                    lineTo(r.right, 0f)
                    lineTo(r.right - head, head * 0.7f)
                }
                canvas.drawPath(path, p)
            }
            ShapeKind.STAR -> canvas.drawPath(starPath(r), p)
            ShapeKind.HEART -> canvas.drawPath(heartPath(r), p)
        }
    }

    private fun starPath(r: RectF): Path = Path().apply {
        val cx = r.centerX(); val cy = r.centerY()
        val outer = min(r.width(), r.height()) / 2
        val inner = outer * 0.42f
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) outer else inner
            val a = Math.toRadians((i * 36 - 90).toDouble())
            val x = cx + rad * cos(a).toFloat(); val y = cy + rad * sin(a).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    private fun heartPath(r: RectF): Path = Path().apply {
        val h = r.height()
        moveTo(r.centerX(), r.top + h * 0.3f)
        cubicTo(r.centerX(), r.top, r.left, r.top, r.left, r.top + h * 0.35f)
        cubicTo(r.left, r.top + h * 0.65f, r.centerX(), r.top + h * 0.8f, r.centerX(), r.bottom)
        cubicTo(r.centerX(), r.top + h * 0.8f, r.right, r.top + h * 0.65f, r.right, r.top + h * 0.35f)
        cubicTo(r.right, r.top, r.centerX(), r.top, r.centerX(), r.top + h * 0.3f)
        close()
    }

    fun drawStroke(canvas: Canvas, st: Stroke, w: Int, h: Int, s: Float) {
        val pts = st.points
        if (pts.size < 2) return
        val width = st.width * s
        val path = Path().apply {
            moveTo(pts[0] * w, pts[1] * h)
            var i = 2
            while (i + 3 < pts.size) {
                // Smooth through midpoints.
                val x1 = pts[i] * w; val y1 = pts[i + 1] * h
                val x2 = pts[i + 2] * w; val y2 = pts[i + 3] * h
                quadTo(x1, y1, (x1 + x2) / 2, (y1 + y2) / 2)
                i += 2
            }
            if (pts.size >= 4) lineTo(pts[pts.size - 2] * w, pts[pts.size - 1] * h) else lineTo(pts[0] * w + 0.1f, pts[1] * h)
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = st.color.toInt()
        }
        if (st.erase) {
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            canvas.drawPath(path, p)
            return
        }
        when (st.kind) {
            BrushKind.PEN -> canvas.drawPath(path, p)
            BrushKind.MARKER -> canvas.drawPath(path, p.apply { alpha = (alpha * 0.85f).roundToInt(); strokeCap = Paint.Cap.SQUARE })
            BrushKind.HIGHLIGHTER -> canvas.drawPath(path, p.apply { alpha = (alpha * 0.35f).roundToInt(); strokeCap = Paint.Cap.BUTT; strokeWidth = width * 2.2f })
            BrushKind.NEON -> {
                canvas.drawPath(path, Paint(p).apply { strokeWidth = width * 2.6f; maskFilter = BlurMaskFilter(width * 1.4f, BlurMaskFilter.Blur.NORMAL) })
                canvas.drawPath(path, Paint(p).apply { color = Color.WHITE; strokeWidth = width * 0.45f })
            }
            BrushKind.SPRAY -> {
                val rnd = Random(pts.size * 31 + st.color.toInt())
                val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = st.color.toInt(); alpha = 160 }
                val measure = android.graphics.PathMeasure(path, false)
                val pos = FloatArray(2)
                var d = 0f
                while (d < measure.length) {
                    measure.getPosTan(d, pos, null)
                    repeat(6) {
                        val a = rnd.nextFloat() * 6.2832f
                        val rr = rnd.nextFloat() * width * 1.2f
                        canvas.drawCircle(pos[0] + cos(a) * rr, pos[1] + sin(a) * rr, max(1f, width * 0.06f), dot)
                    }
                    d += max(1f, width * 0.25f)
                }
            }
        }
    }

    // ---------------------------------------------------------------- frames

    fun frame(img: Bitmap, f: Frame): Bitmap {
        if (f.kind == FrameKind.NONE) return img
        val s = min(img.width, img.height).toFloat()
        val b = (f.size * s).roundToInt().coerceAtLeast(1)
        val r = f.radius * s
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        return when (f.kind) {
            FrameKind.SOLID, FrameKind.ROUNDED -> {
                val out = Bitmap.createBitmap(img.width + 2 * b, img.height + 2 * b, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                if (f.kind == FrameKind.ROUNDED) c.drawRoundRect(RectF(0f, 0f, out.width.toFloat(), out.height.toFloat()), r + b, r + b, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = f.color.toInt() })
                else c.drawColor(f.color.toInt())
                drawRounded(c, img, RectF(b.toFloat(), b.toFloat(), (b + img.width).toFloat(), (b + img.height).toFloat()), if (f.kind == FrameKind.ROUNDED) r else 0f, paint)
                out
            }
            FrameKind.POLAROID -> {
                val side = (s * 0.05f).roundToInt()
                val out = Bitmap.createBitmap(img.width + 2 * side, img.height + side + (s * 0.2f).roundToInt(), Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                c.drawColor(f.color.toInt())
                c.drawBitmap(img, side.toFloat(), side.toFloat(), paint)
                out
            }
            FrameKind.FILM -> {
                val band = (s * 0.09f).roundToInt()
                val out = Bitmap.createBitmap(img.width, img.height + 2 * band, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                c.drawColor(Color.rgb(18, 18, 18))
                c.drawBitmap(img, 0f, band.toFloat(), paint)
                val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(235, 225, 205) }
                val hw = band * 0.45f; val hh = band * 0.5f
                var x = hw * 0.6f
                while (x + hw < out.width) {
                    c.drawRoundRect(RectF(x, (band - hh) / 2, x + hw, (band + hh) / 2), hw * 0.2f, hw * 0.2f, hole)
                    c.drawRoundRect(RectF(x, out.height - (band + hh) / 2, x + hw, out.height - (band - hh) / 2), hw * 0.2f, hw * 0.2f, hole)
                    x += hw * 1.9f
                }
                out
            }
            FrameKind.SHADOW -> {
                val pad = (s * 0.08f).roundToInt()
                val out = Bitmap.createBitmap(img.width + 2 * pad, img.height + 2 * pad, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                c.drawColor(f.color.toInt())
                val rect = RectF(pad.toFloat(), pad.toFloat(), (pad + img.width).toFloat(), (pad + img.height).toFloat())
                val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(pad * 0.45f, 0f, pad * 0.18f, Color.argb(110, 0, 0, 0)) }
                c.drawRoundRect(rect, r, r, shadow)
                drawRounded(c, img, rect, r, paint)
                out
            }
            FrameKind.NONE -> img
        }
    }

    private fun drawRounded(c: Canvas, img: Bitmap, dst: RectF, radius: Float, paint: Paint) {
        if (radius <= 0f) {
            c.drawBitmap(img, null, dst, paint)
            return
        }
        val save = c.save()
        c.clipPath(Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) })
        c.drawBitmap(img, null, dst, paint)
        c.restoreToCount(save)
    }
}
