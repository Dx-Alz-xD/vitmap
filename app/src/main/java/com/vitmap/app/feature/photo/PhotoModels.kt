package com.vitmap.app.feature.photo

import com.vitmap.core.photo.Adjustments
import com.vitmap.core.photo.LocalAdjustment
import kotlinx.serialization.Serializable

/** Rotation, flips, straightening, perspective and the crop rectangle (relative to the rotated photo). */
@Serializable
data class Geometry(
    /** Quarter turns clockwise, 0…3. */
    val quarter: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    /** Fine rotation in degrees, -45…45. */
    val straighten: Float = 0f,
    /** Keystone correction, -100…100. */
    val vertical: Float = 0f,
    val horizontal: Float = 0f,
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val isIdentity: Boolean get() = this == Geometry()
}

enum class TextFont(val label: String) { SANS("Sans"), SERIF("Serif"), MONO("Mono"), CONDENSED("Condensed"), ROUNDED("Rounded"), HANDWRITING("Handwriting"), DISPLAY("Display") }

enum class ShapeKind(val label: String) { RECT("Rectangle"), ELLIPSE("Ellipse"), LINE("Line"), ARROW("Arrow"), STAR("Star"), HEART("Heart") }

enum class BrushKind(val label: String) { PEN("Pen"), MARKER("Marker"), HIGHLIGHTER("Highlighter"), NEON("Neon"), SPRAY("Spray") }

/**
 * Something placed on top of the photo. Positions are relative to the (cropped) photo: x/y is the
 * centre, size is relative to the shorter side.
 */
@Serializable
sealed interface Overlay {
    val id: String
    val x: Float
    val y: Float
    val scale: Float
    val rotation: Float
    val opacity: Float

    fun moved(x: Float, y: Float, scale: Float, rotation: Float): Overlay = when (this) {
        is TextLayer -> copy(x = x, y = y, scale = scale, rotation = rotation)
        is StickerLayer -> copy(x = x, y = y, scale = scale, rotation = rotation)
        is ShapeLayer -> copy(x = x, y = y, scale = scale, rotation = rotation)
        is ImageLayer -> copy(x = x, y = y, scale = scale, rotation = rotation)
        is DrawingLayer -> copy(x = x, y = y, scale = scale, rotation = rotation)
    }

    @Serializable
    data class TextLayer(
        override val id: String,
        val text: String,
        val color: Long = 0xFFFFFFFF,
        val font: TextFont = TextFont.SANS,
        val bold: Boolean = true,
        val italic: Boolean = false,
        /** 0 = left, 1 = centre, 2 = right. */
        val align: Int = 1,
        val outline: Long? = null,
        val outlineWidth: Float = 0.08f,
        val shadow: Boolean = true,
        val background: Long? = null,
        val letterSpacing: Float = 0f,
        val curve: Float = 0f,
        override val x: Float = 0.5f,
        override val y: Float = 0.5f,
        override val scale: Float = 0.12f,
        override val rotation: Float = 0f,
        override val opacity: Float = 1f,
    ) : Overlay

    @Serializable
    data class StickerLayer(
        override val id: String,
        val emoji: String,
        override val x: Float = 0.5f,
        override val y: Float = 0.5f,
        override val scale: Float = 0.25f,
        override val rotation: Float = 0f,
        override val opacity: Float = 1f,
    ) : Overlay

    @Serializable
    data class ShapeLayer(
        override val id: String,
        val kind: ShapeKind,
        val color: Long = 0xFFFFFFFF,
        val fill: Boolean = false,
        val strokeWidth: Float = 0.02f,
        /** Width / height of the shape's box. */
        val aspect: Float = 1f,
        override val x: Float = 0.5f,
        override val y: Float = 0.5f,
        override val scale: Float = 0.3f,
        override val rotation: Float = 0f,
        override val opacity: Float = 1f,
    ) : Overlay

    @Serializable
    data class ImageLayer(
        override val id: String,
        /** File name inside the project folder. */
        val file: String,
        val aspect: Float,
        val blend: BlendMode = BlendMode.NORMAL,
        override val x: Float = 0.5f,
        override val y: Float = 0.5f,
        override val scale: Float = 0.4f,
        override val rotation: Float = 0f,
        override val opacity: Float = 1f,
    ) : Overlay

    /** Freehand strokes; points are relative to the photo when drawn (x/y/scale/rotation transform them afterwards). */
    @Serializable
    data class DrawingLayer(
        override val id: String,
        val strokes: List<Stroke>,
        override val x: Float = 0.5f,
        override val y: Float = 0.5f,
        override val scale: Float = 1f,
        override val rotation: Float = 0f,
        override val opacity: Float = 1f,
    ) : Overlay
}

@Serializable
data class Stroke(val points: List<Float>, val color: Long, val width: Float, val kind: BrushKind = BrushKind.PEN, val erase: Boolean = false)

enum class BlendMode(val label: String) { NORMAL("Normal"), MULTIPLY("Multiply"), SCREEN("Screen"), OVERLAY("Overlay"), SOFT_LIGHT("Soft light"), DARKEN("Darken"), LIGHTEN("Lighten"), DIFFERENCE("Difference"), COLOR("Color"), LUMINOSITY("Luminosity") }

enum class BackgroundMode(val label: String) { KEEP("Original"), TRANSPARENT("Remove"), COLOR("Colour"), BLUR("Blur"), GRAY("Mono"), IMAGE("Image") }

@Serializable
data class Background(
    val mode: BackgroundMode = BackgroundMode.KEEP,
    val color: Long = 0xFFFFFFFF,
    val blur: Float = 50f,
    val image: String? = null,
    /** Soften the cut-out edge, 0…100. */
    val edge: Float = 30f,
    /** Darken/lighten the subject's shadow area, used for a subtle drop shadow. */
    val shadow: Boolean = false,
)

enum class FrameKind(val label: String) { NONE("None"), SOLID("Border"), ROUNDED("Rounded"), POLAROID("Instant"), FILM("Film"), SHADOW("Floating") }

@Serializable
data class Frame(val kind: FrameKind = FrameKind.NONE, val color: Long = 0xFFFFFFFF, val size: Float = 0.05f, val radius: Float = 0.06f)

/** Everything about an edit except the pixels, so history and projects are cheap. */
@Serializable
data class PhotoEdit(
    val adjust: Adjustments = Adjustments(),
    val preset: String? = null,
    val presetAmount: Float = 1f,
    val geometry: Geometry = Geometry(),
    val locals: List<LocalAdjustment> = emptyList(),
    val overlays: List<Overlay> = emptyList(),
    val background: Background = Background(),
    val frame: Frame = Frame(),
    /** Which retouched base image to start from (heal/remove create new versions). */
    val baseVersion: Int = 0,
)

@Serializable
data class PhotoProject(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
    val edit: PhotoEdit = PhotoEdit(),
)

enum class ExportFormat(val label: String, val ext: String, val mime: String, val lossy: Boolean, val alpha: Boolean) {
    JPEG("JPEG", "jpg", "image/jpeg", true, false),
    PNG("PNG", "png", "image/png", false, true),
    WEBP("WebP", "webp", "image/webp", true, true),
    WEBP_LOSSLESS("WebP lossless", "webp", "image/webp", false, true),
    HEIC("HEIC", "heic", "image/heic", true, false),
}

@Serializable
data class ExportOptions(val format: ExportFormat = ExportFormat.JPEG, val quality: Int = 92, val maxSize: Int = 0, val keepExif: Boolean = true, val name: String = "")
