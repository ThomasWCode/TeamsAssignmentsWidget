package com.teamsassignments.widget.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Clock

@Serializable
data class LogEntry(val at: Long, val message: String)

/**
 * The most recent automation steps, shown in the setup app's debug section and mirrored to
 * logcat under [TAG]. Kept in memory while a run is going and saved when it ends.
 */
class SyncLog(
    private val file: File,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val capacity: Int = 100,
) {
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun add(message: String) {
        Log.i(TAG, message)
        _entries.update { (it + LogEntry(clock.millis(), message)).takeLast(capacity) }
    }

    suspend fun persist() = withContext(Dispatchers.IO) {
        runCatching { file.writeText(json.encodeToString(serializer, _entries.value)) }
    }

    private fun load(): List<LogEntry> =
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    companion object {
        const val TAG = "TeamsAutomation"
        private const val FILE_NAME = "sync_log.json"
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(LogEntry.serializer())

        @Volatile
        private var instance: SyncLog? = null

        fun get(context: Context): SyncLog = instance ?: synchronized(this) {
            instance ?: SyncLog(File(context.applicationContext.filesDir, FILE_NAME)).also { instance = it }
        }
    }
}
