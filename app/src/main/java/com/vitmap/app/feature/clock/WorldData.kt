package com.vitmap.app.feature.clock

import android.content.Context
import android.graphics.RectF
import com.vitmap.core.geo.NaturalEarth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import kotlin.math.abs

/** A country outline in Natural Earth map units (x right, y up). */
class CountryShape(
    val code: String,
    val name: String,
    val labelLat: Double,
    val labelLon: Double,
    /** Each ring as x,y pairs. */
    val rings: List<FloatArray>,
) {
    val ringBounds: List<RectF> = rings.map { r ->
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in r.indices step 2) {
            minX = minOf(minX, r[i]); maxX = maxOf(maxX, r[i])
            minY = minOf(minY, r[i + 1]); maxY = maxOf(maxY, r[i + 1])
        }
        RectF(minX, minY, maxX, maxY)
    }

    val bounds: RectF = RectF(ringBounds.minOf { it.left }, ringBounds.minOf { it.top }, ringBounds.maxOf { it.right }, ringBounds.maxOf { it.bottom })

    /** The area worth framing: the main landmass plus other large parts (not far-flung islets). */
    val focusBounds: RectF by lazy {
        val areas = rings.map { ringArea(it) }
        val biggest = areas.maxOrNull() ?: 0.0
        val keep = rings.indices.filter { areas[it] >= biggest * 0.2 }
        RectF(keep.minOf { ringBounds[it].left }, keep.minOf { ringBounds[it].top }, keep.maxOf { ringBounds[it].right }, keep.maxOf { ringBounds[it].bottom })
    }

    fun contains(x: Float, y: Float): Boolean {
        if (!bounds.contains(x, y)) return false
        var inside = false
        for ((k, r) in rings.withIndex()) {
            if (!ringBounds[k].contains(x, y)) continue
            var j = r.size - 2
            var i = 0
            while (i < r.size) {
                val xi = r[i]; val yi = r[i + 1]; val xj = r[j]; val yj = r[j + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
                i += 2
            }
        }
        return inside
    }

    private fun ringArea(r: FloatArray): Double {
        var a = 0.0
        var j = r.size - 2
        for (i in r.indices step 2) {
            a += (r[j].toDouble() * r[i + 1] - r[i].toDouble() * r[j + 1])
            j = i
        }
        return abs(a) / 2
    }
}

data class ZoneInfo(val id: String, val lat: Double, val lon: Double, val countries: List<String>, val comment: String)

data class City(val name: String, val country: String, val iso2: String, val province: String, val lat: Double, val lon: Double, val population: Int, val zone: String) {
    val search: String = "$name $country $province".lowercase()
}

data class CountryInfo(val code: String, val name: String, val capital: String, val lat: Double, val lon: Double, val region: String, val subregion: String)

class World(
    val shapes: List<CountryShape>,
    val zones: List<ZoneInfo>,
    val cities: List<City>,
    val countries: Map<String, CountryInfo>,
) {
    val shapeByCode = shapes.associateBy { it.code }
    val zonesByCountry: Map<String, List<ZoneInfo>> = zones.flatMap { z -> z.countries.map { it to z } }.groupBy({ it.first }, { it.second })

    fun countryAt(x: Float, y: Float): CountryShape? = shapes.firstOrNull { it.contains(x, y) }

    /** The biggest city in [zone], used to place a zone on the map. */
    fun cityForZone(zone: String): City? = cities.filter { it.zone == zone }.maxByOrNull { it.population }
}

fun flagEmoji(iso2: String): String {
    if (iso2.length != 2 || !iso2.all { it in 'A'..'Z' }) return "🌐"
    return String(Character.toChars(0x1F1E6 + (iso2[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (iso2[1] - 'A')))
}

/** Loads the bundled Natural Earth outlines, time-zone table and city list (about 330 KB). */
object WorldData {
    private val mutex = Mutex()
    @Volatile private var cached: World? = null

    suspend fun load(context: Context): World = mutex.withLock {
        cached?.let { return it }
        withContext(Dispatchers.Default) {
            val shapes = readShapes(context)
            val zones = context.assets.open("world/zones.tsv").bufferedReader().readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 4) null else ZoneInfo(p[0], p[1].toDouble(), p[2].toDouble(), p[3].split(',').filter { it.isNotEmpty() }, p.getOrElse(4) { "" })
            }
            val cities = context.assets.open("world/cities.tsv").bufferedReader().readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 8) null else City(p[0], p[1], p[2], p[3], p[4].toDouble(), p[5].toDouble(), p[6].toIntOrNull() ?: 0, p[7])
            }.sortedByDescending { it.population }
            val countries = context.assets.open("world/countries.tsv").bufferedReader().readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 7) null else CountryInfo(p[0], p[1], p[2], p[3].toDouble(), p[4].toDouble(), p[5], p[6])
            }.associateBy { it.code }
            World(shapes, zones, cities, countries).also { cached = it }
        }
    }

    private fun readShapes(context: Context): List<CountryShape> {
        val input = DataInputStream(context.assets.open("world/map.bin").buffered(64 * 1024))
        val magic = ByteArray(4).also { input.readFully(it) }
        require(String(magic) == "VMAP") { "Bad map data" }
        input.readUnsignedByte()
        val count = u16(input)
        val out = ArrayList<CountryShape>(count)
        repeat(count) {
            val code = String(ByteArray(2).also { input.readFully(it) })
            val nameBytes = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            val lat = s16(input) / 100.0
            val lon = s16(input) / 100.0
            val rings = ArrayList<FloatArray>()
            repeat(u16(input)) {
                val n = u16(input)
                val pts = FloatArray(n * 2)
                var x = 0
                var y = 0
                for (k in 0 until n) {
                    x += unzig(varint(input))
                    y += unzig(varint(input))
                    val (px, py) = NaturalEarth.forward(x / 100.0, y / 100.0)
                    pts[k * 2] = px.toFloat()
                    pts[k * 2 + 1] = py.toFloat()
                }
                rings += pts
            }
            out += CountryShape(code, String(nameBytes), lat, lon, rings)
        }
        return out
    }

    private fun u16(i: DataInputStream): Int {
        val a = i.readUnsignedByte()
        val b = i.readUnsignedByte()
        return a or (b shl 8)
    }

    private fun s16(i: DataInputStream): Int = u16(i).toShort().toInt()

    private fun varint(i: DataInputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = i.readUnsignedByte()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    private fun unzig(v: Int): Int = (v ushr 1) xor -(v and 1)
}
