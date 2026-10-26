package com.vitmap.app.feature.clock

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.vitmap.app.ui.theme.Accents
import com.vitmap.app.ui.theme.LocalDarkTheme
import com.vitmap.core.geo.NaturalEarth
import com.vitmap.core.geo.Sun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Map camera in projected units: centre and zoom (1 = whole world fits). */
class MapCamera {
    val cx = Animatable(0f)
    val cy = Animatable(0f)
    val zoom = Animatable(1f)

    suspend fun snap(x: Float, y: Float, z: Float) {
        zoom.snapTo(z.coerceIn(1f, 48f))
        cx.snapTo(x.coerceIn(-NaturalEarth.MAX_X.toFloat(), NaturalEarth.MAX_X.toFloat()))
        cy.snapTo(y.coerceIn(-NaturalEarth.MAX_Y.toFloat(), NaturalEarth.MAX_Y.toFloat()))
    }

    suspend fun animateTo(x: Float, y: Float, z: Float) = coroutineScope {
        val spec = tween<Float>(950, easing = FastOutSlowInEasing)
        launch { zoom.animateTo(z.coerceIn(1f, 48f), spec) }
        launch { cx.animateTo(x, spec) }
        launch { cy.animateTo(y, spec) }
    }
}

private class MapGeometry(val land: Path, val outline: Path, val graticule: Path, val shapes: Map<String, Path>)

private fun buildGeometry(world: World): MapGeometry {
    val land = Path()
    val shapes = HashMap<String, Path>()
    for (s in world.shapes) {
        val p = Path()
        for (r in s.rings) {
            p.moveTo(r[0], r[1])
            var i = 2
            while (i < r.size) {
                p.lineTo(r[i], r[i + 1])
                i += 2
            }
            p.close()
        }
        shapes[s.code] = p
        land.addPath(p)
    }
    val outline = Path()
    for (lat in -90..90) {
        val (x, y) = NaturalEarth.forward(-180.0, lat.toDouble())
        if (lat == -90) outline.moveTo(x.toFloat(), y.toFloat()) else outline.lineTo(x.toFloat(), y.toFloat())
    }
    for (lat in 90 downTo -90) {
        val (x, y) = NaturalEarth.forward(180.0, lat.toDouble())
        outline.lineTo(x.toFloat(), y.toFloat())
    }
    outline.close()
    val grat = Path()
    for (lon in -180..180 step 30) {
        for (lat in -90..90 step 3) {
            val (x, y) = NaturalEarth.forward(lon.toDouble(), lat.toDouble())
            if (lat == -90) grat.moveTo(x.toFloat(), y.toFloat()) else grat.lineTo(x.toFloat(), y.toFloat())
        }
    }
    for (lat in -60..60 step 30) {
        for (lon in -180..180 step 5) {
            val (x, y) = NaturalEarth.forward(lon.toDouble(), lat.toDouble())
            if (lon == -180) grat.moveTo(x.toFloat(), y.toFloat()) else grat.lineTo(x.toFloat(), y.toFloat())
        }
    }
    return MapGeometry(land, outline, grat, shapes)
}

/**
 * Night shading in projected space: each pixel's sun altitude, faded through civil, nautical and
 * astronomical twilight. Rows share the inverse latitude, so this is cheap enough to redo each minute.
 */
private fun nightBitmap(now: Long, w: Int = 420, h: Int = 220): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val px = IntArray(w * h)
    val pos = Sun.position(now)
    val decl = Math.toRadians(pos.declination)
    val utcMin = ((now % 86_400_000L + 86_400_000L) % 86_400_000L) / 60_000.0
    for (row in 0 until h) {
        val y = NaturalEarth.MAX_Y - (row + 0.5) / h * 2 * NaturalEarth.MAX_Y
        val first = NaturalEarth.inverse(0.0, y) ?: continue
        val lat = Math.toRadians(first.second)
        val xs = NaturalEarth.xScale(lat)
        val sinLat = sin(lat) * sin(decl)
        val cosLat = cos(lat) * cos(decl)
        for (col in 0 until w) {
            val x = -NaturalEarth.MAX_X + (col + 0.5) / w * 2 * NaturalEarth.MAX_X
            val lambda = x / xs
            if (abs(lambda) > PI) continue
            val lon = Math.toDegrees(lambda)
            val hourAngle = Math.toRadians((utcMin + pos.equationOfTime + 4 * lon) / 4 - 180)
            val alt = 90 - Math.toDegrees(acos((sinLat + cosLat * cos(hourAngle)).coerceIn(-1.0, 1.0)))
            val t = ((0.8 - alt) / 18.8).coerceIn(0.0, 1.0)
            val a = (t * t * (3 - 2 * t) * 0.62 * 255).toInt()
            if (a > 0) px[row * w + col] = (a shl 24) or (0x06 shl 16) or (0x0B shl 8) or 0x26
        }
    }
    bmp.setPixels(px, 0, w, 0, 0, w, h)
    return bmp
}

@Composable
fun WorldMap(
    world: World,
    clocks: List<ClockEntry>,
    selectedId: String?,
    selectedCountry: String?,
    now: Long,
    use24h: Boolean,
    camera: MapCamera,
    onCountryTap: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val geometry = remember(world) { buildGeometry(world) }
    val scope = rememberCoroutineScope()
    val dark = LocalDarkTheme.current
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current.density
    val minute = now / 60_000
    val night by produceState<Bitmap?>(null, minute) { value = withContext(Dispatchers.Default) { nightBitmap(now) } }
    val pulse by rememberInfiniteTransition(label = "pin").animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    var viewSize by remember { mutableStateOf(Offset(1f, 1f)) }

    val ocean = if (dark) 0xFF0B1730.toInt() else 0xFFDCEBFA.toInt()
    val landColor = if (dark) 0xFF263349.toInt() else 0xFFF6F3EC.toInt()
    val border = if (dark) 0xFF41506B.toInt() else 0xFFC9C2B4.toInt()
    val accent = Accents.Clock.start.toArgb()
    val fills = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val strokes = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND } }
    val text = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) } }
    val formatter = remember(use24h) { DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a") }

    fun baseScale(w: Float, h: Float) = min(w / (2 * NaturalEarth.MAX_X.toFloat()), h / (2 * NaturalEarth.MAX_Y.toFloat()))

    // Frame the selected country.
    LaunchedEffect(selectedCountry, viewSize) {
        val code = selectedCountry
        val shape = code?.let { world.shapeByCode[it] }
        if (shape == null) {
            if (code == null) camera.animateTo(0f, 0f, 1f)
            return@LaunchedEffect
        }
        val b = shape.focusBounds
        val s0 = baseScale(viewSize.x, viewSize.y)
        val bw = max(b.width(), 0.02f) * s0
        val bh = max(b.height(), 0.02f) * s0
        val z = (min(viewSize.x / bw, viewSize.y / bh) * 0.62f).coerceIn(1.4f, 36f)
        camera.animateTo(b.centerX(), b.centerY(), z)
    }

    BoxWithConstraints(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { viewSize = Offset(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(world) {
                    detectTransformGestures { centroid, pan, zoomChange, _ ->
                        scope.launch {
                            val s0 = baseScale(size.width.toFloat(), size.height.toFloat())
                            val z0 = camera.zoom.value
                            val z1 = (z0 * zoomChange).coerceIn(1f, 48f)
                            val cxv = size.width / 2f
                            val cyv = size.height / 2f
                            // Keep the point under the fingers fixed while zooming.
                            val px = camera.cx.value + (centroid.x - cxv) / (s0 * z0)
                            val py = camera.cy.value - (centroid.y - cyv) / (s0 * z0)
                            val nx = px - (centroid.x - cxv) / (s0 * z1) - pan.x / (s0 * z1)
                            val ny = py + (centroid.y - cyv) / (s0 * z1) + pan.y / (s0 * z1)
                            camera.snap(nx, ny, z1)
                        }
                    }
                }
                .pointerInput(world) {
                    detectTapGestures { tap ->
                        val s = baseScale(size.width.toFloat(), size.height.toFloat()) * camera.zoom.value
                        val x = camera.cx.value + (tap.x - size.width / 2f) / s
                        val y = camera.cy.value - (tap.y - size.height / 2f) / s
                        onCountryTap(world.countryAt(x, y)?.code)
                    }
                },
        ) {
            val s = baseScale(size.width, size.height) * camera.zoom.value
            val cx = camera.cx.value
            val cy = camera.cy.value
            fun toScreen(lon: Double, lat: Double): Offset {
                val (x, y) = NaturalEarth.forward(lon, lat)
                return Offset(size.width / 2 + ((x - cx) * s).toFloat(), size.height / 2 - ((y - cy) * s).toFloat())
            }
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save()
                nc.translate(size.width / 2, size.height / 2)
                nc.scale(s, -s)
                nc.translate(-cx, -cy)
                fills.color = ocean
                nc.drawPath(geometry.outline, fills)
                nc.save()
                nc.clipPath(geometry.outline)
                strokes.color = (if (dark) 0x22FFFFFF else 0x1A3B5B8C)
                strokes.strokeWidth = 1f / s
                nc.drawPath(geometry.graticule, strokes)
                fills.color = landColor
                nc.drawPath(geometry.land, fills)
                selectedCountry?.let { code ->
                    geometry.shapes[code]?.let { p ->
                        fills.color = (accent and 0x00FFFFFF) or (0x55 shl 24)
                        nc.drawPath(p, fills)
                    }
                }
                strokes.color = border
                strokes.strokeWidth = 0.7f * density / s
                nc.drawPath(geometry.land, strokes)
                selectedCountry?.let { code ->
                    geometry.shapes[code]?.let { p ->
                        strokes.color = accent
                        strokes.strokeWidth = 2f * density / s
                        nc.drawPath(p, strokes)
                    }
                }
                night?.let { bmp ->
                    // The bitmap covers the projected bounding box, y up.
                    nc.save()
                    nc.scale(1f, -1f)
                    val r = RectF(-NaturalEarth.MAX_X.toFloat(), -NaturalEarth.MAX_Y.toFloat(), NaturalEarth.MAX_X.toFloat(), NaturalEarth.MAX_Y.toFloat())
                    nc.drawBitmap(bmp, null, r, Paint(Paint.FILTER_BITMAP_FLAG))
                    nc.restore()
                }
                nc.restore()
                nc.restore()

                // Sun position
                val (slat, slon) = Sun.subsolar(now)
                val sun = toScreen(slon, slat)
                fills.color = 0x55FFC94D
                nc.drawCircle(sun.x, sun.y, 13f * density, fills)
                fills.color = 0xFFFFC94D.toInt()
                nc.drawCircle(sun.x, sun.y, 6f * density, fills)

                // Clock pins with their local time
                text.textSize = 11f * density
                for (clock in clocks) {
                    val p = toScreen(clock.lon, clock.lat)
                    if (p.x < -40 || p.y < -40 || p.x > size.width + 40 || p.y > size.height + 40) continue
                    val selected = clock.id == selectedId
                    if (selected) {
                        fills.color = (accent and 0x00FFFFFF) or (((1 - pulse) * 140).toInt() shl 24)
                        nc.drawCircle(p.x, p.y, (6 + 18 * pulse) * density, fills)
                    }
                    fills.color = 0xFFFFFFFF.toInt()
                    nc.drawCircle(p.x, p.y, 5.5f * density, fills)
                    fills.color = accent
                    nc.drawCircle(p.x, p.y, 3.6f * density, fills)
                    val label = Instant.ofEpochMilli(now).atZone(runCatching { ZoneId.of(clock.zone) }.getOrDefault(ZoneId.systemDefault())).format(formatter)
                    val name = if (camera.zoom.value > 2.2f || selected) "${clock.label}  $label" else label
                    val tw = text.measureText(name)
                    val pad = 5f * density
                    val bx = p.x + 8 * density
                    val by = p.y - 9 * density
                    fills.color = if (dark) 0xE6151B2E.toInt() else 0xEEFFFFFF.toInt()
                    nc.drawRoundRect(bx, by - text.textSize, bx + tw + pad * 2, by + pad, 8 * density, 8 * density, fills)
                    text.color = if (selected) accent else scheme.onSurface.toArgb()
                    nc.drawText(name, bx + pad, by, text)
                }
            }
        }
    }
}
