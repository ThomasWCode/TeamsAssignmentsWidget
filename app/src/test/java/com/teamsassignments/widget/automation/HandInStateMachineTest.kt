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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The hand-in workflow against the fake phone. It ends on a handed-in screen: the one captured on
 * the phone for "Dr. Frost - Forces - Week 3", or one derived in its wording for other assignments
 * (see scripts/derive_fixtures.py).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HandInStateMachineTest {

    private val log = mutableListOf<String>()
    private var pressing = 0

    private val physics = Assignment(
        key = "4c958b24-de6c-429b-846b-1d02d0cbed0b",
        title = "Particle Physics Test",
        className = "12.2-PH3",
        tab = AssignmentTab.Forthcoming,
    )
    private val prep = Assignment(
        key = "88fafeb2-b06d-41e0-bbeb-e4cd4de4c215",
        title = "Prep 18/09/2026 - Chapter 12 review",
        className = "Further Maths Year 12 (Mechanics mixed) RGAB",
        tab = AssignmentTab.PastDue,
    )

    private fun TestScope.handIn(device: FakeTeamsDevice, isCancelled: () -> Boolean = { false }) =
        HandInStateMachine(
            device = device,
            now = { testScheduler.currentTime },
            log = { log += it },
            isCancelled = isCancelled,
            onPressing = { pressing++ },
        )

    private val FakeTeamsDevice.handInPresses get() = clicked.count { it.startsWith("HAND IN") }

    @Test
    fun `hands in the chosen assignment with one press of Teams' own button`() = runTest {
        val device = FakeTeamsDevice()
        assertEquals(HandInResult.HandedIn, handIn(device).run(physics))
        assertEquals(listOf(physics.key), device.handedIn)
        assertEquals(1, device.handInPresses)
        assertEquals(1, pressing)
        assertEquals(Screen.Detail(physics.key, Tab.Forthcoming), device.screen)
    }

    @Test
    fun `recognises the handed-in screen Teams really shows`() = runTest {
        val drFrost = Assignment(
            key = "f63a23c9-6d36-4c5c-a858-422d8a17a723",
            title = "Dr. Frost - Forces - Week 3",
            className = "12.34 - Further Maths Mechanics - Mr Ryder Richardson 26/27",
            tab = AssignmentTab.Forthcoming,
        )
        val device = FakeTeamsDevice()
        assertEquals(HandInResult.HandedIn, handIn(device).run(drFrost))
        assertEquals(listOf(drFrost.key), device.handedIn)
        assertEquals(1, device.handInPresses)
    }

    @Test
    fun `presses nothing on a screen Teams already shows as handed in`() = runTest {
        val device = FakeTeamsDevice().apply { detailOverrides[physics.key] = "detail_f63a23c9_handed_in" }
        // The captured screen is another assignment's, so it must not open as this one...
        assertEquals(HandInResult.NotFound, handIn(device).run(physics))
        assertEquals(0, device.handInPresses)
        // ...and on its own assignment it counts as handed in already.
        val drFrost = FakeTeamsDevice().apply { detailOverrides["f63a23c9-6d36-4c5c-a858-422d8a17a723"] = "detail_f63a23c9_handed_in" }
        val target = physics.copy(key = "f63a23c9-6d36-4c5c-a858-422d8a17a723", title = "Dr. Frost - Forces - Week 3")
        assertEquals(HandInResult.AlreadyHandedIn, handIn(drFrost).run(target))
        assertTrue(drFrost.clicked.none { "HAND" in it }, drFrost.clicked.toString())
    }

    @Test
    fun `hands in late work with Hand in late`() = runTest {
        val device = FakeTeamsDevice()
        assertEquals(HandInResult.HandedIn, handIn(device).run(prep))
        assertEquals(listOf("HAND IN LATE"), device.clicked.filter { it.startsWith("HAND IN") })
        assertEquals(listOf(prep.key), device.handedIn)
    }

    @Test
    fun `presses nothing but tabs, the card and Hand in`() = runTest {
        val device = FakeTeamsDevice()
        handIn(device).run(physics)
        assertTrue(
            device.pressed.all { id -> Tab.entries.any { it.viewId == id } || TeamsSelectors.CARD_ID.matches(id) || id == "HAND IN" },
            device.pressed.toString(),
        )
        assertFalse(Tab.Completed.viewId in device.pressed)
    }

    @Test
    fun `presses nothing when it's already handed in`() = runTest {
        val device = FakeTeamsDevice().apply { detailOverrides[physics.key] = "detail_4c958b24_handed_in" }
        assertEquals(HandInResult.AlreadyHandedIn, handIn(device).run(physics))
        assertTrue(device.clicked.none { "HAND IN" in it }, device.clicked.toString())
        assertEquals(0, pressing)
    }

    @Test
    fun `presses nothing when the button is greyed out`() = runTest {
        val device = FakeTeamsDevice().apply { detailOverrides[physics.key] = "detail_4c958b24_hand_in_disabled" }
        assertEquals(HandInResult.NoButton, handIn(device).run(physics))
        assertEquals(0, device.handInPresses)
        assertEquals(0, pressing)
    }

    @Test
    fun `presses once, then reports it unconfirmed when Teams shows no change`() = runTest {
        val device = FakeTeamsDevice().apply { handInWorks = false }
        assertEquals(HandInResult.Unconfirmed, handIn(device).run(physics))
        assertEquals(1, device.handInPresses)
        assertTrue(device.handedIn.isEmpty())
    }

    @Test
    fun `presses again only when Teams says the first press didn't go through`() = runTest {
        val device = FakeTeamsDevice().apply { failHandInClicks = 1 }
        assertEquals(HandInResult.HandedIn, handIn(device).run(physics))
        assertEquals(2, device.handInPresses)
        assertEquals(listOf(physics.key), device.handedIn)
    }

    @Test
    fun `gives up when the second press doesn't go through either`() = runTest {
        val device = FakeTeamsDevice().apply { failHandInClicks = 2 }
        assertEquals(HandInResult.NoButton, handIn(device).run(physics))
        assertEquals(2, device.handInPresses)
        assertTrue(device.handedIn.isEmpty())
    }

    @Test
    fun `only takes Teams' word for the assignment it pressed Hand in on`() = runTest {
        // Another assignment's handed-in screen must not count as this one's confirmation.
        val device = FakeTeamsDevice().apply {
            handInWorks = false
            afterAction = { if ("HAND IN" in it.clicked) it.detailOverrides[physics.key] = "detail_88fafeb2_handed_in" }
        }
        assertEquals(HandInResult.Unconfirmed, handIn(device).run(physics))
    }

    @Test
    fun `refuses an assignment saved without its Teams id`() = runTest {
        // Found by title alone, a same-titled card could stand in for it.
        val device = FakeTeamsDevice()
        val noGuid = physics.copy(key = Assignment.fallbackKey(physics.className, physics.title))
        assertEquals(HandInResult.NotFound, handIn(device).run(noGuid))
        assertEquals(Screen.Home, device.screen)
        assertTrue(device.opened.isEmpty())
    }

    @Test
    fun `presses nothing when the assignment is on neither list`() = runTest {
        val device = FakeTeamsDevice()
        val gone = physics.copy(key = "00000000-0000-0000-0000-000000000000")
        assertEquals(HandInResult.NotFound, handIn(device).run(gone))
        assertTrue(device.opened.isEmpty())
        assertEquals(0, device.handInPresses)
    }

    @Test
    fun `stops before pressing when cancelled`() = runTest {
        val device = FakeTeamsDevice()
        val abort = assertFailsWith<SyncAbort> {
            handIn(device, isCancelled = { device.opened.isNotEmpty() }).run(physics)
        }
        assertEquals(SyncAbort.CANCELLED, abort.reason)
        assertEquals(0, device.handInPresses)
        assertEquals(0, pressing)
    }

    @Test
    fun `reports it unconfirmed when the user leaves Teams after the press`() = runTest {
        val device = FakeTeamsDevice().apply {
            handInWorks = false
            afterAction = { if ("HAND IN" in it.clicked) it.screen = Screen.OtherApp }
        }
        assertEquals(HandInResult.Unconfirmed, handIn(device).run(physics))
        assertTrue(log.any { "Stopped waiting for Teams: ${SyncAbort.LEFT_TEAMS}" in it }, log.joinToString("\n"))
    }
}
