package com.vitmap.app

import android.app.Application
import android.content.Context
import com.vitmap.app.data.JsonFileStore
import com.vitmap.app.data.SettingsRepository
import com.vitmap.app.data.packs.PackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

class VitmapApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: SettingsRepository by lazy { SettingsRepository(this) }

    /** Optional downloads (models, dictionaries, runtimes) shared by every tool. */
    val packs: PackManager by lazy { PackManager(this, appScope) }

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val stores = HashMap<String, JsonFileStore<*>>()

    /** One store instance per name, shared by every screen that uses it. */
    @Suppress("UNCHECKED_CAST")
    fun <T> store(name: String, serializer: KSerializer<T>, default: () -> T): JsonFileStore<T> = synchronized(stores) {
        stores.getOrPut(name) { JsonFileStore(File(filesDir, "stores/$name.json"), serializer, json, default) } as JsonFileStore<T>
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: VitmapApp
            private set
    }
}

val Context.vitmap: VitmapApp get() = applicationContext as VitmapApp
