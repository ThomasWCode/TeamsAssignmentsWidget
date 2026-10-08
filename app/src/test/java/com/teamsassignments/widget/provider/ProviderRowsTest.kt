package com.teamsassignments.widget.provider

import com.teamsassignments.widget.data.Assignment
import com.teamsassignments.widget.data.AssignmentTab
import com.teamsassignments.widget.data.SyncStatus
import com.teamsassignments.widget.data.WidgetState
import com.teamsassignments.widget.provider.AssignmentsContract.Assignments
import com.teamsassignments.widget.provider.AssignmentsContract.State
import org.junit.Test
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProviderRowsTest {

    private val physics = Assignment(
        key = "4c958b24-de6c-429b-846b-1d02d0cbed0b",
        title = "Particle Physics Test",
        className = "12.2-PH3",
        description = "Revise chapters 1 to 3.",
        dueText = "Due tomorrow at 08:30",
        dueAt = Instant.parse("2026-09-29T07:30:00Z").toEpochMilli(),
        tab = AssignmentTab.Forthcoming,
        detailReadAt = 1_759_000_000_000,
        lastSyncedAt = 1_759_000_100_000,
    )

    /** Seen only on a list row: no instructions, no due time it could read. */
    private val german = Assignment(
        key = "h-0123456789abcdef",
        title = "Vocabulary 1.2",
        className = "12-GE",
        dueText = "Past due",
        tab = AssignmentTab.PastDue,
    )

    private val saved = WidgetState(
        assignments = listOf(physics, german),
        lastSuccessAt = 1_759_000_200_000,
    )

    private fun ProviderRows.Table.cell(row: Int, column: String): Any? = rows[row][columns.indexOf(column)]

    @Test
    fun `an assignment row carries every field under its column`() {
        val table = ProviderRows.assignments(saved, projection = null)

        assertContentEquals(Assignments.COLUMNS, table.columns)
        assertEquals(2, table.rows.size)
        assertEquals(physics.key, table.cell(0, Assignments.KEY))
        assertEquals("Particle Physics Test", table.cell(0, Assignments.TITLE))
        assertEquals("12.2-PH3", table.cell(0, Assignments.CLASS_NAME))
        assertEquals("Revise chapters 1 to 3.", table.cell(0, Assignments.DESCRIPTION))
        assertEquals("Due tomorrow at 08:30", table.cell(0, Assignments.DUE_TEXT))
        assertEquals(physics.dueAt, table.cell(0, Assignments.DUE_AT))
        assertEquals("Forthcoming", table.cell(0, Assignments.TAB))
        assertEquals(1_759_000_000_000, table.cell(0, Assignments.DETAIL_READ_AT))
        assertEquals(1_759_000_100_000, table.cell(0, Assignments.LAST_SYNCED_AT))
    }

    @Test
    fun `what isn't known yet comes through as null or empty, in the widget's order`() {
        val table = ProviderRows.assignments(saved, projection = null)

        assertEquals(german.key, table.cell(1, Assignments.KEY))
        assertEquals("", table.cell(1, Assignments.DESCRIPTION))
        assertNull(table.cell(1, Assignments.DUE_AT))
        assertNull(table.cell(1, Assignments.DETAIL_READ_AT))
        assertEquals("PastDue", table.cell(1, Assignments.TAB))
    }

    @Test
    fun `rows come in the widget's order, not the store's`() {
        val maths = physics.copy(key = "b0ccf04e", title = "binomial expansion", className = "12-FM", dueAt = physics.dueAt!! - 3_600_000)
        val statics = maths.copy(key = "d53f5f50", title = "Statics prep")
        // A sync that starts on Past due, then a look while Teams was open, can leave the store like this.
        val table = ProviderRows.assignments(WidgetState(assignments = listOf(german, physics, statics, maths)), arrayOf(Assignments.KEY))

        assertEquals(listOf(maths.key, statics.key, physics.key, german.key), table.rows.map { it.single() })
    }

    @Test
    fun `every row is as wide as its columns`() {
        assertEquals(Assignments.COLUMNS.size, ProviderRows.assignments(saved, null).rows.single { it[0] == german.key }.size)
        assertEquals(State.COLUMNS.size, ProviderRows.state(saved, syncServiceEnabled = true, projection = null).rows.single().size)
    }

    @Test
    fun `a projection picks columns in its own order`() {
        val table = ProviderRows.assignments(saved, arrayOf(Assignments.DUE_AT, Assignments.KEY))

        assertContentEquals(arrayOf(Assignments.DUE_AT, Assignments.KEY), table.columns)
        assertContentEquals(arrayOf<Any?>(physics.dueAt, physics.key), table.rows[0])
        assertContentEquals(arrayOf<Any?>(null, german.key), table.rows[1])
    }

    @Test
    fun `an unknown column is refused`() {
        assertFailsWith<IllegalArgumentException> { ProviderRows.assignments(saved, arrayOf(Assignments.KEY, "password")) }
        assertFailsWith<IllegalArgumentException> { ProviderRows.state(saved, true, arrayOf("handed_in")) }
    }

    @Test
    fun `an empty list still has its columns`() {
        val table = ProviderRows.assignments(WidgetState(), projection = null)

        assertContentEquals(Assignments.COLUMNS, table.columns)
        assertEquals(0, table.rows.size)
    }

    @Test
    fun `the state row says how fresh the list is`() {
        val table = ProviderRows.state(saved, syncServiceEnabled = true, projection = null)

        assertContentEquals(State.COLUMNS, table.columns)
        assertEquals(1_759_000_200_000, table.cell(0, State.LAST_SUCCESS_AT))
        assertEquals("idle", table.cell(0, State.STATUS))
        assertNull(table.cell(0, State.STATUS_MESSAGE))
        assertNull(table.cell(0, State.STATUS_AT))
        assertEquals(2, table.cell(0, State.ASSIGNMENT_COUNT))
        assertEquals(1, table.cell(0, State.SYNC_SERVICE_ENABLED))
    }

    @Test
    fun `each sync status has its name, message and time`() {
        fun row(status: SyncStatus) = ProviderRows.state(saved.copy(status = status), false, null)

        row(SyncStatus.Running(done = 2, total = 7, startedAt = 10L)).let {
            assertEquals("running", it.cell(0, State.STATUS))
            assertNull(it.cell(0, State.STATUS_MESSAGE))
            assertEquals(10L, it.cell(0, State.STATUS_AT))
        }
        row(SyncStatus.Failed("Couldn't read the Past due list", at = 20L)).let {
            assertEquals("failed", it.cell(0, State.STATUS))
            assertEquals("Couldn't read the Past due list", it.cell(0, State.STATUS_MESSAGE))
            assertEquals(20L, it.cell(0, State.STATUS_AT))
        }
        row(SyncStatus.Stopped(cancelled = true, at = 30L)).let {
            assertEquals("stopped", it.cell(0, State.STATUS))
            assertEquals("Sync cancelled", it.cell(0, State.STATUS_MESSAGE))
            assertEquals(30L, it.cell(0, State.STATUS_AT))
            assertEquals(0, it.cell(0, State.SYNC_SERVICE_ENABLED))
        }
    }

    @Test
    fun `no sync yet leaves the last success null`() {
        val table = ProviderRows.state(WidgetState(), syncServiceEnabled = false, projection = arrayOf(State.LAST_SUCCESS_AT, State.ASSIGNMENT_COUNT))

        assertContentEquals(arrayOf<Any?>(null, 0), table.rows.single())
    }
}
