package com.vitmap.core

import com.vitmap.core.geo.NaturalEarth
import com.vitmap.core.geo.Sun
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeoTest {
    @Test
    fun projectionRoundTrips() {
        for (lat in listOf(-85.0, -60.0, -23.5, 0.0, 12.3, 51.5, 78.0, 89.0)) for (lon in listOf(-179.0, -120.0, -0.5, 0.0, 33.3, 151.2, 179.9)) {
            val (x, y) = NaturalEarth.forward(lon, lat)
            val back = assertNotNull(NaturalEarth.inverse(x, y))
            assertTrue(abs(back.first - lon) < 1e-6 && abs(back.second - lat) < 1e-6, "$lon,$lat -> $back")
        }
        assertNull(NaturalEarth.inverse(2.7, 1.3)) // outside the rounded corner
        val (xEdge, yPole) = NaturalEarth.forward(180.0, 90.0)
        assertTrue(abs(yPole - NaturalEarth.MAX_Y) < 0.01 && xEdge < NaturalEarth.MAX_X)
    }

    private fun utc(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun solsticeDeclination() {
        val p = Sun.position(utc(2024, 6, 20, 20, 51))
        assertTrue(abs(p.declination - 23.44) < 0.05, "decl ${p.declination}")
        val w = Sun.position(utc(2024, 12, 21, 9, 21))
        assertTrue(abs(w.declination + 23.44) < 0.05)
    }

    @Test
    fun londonSunriseAndSunset() {
        val d = Sun.day(51.5074, -0.1278, utc(2024, 6, 21)) as Sun.Day.Normal
        val rise = Instant.ofEpochMilli(d.sunriseUtcMillis).atZone(ZoneOffset.UTC)
        val set = Instant.ofEpochMilli(d.sunsetUtcMillis).atZone(ZoneOffset.UTC)
        // Published: 03:43 and 20:21 UTC.
        assertTrue(abs(rise.hour * 60 + rise.minute - (3 * 60 + 43)) <= 3, "rise $rise")
        assertTrue(abs(set.hour * 60 + set.minute - (20 * 60 + 21)) <= 3, "set $set")
        assertTrue(Sun.day(78.2, 15.6, utc(2024, 6, 21)) is Sun.Day.PolarDay)
        assertTrue(Sun.day(78.2, 15.6, utc(2024, 12, 21)) is Sun.Day.PolarNight)
    }

    @Test
    fun subsolarPointAndAltitude() {
        val t = utc(2024, 3, 20, 12, 0)
        val (lat, lon) = Sun.subsolar(t)
        assertTrue(abs(lat) < 0.5 && abs(lon - 1.8) < 1.0, "subsolar $lat,$lon")
        assertTrue(Sun.altitude(lat, lon, t) > 89.5)
        assertTrue(Sun.altitude(0.0, lon + 180, t) < -89.0)
    }
}
