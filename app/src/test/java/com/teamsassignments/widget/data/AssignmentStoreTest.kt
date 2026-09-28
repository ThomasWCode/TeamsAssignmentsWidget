package com.teamsassignments.widget.data

import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AssignmentStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = Clock.fixed(Instant.parse("2026-09-28T05:40:00Z"), ZoneOffset.UTC)
    private val file get() = File(tmp.root, "state.json")

    private val physics = Assignment(
        key = "4c958b24-de6c-429b-846b-1d02d0cbed0b",
        title = "Particle Physics Test",
        className = "12.2-PH3",
        dueAt = Instant.parse("2026-09-29T07:30:00Z").toEpochMilli(),
    )

    @Test
    fun `starts empty`() {
        val state = AssignmentStore(file, clock).state.value
        assertEquals(WidgetState(), state)
    }

    @Test
    fun `saved state survives a restart`() = runTest {
        AssignmentStore(file, clock).saveSuccess(listOf(physics))

        val reloaded = AssignmentStore(file, clock).state.value
        assertEquals(listOf(physics), reloaded.assignments)
        assertEquals(clock.millis(), reloaded.lastSuccessAt)
        assertEquals(SyncStatus.Idle, reloaded.status)
        assertTrue("12.2-PH3" in reloaded.classColors)
    }

    @Test
    fun `a failed sync keeps the previous list`() = runTest {
        val store = AssignmentStore(file, clock)
        store.saveSuccess(listOf(physics))
        store.markRunning(done = 0, total = 3)
        store.markFailed("Couldn't read the Past due list")

        val state = store.state.value
        assertEquals(listOf(physics), state.assignments)
        assertEquals(SyncStatus.Failed("Couldn't read the Past due list", clock.millis()), state.status)
    }

    @Test
    fun `a sync the user stopped keeps the previous list and reloads as stopped`() = runTest {
        val store = AssignmentStore(file, clock)
        store.saveSuccess(listOf(physics))
        store.markRunning(done = 1, total = 3)
        store.markStopped(cancelled = true)

        val state = store.state.value
        assertEquals(listOf(physics), state.assignments)
        assertEquals(SyncStatus.Stopped(cancelled = true, at = clock.millis()), state.status)
        assertEquals(state, AssignmentStore(file, clock).state.value)
    }

    @Test
    fun `progress keeps the original start time`() = runTest {
        val ticking = TickingClock(clock.instant())
        val store = AssignmentStore(file, ticking)
        store.markRunning(0, 5)
        val startedAt = ticking.millis()

        ticking.advanceSeconds(30)
        store.markRunning(2, 5)

        assertEquals(SyncStatus.Running(2, 5, startedAt), store.state.value.status)
    }

    private class TickingClock(private var now: Instant) : Clock() {
        fun advanceSeconds(seconds: Long) { now = now.plusSeconds(seconds) }
        override fun instant(): Instant = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
    }

    @Test
    fun `a sync killed with the process loads as interrupted`() = runTest {
        AssignmentStore(file, clock).markRunning(1, 4)

        val status = AssignmentStore(file, clock).state.value.status
        assertIs<SyncStatus.Failed>(status)
        assertEquals(AssignmentStore.INTERRUPTED, status.reason)
    }

    @Test
    fun `a corrupt file loads as empty`() {
        file.writeText("{ not json")
        assertEquals(WidgetState(), AssignmentStore(file, clock).state.value)
    }

    @Test
    fun `unknown fields from a newer version are ignored`() {
        file.writeText("""{"assignments":[],"futureField":42,"status":{"type":"idle"}}""")
        assertEquals(WidgetState(), AssignmentStore(file, clock).state.value)
    }

    @Test
    fun `writes leave no temp file behind`() = runTest {
        AssignmentStore(file, clock).saveSuccess(listOf(physics))
        assertTrue(file.exists())
        assertFalse(File(tmp.root, "state.json.tmp").exists())
    }
}
