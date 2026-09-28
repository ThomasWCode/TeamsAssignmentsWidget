package com.teamsassignments.widget.automation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The automation must never press Hand in, Attach or anything else that changes Teams. */
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
}
