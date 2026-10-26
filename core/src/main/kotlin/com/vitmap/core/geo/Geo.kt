package com.vitmap.core.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

private fun rad(d: Double) = d * PI / 180
private fun deg(r: Double) = r * 180 / PI

/**
 * Natural Earth projection (Šavrič, Patterson, Jenny 2011): a pleasant pseudocylindrical world
 * map. Output x is roughly −2.73..2.73 and y −1.42..1.42 (y up).
 */
object NaturalEarth {
    private const val A0 = 0.8707
    private const val A1 = -0.131979
    private const val A2 = -0.013791
    private const val A3 = 0.003971
    private const val A4 = -0.001529
    private const val B0 = 1.007226
    private const val B1 = 0.015085
    private const val B2 = -0.044475
    private const val B3 = 0.028874
    private const val B4 = -0.005916

    const val MAX_Y = 1.4224
    const val MAX_X = 2.7326

    fun xScale(phi: Double): Double {
        val p2 = phi * phi
        val p4 = p2 * p2
        return A0 + A1 * p2 + A2 * p4 + A3 * p4 * p4 * p2 + A4 * p4 * p4 * p4
    }

    fun y(phi: Double): Double {
        val p2 = phi * phi
        val p4 = p2 * p2
        return phi * (B0 + B1 * p2 + B2 * p4 * p2 + B3 * p4 * p4 + B4 * p4 * p4 * p2)
    }

    /** Projects degrees to map units. */
    fun forward(lon: Double, lat: Double): Pair<Double, Double> {
        val phi = rad(lat.coerceIn(-90.0, 90.0))
        return rad(lon) * xScale(phi) to y(phi)
    }

    /** Map units back to degrees, or null outside the map outline. */
    fun inverse(x: Double, y: Double): Pair<Double, Double>? {
        if (abs(y) > MAX_Y) return null
        var phi = y
        repeat(25) {
            val p2 = phi * phi
            val p4 = p2 * p2
            val f = y(phi) - y
            val df = B0 + 3 * B1 * p2 + 7 * B2 * p4 * p2 + 9 * B3 * p4 * p4 + 11 * B4 * p4 * p4 * p2
            val step = f / df
            phi -= step
            if (abs(step) < 1e-10) return@repeat
        }
        val lambda = x / xScale(phi)
        if (abs(lambda) > PI + 1e-9) return null
        return deg(lambda) to deg(phi)
    }
}

/** Solar position from the NOAA solar calculator equations. */
object Sun {
    data class Position(val declination: Double, val equationOfTime: Double)

    fun position(epochMillis: Long): Position {
        val jd = epochMillis / 86_400_000.0 + 2440587.5
        val t = (jd - 2451545.0) / 36525.0
        val l0 = ((280.46646 + t * (36000.76983 + t * 0.0003032)) % 360 + 360) % 360
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sin(rad(m)) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(rad(2 * m)) * (0.019993 - 0.000101 * t) + sin(rad(3 * m)) * 0.000289
        val trueLong = l0 + c
        val omega = 125.04 - 1934.136 * t
        val appLong = trueLong - 0.00569 - 0.00478 * sin(rad(omega))
        val meanObliq = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60
        val obliq = meanObliq + 0.00256 * cos(rad(omega))
        val decl = deg(asin(sin(rad(obliq)) * sin(rad(appLong))))
        val y = tan(rad(obliq / 2)).let { it * it }
        val eqTime = 4 * deg(
            y * sin(2 * rad(l0)) - 2 * e * sin(rad(m)) + 4 * e * y * sin(rad(m)) * cos(2 * rad(l0)) -
                0.5 * y * y * sin(4 * rad(l0)) - 1.25 * e * e * sin(2 * rad(m)),
        )
        return Position(decl, eqTime)
    }

    /** Latitude and longitude where the sun is straight overhead. */
    fun subsolar(epochMillis: Long): Pair<Double, Double> {
        val p = position(epochMillis)
        val utcMinutes = ((epochMillis % 86_400_000L + 86_400_000L) % 86_400_000L) / 60_000.0
        var lon = -(utcMinutes - 720 + p.equationOfTime) / 4
        lon = ((lon + 540) % 360) - 180
        return p.declination to lon
    }

    /** Sun altitude in degrees above the horizon (no refraction). */
    fun altitude(lat: Double, lon: Double, epochMillis: Long, pos: Position = position(epochMillis)): Double {
        val utcMinutes = ((epochMillis % 86_400_000L + 86_400_000L) % 86_400_000L) / 60_000.0
        val trueSolar = utcMinutes + pos.equationOfTime + 4 * lon
        val hourAngle = trueSolar / 4 - 180
        val cosZ = sin(rad(lat)) * sin(rad(pos.declination)) + cos(rad(lat)) * cos(rad(pos.declination)) * cos(rad(hourAngle))
        return 90 - deg(acos(cosZ.coerceIn(-1.0, 1.0)))
    }

    sealed interface Day {
        data class Normal(val sunriseUtcMillis: Long, val sunsetUtcMillis: Long, val noonUtcMillis: Long) : Day
        data object PolarDay : Day
        data object PolarNight : Day
    }

    /** Sunrise, solar noon and sunset (UTC) for the UTC date containing [dayStartUtcMillis]. */
    fun day(lat: Double, lon: Double, dayStartUtcMillis: Long): Day {
        val midnight = dayStartUtcMillis - ((dayStartUtcMillis % 86_400_000L + 86_400_000L) % 86_400_000L)
        // Evaluate the sun near local noon for accuracy.
        val approxNoon = midnight + ((720 - 4 * lon) * 60_000).toLong()
        val p = position(approxNoon)
        val cosH = cos(rad(90.833)) / (cos(rad(lat)) * cos(rad(p.declination))) - tan(rad(lat)) * tan(rad(p.declination))
        val noonMin = 720 - 4 * lon - p.equationOfTime
        val noon = midnight + (noonMin * 60_000).toLong()
        if (cosH > 1) return Day.PolarNight
        if (cosH < -1) return Day.PolarDay
        val ha = deg(acos(cosH))
        return Day.Normal(noon - (ha * 4 * 60_000).toLong(), noon + (ha * 4 * 60_000).toLong(), noon)
    }
}
