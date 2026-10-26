package com.vitmap.app.feature.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import com.vitmap.core.scan.PageFilter
import com.vitmap.core.scan.PageFilters
import com.vitmap.core.scan.Quad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Produces page images: cropped, straightened and filtered photos, rendered PDF pages, blanks. */
object PageRenderer {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pdfLock = Mutex()

    fun forget(docId: String) {
        cache.snapshot().keys.filter { it.startsWith("$docId/") }.forEach { cache.remove(it) }
    }

    /** The finished page (crop, rotation, filter) without annotations, long side at most [maxDim]. */
    suspend fun render(context: Context, docId: String, page: DocPage, maxDim: Int): Bitmap {
        val key = "$docId/${page.id}/${page.revision}/${page.kind}/${page.filter}/${page.rotation}/${page.quad?.hashCode()}/${page.adjust.hashCode()}/$maxDim"
        cache.get(key)?.let { if (!it.isRecycled) return it }
        val bmp = withContext(Dispatchers.Default) {
            val base = when (page.kind) {
                PageKind.IMAGE -> renderImage(context, docId, page, maxDim)
                PageKind.PDF -> renderPdf(context, docId, page, maxDim)
                PageKind.BLANK -> {
                    val w = (maxDim / max(1f, page.blankAspect)).roundToInt()
                    val h = (w * page.blankAspect).roundToInt()
                    Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                }
            }
            rotate(base, page.rotation)
        }
        cache.put(key, bmp)
        return bmp
    }

    /** The original photo, upright, for the crop editor. */
    suspend fun original(context: Context, docId: String, page: DocPage, maxDim: Int): Bitmap? = withContext(Dispatchers.IO) {
        val name = page.image ?: return@withContext null
        decodeSampled(File(ScanRepository.get(context).dir(docId), name), maxDim)
    }

    private suspend fun renderImage(context: Context, docId: String, page: DocPage, maxDim: Int): Bitmap {
        val f = File(ScanRepository.get(context).dir(docId), page.image ?: error("Image page without image"))
        val src = decodeSampled(f, (maxDim * 1.4f).roundToInt().coerceIn(1200, 4000)) ?: error("Couldn't open ${f.name}")
        val quad = page.cropQuad()
        val cropped = if (quad == null) scaleDown(src, maxDim) else warp(src, quad, maxDim)
        if (cropped !== src) src.recycle()
        return applyFilter(cropped, page.pageFilter, page)
    }

    private suspend fun renderPdf(context: Context, docId: String, page: DocPage, maxDim: Int): Bitmap = pdfLock.withLock {
        val f = File(ScanRepository.get(context).dir(docId), page.pdf ?: error("PDF page without source"))
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                r.openPage(page.pdfIndex.coerceIn(0, r.pageCount - 1)).use { p ->
                    val scale = maxDim.toFloat() / max(p.width, p.height)
                    val bmp = Bitmap.createBitmap((p.width * scale).roundToInt().coerceAtLeast(1), (p.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }

    /** Page size in PDF points as displayed (after the page's own rotation), for PDF pages. */
    suspend fun pdfPageSize(file: File, index: Int): Pair<Int, Int> = pdfLock.withLock {
        withContext(Dispatchers.IO) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r -> r.openPage(index).use { it.width to it.height } }
            }
        }
    }

    fun decodeSampled(file: File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return scaleDown(bmp, maxDim)
    }

    fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val s = maxDim.toFloat() / max(src.width, src.height)
        if (s >= 1f) return src
        return Bitmap.createScaledBitmap(src, (src.width * s).roundToInt().coerceAtLeast(1), (src.height * s).roundToInt().coerceAtLeast(1), true)
    }

    /** Perspective-corrects the region inside [quad] (normalised to [src]) into a flat page. */
    fun warp(src: Bitmap, quad: Quad, maxDim: Int): Bitmap {
        val q = quad.map { com.vitmap.core.scan.Pt(it.x * src.width, it.y * src.height) }
        val (ow, oh) = q.outputSize()
        val s = min(1.0, maxDim / max(ow, oh))
        val w = (ow * s).roundToInt().coerceAtLeast(8)
        val h = (oh * s).roundToInt().coerceAtLeast(8)
        val m = Matrix()
        val srcPts = floatArrayOf(
            q.tl.x.toFloat(), q.tl.y.toFloat(), q.tr.x.toFloat(), q.tr.y.toFloat(),
            q.br.x.toFloat(), q.br.y.toFloat(), q.bl.x.toFloat(), q.bl.y.toFloat(),
        )
        val dstPts = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        m.setPolyToPoly(srcPts, 0, dstPts, 0, 4)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return src
        val m = Matrix().apply { postRotate(d.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    fun applyFilter(src: Bitmap, filter: PageFilter, page: DocPage): Bitmap {
        val adjust = page.adjust
        if (filter == PageFilter.ORIGINAL && adjust.isIdentity) return src
        val bmp = if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, true).also { src.recycle() }
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        PageFilters.apply(px, bmp.width, bmp.height, filter, adjust)
        bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return bmp
    }

    /** Page with annotations drawn on top (thumbnails, image export). */
    suspend fun renderWithAnnotations(context: Context, docId: String, page: DocPage, maxDim: Int): Bitmap {
        val base = render(context, docId, page, maxDim)
        if (page.annotations.isEmpty()) return base
        return withContext(Dispatchers.Default) {
            val out = base.copy(Bitmap.Config.ARGB_8888, true)
            AnnotPainter.draw(Canvas(out), page.annotations, out.width.toFloat(), out.height.toFloat(), ScanRepository.get(context).dir(docId))
            out
        }
    }
}

/** Draws annotations onto an Android canvas of the page's size. */
object AnnotPainter {
    private val imageCache = LruCache<String, Bitmap>(24 * 1024 * 1024)

    fun draw(canvas: Canvas, annotations: List<Annot>, w: Float, h: Float, docDir: File) {
        for (a in annotations) drawOne(canvas, a, w, h, docDir)
    }

    fun drawOne(canvas: Canvas, a: Annot, w: Float, h: Float, docDir: File) {
        when (a) {
            is InkAnnot -> {
                val p = smoothPath(a.points, w, h)
                val paint = strokePaint(a.color, a.width * w).apply {
                    if (a.highlighter) {
                        alpha = 110
                        strokeCap = Paint.Cap.SQUARE
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                    }
                }
                canvas.drawPath(p, paint)
            }
            is TextAnnot -> {
                val layout = textLayout(a, w)
                val x = a.x * w
                val y = a.y * h
                a.background?.let {
                    val pad = a.size * w * 0.25f
                    canvas.drawRoundRect(RectF(x - pad, y - pad, x + layout.width + pad, y + layout.height + pad), pad, pad, fillPaint(it))
                }
                canvas.save()
                canvas.translate(x, y)
                layout.draw(canvas)
                canvas.restore()
            }
            is SignAnnot -> {
                val paint = strokePaint(a.color, a.width * a.w * w)
                for (s in a.strokes) {
                    val pts = s.mapIndexed { i, v -> if (i % 2 == 0) a.x + v * a.w else a.y + v * a.h }
                    canvas.drawPath(smoothPath(pts, w, h), paint)
                }
            }
            is ImageAnnot -> {
                val bmp = image(File(docDir, a.file)) ?: return
                canvas.drawBitmap(bmp, null, RectF(a.x * w, a.y * h, (a.x + a.w) * w, (a.y + a.h) * h), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            is WhiteoutAnnot -> canvas.drawRect(a.x * w, a.y * h, (a.x + a.w) * w, (a.y + a.h) * h, fillPaint(a.color))
            is ShapeAnnot -> {
                val paint = if (a.fill) fillPaint(a.color) else strokePaint(a.color, a.width * w)
                val l = min(a.x1, a.x2) * w
                val t = min(a.y1, a.y2) * h
                val r = max(a.x1, a.x2) * w
                val b = max(a.y1, a.y2) * h
                when (a.shape) {
                    ShapeKind.RECT -> canvas.drawRect(l, t, r, b, paint)
                    ShapeKind.OVAL -> canvas.drawOval(RectF(l, t, r, b), paint)
                    ShapeKind.LINE -> canvas.drawLine(a.x1 * w, a.y1 * h, a.x2 * w, a.y2 * h, strokePaint(a.color, a.width * w))
                    ShapeKind.ARROW -> {
                        val sp = strokePaint(a.color, a.width * w)
                        val x1 = a.x1 * w; val y1 = a.y1 * h; val x2 = a.x2 * w; val y2 = a.y2 * h
                        canvas.drawLine(x1, y1, x2, y2, sp)
                        val ang = atan2(y2 - y1, x2 - x1)
                        val head = max(a.width * w * 4f, 12f)
                        val path = Path().apply {
                            moveTo(x2 - head * cos(ang - 0.5f), y2 - head * sin(ang - 0.5f))
                            lineTo(x2, y2)
                            lineTo(x2 - head * cos(ang + 0.5f), y2 - head * sin(ang + 0.5f))
                        }
                        canvas.drawPath(path, sp)
                    }
                }
            }
        }
    }

    fun textLayout(a: TextAnnot, pageWidth: Float): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = a.color.toInt()
            textSize = a.size * pageWidth
            typeface = if (a.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val width = a.text.split('\n').maxOf { paint.measureText(it) }.coerceAtLeast(1f).let { kotlin.math.ceil(it).toInt() }
        return StaticLayout.Builder.obtain(a.text, 0, a.text.length, paint, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
    }

    fun image(file: File): Bitmap? {
        imageCache.get(file.path)?.let { return it }
        val bmp = PageRenderer.decodeSampled(file, 1600) ?: return null
        imageCache.put(file.path, bmp)
        return bmp
    }

    fun strokePaint(color: Long, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toInt()
        style = Paint.Style.STROKE
        strokeWidth = width.coerceAtLeast(0.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun fillPaint(color: Long) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toInt()
        style = Paint.Style.FILL
    }

    /** Quadratic smoothing through stroke midpoints, for natural-looking ink. */
    fun smoothPath(points: List<Float>, w: Float, h: Float): Path {
        val p = Path()
        if (points.size < 2) return p
        p.moveTo(points[0] * w, points[1] * h)
        if (points.size == 2) {
            p.lineTo(points[0] * w + 0.1f, points[1] * h)
            return p
        }
        var i = 2
        while (i + 3 < points.size) {
            val cx = points[i] * w
            val cy = points[i + 1] * h
            val nx = points[i + 2] * w
            val ny = points[i + 3] * h
            p.quadTo(cx, cy, (cx + nx) / 2, (cy + ny) / 2)
            i += 2
        }
        p.lineTo(points[points.size - 2] * w, points[points.size - 1] * h)
        return p
    }
}
