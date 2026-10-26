package com.vitmap.app.feature.scan

import com.vitmap.core.scan.PageAdjust
import com.vitmap.core.scan.PageFilter
import com.vitmap.core.scan.Pt
import com.vitmap.core.scan.Quad
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

@Serializable
enum class PageKind { IMAGE, PDF, BLANK }

/**
 * One page of a document. Image pages keep the untouched photo plus the crop, filter and
 * adjustments, so every edit stays reversible. PDF pages reference a copy of the source file and
 * are kept as vectors when exported.
 */
@Serializable
data class DocPage(
    val id: String = newId(),
    val kind: PageKind = PageKind.IMAGE,
    val image: String? = null,
    /** Crop corners in the original photo, normalised: tl, tr, br, bl as x,y pairs. */
    val quad: List<Float>? = null,
    val filter: String = PageFilter.MAGIC.name,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val sharpness: Float = 0f,
    /** Extra clockwise rotation in degrees (0, 90, 180, 270). */
    val rotation: Int = 0,
    val pdf: String? = null,
    val pdfIndex: Int = 0,
    /** Height / width of a blank page. */
    val blankAspect: Float = 1.4142f,
    val annotations: List<Annot> = emptyList(),
    /** Bumped on every visual change so cached renders are invalidated. */
    val revision: Int = 0,
) {
    val pageFilter: PageFilter get() = runCatching { PageFilter.valueOf(filter) }.getOrDefault(PageFilter.ORIGINAL)
    val adjust: PageAdjust get() = PageAdjust(brightness, contrast, saturation, sharpness)

    fun cropQuad(): Quad? = quad?.takeIf { it.size == 8 }?.let {
        Quad(Pt(it[0].toDouble(), it[1].toDouble()), Pt(it[2].toDouble(), it[3].toDouble()), Pt(it[4].toDouble(), it[5].toDouble()), Pt(it[6].toDouble(), it[7].toDouble()))
    }

    fun bump() = copy(revision = revision + 1)

    companion object {
        fun quadToList(q: Quad): List<Float> = q.corners.flatMap { listOf(it.x.toFloat(), it.y.toFloat()) }
    }
}

@Serializable
data class ScanDoc(
    val id: String = newId(),
    val name: String,
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
    val pages: List<DocPage> = emptyList(),
)

/**
 * Annotations live in page space normalised to 0..1 (x across, y down), so they're independent of
 * render size. Stroke widths and text sizes are fractions of the page width.
 */
@Serializable
sealed interface Annot {
    val id: String
}

@Serializable
@SerialName("ink")
data class InkAnnot(
    override val id: String = newId(),
    /** x,y pairs. */
    val points: List<Float>,
    val color: Long,
    val width: Float,
    val highlighter: Boolean = false,
) : Annot

@Serializable
@SerialName("text")
data class TextAnnot(
    override val id: String = newId(),
    val text: String,
    /** Top-left of the text box. */
    val x: Float,
    val y: Float,
    val size: Float = 0.035f,
    val color: Long = 0xFF111111,
    val bold: Boolean = false,
    val background: Long? = null,
) : Annot

@Serializable
@SerialName("sign")
data class SignAnnot(
    override val id: String = newId(),
    /** Each stroke as x,y pairs normalised to the signature's own box. */
    val strokes: List<List<Float>>,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val color: Long = 0xFF0B2A6F,
    /** Pen width as a fraction of the box width. */
    val width: Float = 0.012f,
) : Annot

@Serializable
@SerialName("image")
data class ImageAnnot(
    override val id: String = newId(),
    val file: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
) : Annot

@Serializable
enum class ShapeKind { RECT, OVAL, LINE, ARROW }

@Serializable
@SerialName("shape")
data class ShapeAnnot(
    override val id: String = newId(),
    val shape: ShapeKind,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val color: Long,
    val width: Float,
    val fill: Boolean = false,
) : Annot

@Serializable
@SerialName("white")
data class WhiteoutAnnot(
    override val id: String = newId(),
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val color: Long = 0xFFFFFFFF,
) : Annot

@Serializable
data class SavedSignature(
    val id: String = newId(),
    val strokes: List<List<Float>>,
    /** Height / width of the signature box. */
    val aspect: Float,
    val color: Long,
    val created: Long = System.currentTimeMillis(),
)

@Serializable
data class ScanPrefs(
    val signatures: List<SavedSignature> = emptyList(),
    val autoCapture: Boolean = true,
    val batch: Boolean = true,
    val defaultFilter: String = PageFilter.MAGIC.name,
    val exportQuality: String = ExportQuality.MEDIUM.name,
    val pageSize: String = ExportPageSize.FIT.name,
    val grid: Boolean = false,
)

enum class ExportQuality(val label: String, val jpeg: Float, val maxDim: Int) {
    SMALL("Small", 0.6f, 1500),
    MEDIUM("Balanced", 0.8f, 2200),
    HIGH("High", 0.92f, 3200),
}

enum class ExportPageSize(val label: String) {
    FIT("Fit content"),
    A4("A4"),
    LETTER("Letter"),
}

/** Box helper shared by annotations with x, y, w, h. */
data class NormRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun contains(px: Float, py: Float) = px in x..(x + w) && py in y..(y + h)
}

fun Annot.bounds(aspect: Float): NormRect? = when (this) {
    is TextAnnot -> {
        val lines = text.split('\n')
        val longest = lines.maxOf { it.length }.coerceAtLeast(1)
        NormRect(x, y, (longest * size * 0.55f).coerceAtMost(1f), lines.size * size * 1.25f / aspect)
    }
    is SignAnnot -> NormRect(x, y, w, h)
    is ImageAnnot -> NormRect(x, y, w, h)
    is WhiteoutAnnot -> NormRect(x, y, w, h)
    is ShapeAnnot -> NormRect(minOf(x1, x2), minOf(y1, y2), kotlin.math.abs(x2 - x1), kotlin.math.abs(y2 - y1))
    is InkAnnot -> {
        if (points.size < 2) null else {
            var minX = 1f
            var minY = 1f
            var maxX = 0f
            var maxY = 0f
            for (i in points.indices step 2) {
                minX = minOf(minX, points[i]); maxX = maxOf(maxX, points[i])
                minY = minOf(minY, points[i + 1]); maxY = maxOf(maxY, points[i + 1])
            }
            NormRect(minX, minY, maxX - minX, maxY - minY)
        }
    }
}

/** Moves an annotation by a normalised offset. */
fun Annot.moved(dx: Float, dy: Float): Annot = when (this) {
    is TextAnnot -> copy(x = x + dx, y = y + dy)
    is SignAnnot -> copy(x = x + dx, y = y + dy)
    is ImageAnnot -> copy(x = x + dx, y = y + dy)
    is WhiteoutAnnot -> copy(x = x + dx, y = y + dy)
    is ShapeAnnot -> copy(x1 = x1 + dx, y1 = y1 + dy, x2 = x2 + dx, y2 = y2 + dy)
    is InkAnnot -> copy(points = points.mapIndexed { i, v -> if (i % 2 == 0) v + dx else v + dy })
}

/** Scales a box annotation about its top-left corner. */
fun Annot.resized(scale: Float): Annot = when (this) {
    is TextAnnot -> copy(size = (size * scale).coerceIn(0.008f, 0.3f))
    is SignAnnot -> copy(w = (w * scale).coerceIn(0.03f, 1f), h = (h * scale).coerceIn(0.01f, 1f))
    is ImageAnnot -> copy(w = (w * scale).coerceIn(0.03f, 1f), h = (h * scale).coerceIn(0.01f, 1f))
    is WhiteoutAnnot -> copy(w = (w * scale).coerceIn(0.01f, 1f), h = (h * scale).coerceIn(0.005f, 1f))
    is ShapeAnnot -> copy(x2 = x1 + (x2 - x1) * scale, y2 = y1 + (y2 - y1) * scale)
    is InkAnnot -> this
}
