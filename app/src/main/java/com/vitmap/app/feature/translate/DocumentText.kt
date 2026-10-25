package com.vitmap.app.feature.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

data class LoadedDocument(val name: String, val paragraphs: List<String>, val note: String? = null)

/** Pulls plain paragraphs out of common document formats, entirely on device. */
object DocumentText {
    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "document"
        }
        return uri.lastPathSegment ?: "document"
    }

    suspend fun load(
        context: Context,
        uri: Uri,
        ocr: suspend (Bitmap) -> String,
        onProgress: (String) -> Unit,
    ): LoadedDocument = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        val mime = context.contentResolver.getType(uri) ?: ""
        val lower = name.lowercase()
        when {
            mime == "application/pdf" || lower.endsWith(".pdf") -> loadPdf(context, uri, name, ocr, onProgress)
            mime.contains("wordprocessingml") || lower.endsWith(".docx") -> LoadedDocument(name, docx(context, uri))
            mime.contains("opendocument.text") || lower.endsWith(".odt") -> LoadedDocument(name, odt(context, uri))
            mime.contains("html") || lower.endsWith(".html") || lower.endsWith(".htm") -> {
                val raw = readText(context, uri)
                LoadedDocument(name, split(Html.fromHtml(raw, Html.FROM_HTML_MODE_COMPACT).toString()))
            }
            lower.endsWith(".srt") || lower.endsWith(".vtt") -> LoadedDocument(name, subtitles(readText(context, uri)), "Timing lines are kept as-is")
            else -> LoadedDocument(name, split(readText(context, uri)))
        }
    }

    private fun readText(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

    /** Paragraphs separated by blank lines; long single-line blocks are kept together. */
    fun split(text: String): List<String> =
        text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun subtitles(text: String): List<String> =
        text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun docx(context: Context, uri: Uri): List<String> {
        val xml = zipEntry(context, uri, "word/document.xml") ?: return emptyList()
        val paragraphs = ArrayList<String>()
        for (p in Regex("<w:p[ >].*?</w:p>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val text = Regex("<w:t[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL).findAll(p.value).joinToString("") { unescape(it.groupValues[1]) }
            if (text.isNotBlank()) paragraphs += text.trim()
        }
        return paragraphs
    }

    private fun odt(context: Context, uri: Uri): List<String> {
        val xml = zipEntry(context, uri, "content.xml") ?: return emptyList()
        return Regex("<text:(p|h)[^>]*>(.*?)</text:(p|h)>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { unescape(it.groupValues[2].replace(Regex("<[^>]+>"), "")).trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun zipEntry(context: Context, uri: Uri, entry: String): String? {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name == entry) return zip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
        return null
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private suspend fun loadPdf(context: Context, uri: Uri, name: String, ocr: suspend (Bitmap) -> String, onProgress: (String) -> Unit): LoadedDocument {
        // PdfRenderer needs a seekable file descriptor, so copy content:// streams to cache first.
        val tmp = File(context.cacheDir, "doc.pdf")
        context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val pfd = android.os.ParcelFileDescriptor.open(tmp, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val paragraphs = ArrayList<String>()
        return PdfRenderer(pfd).use { renderer ->
            val pages = minOf(renderer.pageCount, 40)
            for (i in 0 until pages) {
                onProgress("Reading page ${i + 1} of $pages…")
                renderer.openPage(i).use { page ->
                    val scale = 2f
                    val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val text = ocr(bmp)
                    bmp.recycle()
                    paragraphs += split(text)
                }
            }
            val note = if (renderer.pageCount > pages) "Only the first $pages of ${renderer.pageCount} pages were read" else null
            tmp.delete()
            LoadedDocument(name, paragraphs, note)
        }
    }
}
