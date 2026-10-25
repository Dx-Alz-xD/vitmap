package com.vitmap.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A tiny persistent value backed by a JSON file. Reads lazily, writes atomically, and exposes the
 * current value as a StateFlow so Compose screens can observe it.
 */
class JsonFileStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val json: Json,
    private val default: () -> T,
) {
    private val mutex = Mutex()
    private val state = MutableStateFlow<T?>(null)

    /** Null until the first [load] completes. */
    val flow: StateFlow<T?> = state.asStateFlow()

    suspend fun load(): T = mutex.withLock { loadLocked() }

    private suspend fun loadLocked(): T {
        state.value?.let { return it }
        val value = withContext(Dispatchers.IO) {
            runCatching { if (file.exists()) json.decodeFromString(serializer, file.readText()) else null }.getOrNull()
        } ?: default()
        state.value = value
        return value
    }

    suspend fun update(transform: (T) -> T): T = mutex.withLock {
        val next = transform(loadLocked())
        state.value = next
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(serializer, next))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
        next
    }

    suspend fun set(value: T) {
        update { value }
    }
}
