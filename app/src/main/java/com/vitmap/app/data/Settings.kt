package com.vitmap.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vitmap.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val haptics: Boolean = true,
    val recentTools: List<String> = emptyList(),
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val haptics = booleanPreferencesKey("haptics")
        val recents = stringPreferencesKey("recent_tools")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.dynamic] ?: false,
            haptics = p[Keys.haptics] ?: true,
            recentTools = p[Keys.recents]?.split(',')?.filter { it.isNotBlank() } ?: emptyList(),
        )
    }

    suspend fun setTheme(mode: ThemeMode) = context.dataStore.edit { it[Keys.theme] = mode.name }
    suspend fun setDynamicColor(on: Boolean) = context.dataStore.edit { it[Keys.dynamic] = on }
    suspend fun setHaptics(on: Boolean) = context.dataStore.edit { it[Keys.haptics] = on }

    suspend fun noteToolOpened(id: String) = context.dataStore.edit { p ->
        val list = p[Keys.recents]?.split(',')?.filter { it.isNotBlank() && it != id } ?: emptyList()
        p[Keys.recents] = (listOf(id) + list).take(6).joinToString(",")
    }

    // Small typed preferences used by individual tools.
    fun string(key: String, default: String): Flow<String> = context.dataStore.data.map { it[stringPreferencesKey(key)] ?: default }
    fun int(key: String, default: Int): Flow<Int> = context.dataStore.data.map { it[intPreferencesKey(key)] ?: default }
    fun bool(key: String, default: Boolean): Flow<Boolean> = context.dataStore.data.map { it[booleanPreferencesKey(key)] ?: default }
    suspend fun putString(key: String, value: String) = context.dataStore.edit { it[stringPreferencesKey(key)] = value }
    suspend fun putInt(key: String, value: Int) = context.dataStore.edit { it[intPreferencesKey(key)] = value }
    suspend fun putBool(key: String, value: Boolean) = context.dataStore.edit { it[booleanPreferencesKey(key)] = value }
}
