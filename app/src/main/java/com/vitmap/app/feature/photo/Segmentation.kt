package com.vitmap.app.feature.photo

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.vitmap.app.data.packs.Pack
import com.vitmap.app.data.packs.PackGroup
import com.vitmap.app.data.packs.PlayModuleInstaller
import com.vitmap.app.vitmap
import kotlinx.coroutines.tasks.await
import kotlin.math.max
import kotlin.math.roundToInt

class SegmentationMissingException : Exception("The background remover isn't downloaded yet")

/** On-device subject cut-out with ML Kit; the model arrives through Google Play services. */
object Segmentation {
    private val client: SubjectSegmenter by lazy {
        SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
    }

    val pack = Pack(
        id = "subject-segmentation",
        title = "Background remover",
        description = "Finds people, pets and objects in photos so the background can be removed, blurred or replaced. Runs on your device; delivered by Google Play services.",
        approxBytes = 6_000_000,
        group = PackGroup.VISION,
        installer = PlayModuleInstaller { client },
    )

    /**
     * Foreground confidence (0…1) for [bitmap], computed on a copy no larger than [maxSide] and
     * returned at that size with its dimensions.
     */
    suspend fun mask(context: Context, bitmap: Bitmap, maxSide: Int = 1600): Triple<FloatArray, Int, Int> {
        if (!context.vitmap.packs.isReady(pack)) throw SegmentationMissingException()
        val scale = (maxSide.toFloat() / max(bitmap.width, bitmap.height)).coerceAtMost(1f)
        val input = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true) else bitmap
        val result = client.process(InputImage.fromBitmap(input, 0)).await()
        val buffer = result.foregroundConfidenceMask ?: throw IllegalStateException("No subject found")
        buffer.rewind()
        val out = FloatArray(input.width * input.height)
        buffer.get(out, 0, minOf(out.size, buffer.remaining()))
        return Triple(out, input.width, input.height)
    }
}
