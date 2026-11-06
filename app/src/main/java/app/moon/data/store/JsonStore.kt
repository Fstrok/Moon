package app.moon.data.store

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

/** Маленькое JSON-хранилище со StateFlow и отложенной атомарной записью. */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    default: T,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(load() ?: default)
    val state: StateFlow<T> = _state.asStateFlow()
    val value: T get() = _state.value

    private val signal = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            for (s in signal) {
                delay(400)
                write(_state.value)
            }
        }
    }

    fun update(f: (T) -> T) {
        _state.update(f)
        signal.trySend(Unit)
    }

    private fun load(): T? = runCatching {
        if (file.exists()) AppJson.decodeFromString(serializer, file.readText()) else null
    }.getOrNull()

    @Synchronized
    private fun write(v: T) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(AppJson.encodeToString(serializer, v))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}
