package com.teamsassignments.widget.automation

import com.teamsassignments.widget.data.DueDateParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The automation must never press Attach or anything else that changes Teams, and Hand in only
 * in the hand-in workflow, the user having asked for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SafetyTest {

    private class Probe(device: TeamsDevice) :
        TeamsAutomation(device, AutomationConfig(), now = { 0L }, log = {}, isCancelled = { false }) {
        fun tryClick(node: UiNode) = click(node)
    }

    private val clicks = mutableListOf<String>()
    private fun load(fixture: String) = Fixtures.load(fixture) { node, _ -> clicks += node.viewId; true }

    @Test
    fun `refuses the hand-in button and the attach menu`() = runTest {
        val detail = load("detail_4c958b24")
        val probe = Probe(FakeTeamsDevice())
        val dangerous = detail.walk().filter {
            it.text.equals("HAND IN", ignoreCase = true) || it.contentDescription in setOf("Open Attach menu", "Open New menu")
        }.toList()
        assertEquals(3, dangerous.size)

        dangerous.forEach { node -> assertFailsWith<SyncAbort> { probe.tryClick(node) } }
        assertTrue(clicks.isEmpty())
    }

    @Test
    fun `refuses reference-material buttons even though their ids start with a GUID`() = runTest {
        val detail = load("detail_b0ccf04e")
        val attachment = detail.walk().first { it.viewId.endsWith("-card") }
        assertFailsWith<SyncAbort> { Probe(FakeTeamsDevice()).tryClick(attachment) }
        assertTrue(clicks.isEmpty())
    }

    @Test
    fun `allows tabs and cards`() = runTest {
        val list = load("list_forthcoming")
        val probe = Probe(FakeTeamsDevice())
        probe.tryClick(list.findById("tab-Past-due")!!)
        probe.tryClick(TeamsScreens.findCard(list, "36274911-c6dd-490d-956d-0273df409847")!!)
        assertEquals(listOf("tab-Past-due", "36274911-c6dd-490d-956d-0273df409847"), clicks)
    }

    @Test
    fun `taps land on a visible card title below the tab bar`() {
        val list = load("list_forthcoming")
        val probe = Probe(FakeTeamsDevice())

        val visible = TeamsScreens.findCard(list, "36274911-c6dd-490d-956d-0273df409847")!!
        val point = assertNotNull(probe.safeTapPoint(list, visible))
        assertTrue(point.first in 213..942 && point.second in 868..1009, point.toString())

        // Off-screen cards have zero-height bounds, so there is nowhere safe to tap.
        val offScreen = TeamsScreens.findCard(list, "66fcdab0-2ed3-44b9-9ea3-3fba18d79567")!!
        assertNull(probe.safeTapPoint(list, offScreen))
    }

    @Test
    fun `the hand-in check allows Teams' own Hand in button and nothing else`() {
        val handIn = HandInStateMachine(FakeTeamsDevice(), now = { 0L })
        handIn.requireHandInButton(load("detail_4c958b24").walk().single { it.text == "HAND IN" })
        handIn.requireHandInButton(load("detail_88fafeb2").walk().single { it.text == "HAND IN LATE" })

        val detail = load("detail_4c958b24")
        val refused = listOf(
            load("detail_4c958b24_handed_in").walk().single { it.text == "UNDO HAND IN" },
            load("detail_4c958b24_hand_in_disabled").walk().single { it.text == "HAND IN" },
            detail.walk().single { it.contentDescription == "Open Attach menu" },
            detail.walk().single { it.contentDescription == "Open New menu" },
            detail.walk().single { it.text == "Immersive Reader" },
            detail.findById("overflow_menu_button")!!, // Back
            // Hand in's words on anything but a button, such as a line of the instructions.
            FakeNode(
                className = "android.widget.TextView", text = "Hand in", contentDescription = "", viewId = "",
                bounds = IntRect(0, 0, 100, 100), isClickable = true, isScrollable = false, isSelected = false,
                children = emptyList(), onAction = { _, _ -> true },
            ),
        )
        refused.forEach { node -> assertFailsWith<SyncAbort>(node.describe()) { handIn.requireHandInButton(node) } }
        assertTrue(clicks.isEmpty())
    }

    @Test
    fun `a sync never presses Hand in`() = runTest {
        val device = FakeTeamsDevice()
        SyncStateMachine(device, DueDateParser(Clock.systemUTC()), System::currentTimeMillis, now = { testScheduler.currentTime })
            .run(emptyList())
        assertTrue(device.opened.isNotEmpty())
        assertTrue(device.pressed.none { "HAND IN" in it.uppercase() }, device.pressed.toString())
        assertTrue(device.handedIn.isEmpty())
    }
}
