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

    private val hausaufgabe = Assignment(
        key = "36274911-c6dd-490d-956d-0273df409847",
        title = "Hausaufgabe Jugendkultur Vokabeln",
        className = "German Y12 2026/27 LKP",
    )

    @Test
    fun `a handed-in assignment is dropped, and the sync time kept`() = runTest {
        val store = AssignmentStore(file, clock)
        store.saveSuccess(listOf(physics, hausaufgabe))
        store.markHandedIn(physics.key)

        val state = AssignmentStore(file, clock).state.value
        assertEquals(listOf(hausaufgabe), state.assignments)
        assertEquals(clock.millis(), state.lastSuccessAt)
        assertEquals(SyncStatus.Idle, state.status)
    }

    @Test
    fun `what's seen in Teams is saved, but never over a running sync`() = runTest {
        val store = AssignmentStore(file, clock)
        store.saveSuccess(listOf(physics))
        store.markFailed("Couldn't read the Past due list")

        store.applyObserved { saved, _ -> AssignmentStore.Observed(saved + hausaufgabe) }
        with(store.state.value) {
            assertEquals(listOf(physics, hausaufgabe), assignments)
            assertTrue("German Y12 2026/27 LKP" in classColors)
            // Seeing part of Teams isn't a sync: the last sync's time and outcome stand.
            assertEquals(clock.millis(), lastSuccessAt)
            assertIs<SyncStatus.Failed>(status)
        }

        store.markRunning(0, 3)
        store.applyObserved { _, _ -> AssignmentStore.Observed(emptyList()) }
        assertEquals(listOf(physics, hausaufgabe), store.state.value.assignments)
    }

    private class SettableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
    }

    @Test
    fun `a hand-in is remembered for twelve hours, across a restart`() = runTest {
        // Codex review: kept in memory only, it was forgotten whenever the service restarted.
        val time = SettableClock(clock.instant())
        AssignmentStore(file, time).apply {
            saveSuccess(listOf(physics, hausaufgabe))
            markHandedIn(physics.key)
        }
        val restarted = AssignmentStore(file, time)
        assertEquals(setOf(physics.key), restarted.recentlyHandedIn())
        time.now = time.now.plusSeconds(12 * 3_600L)
        assertEquals(emptySet(), restarted.recentlyHandedIn())
    }

    @Test
    fun `remembers work newly seen handed in, even with the list unchanged`() = runTest {
        // Codex review: work already off the list (taken as handed in, say), then seen on Completed.
        val time = SettableClock(clock.instant())
        val store = AssignmentStore(file, time)
        store.saveSuccess(listOf(physics))
        suspend fun seenDone() = store.applyObserved { saved, _ ->
            AssignmentStore.Observed(saved, handedIn = listOf(hausaufgabe.key))
        }
        seenDone()
        assertEquals(listOf(physics), store.state.value.assignments)
        assertEquals(setOf(hausaufgabe.key), AssignmentStore(file, time).recentlyHandedIn())
        // Seen again, it keeps its first time, so it still goes 12 hours after that.
        time.now = time.now.plusSeconds(6 * 3_600L)
        seenDone()
        time.now = time.now.plusSeconds(6 * 3_600L)
        assertEquals(emptySet(), store.recentlyHandedIn())
    }

    @Test
    fun `work only taken as handed in isn't remembered`() = runTest {
        val store = AssignmentStore(file, clock)
        store.saveSuccess(listOf(physics, hausaufgabe))
        store.markHandedIn(physics.key, remembered = false)
        assertEquals(listOf(hausaufgabe), store.state.value.assignments)
        assertEquals(emptySet(), store.recentlyHandedIn())
        assertTrue(store.state.value.handedInWork.isEmpty())
    }

    @Test
    fun `reading along remembers what it saw handed in, and is told what was`() = runTest {
        val store = AssignmentStore(file, clock)
        store.saveSuccess(listOf(physics, hausaufgabe))
        store.markHandedIn(hausaufgabe.key)
        var told = emptySet<String>()
        store.applyObserved { saved, handedInLately ->
            told = handedInLately.keys
            AssignmentStore.Observed(emptyList(), handedIn = saved.map { it.key })
        }
        assertEquals(setOf(hausaufgabe.key), told)
        assertEquals(setOf(physics.key, hausaufgabe.key), store.recentlyHandedIn())
    }

    @Test
    fun `keeps the assignment behind a hand-in, and forgets both once it's back on the list`() = runTest {
        // On the phone on 1 Oct: a hand-in undone in Teams came back without its Teams id, since
        // only the id had been kept, and the assignment's own screen doesn't show one.
        AssignmentStore(file, clock).apply {
            saveSuccess(listOf(physics, hausaufgabe))
            markHandedIn(physics.key)
            // One seen handed in while reading along is kept the same way; one never on the list can't be.
            applyObserved { saved, _ -> AssignmentStore.Observed(saved - hausaufgabe, handedIn = listOf(hausaufgabe.key, "never-listed")) }
        }

        val restarted = AssignmentStore(file, clock)
        var told = emptyMap<String, Assignment?>()
        restarted.applyObserved { saved, handedInLately ->
            told = handedInLately
            // Its own screen showed it open again: reading along puts it back.
            AssignmentStore.Observed(saved + physics)
        }
        assertEquals(mapOf(physics.key to physics, hausaufgabe.key to hausaufgabe, "never-listed" to null), told)
        with(restarted.state.value) {
            assertEquals(listOf(physics), assignments)
            assertEquals(setOf(hausaufgabe.key, "never-listed"), handedIn.keys)
            assertEquals(listOf(hausaufgabe), handedInWork)
        }
    }

    @Test
    fun `a sync that finds the work open again forgets its hand-in`() = runTest {
        // Codex review: left remembered, a hand-in after the sync kept the first one's time, and
        // a list Teams hadn't refreshed could add the work back once that ran out.
        val time = SettableClock(clock.instant())
        val store = AssignmentStore(file, time)
        store.saveSuccess(listOf(physics, hausaufgabe))
        store.markHandedIn(physics.key)

        // Undone in Teams, then synced: it's listed again.
        store.saveSuccess(listOf(physics, hausaufgabe))
        with(store.state.value) {
            assertTrue(handedIn.isEmpty())
            assertTrue(handedInWork.isEmpty())
        }

        // Handed in again eleven hours on, in Teams this time: remembered from then, not from before.
        time.now = time.now.plusSeconds(11 * 3_600L)
        store.applyObserved { saved, _ -> AssignmentStore.Observed(saved - physics, handedIn = listOf(physics.key)) }
        time.now = time.now.plusSeconds(2 * 3_600L)
        assertEquals(setOf(physics.key), store.recentlyHandedIn())
        assertEquals(listOf(physics), AssignmentStore(file, time).state.value.handedInWork)
    }

    @Test
    fun `a hand-in remembered without a Teams id is forgotten once the list holds that work under its id`() = runTest {
        // Codex review: only the same key counted, so work handed in before the list had shown it
        // stayed remembered under its stand-in key after a sync listed it under its Teams id. A
        // later hand-in was then remembered beside it, and its screen could bring back neither.
        val unkeyed = physics.copy(key = Assignment.fallbackKey(physics.className, physics.title))
        val store = AssignmentStore(file, clock)
        store.saveSuccess(listOf(unkeyed, hausaufgabe))
        store.markHandedIn(unkeyed.key)
        assertEquals(setOf(unkeyed.key), store.recentlyHandedIn())

        // Another week's work of that class and title, due at another time, isn't it.
        store.saveSuccess(listOf(hausaufgabe, physics.copy(dueAt = physics.dueAt!! + 7 * 24 * 3_600_000L)))
        assertEquals(setOf(unkeyed.key), store.recentlyHandedIn())

        store.saveSuccess(listOf(hausaufgabe, physics))
        with(store.state.value) {
            assertTrue(handedIn.isEmpty())
            assertTrue(handedInWork.isEmpty())
        }
    }

    @Test
    fun `a state file from 0_2_0 that remembers listed work as handed in is put right on loading`() = runTest {
        // Codex review: there a sync put work back on the list without forgetting its hand-in.
        // Loaded as it stood, a later hand-in kept the old time, and the work itself wasn't kept.
        val time = SettableClock(clock.instant())
        file.writeText(
            """{"assignments":[{"key":"${physics.key}","title":"${physics.title}","className":"${physics.className}"}],""" +
                """"handedIn":{"${physics.key}":${time.millis()},"${hausaufgabe.key}":${time.millis()}}}""",
        )
        val store = AssignmentStore(file, time)
        assertEquals(setOf(hausaufgabe.key), store.recentlyHandedIn())

        // Handed in eleven hours on: remembered from then, with the work behind it.
        time.now = time.now.plusSeconds(11 * 3_600L)
        store.applyObserved { saved, _ -> AssignmentStore.Observed(emptyList(), handedIn = saved.map { it.key }) }
        time.now = time.now.plusSeconds(2 * 3_600L)
        assertEquals(setOf(physics.key), store.recentlyHandedIn())
        assertEquals(listOf(physics.key), store.state.value.handedInWork.map { it.key })
    }

    @Test
    fun `a sync keeps the hand-ins of work it doesn't list`() = runTest {
        val store = AssignmentStore(file, clock)
        store.saveSuccess(listOf(physics, hausaufgabe))
        store.markHandedIn(physics.key)
        store.saveSuccess(listOf(hausaufgabe))
        assertEquals(setOf(physics.key), store.recentlyHandedIn())
        assertEquals(listOf(physics), store.state.value.handedInWork)
    }
}
