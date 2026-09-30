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

    /** Records a sync the user stopped. The previous list is kept. */
    suspend fun markStopped(cancelled: Boolean) = update {
        it.copy(status = SyncStatus.Stopped(cancelled, clock.millis()))
    }

    suspend fun saveSuccess(assignments: List<Assignment>) = update { state ->
        state.copy(
            assignments = assignments,
            lastSuccessAt = clock.millis(),
            status = SyncStatus.Idle,
            classColors = ClassColors.assign(state.classColors, assignments.map { it.className }),
        )
    }

    /**
     * Drops an assignment that has just been handed in, or is taken as handed in. A real hand-in is
     * [remembered] (see [recentlyHandedIn]); one only presumed isn't, so that a list still showing
     * it can bring it back. The sync time and status are kept.
     */
    suspend fun markHandedIn(key: String, remembered: Boolean = true) = update { state ->
        val now = clock.millis()
        state.copy(
            assignments = state.assignments.filterNot { it.key == key },
            handedIn = recent(state.handedIn, now) + if (remembered) mapOf(key to now) else emptyMap(),
        )
    }

    /** The keys handed in over the last [HANDED_IN_MEMORY_MS] (see [WidgetState.handedIn]). */
    fun recentlyHandedIn(): Set<String> = recent(state.value.handedIn, clock.millis()).keys

    /** What reading along made of the list, and the keys it saw handed in, to remember. */
    data class Observed(val assignments: List<Assignment>, val handedIn: Collection<String> = emptyList())

    /**
     * Applies what was seen in Teams outside a sync (see TeamsObserver). [transform] gets the list
     * and the keys handed in lately. A running sync owns the list, so nothing changes while one is;
     * the sync time and status are kept either way.
     */
    suspend fun applyObserved(transform: (List<Assignment>, Set<String>) -> Observed) = update { state ->
        if (state.status is SyncStatus.Running) return@update state
        val now = clock.millis()
        val observed = transform(state.assignments, recent(state.handedIn, now).keys)
        if (observed.assignments == state.assignments) {
            state
        } else {
            state.copy(
                assignments = observed.assignments,
                classColors = ClassColors.assign(state.classColors, observed.assignments.map { it.className }),
                handedIn = recent(state.handedIn, now) + observed.handedIn.associateWith { now },
            )
        }
    }

    private fun recent(handedIn: Map<String, Long>, now: Long) = handedIn.filterValues { now - it in 0 until HANDED_IN_MEMORY_MS }

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

        /** How long work handed in isn't added back from a list that still shows it as open. */
        const val HANDED_IN_MEMORY_MS = 12 * 60 * 60_000L

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
