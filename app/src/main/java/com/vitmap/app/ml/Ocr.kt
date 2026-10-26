package com.vitmap.app.ml

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.vitmap.app.data.packs.Pack
import com.vitmap.app.data.packs.PackGroup
import com.vitmap.app.data.packs.PlayModuleInstaller
import com.vitmap.app.vitmap
import kotlinx.coroutines.tasks.await

/** Writing systems with a dedicated ML Kit text-recognition model. */
enum class OcrScript(val label: String, val native: String, val approxBytes: Long) {
    LATIN("Latin", "Latin", 4_000_000),
    CHINESE("Chinese", "中文", 12_000_000),
    DEVANAGARI("Devanagari", "देवनागरी", 10_000_000),
    JAPANESE("Japanese", "日本語", 12_000_000),
    KOREAN("Korean", "한국어", 10_000_000),
}

class OcrMissingException(val script: OcrScript) : Exception("The ${script.label} text-recognition model isn't downloaded yet")

/**
 * On-device text recognition. The models are delivered on demand by Google Play services instead of
 * being bundled, so each script is a pack the user downloads once.
 */
object Ocr {
    private val clients = HashMap<OcrScript, TextRecognizer>()

    @Synchronized
    fun client(script: OcrScript): TextRecognizer = clients.getOrPut(script) {
        when (script) {
            OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            OcrScript.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        }
    }

    val packs: List<Pack> = OcrScript.entries.map { script ->
        Pack(
            id = "ocr-${script.name.lowercase()}",
            title = "Text recognition: ${script.label}",
            description = "Reads ${script.label} text in photos, camera frames and scanned PDFs, on your device. Delivered by Google Play services.",
            approxBytes = script.approxBytes,
            group = PackGroup.VISION,
            installer = PlayModuleInstaller { client(script) },
        )
    }

    fun pack(script: OcrScript): Pack = packs[script.ordinal]

    /** Script whose model reads text in the given ML Kit / BCP-47 language. */
    fun scriptFor(language: String?): OcrScript = when (language) {
        "zh" -> OcrScript.CHINESE
        "ja" -> OcrScript.JAPANESE
        "ko" -> OcrScript.KOREAN
        "hi", "mr" -> OcrScript.DEVANAGARI
        else -> OcrScript.LATIN
    }

    /** Recognises text; throws [OcrMissingException] if the script's model isn't installed. */
    suspend fun recognize(context: Context, bitmap: Bitmap, script: OcrScript = OcrScript.LATIN): Text {
        if (!context.vitmap.packs.isReady(pack(script))) throw OcrMissingException(script)
        return client(script).process(InputImage.fromBitmap(bitmap, 0)).await()
    }
}
