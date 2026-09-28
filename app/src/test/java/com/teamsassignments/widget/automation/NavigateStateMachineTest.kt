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

    private fun assignment(key: String, title: String, tab: AssignmentTab) =
        Assignment(key = key, title = title, className = "", tab = tab)

    @Test
    fun `opens a Forthcoming assignment`() = runTest {
        val device = FakeTeamsDevice()
        val target = assignment("4c958b24-de6c-429b-846b-1d02d0cbed0b", "Particle Physics Test", AssignmentTab.Forthcoming)

        assertTrue(navigate(device).run(target))
        assertEquals(Screen.Detail(target.key, Tab.Forthcoming), device.screen)
        assertFalse(Tab.PastDue.viewId in device.clicked)
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
        assertEquals(0, device.cardClicks)
    }
}
