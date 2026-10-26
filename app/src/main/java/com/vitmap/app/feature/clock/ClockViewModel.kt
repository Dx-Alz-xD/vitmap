package com.vitmap.app.feature.clock

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vitmap.app.vitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.ZoneId
import java.util.UUID

@Serializable
data class ClockEntry(
    val id: String = UUID.randomUUID().toString().take(8),
    val zone: String,
    val label: String,
    val country: String,
    val lat: Double,
    val lon: Double,
)

@Serializable
data class ClockPrefs(
    val clocks: List<ClockEntry>? = null,
    val use24h: Boolean = true,
    val showSeconds: Boolean = true,
)

data class ClockState(
    val world: World? = null,
    val clocks: List<ClockEntry> = emptyList(),
    val selectedId: String? = null,
    val selectedCountry: String? = null,
    val now: Long = System.currentTimeMillis(),
    /** Offset applied in the planner, in minutes from now. */
    val plannerOffsetMin: Int = 0,
    val prefs: ClockPrefs = ClockPrefs(),
)

class ClockViewModel(app: Application) : AndroidViewModel(app) {
    private val store = app.vitmap.store("world-clock", ClockPrefs.serializer()) { ClockPrefs() }
    private val _state = MutableStateFlow(ClockState())
    val state: StateFlow<ClockState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val world = WorldData.load(app)
            val prefs = store.load()
            val clocks = prefs.clocks ?: defaults(world)
            _state.update { it.copy(world = world, clocks = clocks, prefs = prefs) }
            if (prefs.clocks == null) store.update { it.copy(clocks = clocks) }
        }
        viewModelScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                _state.update { it.copy(now = now) }
                delay(1000 - now % 1000)
            }
        }
    }

    private fun defaults(world: World): List<ClockEntry> {
        val local = ZoneId.systemDefault().id
        val zones = listOf(local, "America/New_York", "Europe/London", "Asia/Tokyo", "Australia/Sydney").distinct()
        return zones.mapNotNull { z -> entryForZone(world, z) }
    }

    fun entryForZone(world: World, zone: String, preferredCity: City? = null): ClockEntry? {
        val city = preferredCity ?: world.cityForZone(zone)
        val info = world.zones.firstOrNull { it.id == zone }
        val label = city?.name ?: zone.substringAfterLast('/').replace('_', ' ')
        val country = city?.iso2 ?: info?.countries?.firstOrNull() ?: ""
        val lat = city?.lat ?: info?.lat ?: return null
        val lon = city?.lon ?: info?.lon ?: return null
        return ClockEntry(zone = zone, label = label, country = country, lat = lat, lon = lon)
    }

    fun add(entry: ClockEntry) {
        val list = _state.value.clocks + entry
        _state.update { it.copy(clocks = list, selectedId = entry.id, selectedCountry = entry.country) }
        save(list)
    }

    fun remove(id: String) {
        val list = _state.value.clocks.filter { it.id != id }
        _state.update { it.copy(clocks = list, selectedId = if (it.selectedId == id) null else it.selectedId) }
        save(list)
    }

    fun move(id: String, by: Int) {
        val list = _state.value.clocks.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        _state.update { it.copy(clocks = list) }
        save(list)
    }

    fun select(id: String?) {
        val c = _state.value.clocks.firstOrNull { it.id == id }
        _state.update { it.copy(selectedId = if (it.selectedId == id) null else id, selectedCountry = if (it.selectedId == id) null else c?.country) }
    }

    fun selectCountry(code: String?) = _state.update { it.copy(selectedCountry = code, selectedId = null) }

    fun setPlannerOffset(min: Int) = _state.update { it.copy(plannerOffsetMin = min) }

    fun setPrefs(change: (ClockPrefs) -> ClockPrefs) = viewModelScope.launch {
        val p = store.update(change)
        _state.update { it.copy(prefs = p) }
    }

    private fun save(list: List<ClockEntry>) = viewModelScope.launch { store.update { it.copy(clocks = list) } }
}
