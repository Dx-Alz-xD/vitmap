package com.vitmap.app.feature.forex

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vitmap.app.vitmap
import com.vitmap.core.forex.RateTable
import com.vitmap.core.math.Expression
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.Currency
import java.util.Locale

@Serializable
data class ForexPrefs(
    val from: String = "",
    val to: String = "",
    val amount: String = "100",
    val favorites: List<String> = emptyList(),
    val recent: List<String> = emptyList(),
    val range: String = HistoryRange.MONTH.name,
)

data class ForexState(
    val table: RateTable? = null,
    val previous: RateTable? = null,
    val from: String = "usd",
    val to: String = "eur",
    val amountText: String = "100",
    val refreshing: Boolean = false,
    val error: String? = null,
    val fetchedAt: Long? = null,
    val history: List<HistoryPoint> = emptyList(),
    val historyLoading: Boolean = false,
    val historyError: String? = null,
    val range: HistoryRange = HistoryRange.MONTH,
    val prefs: ForexPrefs = ForexPrefs(),
) {
    val amount: Double? get() = Expression.evaluate(amountText.replace(",", "")) ?: amountText.replace(",", "").toDoubleOrNull()
    val result: Double? get() = amount?.let { a -> table?.convert(a, from, to) }
    val rate: Double? get() = table?.rate(from, to)

    /** Day-over-day change of [code] against [from], in percent. */
    fun change(code: String): Double? {
        val now = table?.rate(from, code) ?: return null
        val before = previous?.rate(from, code) ?: return null
        if (previous?.date == table?.date) return null
        return (now / before - 1) * 100
    }
}

class ForexViewModel(app: Application) : AndroidViewModel(app) {
    val repo = ForexRepository(app, app.vitmap.json)
    private val store = app.vitmap.store("forex", ForexPrefs.serializer()) { ForexPrefs() }
    private val _state = MutableStateFlow(ForexState())
    val state: StateFlow<ForexState> = _state.asStateFlow()
    private var historyJob: Job? = null

    init {
        viewModelScope.launch {
            val p = store.load()
            val localCode = runCatching { Currency.getInstance(Locale.getDefault()).currencyCode.lowercase() }.getOrNull() ?: "usd"
            val from = p.from.ifEmpty { "usd" }
            val to = p.to.ifEmpty { if (localCode == "usd") "eur" else localCode }
            val favorites = p.favorites.ifEmpty { listOf("eur", "usd", "gbp", "jpy", "inr", "cny", "aud", "cad", "chf", "btc", "xau").filter { it != from } }
            _state.update {
                it.copy(
                    table = repo.cached(), previous = repo.previous(), fetchedAt = repo.cachedAt(), from = from, to = to, amountText = p.amount,
                    range = runCatching { HistoryRange.valueOf(p.range) }.getOrDefault(HistoryRange.MONTH), prefs = p.copy(favorites = favorites),
                )
            }
            if (p.favorites.isEmpty()) store.update { it.copy(favorites = favorites) }
            val stale = (repo.cachedAt() ?: 0L) < System.currentTimeMillis() - 3 * 3600_000L
            if (stale) refresh()
            loadHistory()
        }
    }

    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            try {
                val t = repo.refresh()
                _state.update { it.copy(table = t, previous = repo.previous(), fetchedAt = repo.cachedAt(), refreshing = false) }
            } catch (e: Exception) {
                _state.update { it.copy(refreshing = false, error = "Offline: showing rates from ${it.table?.date ?: "the bundled snapshot"}") }
            }
        }
    }

    fun setAmount(text: String) {
        _state.update { it.copy(amountText = text) }
        save { it.copy(amount = text) }
    }

    fun setFrom(code: String) {
        val s = _state.value
        if (code == s.to) swap() else {
            _state.update { it.copy(from = code) }
            save { it.copy(from = code, recent = (listOf(code) + it.recent - code).take(8)) }
            loadHistory()
        }
    }

    fun setTo(code: String) {
        val s = _state.value
        if (code == s.from) swap() else {
            _state.update { it.copy(to = code) }
            save { it.copy(to = code, recent = (listOf(code) + it.recent - code).take(8)) }
            loadHistory()
        }
    }

    fun swap() {
        val s = _state.value
        val converted = s.result
        _state.update { it.copy(from = s.to, to = s.from, amountText = converted?.let { r -> trimmed(r) } ?: it.amountText) }
        save { it.copy(from = s.to, to = s.from) }
        loadHistory()
    }

    private fun trimmed(v: Double): String {
        val s = "%.6f".format(Locale.US, v).trimEnd('0').trimEnd('.')
        return if (s.isEmpty()) "0" else s
    }

    fun toggleFavorite(code: String) {
        save { p -> p.copy(favorites = if (code in p.favorites) p.favorites - code else p.favorites + code) }
    }

    fun moveFavorite(code: String, by: Int) = save { p ->
        val l = p.favorites.toMutableList()
        val i = l.indexOf(code)
        val j = i + by
        if (i >= 0 && j in l.indices) l.add(j, l.removeAt(i))
        p.copy(favorites = l)
    }

    fun setRange(r: HistoryRange) {
        _state.update { it.copy(range = r) }
        save { it.copy(range = r.name) }
        loadHistory()
    }

    fun loadHistory() {
        historyJob?.cancel()
        val s = _state.value
        _state.update { it.copy(historyLoading = true, historyError = null) }
        historyJob = viewModelScope.launch {
            try {
                val h = repo.history(s.from, s.to, s.range)
                _state.update { it.copy(history = h, historyLoading = false, historyError = if (h.size < 2) "No history for this pair" else null) }
            } catch (e: Exception) {
                _state.update { it.copy(history = emptyList(), historyLoading = false, historyError = "History needs an internet connection") }
            }
        }
    }

    private fun save(change: (ForexPrefs) -> ForexPrefs) {
        viewModelScope.launch {
            val p = store.update(change)
            _state.update { it.copy(prefs = p) }
        }
    }
}
