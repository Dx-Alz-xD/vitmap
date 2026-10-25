package com.vitmap.core.measure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MeasureTest {
    @Test
    fun lineCalibration() {
        val cal = Calibration.fromLine(Pt(0.0, 0.0), Pt(100.0, 0.0), 85.6)!!
        assertEquals(171.2, Geometry.distance(cal, Pt(0.0, 0.0), Pt(0.0, 200.0)), 1e-9)
    }

    @Test
    fun perspectiveCalibration() {
        // A card photographed at an angle: a trapezoid in the image.
        val corners = listOf(Pt(100.0, 100.0), Pt(500.0, 120.0), Pt(470.0, 380.0), Pt(130.0, 360.0))
        val cal = Calibration.fromRectangle(corners, 85.6, 53.98)
        assertNotNull(cal)
        assertEquals(85.6, Geometry.distance(cal, corners[0], corners[1]), 1e-6)
        assertEquals(53.98, Geometry.distance(cal, corners[1], corners[2]), 1e-6)
        assertEquals(Math.hypot(85.6, 53.98), Geometry.distance(cal, corners[0], corners[2]), 1e-6)
        assertEquals(85.6 * 53.98, Geometry.area(cal, corners), 1e-6)
        assertEquals(90.0, Geometry.angle(cal, corners[0], corners[1], corners[2]), 1e-6)
    }

    @Test
    fun homographyInverse() {
        val h = Homography.fromPoints(
            listOf(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 1.0), Pt(0.0, 1.0)),
            listOf(Pt(10.0, 10.0), Pt(30.0, 12.0), Pt(28.0, 40.0), Pt(8.0, 35.0)),
        )!!
        val inv = h.inverse()!!
        val p = inv.map(h.map(Pt(0.3, 0.7)))
        assertEquals(0.3, p.x, 1e-9)
        assertEquals(0.7, p.y, 1e-9)
    }
}
