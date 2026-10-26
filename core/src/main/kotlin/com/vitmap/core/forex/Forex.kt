package com.vitmap.core.forex

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max

enum class CurrencyKind(val label: String) {
    FIAT("Currencies"),
    LOCAL("Local & pegged currencies"),
    METAL("Precious metals"),
    CRYPTO("Crypto"),
    LEGACY("Historic currencies"),
}

/**
 * Units of each currency per one unit of [base] (lower-case codes, as the rate feeds use), plus the
 * date they apply to. Currencies the feed doesn't carry are filled in from fixed relationships.
 */
class RateTable(val base: String, val date: String, rates: Map<String, Double>, val source: String = "") {
    val rates: Map<String, Double> = complete(rates.mapKeys { it.key.lowercase() }.filterValues { it > 0 && it.isFinite() } + (base to 1.0))

    operator fun contains(code: String) = rates.containsKey(code.lowercase())

    /** Converts [amount] of [from] into [to], or null if either rate is unknown. */
    fun convert(amount: Double, from: String, to: String): Double? {
        val a = rates[from.lowercase()] ?: return null
        val b = rates[to.lowercase()] ?: return null
        return amount * b / a
    }

    fun rate(from: String, to: String): Double? = convert(1.0, from, to)

    companion object {
        /** Irrevocable euro conversion rates of currencies replaced by the euro. */
        val EURO_LEGACY: Map<String, Double> = mapOf(
            "ats" to 13.7603, "bef" to 40.3399, "dem" to 1.95583, "esp" to 166.386, "fim" to 5.94573,
            "frf" to 6.55957, "iep" to 0.787564, "itl" to 1936.27, "luf" to 40.3399, "nlg" to 2.20371,
            "pte" to 200.482, "grd" to 340.750, "sit" to 239.640, "cyp" to 0.585274, "mtl" to 0.429300,
            "skk" to 30.1260, "eek" to 15.6466, "lvl" to 0.702804, "ltl" to 3.45280, "hrk" to 7.53450,
            "bgn" to 1.95583,
            // Currencies of micro-states that were tied to the lira, franc and peseta.
            "val" to 1936.27, "sml" to 1936.27, "mcf" to 6.55957, "adp" to 166.386,
        )

        /** Currencies fixed 1:1 (or at a set ratio) to another: code -> (anchor, units per anchor). */
        val PEGS: Map<String, Pair<String, Double>> = mapOf(
            "fok" to ("dkk" to 1.0), "ggp" to ("gbp" to 1.0), "jep" to ("gbp" to 1.0), "imp" to ("gbp" to 1.0),
            "shp" to ("gbp" to 1.0), "fkp" to ("gbp" to 1.0), "gip" to ("gbp" to 1.0),
            "tvd" to ("aud" to 1.0), "kid" to ("aud" to 1.0), "ckd" to ("nzd" to 1.0),
            "btn" to ("inr" to 1.0), "npr" to ("inr" to 1.6), "bnd" to ("sgd" to 1.0),
            "bmd" to ("usd" to 1.0), "bsd" to ("usd" to 1.0), "pab" to ("usd" to 1.0), "kyd" to ("usd" to 0.8333333333),
            "aed" to ("usd" to 3.6725), "sar" to ("usd" to 3.75), "qar" to ("usd" to 3.64), "omr" to ("usd" to 0.3845),
            "bhd" to ("usd" to 0.376), "jod" to ("usd" to 0.709), "hkd" to ("usd" to 7.8), "bbd" to ("usd" to 2.0),
            "xcd" to ("usd" to 2.7), "awg" to ("usd" to 1.79), "ang" to ("usd" to 1.79), "xcg" to ("usd" to 1.79),
            "bzd" to ("usd" to 2.0), "djf" to ("usd" to 177.721), "cuc" to ("usd" to 1.0),
            "xof" to ("eur" to 655.957), "xaf" to ("eur" to 655.957), "kmf" to ("eur" to 491.96775), "xpf" to ("eur" to 119.33174),
            "cve" to ("eur" to 110.265), "bam" to ("eur" to 1.95583), "stn" to ("eur" to 24.5),
            "lsl" to ("zar" to 1.0), "nad" to ("zar" to 1.0), "szl" to ("zar" to 1.0), "mop" to ("hkd" to 1.03),
        )

        /** Old units of redenominated currencies: code -> (successor, old units per new unit). */
        val REDENOMINATED: Map<String, Pair<String, Double>> = mapOf(
            "mro" to ("mru" to 10.0), "std" to ("stn" to 1000.0), "byr" to ("byn" to 10_000.0),
            "vef" to ("ves" to 100_000.0), "veb" to ("ves" to 100_000_000.0), "zmk" to ("zmw" to 1000.0),
            "ghc" to ("ghs" to 10_000.0), "rol" to ("ron" to 10_000.0), "trl" to ("try" to 1_000_000.0),
            "sdd" to ("sdg" to 100.0), "mzm" to ("mzn" to 1000.0), "azm" to ("azn" to 5000.0),
            "tmm" to ("tmt" to 5000.0), "sll" to ("sle" to 1000.0), "mgf" to ("mga" to 5.0),
        )

        /** Fills currencies missing from a feed. Market rates from the feed always win over pegs. */
        fun complete(input: Map<String, Double>): Map<String, Double> {
            val out = HashMap(input)
            val eur = out["eur"]
            if (eur != null) for ((c, r) in EURO_LEGACY) if (c !in out || c == "bgn") out[c] = eur * r
            repeat(2) {
                for ((c, peg) in PEGS) if (c !in out) out[peg.first]?.let { out[c] = it * peg.second }
                for ((c, rel) in REDENOMINATED) if (c !in out) out[rel.first]?.let { out[c] = it * rel.second }
            }
            // Precious metals by the gram, for jewellery and bullion maths.
            out["xau"]?.let { out["xaug"] = it * 31.1034768 }
            out["xag"]?.let { out["xagg"] = it * 31.1034768 }
            return out
        }
    }
}

object MoneyFormat {
    /** Rounds sensibly for display: two decimals for normal amounts, more significant digits for tiny ones. */
    fun decimalsFor(value: Double, minorUnits: Int = 2): Int {
        val v = abs(value)
        if (v == 0.0) return minorUnits
        if (v >= 1) return if (v >= 1_000_000) minOf(minorUnits, 2) else minorUnits.coerceAtLeast(2)
        val lead = -floor(log10(v)).toInt()
        return max(minorUnits, lead + 4).coerceAtMost(12)
    }

    fun format(value: Double, decimals: Int = decimalsFor(value), grouping: Boolean = true): String {
        if (!value.isFinite()) return "—"
        val s = "%.${decimals}f".format(java.util.Locale.US, value)
        val (int, frac) = s.split('.').let { it[0] to it.getOrNull(1) }
        val neg = int.startsWith('-')
        val digits = int.removePrefix("-")
        val grouped = if (grouping) digits.reversed().chunked(3).joinToString(",").reversed() else digits
        val trimmedFrac = frac?.let { f -> if (decimals > 2) f.trimEnd('0').let { if (it.length < 2) f.take(2) else it } else f }
        return (if (neg) "-" else "") + grouped + (trimmedFrac?.takeIf { it.isNotEmpty() }?.let { ".$it" } ?: "")
    }
}
