package com.teamsassignments.widget.automation

import com.teamsassignments.widget.automation.FakeTeamsDevice.Screen
import com.teamsassignments.widget.automation.TeamsSelectors.Tab
import com.teamsassignments.widget.data.Assignment
import com.teamsassignments.widget.data.AssignmentTab
import com.teamsassignments.widget.data.DueDateParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SyncStateMachineTest {

    /** When the fixtures were captured: Monday 28 Sept 2026, 06:40 BST. */
    private val clock = Clock.fixed(Instant.parse("2026-09-28T05:40:00Z"), ZoneId.of("Europe/London"))
    private val parser = DueDateParser(clock)

    private val log = mutableListOf<String>()
    private val progress = mutableListOf<Pair<Int, Int>>()

    private fun TestScope.machine(
        device: FakeTeamsDevice,
        config: AutomationConfig = AutomationConfig(),
        wallClock: () -> Long = { clock.millis() },
        isCancelled: () -> Boolean = { false },
    ) = SyncStateMachine(
        device = device,
        parser = parser,
        wallClock = wallClock,
        onProgress = { done, total -> progress += done to total },
        config = config,
        now = { testScheduler.currentTime },
        log = { log += it },
        isCancelled = isCancelled,
    )

    private fun millis(utc: String) = Instant.parse(utc).toEpochMilli()

    @Test
    fun `reads every assignment that isn't handed in`() = runTest {
        val device = FakeTeamsDevice()
        val result = machine(device).run(previous = emptyList())

        assertEquals(10, result.size)
        val byId = result.associateBy { it.key.take(8) }

        with(byId.getValue("36274911")) {
            assertEquals("36274911-c6dd-490d-956d-0273df409847", key)
            assertEquals("Hausaufgabe Jugendkultur Vokabeln", title)
            assertEquals("German Y12 2026/27 LKP", className)
            assertEquals("Learn new vocabulary p 67, 3.3", description)
            assertEquals("Due today at 08:00", dueText)
            assertEquals(millis("2026-09-28T07:00:00Z"), dueAt)
            assertEquals(AssignmentTab.Forthcoming, tab)
            assertEquals(clock.millis(), detailReadAt)
        }
        with(byId.getValue("b0ccf04e")) {
            assertEquals("Physics Skills & Stretch 12.2-PH3", className)
            assertEquals(millis("2026-09-29T22:59:00Z"), dueAt)
        }
        with(byId.getValue("66fcdab0")) {
            assertEquals("Ms Cloud year 12 2026/27", className)
            assertEquals(millis("2026-10-01T07:30:00Z"), dueAt)
        }
        with(byId.getValue("d53f5f50")) {
            assertEquals(AssignmentTab.PastDue, tab)
            assertEquals(millis("2026-09-17T07:30:00Z"), dueAt)
        }

        assertEquals(0 to 10, progress.first())
        assertEquals(10 to 10, progress.last())
        assertEquals(10, device.cardClicks)
        assertIs<Screen.List>(device.screen)
    }

    @Test
    fun `only ever clicks tabs and assignment cards`() = runTest {
        val device = FakeTeamsDevice()
        machine(device).run(emptyList())
        assertTrue(device.clicked.isNotEmpty())
        assertTrue(
            device.clicked.all { id -> Tab.entries.any { it.viewId == id } || TeamsSelectors.CARD_ID.matches(id) },
            device.clicked.toString(),
        )
        assertTrue(Tab.Completed.viewId !in device.clicked)
    }

    @Test
    fun `reuses recent details instead of reopening every card`() = runTest {
        val first = machine(FakeTeamsDevice()).run(emptyList())

        val device = FakeTeamsDevice()
        val second = machine(device).run(previous = first)

        assertEquals(0, device.cardClicks)
        assertEquals(first.associate { it.key to it.description }, second.associate { it.key to it.description })
        assertEquals(first.associate { it.key to it.className }, second.associate { it.key to it.className })
    }

    @Test
    fun `a full sync rereads every card`() = runTest {
        val first = machine(FakeTeamsDevice()).run(emptyList())
        val device = FakeTeamsDevice()
        machine(device).run(previous = first, full = true)
        assertEquals(10, device.cardClicks)
    }

    @Test
    fun `details older than the reuse window are reread`() = runTest {
        val stale = machine(FakeTeamsDevice()).run(emptyList())
            .map { it.copy(detailReadAt = clock.millis() - TimeUnit.DAYS.toMillis(4)) }
        val device = FakeTeamsDevice()
        machine(device).run(previous = stale)
        assertEquals(10, device.cardClicks)
    }

    @Test
    fun `a changed row is reread`() = runTest {
        val first = machine(FakeTeamsDevice()).run(emptyList())
        val edited = first.map { if (it.key.startsWith("36274911")) it.copy(dueAt = 0) else it }
        val device = FakeTeamsDevice()
        machine(device).run(previous = edited)
        assertEquals(listOf("36274911-c6dd-490d-956d-0273df409847"), device.clicked.filter { TeamsSelectors.CARD_ID.matches(it) })
    }

    @Test
    fun `falls back to a tap when the click is swallowed`() = runTest {
        val device = FakeTeamsDevice().apply { swallowClicks["36274911-c6dd-490d-956d-0273df409847"] = 1 }
        val result = machine(device).run(emptyList())

        assertEquals(1, device.tapped.size)
        val (x, y) = device.tapped.single()
        assertTrue(x in 213..942 && y in 868..1009, "tap ($x, $y) should land on the card title")
        assertEquals("Learn new vocabulary p 67, 3.3", result.single { it.key.startsWith("36274911") }.description)
    }

    @Test
    fun `retries a card once before giving up on its details`() = runTest {
        val id = "839994fb-a6a8-4f11-9ff6-bd319473a93a"
        val device = FakeTeamsDevice().apply {
            swallowClicks[id] = 1
            tapsOpenCards = false
        }
        val result = machine(device).run(emptyList())
        assertEquals(2, device.clicked.count { it == id })
        assertNotNull(result.single { it.key == id }.detailReadAt)
        assertTrue(log.any { "attempt 1 of 2" in it })
    }

    @Test
    fun `keeps the list data when a card never opens`() = runTest {
        val id = "839994fb-a6a8-4f11-9ff6-bd319473a93a"
        val device = FakeTeamsDevice().apply {
            swallowClicks[id] = Int.MAX_VALUE
            tapsOpenCards = false
        }
        val result = machine(device).run(emptyList())

        assertEquals(10, result.size)
        with(result.single { it.key == id }) {
            assertEquals("w/sheet - prepositions, cases, adjective endings", title)
            assertEquals("12.1 German 2026-27", className)
            assertEquals("", description)
            assertNull(detailReadAt)
            assertEquals(millis("2026-09-29T08:00:00Z"), dueAt)
            assertEquals("29 Sept · Due at 09:00", dueText)
        }
    }

    @Test
    fun `keeps an earlier description when a card won't open this time`() = runTest {
        val id = "839994fb-a6a8-4f11-9ff6-bd319473a93a"
        val earlier = Assignment(key = id, title = "old", className = "old", description = "Earlier instructions")
        val device = FakeTeamsDevice().apply {
            swallowClicks[id] = Int.MAX_VALUE
            tapsOpenCards = false
        }
        val result = machine(device).run(previous = listOf(earlier))
        assertEquals("Earlier instructions", result.single { it.key == id }.description)
    }

    @Test
    fun `stops when the user leaves Teams`() = runTest {
        val device = FakeTeamsDevice().apply {
            afterAction = { if (it.cardClicks == 3) it.screen = Screen.OtherApp }
        }
        val abort = assertFailsWith<SyncAbort> { machine(device).run(emptyList()) }
        assertEquals("Teams was closed", abort.reason)
    }

    @Test
    fun `stops when cancelled`() = runTest {
        val device = FakeTeamsDevice()
        val abort = assertFailsWith<SyncAbort> {
            machine(device, isCancelled = { device.cardClicks >= 2 }).run(emptyList())
        }
        assertEquals("Cancelled", abort.reason)
        assertEquals(2, device.cardClicks)
    }

    @Test
    fun `stops when the whole run takes too long`() = runTest {
        val abort = assertFailsWith<SyncAbort> {
            machine(FakeTeamsDevice(), config = AutomationConfig(globalTimeoutMs = 8_000)).run(emptyList())
        }
        assertEquals("Took too long", abort.reason)
    }

    @Test
    fun `fails cleanly when Teams never shows the list`() = runTest {
        val device = FakeTeamsDevice().apply { launchLandsOn = Screen.Home }
        val abort = assertFailsWith<SyncAbort> { machine(device).run(emptyList()) }
        assertEquals("Couldn't open Teams Assignments", abort.reason)
    }

    @Test
    fun `fails cleanly when Teams isn't installed`() = runTest {
        val device = FakeTeamsDevice().apply { teamsInstalled = false }
        val abort = assertFailsWith<SyncAbort> { machine(device).run(emptyList()) }
        assertEquals("Teams isn't installed", abort.reason)
    }

    @Test
    fun `backs out when Teams resumes on a detail screen`() = runTest {
        val device = FakeTeamsDevice().apply {
            launchLandsOn = Screen.Detail("36274911-c6dd-490d-956d-0273df409847", Tab.Forthcoming)
        }
        val result = machine(device).run(emptyList())
        assertEquals(10, result.size)
    }

    @Test
    fun `a card that collapsed before the sync still gets its details`() = runTest {
        val device = FakeTeamsDevice(
            lists = mapOf(
                Tab.Forthcoming to "list_forthcoming_after_back",
                Tab.PastDue to "list_past_due_after_back",
                Tab.Completed to "list_completed",
            ),
        )
        val result = machine(device).run(emptyList())
        assertEquals(10, result.size)
        with(result.single { it.key.startsWith("4c958b24") }) {
            assertEquals("Particle Physics Test", title)
            assertEquals("12.2-PH3", className)
            assertTrue(description.startsWith("1) Use results"))
        }
    }
}
