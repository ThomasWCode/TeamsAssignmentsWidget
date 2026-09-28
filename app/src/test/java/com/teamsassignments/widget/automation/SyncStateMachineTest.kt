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
import kotlin.test.assertFalse
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

    private val hausaufgabe = "36274911-c6dd-490d-956d-0273df409847"
    private val worksheet = "839994fb-a6a8-4f11-9ff6-bd319473a93a"

    private fun TestScope.machine(
        device: FakeTeamsDevice,
        config: AutomationConfig = AutomationConfig(),
        isCancelled: () -> Boolean = { false },
    ) = SyncStateMachine(
        device = device,
        parser = parser,
        wallClock = { clock.millis() },
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
            assertEquals(hausaufgabe, key)
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
        assertEquals(10, device.opened.size)
        assertIs<Screen.List>(device.screen)
    }

    @Test
    fun `only ever presses tabs and assignment cards`() = runTest {
        val device = FakeTeamsDevice()
        machine(device).run(emptyList())
        assertTrue(device.pressed.isNotEmpty())
        assertTrue(
            device.pressed.all { id -> Tab.entries.any { it.viewId == id } || TeamsSelectors.CARD_ID.matches(id) },
            device.pressed.toString(),
        )
        assertFalse(Tab.Completed.viewId in device.pressed)
    }

    @Test
    fun `taps visible cards and tabs rather than using click actions`() = runTest {
        // On the phone, Teams ignored every accessibility click but responded to taps.
        val device = FakeTeamsDevice()
        machine(device).run(emptyList())
        assertTrue(hausaufgabe in device.tappedTargets)
        assertFalse(hausaufgabe in device.clicked)
        assertTrue(Tab.PastDue.viewId in device.tappedTargets)
        assertFalse(Tab.PastDue.viewId in device.clicked)
    }

    @Test
    fun `falls back to the click action when taps do nothing`() = runTest {
        val device = FakeTeamsDevice().apply { tapsOpenCards = false }
        val result = machine(device).run(emptyList())
        assertEquals(10, result.size)
        assertTrue(hausaufgabe in device.clicked)
    }

    @Test
    fun `reuses recent details instead of reopening every card`() = runTest {
        val first = machine(FakeTeamsDevice()).run(emptyList())

        val device = FakeTeamsDevice()
        val second = machine(device).run(previous = first)

        assertTrue(device.opened.isEmpty(), device.opened.toString())
        assertEquals(first.associate { it.key to it.description }, second.associate { it.key to it.description })
        assertEquals(first.associate { it.key to it.className }, second.associate { it.key to it.className })
    }

    @Test
    fun `a full sync rereads every card`() = runTest {
        val first = machine(FakeTeamsDevice()).run(emptyList())
        val device = FakeTeamsDevice()
        machine(device).run(previous = first, full = true)
        assertEquals(10, device.opened.size)
    }

    @Test
    fun `details older than the reuse window are reread`() = runTest {
        val stale = machine(FakeTeamsDevice()).run(emptyList())
            .map { it.copy(detailReadAt = clock.millis() - TimeUnit.DAYS.toMillis(4)) }
        val device = FakeTeamsDevice()
        machine(device).run(previous = stale)
        assertEquals(10, device.opened.size)
    }

    @Test
    fun `a changed row is reread`() = runTest {
        val first = machine(FakeTeamsDevice()).run(emptyList())
        val edited = first.map { if (it.key == hausaufgabe) it.copy(dueAt = 0) else it }
        val device = FakeTeamsDevice()
        machine(device).run(previous = edited)
        assertEquals(listOf(hausaufgabe), device.opened)
    }

    @Test
    fun `a changed row that fails to open is retried on the next sync`() = runTest {
        // Codex review: keeping the old read time made the changed row look fresh for days.
        val first = machine(FakeTeamsDevice()).run(emptyList())
        val changed = first.map { if (it.key == worksheet) it.copy(dueAt = 0) else it }

        val failing = FakeTeamsDevice().apply {
            tapsOpenCards = false
            swallowClicks[worksheet] = Int.MAX_VALUE
        }
        val second = machine(failing).run(previous = changed)
        assertNull(second.single { it.key == worksheet }.detailReadAt)

        val device = FakeTeamsDevice()
        machine(device).run(previous = second)
        assertEquals(listOf(worksheet), device.opened)
    }

    @Test
    fun `an unchanged row that fails to open keeps its earlier read`() = runTest {
        val first = machine(FakeTeamsDevice()).run(emptyList())
        val due = first.map { if (it.key == worksheet) it.copy(detailReadAt = clock.millis() - TimeUnit.DAYS.toMillis(4)) else it }
        val failing = FakeTeamsDevice().apply {
            tapsOpenCards = false
            swallowClicks[worksheet] = Int.MAX_VALUE
        }
        val result = machine(failing).run(previous = due)
        with(result.single { it.key == worksheet }) {
            assertEquals(clock.millis() - TimeUnit.DAYS.toMillis(4), detailReadAt)
            assertTrue(description.startsWith("Complete the worksheet"))
        }
    }

    @Test
    fun `retries a card once before giving up on its details`() = runTest {
        val device = FakeTeamsDevice().apply {
            tapsOpenCards = false
            swallowClicks[worksheet] = 1
        }
        val result = machine(device).run(emptyList())
        assertEquals(2, device.clicked.count { it == worksheet })
        assertNotNull(result.single { it.key == worksheet }.detailReadAt)
        assertTrue(log.any { "attempt 1 of 2" in it })
    }

    @Test
    fun `keeps the list data when a card never opens`() = runTest {
        val device = FakeTeamsDevice().apply {
            tapsOpenCards = false
            swallowClicks[worksheet] = Int.MAX_VALUE
        }
        val result = machine(device).run(emptyList())

        assertEquals(10, result.size)
        with(result.single { it.key == worksheet }) {
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
        val earlier = Assignment(key = worksheet, title = "old", className = "old", description = "Earlier instructions")
        val device = FakeTeamsDevice().apply {
            tapsOpenCards = false
            swallowClicks[worksheet] = Int.MAX_VALUE
        }
        val result = machine(device).run(previous = listOf(earlier))
        assertEquals("Earlier instructions", result.single { it.key == worksheet }.description)
    }

    @Test
    fun `a card on both tabs is kept under the later one`() = runTest {
        // Codex review: a deadline passing between the two list reads puts a card on both tabs.
        val device = FakeTeamsDevice(
            lists = mapOf(
                Tab.Forthcoming to "list_forthcoming",
                Tab.PastDue to "list_past_due_with_moved_cards",
                Tab.Completed to "list_completed",
            ),
        )
        val result = machine(device).run(emptyList())

        assertEquals(10, result.size)
        assertEquals(AssignmentTab.PastDue, result.single { it.key == hausaufgabe }.tab)
        assertEquals(1, device.opened.count { it == hausaufgabe })
    }

    @Test
    fun `never takes the previous tab's rows for a slow tab`() = runTest {
        // Codex review: Teams can mark a tab selected before replacing its rows.
        val device = FakeTeamsDevice(now = { testScheduler.currentTime }).apply {
            slowTabs[Tab.PastDue] = "list_past_due_stale_rows" to 4_000L
        }
        val result = machine(device).run(emptyList())
        assertEquals(10, result.size)
        assertEquals(3, result.count { it.tab == AssignmentTab.PastDue })
        assertEquals(AssignmentTab.Forthcoming, result.single { it.key == hausaufgabe }.tab)
    }

    @Test
    fun `waits for a sliding screen to settle before tapping`() = runTest {
        // Seen on the phone: after Back from an assignment, the list slid in 337 px to the left and
        // the Forthcoming tab was tapped at a negative x, which crashed the sync.
        val device = FakeTeamsDevice(now = { testScheduler.currentTime }).apply { backTransition = -337 to 400L }
        val result = machine(device).run(emptyList())

        assertEquals(10, result.size)
        assertTrue(device.tapped.all { (x, y) -> x >= 0 && y >= 0 }, device.tapped.toString())
        assertTrue(device.tappedTargets.none { it.isEmpty() }, "every tap should land on a tab or card")
        assertTrue(Tab.Forthcoming.viewId in device.tappedTargets)
    }

    @Test
    fun `a single card that moved tabs between the two reads still settles`() = runTest {
        // Codex review: its only card makes Past due look like Forthcoming's rows, but the list changed.
        val device = FakeTeamsDevice(
            lists = mapOf(
                Tab.Forthcoming to "list_forthcoming_single",
                Tab.PastDue to "list_past_due_single_moved",
                Tab.Completed to "list_completed",
            ),
        )
        val result = machine(device).run(emptyList())
        assertEquals(listOf(hausaufgabe), result.map { it.key })
        assertEquals(AssignmentTab.PastDue, result.single().tab)
    }

    @Test
    fun `gives up rather than guess when the new tab never changes`() = runTest {
        val device = FakeTeamsDevice(
            lists = mapOf(
                Tab.Forthcoming to "list_forthcoming",
                Tab.PastDue to "list_past_due_stale_rows",
                Tab.Completed to "list_completed",
            ),
        )
        val abort = assertFailsWith<SyncAbort> { machine(device).run(emptyList()) }
        assertEquals("Couldn't read the Past due list", abort.reason)
    }

    @Test
    fun `waits while the list shows a spinner`() = runTest {
        val device = FakeTeamsDevice(now = { testScheduler.currentTime }).apply {
            slowTabs[Tab.PastDue] = "list_past_due_loading" to 5_000L
        }
        assertEquals(10, machine(device).run(emptyList()).size)
    }

    @Test
    fun `waits while the Assignments module is still loading`() = runTest {
        // Captured on the phone just after launch: Teams' toolbar over an empty WebView. Taken for
        // a list, it would read as an empty Forthcoming tab, so it lasts past both empty-list holds.
        val device = FakeTeamsDevice(now = { testScheduler.currentTime }).apply {
            launchLoading = "list_assignments_loading" to 8_000L
        }
        val result = machine(device).run(emptyList())
        assertEquals(10, result.size)
        assertEquals(7, result.count { it.tab == AssignmentTab.Forthcoming })
    }

    @Test
    fun `an empty tab is believed once it stays empty`() = runTest {
        val device = FakeTeamsDevice(
            lists = mapOf(
                Tab.Forthcoming to "list_forthcoming",
                Tab.PastDue to "list_past_due_empty",
                Tab.Completed to "list_completed",
            ),
        )
        val result = machine(device).run(emptyList())
        assertEquals(7, result.size)
        assertTrue(result.all { it.tab == AssignmentTab.Forthcoming })
    }

    @Test
    fun `a tab that had work at the last sync must stay empty for longer`() = runTest {
        val previous = machine(FakeTeamsDevice()).run(emptyList())
        fun emptyForFourSeconds() = FakeTeamsDevice(now = { testScheduler.currentTime }).apply {
            slowTabs[Tab.PastDue] = "list_past_due_empty" to 4_000L
        }
        // Past due had work last time, so four seconds of "empty" isn't believed: the real list is read.
        assertEquals(3, machine(emptyForFourSeconds()).run(previous).count { it.tab == AssignmentTab.PastDue })
        // With nothing there before, the same empty spell is believed.
        assertEquals(0, machine(emptyForFourSeconds()).run(emptyList()).count { it.tab == AssignmentTab.PastDue })
    }

    @Test
    fun `tries the tab again when Teams ignores the first tap`() = runTest {
        val device = FakeTeamsDevice().apply { ignoreTabTaps = 1 }
        val result = machine(device).run(emptyList())
        assertEquals(10, result.size)
        assertTrue(log.any { "Past due tab selected on attempt 2" in it }, log.joinToString("\n"))
    }

    @Test
    fun `taps the tab a second time when its click is ignored too`() = runTest {
        val device = FakeTeamsDevice().apply {
            ignoreTabTaps = 1
            swallowTabClicks = 1
        }
        val result = machine(device).run(emptyList())
        assertEquals(10, result.size)
        assertTrue(log.any { "Past due tab selected on attempt 3" in it }, log.joinToString("\n"))
        // The centre of the Past due tab, [396,306][660,401] in the capture.
        assertEquals(listOf(528 to 353, 528 to 353), device.tapped.filterIndexed { i, _ -> device.tappedTargets[i] == Tab.PastDue.viewId }.take(2))
    }

    @Test
    fun `probes for more cards only when the list runs off screen`() = runTest {
        val device = FakeTeamsDevice()
        machine(device).run(emptyList())
        // Forthcoming runs past the bottom of the screen, so one scroll is tried; Past due ends on screen.
        assertEquals(listOf(UiAction.ScrollForward), device.scrolls)
    }

    @Test
    fun `stops when the user leaves Teams`() = runTest {
        val device = FakeTeamsDevice().apply {
            afterAction = { if (it.opened.size == 3) it.screen = Screen.OtherApp }
        }
        val abort = assertFailsWith<SyncAbort> { machine(device).run(emptyList()) }
        assertEquals(SyncAbort.LEFT_TEAMS, abort.reason)
        assertTrue(abort.byUser)
    }

    @Test
    fun `stops when cancelled`() = runTest {
        val device = FakeTeamsDevice()
        val abort = assertFailsWith<SyncAbort> {
            machine(device, isCancelled = { device.opened.size >= 2 }).run(emptyList())
        }
        assertEquals(SyncAbort.CANCELLED, abort.reason)
        assertEquals(2, device.opened.size)
    }

    @Test
    fun `stops when the whole run takes too long`() = runTest {
        val abort = assertFailsWith<SyncAbort> {
            machine(FakeTeamsDevice(), config = AutomationConfig(globalTimeoutMs = 8_000)).run(emptyList())
        }
        assertEquals("Took too long", abort.reason)
        assertFalse(abort.byUser)
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
        val device = FakeTeamsDevice().apply { launchLandsOn = Screen.Detail(hausaufgabe, Tab.Forthcoming) }
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
