package com.vitmap.app.feature.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import com.vitmap.core.scan.PageFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class ExportOptions(
    val quality: ExportQuality = ExportQuality.MEDIUM,
    val pageSize: ExportPageSize = ExportPageSize.FIT,
    val password: String? = null,
    val title: String? = null,
)

object Pdf {
    @Volatile private var ready = false

    fun init(context: Context) {
        if (!ready) {
            PDFBoxResourceLoader.init(context.applicationContext)
            ready = true
        }
    }
}

/**
 * Writes a document to PDF with PdfBox. Photo pages become JPEG images (lossless for black and
 * white, which compresses far better), imported PDF pages are copied as-is so text stays
 * selectable, and annotations are drawn as vectors on top (text and stickers as images).
 */
object PdfExporter {
    suspend fun export(context: Context, doc: ScanDoc, options: ExportOptions, out: OutputStream, progress: (Int, Int) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) {
            Pdf.init(context)
            val repo = ScanRepository.get(context)
            val dir = repo.dir(doc.id)
            val sources = HashMap<String, PDDocument>()
            PDDocument().use { pdf ->
                try {
                    doc.pages.forEachIndexed { i, page ->
                        progress(i, doc.pages.size)
                        val pdPage = when (page.kind) {
                            PageKind.PDF -> {
                                val src = sources.getOrPut(page.pdf!!) { PDDocument.load(File(dir, page.pdf)) }
                                val imported = pdf.importPage(src.getPage(page.pdfIndex.coerceIn(0, src.numberOfPages - 1)))
                                imported.rotation = ((imported.rotation + page.rotation) % 360 + 360) % 360
                                imported
                            }
                            else -> imagePage(context, pdf, doc, page, options)
                        }
                        if (page.annotations.isNotEmpty()) drawAnnotations(pdf, pdPage, page.annotations, dir)
                    }
                    progress(doc.pages.size, doc.pages.size)
                    pdf.documentInformation.title = options.title ?: doc.name
                    pdf.documentInformation.creator = "Vitmap"
                    options.password?.takeIf { it.isNotEmpty() }?.let { pw ->
                        val policy = StandardProtectionPolicy(pw, pw, AccessPermission())
                        policy.encryptionKeyLength = 256
                        pdf.protect(policy)
                    }
                    pdf.save(out)
                } finally {
                    sources.values.forEach { runCatching { it.close() } }
                }
            }
        }

    suspend fun exportToFile(context: Context, doc: ScanDoc, options: ExportOptions, file: File, progress: (Int, Int) -> Unit = { _, _ -> }): File {
        file.parentFile?.mkdirs()
        file.outputStream().use { export(context, doc, options, it, progress) }
        return file
    }

    private suspend fun imagePage(context: Context, pdf: PDDocument, doc: ScanDoc, page: DocPage, options: ExportOptions): PDPage {
        val bmp = PageRenderer.render(context, doc.id, page, if (page.kind == PageKind.BLANK) 800 else options.quality.maxDim)
        val aspect = bmp.height.toFloat() / bmp.width
        val box = when (options.pageSize) {
            ExportPageSize.A4 -> if (aspect >= 1f) PDRectangle.A4 else PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)
            ExportPageSize.LETTER -> if (aspect >= 1f) PDRectangle.LETTER else PDRectangle(PDRectangle.LETTER.height, PDRectangle.LETTER.width)
            ExportPageSize.FIT -> if (aspect >= 1f) PDRectangle(595f, 595f * aspect) else PDRectangle(842f / aspect, 842f)
        }
        val pdPage = PDPage(box)
        pdf.addPage(pdPage)
        if (page.kind == PageKind.BLANK) return pdPage
        val image: PDImageXObject = if (page.pageFilter == PageFilter.BW) LosslessFactory.createFromImage(pdf, bmp) else JPEGFactory.createFromImage(pdf, bmp, options.quality.jpeg)
        val scale = min(box.width / bmp.width, box.height / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        PDPageContentStream(pdf, pdPage, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.drawImage(image, (box.width - w) / 2, (box.height - h) / 2, w, h)
        }
        return pdPage
    }

    private fun drawAnnotations(pdf: PDDocument, page: PDPage, annotations: List<Annot>, dir: File) {
        val (m, w, h) = PdfGeometry.displayTransform(page)
        PDPageContentStream(pdf, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.saveGraphicsState()
            cs.transform(m)
            for (a in annotations) {
                when (a) {
                    is InkAnnot -> {
                        cs.saveGraphicsState()
                        if (a.highlighter) cs.setGraphicsStateParameters(alpha(0.4f))
                        stroke(cs, a.color, a.width * w)
                        polyline(cs, a.points.mapIndexed { i, v -> if (i % 2 == 0) v * w else v * h })
                        cs.stroke()
                        cs.restoreGraphicsState()
                    }
                    is SignAnnot -> {
                        stroke(cs, a.color, a.width * a.w * w)
                        for (s in a.strokes) {
                            polyline(cs, s.mapIndexed { i, v -> if (i % 2 == 0) (a.x + v * a.w) * w else (a.y + v * a.h) * h })
                            cs.stroke()
                        }
                    }
                    is WhiteoutAnnot -> {
                        fill(cs, a.color)
                        cs.addRect(a.x * w, a.y * h, a.w * w, a.h * h)
                        cs.fill()
                    }
                    is ShapeAnnot -> drawShape(cs, a, w, h)
                    is TextAnnot -> {
                        // Rendered at 4 px per point so it stays crisp when zoomed; any script works.
                        val scale = 4f
                        val layout = AnnotPainter.textLayout(a, w * scale)
                        val pad = if (a.background != null) a.size * w * scale * 0.25f else 0f
                        val bmp = Bitmap.createBitmap((layout.width + pad * 2).toInt().coerceAtLeast(1), (layout.height + pad * 2).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        val c = Canvas(bmp)
                        a.background?.let { c.drawRoundRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), pad, pad, AnnotPainter.fillPaint(it)) }
                        c.translate(pad, pad)
                        layout.draw(c)
                        val img = LosslessFactory.createFromImage(pdf, bmp)
                        val x = a.x * w - pad / scale
                        val y = a.y * h - pad / scale
                        drawImageDisplay(cs, img, x, y, bmp.width / scale, bmp.height / scale)
                        bmp.recycle()
                    }
                    is ImageAnnot -> {
                        val bmp = PageRenderer.decodeSampled(File(dir, a.file), 2000) ?: continue
                        val img = if (bmp.hasAlpha()) LosslessFactory.createFromImage(pdf, bmp) else JPEGFactory.createFromImage(pdf, bmp, 0.9f)
                        drawImageDisplay(cs, img, a.x * w, a.y * h, a.w * w, a.h * h)
                    }
                }
            }
            cs.restoreGraphicsState()
        }
    }

    /** Draws an image into a y-down display rectangle. */
    private fun drawImageDisplay(cs: PDPageContentStream, img: PDImageXObject, x: Float, y: Float, w: Float, h: Float) {
        cs.drawImage(img, Matrix(w, 0f, 0f, -h, x, y + h))
    }

    private fun drawShape(cs: PDPageContentStream, a: ShapeAnnot, w: Float, h: Float) {
        val l = min(a.x1, a.x2) * w
        val t = min(a.y1, a.y2) * h
        val r = max(a.x1, a.x2) * w
        val b = max(a.y1, a.y2) * h
        if (a.fill) fill(cs, a.color) else stroke(cs, a.color, a.width * w)
        when (a.shape) {
            ShapeKind.RECT -> cs.addRect(l, t, r - l, b - t)
            ShapeKind.OVAL -> {
                val k = 0.5523f
                val cx = (l + r) / 2
                val cy = (t + b) / 2
                val rx = (r - l) / 2
                val ry = (b - t) / 2
                cs.moveTo(cx + rx, cy)
                cs.curveTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry)
                cs.curveTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy)
                cs.curveTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry)
                cs.curveTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy)
                cs.closePath()
            }
            ShapeKind.LINE, ShapeKind.ARROW -> {
                stroke(cs, a.color, a.width * w)
                val x1 = a.x1 * w; val y1 = a.y1 * h; val x2 = a.x2 * w; val y2 = a.y2 * h
                cs.moveTo(x1, y1)
                cs.lineTo(x2, y2)
                if (a.shape == ShapeKind.ARROW) {
                    val ang = atan2(y2 - y1, x2 - x1)
                    val head = max(a.width * w * 4f, 6f)
                    cs.moveTo(x2 - head * cos(ang - 0.5f), y2 - head * sin(ang - 0.5f))
                    cs.lineTo(x2, y2)
                    cs.lineTo(x2 - head * cos(ang + 0.5f), y2 - head * sin(ang + 0.5f))
                }
                cs.stroke()
                return
            }
        }
        if (a.fill) cs.fill() else cs.stroke()
    }

    /** Smooth stroke through the midpoints of [pts] (x,y pairs), matching the on-screen ink. */
    private fun polyline(cs: PDPageContentStream, pts: List<Float>) {
        if (pts.size < 2) return
        var px = pts[0]
        var py = pts[1]
        cs.moveTo(px, py)
        if (pts.size == 2) {
            cs.lineTo(px + 0.05f, py)
            return
        }
        var i = 2
        while (i + 3 < pts.size) {
            val cx = pts[i]
            val cy = pts[i + 1]
            val mx = (cx + pts[i + 2]) / 2
            val my = (cy + pts[i + 3]) / 2
            // The quadratic segment written as the equivalent cubic.
            cs.curveTo(px + 2f / 3 * (cx - px), py + 2f / 3 * (cy - py), mx + 2f / 3 * (cx - mx), my + 2f / 3 * (cy - my), mx, my)
            px = mx
            py = my
            i += 2
        }
        cs.lineTo(pts[pts.size - 2], pts[pts.size - 1])
    }

    private fun stroke(cs: PDPageContentStream, color: Long, width: Float) {
        val c = color.toInt()
        cs.setStrokingColor((c shr 16 and 0xFF) / 255f, (c shr 8 and 0xFF) / 255f, (c and 0xFF) / 255f)
        cs.setLineWidth(width.coerceAtLeast(0.2f))
        cs.setLineCapStyle(1)
        cs.setLineJoinStyle(1)
    }

    private fun fill(cs: PDPageContentStream, color: Long) {
        val c = color.toInt()
        cs.setNonStrokingColor((c shr 16 and 0xFF) / 255f, (c shr 8 and 0xFF) / 255f, (c and 0xFF) / 255f)
    }

    private fun alpha(a: Float) = PDExtendedGraphicsState().apply {
        strokingAlphaConstant = a
        nonStrokingAlphaConstant = a
    }
}
