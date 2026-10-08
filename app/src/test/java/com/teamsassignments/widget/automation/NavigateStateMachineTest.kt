package com.teamsassignments.widget.automation

import com.teamsassignments.widget.automation.FakeTeamsDevice.Screen
import com.teamsassignments.widget.automation.TeamsSelectors.Tab
import com.teamsassignments.widget.data.Assignment
import com.teamsassignments.widget.data.AssignmentTab
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NavigateStateMachineTest {

    private fun TestScope.navigate(device: FakeTeamsDevice) =
        NavigateStateMachine(device, now = { testScheduler.currentTime })

    private fun assignment(key: String, title: String, tab: AssignmentTab, className: String = "") =
        Assignment(key = key, title = title, className = className, tab = tab)

    @Test
    fun `opens a Forthcoming assignment`() = runTest {
        val device = FakeTeamsDevice()
        val target = assignment("4c958b24-de6c-429b-846b-1d02d0cbed0b", "Particle Physics Test", AssignmentTab.Forthcoming)

        assertTrue(navigate(device).run(target))
        assertEquals(Screen.Detail(target.key, Tab.Forthcoming), device.screen)
        assertFalse(Tab.PastDue.viewId in device.pressed)
    }

    @Test
    fun `remembers the title the list showed, unless the card was collapsed`() = runTest {
        val target = assignment("4c958b24-de6c-429b-846b-1d02d0cbed0b", "Physics test (old name)", AssignmentTab.Forthcoming)
        val machine = navigate(FakeTeamsDevice())
        assertTrue(machine.run(target))
        assertEquals("Particle Physics Test", machine.listedTitle)

        // After a visit its card collapses into one node, whose title is cut out of its text.
        val collapsed = FakeTeamsDevice(
            lists = mapOf(
                Tab.Forthcoming to "list_forthcoming_after_back",
                Tab.PastDue to "list_past_due",
                Tab.Completed to "list_completed",
            ),
        )
        val again = navigate(collapsed)
        assertTrue(again.run(target))
        assertNull(again.listedTitle)
    }

    @Test
    fun `opens a Past due assignment from its tab`() = runTest {
        val device = FakeTeamsDevice()
        val target = assignment("d3f67007-3eb3-409e-840f-d8602b70ba8f", "Text - LESEN", AssignmentTab.PastDue)

        assertTrue(navigate(device).run(target))
        assertEquals(Screen.Detail(target.key, Tab.PastDue), device.screen)
    }

    @Test
    fun `looks in the other tab when the deadline has moved it`() = runTest {
        val device = FakeTeamsDevice()
        // Last synced while still forthcoming; it's now past due.
        val target = assignment("d3f67007-3eb3-409e-840f-d8602b70ba8f", "Text - LESEN", AssignmentTab.Forthcoming)

        assertTrue(navigate(device).run(target))
        assertEquals(Screen.Detail(target.key, Tab.PastDue), device.screen)
    }

    @Test
    fun `leaves Teams on the list when the assignment is gone`() = runTest {
        val device = FakeTeamsDevice()
        val target = assignment("00000000-0000-0000-0000-000000000000", "Handed in yesterday", AssignmentTab.Forthcoming)

        val machine = navigate(device)
        assertFalse(machine.run(target))
        assertIs<Screen.List>(device.screen)
        assertTrue(device.opened.isEmpty())
        // Both open tabs were read in full, and neither lists it.
        assertTrue(machine.notListed)
    }

    private val gone = assignment("00000000-0000-0000-0000-000000000000", "Handed in yesterday", AssignmentTab.Forthcoming)

    @Test
    fun `doesn't take it as gone when a list isn't whole in the tree`() = runTest {
        // Derived: a virtualised Forthcoming list only holds the rows in view.
        val device = FakeTeamsDevice(
            lists = mapOf(
                Tab.Forthcoming to "list_forthcoming_virtualised",
                Tab.PastDue to "list_past_due",
                Tab.Completed to "list_completed",
            ),
        )
        val machine = navigate(device)
        assertFalse(machine.run(gone))
        assertFalse(machine.notListed)
    }

    /** The 8 Oct Past due capture: seven cards and Teams' "load more" placeholder below them. */
    private fun TestScope.loadMoreDevice() = FakeTeamsDevice(
        lists = mapOf(
            Tab.Forthcoming to "list_forthcoming",
            Tab.PastDue to "list_past_due_load_more",
            Tab.Completed to "list_completed",
        ),
        now = { testScheduler.currentTime },
    )

    @Test
    fun `opens a card on a page Teams only loads once asked`() = runTest {
        // Codex review: the card may be on a page not loaded yet, and on the phone a page took
        // 2.6 s. The placeholder is brought into view and the list waited for, as in a sync. (The
        // page here is the 28 Sept capture, holding "Text - LESEN".)
        val device = loadMoreDevice().apply {
            scrollsTo[Tab.PastDue] = listOf(0L to "list_past_due_loading", 2_600L to "list_past_due")
        }
        val target = assignment("d3f67007-3eb3-409e-840f-d8602b70ba8f", "Text - LESEN", AssignmentTab.PastDue)

        assertTrue(navigate(device).run(target))
        assertEquals(Screen.Detail(target.key, Tab.PastDue), device.screen)
        assertEquals(1, device.placeholdersShown)
    }

    @Test
    fun `opens a card on a Past due list still waiting to load more`() = runTest {
        // On the phone, opening overdue work waited 17 s for the placeholder, then gave up.
        val device = loadMoreDevice()
        val target = assignment("66fcdab0-2ed3-44b9-9ea3-3fba18d79567", "Gefahren in den sozialen Netzwerken. Vor- und Nachteile", AssignmentTab.PastDue)

        assertTrue(navigate(device).run(target))
        assertEquals(Screen.Detail(target.key, Tab.PastDue), device.screen)
    }

    @Test
    fun `doesn't take it as gone while Past due may have more to load`() = runTest {
        val machine = navigate(loadMoreDevice())
        assertFalse(machine.run(gone))
        assertFalse(machine.notListed)
    }

    /** "Text - LESEN" on the 28 Sept Past due capture, standing in for a card on a later page. */
    private val lesen = TeamsScreens.cards(Fixtures.load("list_past_due")).single { it.id.startsWith("d3f67007") }

    @Test
    fun `remembers the title a later page shows for a renamed card`() = runTest {
        // Codex review: a hand-in compares the opened screen with this title, so it must be the
        // card's current one, whichever page it turned up on.
        val device = loadMoreDevice().apply { scrollsTo[Tab.PastDue] = listOf(0L to "list_past_due") }
        val machine = navigate(device)

        assertTrue(machine.run(assignment(lesen.id, "Text (before it was renamed)", AssignmentTab.PastDue)))
        assertEquals(lesen.title, machine.listedTitle)
    }

    @Test
    fun `finds a card saved without a Teams id on a later page`() = runTest {
        // Codex review: a card added from its own screen has no GUID until a sync, and is matched
        // by title and class, which must also look past the first page.
        val device = loadMoreDevice().apply { scrollsTo[Tab.PastDue] = listOf(0L to "list_past_due") }
        val target = assignment(Assignment.fallbackKey(lesen.className, lesen.title), lesen.title, AssignmentTab.PastDue, lesen.className)

        assertTrue(navigate(device).run(target))
        assertEquals(Screen.Detail(lesen.id, Tab.PastDue), device.screen)
    }

    @Test
    fun `doesn't take the rows a paged tab ended on for the next tab's`() = runTest {
        // Codex review: after Past due's search paged in more rows, Forthcoming is selected while
        // those rows still show (derived). They must not pass for Forthcoming's own list, or the
        // physics test on it would be missed.
        val device = loadMoreDevice().apply {
            scrollsTo[Tab.PastDue] = listOf(0L to "list_past_due")
            slowTabs[Tab.Forthcoming] = "list_forthcoming_stale_past_due_rows" to 2_000L
        }
        val target = assignment("4c958b24-de6c-429b-846b-1d02d0cbed0b", "Particle Physics Test", AssignmentTab.PastDue)

        assertTrue(navigate(device).run(target))
        assertEquals(Screen.Detail(target.key, Tab.Forthcoming), device.screen)
    }

    @Test
    fun `doesn't take a paged tab's rows for the next tab's when finding by title either`() = runTest {
        // Codex review: work saved without a Teams id pages the tab looking for a match, and when
        // there's none the rows it paged in must still be the next tab's baseline (derived, as above).
        val physics = TeamsScreens.cards(Fixtures.load("list_forthcoming")).single { it.id.startsWith("4c958b24") }
        val device = loadMoreDevice().apply {
            scrollsTo[Tab.PastDue] = listOf(0L to "list_past_due")
            slowTabs[Tab.Forthcoming] = "list_forthcoming_stale_past_due_rows" to 2_000L
        }
        val target = assignment(Assignment.fallbackKey(physics.className, physics.title), physics.title, AssignmentTab.PastDue, physics.className)

        assertTrue(navigate(device).run(target))
        assertEquals(Screen.Detail(physics.id, Tab.Forthcoming), device.screen)
    }

    @Test
    fun `takes it as gone once the search has loaded the rest of Past due`() = runTest {
        // Codex review: looking for the card loads every page, and the whole list then counts.
        val device = loadMoreDevice().apply { scrollsTo[Tab.PastDue] = listOf(0L to "list_past_due_load_more_end") }
        val machine = navigate(device)
        assertFalse(machine.run(gone))
        assertTrue(machine.notListed)
    }

    @Test
    fun `doesn't take it as gone when both tabs showed the same cards`() = runTest {
        // Codex review: Past due flashing a spinner, then showing Forthcoming's rows again, passes
        // as Past due's own list, since the list changed on the way. Two identical tabs prove nothing.
        val device = FakeTeamsDevice(
            lists = mapOf(
                Tab.Forthcoming to "list_forthcoming",
                Tab.PastDue to "list_past_due_stale_rows",
                Tab.Completed to "list_completed",
            ),
            now = { testScheduler.currentTime },
        ).apply { slowTabs[Tab.PastDue] = "list_past_due_loading" to 1_000L }
        val machine = navigate(device)
        assertFalse(machine.run(gone))
        assertFalse(machine.notListed)
    }

    @Test
    fun `doesn't take it as gone while it falls due`() = runTest {
        // It may be moving from Forthcoming to Past due as the tabs are read.
        val now = 1_790_000_000_000L
        val machine = NavigateStateMachine(FakeTeamsDevice(), now = { testScheduler.currentTime }, wallClock = { now })
        assertFalse(machine.run(gone.copy(dueAt = now + 60_000)))
        assertFalse(machine.notListed)
    }

    @Test
    fun `a card that's listed but won't open isn't taken as gone`() = runTest {
        val id = "4c958b24-de6c-429b-846b-1d02d0cbed0b"
        val device = FakeTeamsDevice().apply { detailOverrides[id] = "detail_unreadable" }
        val machine = navigate(device)
        assertFalse(machine.run(assignment(id, "Particle Physics Test", AssignmentTab.Forthcoming)))
        assertFalse(machine.notListed)
    }

    @Test
    fun `an empty tab that had work must stay empty for longer, as in a sync`() = runTest {
        fun device() = FakeTeamsDevice(
            lists = mapOf(
                Tab.Forthcoming to "list_forthcoming",
                Tab.PastDue to "list_past_due_empty",
                Tab.Completed to "list_completed",
            ),
        )
        val start = testScheduler.currentTime
        assertFalse(navigate(device()).run(gone))
        val quick = testScheduler.currentTime - start

        val hadWork = listOf(gone.copy(key = "d3f67007-3eb3-409e-840f-d8602b70ba8f", tab = AssignmentTab.PastDue))
        val machine = navigate(device())
        val second = testScheduler.currentTime
        assertFalse(machine.run(gone, hadWork))
        assertTrue(testScheduler.currentTime - second - quick >= 4_000, "waited ${testScheduler.currentTime - second} vs $quick")
        assertTrue(machine.notListed)
    }

    @Test
    fun `never opens a same-titled card in place of a missing GUID`() = runTest {
        // Codex review: a weekly "Prep 1" in the same class must not stand in for one handed in.
        val device = FakeTeamsDevice()
        val handedIn = assignment(
            "11111111-2222-3333-4444-555555555555", "Prep 1", AssignmentTab.Forthcoming,
            className = "Physics Skills & Stretch 12.2-PH3",
        )

        assertFalse(navigate(device).run(handedIn))
        assertTrue(device.opened.isEmpty())
    }

    @Test
    fun `matches by title only for cards saved without a GUID`() = runTest {
        val device = FakeTeamsDevice()
        val className = "Physics Skills & Stretch 12.2-PH3"
        val noGuid = assignment(Assignment.fallbackKey(className, "Prep 1"), "Prep 1", AssignmentTab.Forthcoming, className)

        assertTrue(navigate(device).run(noGuid))
        assertEquals(listOf("b0ccf04e-a976-4a0a-8066-6fca1e0ef999"), device.opened)
    }

    @Test
    fun `opens a card renamed since the last sync`() = runTest {
        // Codex review: the check is against the title the card shows now, not the saved one.
        val id = "4c958b24-de6c-429b-846b-1d02d0cbed0b"
        val device = FakeTeamsDevice()
        assertTrue(navigate(device).run(assignment(id, "Physics test (old name)", AssignmentTab.Forthcoming)))
        assertEquals(Screen.Detail(id, Tab.Forthcoming), device.screen)
    }

    @Test
    fun `taps a card again when the first tap is missed`() = runTest {
        // Codex review: Teams ignores click actions, so one missed tap mustn't lose the assignment.
        val id = "4c958b24-de6c-429b-846b-1d02d0cbed0b"
        val device = FakeTeamsDevice().apply {
            ignoreCardTaps[id] = 1
            swallowClicks[id] = 1
        }
        assertTrue(navigate(device).run(assignment(id, "Particle Physics Test", AssignmentTab.Forthcoming)))
        assertEquals(Screen.Detail(id, Tab.Forthcoming), device.screen)
    }

    @Test
    fun `goes back to the list when a detail screen can't be read`() = runTest {
        // Codex review: an unreadable detail screen was left open, stranding the next step.
        val id = "4c958b24-de6c-429b-846b-1d02d0cbed0b"
        val device = FakeTeamsDevice().apply { detailOverrides[id] = "detail_unreadable" }

        assertFalse(navigate(device).run(assignment(id, "Particle Physics Test", AssignmentTab.Forthcoming)))
        assertIs<Screen.List>(device.screen)
    }

    @Test
    fun `looks upwards for a card when the list ends on screen`() = runTest {
        // Codex review: a list scrolled to its end can still have rows above.
        val device = FakeTeamsDevice()
        val target = assignment("00000000-0000-0000-0000-000000000000", "Gone", AssignmentTab.PastDue)

        assertFalse(navigate(device).run(target))
        assertTrue(UiAction.ScrollBackward in device.scrolls)
    }

    @Test
    fun `scrolls for a card missing from a list that runs off screen`() = runTest {
        // Codex review: a virtualised list only holds the rows in view, so look further before giving up.
        val device = FakeTeamsDevice()
        val target = assignment("00000000-0000-0000-0000-000000000000", "Further down", AssignmentTab.Forthcoming)

        assertFalse(navigate(device).run(target))
        assertTrue(UiAction.ScrollForward in device.scrolls)
    }
}
