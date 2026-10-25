package com.vitmap.app.feature.convert

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vitmap.app.vitmap
import com.vitmap.core.units.CustomCategorySpec
import com.vitmap.core.units.CustomUnitSpec
import com.vitmap.core.units.UnitCatalog
import com.vitmap.core.units.UnitCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class StoredUnit(val id: String, val categoryId: String, val name: String, val symbol: String, val amount: Double, val relativeTo: String)

@Serializable
data class StoredCategory(val id: String, val name: String, val baseName: String, val baseSymbol: String)

@Serializable
data class ConvertPrefs(
    val units: List<StoredUnit> = emptyList(),
    val categories: List<StoredCategory> = emptyList(),
    /** categoryId -> "fromId|toId" */
    val lastPair: Map<String, String> = emptyMap(),
    val favorites: List<String> = emptyList(),
    val significant: Int = 10,
    val scientific: Boolean = false,
)

data class ConvertState(
    val categories: List<UnitCategory> = UnitCatalog.categories,
    val prefs: ConvertPrefs = ConvertPrefs(),
    val openCategory: String? = null,
    val fromId: String? = null,
    val toId: String? = null,
    val input: String = "1",
) {
    val category: UnitCategory? get() = categories.firstOrNull { it.id == openCategory }
}

class ConvertViewModel(app: Application) : AndroidViewModel(app) {
    private val store = app.vitmap.store("convert", ConvertPrefs.serializer()) { ConvertPrefs() }
    private val _state = MutableStateFlow(ConvertState())
    val state: StateFlow<ConvertState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val prefs = store.load()
            _state.update { it.copy(prefs = prefs, categories = build(prefs)) }
        }
    }

    private fun build(p: ConvertPrefs): List<UnitCategory> = UnitCatalog.withCustom(
        p.units.map { CustomUnitSpec(it.id, it.categoryId, it.name, it.symbol, it.amount, it.relativeTo) },
        p.categories.map { CustomCategorySpec(it.id, it.name, it.baseName, it.baseSymbol) },
    )

    private fun savePrefs(transform: (ConvertPrefs) -> ConvertPrefs) {
        val next = transform(_state.value.prefs)
        _state.update { it.copy(prefs = next, categories = build(next)) }
        viewModelScope.launch { store.set(next) }
    }

    fun open(categoryId: String?) {
        if (categoryId == null) {
            _state.update { it.copy(openCategory = null) }
            return
        }
        val s = _state.value
        val cat = s.categories.firstOrNull { it.id == categoryId } ?: return
        val saved = s.prefs.lastPair[categoryId]?.split('|')
        val from = saved?.getOrNull(0)?.takeIf { cat.unit(it) != null } ?: cat.defaultPair.first.id
        val to = saved?.getOrNull(1)?.takeIf { cat.unit(it) != null } ?: cat.defaultPair.second.id
        _state.update { it.copy(openCategory = categoryId, fromId = from, toId = to) }
    }

    /** Jumps straight to a unit found through global search. */
    fun openUnit(categoryId: String, unitId: String) {
        open(categoryId)
        setFrom(unitId)
    }

    fun setInput(text: String) = _state.update { it.copy(input = text) }

    fun setFrom(id: String) {
        _state.update { it.copy(fromId = id) }
        rememberPair()
    }

    fun setTo(id: String) {
        _state.update { it.copy(toId = id) }
        rememberPair()
    }

    fun swap() {
        _state.update { it.copy(fromId = it.toId, toId = it.fromId) }
        rememberPair()
    }

    private fun rememberPair() {
        val s = _state.value
        val cat = s.openCategory ?: return
        savePrefs { it.copy(lastPair = it.lastPair + (cat to "${s.fromId}|${s.toId}")) }
    }

    fun toggleFavorite(categoryId: String) = savePrefs {
        it.copy(favorites = if (categoryId in it.favorites) it.favorites - categoryId else it.favorites + categoryId)
    }

    fun setSignificant(n: Int) = savePrefs { it.copy(significant = n) }
    fun setScientific(on: Boolean) = savePrefs { it.copy(scientific = on) }

    fun addUnit(categoryId: String, name: String, symbol: String, amount: Double, relativeTo: String) {
        val id = "$categoryId.custom_${UUID.randomUUID().toString().take(8)}"
        savePrefs { it.copy(units = it.units + StoredUnit(id, categoryId, name.trim(), symbol.trim().ifEmpty { name.trim() }, amount, relativeTo)) }
    }

    fun deleteUnit(id: String) = savePrefs { p ->
        // Removing a unit also removes units that were defined in terms of it.
        val doomed = HashSet<String>().apply { add(id) }
        var changed = true
        while (changed) {
            changed = false
            for (u in p.units) if (u.relativeTo in doomed && doomed.add(u.id)) changed = true
        }
        p.copy(units = p.units.filter { it.id !in doomed })
    }.also {
        val s = _state.value
        val cat = s.category ?: return@also
        if (cat.unit(s.fromId ?: "") == null || cat.unit(s.toId ?: "") == null) open(cat.id)
    }

    fun addCategory(name: String, baseName: String, baseSymbol: String): String {
        val id = "custom_${UUID.randomUUID().toString().take(8)}"
        savePrefs { it.copy(categories = it.categories + StoredCategory(id, name.trim(), baseName.trim(), baseSymbol.trim().ifEmpty { baseName.trim() })) }
        return id
    }

    fun deleteCategory(id: String) {
        savePrefs { p -> p.copy(categories = p.categories.filter { it.id != id }, units = p.units.filter { it.categoryId != id }, favorites = p.favorites - id) }
        if (_state.value.openCategory == id) open(null)
    }
}
