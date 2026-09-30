package com.teamsassignments.widget.automation

import com.teamsassignments.widget.automation.LookPacer.Outcome
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LookPacerTest {

    @Test
    fun `looks at every change while Teams shows Assignments`() {
        val pacer = LookPacer()
        assertTrue(pacer.shouldLook(0, screenChanged = false))
        pacer.looked(Outcome.Read, 0)
        assertTrue(pacer.shouldLook(700, screenChanged = false))
        assertFalse(pacer.owed)
    }

    @Test
    fun `elsewhere in Teams, looks again after five seconds, or on another screen`() {
        val pacer = LookPacer()
        pacer.looked(Outcome.Elsewhere, 0)
        assertFalse(pacer.shouldLook(1_000, screenChanged = false))
        assertTrue(pacer.shouldLook(1_000, screenChanged = true))
        pacer.looked(Outcome.Elsewhere, 1_000)
        assertFalse(pacer.shouldLook(5_900, screenChanged = false))
        assertTrue(pacer.shouldLook(6_000, screenChanged = false))
    }

    @Test
    fun `a change held back is owed its look, even once Teams goes quiet`() {
        // Codex review: the held-back change used to get no look at all unless Teams changed again.
        val pacer = LookPacer()
        pacer.looked(Outcome.Elsewhere, 0)
        assertFalse(pacer.shouldLook(1_000, screenChanged = false))
        assertTrue(pacer.owed)
        assertTrue(pacer.shouldLook(5_000, screenChanged = false))
        assertFalse(pacer.owed)
    }

    @Test
    fun `a failed read is tried again, up to five times, without counting as elsewhere`() {
        // Codex review: a copy that failed on Assignments was taken for being elsewhere.
        val pacer = LookPacer()
        repeat(4) {
            pacer.looked(Outcome.Failed, 0)
            assertTrue(pacer.owed)
            assertTrue(pacer.shouldLook(700, screenChanged = false))
        }
        pacer.looked(Outcome.Failed, 0)
        assertFalse(pacer.owed)
        // A read resets the count.
        pacer.looked(Outcome.Read, 0)
        pacer.looked(Outcome.Failed, 0)
        assertTrue(pacer.owed)
    }
}
