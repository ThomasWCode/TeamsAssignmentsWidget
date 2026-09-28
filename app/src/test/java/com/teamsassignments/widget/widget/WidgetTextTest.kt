package com.teamsassignments.widget.widget

import com.teamsassignments.widget.data.Assignment
import com.teamsassignments.widget.data.SyncStatus
import com.teamsassignments.widget.data.WidgetState
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals

class WidgetTextTest {

    /** Monday 28 Sept 2026, 14:40 BST. */
    private val clock = Clock.fixed(Instant.parse("2026-09-28T13:40:00Z"), ZoneId.of("Europe/London"))
    private fun millis(utc: String) = Instant.parse(utc).toEpochMilli()

    private fun assignment(due: String?) = Assignment(key = "k$due", title = "t", className = "c", dueAt = due?.let(::millis))

    @Test
    fun `subtitle shows progress, failure and the last sync`() {
        fun subtitle(state: WidgetState) = WidgetText.subtitle(state, clock, use24Hour = true, locale = Locale.UK)

        assertEquals("Syncing…", subtitle(WidgetState(status = SyncStatus.Running())))
        assertEquals("Syncing 3/7…", subtitle(WidgetState(status = SyncStatus.Running(3, 7))))
        assertEquals(
            "Last sync failed: Couldn't read the Past due list",
            subtitle(WidgetState(status = SyncStatus.Failed("Couldn't read the Past due list"))),
        )
        assertEquals("Tap ↻ to sync with Teams", subtitle(WidgetState()))
        assertEquals(
            "Updated 14:32 · 2 due",
            subtitle(WidgetState(assignments = listOf(assignment(null), assignment(null)), lastSuccessAt = millis("2026-09-28T13:32:00Z"))),
        )
        assertEquals("Updated 14:32 · nothing due", subtitle(WidgetState(lastSuccessAt = millis("2026-09-28T13:32:00Z"))))
    }

    @Test
    fun `a sync the user stopped keeps the last update time`() {
        fun subtitle(state: WidgetState) = WidgetText.subtitle(state, clock, use24Hour = true, locale = Locale.UK)
        val synced = WidgetState(lastSuccessAt = millis("2026-09-28T13:32:00Z"))

        assertEquals("Sync cancelled · updated 14:32", subtitle(synced.copy(status = SyncStatus.Stopped(cancelled = true))))
        assertEquals("Sync stopped · updated 14:32", subtitle(synced.copy(status = SyncStatus.Stopped(cancelled = false))))
        assertEquals("Sync cancelled", subtitle(WidgetState(status = SyncStatus.Stopped(cancelled = true))))
    }

    @Test
    fun `when text gets coarser with age`() {
        fun text(utc: String, h24: Boolean = true) = WidgetText.whenText(millis(utc), clock, h24, Locale.UK)
        assertEquals("09:05", text("2026-09-28T08:05:00Z"))
        assertEquals("9:05 am", text("2026-09-28T08:05:00Z", h24 = false).lowercase())
        assertEquals("Sat 18:00", text("2026-09-26T17:00:00Z"))
        assertEquals("12 Sept".take(6), text("2026-09-12T17:00:00Z").take(6)) // "12 Sep" or "12 Sept" by CLDR version
    }

    @Test
    fun `description preview is one line`() {
        assertEquals(
            "Dear all, Complete the Dr. Frost on Forces. Thanks, Mr RR.",
            WidgetText.descriptionPreview("Dear all,\nComplete the Dr. Frost on Forces.\n\n  Thanks,\nMr RR."),
        )
    }

    @Test
    fun `redraws at midnight or just after the next deadline`() {
        val midnight = millis("2026-09-28T23:00:00Z") // 00:00 BST
        assertEquals(midnight, WidgetText.nextRedrawAt(emptyList(), clock))
        assertEquals(midnight, WidgetText.nextRedrawAt(listOf(assignment("2026-09-29T07:30:00Z")), clock))
        // A deadline this evening comes first; ones already past don't count.
        assertEquals(
            millis("2026-09-28T22:59:01Z"),
            WidgetText.nextRedrawAt(
                listOf(assignment("2026-09-27T22:59:00Z"), assignment("2026-09-28T22:59:00Z"), assignment(null)),
                clock,
            ),
        )
    }
}
