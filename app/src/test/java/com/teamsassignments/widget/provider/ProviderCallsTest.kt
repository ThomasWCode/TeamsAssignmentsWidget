package com.teamsassignments.widget.provider

import com.teamsassignments.widget.data.Assignment
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderCallsTest {

    private class FakeControl(
        override var isBusy: Boolean = false,
        private val opens: Boolean = true,
    ) : SyncControl {
        val started = mutableListOf<String>()

        override fun startSync(): Boolean {
            if (isBusy) return false
            started += "sync"
            return true
        }

        override fun openAssignment(key: String): Boolean {
            if (isBusy || !opens) return false
            started += "open $key"
            return true
        }
    }

    private val saved = listOf(Assignment(key = "4c958b24", title = "Particle Physics Test", className = "12.2-PH3"))

    @Test
    fun `a sync starts when the service is idle`() {
        val control = FakeControl()

        assertEquals(CallOutcome.Started, ProviderCalls.requestSync(control))
        assertEquals(listOf("sync"), control.started)
    }

    @Test
    fun `a sync is refused while Teams is busy, or with the service off`() {
        assertEquals(CallOutcome.Busy, ProviderCalls.requestSync(FakeControl(isBusy = true)))
        assertEquals(CallOutcome.ServiceOff, ProviderCalls.requestSync(null))
    }

    @Test
    fun `an assignment on the list opens`() {
        val control = FakeControl()

        assertEquals(CallOutcome.Started, ProviderCalls.open(control, "4c958b24", saved))
        assertEquals(listOf("open 4c958b24"), control.started)
    }

    @Test
    fun `a key that isn't on the list opens nothing, whatever the service is doing`() {
        val control = FakeControl()

        assertEquals(CallOutcome.UnknownKey, ProviderCalls.open(control, "gone", saved))
        assertEquals(CallOutcome.UnknownKey, ProviderCalls.open(control, null, saved))
        assertEquals(CallOutcome.UnknownKey, ProviderCalls.open(null, "gone", saved))
        assertTrue(control.started.isEmpty())
    }

    @Test
    fun `opening is refused while Teams is busy, or with the service off`() {
        assertEquals(CallOutcome.Busy, ProviderCalls.open(FakeControl(isBusy = true), "4c958b24", saved))
        assertEquals(CallOutcome.ServiceOff, ProviderCalls.open(null, "4c958b24", saved))
    }

    @Test
    fun `an assignment that leaves the list just before opening counts as unknown`() {
        assertEquals(CallOutcome.UnknownKey, ProviderCalls.open(FakeControl(opens = false), "4c958b24", saved))
    }

    @Test
    fun `outcomes carry the contract's reasons`() {
        assertTrue(CallOutcome.Started.started)
        assertNull(CallOutcome.Started.reason)
        assertFalse(CallOutcome.Busy.started)
        assertEquals(AssignmentsContract.REASON_BUSY, CallOutcome.Busy.reason)
        assertEquals(AssignmentsContract.REASON_SERVICE_OFF, CallOutcome.ServiceOff.reason)
        assertEquals(AssignmentsContract.REASON_UNKNOWN_KEY, CallOutcome.UnknownKey.reason)
    }
}
