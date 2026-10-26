package com.vitmap.app.feature.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import com.vitmap.app.data.FileSaver
import com.vitmap.app.data.SaveCollection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class PdfPasswordException : IOException("This PDF is password-protected")

data class PdfInfo(
    val pages: Int,
    val title: String,
    val author: String,
    val subject: String,
    val keywords: String,
    val creator: String,
    val producer: String,
    val version: Float,
    val encrypted: Boolean,
    val bytes: Long,
)

enum class NumberPosition(val label: String) {
    BOTTOM_CENTER("Bottom centre"),
    BOTTOM_RIGHT("Bottom right"),
    BOTTOM_LEFT("Bottom left"),
    TOP_CENTER("Top centre"),
    TOP_RIGHT("Top right"),
}

object PdfGeometry {
    /**
     * Maps page display space (points, origin top-left, y down, after the page's /Rotate) to PDF
     * user space, and returns the display width and height.
     */
    fun displayTransform(page: PDPage): Triple<Matrix, Float, Float> {
        val cb = page.cropBox
        val llx = cb.lowerLeftX
        val lly = cb.lowerLeftY
        val w = cb.width
        val h = cb.height
        return when (((page.rotation % 360) + 360) % 360) {
            90 -> Triple(Matrix(0f, 1f, 1f, 0f, llx, lly), h, w)
            180 -> Triple(Matrix(-1f, 0f, 0f, 1f, llx + w, lly), w, h)
            270 -> Triple(Matrix(0f, -1f, -1f, 0f, llx + w, lly + h), h, w)
            else -> Triple(Matrix(1f, 0f, 0f, -1f, llx, lly + h), w, h)
        }
    }
}

/** Whole-file PDF operations for the PDF tools screen. */
object PdfOps {
    private suspend fun local(context: Context, uri: Uri): File = FileSaver.copyToCache(context, uri, folder = "pdf-in")

    private fun open(file: File, password: String?): PDDocument = try {
        PDDocument.load(file, password ?: "")
    } catch (e: InvalidPasswordException) {
        throw PdfPasswordException()
    }

    private fun out(context: Context, name: String): File =
        File(context.cacheDir, "exports/${FileSaver.safeName(name)}").apply { parentFile?.mkdirs() }

    suspend fun info(context: Context, uri: Uri, password: String? = null): PdfInfo = withContext(Dispatchers.IO) {
        Pdf.init(context)
        val f = local(context, uri)
        open(f, password).use { d ->
            val i = d.documentInformation
            PdfInfo(d.numberOfPages, i.title.orEmpty(), i.author.orEmpty(), i.subject.orEmpty(), i.keywords.orEmpty(), i.creator.orEmpty(), i.producer.orEmpty(), d.version, d.isEncrypted, f.length())
        }.also { f.delete() }
    }

    suspend fun setInfo(context: Context, uri: Uri, title: String, author: String, subject: String, keywords: String, name: String): File = withContext(Dispatchers.IO) {
        Pdf.init(context)
        val f = local(context, uri)
        val result = out(context, "$name.pdf")
        open(f, null).use { d ->
            d.documentInformation.apply {
                this.title = title
                this.author = author
                this.subject = subject
                this.keywords = keywords
            }
            d.save(result)
        }
        f.delete()
        result
    }

    /** Merges PDFs in order, keeping bookmarks, links and forms. */
    suspend fun merge(context: Context, uris: List<Uri>, name: String, progress: (Int, Int) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        Pdf.init(context)
        val inputs = uris.mapIndexed { i, u -> progress(i, uris.size); local(context, u) }
        val result = out(context, "$name.pdf")
        val merger = PDFMergerUtility()
        merger.destinationFileName = result.absolutePath
        for (f in inputs) merger.addSource(f)
        try {
            merger.mergeDocuments(null)
        } catch (e: InvalidPasswordException) {
            throw PdfPasswordException()
        } finally {
            inputs.forEach { it.delete() }
        }
        progress(uris.size, uris.size)
        result
    }

    /** Parses "1-3, 5, 8-" into zero-based page ranges. */
    fun parseRanges(spec: String, pageCount: Int): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (part in spec.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
            val r = if ('-' in part) {
                val (a, b) = part.split('-', limit = 2)
                val start = a.trim().toIntOrNull() ?: 1
                val end = b.trim().toIntOrNull() ?: pageCount
                start..end
            } else {
                val n = part.toIntOrNull() ?: continue
                n..n
            }
            val first = r.first.coerceIn(1, pageCount)
            val last = r.last.coerceIn(1, pageCount)
            if (first <= last) out += (first - 1)..(last - 1)
        }
        return out
    }

    /** Writes each range (zero-based pages) into its own PDF. */
    suspend fun split(context: Context, uri: Uri, ranges: List<IntRange>, baseName: String, password: String? = null): List<File> = withContext(Dispatchers.IO) {
        Pdf.init(context)
        val f = local(context, uri)
        val files = ArrayList<File>()
        open(f, password).use { src ->
            src.isAllSecurityToBeRemoved = true
            ranges.forEachIndexed { i, r ->
                PDDocument().use { dst ->
                    for (p in r) if (p < src.numberOfPages) dst.importPage(src.getPage(p))
                    val label = if (r.first == r.last) "p${r.first + 1}" else "p${r.first + 1}-${r.last + 1}"
                    val file = out(context, "$baseName ($label).pdf")
                    dst.save(file)
                    files += file
                }
            }
        }
        f.delete()
        files
    }

    /** One new PDF with the given zero-based pages, in that order. */
    suspend fun extract(context: Context, uri: Uri, pages: List<Int>, name: String, password: String? = null): File = withContext(Dispatchers.IO) {
        Pdf.init(context)
        val f = local(context, uri)
        val result = out(context, "$name.pdf")
        open(f, password).use { src ->
            src.isAllSecurityToBeRemoved = true
            PDDocument().use { dst ->
                for (p in pages) if (p in 0 until src.numberOfPages) dst.importPage(src.getPage(p))
                if (dst.numberOfPages == 0) throw IOException("No pages matched")
                dst.save(result)
            }
        }
        f.delete()
        result
    }

    suspend fun extractText(context: Context, uri: Uri, password: String? = null): String = withContext(Dispatchers.IO) {
        Pdf.init(context)
        val f = local(context, uri)
        try {
            open(f, password).use { d -> PDFTextStripper().apply { sortByPosition = true }.getText(d) }
        } finally {
            f.delete()
        }
    }

    suspend fun protect(context: Context, uri: Uri, userPassword: String, ownerPassword: String, allowPrint: Boolean, allowCopy: Boolean, allowEdit: Boolean, name: String): File =
        withContext(Dispatchers.IO) {
            Pdf.init(context)
            val f = local(context, uri)
            val result = out(context, "$name.pdf")
            open(f, null).use { d ->
                val perms = AccessPermission().apply {
                    setCanPrint(allowPrint)
                    setCanExtractContent(allowCopy)
                    setCanModify(allowEdit)
                    setCanModifyAnnotations(allowEdit)
                    setCanFillInForm(allowEdit)
                }
                val policy = StandardProtectionPolicy(ownerPassword.ifEmpty { userPassword }, userPassword, perms)
                policy.encryptionKeyLength = 256
                d.protect(policy)
                d.save(result)
            }
            f.delete()
            result
        }

    suspend fun unlock(context: Context, uri: Uri, password: String, name: String): File = withContext(Dispatchers.IO) {
        Pdf.init(context)
        val f = local(context, uri)
        val result = out(context, "$name.pdf")
        open(f, password).use { d ->
            d.isAllSecurityToBeRemoved = true
            d.save(result)
        }
        f.delete()
        result
    }

    suspend fun pageNumbers(context: Context, uri: Uri, position: NumberPosition, startAt: Int, pattern: String, sizePt: Float, name: String): File = withContext(Dispatchers.IO) {
        Pdf.init(context)
        val f = local(context, uri)
        val result = out(context, "$name.pdf")
        open(f, null).use { d ->
            val font = PDType1Font.HELVETICA
            val total = d.numberOfPages
            for ((i, page) in d.pages.withIndex()) {
                val label = pattern.replace("{n}", (startAt + i).toString()).replace("{total}", (startAt + total - 1).toString())
                val safe = label.filter { it.code in 32..126 }
                val (m, w, h) = PdfGeometry.displayTransform(page)
                val tw = font.getStringWidth(safe) / 1000f * sizePt
                val margin = 28f
                val x = when (position) {
                    NumberPosition.BOTTOM_LEFT -> margin
                    NumberPosition.BOTTOM_RIGHT, NumberPosition.TOP_RIGHT -> w - margin - tw
                    else -> (w - tw) / 2
                }
                val y = when (position) {
                    NumberPosition.TOP_CENTER, NumberPosition.TOP_RIGHT -> margin + sizePt
                    else -> h - margin
                }
                PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.saveGraphicsState()
                    cs.transform(m)
                    cs.beginText()
                    cs.setFont(font, sizePt)
                    cs.setNonStrokingColor(0.2f, 0.2f, 0.2f)
                    // Flip the text back upright inside the y-down display space.
                    cs.setTextMatrix(Matrix(1f, 0f, 0f, -1f, x, y))
                    cs.showText(safe)
                    cs.endText()
                    cs.restoreGraphicsState()
                }
            }
            d.save(result)
        }
        f.delete()
        result
    }

    /** Diagonal text watermark on every page; rendered as an image so any script works. */
    suspend fun watermark(context: Context, uri: Uri, text: String, opacity: Float, color: Long, name: String): File = withContext(Dispatchers.IO) {
        Pdf.init(context)
        val f = local(context, uri)
        val result = out(context, "$name.pdf")
        open(f, null).use { d ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color.toInt()
                textSize = 220f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val tw = paint.measureText(text).coerceAtLeast(1f)
            val fm = paint.fontMetrics
            val bmp = Bitmap.createBitmap(tw.roundToInt() + 40, (fm.descent - fm.ascent).roundToInt() + 40, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawText(text, 20f, 20f - fm.ascent, paint)
            val img = LosslessFactory.createFromImage(d, bmp)
            val gs = PDExtendedGraphicsState().apply {
                nonStrokingAlphaConstant = opacity
                strokingAlphaConstant = opacity
            }
            for (page in d.pages) {
                val (m, w, h) = PdfGeometry.displayTransform(page)
                val phi = -atan2(h, w)
                val iw = kotlin.math.hypot(w, h) * 0.7f
                val ih = iw * bmp.height / bmp.width
                val cx = w / 2
                val cy = h / 2
                val c = cos(phi)
                val s = sin(phi)
                val matrix = Matrix(iw * c, iw * s, ih * s, -ih * c, cx - iw / 2 * c - ih / 2 * s, cy - iw / 2 * s + ih / 2 * c)
                PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.saveGraphicsState()
                    cs.transform(m)
                    cs.setGraphicsStateParameters(gs)
                    cs.drawImage(img, matrix)
                    cs.restoreGraphicsState()
                }
            }
            bmp.recycle()
            d.save(result)
        }
        f.delete()
        result
    }

    /** Renders every page to an image in Pictures/Vitmap/<name>. Returns how many were saved. */
    suspend fun toImages(context: Context, uri: Uri, dpi: Int, png: Boolean, name: String, progress: (Int, Int) -> Unit): Int = withContext(Dispatchers.IO) {
        val f = local(context, uri)
        var saved = 0
        try {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    for (i in 0 until r.pageCount) {
                        progress(i, r.pageCount)
                        r.openPage(i).use { p ->
                            val scale = dpi / 72f
                            val bmp = Bitmap.createBitmap((p.width * scale).roundToInt().coerceAtLeast(1), (p.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val file = "${FileSaver.safeName(name)} - ${i + 1}.${if (png) "png" else "jpg"}"
                            val u = FileSaver.save(context, SaveCollection.PICTURES, file, if (png) "image/png" else "image/jpeg", FileSaver.safeName(name)) { out ->
                                bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, out)
                            }
                            if (u != null) saved++
                            bmp.recycle()
                        }
                    }
                    progress(r.pageCount, r.pageCount)
                }
            }
        } catch (e: SecurityException) {
            throw PdfPasswordException()
        } finally {
            f.delete()
        }
        saved
    }
}
