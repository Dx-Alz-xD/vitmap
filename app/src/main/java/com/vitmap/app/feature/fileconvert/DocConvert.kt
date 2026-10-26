package com.vitmap.app.feature.fileconvert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.vitmap.app.feature.compress.Progress
import com.vitmap.app.feature.scan.Pdf
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/** A deliberately simple document model: headings, paragraphs, list items and code. */
data class Block(val kind: Kind, val text: String) {
    enum class Kind { H1, H2, H3, P, LI, CODE }
}

object DocConvert {
    suspend fun convert(context: Context, input: File, name: String, inFmt: Fmt?, out: Fmt, o: ConvertOptions, target: File, p: Progress) {
        if (out.id == "pdf-images") return pdfToImages(input, target, o, p)
        p.update(null, "Reading ${inFmt?.label ?: "document"}")
        val blocks = read(context, input, inFmt)
        if (blocks.isEmpty()) throw IOException("No text found. If this is a scanned PDF, use PDF Tools → Extract text, which can run OCR.")
        p.update(null, "Writing ${out.label}")
        val title = name.substringBeforeLast('.')
        when (out.id) {
            "txt" -> target.writeText(blocks.joinToString("\n\n") { (if (it.kind == Block.Kind.LI) "• " else "") + it.text })
            "md" -> target.writeText(toMarkdown(blocks))
            "html" -> target.writeText(toHtml(blocks, title))
            "docx" -> writeDocx(blocks, target)
            "rtf" -> target.writeText(toRtf(blocks))
            "pdf" -> writePdf(blocks, target)
            else -> throw IOException("Can't write ${out.label}")
        }
    }

    // ---------------------------------------------------------------- reading

    fun read(context: Context, file: File, fmt: Fmt?): List<Block> = when (fmt?.id) {
        "pdf" -> {
            Pdf.init(context)
            PDDocument.load(file).use { d -> paragraphs(PDFTextStripper().apply { sortByPosition = true; paragraphEnd = "\n\n" }.getText(d)) }
        }
        "docx" -> ZipFile(file).use { z -> docx(z.getInputStream(z.getEntry("word/document.xml") ?: throw IOException("Not a Word document")).bufferedReader().readText()) }
        "odt" -> ZipFile(file).use { z -> odt(z.getInputStream(z.getEntry("content.xml") ?: throw IOException("Not an OpenDocument file")).bufferedReader().readText()) }
        "html" -> html(file.readText())
        "md" -> markdown(file.readText())
        "rtf" -> paragraphs(rtfText(file.readText()))
        "epub" -> epub(file)
        else -> paragraphs(file.readText())
    }

    private fun paragraphs(text: String): List<Block> =
        text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).map { it.replace(Regex("\\s*\n\\s*"), " ").trim() }.filter { it.isNotEmpty() }.map { Block(Block.Kind.P, it) }

    private fun parser(xml: String): XmlPullParser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser().apply { setInput(StringReader(xml)) }

    private fun docx(xml: String): List<Block> {
        val out = ArrayList<Block>()
        val p = parser(xml)
        var text = StringBuilder()
        var style = ""
        var list = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "w:p" -> { text = StringBuilder(); style = ""; list = false }
                    "w:pStyle" -> style = p.getAttributeValue(null, "w:val").orEmpty()
                    "w:numPr" -> list = true
                    "w:tab" -> text.append('\t')
                    "w:br" -> text.append('\n')
                }
                XmlPullParser.TEXT -> if (p.text != null) text.append(p.text)
                XmlPullParser.END_TAG -> if (p.name == "w:p") {
                    val t = text.toString().trim()
                    if (t.isNotEmpty()) {
                        val kind = when {
                            style.contains("Heading1", true) || style.equals("Title", true) -> Block.Kind.H1
                            style.contains("Heading2", true) -> Block.Kind.H2
                            style.contains("Heading", true) -> Block.Kind.H3
                            list || style.contains("List", true) -> Block.Kind.LI
                            else -> Block.Kind.P
                        }
                        out += Block(kind, t)
                    }
                }
            }
        }
        return out
    }

    private fun odt(xml: String): List<Block> {
        val out = ArrayList<Block>()
        val p = parser(xml)
        var text: StringBuilder? = null
        var kind = Block.Kind.P
        var inList = 0
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "text:list" -> inList++
                    "text:h" -> { text = StringBuilder(); kind = when (p.getAttributeValue(null, "text:outline-level")) { "1" -> Block.Kind.H1; "2" -> Block.Kind.H2; else -> Block.Kind.H3 } }
                    "text:p" -> { text = StringBuilder(); kind = if (inList > 0) Block.Kind.LI else Block.Kind.P }
                    "text:s" -> text?.append(' ')
                    "text:tab" -> text?.append('\t')
                    "text:line-break" -> text?.append('\n')
                }
                XmlPullParser.TEXT -> text?.append(p.text ?: "")
                XmlPullParser.END_TAG -> when (p.name) {
                    "text:list" -> inList--
                    "text:p", "text:h" -> {
                        text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += Block(kind, it) }
                        text = null
                    }
                }
            }
        }
        return out
    }

    private fun decodeEntities(s: String): String = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: "" }
        .replace("&amp;", "&")

    fun html(source: String): List<Block> {
        val body = source.replace(Regex("(?is)<(script|style|head|nav|footer)[^>]*>.*?</\\1>"), " ")
        val out = ArrayList<Block>()
        val tag = Regex("(?is)<(h[1-6]|p|li|pre|div|br|tr|blockquote)(\\s[^>]*)?>(.*?)(?=<(?:h[1-6]|p|li|pre|div|tr|blockquote)[\\s>]|</(?:body|html)>|$)")
        for (m in tag.findAll(body)) {
            val text = decodeEntities(m.groupValues[3].replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), " ")).replace(Regex("[ \\t]+"), " ").trim()
            if (text.isEmpty()) continue
            val kind = when (m.groupValues[1].lowercase()) {
                "h1" -> Block.Kind.H1
                "h2" -> Block.Kind.H2
                "h3", "h4", "h5", "h6" -> Block.Kind.H3
                "li" -> Block.Kind.LI
                "pre" -> Block.Kind.CODE
                else -> Block.Kind.P
            }
            out += Block(kind, text)
        }
        return out.ifEmpty { paragraphs(decodeEntities(body.replace(Regex("<[^>]+>"), "\n"))) }
    }

    fun markdown(md: String): List<Block> {
        val out = ArrayList<Block>()
        val para = StringBuilder()
        var code: StringBuilder? = null
        fun flush() {
            if (para.isNotBlank()) out += Block(Block.Kind.P, inlineMd(para.toString().trim()))
            para.setLength(0)
        }
        for (line in md.replace("\r\n", "\n").lines()) {
            if (line.trimStart().startsWith("```")) {
                if (code == null) { flush(); code = StringBuilder() } else { out += Block(Block.Kind.CODE, code.toString().trimEnd()); code = null }
                continue
            }
            if (code != null) { code.appendLine(line); continue }
            val t = line.trim()
            when {
                t.isEmpty() -> flush()
                t.startsWith("# ") -> { flush(); out += Block(Block.Kind.H1, inlineMd(t.drop(2))) }
                t.startsWith("## ") -> { flush(); out += Block(Block.Kind.H2, inlineMd(t.drop(3))) }
                t.startsWith("#") -> { flush(); out += Block(Block.Kind.H3, inlineMd(t.trimStart('#').trim())) }
                t.matches(Regex("^([-*+]|\\d+[.)])\\s+.*")) -> { flush(); out += Block(Block.Kind.LI, inlineMd(t.replaceFirst(Regex("^([-*+]|\\d+[.)])\\s+"), ""))) }
                else -> para.append(if (para.isEmpty()) "" else " ").append(t)
            }
        }
        flush()
        code?.let { out += Block(Block.Kind.CODE, it.toString()) }
        return out
    }

    /** Strips Markdown emphasis and turns links into "text (url)". */
    private fun inlineMd(s: String) = s.replace(Regex("!?\\[([^]]*)]\\(([^)]*)\\)"), "$1 ($2)").replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2").replace(Regex("(?<![\\w*])[*_](.+?)[*_](?![\\w*])"), "$1").replace("`", "")

    private fun rtfText(rtf: String): String {
        val sb = StringBuilder()
        var depth = 0
        var skip = 0
        var i = 0
        while (i < rtf.length) {
            val c = rtf[i]
            when {
                c == '{' -> { depth++; i++ }
                c == '}' -> { if (skip == depth) skip = 0; depth--; i++ }
                c == '\\' -> {
                    val m = Regex("""\\([a-zA-Z]+)(-?\d+)? ?|\\'([0-9a-fA-F]{2})|\\(.)""").find(rtf, i)
                    if (m == null || m.range.first != i) { i++; continue }
                    val word = m.groupValues[1]
                    when {
                        m.groupValues[3].isNotEmpty() -> if (skip == 0) sb.append(m.groupValues[3].toInt(16).toChar())
                        word in setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer") -> if (skip == 0) skip = depth
                        skip != 0 -> {}
                        word == "par" || word == "line" -> sb.append('\n').append(if (word == "par") "\n" else "")
                        word == "tab" -> sb.append('\t')
                        word == "u" -> m.groupValues[2].toIntOrNull()?.let { sb.append((if (it < 0) it + 65536 else it).toChar()) }
                        m.groupValues[4].isNotEmpty() && m.groupValues[4] in listOf("\\", "{", "}") -> sb.append(m.groupValues[4])
                    }
                    i = m.range.last + 1
                }
                else -> { if (skip == 0 && c != '\r' && c != '\n') sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private fun epub(file: File): List<Block> = ZipFile(file).use { z ->
        val container = z.getEntry("META-INF/container.xml")?.let { z.getInputStream(it).bufferedReader().readText() }
        val opfPath = container?.let { Regex("full-path=\"([^\"]+)\"").find(it)?.groupValues?.get(1) } ?: throw IOException("Not an EPUB")
        val opf = z.getInputStream(z.getEntry(opfPath)).bufferedReader().readText()
        val baseDir = opfPath.substringBeforeLast('/', "")
        val manifest = Regex("<item\\s[^>]*?id=\"([^\"]+)\"[^>]*?href=\"([^\"]+)\"").findAll(opf).associate { it.groupValues[1] to it.groupValues[2] } +
            Regex("<item\\s[^>]*?href=\"([^\"]+)\"[^>]*?id=\"([^\"]+)\"").findAll(opf).associate { it.groupValues[2] to it.groupValues[1] }
        val spine = Regex("<itemref\\s[^>]*?idref=\"([^\"]+)\"").findAll(opf).map { it.groupValues[1] }.toList()
        spine.flatMap { id ->
            val href = manifest[id] ?: return@flatMap emptyList()
            val path = (if (baseDir.isEmpty()) href else "$baseDir/$href").replace("%20", " ")
            z.getEntry(path)?.let { html(z.getInputStream(it).bufferedReader().readText()) } ?: emptyList()
        }
    }

    // ---------------------------------------------------------------- writing

    fun toMarkdown(blocks: List<Block>) = blocks.joinToString("\n\n") {
        when (it.kind) {
            Block.Kind.H1 -> "# ${it.text}"
            Block.Kind.H2 -> "## ${it.text}"
            Block.Kind.H3 -> "### ${it.text}"
            Block.Kind.LI -> "- ${it.text}"
            Block.Kind.CODE -> "```\n${it.text}\n```"
            Block.Kind.P -> it.text
        }
    } + "\n"

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun toHtml(blocks: List<Block>, title: String) = buildString {
        append("<!doctype html>\n<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>").append(esc(title)).append("</title>\n")
        append("<style>body{font:17px/1.6 system-ui,sans-serif;max-width:46em;margin:2em auto;padding:0 1em;color:#1f2933}h1,h2,h3{line-height:1.25}pre{background:#f3f4f6;padding:1em;overflow:auto;border-radius:8px}</style></head><body>\n")
        var inList = false
        for (b in blocks) {
            if (b.kind == Block.Kind.LI && !inList) { append("<ul>\n"); inList = true }
            if (b.kind != Block.Kind.LI && inList) { append("</ul>\n"); inList = false }
            when (b.kind) {
                Block.Kind.H1 -> append("<h1>").append(esc(b.text)).append("</h1>\n")
                Block.Kind.H2 -> append("<h2>").append(esc(b.text)).append("</h2>\n")
                Block.Kind.H3 -> append("<h3>").append(esc(b.text)).append("</h3>\n")
                Block.Kind.LI -> append("<li>").append(esc(b.text)).append("</li>\n")
                Block.Kind.CODE -> append("<pre><code>").append(esc(b.text)).append("</code></pre>\n")
                Block.Kind.P -> append("<p>").append(esc(b.text).replace("\n", "<br>")).append("</p>\n")
            }
        }
        if (inList) append("</ul>\n")
        append("</body></html>\n")
    }

    fun toRtf(blocks: List<Block>) = buildString {
        append("{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Helvetica;}{\\f1 Courier New;}}\\fs24\n")
        fun e(s: String) = buildString {
            for (c in s) when {
                c == '\\' || c == '{' || c == '}' -> append('\\').append(c)
                c == '\n' -> append("\\line ")
                c.code > 127 -> append("\\u").append(c.code.toShort().toInt()).append('?')
                else -> append(c)
            }
        }
        for (b in blocks) {
            when (b.kind) {
                Block.Kind.H1 -> append("{\\b\\fs40 ").append(e(b.text)).append("}\\par\\par\n")
                Block.Kind.H2 -> append("{\\b\\fs32 ").append(e(b.text)).append("}\\par\\par\n")
                Block.Kind.H3 -> append("{\\b\\fs28 ").append(e(b.text)).append("}\\par\\par\n")
                Block.Kind.LI -> append("\\bullet  ").append(e(b.text)).append("\\par\n")
                Block.Kind.CODE -> append("{\\f1\\fs20 ").append(e(b.text)).append("}\\par\\par\n")
                Block.Kind.P -> append(e(b.text)).append("\\par\\par\n")
            }
        }
        append("}\n")
    }

    private fun writeDocx(blocks: List<Block>, target: File) {
        fun x(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val body = blocks.joinToString("") { b ->
            val style = when (b.kind) {
                Block.Kind.H1 -> "Heading1"
                Block.Kind.H2 -> "Heading2"
                Block.Kind.H3 -> "Heading3"
                Block.Kind.LI -> "ListParagraph"
                Block.Kind.CODE -> "Code"
                Block.Kind.P -> null
            }
            val pPr = style?.let { "<w:pPr><w:pStyle w:val=\"$it\"/></w:pPr>" } ?: ""
            val runs = b.text.split('\n').joinToString("<w:br/>") { line -> "<w:t xml:space=\"preserve\">${x(if (b.kind == Block.Kind.LI) "• $line" else line)}</w:t>" }
            "<w:p>$pPr<w:r>$runs</w:r></w:p>"
        }
        val files = mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/_rels/document.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
            "word/styles.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/><w:sz w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:pPr><w:spacing w:before="240" w:after="120"/></w:pPr><w:rPr><w:b/><w:sz w:val="36"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:pPr><w:spacing w:before="200" w:after="100"/></w:pPr><w:rPr><w:b/><w:sz w:val="30"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:rPr><w:b/><w:sz w:val="26"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:pPr><w:ind w:left="360"/></w:pPr></w:style><w:style w:type="paragraph" w:styleId="Code"><w:name w:val="Code"/><w:rPr><w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/><w:sz w:val="20"/></w:rPr></w:style></w:styles>""",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>""",
        )
        ZipOutputStream(target.outputStream().buffered()).use { z ->
            for ((name, content) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray())
                z.closeEntry()
            }
        }
    }

    /** Lays the text out on A4 pages with Android's text engine, so every script and emoji works. */
    private fun writePdf(blocks: List<Block>, target: File) {
        val pageW = 595
        val pageH = 842
        val margin = 56
        val width = pageW - margin * 2
        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            y = margin.toFloat()
        }
        newPage()
        for (b in blocks) {
            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1F2933.toInt()
                textSize = when (b.kind) {
                    Block.Kind.H1 -> 22f
                    Block.Kind.H2 -> 17f
                    Block.Kind.H3 -> 14f
                    Block.Kind.CODE -> 9.5f
                    else -> 11f
                }
                typeface = when (b.kind) {
                    Block.Kind.H1, Block.Kind.H2, Block.Kind.H3 -> Typeface.DEFAULT_BOLD
                    Block.Kind.CODE -> Typeface.MONOSPACE
                    else -> Typeface.DEFAULT
                }
            }
            val text = if (b.kind == Block.Kind.LI) "•  ${b.text}" else b.text
            val indent = if (b.kind == Block.Kind.LI) 12 else 0
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width - indent).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.2f).build()
            val before = if (b.kind in setOf(Block.Kind.H1, Block.Kind.H2)) 10f else 0f
            var line = 0
            y += before
            while (line < layout.lineCount) {
                val room = pageH - margin - y
                var last = line
                while (last < layout.lineCount && layout.getLineBottom(last) - layout.getLineTop(line) <= room) last++
                if (last == line) {
                    newPage()
                    continue
                }
                val c = page!!.canvas
                c.save()
                c.translate((margin + indent).toFloat(), y - layout.getLineTop(line))
                c.clipRect(0f, layout.getLineTop(line).toFloat(), width.toFloat(), layout.getLineBottom(last - 1).toFloat())
                layout.draw(c)
                c.restore()
                y += layout.getLineBottom(last - 1) - layout.getLineTop(line)
                line = last
                if (line < layout.lineCount) newPage()
            }
            y += if (b.kind == Block.Kind.LI) 4f else 10f
        }
        page?.let { doc.finishPage(it) }
        target.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    private fun pdfToImages(input: File, target: File, o: ConvertOptions, p: Progress) {
        ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                ZipOutputStream(target.outputStream().buffered()).use { z ->
                    for (i in 0 until r.pageCount) {
                        p.update(i.toFloat() / r.pageCount, "Rendering page ${i + 1} of ${r.pageCount}")
                        r.openPage(i).use { page ->
                            val s = o.dpi / 72f
                            val bmp = Bitmap.createBitmap((page.width * s).roundToInt().coerceAtLeast(1), (page.height * s).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            z.putNextEntry(ZipEntry("page-%03d.jpg".format(i + 1)))
                            bmp.compress(Bitmap.CompressFormat.JPEG, o.quality, z)
                            z.closeEntry()
                            bmp.recycle()
                        }
                    }
                }
            }
        }
    }
}
