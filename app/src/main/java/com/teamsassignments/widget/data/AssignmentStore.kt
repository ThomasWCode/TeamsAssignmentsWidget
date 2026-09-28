package com.teamsassignments.widget.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock

/**
 * The widget's state, kept in a JSON file and exposed as a [StateFlow].
 *
 * The service, the widget and the setup screen all run in the app's one process, so they share
 * the singleton from [get]. Writes go to a temp file that is then renamed over the real one, so a
 * crash mid-write can't leave a half-written file.
 */
class AssignmentStore(
    private val file: File,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<WidgetState> = _state.asStateFlow()

    suspend fun update(transform: (WidgetState) -> WidgetState): WidgetState = mutex.withLock {
        val next = transform(_state.value)
        if (next != _state.value) {
            withContext(Dispatchers.IO) { write(next) }
            _state.value = next
        }
        next
    }

    suspend fun markRunning(done: Int, total: Int) = update { state ->
        val startedAt = (state.status as? SyncStatus.Running)?.startedAt ?: clock.millis()
        state.copy(status = SyncStatus.Running(done, total, startedAt))
    }

    /** Records a failed sync. The previous list is kept. */
    suspend fun markFailed(reason: String) = update {
        it.copy(status = SyncStatus.Failed(reason, clock.millis()))
    }

    suspend fun saveSuccess(assignments: List<Assignment>) = update { state ->
        state.copy(
            assignments = assignments,
            lastSuccessAt = clock.millis(),
            status = SyncStatus.Idle,
            classColors = ClassColors.assign(state.classColors, assignments.map { it.className }),
        )
    }

    private fun load(): WidgetState {
        val loaded = runCatching {
            if (file.exists()) json.decodeFromString<WidgetState>(file.readText()) else null
        }.getOrNull() ?: WidgetState()
        // A sync can't outlive the process, so a saved Running status means it was killed mid-sync.
        return if (loaded.status is SyncStatus.Running) {
            loaded.copy(status = SyncStatus.Failed(INTERRUPTED, clock.millis()))
        } else {
            loaded
        }
    }

    private fun write(state: WidgetState) {
        val dir = requireNotNull(file.absoluteFile.parentFile) { "State file needs a parent directory" }
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(WidgetState.serializer(), state))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val FILE_NAME = "widget_state.json"
        const val INTERRUPTED = "Sync was interrupted"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        @Volatile
        private var instance: AssignmentStore? = null

        fun get(context: Context): AssignmentStore = instance ?: synchronized(this) {
            instance ?: AssignmentStore(File(context.applicationContext.filesDir, FILE_NAME)).also { instance = it }
        }
    }
}
