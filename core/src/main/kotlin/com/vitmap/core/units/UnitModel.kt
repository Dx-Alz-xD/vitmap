package com.vitmap.core.units

/** How a unit maps to its category's base unit. */
sealed interface Conversion {
    fun toBase(value: Double): Double
    fun fromBase(value: Double): Double

    /** base = value * factor + offset */
    data class Linear(val factor: Double, val offset: Double = 0.0) : Conversion {
        override fun toBase(value: Double) = value * factor + offset
        override fun fromBase(value: Double) = (value - offset) / factor
    }

    /** base = k / value (e.g. L/100 km against km/L). */
    data class Reciprocal(val k: Double) : Conversion {
        override fun toBase(value: Double) = if (value == 0.0) Double.POSITIVE_INFINITY else k / value
        override fun fromBase(value: Double) = if (value == 0.0) Double.POSITIVE_INFINITY else k / value
    }
}

enum class UnitGroup(val title: String) {
    COMMON("Common"),
    METRIC("Metric & SI"),
    IMPERIAL("Imperial & US"),
    SCIENTIFIC("Scientific"),
    ASTRONOMICAL("Astronomical"),
    REGIONAL("Regional & traditional"),
    HISTORICAL("Historical & ancient"),
    FUN("Fun & obscure"),
    CUSTOM("Custom"),
}

data class UnitDef(
    val id: String,
    val name: String,
    val symbol: String,
    val conversion: Conversion,
    val group: UnitGroup,
    val aliases: List<String> = emptyList(),
    val note: String? = null,
) {
    fun toBase(v: Double) = conversion.toBase(v)
    fun fromBase(v: Double) = conversion.fromBase(v)

    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return name.lowercase().contains(q) || symbol.lowercase() == q || symbol.lowercase().contains(q) ||
            aliases.any { it.lowercase().contains(q) }
    }
}

data class UnitCategory(
    val id: String,
    val name: String,
    val icon: String,
    val description: String,
    val units: List<UnitDef>,
    val isCustom: Boolean = false,
) {
    /** The unit every other unit converts through (factor 1, no offset). */
    val baseUnit: UnitDef
        get() = units.firstOrNull { it.conversion == Conversion.Linear(1.0, 0.0) } ?: units.first()

    fun unit(id: String): UnitDef? = units.firstOrNull { it.id == id }

    /** Units shown first when the category opens: the first two "common" ones. */
    val defaultPair: Pair<UnitDef, UnitDef>
        get() {
            val common = units.filter { it.group == UnitGroup.COMMON }
            val a = common.getOrNull(0) ?: units[0]
            val b = common.getOrNull(1) ?: units.getOrElse(1) { units[0] }
            return a to b
        }
}

object Converter {
    fun convert(value: Double, from: UnitDef, to: UnitDef): Double = to.fromBase(from.toBase(value))
}

/** A user-defined unit: 1 [symbol] = [amount] × [relativeTo] within an existing or custom category. */
data class CustomUnitSpec(
    val id: String,
    val categoryId: String,
    val name: String,
    val symbol: String,
    val amount: Double,
    val relativeToUnitId: String,
)

/** A user-defined category with its own base unit. */
data class CustomCategorySpec(
    val id: String,
    val name: String,
    val baseName: String,
    val baseSymbol: String,
)
