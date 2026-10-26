package com.vitmap.app.feature.photo

import com.vitmap.core.photo.Adjustments
import com.vitmap.core.photo.CurvePoint
import com.vitmap.core.photo.Curves
import com.vitmap.core.photo.Grading
import com.vitmap.core.photo.HslBand

data class Preset(val id: String, val name: String, val group: String, val adjust: Adjustments)

/** Built-in looks. Each is plain [Adjustments], so it can be blended at any strength and tweaked after. */
object Presets {
    private fun c(vararg p: Float) = p.toList().chunked(2).map { CurvePoint(it[0], it[1]) }
    private fun hsl(vararg bands: Pair<Int, HslBand>) = List(8) { i -> bands.firstOrNull { it.first == i }?.second ?: HslBand() }
    private const val RED = 0
    private const val ORANGE = 1
    private const val YELLOW = 2
    private const val GREEN = 3
    private const val AQUA = 4
    private const val BLUE = 5

    val all: List<Preset> = listOf(
        // Natural
        Preset("vivid", "Vivid", "Natural", Adjustments(contrast = 15f, vibrance = 35f, saturation = 8f, clarity = 10f)),
        Preset("bright", "Bright", "Natural", Adjustments(exposure = 12f, shadows = 25f, highlights = -15f, vibrance = 15f)),
        Preset("warm", "Warm", "Natural", Adjustments(temperature = 25f, tint = 4f, vibrance = 10f)),
        Preset("cool", "Cool", "Natural", Adjustments(temperature = -25f, vibrance = 10f, contrast = 5f)),
        Preset("crisp", "Crisp", "Natural", Adjustments(clarity = 30f, sharpen = 30f, contrast = 12f, dehaze = 10f)),
        Preset("hdr", "HDR", "Natural", Adjustments(shadows = 60f, highlights = -60f, clarity = 45f, vibrance = 25f, whites = 10f, blacks = -10f)),
        // Film
        Preset("portra", "Portra", "Film", Adjustments(temperature = 10f, contrast = -8f, fade = 15f, vibrance = -5f, grain = 18f,
            curves = Curves(rgb = c(0f, 0.04f, 0.25f, 0.24f, 0.75f, 0.78f, 1f, 0.97f)),
            hsl = hsl(ORANGE to HslBand(sat = -10f, lum = 8f), GREEN to HslBand(hue = -20f, sat = -20f)),
            grading = Grading(shadowHue = 200f, shadowSat = 12f, highHue = 40f, highSat = 15f))),
        Preset("gold", "Gold", "Film", Adjustments(temperature = 22f, tint = 5f, saturation = 10f, contrast = 10f, grain = 15f,
            grading = Grading(highHue = 48f, highSat = 25f, midHue = 35f, midSat = 10f))),
        Preset("velvia", "Velvia", "Film", Adjustments(saturation = 35f, contrast = 25f, vibrance = 20f, blacks = -10f,
            hsl = hsl(GREEN to HslBand(sat = 20f), BLUE to HslBand(sat = 25f, lum = -10f)))),
        Preset("cinestill", "Cinestill", "Film", Adjustments(temperature = -18f, tint = 6f, contrast = 12f, bloom = 30f, grain = 20f,
            grading = Grading(shadowHue = 190f, shadowSat = 25f, highHue = 15f, highSat = 18f))),
        Preset("ektar", "Ektar", "Film", Adjustments(saturation = 25f, contrast = 18f, temperature = 6f, hsl = hsl(RED to HslBand(sat = 15f), BLUE to HslBand(sat = 15f)))),
        Preset("instant", "Instant", "Film", Adjustments(fade = 35f, contrast = -12f, temperature = 12f, vignette = -20f, saturation = -15f,
            curves = Curves(rgb = c(0f, 0.12f, 0.5f, 0.52f, 1f, 0.93f)))),
        // Black & white
        Preset("mono", "Mono", "B&W", Adjustments(monochrome = true, contrast = 12f)),
        Preset("noir", "Noir", "B&W", Adjustments(monochrome = true, contrast = 55f, blacks = -25f, clarity = 25f, vignette = -35f, grain = 20f)),
        Preset("highkey", "High key", "B&W", Adjustments(monochrome = true, exposure = 25f, shadows = 40f, contrast = -15f, highlights = -10f)),
        Preset("silver", "Silver", "B&W", Adjustments(monochrome = true, fade = 20f, clarity = 20f, grading = Grading(shadowHue = 210f, shadowSat = 10f))),
        Preset("sepia", "Sepia", "B&W", Adjustments(monochrome = true, contrast = 5f, fade = 10f, grading = Grading(shadowHue = 30f, shadowSat = 35f, midHue = 35f, midSat = 30f, highHue = 45f, highSat = 20f))),
        Preset("selenium", "Selenium", "B&W", Adjustments(monochrome = true, contrast = 20f, grading = Grading(shadowHue = 290f, shadowSat = 20f, highHue = 40f, highSat = 8f))),
        // Cinematic
        Preset("tealorange", "Teal & orange", "Cinematic", Adjustments(contrast = 15f,
            hsl = hsl(ORANGE to HslBand(sat = 15f), AQUA to HslBand(hue = 10f, sat = 20f), BLUE to HslBand(hue = -15f)),
            grading = Grading(shadowHue = 190f, shadowSat = 35f, highHue = 30f, highSat = 30f))),
        Preset("moody", "Moody", "Cinematic", Adjustments(exposure = -10f, contrast = 20f, highlights = -30f, saturation = -20f, fade = 12f, vignette = -30f,
            grading = Grading(shadowHue = 210f, shadowSat = 20f))),
        Preset("blockbuster", "Blockbuster", "Cinematic", Adjustments(contrast = 30f, clarity = 20f, dehaze = 15f, vignette = -25f, temperature = -5f,
            grading = Grading(shadowHue = 200f, shadowSat = 30f, highHue = 35f, highSat = 20f))),
        Preset("matte", "Matte", "Cinematic", Adjustments(fade = 40f, contrast = -10f, saturation = -10f, curves = Curves(rgb = c(0f, 0.1f, 0.3f, 0.32f, 1f, 0.95f)))),
        Preset("bleach", "Bleach bypass", "Cinematic", Adjustments(saturation = -45f, contrast = 40f, clarity = 30f, sharpen = 20f)),
        // Vintage
        Preset("seventies", "70s", "Vintage", Adjustments(temperature = 25f, fade = 25f, saturation = -10f, grain = 25f, vignette = -20f,
            grading = Grading(highHue = 50f, highSat = 25f, shadowHue = 20f, shadowSat = 15f))),
        Preset("lomo", "Lomo", "Vintage", Adjustments(saturation = 30f, contrast = 35f, vignette = -60f, vignetteMidpoint = 35f,
            curves = Curves(red = c(0f, 0f, 0.5f, 0.55f, 1f, 1f), blue = c(0f, 0.08f, 1f, 0.9f)))),
        Preset("cross", "Cross process", "Vintage", Adjustments(contrast = 20f, saturation = 15f,
            curves = Curves(red = c(0f, 0f, 0.3f, 0.22f, 0.7f, 0.82f, 1f, 1f), green = c(0f, 0f, 0.3f, 0.26f, 0.7f, 0.78f, 1f, 1f), blue = c(0f, 0.2f, 1f, 0.8f)))),
        Preset("faded", "Faded", "Vintage", Adjustments(fade = 45f, saturation = -25f, temperature = 8f, grain = 12f)),
        // Creative
        Preset("cyberpunk", "Cyberpunk", "Creative", Adjustments(temperature = -30f, tint = 40f, contrast = 25f, saturation = 25f, bloom = 25f,
            grading = Grading(shadowHue = 260f, shadowSat = 40f, highHue = 320f, highSat = 30f))),
        Preset("pastel", "Pastel", "Creative", Adjustments(exposure = 10f, contrast = -25f, saturation = -15f, fade = 20f, highlights = -20f, tint = 8f)),
        Preset("dream", "Dreamy", "Creative", Adjustments(bloom = 45f, contrast = -15f, exposure = 8f, fade = 15f, temperature = 8f)),
        Preset("duotone", "Duotone", "Creative", Adjustments(monochrome = true, contrast = 25f, grading = Grading(shadowHue = 250f, shadowSat = 60f, highHue = 40f, highSat = 50f))),
        // Subjects
        Preset("portrait", "Portrait", "Subjects", Adjustments(clarity = -15f, noiseReduction = 20f, temperature = 6f, hsl = hsl(ORANGE to HslBand(sat = -8f, lum = 10f), RED to HslBand(lum = 5f)), vignette = -15f)),
        Preset("landscape", "Landscape", "Subjects", Adjustments(dehaze = 20f, clarity = 25f, vibrance = 30f, highlights = -25f, shadows = 20f,
            hsl = hsl(BLUE to HslBand(sat = 20f, lum = -15f), GREEN to HslBand(sat = 15f), YELLOW to HslBand(hue = -10f)))),
        Preset("food", "Food", "Subjects", Adjustments(temperature = 12f, saturation = 15f, clarity = 15f, shadows = 15f, hsl = hsl(RED to HslBand(sat = 15f), ORANGE to HslBand(sat = 10f, lum = 5f)))),
        Preset("night", "Night", "Subjects", Adjustments(noiseReduction = 40f, shadows = 30f, highlights = -30f, temperature = -12f, clarity = 10f, blacks = -10f)),
    )

    val groups: List<String> = all.map { it.group }.distinct()

    fun byId(id: String?): Preset? = all.firstOrNull { it.id == id }
}
