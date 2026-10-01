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

    @Test
    fun `a page it can't place gets a few quick looks, then waits like anywhere else`() {
        // On the phone, an assignment opened from the Activity feed took a second or two to load.
        // Taken for elsewhere at the first look, it was read five seconds late.
        val pacer = LookPacer()
        pacer.looked(Outcome.Elsewhere, 0) // the Activity feed
        assertTrue(pacer.shouldLook(500, screenChanged = true))
        repeat(4) { i ->
            pacer.looked(Outcome.Unsure, 500L + 700 * i)
            assertTrue(pacer.owed)
            assertTrue(pacer.shouldLook(1_200L + 700 * i, screenChanged = false))
        }
        // Still unplaced at the fifth look: some other app's page, checked like a chat from now on.
        pacer.looked(Outcome.Unsure, 3_300)
        assertFalse(pacer.owed)
        assertFalse(pacer.shouldLook(4_000, screenChanged = false))
        assertTrue(pacer.shouldLook(8_300, screenChanged = false))
        pacer.looked(Outcome.Unsure, 8_300)
        assertFalse(pacer.shouldLook(9_000, screenChanged = false))
        // Once it loads as an assignment's, it's read like the rest of Assignments.
        assertTrue(pacer.shouldLook(13_300, screenChanged = false))
        pacer.looked(Outcome.Read, 13_300)
        assertTrue(pacer.shouldLook(14_000, screenChanged = false))
    }
}
