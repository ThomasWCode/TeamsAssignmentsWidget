package com.teamsassignments.widget.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import com.teamsassignments.widget.automation.TeamsAutomationService
import com.teamsassignments.widget.data.AssignmentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The assignments not handed in, for apps signed with this app's key (Decrastination), so they
 * needn't read the widget: see [AssignmentsContract]. Read-only. Its two calls start what ↻ and a
 * row tap start, and nothing else.
 *
 * Every entry point needs [AssignmentsContract.PERMISSION]: the manifest guards queries, and
 * [call] checks for itself, since Android checks no permission on calls.
 */
class AssignmentsProvider : ContentProvider() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(): Boolean {
        val context = context ?: return false
        val root = Uri.Builder().scheme("content").authority(AssignmentsContract.AUTHORITY).build()
        // Every change to the list or the sync status goes through the store's state, so observers
        // hear of each one. The current state is sent too: nothing can slip by while this starts.
        scope.launch {
            AssignmentStore.get(context).state.collect { context.contentResolver.notifyChange(root, null, 0) }
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        require(selection.isNullOrEmpty() && sortOrder.isNullOrEmpty()) { "Selection and sort order aren't supported" }
        val context = requireNotNull(context)
        val state = AssignmentStore.get(context).state.value
        val table = when (Path.of(uri)) {
            Path.Assignments -> ProviderRows.assignments(state, projection)
            Path.State -> ProviderRows.state(state, TeamsAutomationService.isEnabled(context), projection)
        }
        return MatrixCursor(table.columns, table.rows.size).apply {
            table.rows.forEach(::addRow)
            setNotificationUri(context.contentResolver, uri)
        }
    }

    override fun getType(uri: Uri): String = when (Path.of(uri)) {
        Path.Assignments -> "vnd.android.cursor.dir/vnd.${AssignmentsContract.AUTHORITY}.assignment"
        Path.State -> "vnd.android.cursor.item/vnd.${AssignmentsContract.AUTHORITY}.state"
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = requireNotNull(context)
        context.enforceCallingOrSelfPermission(AssignmentsContract.PERMISSION, "Needs ${AssignmentsContract.PERMISSION}")
        require(method == AssignmentsContract.METHOD_REQUEST_SYNC || method == AssignmentsContract.METHOD_OPEN) {
            "No method \"$method\""
        }
        // The process may have just been started for this call, a moment before the system binds the service.
        val service = runBlocking { TeamsAutomationService.awaitInstance(context) }
        val control = service?.let(::ServiceControl)
        val outcome = onMainThread {
            if (method == AssignmentsContract.METHOD_OPEN) {
                ProviderCalls.open(control, arg, AssignmentStore.get(context).state.value.assignments)
            } else {
                ProviderCalls.requestSync(control)
            }
        }
        return Bundle().apply {
            putBoolean(AssignmentsContract.RESULT_STARTED, outcome.started)
            putString(AssignmentsContract.RESULT_REASON, outcome.reason)
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException(READ_ONLY)

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException(READ_ONLY)

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException(READ_ONLY)

    /** The service is only used on the main thread; calls arrive on binder threads. */
    private fun <T> onMainThread(block: () -> T): T =
        if (Looper.myLooper() == Looper.getMainLooper()) block() else runBlocking(Dispatchers.Main) { block() }

    private enum class Path {
        Assignments,
        State,
        ;

        companion object {
            fun of(uri: Uri): Path = when (uri.pathSegments) {
                listOf(AssignmentsContract.Assignments.PATH) -> Assignments
                listOf(AssignmentsContract.State.PATH) -> State
                else -> throw IllegalArgumentException("No such table: $uri")
            }
        }
    }

    private class ServiceControl(private val service: TeamsAutomationService) : SyncControl {
        override val isBusy: Boolean get() = service.isBusy
        override fun startSync(): Boolean = service.startSync()
        override fun openAssignment(key: String): Boolean = service.openAssignment(key)
    }

    private companion object {
        const val READ_ONLY = "The assignments are read-only"
    }
}
