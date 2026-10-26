package com.vitmap.app.feature.forex

import android.content.Context
import com.vitmap.app.data.packs.Http
import com.vitmap.core.forex.CurrencyKind
import com.vitmap.core.forex.RateTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class CurrencyInfo(val code: String, val name: String, val kind: CurrencyKind, val symbol: String, val countries: List<String>) {
    val upper: String get() = code.uppercase()
    val search: String = "$code $name ${countries.joinToString(" ")}".lowercase()
}

@Serializable
data class CachedRates(val base: String, val date: String, val rates: Map<String, Double>, val source: String, val fetchedAt: Long)

data class HistoryPoint(val date: LocalDate, val value: Double)

enum class HistoryRange(val label: String, val days: Long) {
    WEEK("1W", 7), MONTH("1M", 30), QUARTER("3M", 91), YEAR("1Y", 365), FIVE("5Y", 1826), MAX("Max", 365L * 27),
}

/**
 * Exchange rates from several free sources (the daily currency-api mirrors, then
 * ExchangeRate-API), cached on the device so conversion keeps working offline. History comes from
 * the ECB via Frankfurter where it covers both currencies, otherwise from daily snapshots.
 */
class ForexRepository(private val context: Context, private val json: Json) {
    private val dir = File(context.filesDir, "forex").apply { mkdirs() }
    private val latestFile = File(dir, "latest.json")
    private val previousFile = File(dir, "previous.json")
    private val historyCache = HashMap<String, List<HistoryPoint>>()

    val catalog: List<CurrencyInfo> by lazy {
        context.assets.open("forex/catalog.tsv").bufferedReader().readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 3) null else CurrencyInfo(
                p[0], p[1], runCatching { CurrencyKind.valueOf(p[2]) }.getOrDefault(CurrencyKind.CRYPTO), p.getOrElse(3) { "" },
                p.getOrElse(4) { "" }.split(',').filter { it.isNotEmpty() },
            )
        }
    }

    val byCode: Map<String, CurrencyInfo> by lazy { catalog.associateBy { it.code } }

    private fun toTable(c: CachedRates) = RateTable(c.base, c.date, c.rates, c.source)

    suspend fun cached(): RateTable = withContext(Dispatchers.IO) {
        readCache(latestFile)?.let(::toTable) ?: snapshot()
    }

    suspend fun previous(): RateTable? = withContext(Dispatchers.IO) { readCache(previousFile)?.let(::toTable) }

    fun cachedAt(): Long? = readCache(latestFile)?.fetchedAt

    private fun readCache(f: File): CachedRates? = runCatching { json.decodeFromString(CachedRates.serializer(), f.readText()) }.getOrNull()

    private fun snapshot(): RateTable {
        val obj = json.parseToJsonElement(context.assets.open("forex/snapshot.json").bufferedReader().readText()).jsonObject
        val rates = obj["rates"]!!.jsonObject.mapValues { it.value.jsonPrimitive.doubleOrNull ?: 0.0 }
        return RateTable("eur", obj["date"]!!.jsonPrimitive.content, rates, "Bundled snapshot")
    }

    private fun mirrors(tag: String, base: String) = listOf(
        "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@$tag/v1/currencies/$base.min.json",
        "https://${if (tag == "latest") "latest" else tag}.currency-api.pages.dev/v1/currencies/$base.min.json",
    )

    private suspend fun fetchDaily(tag: String, base: String = "eur"): CachedRates? {
        for (url in mirrors(tag, base)) {
            val parsed = runCatching {
                val obj = json.parseToJsonElement(Http.text(url)).jsonObject
                val rates = (obj[base] as JsonObject).mapValues { it.value.jsonPrimitive.doubleOrNull ?: 0.0 }
                CachedRates(base, obj["date"]!!.jsonPrimitive.content, rates, "currency-api (daily)", System.currentTimeMillis())
            }.getOrNull()
            if (parsed != null && parsed.rates.size > 50) return parsed
        }
        return null
    }

    private suspend fun fetchErApi(): CachedRates? = runCatching {
        val obj = json.parseToJsonElement(Http.text("https://open.er-api.com/v6/latest/EUR")).jsonObject
        val rates = obj["rates"]!!.jsonObject.mapKeys { it.key.lowercase() }.mapValues { it.value.jsonPrimitive.doubleOrNull ?: 0.0 }
        val date = obj["time_last_update_utc"]?.jsonPrimitive?.content?.let { runCatching { java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate().toString() }.getOrNull() } ?: LocalDate.now().toString()
        CachedRates("eur", date, rates, "ExchangeRate-API", System.currentTimeMillis())
    }.getOrNull()

    /** Downloads fresh rates (merging both sources so every currency is covered) and caches them. */
    suspend fun refresh(): RateTable = withContext(Dispatchers.IO) {
        val daily = fetchDaily("latest")
        val er = fetchErApi()
        val merged = when {
            daily != null && er != null -> daily.copy(rates = er.rates.filterKeys { it !in daily.rates } + daily.rates, source = "currency-api + ExchangeRate-API")
            else -> daily ?: er ?: throw java.io.IOException("Couldn't reach any exchange-rate service")
        }
        val old = readCache(latestFile)
        if (old != null && old.date != merged.date) previousFile.writeText(json.encodeToString(CachedRates.serializer(), old))
        latestFile.writeText(json.encodeToString(CachedRates.serializer(), merged))
        // Day-over-day change needs the previous day's rates.
        if (readCache(previousFile)?.date == merged.date || readCache(previousFile) == null) {
            runCatching {
                val y = LocalDate.parse(merged.date).minusDays(1).toString()
                fetchDaily(y)?.let { previousFile.writeText(json.encodeToString(CachedRates.serializer(), it)) }
            }
        }
        toTable(merged)
    }

    /** Currencies the ECB reference rates (via Frankfurter) cover, since 1999. */
    private val ecb = setOf(
        "aud", "brl", "cad", "chf", "cny", "czk", "dkk", "eur", "gbp", "hkd", "huf", "idr", "ils", "inr", "isk", "jpy", "krw", "mxn",
        "myr", "nok", "nzd", "php", "pln", "ron", "sek", "sgd", "thb", "try", "usd", "zar",
    )

    suspend fun history(from: String, to: String, range: HistoryRange): List<HistoryPoint> = withContext(Dispatchers.IO) {
        val key = "$from-$to-${range.name}-${LocalDate.now()}"
        historyCache[key]?.let { return@withContext it }
        val end = LocalDate.now()
        val start = end.minusDays(range.days)
        val points = if (from in ecb && to in ecb && from != to) {
            frankfurter(from, to, start, end)
        } else {
            sampled(from, to, start.coerceAtLeast(LocalDate.of(2024, 3, 2)), end, if (range.days <= 31) range.days.toInt() else 30)
        }
        historyCache[key] = points
        points
    }

    private suspend fun frankfurter(from: String, to: String, start: LocalDate, end: LocalDate): List<HistoryPoint> {
        val url = "https://api.frankfurter.dev/v1/$start..$end?base=${from.uppercase()}&symbols=${to.uppercase()}"
        val obj = json.parseToJsonElement(Http.text(url)).jsonObject
        val rates = obj["rates"]!!.jsonObject
        val all = rates.mapNotNull { (d, v) -> v.jsonObject[to.uppercase()]?.jsonPrimitive?.doubleOrNull?.let { HistoryPoint(LocalDate.parse(d), it) } }.sortedBy { it.date }
        // Long ranges come back weekly; keep the chart light.
        val step = maxOf(1, all.size / 400)
        return all.filterIndexed { i, _ -> i % step == 0 || i == all.lastIndex }
    }

    private suspend fun sampled(from: String, to: String, start: LocalDate, end: LocalDate, count: Int): List<HistoryPoint> = coroutineScope {
        val total = ChronoUnit.DAYS.between(start, end).coerceAtLeast(1)
        val dates = (0..count).map { start.plusDays(total * it / count) }.distinct()
        val gate = Semaphore(6)
        dates.map { d ->
            async {
                gate.withPermit {
                    val r = fetchDaily(d.toString(), "eur") ?: return@withPermit null
                    RateTable("eur", r.date, r.rates).rate(from, to)?.let { HistoryPoint(d, it) }
                }
            }
        }.awaitAll().filterNotNull()
    }
}

private fun LocalDate.coerceAtLeast(min: LocalDate) = if (isBefore(min)) min else this
