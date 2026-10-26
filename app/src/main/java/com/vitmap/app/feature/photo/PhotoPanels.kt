package com.vitmap.app.feature.photo

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.FlipToBack
import androidx.compose.material.icons.rounded.FlipToFront
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.RotateLeft
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitmap.core.photo.Adjustments
import com.vitmap.core.photo.Curves
import com.vitmap.core.photo.Grading
import com.vitmap.core.photo.HslBand
import com.vitmap.core.photo.MaskShape
import com.vitmap.core.photo.PhotoPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

enum class ImagePurpose { LAYER, BACKGROUND }

@Composable
fun ToolPanel(ui: PhotoUi, vm: PhotoViewModel, state: EditorState, onPickImage: (ImagePurpose) -> Unit, onEditText: (String?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (ui.tool) {
            PhotoTool.PRESETS -> PresetsPanel(ui, vm)
            PhotoTool.ADJUST -> AdjustPanel(ui, vm)
            PhotoTool.CURVES -> CurvesPanel(ui, vm)
            PhotoTool.HSL -> HslPanel(ui, vm)
            PhotoTool.GRADE -> GradePanel(ui, vm)
            PhotoTool.DETAIL -> DetailPanel(ui, vm)
            PhotoTool.EFFECTS -> EffectsPanel(ui, vm)
            PhotoTool.CROP -> CropPanel(ui, vm, state)
            PhotoTool.HEAL -> HealPanel(vm, state)
            PhotoTool.MASKS -> MasksPanel(ui, vm, state)
            PhotoTool.BACKGROUND -> BackgroundPanel(ui, vm, onPickImage)
            PhotoTool.TEXT -> TextPanel(ui, vm, onEditText)
            PhotoTool.DRAW -> DrawPanel(ui, vm, state)
            PhotoTool.STICKERS -> StickerPanel(vm)
            PhotoTool.SHAPES -> ShapePanel(ui, vm)
            PhotoTool.LAYERS -> ImagesPanel(ui, vm, onPickImage)
            PhotoTool.FRAME -> FramePanel(ui, vm)
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
}

/** A slider bound to one global adjustment. */
@Composable
private fun A(ui: PhotoUi, vm: PhotoViewModel, label: String, get: (Adjustments) -> Float, set: (Adjustments, Float) -> Adjustments, range: ClosedFloatingPointRange<Float> = -100f..100f, default: Float = 0f, track: Brush? = null) {
    AdjustSlider(label, get(ui.edit.adjust), { v -> vm.adjust { set(it, v) } }, vm::beginGesture, vm::endGesture, range, default, track)
}

// ---------------------------------------------------------------- looks

@Composable
private fun PresetsPanel(ui: PhotoUi, vm: PhotoViewModel) {
    val source = ui.original
    val thumbs by produceState<Map<String, Bitmap>>(emptyMap(), source) {
        if (source == null) return@produceState
        value = withContext(Dispatchers.Default) {
            val s = 140f / max(source.width, source.height)
            val t = Bitmap.createScaledBitmap(source, max(1, (source.width * s).roundToInt()), max(1, (source.height * s).roundToInt()), true)
            val px = IntArray(t.width * t.height).also { t.getPixels(it, 0, t.width, 0, 0, t.width, t.height) }
            buildMap {
                put("none", t)
                for (p in Presets.all) put(p.id, Bitmap.createBitmap(PhotoPipeline.render(px, t.width, t.height, p.adjust), t.width, t.height, Bitmap.Config.ARGB_8888))
            }
        }
    }
    var group by remember { mutableStateOf(Presets.groups.first()) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = false, onClick = vm::autoEnhance, label = { Text("Auto") }, leadingIcon = { Icon(Icons.Rounded.AutoFixHigh, null, Modifier.size(18.dp)) })
        for (g in Presets.groups) FilterChip(selected = group == g, onClick = { group = g }, label = { Text(g) })
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PresetTile("Original", thumbs["none"], ui.edit.preset == null && ui.edit.adjust.isIdentity) { vm.applyPreset(null) }
        for (p in Presets.all.filter { it.group == group }) PresetTile(p.name, thumbs[p.id], ui.edit.preset == p.id) { vm.applyPreset(p, ui.edit.presetAmount.takeIf { ui.edit.preset != null } ?: 1f) }
    }
    Presets.byId(ui.edit.preset)?.let { p ->
        AdjustSlider("Strength", ui.edit.presetAmount * 100f, { v -> vm.change(commit = false) { e -> e.copy(adjust = Adjustments().mix(p.adjust, v / 100f), presetAmount = v / 100f) } }, vm::beginGesture, vm::endGesture, 0f..150f, 100f)
        Text("Tweak anything afterwards in Adjust, Curves or Colour mix.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PresetTile(name: String, thumb: Bitmap?, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp).clickable(onClick = onClick)) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))) {
            thumb?.let { Image(it.asImageBitmap(), null, Modifier.size(72.dp), contentScale = ContentScale.Crop) }
        }
        Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

// ---------------------------------------------------------------- light & colour

@Composable
private fun AdjustPanel(ui: PhotoUi, vm: PhotoViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Section("Light")
        Spacer(Modifier.weight(1f))
        TextButton(onClick = vm::autoEnhance) { Icon(Icons.Rounded.AutoFixHigh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Auto") }
    }
    A(ui, vm, "Exposure", { it.exposure }, { a, v -> a.copy(exposure = v) })
    A(ui, vm, "Brightness", { it.brightness }, { a, v -> a.copy(brightness = v) })
    A(ui, vm, "Contrast", { it.contrast }, { a, v -> a.copy(contrast = v) })
    A(ui, vm, "Highlights", { it.highlights }, { a, v -> a.copy(highlights = v) })
    A(ui, vm, "Shadows", { it.shadows }, { a, v -> a.copy(shadows = v) })
    A(ui, vm, "Whites", { it.whites }, { a, v -> a.copy(whites = v) })
    A(ui, vm, "Blacks", { it.blacks }, { a, v -> a.copy(blacks = v) })
    Section("Colour")
    A(ui, vm, "Temperature", { it.temperature }, { a, v -> a.copy(temperature = v) }, track = temperatureBrush)
    A(ui, vm, "Tint", { it.tint }, { a, v -> a.copy(tint = v) }, track = tintBrush)
    A(ui, vm, "Vibrance", { it.vibrance }, { a, v -> a.copy(vibrance = v) })
    A(ui, vm, "Saturation", { it.saturation }, { a, v -> a.copy(saturation = v) })
    A(ui, vm, "Hue", { it.hue }, { a, v -> a.copy(hue = v) }, track = hueBrush)
}

@Composable
private fun CurvesPanel(ui: PhotoUi, vm: PhotoViewModel) {
    var channel by remember { mutableIntStateOf(0) }
    val names = listOf("RGB", "Red", "Green", "Blue")
    val colors = listOf(MaterialTheme.colorScheme.onSurface, Color(0xFFEF4444), Color(0xFF22C55E), Color(0xFF3B82F6))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        names.forEachIndexed { i, n -> FilterChip(selected = channel == i, onClick = { channel = i }, label = { Text(n) }) }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { vm.change { e -> e.copy(adjust = e.adjust.copy(curves = e.adjust.curves.withChannel(channel, Curves.IDENTITY))) } }) { Text("Reset") }
    }
    val hist = ui.histogram?.let { h -> if (channel == 0) h[3] else h[channel - 1] }
    CurveEditor(ui.edit.adjust.curves.channel(channel), colors[channel], hist) { pts, done ->
        vm.beginGesture()
        vm.change(commit = false) { e -> e.copy(adjust = e.adjust.copy(curves = e.adjust.curves.withChannel(channel, pts))) }
        if (done) vm.endGesture()
    }
    Text("Drag to shape · tap to add a point · double-tap a point to remove it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val bandNames = listOf("Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta")
private val bandColors = listOf(0xFFEF4444, 0xFFF97316, 0xFFFACC15, 0xFF22C55E, 0xFF06B6D4, 0xFF3B82F6, 0xFF8B5CF6, 0xFFEC4899)

@Composable
private fun HslPanel(ui: PhotoUi, vm: PhotoViewModel) {
    var band by remember { mutableIntStateOf(0) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        bandColors.forEachIndexed { i, c -> SwatchDot(Color(c.toInt()), band == i) { band = i } }
    }
    Text(bandNames[band], style = MaterialTheme.typography.labelLarge)
    val b = ui.edit.adjust.hsl.getOrElse(band) { HslBand() }
    fun set(f: (HslBand) -> HslBand) = vm.adjust { a -> a.copy(hsl = a.hsl.mapIndexed { i, x -> if (i == band) f(x) else x }) }
    AdjustSlider("Hue", b.hue, { v -> set { it.copy(hue = v) } }, vm::beginGesture, vm::endGesture, track = hueBrush)
    AdjustSlider("Saturation", b.sat, { v -> set { it.copy(sat = v) } }, vm::beginGesture, vm::endGesture)
    AdjustSlider("Luminance", b.lum, { v -> set { it.copy(lum = v) } }, vm::beginGesture, vm::endGesture)
    TextButton(onClick = { vm.change { e -> e.copy(adjust = e.adjust.copy(hsl = List(8) { HslBand() })) } }) { Text("Reset all colours") }
}

@Composable
private fun GradePanel(ui: PhotoUi, vm: PhotoViewModel) {
    val g = ui.edit.adjust.grading
    fun set(f: (Grading) -> Grading) = vm.adjust { a -> a.copy(grading = f(a.grading)) }
    Section("Shadows")
    AdjustSlider("Hue", g.shadowHue, { v -> set { it.copy(shadowHue = v) } }, vm::beginGesture, vm::endGesture, 0f..360f, 220f, hueBrush, format = { "${it.roundToInt()}°" })
    AdjustSlider("Amount", g.shadowSat, { v -> set { it.copy(shadowSat = v) } }, vm::beginGesture, vm::endGesture, 0f..100f)
    Section("Midtones")
    AdjustSlider("Hue", g.midHue, { v -> set { it.copy(midHue = v) } }, vm::beginGesture, vm::endGesture, 0f..360f, 30f, hueBrush, format = { "${it.roundToInt()}°" })
    AdjustSlider("Amount", g.midSat, { v -> set { it.copy(midSat = v) } }, vm::beginGesture, vm::endGesture, 0f..100f)
    Section("Highlights")
    AdjustSlider("Hue", g.highHue, { v -> set { it.copy(highHue = v) } }, vm::beginGesture, vm::endGesture, 0f..360f, 45f, hueBrush, format = { "${it.roundToInt()}°" })
    AdjustSlider("Amount", g.highSat, { v -> set { it.copy(highSat = v) } }, vm::beginGesture, vm::endGesture, 0f..100f)
    AdjustSlider("Balance", g.balance, { v -> set { it.copy(balance = v) } }, vm::beginGesture, vm::endGesture)
}

@Composable
private fun DetailPanel(ui: PhotoUi, vm: PhotoViewModel) {
    A(ui, vm, "Clarity", { it.clarity }, { a, v -> a.copy(clarity = v) })
    A(ui, vm, "Dehaze", { it.dehaze }, { a, v -> a.copy(dehaze = v) })
    A(ui, vm, "Sharpen", { it.sharpen }, { a, v -> a.copy(sharpen = v) }, 0f..100f)
    A(ui, vm, "Noise reduction", { it.noiseReduction }, { a, v -> a.copy(noiseReduction = v) }, 0f..100f)
}

@Composable
private fun EffectsPanel(ui: PhotoUi, vm: PhotoViewModel) {
    A(ui, vm, "Vignette", { it.vignette }, { a, v -> a.copy(vignette = v) })
    A(ui, vm, "Vignette size", { it.vignetteMidpoint }, { a, v -> a.copy(vignetteMidpoint = v) }, 0f..100f, 50f)
    A(ui, vm, "Grain", { it.grain }, { a, v -> a.copy(grain = v) }, 0f..100f)
    A(ui, vm, "Fade", { it.fade }, { a, v -> a.copy(fade = v) }, 0f..100f)
    A(ui, vm, "Glow", { it.bloom }, { a, v -> a.copy(bloom = v) }, 0f..100f)
    A(ui, vm, "Colour fringe", { it.chromatic }, { a, v -> a.copy(chromatic = v) }, 0f..100f)
    A(ui, vm, "Posterize", { it.posterize }, { a, v -> a.copy(posterize = v) }, 0f..100f)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = ui.edit.adjust.monochrome, onClick = { vm.adjust(commit = true) { it.copy(monochrome = !it.monochrome) } }, label = { Text("Black & white") })
        FilterChip(selected = ui.edit.adjust.invert, onClick = { vm.adjust(commit = true) { it.copy(invert = !it.invert) } }, label = { Text("Invert") })
    }
}

// ---------------------------------------------------------------- geometry & retouch

private val aspects = listOf<Pair<String, Float?>>("Free" to null, "Original" to -1f, "1:1" to 1f, "4:5" to 0.8f, "5:4" to 1.25f, "3:2" to 1.5f, "2:3" to 2f / 3, "16:9" to 16f / 9, "9:16" to 9f / 16, "21:9" to 21f / 9)

@Composable
private fun CropPanel(ui: PhotoUi, vm: PhotoViewModel, state: EditorState) {
    LaunchedEffect(Unit) { vm.refreshUncropped() }
    val g = ui.edit.geometry
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, a) in aspects) {
            val value = if (a == -1f) ui.uncropped?.let { it.width.toFloat() / it.height } else a
            FilterChip(selected = state.cropAspect == value, onClick = {
                state.cropAspect = value
                val u = ui.uncropped
                if (value != null && u != null) {
                    // Fit the largest centred box of that shape.
                    val rel = value * u.height / u.width
                    val w = if (rel >= 1f) 1f else rel
                    val h = if (rel >= 1f) 1f / rel else 1f
                    vm.change { e -> e.copy(geometry = e.geometry.copy(left = (1 - w) / 2, right = (1 + w) / 2, top = (1 - h) / 2, bottom = (1 + h) / 2)) }
                }
            }, label = { Text(label) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.change { e -> e.copy(geometry = e.geometry.copy(quarter = (e.geometry.quarter + 3) % 4, left = 0f, top = 0f, right = 1f, bottom = 1f)) } }) { Icon(Icons.Rounded.RotateLeft, "Rotate left") }
        IconButton(onClick = { vm.change { e -> e.copy(geometry = e.geometry.copy(quarter = (e.geometry.quarter + 1) % 4, left = 0f, top = 0f, right = 1f, bottom = 1f)) } }) { Icon(Icons.Rounded.RotateRight, "Rotate right") }
        IconButton(onClick = { vm.change { e -> e.copy(geometry = e.geometry.copy(flipH = !e.geometry.flipH)) } }) { Icon(Icons.Rounded.Flip, "Flip horizontally") }
        IconButton(onClick = { vm.change { e -> e.copy(geometry = e.geometry.copy(flipV = !e.geometry.flipV)) } }) { Icon(Icons.Rounded.Flip, "Flip vertically", Modifier.rotate(90f)) }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { state.cropAspect = null; vm.change { e -> e.copy(geometry = Geometry()) } }) { Text("Reset") }
    }
    AdjustSlider("Straighten", g.straighten, { v -> vm.change(commit = false) { e -> e.copy(geometry = e.geometry.copy(straighten = v)) } }, vm::beginGesture, vm::endGesture, -45f..45f, format = { "%.1f°".format(it) })
    AdjustSlider("Vertical perspective", g.vertical, { v -> vm.change(commit = false) { e -> e.copy(geometry = e.geometry.copy(vertical = v)) } }, vm::beginGesture, vm::endGesture)
    AdjustSlider("Horizontal perspective", g.horizontal, { v -> vm.change(commit = false) { e -> e.copy(geometry = e.geometry.copy(horizontal = v)) } }, vm::beginGesture, vm::endGesture)
}

@Composable
private fun HealPanel(vm: PhotoViewModel, state: EditorState) {
    Text("Paint over blemishes, wires, people or anything else; it's filled in from its surroundings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    AdjustSlider("Brush size", state.brushSize * 1000f, { state.brushSize = it / 1000f }, {}, {}, 5f..150f, 40f)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.heal(state.healStrokes.toList()); state.healStrokes.clear() }, enabled = state.healStrokes.isNotEmpty()) { Icon(Icons.Rounded.AutoFixHigh, null); Spacer(Modifier.width(6.dp)); Text("Remove") }
        OutlinedButton(onClick = { state.healStrokes.removeLastOrNull() }, enabled = state.healStrokes.isNotEmpty()) { Icon(Icons.AutoMirrored.Rounded.Undo, null) }
        TextButton(onClick = { state.healStrokes.clear() }, enabled = state.healStrokes.isNotEmpty()) { Text("Clear") }
    }
    Text("Use two fingers to zoom in for small spots. Each fill can be undone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MasksPanel(ui: PhotoUi, vm: PhotoViewModel, state: EditorState) {
    val locals = ui.edit.locals
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        locals.forEachIndexed { i, l ->
            val kind = when (l.shape) { is MaskShape.Radial -> "Radial"; is MaskShape.Linear -> "Gradient"; is MaskShape.Brush -> "Brush" }
            FilterChip(selected = ui.selectedLocal == i, onClick = { vm.selectLocal(i) }, label = { Text("${i + 1}. $kind") })
        }
        OutlinedButton(onClick = { vm.addLocal(MaskShape.Radial(0.5f, 0.5f, 0.25f, 0.25f)) }) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Text("Radial") }
        OutlinedButton(onClick = { vm.addLocal(MaskShape.Linear(0.5f, 0.1f, 0.5f, 0.6f)) }) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Text("Gradient") }
        OutlinedButton(onClick = { vm.addLocal(MaskShape.Brush(emptyList())) }) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Text("Brush") }
    }
    val i = ui.selectedLocal
    val l = locals.getOrNull(i)
    if (l == null) {
        Text("Add a mask to adjust part of the photo: a radial area (faces, the sun), a gradient (skies) or a painted area.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = state.showMask, onClick = { state.showMask = !state.showMask }, label = { Text("Show mask") }, leadingIcon = { Icon(Icons.Rounded.Visibility, null, Modifier.size(18.dp)) })
        when (val sh = l.shape) {
            is MaskShape.Radial -> FilterChip(selected = sh.invert, onClick = { vm.updateLocal(i, commit = true) { it.copy(shape = sh.copy(invert = !sh.invert)) } }, label = { Text("Invert") })
            is MaskShape.Brush -> {
                FilterChip(selected = !state.maskErase, onClick = { state.maskErase = false }, label = { Text("Paint") })
                FilterChip(selected = state.maskErase, onClick = { state.maskErase = true }, label = { Text("Erase") })
            }
            else -> {}
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { vm.removeLocal(i) }) { Icon(Icons.Rounded.Delete, "Delete mask") }
    }
    (l.shape as? MaskShape.Radial)?.let { sh -> AdjustSlider("Feather", sh.feather * 100, { v -> vm.updateLocal(i) { it.copy(shape = sh.copy(feather = v / 100f)) } }, vm::beginGesture, vm::endGesture, 0f..100f, 50f) }
    if (l.shape is MaskShape.Brush) AdjustSlider("Brush size", state.brushSize * 1000f, { state.brushSize = it / 1000f }, {}, {}, 5f..200f, 40f)
    AdjustSlider("Amount", l.amount * 100, { v -> vm.updateLocal(i) { it.copy(amount = v / 100f) } }, vm::beginGesture, vm::endGesture, 0f..100f, 100f)
    for (row in localRows) {
        AdjustSlider(row.label, row.get(l.adjust), { v -> vm.updateLocal(i) { it.copy(adjust = row.set(it.adjust, v)) } }, vm::beginGesture, vm::endGesture, row.range, track = row.track)
    }
}

private class LocalRow(val label: String, val get: (Adjustments) -> Float, val set: (Adjustments, Float) -> Adjustments, val range: ClosedFloatingPointRange<Float> = -100f..100f, val track: Brush? = null)

private val localRows = listOf(
    LocalRow("Exposure", { it.exposure }, { a, v -> a.copy(exposure = v) }),
    LocalRow("Contrast", { it.contrast }, { a, v -> a.copy(contrast = v) }),
    LocalRow("Highlights", { it.highlights }, { a, v -> a.copy(highlights = v) }),
    LocalRow("Shadows", { it.shadows }, { a, v -> a.copy(shadows = v) }),
    LocalRow("Temperature", { it.temperature }, { a, v -> a.copy(temperature = v) }, track = temperatureBrush),
    LocalRow("Tint", { it.tint }, { a, v -> a.copy(tint = v) }, track = tintBrush),
    LocalRow("Saturation", { it.saturation }, { a, v -> a.copy(saturation = v) }),
    LocalRow("Clarity", { it.clarity }, { a, v -> a.copy(clarity = v) }),
    LocalRow("Dehaze", { it.dehaze }, { a, v -> a.copy(dehaze = v) }),
    LocalRow("Sharpen", { it.sharpen }, { a, v -> a.copy(sharpen = v) }, 0f..100f),
    LocalRow("Noise reduction", { it.noiseReduction }, { a, v -> a.copy(noiseReduction = v) }, 0f..100f),
)

@Composable
private fun BackgroundPanel(ui: PhotoUi, vm: PhotoViewModel, onPickImage: (ImagePurpose) -> Unit) {
    val bg = ui.edit.background
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (m in BackgroundMode.entries) FilterChip(selected = bg.mode == m, onClick = {
            when (m) {
                BackgroundMode.KEEP -> vm.change { it.copy(background = it.background.copy(mode = m)) }
                BackgroundMode.IMAGE -> onPickImage(ImagePurpose.BACKGROUND)
                else -> vm.cutOut(m)
            }
        }, label = { Text(m.label) })
    }
    when (bg.mode) {
        BackgroundMode.COLOR -> ColorRow(bg.color, { c -> vm.change { it.copy(background = it.background.copy(color = c)) } })
        BackgroundMode.BLUR -> AdjustSlider("Blur", bg.blur, { v -> vm.change(commit = false) { it.copy(background = it.background.copy(blur = v)) } }, vm::beginGesture, vm::endGesture, 0f..100f, 50f)
        else -> {}
    }
    if (bg.mode != BackgroundMode.KEEP) {
        AdjustSlider("Edge softness", bg.edge, { v -> vm.change(commit = false) { it.copy(background = it.background.copy(edge = v)) } }, vm::beginGesture, vm::endGesture, 0f..100f, 30f)
        TextButton(onClick = { vm.redetectSubject(bg.mode) }) { Text("Find the subject again") }
        if (bg.mode == BackgroundMode.TRANSPARENT) Text("Export as PNG or WebP to keep the transparency.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text("Cut out people, pets and objects on your phone, then remove, blur, recolour or replace what's behind them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- overlays

@Composable
private fun LayerActions(vm: PhotoViewModel, id: String, extra: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        extra()
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { vm.reorderOverlay(id, up = false) }) { Icon(Icons.Rounded.FlipToBack, "Send backward") }
        IconButton(onClick = { vm.reorderOverlay(id, up = true) }) { Icon(Icons.Rounded.FlipToFront, "Bring forward") }
        IconButton(onClick = { vm.duplicateOverlay(id) }) { Icon(Icons.Rounded.ContentCopy, "Duplicate") }
        IconButton(onClick = { vm.removeOverlay(id) }) { Icon(Icons.Rounded.Delete, "Delete") }
    }
}

@Composable
private fun OpacitySlider(vm: PhotoViewModel, o: Overlay) {
    AdjustSlider("Opacity", o.opacity * 100, { v ->
        vm.updateOverlay(o.id, commit = false) { x ->
            when (x) {
                is Overlay.TextLayer -> x.copy(opacity = v / 100f)
                is Overlay.StickerLayer -> x.copy(opacity = v / 100f)
                is Overlay.ShapeLayer -> x.copy(opacity = v / 100f)
                is Overlay.ImageLayer -> x.copy(opacity = v / 100f)
                is Overlay.DrawingLayer -> x.copy(opacity = v / 100f)
            }
        }
    }, vm::beginGesture, vm::endGesture, 0f..100f, 100f)
}

@Composable
private fun TextPanel(ui: PhotoUi, vm: PhotoViewModel, onEditText: (String?) -> Unit) {
    val t = ui.edit.overlays.firstOrNull { it.id == ui.selected } as? Overlay.TextLayer
    if (t == null) {
        Button(onClick = { onEditText(null) }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Add text") }
        Text("Tap text on the photo to select it; drag to move, pinch to resize and rotate, double-tap to edit.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    fun set(commit: Boolean = true, f: (Overlay.TextLayer) -> Overlay.TextLayer) = vm.updateOverlay(t.id, commit) { f(it as Overlay.TextLayer) }
    LayerActions(vm, t.id) {
        IconButton(onClick = { onEditText(t.id) }) { Icon(Icons.Rounded.Edit, "Edit text") }
        IconButton(onClick = { set { it.copy(bold = !it.bold) } }) { Icon(Icons.Rounded.FormatBold, "Bold", tint = if (t.bold) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        IconButton(onClick = { set { it.copy(italic = !it.italic) } }) { Icon(Icons.Rounded.FormatItalic, "Italic", tint = if (t.italic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (f in TextFont.entries) FilterChip(selected = t.font == f, onClick = { set { it.copy(font = f) } }, label = { Text(f.label) })
    }
    Section("Colour")
    ColorRow(t.color, { c -> set { it.copy(color = c) } })
    Section("Outline")
    ColorRow(t.outline, { c -> set { it.copy(outline = c) } }, allowNone = true, onNone = { set { it.copy(outline = null) } })
    Section("Background")
    ColorRow(t.background, { c -> set { it.copy(background = c) } }, allowNone = true, onNone = { set { it.copy(background = null) } })
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Left", "Centre", "Right").forEachIndexed { i, n -> FilterChip(selected = t.align == i, onClick = { set { it.copy(align = i) } }, label = { Text(n) }) }
        FilterChip(selected = t.shadow, onClick = { set { it.copy(shadow = !it.shadow) } }, label = { Text("Shadow") })
    }
    AdjustSlider("Size", t.scale * 100, { v -> set(false) { it.copy(scale = v / 100f) } }, vm::beginGesture, vm::endGesture, 2f..60f, 12f)
    AdjustSlider("Letter spacing", t.letterSpacing * 100, { v -> set(false) { it.copy(letterSpacing = v / 100f) } }, vm::beginGesture, vm::endGesture, -10f..50f, 0f)
    AdjustSlider("Curve", t.curve, { v -> set(false) { it.copy(curve = v) } }, vm::beginGesture, vm::endGesture)
    AdjustSlider("Outline width", t.outlineWidth * 100, { v -> set(false) { it.copy(outlineWidth = v / 100f) } }, vm::beginGesture, vm::endGesture, 1f..30f, 8f)
    AdjustSlider("Rotation", t.rotation, { v -> set(false) { it.copy(rotation = v) } }, vm::beginGesture, vm::endGesture, -180f..180f, format = { "${it.roundToInt()}°" })
    OpacitySlider(vm, t)
}

@Composable
private fun DrawPanel(ui: PhotoUi, vm: PhotoViewModel, state: EditorState) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (k in BrushKind.entries) FilterChip(selected = !state.eraser && state.drawKind == k, onClick = { state.drawKind = k; state.eraser = false }, label = { Text(k.label) })
        FilterChip(selected = state.eraser, onClick = { state.eraser = !state.eraser }, label = { Text("Eraser") })
    }
    ColorRow(state.drawColor, { state.drawColor = it; state.eraser = false })
    AdjustSlider("Size", state.drawWidth * 1000, { state.drawWidth = it / 1000f }, {}, {}, 2f..80f, 12f)
    val layer = ui.edit.overlays.firstOrNull { it.id == ui.selected } as? Overlay.DrawingLayer
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { layer?.let { l -> vm.updateOverlay(l.id) { (it as Overlay.DrawingLayer).copy(strokes = it.strokes.dropLast(1)) } } }, enabled = layer?.strokes?.isNotEmpty() == true) {
            Icon(Icons.AutoMirrored.Rounded.Undo, null); Spacer(Modifier.width(4.dp)); Text("Last stroke")
        }
        TextButton(onClick = { vm.select(null) }) { Text("New drawing layer") }
    }
    Text("Draw with one finger; use two to zoom. Each drawing is a layer you can move and resize from the Images tab.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val stickerSets = linkedMapOf(
    "Smileys" to "😀 😂 🥹 😍 🥰 😎 🤩 😜 🤔 😴 😭 😡 🥳 😇 🤯 🫶 👍 👏 🙌 💪 ✌️ 🤞 👀 🙈",
    "Love" to "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💖 💘 💕 💯 💋 🌹 💐 💍",
    "Nature" to "🌸 🌻 🌷 🌴 🌵 🍀 🍁 🌈 ☀️ 🌙 ⭐ ✨ ⚡ 🔥 ❄️ 🌊 🌍 🦋 🐶 🐱 🦊 🐼 🐸 🦄",
    "Food" to "🍕 🍔 🍟 🌮 🍣 🍜 🍩 🍪 🎂 🍰 🍓 🍉 🍒 🥑 ☕ 🍷 🍸 🧋",
    "Fun" to "🎉 🎊 🎈 🎁 🏆 🥇 🎵 🎶 🎮 📸 🎬 🚀 ✈️ 🚗 🏖️ 🏔️ 🎯 💎 👑 🕶️ 💡 📍 ✅ ❌",
    "Flags" to "🏳️‍🌈 🇺🇳 🇮🇳 🇺🇸 🇬🇧 🇯🇵 🇫🇷 🇩🇪 🇧🇷 🇨🇦 🇦🇺 🇪🇸 🇮🇹 🇰🇷 🇨🇳 🇲🇽",
)

@Composable
private fun StickerPanel(vm: PhotoViewModel) {
    var set by remember { mutableStateOf(stickerSets.keys.first()) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (k in stickerSets.keys) FilterChip(selected = set == k, onClick = { set = k }, label = { Text(k) })
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (e in stickerSets[set].orEmpty().split(' ')) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).clickable { vm.addOverlay(Overlay.StickerLayer(vm.newId(), e)) }, contentAlignment = Alignment.Center) {
                Text(e, fontSize = 28.sp)
            }
        }
    }
}

@Composable
private fun ShapePanel(ui: PhotoUi, vm: PhotoViewModel) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (k in ShapeKind.entries) OutlinedButton(onClick = { vm.addOverlay(Overlay.ShapeLayer(vm.newId(), k, aspect = if (k == ShapeKind.LINE || k == ShapeKind.ARROW) 3f else 1f)) }) { Text(k.label) }
    }
    val s = ui.edit.overlays.firstOrNull { it.id == ui.selected } as? Overlay.ShapeLayer ?: return
    fun set(commit: Boolean = true, f: (Overlay.ShapeLayer) -> Overlay.ShapeLayer) = vm.updateOverlay(s.id, commit) { f(it as Overlay.ShapeLayer) }
    LayerActions(vm, s.id) { FilterChip(selected = s.fill, onClick = { set { it.copy(fill = !it.fill) } }, label = { Text("Filled") }) }
    ColorRow(s.color, { c -> set { it.copy(color = c) } })
    AdjustSlider("Line width", s.strokeWidth * 1000, { v -> set(false) { it.copy(strokeWidth = v / 1000f) } }, vm::beginGesture, vm::endGesture, 2f..80f, 20f)
    AdjustSlider("Stretch", s.aspect * 100, { v -> set(false) { it.copy(aspect = v / 100f) } }, vm::beginGesture, vm::endGesture, 20f..500f, 100f)
    OpacitySlider(vm, s)
}

@Composable
private fun ImagesPanel(ui: PhotoUi, vm: PhotoViewModel, onPickImage: (ImagePurpose) -> Unit) {
    Button(onClick = { onPickImage(ImagePurpose.LAYER) }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Add an image") }
    val o = ui.edit.overlays.firstOrNull { it.id == ui.selected }
    if (o == null) {
        Text("Overlay logos, photos or textures and blend them in. Tap a layer on the photo to select it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LayerActions(vm, o.id)
    if (o is Overlay.ImageLayer) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in BlendMode.entries) FilterChip(selected = o.blend == m, onClick = { vm.updateOverlay(o.id) { (it as Overlay.ImageLayer).copy(blend = m) } }, label = { Text(m.label) })
        }
    }
    OpacitySlider(vm, o)
}

@Composable
private fun FramePanel(ui: PhotoUi, vm: PhotoViewModel) {
    val f = ui.edit.frame
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (k in FrameKind.entries) FilterChip(selected = f.kind == k, onClick = { vm.change { it.copy(frame = it.frame.copy(kind = k, color = if (k == FrameKind.FILM) 0xFF121212 else it.frame.color)) } }, label = { Text(k.label) })
    }
    if (f.kind != FrameKind.NONE && f.kind != FrameKind.FILM) ColorRow(f.color, { c -> vm.change { it.copy(frame = it.frame.copy(color = c)) } })
    if (f.kind == FrameKind.SOLID || f.kind == FrameKind.ROUNDED) AdjustSlider("Width", f.size * 1000, { v -> vm.change(commit = false) { it.copy(frame = it.frame.copy(size = v / 1000f)) } }, vm::beginGesture, vm::endGesture, 5f..200f, 50f)
    if (f.kind == FrameKind.ROUNDED || f.kind == FrameKind.SHADOW) AdjustSlider("Corner radius", f.radius * 1000, { v -> vm.change(commit = false) { it.copy(frame = it.frame.copy(radius = v / 1000f)) } }, vm::beginGesture, vm::endGesture, 0f..250f, 60f)
}
