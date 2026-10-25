package com.vitmap.app.feature.color

import android.app.Application
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vitmap.app.vitmap
import com.vitmap.core.color.ColorNamer
import com.vitmap.core.color.CssColors
import com.vitmap.core.color.Rgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class SavedColor(val argb: Int, val name: String, val time: Long) {
    val rgb: Rgb get() = Rgb.fromArgb(argb or (0xFF shl 24))
}

class ColorViewModel(app: Application) : AndroidViewModel(app) {
    private val store = app.vitmap.store("colors", kotlinx.serialization.builtins.ListSerializer(SavedColor.serializer())) { emptyList() }

    private val _live = MutableStateFlow<Rgb?>(null)
    val live: StateFlow<Rgb?> = _live.asStateFlow()

    val frozen = MutableStateFlow(false)
    val sampleRadius = MutableStateFlow(4)

    private val _namer = MutableStateFlow<ColorNamer?>(null)
    val namerReady: StateFlow<Boolean> = _namer.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val saved: StateFlow<List<SavedColor>> = store.flow.map { it ?: emptyList() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val compareA = MutableStateFlow(Rgb(255, 87, 51))
    val compareB = MutableStateFlow(Rgb(51, 153, 255))

    private var smooth = DoubleArray(3)
    private var hasSmooth = false
    private var lastEmit = 0L

    val analyzer = ImageAnalysis.Analyzer { image -> analyze(image) }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            store.load()
            val text = runCatching { app.assets.open("colornames.csv").bufferedReader().use { it.readText() } }.getOrNull()
            _namer.value = if (text != null) ColorNamer.fromCsv(text) else ColorNamer.css
        }
    }

    private fun analyze(image: ImageProxy) {
        try {
            if (frozen.value) return
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val cx = image.width / 2
            val cy = image.height / 2
            val r = sampleRadius.value
            var sr = 0L
            var sg = 0L
            var sb = 0L
            var n = 0
            for (y in cy - r..cy + r) {
                if (y !in 0 until image.height) continue
                for (x in cx - r..cx + r) {
                    if (x !in 0 until image.width) continue
                    val i = y * rowStride + x * pixelStride
                    if (i + 2 >= buf.limit()) continue
                    sr += buf.get(i).toInt() and 0xFF
                    sg += buf.get(i + 1).toInt() and 0xFF
                    sb += buf.get(i + 2).toInt() and 0xFF
                    n++
                }
            }
            if (n == 0) return
            val cur = doubleArrayOf(sr.toDouble() / n, sg.toDouble() / n, sb.toDouble() / n)
            if (!hasSmooth) {
                smooth = cur
                hasSmooth = true
            } else {
                for (k in 0..2) smooth[k] = smooth[k] * 0.55 + cur[k] * 0.45
            }
            val now = System.currentTimeMillis()
            if (now - lastEmit > 70) {
                lastEmit = now
                _live.value = Rgb(smooth[0].roundToInt().coerceIn(0, 255), smooth[1].roundToInt().coerceIn(0, 255), smooth[2].roundToInt().coerceIn(0, 255))
            }
        } finally {
            image.close()
        }
    }

    fun nameOf(rgb: Rgb): String {
        CssColors.nameOf(rgb)?.let { css -> return css.replaceFirstChar { it.uppercase() } }
        val namer = _namer.value ?: return "…"
        return namer.nearest(rgb)?.first?.name ?: "Unknown"
    }

    fun toggleFreeze() {
        frozen.value = !frozen.value
    }

    fun save(rgb: Rgb) {
        viewModelScope.launch {
            store.update { list -> listOf(SavedColor(rgb.argb, nameOf(rgb), System.currentTimeMillis())) + list.filter { it.argb != rgb.argb } }
        }
    }

    fun delete(c: SavedColor) {
        viewModelScope.launch { store.update { list -> list.filter { it.time != c.time } } }
    }

    fun clearAll() {
        viewModelScope.launch { store.set(emptyList()) }
    }
}
