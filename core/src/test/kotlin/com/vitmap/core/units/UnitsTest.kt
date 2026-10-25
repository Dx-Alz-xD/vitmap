package com.vitmap.core.units

import com.vitmap.core.math.Expression
import com.vitmap.core.math.NumberFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnitsTest {
    private fun conv(v: Double, from: String, to: String): Double {
        val a = UnitCatalog.findUnit(from)!!.second
        val b = UnitCatalog.findUnit(to)!!.second
        return Converter.convert(v, a, b)
    }

    @Test
    fun idsAreUniqueAndFactorsSane() {
        val ids = UnitCatalog.categories.flatMap { c -> c.units.map { it.id } }
        assertEquals(ids.size, ids.toSet().size, "duplicate ids: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}")
        for (c in UnitCatalog.categories) {
            assertTrue(c.units.size >= 3, c.id)
            for (u in c.units) {
                val back = u.fromBase(u.toBase(3.7))
                assertEquals(3.7, back, 1e-9 * 3.7, "${u.id} round trip")
            }
        }
        assertTrue(UnitCatalog.categories.size >= 40)
    }

    @Test
    fun knownConversions() {
        assertEquals(2.54, conv(1.0, "length.in", "length.cm"), 1e-12)
        assertEquals(1609.344, conv(1.0, "length.mi", "length.m"), 1e-9)
        assertEquals(212.0, conv(100.0, "temperature.c", "temperature.f"), 1e-9)
        assertEquals(-40.0, conv(-40.0, "temperature.f", "temperature.c"), 1e-9)
        assertEquals(273.15, conv(0.0, "temperature.c", "temperature.k"), 1e-9)
        assertEquals(80.0, conv(100.0, "temperature.c", "temperature.re"), 1e-9)
        assertEquals(2.20462262, conv(1.0, "mass.kg", "mass.lb"), 1e-8)
        assertEquals(23.5214583, conv(10.0, "fuel.l100km", "fuel.mpgus"), 1e-6)
        assertEquals(10.0, conv(23.5214583, "fuel.mpgus", "fuel.l100km"), 1e-6)
        assertEquals(1024.0, conv(1.0, "data.MiB", "data.KiB"), 1e-9)
        assertEquals(180.0, conv(Math.PI, "angle.rad", "angle.deg"), 1e-9)
        assertEquals(14.5037738, conv(1.0, "pressure.bar", "pressure.psi"), 1e-6)
        assertEquals(4.184, conv(1.0, "energy.kcal", "energy.kj"), 1e-12)
    }

    @Test
    fun customUnitsResolve() {
        val cats = UnitCatalog.withCustom(
            listOf(
                CustomUnitSpec("length.step", "length", "My step", "step", 0.75, "length.m"),
                CustomUnitSpec("length.stride", "length", "Stride", "stride", 2.0, "length.step"),
                CustomUnitSpec("myc.cup", "myc", "Cup", "cup", 3.0, "myc.base"),
            ),
            listOf(CustomCategorySpec("myc", "Coffee", "Bean", "bean")),
        )
        val length = cats.first { it.id == "length" }
        val stride = length.unit("length.stride")!!
        assertEquals(1.5, stride.toBase(1.0), 1e-12)
        val coffee = cats.first { it.id == "myc" }
        assertEquals(2, coffee.units.size)
        assertEquals(6.0, Converter.convert(2.0, coffee.unit("myc.cup")!!, coffee.unit("myc.base")!!), 1e-12)
    }

    @Test
    fun expressions() {
        assertEquals(42.0, Expression.evaluate("12*3.5"))
        assertEquals(1024.0, Expression.evaluate("2^10"))
        assertEquals(-3.0, Expression.evaluate("-(1+2)"))
        assertEquals(1500.0, Expression.evaluate("1,500"))
        assertEquals(2.0, Expression.evaluate("sqrt(4)"))
        assertEquals(1.5e-3, Expression.evaluate("1.5e-3"))
        assertNull(Expression.evaluate("2+"))
        assertNull(Expression.evaluate("abc"))
        assertNotNull(Expression.evaluate("pi*2"))
    }

    @Test
    fun formatting() {
        assertEquals("1,234.5", NumberFormatter.format(1234.5))
        assertEquals("0.333333333", NumberFormatter.format(1.0 / 3, 9))
        assertEquals("1.5×10⁻⁹", NumberFormatter.format(1.5e-9))
        assertEquals("6.02214076×10²³", NumberFormatter.format(6.02214076e23))
        assertEquals("0", NumberFormatter.format(0.0))
    }
}
