package com.vitmap.core.units

import com.vitmap.core.units.UnitGroup.ASTRONOMICAL
import com.vitmap.core.units.UnitGroup.COMMON
import com.vitmap.core.units.UnitGroup.FUN
import com.vitmap.core.units.UnitGroup.HISTORICAL
import com.vitmap.core.units.UnitGroup.IMPERIAL
import com.vitmap.core.units.UnitGroup.METRIC
import com.vitmap.core.units.UnitGroup.REGIONAL
import com.vitmap.core.units.UnitGroup.SCIENTIFIC
import kotlin.math.PI

private class CategoryBuilder(private val cat: String) {
    val units = ArrayList<UnitDef>()
    private var group = COMMON

    fun group(g: UnitGroup, block: CategoryBuilder.() -> Unit) {
        val old = group
        group = g
        block()
        group = old
    }

    fun u(id: String, name: String, symbol: String, factor: Double, vararg aliases: String, note: String? = null) {
        units += UnitDef("$cat.$id", name, symbol, Conversion.Linear(factor), group, aliases.toList(), note)
    }

    fun affine(id: String, name: String, symbol: String, factor: Double, offset: Double, vararg aliases: String) {
        units += UnitDef("$cat.$id", name, symbol, Conversion.Linear(factor, offset), group, aliases.toList())
    }

    fun recip(id: String, name: String, symbol: String, k: Double, vararg aliases: String) {
        units += UnitDef("$cat.$id", name, symbol, Conversion.Reciprocal(k), group, aliases.toList())
    }
}

private fun category(id: String, name: String, icon: String, description: String, block: CategoryBuilder.() -> Unit): UnitCategory {
    val b = CategoryBuilder(id)
    b.block()
    return UnitCategory(id, name, icon, description, b.units)
}

object UnitCatalog {
    private const val INCH = 0.0254
    private const val FOOT = 0.3048
    private const val YARD = 0.9144
    private const val MILE = 1609.344
    private const val LB = 0.45359237
    private const val G0 = 9.80665
    private const val US_GAL = 3.785411784e-3
    private const val UK_GAL = 4.54609e-3
    private const val LBF = 4.4482216152605
    private const val CAL = 4.184
    private const val BTU = 1055.05585262
    private const val EV = 1.602176634e-19
    private const val C = 299792458.0
    private const val LY = 9.4607304725808e15
    private const val PC = 3.0856775814913673e16
    private const val AU = 1.495978707e11

    val categories: List<UnitCategory> by lazy { build() }

    fun category(id: String): UnitCategory? = categories.firstOrNull { it.id == id }

    fun findUnit(unitId: String): Pair<UnitCategory, UnitDef>? {
        for (c in categories) c.unit(unitId)?.let { return c to it }
        return null
    }

    /** Merges user-defined units and categories into the built-in catalog. */
    fun withCustom(units: List<CustomUnitSpec>, customCategories: List<CustomCategorySpec>): List<UnitCategory> {
        val all = categories.toMutableList()
        for (cc in customCategories) {
            val base = UnitDef("${cc.id}.base", cc.baseName, cc.baseSymbol, Conversion.Linear(1.0), COMMON)
            all += UnitCategory(cc.id, cc.name, "tune", "Your own category", listOf(base), isCustom = true)
        }
        // Units can reference other custom units, so resolve until nothing changes.
        val pending = units.toMutableList()
        var progress = true
        while (pending.isNotEmpty() && progress) {
            progress = false
            val iter = pending.iterator()
            while (iter.hasNext()) {
                val spec = iter.next()
                val idx = all.indexOfFirst { c -> c.id == spec.categoryId }
                if (idx < 0 || spec.amount == 0.0) {
                    iter.remove()
                    continue
                }
                val cat = all[idx]
                val ref = cat.unit(spec.relativeToUnitId) ?: continue
                // v custom = (v * amount) ref
                val conv = when (val rc = ref.conversion) {
                    is Conversion.Linear -> Conversion.Linear(spec.amount * rc.factor, rc.offset)
                    is Conversion.Reciprocal -> Conversion.Reciprocal(rc.k / spec.amount)
                }
                val def = UnitDef(
                    spec.id, spec.name, spec.symbol, conv, UnitGroup.CUSTOM,
                    note = "1 ${spec.symbol} = ${spec.amount} ${ref.symbol}",
                )
                all[idx] = cat.copy(units = cat.units + def)
                iter.remove()
                progress = true
            }
        }
        return all
    }

    private fun build(): List<UnitCategory> = listOf(
        category("length", "Length", "straighten", "Distance, height, width and depth") {
            u("m", "Meter", "m", 1.0, "metre")
            u("km", "Kilometer", "km", 1e3, "kilometre", "klick")
            u("cm", "Centimeter", "cm", 1e-2, "centimetre")
            u("mm", "Millimeter", "mm", 1e-3, "millimetre")
            u("in", "Inch", "in", INCH, "\"", "inches")
            u("ft", "Foot", "ft", FOOT, "'", "feet")
            u("yd", "Yard", "yd", YARD)
            u("mi", "Mile", "mi", MILE, "statute mile")
            u("nmi", "Nautical mile", "NM", 1852.0, "nmi", "sea mile")
            group(METRIC) {
                u("um", "Micrometer", "µm", 1e-6, "micron")
                u("nm", "Nanometer", "nm", 1e-9)
                u("pm", "Picometer", "pm", 1e-12)
                u("fm", "Femtometer", "fm", 1e-15, "fermi")
                u("am", "Attometer", "am", 1e-18)
                u("dm", "Decimeter", "dm", 0.1)
                u("dam", "Decameter", "dam", 10.0)
                u("hm", "Hectometer", "hm", 100.0)
                u("Mm", "Megameter", "Mm", 1e6)
                u("Gm", "Gigameter", "Gm", 1e9)
                u("mym", "Myriameter", "mym", 1e4, "mil (Scandinavian)", note = "Also the Scandinavian mil")
            }
            group(IMPERIAL) {
                u("thou", "Thou (mil)", "thou", INCH / 1000, "mil")
                u("line", "Line", "ln", INCH / 12)
                u("barleycorn", "Barleycorn", "bc", INCH / 3)
                u("hand", "Hand", "hh", 0.1016, note = "Used for horse heights")
                u("link", "Link (Gunter's)", "li", 0.201168)
                u("rod", "Rod / pole / perch", "rd", 5.0292)
                u("chain", "Chain (Gunter's)", "ch", 20.1168)
                u("furlong", "Furlong", "fur", 201.168)
                u("league", "League (land)", "lea", 3 * MILE)
                u("fathom", "Fathom", "ftm", 1.8288)
                u("cable", "Cable (international)", "cb", 185.2)
                u("cableus", "Cable (US Navy)", "cb US", 219.456)
                u("survft", "US survey foot", "ft US", 1200.0 / 3937.0)
                u("survmi", "US survey mile", "mi US", 6336000.0 / 3937.0)
                u("point", "Point (typographic)", "pt", INCH / 72)
                u("pica", "Pica", "pc", INCH / 6)
                u("twip", "Twip", "twip", INCH / 1440)
            }
            group(SCIENTIFIC) {
                u("angstrom", "Ångström", "Å", 1e-10, "angstrom")
                u("bohr", "Bohr radius", "a₀", 5.29177210903e-11)
                u("xu", "X unit", "xu", 1.0021e-13)
                u("re", "Classical electron radius", "rₑ", 2.8179403262e-15)
                u("planck", "Planck length", "ℓP", 1.616255e-35)
            }
            group(ASTRONOMICAL) {
                u("au", "Astronomical unit", "au", AU)
                u("ls", "Light-second", "ls", C)
                u("lmin", "Light-minute", "lmin", C * 60)
                u("lh", "Light-hour", "lh", C * 3600)
                u("lday", "Light-day", "ld", C * 86400)
                u("ly", "Light-year", "ly", LY)
                u("pc", "Parsec", "pc", PC)
                u("kpc", "Kiloparsec", "kpc", PC * 1e3)
                u("Mpc", "Megaparsec", "Mpc", PC * 1e6)
                u("Gpc", "Gigaparsec", "Gpc", PC * 1e9)
                u("rearth", "Earth radius", "R⊕", 6.371e6)
                u("ldist", "Earth–Moon distance", "LD", 3.844e8)
                u("rsun", "Solar radius", "R☉", 6.957e8)
                u("rjup", "Jupiter radius", "RJ", 6.9911e7)
            }
            group(REGIONAL) {
                u("shaku", "Shaku (Japan)", "尺", 10.0 / 33)
                u("sun_jp", "Sun (Japan)", "寸", 1.0 / 33)
                u("ken", "Ken (Japan)", "間", 60.0 / 33)
                u("jo", "Jō (Japan)", "丈", 100.0 / 33)
                u("ri_jp", "Ri (Japan)", "里", 12960.0 / 3.3)
                u("chi", "Chi (China)", "尺", 1.0 / 3)
                u("cun", "Cun (China)", "寸", 1.0 / 30)
                u("zhang", "Zhang (China)", "丈", 10.0 / 3)
                u("li_cn", "Li (China)", "里", 500.0)
                u("verst", "Verst (Russia)", "verst", 1066.8)
                u("sazhen", "Sazhen (Russia)", "sazhen", 2.1336)
                u("arshin", "Arshin (Russia)", "arshin", 0.7112)
                u("vershok", "Vershok (Russia)", "vershok", 0.04445)
                u("gaj", "Gaj (India)", "gaj", YARD, "guz")
                u("haath", "Haath (India)", "haath", 0.4572)
                u("angul", "Angul (India)", "angul", 0.01905)
                u("kos", "Kos (India)", "kos", 3218.688, "krosa")
                u("vara", "Vara (Spain)", "vara", 0.8359)
            }
            group(HISTORICAL) {
                u("cubit", "Cubit", "cubit", 0.4572)
                u("royalcubit", "Royal cubit (Egypt)", "meh niswt", 0.5236)
                u("pes", "Roman foot (pes)", "pes", 0.296)
                u("passus", "Roman pace (passus)", "passus", 1.48)
                u("millepassus", "Roman mile", "m.p.", 1480.0)
                u("stadion", "Stadion (Greek)", "stadion", 185.0)
                u("parasang", "Parasang (Persian)", "parasang", 5600.0)
                u("span", "Span", "span", 0.2286)
                u("ell", "Ell (English)", "ell", 1.143)
                u("digit", "Digit", "digit", 0.01905)
            }
            group(FUN) {
                u("smoot", "Smoot", "smoot", 1.7018)
                u("beardsecond", "Beard-second", "b-s", 5e-9, note = "How far a beard grows in one second")
                u("banana", "Banana (for scale)", "🍌", 0.178)
                u("footballfield", "American football field", "field", 91.44)
                u("pool", "Olympic pool length", "pool", 50.0)
                u("marathon", "Marathon", "marathon", 42195.0)
                u("everest", "Mount Everest height", "Everest", 8848.86)
                u("eiffel", "Eiffel Tower height", "Eiffel", 330.0)
                u("bus", "London bus length", "bus", 11.23)
                u("altuve", "Altuve", "altuve", 1.65)
                u("sheppey", "Sheppey", "sheppey", 1408.0, note = "The closest distance at which sheep remain picturesque")
            }
        },
        category("mass", "Mass & weight", "scale", "How heavy something is") {
            u("kg", "Kilogram", "kg", 1.0, "kilo")
            u("g", "Gram", "g", 1e-3)
            u("lb", "Pound", "lb", LB, "lbs")
            u("oz", "Ounce", "oz", LB / 16)
            u("t", "Tonne (metric ton)", "t", 1e3, "metric ton")
            u("st", "Stone", "st", 14 * LB)
            group(METRIC) {
                u("mg", "Milligram", "mg", 1e-6)
                u("ug", "Microgram", "µg", 1e-9, "mcg")
                u("ng", "Nanogram", "ng", 1e-12)
                u("q", "Quintal", "q", 100.0)
                u("Mt", "Megatonne", "Mt", 1e9)
                u("ct", "Carat", "ct", 2e-4)
                u("ctpt", "Carat point", "pt", 2e-6)
            }
            group(IMPERIAL) {
                u("gr", "Grain", "gr", 6.479891e-5)
                u("dr", "Dram (avoirdupois)", "dr", LB / 256)
                u("shortton", "Short ton (US)", "tn", 2000 * LB)
                u("longton", "Long ton (UK)", "LT", 2240 * LB)
                u("cwtus", "Hundredweight (US)", "cwt", 100 * LB)
                u("cwtuk", "Hundredweight (UK)", "cwt UK", 112 * LB)
                u("ozt", "Troy ounce", "oz t", 0.0311034768)
                u("lbt", "Troy pound", "lb t", 0.3732417216)
                u("dwt", "Pennyweight", "dwt", 1.55517384e-3)
                u("slug", "Slug", "slug", 14.59390294)
                u("butter", "Stick of butter", "stick", 0.1134)
            }
            group(SCIENTIFIC) {
                u("da", "Dalton (atomic mass unit)", "Da", 1.66053906660e-27, "amu", "u")
                u("me", "Electron mass", "mₑ", 9.1093837015e-31)
                u("mp", "Proton mass", "mₚ", 1.67262192369e-27)
                u("evc2", "Electronvolt / c²", "eV/c²", 1.78266192e-36)
                u("planck", "Planck mass", "mP", 2.176434e-8)
            }
            group(ASTRONOMICAL) {
                u("mearth", "Earth mass", "M⊕", 5.9722e24)
                u("mmoon", "Moon mass", "M☾", 7.342e22)
                u("mjup", "Jupiter mass", "MJ", 1.89813e27)
                u("msun", "Solar mass", "M☉", 1.98847e30)
            }
            group(REGIONAL) {
                u("tola", "Tola (India)", "tola", 0.0116638038)
                u("seer", "Seer (India)", "seer", 0.9331)
                u("maund", "Maund (India)", "maund", 37.3242)
                u("ratti", "Ratti (India)", "ratti", 1.215e-4)
                u("jin", "Jin / catty (China)", "斤", 0.5)
                u("liang", "Liang / tael (China)", "两", 0.05)
                u("dan", "Dan (China)", "担", 50.0)
                u("kan", "Kan (Japan)", "貫", 3.75)
                u("momme", "Monme (Japan)", "匁", 3.75e-3)
                u("pood", "Pood (Russia)", "pood", 16.3804964)
                u("funt", "Funt (Russia)", "funt", 0.40951241)
            }
            group(HISTORICAL) {
                u("libra", "Roman pound (libra)", "libra", 0.3289)
                u("talent", "Talent (Greek)", "talent", 26.0)
                u("mina", "Mina (Greek)", "mina", 0.431)
                u("shekel", "Shekel", "shekel", 0.01142)
                u("scruple", "Scruple (apothecaries')", "℈", 1.2959782e-3)
            }
            group(FUN) {
                u("elephant", "African elephant", "🐘", 6000.0)
                u("bluewhale", "Blue whale", "🐋", 150000.0)
                u("car", "Average car", "car", 1500.0)
            }
        },
        category("temperature", "Temperature", "thermostat", "Hot, cold and absolute scales") {
            affine("c", "Celsius", "°C", 1.0, 273.15, "centigrade")
            affine("f", "Fahrenheit", "°F", 5.0 / 9.0, 459.67 * 5.0 / 9.0)
            u("k", "Kelvin", "K", 1.0)
            group(SCIENTIFIC) {
                u("r", "Rankine", "°R", 5.0 / 9.0)
                u("planck", "Planck temperature", "TP", 1.416784e32)
            }
            group(HISTORICAL) {
                affine("re", "Réaumur", "°Ré", 1.25, 273.15)
                affine("ro", "Rømer", "°Rø", 40.0 / 21.0, 273.15 - 7.5 * 40.0 / 21.0)
                affine("n", "Newton", "°N", 100.0 / 33.0, 273.15)
                affine("de", "Delisle", "°De", -2.0 / 3.0, 373.15)
            }
        },
        category("area", "Area", "square_foot", "Surfaces, floors and land") {
            u("m2", "Square meter", "m²", 1.0, "sq m")
            u("km2", "Square kilometer", "km²", 1e6)
            u("ft2", "Square foot", "ft²", FOOT * FOOT, "sq ft")
            u("ha", "Hectare", "ha", 1e4)
            u("ac", "Acre", "ac", 4046.8564224)
            u("mi2", "Square mile", "mi²", MILE * MILE)
            group(METRIC) {
                u("cm2", "Square centimeter", "cm²", 1e-4)
                u("mm2", "Square millimeter", "mm²", 1e-6)
                u("a", "Are", "a", 100.0)
                u("daa", "Decare", "daa", 1000.0)
            }
            group(IMPERIAL) {
                u("in2", "Square inch", "in²", INCH * INCH)
                u("yd2", "Square yard", "yd²", YARD * YARD)
                u("rood", "Rood", "rood", 1011.7141056)
                u("perch2", "Square perch", "perch", 25.29285264)
                u("section", "Section", "section", 2.589988110336e6)
                u("township", "Township", "twp", 9.3239572e7)
                u("cmil", "Circular mil", "cmil", 5.067074790975e-10)
            }
            group(SCIENTIFIC) {
                u("barn", "Barn", "b", 1e-28)
                u("shed", "Shed", "shed", 1e-52)
                u("planck", "Planck area", "ℓP²", 2.6121e-70)
            }
            group(REGIONAL) {
                u("bigha_up", "Bigha (Uttar Pradesh)", "bigha", 2529.29)
                u("biswa_up", "Biswa (Uttar Pradesh)", "biswa", 126.46)
                u("bigha_wb", "Bigha (West Bengal)", "bigha WB", 1337.8)
                u("katha_wb", "Katha (West Bengal)", "katha", 66.89)
                u("gunta", "Guntha (Maharashtra / Karnataka)", "gunta", 101.17)
                u("cent", "Cent (South India)", "cent", 40.4686)
                u("ground", "Ground (Tamil Nadu)", "ground", 222.97)
                u("kanal", "Kanal (Punjab)", "kanal", 505.857)
                u("marla", "Marla (Punjab)", "marla", 25.2929)
                u("tsubo", "Tsubo (Japan)", "坪", 400.0 / 121.0)
                u("tatami", "Tatami (jō)", "畳", 1.6529)
                u("ping", "Ping (Taiwan)", "坪 TW", 400.0 / 121.0)
                u("mu", "Mu (China)", "亩", 2000.0 / 3.0)
                u("qing", "Qing (China)", "顷", 200000.0 / 3.0)
                u("rai", "Rai (Thailand)", "rai", 1600.0)
                u("dunam", "Dunam", "dunam", 1000.0)
                u("feddan", "Feddan (Egypt)", "feddan", 4200.83)
                u("desyatina", "Desyatina (Russia)", "desyatina", 10925.0)
            }
            group(FUN) {
                u("tennis", "Tennis court", "court", 260.87)
                u("soccer", "Football (soccer) pitch", "pitch", 7140.0)
                u("nflfield", "American football field", "field", 5351.215)
                u("centralpark", "Central Park", "CP", 3.41e6)
                u("wales", "Wales", "Wales", 2.0779e10)
            }
        },
        category("volume", "Volume & cooking", "local_drink", "Liquids, containers and recipes") {
            u("l", "Liter", "L", 1e-3, "litre")
            u("ml", "Milliliter", "mL", 1e-6, "millilitre", "cc")
            u("m3", "Cubic meter", "m³", 1.0)
            u("galus", "Gallon (US)", "gal", US_GAL)
            u("cupus", "Cup (US)", "cup", US_GAL / 16)
            u("tbsp", "Tablespoon (US)", "tbsp", US_GAL / 256)
            u("tsp", "Teaspoon (US)", "tsp", US_GAL / 768)
            u("floz", "Fluid ounce (US)", "fl oz", US_GAL / 128)
            group(METRIC) {
                u("cl", "Centiliter", "cL", 1e-5)
                u("dl", "Deciliter", "dL", 1e-4)
                u("hl", "Hectoliter", "hL", 0.1)
                u("ul", "Microliter", "µL", 1e-9)
                u("cm3", "Cubic centimeter", "cm³", 1e-6)
                u("mm3", "Cubic millimeter", "mm³", 1e-9)
                u("km3", "Cubic kilometer", "km³", 1e9)
                u("metcup", "Metric cup", "cup (metric)", 2.5e-4)
                u("mettbsp", "Metric tablespoon", "tbsp (metric)", 1.5e-5)
                u("mettsp", "Metric teaspoon", "tsp (metric)", 5e-6)
                u("autbsp", "Australian tablespoon", "tbsp (AU)", 2e-5)
            }
            group(IMPERIAL) {
                u("qtus", "Quart (US)", "qt", US_GAL / 4)
                u("ptus", "Pint (US)", "pt", US_GAL / 8)
                u("gillus", "Gill (US)", "gi", US_GAL / 32)
                u("legalcup", "Cup (US legal)", "cup (legal)", 2.4e-4)
                u("jigger", "Jigger", "jigger", US_GAL / 128 * 1.5)
                u("drus", "Fluid dram (US)", "fl dr", US_GAL / 1024)
                u("minim", "Minim (US)", "min", US_GAL / 61440)
                u("galuk", "Gallon (Imperial)", "gal UK", UK_GAL)
                u("qtuk", "Quart (Imperial)", "qt UK", UK_GAL / 4)
                u("ptuk", "Pint (Imperial)", "pt UK", UK_GAL / 8)
                u("cupuk", "Cup (Imperial)", "cup UK", UK_GAL / 16)
                u("gilluk", "Gill (Imperial)", "gi UK", UK_GAL / 32)
                u("flozuk", "Fluid ounce (Imperial)", "fl oz UK", UK_GAL / 160)
                u("tbspuk", "Tablespoon (Imperial)", "tbsp UK", UK_GAL / 256)
                u("tspuk", "Teaspoon (Imperial)", "tsp UK", UK_GAL / 768)
                u("in3", "Cubic inch", "in³", INCH * INCH * INCH)
                u("ft3", "Cubic foot", "ft³", FOOT * FOOT * FOOT)
                u("yd3", "Cubic yard", "yd³", YARD * YARD * YARD)
                u("acft", "Acre-foot", "ac·ft", 1233.48183754752)
                u("bbloil", "Barrel (oil)", "bbl", 0.158987294928)
                u("bblus", "Barrel (US fluid)", "fl bbl", 0.119240471196)
                u("hogshead", "Hogshead", "hhd", 0.238480942392)
                u("bushelus", "Bushel (US)", "bu", 0.03523907016688)
                u("peckus", "Peck (US)", "pk", 0.00880976754172)
                u("drygalus", "Dry gallon (US)", "dry gal", 4.40488377086e-3)
                u("bushuk", "Bushel (Imperial)", "bu UK", 0.03636872)
            }
            group(REGIONAL) {
                u("go", "Gō (Japan)", "合", 2401.0 / 1331.0 * 1e-4)
                u("sho", "Shō (Japan)", "升", 2401.0 / 1331.0 * 1e-3)
                u("to_jp", "To (Japan)", "斗", 2401.0 / 1331.0 * 1e-2)
                u("koku", "Koku (Japan)", "石", 2401.0 / 1331.0 * 1e-1)
                u("sheng", "Sheng (China)", "升", 1e-3)
                u("dou", "Dou (China)", "斗", 1e-2)
                u("vedro", "Vedro (Russia)", "vedro", 0.01229941)
                u("shtof", "Shtof (Russia)", "shtof", 1.2299e-3)
            }
            group(HISTORICAL) {
                u("amphora", "Amphora (Roman)", "amphora", 0.0262)
                u("sextarius", "Sextarius (Roman)", "sextarius", 5.46e-4)
                u("bath", "Bath (Biblical)", "bath", 0.022)
                u("hin", "Hin (Biblical)", "hin", 0.00366)
                u("log", "Log (Biblical)", "log", 3.05e-4)
                u("firkin", "Firkin", "firkin", 0.04091481)
                u("tun", "Tun", "tun", 0.953923769568)
            }
            group(FUN) {
                u("drop", "Drop", "gtt", 5e-8)
                u("pinch", "Pinch", "pinch", 3.08e-7)
                u("smidgen", "Smidgen", "smidgen", 1.54e-7)
                u("bathtub", "Bathtub", "tub", 0.15)
                u("olympicpool", "Olympic pool", "pool", 2500.0)
            }
        },
        category("time", "Time", "schedule", "Durations from attoseconds to eons") {
            u("s", "Second", "s", 1.0, "sec")
            u("min", "Minute", "min", 60.0)
            u("h", "Hour", "h", 3600.0, "hr")
            u("d", "Day", "d", 86400.0)
            u("wk", "Week", "wk", 604800.0)
            u("mo", "Month (average)", "mo", 2629746.0)
            u("yr", "Year (Gregorian)", "yr", 31556952.0)
            group(METRIC) {
                u("ms", "Millisecond", "ms", 1e-3)
                u("us", "Microsecond", "µs", 1e-6)
                u("ns", "Nanosecond", "ns", 1e-9)
                u("ps", "Picosecond", "ps", 1e-12)
                u("fs", "Femtosecond", "fs", 1e-15)
                u("as", "Attosecond", "as", 1e-18)
                u("ks", "Kilosecond", "ks", 1e3)
                u("Ms", "Megasecond", "Ms", 1e6)
                u("Gs", "Gigasecond", "Gs", 1e9)
            }
            group(COMMON) {
                u("fortnight", "Fortnight", "fn", 1209600.0)
                u("decade", "Decade", "dec", 315569520.0)
                u("century", "Century", "c", 3155695200.0)
                u("millennium", "Millennium", "ka", 31556952000.0)
            }
            group(SCIENTIFIC) {
                u("julyr", "Julian year", "a", 31557600.0)
                u("tropyr", "Tropical year", "yr (trop)", 31556925.216)
                u("sidday", "Sidereal day", "sid d", 86164.0905)
                u("sidyr", "Sidereal year", "sid yr", 31558149.7635)
                u("synmonth", "Synodic (lunar) month", "lunation", 2551442.8896)
                u("Ma", "Megaannum", "Ma", 31557600.0 * 1e6, "million years")
                u("Ga", "Gigaannum", "Ga", 31557600.0 * 1e9, "billion years")
                u("shake", "Shake", "shake", 1e-8)
                u("svedberg", "Svedberg", "S", 1e-13)
                u("planck", "Planck time", "tP", 5.391247e-44)
            }
            group(ASTRONOMICAL) {
                u("sol", "Martian sol", "sol", 88775.244)
                u("jupday", "Jupiter day", "J day", 35730.0)
                u("galyr", "Galactic year", "GY", 7.258e15)
            }
            group(REGIONAL) {
                u("ghati", "Ghaṭikā (India)", "ghati", 1440.0, "ghadi")
                u("muhurta", "Muhūrta (India)", "muhurta", 2880.0)
                u("pal", "Pal / vighati (India)", "pal", 24.0)
                u("ke", "Kè (China)", "刻", 864.0)
                u("shichen", "Shíchen (China)", "时辰", 7200.0)
            }
            group(HISTORICAL) {
                u("moment", "Moment (medieval)", "moment", 90.0)
                u("helek", "Helek (Hebrew)", "helek", 10.0 / 3.0)
                u("olympiad", "Olympiad", "olympiad", 4 * 31557600.0)
                u("indiction", "Indiction", "indiction", 15 * 31557600.0)
            }
            group(FUN) {
                u("jiffy", "Jiffy (AC mains, 60 Hz)", "jiffy", 1.0 / 60)
                u("microcentury", "Microcentury (lecture length)", "µcentury", 3155.7600)
                u("nanocentury", "Nanocentury (≈ π seconds)", "ncentury", 3.15576)
                u("dogyear", "Dog year", "dog yr", 31556952.0 / 7)
            }
        },
        category("speed", "Speed", "speed", "How fast things move") {
            u("kmh", "Kilometer per hour", "km/h", 1 / 3.6, "kph")
            u("mph", "Mile per hour", "mph", MILE / 3600)
            u("ms", "Meter per second", "m/s", 1.0)
            u("kn", "Knot", "kn", 1852.0 / 3600, "kt")
            u("fts", "Foot per second", "ft/s", FOOT)
            group(METRIC) {
                u("kms", "Kilometer per second", "km/s", 1e3)
                u("cms", "Centimeter per second", "cm/s", 1e-2)
                u("mms", "Millimeter per second", "mm/s", 1e-3)
                u("mmin", "Meter per minute", "m/min", 1.0 / 60)
            }
            group(IMPERIAL) {
                u("ftmin", "Foot per minute", "ft/min", FOOT / 60)
                u("ins", "Inch per second", "in/s", INCH)
                u("mis", "Mile per second", "mi/s", MILE)
            }
            group(SCIENTIFIC) {
                u("c", "Speed of light", "c", C)
                u("mach", "Mach (sea level, 15 °C)", "Ma", 340.29)
                u("soundwater", "Speed of sound in water", "c water", 1481.0)
            }
            group(ASTRONOMICAL) {
                u("orbit", "Earth's orbital speed", "v⊕", 29780.0)
                u("escape", "Earth escape velocity", "vₑ", 11186.0)
                u("auday", "AU per day", "au/d", AU / 86400)
            }
            group(FUN) {
                u("ffn", "Furlong per fortnight", "fur/fn", 201.168 / 1209600.0)
                u("walk", "Walking pace", "walk", 1.4)
                u("snail", "Garden snail", "🐌", 0.013)
                u("cheetah", "Cheetah sprint", "🐆", 30.0)
            }
        },
        category("acceleration", "Acceleration", "rocket_launch", "Change of speed and g-forces") {
            u("ms2", "Meter per second²", "m/s²", 1.0)
            u("g", "Standard gravity", "g", G0, "g-force")
            u("fts2", "Foot per second²", "ft/s²", FOOT)
            group(METRIC) {
                u("gal", "Gal (galileo)", "Gal", 0.01)
                u("mgal", "Milligal", "mGal", 1e-5)
                u("cms2", "Centimeter per second²", "cm/s²", 0.01)
                u("kmhs", "Kilometer per hour per second", "km/h/s", 1 / 3.6)
            }
            group(IMPERIAL) {
                u("ins2", "Inch per second²", "in/s²", INCH)
                u("mphs", "Mile per hour per second", "mph/s", MILE / 3600)
                u("kns", "Knot per second", "kn/s", 1852.0 / 3600)
            }
            group(ASTRONOMICAL) {
                u("moon", "Moon surface gravity", "g☾", 1.62)
                u("mars", "Mars surface gravity", "g♂", 3.721)
                u("jupiter", "Jupiter surface gravity", "g♃", 24.79)
                u("sun", "Sun surface gravity", "g☉", 274.0)
            }
        },
        category("pressure", "Pressure", "compress", "Force per area: weather, tyres, depth") {
            u("kpa", "Kilopascal", "kPa", 1e3)
            u("bar", "Bar", "bar", 1e5)
            u("psi", "Pound per square inch", "psi", 6894.757293168)
            u("atm", "Atmosphere", "atm", 101325.0)
            u("pa", "Pascal", "Pa", 1.0)
            u("mmhg", "Millimeter of mercury", "mmHg", 133.322387415)
            group(METRIC) {
                u("hpa", "Hectopascal", "hPa", 100.0)
                u("mpa", "Megapascal", "MPa", 1e6)
                u("gpa", "Gigapascal", "GPa", 1e9)
                u("mbar", "Millibar", "mbar", 100.0)
                u("at", "Technical atmosphere", "at", 98066.5, "kgf/cm²")
                u("torr", "Torr", "Torr", 101325.0 / 760)
                u("cmh2o", "Centimeter of water", "cmH₂O", 98.0665)
                u("mmh2o", "Millimeter of water", "mmH₂O", 9.80665)
                u("barye", "Barye", "Ba", 0.1, "dyn/cm²")
                u("pieze", "Pièze", "pz", 1000.0)
            }
            group(IMPERIAL) {
                u("inhg", "Inch of mercury", "inHg", 3386.389)
                u("inh2o", "Inch of water", "inH₂O", 249.08891)
                u("ksi", "Kilopound per square inch", "ksi", 6.894757293168e6)
                u("psf", "Pound per square foot", "psf", 47.880258980336)
            }
            group(SCIENTIFIC) {
                u("planck", "Planck pressure", "pP", 4.63309e113)
            }
            group(FUN) {
                u("mariana", "Bottom of the Mariana Trench", "trench", 1.086e8)
                u("venus", "Venus surface pressure", "♀", 9.2e6)
            }
        },
        category("energy", "Energy", "bolt", "Work, heat, food and fuel") {
            u("j", "Joule", "J", 1.0)
            u("kj", "Kilojoule", "kJ", 1e3)
            u("kcal", "Kilocalorie (food Calorie)", "kcal", CAL * 1000, "Cal", "calorie")
            u("kwh", "Kilowatt-hour", "kWh", 3.6e6)
            u("cal", "Calorie (thermochemical)", "cal", CAL)
            u("btu", "British thermal unit", "BTU", BTU)
            group(METRIC) {
                u("mj", "Megajoule", "MJ", 1e6)
                u("gj", "Gigajoule", "GJ", 1e9)
                u("mJ", "Millijoule", "mJ", 1e-3)
                u("uj", "Microjoule", "µJ", 1e-6)
                u("wh", "Watt-hour", "Wh", 3600.0)
                u("mwh", "Megawatt-hour", "MWh", 3.6e9)
                u("gwh", "Gigawatt-hour", "GWh", 3.6e12)
                u("calit", "Calorie (IT)", "cal IT", 4.1868)
                u("erg", "Erg", "erg", 1e-7)
            }
            group(IMPERIAL) {
                u("ftlbf", "Foot-pound", "ft·lbf", 1.3558179483314004)
                u("inlbf", "Inch-pound", "in·lbf", 0.1129848290276167)
                u("therm", "Therm", "thm", BTU * 1e5)
                u("quad", "Quad", "quad", BTU * 1e15)
            }
            group(SCIENTIFIC) {
                u("ev", "Electronvolt", "eV", EV)
                u("kev", "Kiloelectronvolt", "keV", EV * 1e3)
                u("mev", "Megaelectronvolt", "MeV", EV * 1e6)
                u("gev", "Gigaelectronvolt", "GeV", EV * 1e9)
                u("tev", "Teraelectronvolt", "TeV", EV * 1e12)
                u("hartree", "Hartree", "Eh", 4.3597447222071e-18)
                u("rydberg", "Rydberg", "Ry", 2.1798723611035e-18)
                u("planck", "Planck energy", "EP", 1.9561e9)
                u("foe", "Foe (supernova)", "foe", 1e44)
            }
            group(REGIONAL) {
                u("tnt", "Ton of TNT", "tTNT", 4.184e9)
                u("kt", "Kiloton of TNT", "kt", 4.184e12)
                u("mt", "Megaton of TNT", "Mt", 4.184e15)
                u("boe", "Barrel of oil equivalent", "BOE", 6.1178632e9)
                u("toe", "Tonne of oil equivalent", "toe", 4.1868e10)
                u("tce", "Tonne of coal equivalent", "tce", 2.93076e10)
            }
            group(FUN) {
                u("bigmac", "Big Mac", "🍔", 563 * CAL * 1000)
                u("banana", "Banana (food energy)", "🍌", 105 * CAL * 1000)
                u("aa", "AA battery", "AA", 9360.0)
                u("lightning", "Lightning bolt", "⚡", 1e9)
            }
        },
        category("power", "Power", "power", "Rate of energy: engines, bulbs, plants") {
            u("w", "Watt", "W", 1.0)
            u("kw", "Kilowatt", "kW", 1e3)
            u("hp", "Horsepower (mechanical)", "hp", 745.69987158227022)
            u("ps", "Metric horsepower", "PS", 735.49875, "cv", "pk")
            group(METRIC) {
                u("mw", "Milliwatt", "mW", 1e-3)
                u("MW", "Megawatt", "MW", 1e6)
                u("gw", "Gigawatt", "GW", 1e9)
                u("tw", "Terawatt", "TW", 1e12)
                u("kcalh", "Kilocalorie per hour", "kcal/h", CAL * 1000 / 3600)
                u("cals", "Calorie per second", "cal/s", CAL)
                u("ergs", "Erg per second", "erg/s", 1e-7)
                u("va", "Volt-ampere", "VA", 1.0)
            }
            group(IMPERIAL) {
                u("hpe", "Horsepower (electric)", "hp(E)", 746.0)
                u("hpboiler", "Horsepower (boiler)", "hp(S)", 9809.5)
                u("btuh", "BTU per hour", "BTU/h", BTU / 3600)
                u("ftlbfs", "Foot-pound per second", "ft·lbf/s", 1.3558179483314004)
                u("tonref", "Ton of refrigeration", "TR", 3516.8528420667)
            }
            group(ASTRONOMICAL) {
                u("lsun", "Solar luminosity", "L☉", 3.828e26)
            }
            group(FUN) {
                u("human", "Resting human", "🧍", 100.0)
                u("bulb", "Old light bulb", "💡", 60.0)
                u("horse", "Actual horse (peak)", "🐎", 11000.0)
            }
        },
        category("force", "Force", "fitness_center", "Push and pull") {
            u("n", "Newton", "N", 1.0)
            u("kn", "Kilonewton", "kN", 1e3)
            u("kgf", "Kilogram-force", "kgf", G0, "kilopond", "kp")
            u("lbf", "Pound-force", "lbf", LBF)
            group(METRIC) {
                u("mn", "Millinewton", "mN", 1e-3)
                u("MN", "Meganewton", "MN", 1e6)
                u("dyn", "Dyne", "dyn", 1e-5)
                u("gf", "Gram-force", "gf", G0 / 1000)
                u("tf", "Tonne-force", "tf", G0 * 1000)
                u("sthene", "Sthène", "sn", 1000.0)
            }
            group(IMPERIAL) {
                u("ozf", "Ounce-force", "ozf", LBF / 16)
                u("pdl", "Poundal", "pdl", 0.138254954376)
                u("kip", "Kip", "kip", LBF * 1000)
                u("tonf", "Short ton-force", "tonf", LBF * 2000)
            }
            group(SCIENTIFIC) {
                u("planck", "Planck force", "FP", 1.21027e44)
            }
        },
        category("torque", "Torque", "settings", "Twisting force: bolts and engines") {
            u("nm", "Newton meter", "N·m", 1.0)
            u("lbfft", "Pound-foot", "lbf·ft", 1.3558179483314004, "ft-lb")
            u("lbfin", "Pound-inch", "lbf·in", 0.1129848290276167)
            u("kgfm", "Kilogram-force meter", "kgf·m", G0)
            group(METRIC) {
                u("knm", "Kilonewton meter", "kN·m", 1e3)
                u("ncm", "Newton centimeter", "N·cm", 0.01)
                u("nmm", "Newton millimeter", "N·mm", 1e-3)
                u("kgfcm", "Kilogram-force centimeter", "kgf·cm", G0 / 100)
                u("dyncm", "Dyne centimeter", "dyn·cm", 1e-7)
            }
            group(IMPERIAL) {
                u("ozfin", "Ounce-force inch", "ozf·in", 7.0615518333e-3)
            }
        },
        category("density", "Density", "water_drop", "Mass per volume") {
            u("kgm3", "Kilogram per cubic meter", "kg/m³", 1.0)
            u("gcm3", "Gram per cubic centimeter", "g/cm³", 1000.0, "g/cc")
            u("gml", "Gram per milliliter", "g/mL", 1000.0)
            u("lbft3", "Pound per cubic foot", "lb/ft³", 16.01846337396)
            group(METRIC) {
                u("kgl", "Kilogram per liter", "kg/L", 1000.0)
                u("gl", "Gram per liter", "g/L", 1.0)
                u("mgl", "Milligram per liter", "mg/L", 1e-3)
                u("tm3", "Tonne per cubic meter", "t/m³", 1000.0)
                u("water", "Water (at 4 °C)", "× water", 1000.0, "specific gravity")
            }
            group(IMPERIAL) {
                u("lbin3", "Pound per cubic inch", "lb/in³", 27679.9047102)
                u("lbgalus", "Pound per gallon (US)", "lb/gal", 119.826427317)
                u("lbgaluk", "Pound per gallon (UK)", "lb/gal UK", 99.776372663)
                u("ozin3", "Ounce per cubic inch", "oz/in³", 1729.99404439)
                u("slugft3", "Slug per cubic foot", "slug/ft³", 515.378818)
            }
            group(ASTRONOMICAL) {
                u("sunavg", "Sun (average)", "ρ☉", 1408.0)
                u("neutron", "Neutron star", "ρNS", 4e17)
            }
        },
        category("data", "Data storage", "storage", "Bits, bytes and binary prefixes") {
            u("B", "Byte", "B", 8.0)
            u("kB", "Kilobyte", "kB", 8e3)
            u("MB", "Megabyte", "MB", 8e6)
            u("GB", "Gigabyte", "GB", 8e9)
            u("TB", "Terabyte", "TB", 8e12)
            u("bit", "Bit", "bit", 1.0, "b")
            group(METRIC) {
                u("PB", "Petabyte", "PB", 8e15)
                u("EB", "Exabyte", "EB", 8e18)
                u("ZB", "Zettabyte", "ZB", 8e21)
                u("YB", "Yottabyte", "YB", 8e24)
                u("kbit", "Kilobit", "kbit", 1e3)
                u("Mbit", "Megabit", "Mbit", 1e6)
                u("Gbit", "Gigabit", "Gbit", 1e9)
                u("Tbit", "Terabit", "Tbit", 1e12)
            }
            group(SCIENTIFIC) {
                u("KiB", "Kibibyte", "KiB", 8.0 * 1024)
                u("MiB", "Mebibyte", "MiB", 8.0 * 1024 * 1024)
                u("GiB", "Gibibyte", "GiB", 8.0 * 1024 * 1024 * 1024)
                u("TiB", "Tebibyte", "TiB", 8.0 * 1024 * 1024 * 1024 * 1024)
                u("PiB", "Pebibyte", "PiB", 8.0 * 1024.0 * 1024 * 1024 * 1024 * 1024)
                u("EiB", "Exbibyte", "EiB", 8.0 * 1024.0 * 1024 * 1024 * 1024 * 1024 * 1024)
                u("Kibit", "Kibibit", "Kibit", 1024.0)
                u("Mibit", "Mebibit", "Mibit", 1024.0 * 1024)
                u("Gibit", "Gibibit", "Gibit", 1024.0 * 1024 * 1024)
                u("nibble", "Nibble", "nibble", 4.0)
                u("word", "Word (16-bit)", "word", 16.0)
                u("dword", "Double word (32-bit)", "dword", 32.0)
                u("qword", "Quad word (64-bit)", "qword", 64.0)
                u("nat", "Nat (information)", "nat", 1.4426950408889634)
                u("trit", "Trit", "trit", 1.584962500721156)
            }
            group(FUN) {
                u("floppy", "3½\" floppy disk", "💾", 1474560.0 * 8)
                u("cd", "CD-ROM (700 MiB)", "CD", 700.0 * 1024 * 1024 * 8)
                u("dvd", "DVD (single layer)", "DVD", 4.7e9 * 8)
                u("bluray", "Blu-ray (single layer)", "BD", 25e9 * 8)
            }
        },
        category("datarate", "Data transfer rate", "network_check", "Bandwidth and download speed") {
            u("mbps", "Megabit per second", "Mbps", 1e6, "Mbit/s")
            u("MBps", "Megabyte per second", "MB/s", 8e6)
            u("kbps", "Kilobit per second", "kbps", 1e3)
            u("gbps", "Gigabit per second", "Gbps", 1e9)
            u("bps", "Bit per second", "bps", 1.0)
            group(METRIC) {
                u("tbps", "Terabit per second", "Tbps", 1e12)
                u("Bps", "Byte per second", "B/s", 8.0)
                u("kBps", "Kilobyte per second", "kB/s", 8e3)
                u("GBps", "Gigabyte per second", "GB/s", 8e9)
                u("KiBps", "Kibibyte per second", "KiB/s", 8.0 * 1024)
                u("MiBps", "Mebibyte per second", "MiB/s", 8.0 * 1024 * 1024)
                u("GiBps", "Gibibyte per second", "GiB/s", 8.0 * 1024 * 1024 * 1024)
            }
            group(FUN) {
                u("modem", "Dial-up modem (56k)", "56k", 56000.0)
                u("t1", "T1 line", "T1", 1.544e6)
                u("e1", "E1 line", "E1", 2.048e6)
                u("usb2", "USB 2.0", "USB2", 480e6)
                u("usb3", "USB 3.0", "USB3", 5e9)
                u("sata3", "SATA III", "SATA3", 6e9)
                u("tb3", "Thunderbolt 3", "TB3", 40e9)
                u("pigeon", "Carrier pigeon with a 256 GB microSD", "🐦", 256e9 * 8 / 3600)
            }
        },
        category("angle", "Angle", "architecture", "Rotation and direction") {
            u("deg", "Degree", "°", PI / 180)
            u("rad", "Radian", "rad", 1.0)
            u("grad", "Gradian", "gon", PI / 200, "grad")
            u("turn", "Turn", "tr", 2 * PI, "revolution", "rev")
            group(METRIC) {
                u("arcmin", "Arcminute", "′", PI / 10800)
                u("arcsec", "Arcsecond", "″", PI / 648000)
                u("mas", "Milliarcsecond", "mas", PI / 648000000)
                u("mrad", "Milliradian", "mrad", 1e-3)
                u("urad", "Microradian", "µrad", 1e-6)
            }
            group(SCIENTIFIC) {
                u("quadrant", "Quadrant (right angle)", "quad", PI / 2)
                u("sextant", "Sextant", "sextant", PI / 3)
                u("octant", "Octant", "octant", PI / 4)
                u("sign", "Sign (zodiac)", "sign", PI / 6)
                u("hourangle", "Hour angle", "h", PI / 12)
                u("point", "Compass point", "point", 2 * PI / 32)
                u("brad", "Binary degree", "brad", 2 * PI / 256)
            }
            group(REGIONAL) {
                u("milnato", "Mil (NATO)", "mil", 2 * PI / 6400)
                u("milwp", "Mil (Warsaw Pact)", "mil WP", 2 * PI / 6000)
                u("milsw", "Mil (Sweden)", "streck", 2 * PI / 6300)
            }
        },
        category("frequency", "Frequency", "graphic_eq", "Cycles, rotations and beats") {
            u("hz", "Hertz", "Hz", 1.0)
            u("khz", "Kilohertz", "kHz", 1e3)
            u("mhz", "Megahertz", "MHz", 1e6)
            u("ghz", "Gigahertz", "GHz", 1e9)
            u("rpm", "Revolutions per minute", "rpm", 1.0 / 60)
            group(METRIC) {
                u("thz", "Terahertz", "THz", 1e12)
                u("mHz", "Millihertz", "mHz", 1e-3)
                u("rads", "Radian per second", "rad/s", 1 / (2 * PI))
                u("degs", "Degree per second", "°/s", 1.0 / 360)
                u("cph", "Cycles per hour", "cph", 1.0 / 3600)
                u("perday", "Cycles per day", "/d", 1.0 / 86400)
            }
            group(FUN) {
                u("bpm", "Beats per minute", "BPM", 1.0 / 60)
                u("hummingbird", "Hummingbird wingbeat", "🐦", 50.0)
            }
        },
        category("fuel", "Fuel economy", "local_gas_station", "Distance per fuel and consumption") {
            u("kml", "Kilometer per liter", "km/L", 1.0)
            recip("l100km", "Liter per 100 km", "L/100 km", 100.0)
            u("mpgus", "Mile per gallon (US)", "mpg", MILE / 1000 / US_GAL / 1000)
            u("mpguk", "Mile per gallon (UK)", "mpg UK", MILE / 1000 / UK_GAL / 1000)
            group(METRIC) {
                u("mil", "Mile per liter", "mi/L", MILE / 1000)
                u("kmgalus", "Kilometer per gallon (US)", "km/gal", 1.0 / (US_GAL * 1000))
                recip("lkm", "Liter per kilometer", "L/km", 1.0)
            }
            group(IMPERIAL) {
                recip("gal100mi", "Gallon (US) per 100 miles", "gal/100 mi", 100 * MILE / 1000 / (US_GAL * 1000))
                recip("galuk100mi", "Gallon (UK) per 100 miles", "gal UK/100 mi", 100 * MILE / 1000 / (UK_GAL * 1000))
            }
        },
        category("current", "Electric current", "electric_bolt", "Flow of charge") {
            u("a", "Ampere", "A", 1.0, "amp")
            u("ma", "Milliampere", "mA", 1e-3)
            u("ua", "Microampere", "µA", 1e-6)
            u("ka", "Kiloampere", "kA", 1e3)
            group(SCIENTIFIC) {
                u("na", "Nanoampere", "nA", 1e-9)
                u("abampere", "Abampere (biot)", "abA", 10.0, "biot")
                u("statampere", "Statampere", "statA", 3.335641e-10)
                u("planck", "Planck current", "IP", 3.4789e25)
            }
        },
        category("voltage", "Voltage", "battery_charging_full", "Electric potential difference") {
            u("v", "Volt", "V", 1.0)
            u("mv", "Millivolt", "mV", 1e-3)
            u("kv", "Kilovolt", "kV", 1e3)
            group(SCIENTIFIC) {
                u("uv", "Microvolt", "µV", 1e-6)
                u("MV", "Megavolt", "MV", 1e6)
                u("abvolt", "Abvolt", "abV", 1e-8)
                u("statvolt", "Statvolt", "statV", 299.792458)
                u("planck", "Planck voltage", "VP", 1.04295e27)
            }
        },
        category("resistance", "Electrical resistance", "tune", "Opposition to current") {
            u("ohm", "Ohm", "Ω", 1.0)
            u("kohm", "Kiloohm", "kΩ", 1e3)
            u("Mohm", "Megaohm", "MΩ", 1e6)
            u("mohm", "Milliohm", "mΩ", 1e-3)
            group(SCIENTIFIC) {
                u("Gohm", "Gigaohm", "GΩ", 1e9)
                u("abohm", "Abohm", "abΩ", 1e-9)
                u("statohm", "Statohm", "statΩ", 8.987551787e11)
                u("klitzing", "von Klitzing constant", "RK", 25812.80745)
            }
        },
        category("capacitance", "Capacitance", "battery_std", "Ability to store charge") {
            u("f", "Farad", "F", 1.0)
            u("uf", "Microfarad", "µF", 1e-6)
            u("nf", "Nanofarad", "nF", 1e-9)
            u("pf", "Picofarad", "pF", 1e-12)
            group(SCIENTIFIC) {
                u("mf", "Millifarad", "mF", 1e-3)
                u("abfarad", "Abfarad", "abF", 1e9)
                u("statfarad", "Statfarad", "statF", 1.1126500560536e-12)
            }
        },
        category("inductance", "Inductance", "cable", "Opposition to current change") {
            u("h", "Henry", "H", 1.0)
            u("mh", "Millihenry", "mH", 1e-3)
            u("uh", "Microhenry", "µH", 1e-6)
            group(SCIENTIFIC) {
                u("nh", "Nanohenry", "nH", 1e-9)
                u("abhenry", "Abhenry", "abH", 1e-9)
                u("stathenry", "Stathenry", "statH", 8.987551787e11)
            }
        },
        category("charge", "Electric charge", "battery_full", "Quantity of electricity") {
            u("c", "Coulomb", "C", 1.0)
            u("mah", "Milliampere-hour", "mAh", 3.6)
            u("ah", "Ampere-hour", "Ah", 3600.0)
            group(SCIENTIFIC) {
                u("mc", "Millicoulomb", "mC", 1e-3)
                u("uc", "Microcoulomb", "µC", 1e-6)
                u("nc", "Nanocoulomb", "nC", 1e-9)
                u("e", "Elementary charge", "e", 1.602176634e-19)
                u("faraday", "Faraday", "F", 96485.33212)
                u("abcoulomb", "Abcoulomb", "abC", 10.0)
                u("statcoulomb", "Statcoulomb", "statC", 3.335640952e-10)
            }
        },
        category("conductance", "Electrical conductance", "settings_input_component", "Ease of current flow") {
            u("s", "Siemens", "S", 1.0, "mho")
            u("ms", "Millisiemens", "mS", 1e-3)
            u("us", "Microsiemens", "µS", 1e-6)
            group(SCIENTIFIC) {
                u("abmho", "Abmho", "abS", 1e9)
                u("statmho", "Statmho", "statS", 1.11265e-12)
                u("g0", "Conductance quantum", "G₀", 7.748091729e-5)
            }
        },
        category("magflux", "Magnetic flux", "blur_circular", "Total magnetic field through a surface") {
            u("wb", "Weber", "Wb", 1.0, "volt-second")
            u("mwb", "Milliweber", "mWb", 1e-3)
            u("mx", "Maxwell", "Mx", 1e-8)
            group(SCIENTIFIC) {
                u("phi0", "Magnetic flux quantum", "Φ₀", 2.067833848e-15)
            }
        },
        category("magfield", "Magnetic flux density", "explore", "Magnetic field strength (B)") {
            u("t", "Tesla", "T", 1.0)
            u("mt", "Millitesla", "mT", 1e-3)
            u("ut", "Microtesla", "µT", 1e-6)
            u("gauss", "Gauss", "G", 1e-4)
            group(SCIENTIFIC) {
                u("nt", "Nanotesla", "nT", 1e-9)
                u("gamma", "Gamma", "γ", 1e-9)
            }
            group(FUN) {
                u("earth", "Earth's magnetic field", "B⊕", 5e-5)
                u("fridge", "Fridge magnet", "🧲", 5e-3)
                u("mri", "MRI scanner", "MRI", 3.0)
            }
        },
        category("hfield", "Magnetic field strength", "wifi_tethering", "Magnetising field (H)") {
            u("am", "Ampere per meter", "A/m", 1.0)
            u("oe", "Oersted", "Oe", 1000 / (4 * PI))
            u("kam", "Kiloampere per meter", "kA/m", 1e3)
        },
        category("illuminance", "Illuminance", "light_mode", "Light falling on a surface") {
            u("lux", "Lux", "lx", 1.0)
            u("fc", "Foot-candle", "fc", 10.763910416709722, "lm/ft²")
            group(SCIENTIFIC) {
                u("phot", "Phot", "ph", 1e4)
                u("nox", "Nox", "nx", 1e-3)
                u("klux", "Kilolux", "klx", 1e3)
            }
            group(FUN) {
                u("sun", "Direct sunlight", "☀", 100000.0)
                u("office", "Office lighting", "office", 400.0)
                u("fullmoon", "Full moon", "🌕", 0.25)
            }
        },
        category("luminance", "Luminance", "brightness_high", "Brightness of a surface or screen") {
            u("nit", "Candela per square meter (nit)", "cd/m²", 1.0, "nit")
            u("fl", "Foot-lambert", "fL", 3.4262590996)
            group(SCIENTIFIC) {
                u("stilb", "Stilb", "sb", 1e4)
                u("lambert", "Lambert", "L", 1e4 / PI)
                u("apostilb", "Apostilb", "asb", 1 / PI)
                u("skot", "Skot", "sk", 1e-3 / PI)
                u("cdin2", "Candela per square inch", "cd/in²", 1550.0031)
            }
        },
        category("intensity", "Luminous intensity", "flare", "Light emitted in a direction") {
            u("cd", "Candela", "cd", 1.0)
            u("cp", "Candlepower (new)", "cp", 1.0)
            group(HISTORICAL) {
                u("hefner", "Hefnerkerze", "HK", 0.903)
                u("carcel", "Carcel", "carcel", 9.74)
                u("violle", "Violle", "violle", 20.17)
            }
        },
        category("radioactivity", "Radioactivity", "warning", "Decays per second") {
            u("bq", "Becquerel", "Bq", 1.0)
            u("ci", "Curie", "Ci", 3.7e10)
            u("kbq", "Kilobecquerel", "kBq", 1e3)
            u("mbq", "Megabecquerel", "MBq", 1e6)
            group(SCIENTIFIC) {
                u("gbq", "Gigabecquerel", "GBq", 1e9)
                u("tbq", "Terabecquerel", "TBq", 1e12)
                u("mci", "Millicurie", "mCi", 3.7e7)
                u("uci", "Microcurie", "µCi", 3.7e4)
                u("nci", "Nanocurie", "nCi", 37.0)
                u("rd", "Rutherford", "Rd", 1e6)
                u("dpm", "Disintegrations per minute", "dpm", 1.0 / 60)
            }
        },
        category("dose", "Radiation dose", "health_and_safety", "Absorbed and equivalent dose") {
            u("sv", "Sievert", "Sv", 1.0)
            u("msv", "Millisievert", "mSv", 1e-3)
            u("usv", "Microsievert", "µSv", 1e-6)
            u("gy", "Gray (absorbed)", "Gy", 1.0)
            group(SCIENTIFIC) {
                u("rem", "Rem", "rem", 0.01)
                u("mrem", "Millirem", "mrem", 1e-5)
                u("rad", "Rad (absorbed)", "rad", 0.01)
                u("mgy", "Milligray", "mGy", 1e-3)
                u("ergg", "Erg per gram", "erg/g", 1e-4)
            }
            group(FUN) {
                u("banana", "Banana equivalent dose", "BED", 1e-7)
                u("xray", "Chest X-ray", "CXR", 2e-5)
                u("flight", "Transatlantic flight", "✈", 8e-5)
                u("ct", "CT scan (chest)", "CT", 7e-3)
            }
        },
        category("exposure", "Radiation exposure", "sensors", "Ionisation of air") {
            u("ckg", "Coulomb per kilogram", "C/kg", 1.0)
            u("r", "Roentgen", "R", 2.58e-4)
            u("mr", "Milliroentgen", "mR", 2.58e-7)
            u("ur", "Microroentgen", "µR", 2.58e-10)
        },
        category("viscosity", "Dynamic viscosity", "opacity", "Resistance of a fluid to flow") {
            u("pas", "Pascal second", "Pa·s", 1.0)
            u("mpas", "Millipascal second", "mPa·s", 1e-3)
            u("p", "Poise", "P", 0.1)
            u("cp", "Centipoise", "cP", 1e-3)
            group(IMPERIAL) {
                u("lbfts", "Pound per foot second", "lb/(ft·s)", 1.488163944)
                u("lbfsft2", "Pound-force second per ft²", "lbf·s/ft²", 47.88025898)
                u("reyn", "Reyn", "reyn", 6894.757293168)
            }
            group(FUN) {
                u("water", "Water at 20 °C", "H₂O", 1.002e-3)
                u("honey", "Honey", "🍯", 10.0)
            }
        },
        category("kinvisc", "Kinematic viscosity", "waves", "Viscosity divided by density") {
            u("m2s", "Square meter per second", "m²/s", 1.0)
            u("st", "Stokes", "St", 1e-4)
            u("cst", "Centistokes", "cSt", 1e-6)
            u("ft2s", "Square foot per second", "ft²/s", FOOT * FOOT)
            u("in2s", "Square inch per second", "in²/s", INCH * INCH)
        },
        category("flow", "Volumetric flow", "water", "Volume per time: pumps, rivers, taps") {
            u("ls", "Liter per second", "L/s", 1e-3)
            u("lmin", "Liter per minute", "L/min", 1e-3 / 60)
            u("m3h", "Cubic meter per hour", "m³/h", 1.0 / 3600)
            u("gpm", "Gallon (US) per minute", "gpm", US_GAL / 60)
            u("cfm", "Cubic foot per minute", "cfm", FOOT * FOOT * FOOT / 60)
            u("m3s", "Cubic meter per second", "m³/s", 1.0, "cumec")
            group(METRIC) {
                u("lh", "Liter per hour", "L/h", 1e-3 / 3600)
                u("mlmin", "Milliliter per minute", "mL/min", 1e-6 / 60)
                u("m3min", "Cubic meter per minute", "m³/min", 1.0 / 60)
            }
            group(IMPERIAL) {
                u("cfs", "Cubic foot per second", "cfs", FOOT * FOOT * FOOT, "cusec")
                u("gph", "Gallon (US) per hour", "gph", US_GAL / 3600)
                u("gpmuk", "Gallon (UK) per minute", "gpm UK", UK_GAL / 60)
                u("bpd", "Barrel (oil) per day", "bbl/d", 0.158987294928 / 86400)
                u("acftd", "Acre-foot per day", "ac·ft/d", 1233.48183754752 / 86400)
            }
            group(FUN) {
                u("niagara", "Niagara Falls (average)", "Niagara", 2400.0)
                u("amazon", "Amazon River", "Amazon", 209000.0)
            }
        },
        category("massflow", "Mass flow", "conveyor_belt", "Mass per time") {
            u("kgs", "Kilogram per second", "kg/s", 1.0)
            u("kgh", "Kilogram per hour", "kg/h", 1.0 / 3600)
            u("gs", "Gram per second", "g/s", 1e-3)
            u("th", "Tonne per hour", "t/h", 1000.0 / 3600)
            u("lbs", "Pound per second", "lb/s", LB)
            u("lbh", "Pound per hour", "lb/h", LB / 3600)
            u("lbmin", "Pound per minute", "lb/min", LB / 60)
        },
        category("concentration", "Molar concentration", "science", "Amount of substance per volume") {
            u("molar", "Molar", "M", 1000.0, "mol/L")
            u("mm", "Millimolar", "mM", 1.0)
            u("um", "Micromolar", "µM", 1e-3)
            u("nm", "Nanomolar", "nM", 1e-6)
            u("pm", "Picomolar", "pM", 1e-9)
            u("molm3", "Mole per cubic meter", "mol/m³", 1.0)
        },
        category("amount", "Amount of substance", "bubble_chart", "Counting atoms and molecules") {
            u("mol", "Mole", "mol", 1.0)
            u("mmol", "Millimole", "mmol", 1e-3)
            u("umol", "Micromole", "µmol", 1e-6)
            u("kmol", "Kilomole", "kmol", 1e3)
            u("lbmol", "Pound-mole", "lb-mol", 453.59237)
            u("particles", "Particles", "entities", 1 / 6.02214076e23)
        },
        category("angvel", "Angular velocity", "rotate_right", "How fast something spins") {
            u("rpm", "Revolutions per minute", "rpm", 2 * PI / 60)
            u("rads", "Radian per second", "rad/s", 1.0)
            u("degs", "Degree per second", "°/s", PI / 180)
            u("rps", "Revolutions per second", "rps", 2 * PI)
            u("degmin", "Degree per minute", "°/min", PI / 180 / 60)
            u("degh", "Degree per hour", "°/h", PI / 180 / 3600)
            u("radmin", "Radian per minute", "rad/min", 1.0 / 60)
        },
        category("momentum", "Momentum", "sports_baseball", "Mass in motion") {
            u("kgms", "Kilogram meter per second", "kg·m/s", 1.0, "N·s")
            u("gcms", "Gram centimeter per second", "g·cm/s", 1e-5)
            u("lbfts", "Pound foot per second", "lb·ft/s", 0.138254954376)
            u("mevc", "MeV/c", "MeV/c", 5.344286e-22)
        },
        category("thermalcond", "Thermal conductivity", "device_thermostat", "How well materials conduct heat") {
            u("wmk", "Watt per meter kelvin", "W/(m·K)", 1.0)
            u("btuhftf", "BTU per hour foot °F", "BTU/(h·ft·°F)", 1.730734666)
            u("calscmc", "Calorie per second cm °C", "cal/(s·cm·°C)", 418.4)
            u("kcalhmc", "Kilocalorie per hour meter °C", "kcal/(h·m·°C)", 1.163)
        },
        category("specificheat", "Specific heat capacity", "local_fire_department", "Energy to warm a mass") {
            u("jkgk", "Joule per kilogram kelvin", "J/(kg·K)", 1.0)
            u("kjkgk", "Kilojoule per kilogram kelvin", "kJ/(kg·K)", 1e3)
            u("calgc", "Calorie per gram °C", "cal/(g·°C)", 4184.0)
            u("btulbf", "BTU per pound °F", "BTU/(lb·°F)", 4186.8)
        },
        category("heatflux", "Heat flux & irradiance", "wb_sunny", "Power per area: sunlight, heating") {
            u("wm2", "Watt per square meter", "W/m²", 1.0)
            u("kwm2", "Kilowatt per square meter", "kW/m²", 1e3)
            u("wcm2", "Watt per square centimeter", "W/cm²", 1e4)
            u("btuhft2", "BTU per hour square foot", "BTU/(h·ft²)", 3.154590745)
            u("langleymin", "Langley per minute", "Ly/min", 697.3333)
            group(ASTRONOMICAL) {
                u("solarconst", "Solar constant", "S☉", 1361.0)
            }
        },
        category("surfacetension", "Surface tension", "bubble_chart", "Force along a liquid surface") {
            u("nm", "Newton per meter", "N/m", 1.0)
            u("mnm", "Millinewton per meter", "mN/m", 1e-3)
            u("dyncm", "Dyne per centimeter", "dyn/cm", 1e-3)
            u("lbfin", "Pound-force per inch", "lbf/in", 175.12683)
            group(FUN) {
                u("water", "Water at 20 °C", "H₂O", 0.07275)
            }
        },
        category("typography", "Typography & screen", "text_fields", "Points, picas, pixels and print sizes") {
            u("pt", "Point (DTP)", "pt", 1.0)
            u("px96", "Pixel (96 dpi, CSS)", "px", 0.75)
            u("mm", "Millimeter", "mm", 72 / 25.4)
            u("in", "Inch", "in", 72.0)
            u("pica", "Pica", "pc", 12.0)
            group(METRIC) {
                u("cm", "Centimeter", "cm", 720 / 25.4)
                u("q", "Q (quarter millimeter)", "Q", 18 / 25.4)
                u("px72", "Pixel (72 dpi)", "px@72", 1.0)
                u("dp160", "Android dp (160 dpi)", "dp", 72.0 / 160)
            }
            group(HISTORICAL) {
                u("didot", "Didot point", "dd", 0.376065 * 72 / 25.4)
                u("cicero", "Cicero", "cc", 12 * 0.376065 * 72 / 25.4)
                u("twip", "Twip", "twip", 1.0 / 20)
                u("agate", "Agate", "agate", 5.5)
            }
        },
        category("count", "Numbers & counts", "pin", "Dozens, lakhs, googols") {
            u("one", "One", "×1", 1.0, "unit")
            u("dozen", "Dozen", "doz", 12.0)
            u("hundred", "Hundred", "×100", 100.0)
            u("thousand", "Thousand", "k", 1e3)
            u("million", "Million", "M", 1e6)
            u("billion", "Billion", "B", 1e9)
            group(REGIONAL) {
                u("lakh", "Lakh (India)", "lakh", 1e5)
                u("crore", "Crore (India)", "crore", 1e7)
                u("arab", "Arab (India)", "arab", 1e9)
                u("myriad", "Myriad / wàn", "万", 1e4)
                u("yi", "Yì (China)", "亿", 1e8)
                u("oku", "Oku (Japan)", "億", 1e8)
            }
            group(HISTORICAL) {
                u("score", "Score", "score", 20.0)
                u("bakers", "Baker's dozen", "b.doz", 13.0)
                u("gross", "Gross", "gr", 144.0)
                u("greatgross", "Great gross", "g.gr", 1728.0)
                u("shock", "Shock", "shock", 60.0)
            }
            group(SCIENTIFIC) {
                u("trillion", "Trillion", "T", 1e12)
                u("quadrillion", "Quadrillion", "Q", 1e15)
                u("avogadro", "Avogadro's number", "Nₐ", 6.02214076e23)
                u("googol", "Googol", "googol", 1e100)
            }
        },
    )
}
