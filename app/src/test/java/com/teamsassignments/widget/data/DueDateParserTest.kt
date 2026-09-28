package com.teamsassignments.widget.data

import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DueDateParserTest {

    private val london: ZoneId = ZoneId.of("Europe/London")

    /** Monday 28 Sept 2026, 06:40 BST: when the Phase 0 fixtures were captured. */
    private val parser = DueDateParser(Clock.fixed(Instant.parse("2026-09-28T05:40:00Z"), london))

    private fun utc(text: String): Instant = Instant.parse(text)

    // Detail screen formats (docs/teams-ui-notes.md)

    @Test
    fun `detail - today and tomorrow`() {
        assertEquals(utc("2026-09-28T07:00:00Z"), parser.parseDetail("Due today at 08:00"))
        assertEquals(utc("2026-09-29T07:30:00Z"), parser.parseDetail("Due tomorrow at 08:30"))
        assertEquals(utc("2026-09-29T22:59:00Z"), parser.parseDetail("Due tomorrow at 23:59"))
        assertEquals(utc("2026-09-27T22:59:00Z"), parser.parseDetail("Due yesterday at 23:59"))
    }

    @Test
    fun `detail - full date with year`() {
        assertEquals(utc("2026-09-30T07:30:00Z"), parser.parseDetail("Due 30 September 2026 08:30"))
        assertEquals(utc("2026-10-01T07:30:00Z"), parser.parseDetail("Due 1 October 2026 08:30"))
        assertEquals(utc("2026-09-25T22:59:00Z"), parser.parseDetail("Due 25 September 2026 23:59"))
        assertEquals(utc("2026-09-17T07:30:00Z"), parser.parseDetail("Due 17 September 2026 08:30"))
    }

    @Test
    fun `detail - dates after the clocks go back use GMT`() {
        assertEquals(utc("2026-11-03T09:00:00Z"), parser.parseDetail("Due 3 November 2026 09:00"))
    }

    @Test
    fun `detail - tolerates non-breaking spaces and other spellings`() {
        assertEquals(utc("2026-09-30T07:30:00Z"), parser.parseDetail("Due 30 September 2026 08:30 "))
        assertEquals(utc("2026-09-30T07:30:00Z"), parser.parseDetail("Due 30 Sept 2026 08:30"))
        assertEquals(utc("2026-09-30T07:30:00Z"), parser.parseDetail("Due Wednesday at 08:30"))
        assertEquals(utc("2026-09-30T07:30:00Z"), parser.parseDetail("Due September 30, 2026 8:30 AM"))
        assertEquals(utc("2026-09-30T22:59:00Z"), parser.parseDetail("Due Sep 30, 2026, 11:59 PM"))
        assertEquals(utc("2026-09-30T22:59:00Z"), parser.parseDetail("Due 30 September 2026"))
    }

    @Test
    fun `detail - rejects other text`() {
        assertNull(parser.parseDetail("Multiple submissions allowed"))
        assertNull(parser.parseDetail("Due soon"))
        assertNull(parser.parseDetail("Due 31 February 2026 08:00"))
        assertNull(parser.parseDetail("Due 30 September 2026 25:00"))
    }

    // List card formats

    @Test
    fun `list - header date plus due time`() {
        assertEquals(utc("2026-09-28T07:00:00Z"), parser.parseList("28 Sept", "Today", "Due at 08:00"))
        assertEquals(utc("2026-09-29T07:30:00Z"), parser.parseList("29 Sept", "Tomorrow", "Due at 08:30"))
        assertEquals(utc("2026-10-01T07:30:00Z"), parser.parseList("1 Oct", "Thursday", "Due at 08:30"))
        assertEquals(utc("2026-09-25T22:59:00Z"), parser.parseList("25 Sept", "Due 2 days ago", "Due at 23:59"))
        assertEquals(utc("2026-09-17T07:30:00Z"), parser.parseList("17 Sept", "Due 11 days ago", "Due at 08:30"))
    }

    @Test
    fun `list - falls back to the relative label`() {
        assertEquals(utc("2026-09-29T08:00:00Z"), parser.parseList(null, "Tomorrow", "Due at 09:00"))
        assertEquals(utc("2026-09-21T08:00:00Z"), parser.parseList(null, "Due 7 days ago", "Due at 09:00"))
        assertEquals(utc("2026-09-30T07:30:00Z"), parser.parseList("??", "Wednesday", "Due at 08:30"))
    }

    @Test
    fun `list - rejects handed-in rows and missing dates`() {
        assertNull(parser.parseList("28 Sept", "Today", "Submitted at 09:18"))
        assertNull(parser.parseList(null, null, "Due at 09:00"))
    }

    @Test
    fun `list - infers the nearest year across new year`() {
        val december = DueDateParser(Clock.fixed(Instant.parse("2026-12-30T12:00:00Z"), london))
        assertEquals(utc("2027-01-03T09:00:00Z"), december.parseList("3 Jan", null, "Due at 09:00"))

        val january = DueDateParser(Clock.fixed(Instant.parse("2027-01-02T12:00:00Z"), london))
        assertEquals(utc("2026-12-30T09:00:00Z"), january.parseList("30 Dec", "Due 3 days ago", "Due at 09:00"))
    }

    // Buckets

    @Test
    fun `bucket - groups by day relative to now`() {
        assertEquals(DueBucket.Overdue, parser.bucket(utc("2026-09-28T05:00:00Z")))
        assertEquals(DueBucket.Today, parser.bucket(utc("2026-09-28T07:00:00Z")))
        assertEquals(DueBucket.Tomorrow, parser.bucket(utc("2026-09-29T07:30:00Z")))
        assertEquals(DueBucket.ThisWeek(LocalDate.of(2026, 9, 30)), parser.bucket(utc("2026-09-30T07:30:00Z")))
        assertEquals(DueBucket.ThisWeek(LocalDate.of(2026, 10, 4)), parser.bucket(utc("2026-10-04T10:00:00Z")))
        assertEquals(DueBucket.Later(LocalDate.of(2026, 10, 5)), parser.bucket(utc("2026-10-05T07:00:00Z")))
        assertEquals(DueBucket.NoDueDate, parser.bucket(null))
    }

    @Test
    fun `bucket - late tonight is still today`() {
        assertEquals(DueBucket.Today, parser.bucket(utc("2026-09-28T22:59:00Z")))
    }
}
