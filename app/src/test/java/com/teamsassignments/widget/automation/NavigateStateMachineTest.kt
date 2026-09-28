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

        assertFalse(navigate(device).run(target))
        assertIs<Screen.List>(device.screen)
        assertTrue(device.opened.isEmpty())
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
