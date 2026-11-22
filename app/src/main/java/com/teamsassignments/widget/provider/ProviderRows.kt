package com.teamsassignments.widget.provider

import com.teamsassignments.widget.data.SyncStatus
import com.teamsassignments.widget.data.WidgetState
import com.teamsassignments.widget.data.widgetOrder
import com.teamsassignments.widget.provider.AssignmentsContract.Assignments
import com.teamsassignments.widget.provider.AssignmentsContract.State

/**
 * The provider's rows, as plain arrays in [Assignments.COLUMNS] and [State.COLUMNS] order. Kept
 * apart from the provider so they can be tested without Android's cursors.
 */
internal object ProviderRows {

    /** A projection and its rows, ready for a cursor. */
    class Table(val columns: Array<String>, val rows: List<Array<Any?>>)

    /** In the widget's order, not the store's, which follows the order Teams' tabs were read in. */
    fun assignments(state: WidgetState, projection: Array<String>?): Table =
        Table(
            Assignments.COLUMNS,
            state.assignments.sortedWith(widgetOrder).map {
                // By the key it was first given: one that took its Teams id later stays the same work.
                arrayOf(
                    it.formerKey ?: it.key, it.title, it.className, it.description, it.dueText, it.dueAt, it.tab.name, it.detailReadAt, it.lastSyncedAt,
                    it.attachments.joinToString("\n"), it.links.joinToString("\n"),
                )
            },
        ).project(projection)

    fun state(state: WidgetState, syncServiceEnabled: Boolean, projection: Array<String>?): Table {
        val (status, message, at) = when (val s = state.status) {
            SyncStatus.Idle -> Triple("idle", null, null)
            is SyncStatus.Running -> Triple("running", null, s.startedAt)
            is SyncStatus.Failed -> Triple("failed", s.reason, s.at)
            is SyncStatus.Stopped -> Triple("stopped", s.summary, s.at)
        }
        val row = arrayOf<Any?>(
            state.lastSuccessAt, status, message, at, state.assignments.size, if (syncServiceEnabled) 1 else 0, state.listsSeenAt, state.noticedAt,
        )
        return Table(State.COLUMNS, listOf(row)).project(projection)
    }

    /** Which of the two tables a change of state alters, by their rows: what's announced, and on which URI. */
    data class Changed(val assignments: Boolean, val state: Boolean)

    /**
     * Which tables' rows differ between [before] and [after]. What they don't show (class colours,
     * work handed in lately) changes neither. Whether the sync service is on isn't part of the
     * state, so it's left out: observers aren't told of it ([State.SYNC_SERVICE_ENABLED]).
     */
    fun changed(before: WidgetState, after: WidgetState): Changed = Changed(
        assignments = !rowsEqual(assignments(before, null), assignments(after, null)),
        state = !rowsEqual(state(before, false, null), state(after, false, null)),
    )

    private fun rowsEqual(a: Table, b: Table): Boolean =
        a.rows.size == b.rows.size && a.rows.indices.all { a.rows[it].contentEquals(b.rows[it]) }

    /** Just [projection]'s columns, in its order; all of them when it's null. Unknown names are refused. */
    private fun Table.project(projection: Array<String>?): Table {
        if (projection == null) return this
        val indexes = projection.map { name ->
            columns.indexOf(name).also { require(it >= 0) { "No column \"$name\"; there are ${columns.joinToString()}" } }
        }
        return Table(projection.copyOf(), rows.map { row -> Array(indexes.size) { row[indexes[it]] } })
    }
}
