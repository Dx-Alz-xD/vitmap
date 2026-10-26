package com.vitmap.core

import com.vitmap.core.forex.MoneyFormat
import com.vitmap.core.forex.RateTable
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ForexTest {
    private val table = RateTable("eur", "2026-10-25", mapOf("usd" to 1.10, "gbp" to 0.85, "inr" to 92.0, "dkk" to 7.46, "aud" to 1.65, "ves" to 40.0, "xau" to 0.0004))

    private fun close(a: Double?, b: Double, eps: Double = 1e-9) = assertTrue(a != null && abs(a - b) < eps * maxOf(1.0, abs(b)), "$a != $b")

    @Test
    fun crossRates() {
        close(table.convert(100.0, "usd", "gbp"), 100 * 0.85 / 1.10)
        close(table.convert(1.0, "EUR", "INR"), 92.0)
        close(table.rate("gbp", "usd")!! * table.rate("usd", "gbp")!!, 1.0)
    }

    @Test
    fun legacyEuroCurrenciesUseFixedRates() {
        close(table.convert(1.0, "eur", "dem"), 1.95583)
        close(table.convert(100.0, "frf", "dem"), 100 / 6.55957 * 1.95583)
        close(table.convert(1.0, "eur", "bgn"), 1.95583)
    }

    @Test
    fun pegsAndRedenominations() {
        close(table.convert(1.0, "gbp", "jep"), 1.0)
        close(table.convert(1.0, "dkk", "fok"), 1.0)
        close(table.convert(1.0, "aud", "tvd"), 1.0)
        close(table.convert(1.0, "inr", "npr"), 1.6)
        close(table.convert(1.0, "ves", "vef"), 100_000.0)
        close(assertNotNull(table.convert(1.0, "xau", "xaug")), 31.1034768)
    }

    @Test
    fun feedRatesBeatPegs() {
        val t = RateTable("eur", "d", mapOf("usd" to 1.1, "hkd" to 8.6))
        close(t.rate("usd", "hkd"), 8.6 / 1.1)
    }

    @Test
    fun formatting() {
        assertEquals("1,234,567.89", MoneyFormat.format(1234567.891))
        assertEquals("0.00012345", MoneyFormat.format(0.000123454))
        assertEquals("12.50", MoneyFormat.format(12.5))
        assertEquals("-3.20", MoneyFormat.format(-3.2))
    }
}
