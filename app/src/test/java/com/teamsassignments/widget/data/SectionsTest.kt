package com.teamsassignments.widget.data

import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SectionsTest {

    private val clock = Clock.fixed(Instant.parse("2026-09-28T05:40:00Z"), ZoneId.of("Europe/London"))
    private val parser = DueDateParser(clock)
    private val formatter = DueFormatter(clock, Locale.UK, use24Hour = true)

    private fun assignment(title: String, due: String?) = Assignment(
        key = title,
        title = title,
        className = "Class",
        dueAt = due?.let { Instant.parse(it).toEpochMilli() },
    )

    @Test
    fun `sections come out in time order with undated work last`() {
        val sections = groupIntoSections(
            listOf(
                assignment("No date", null),
                assignment("Wednesday work", "2026-09-30T07:30:00Z"),
                assignment("Old", "2026-09-17T07:30:00Z"),
                assignment("Tomorrow B", "2026-09-29T08:00:00Z"),
                assignment("Tomorrow A", "2026-09-29T08:00:00Z"),
                assignment("Next week", "2026-10-06T07:30:00Z"),
                assignment("Today", "2026-09-28T07:00:00Z"),
                assignment("Recent", "2026-09-25T22:59:00Z"),
            ),
            parser,
        )

        assertEquals(
            listOf(
                DueBucket.Overdue,
                DueBucket.Today,
                DueBucket.Tomorrow,
                DueBucket.ThisWeek(LocalDate.of(2026, 9, 30)),
                DueBucket.Later(LocalDate.of(2026, 10, 6)),
                DueBucket.NoDueDate,
            ),
            sections.map { it.bucket },
        )
        assertEquals(listOf("Old", "Recent"), sections[0].assignments.map { it.title })
        assertEquals(listOf("Tomorrow A", "Tomorrow B"), sections[2].assignments.map { it.title })
    }

    @Test
    fun `section titles`() {
        assertEquals("Overdue", formatter.sectionTitle(DueBucket.Overdue))
        assertEquals("Tomorrow", formatter.sectionTitle(DueBucket.Tomorrow))
        assertEquals("Wednesday", formatter.sectionTitle(DueBucket.ThisWeek(LocalDate.of(2026, 9, 30))))
        assertEquals("Fri 9 Oct", formatter.sectionTitle(DueBucket.Later(LocalDate.of(2026, 10, 9))))
    }

    @Test
    fun `rows show just the time unless overdue`() {
        val tomorrow = assignment("t", "2026-09-29T07:30:00Z")
        assertEquals("08:30", formatter.rowDue(tomorrow, DueBucket.Tomorrow))

        val yesterday = assignment("y", "2026-09-27T22:59:00Z")
        assertEquals("Yesterday 23:59", formatter.rowDue(yesterday, DueBucket.Overdue))

        val earlierToday = assignment("e", "2026-09-28T05:00:00Z")
        assertEquals("Today 06:00", formatter.rowDue(earlierToday, DueBucket.Overdue))

        // en-GB abbreviates September as "Sep" or "Sept" depending on the CLDR version.
        val older = formatter.rowDue(assignment("o", "2026-09-25T22:59:00Z"), DueBucket.Overdue)
        assertTrue(older.startsWith("Fri 25 Sep") && older.endsWith(" 23:59"), older)
    }

    @Test
    fun `undated rows fall back to the raw due text`() {
        val raw = Assignment(key = "k", title = "t", className = "c", dueText = "Due some day")
        assertEquals("some day", formatter.rowDue(raw, DueBucket.NoDueDate))
    }
}
