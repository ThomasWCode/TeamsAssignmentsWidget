package com.teamsassignments.widget.data

import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Turns the due texts Teams shows into an [Instant] and a display [DueBucket].
 *
 * Formats seen in Phase 0 (docs/teams-ui-notes.md), all en-GB and 24-hour:
 * - list card: header `28 Sept` / `1 Oct` (no year) plus `Due at 08:30`, with a relative label
 *   such as `Today`, `Wednesday` or `Due 2 days ago`;
 * - detail screen: `Due today at 08:00`, `Due tomorrow at 08:30`, `Due 30 September 2026 08:30`.
 *
 * en-US variants (`September 30, 2026 8:30 AM`) are accepted too, in case the Teams locale changes.
 */
class DueDateParser(private val clock: Clock) {

    private fun now(): ZonedDateTime = ZonedDateTime.now(clock)

    /** Parses the detail screen's due line, e.g. `Due 30 September 2026 08:30`. */
    fun parseDetail(text: String): Instant? {
        val body = normalise(text).replace(DUE_PREFIX, "")
        val today = now().toLocalDate()

        RELATIVE_DAY.matchEntire(body)?.let { m ->
            val date = relativeDay(m.groupValues[1], today) ?: return null
            return at(date, time(m, 2))
        }
        WEEKDAY.matchEntire(body)?.let { m ->
            return at(nextWeekday(m.groupValues[1], today), time(m, 2))
        }
        DAY_MONTH_YEAR_TIME.matchEntire(body)?.let { m ->
            val date = date(m.groupValues[1], m.groupValues[2], m.groupValues[3], today) ?: return null
            return at(date, time(m, 4))
        }
        MONTH_DAY_YEAR_TIME.matchEntire(body)?.let { m ->
            val date = date(m.groupValues[2], m.groupValues[1], m.groupValues[3], today) ?: return null
            return at(date, time(m, 4))
        }
        DAY_MONTH_YEAR.matchEntire(body)?.let { m ->
            val date = date(m.groupValues[1], m.groupValues[2], m.groupValues[3], today) ?: return null
            return at(date, END_OF_DAY)
        }
        return null
    }

    /**
     * Parses a list card: its group's [headerDate] (`28 Sept`), the group's [headerLabel]
     * (`Today`, `Due 2 days ago`) and the card's [dueLine] (`Due at 08:30`).
     * The header date wins; the label is the fallback.
     */
    fun parseList(headerDate: String?, headerLabel: String?, dueLine: String): Instant? {
        val today = now().toLocalDate()
        val time = LIST_DUE_LINE.matchEntire(normalise(dueLine))?.let { time(it, 1) } ?: return null

        val date = headerDate?.let(::normalise)?.let { header ->
            HEADER_DAY_MONTH.matchEntire(header)?.let { date(it.groupValues[1], it.groupValues[2], it.groupValues[3], today) }
                ?: HEADER_MONTH_DAY.matchEntire(header)?.let { date(it.groupValues[2], it.groupValues[1], it.groupValues[3], today) }
        } ?: headerLabel?.let { labelDate(normalise(it), today) } ?: return null

        return at(date, time)
    }

    fun bucket(dueAt: Instant?): DueBucket {
        if (dueAt == null) return DueBucket.NoDueDate
        val now = now()
        if (dueAt.isBefore(now.toInstant())) return DueBucket.Overdue
        val date = dueAt.atZone(clock.zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), date)
        return when {
            days <= 0L -> DueBucket.Today
            days == 1L -> DueBucket.Tomorrow
            days < 7L -> DueBucket.ThisWeek(date)
            else -> DueBucket.Later(date)
        }
    }

    private fun at(date: LocalDate, time: LocalTime?): Instant? =
        time?.let { ZonedDateTime.of(date, it, clock.zone).toInstant() }

    private fun labelDate(label: String, today: LocalDate): LocalDate? {
        LABEL_DAYS_AGO.matchEntire(label)?.let { return today.minusDays(it.groupValues[1].toLong()) }
        LABEL_RELATIVE.matchEntire(label)?.let { return relativeDay(it.groupValues[1], today) }
        LABEL_WEEKDAY.matchEntire(label)?.let { return nextWeekday(it.groupValues[1], today) }
        return null
    }

    private fun relativeDay(word: String, today: LocalDate): LocalDate? = when (word.lowercase()) {
        "today" -> today
        "tomorrow" -> today.plusDays(1)
        "yesterday" -> today.minusDays(1)
        else -> null
    }

    /** The next date (today included) that falls on [name]. */
    private fun nextWeekday(name: String, today: LocalDate): LocalDate {
        val target = DayOfWeek.valueOf(name.uppercase())
        return today.plusDays(Math.floorMod(target.value - today.dayOfWeek.value, 7).toLong())
    }

    /** Builds a date; without a year, picks the year that puts it closest to [today]. */
    private fun date(day: String, monthName: String, year: String, today: LocalDate): LocalDate? {
        val month = MONTHS[monthName.lowercase().trimEnd('.')] ?: return null
        val dayOfMonth = day.toIntOrNull() ?: return null
        val years = year.toIntOrNull()?.let { listOf(it) } ?: listOf(today.year - 1, today.year, today.year + 1)
        return years
            .mapNotNull { runCatching { LocalDate.of(it, month, dayOfMonth) }.getOrNull() }
            .minByOrNull { abs(ChronoUnit.DAYS.between(today, it)) }
    }

    /** Reads the hour, minute and optional am/pm groups starting at [first]. */
    private fun time(match: MatchResult, first: Int): LocalTime? {
        var hour = match.groupValues[first].toIntOrNull() ?: return null
        val minute = match.groupValues[first + 1].toIntOrNull() ?: return null
        val amPm = match.groupValues[first + 2]
        if (amPm.isNotEmpty()) {
            if (hour !in 1..12) return null
            hour = hour % 12 + if (amPm.equals("p", ignoreCase = true)) 12 else 0
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    private companion object {
        val END_OF_DAY: LocalTime = LocalTime.of(23, 59)

        val MONTHS = mapOf(
            "jan" to 1, "january" to 1, "feb" to 2, "february" to 2, "mar" to 3, "march" to 3,
            "apr" to 4, "april" to 4, "may" to 5, "jun" to 6, "june" to 6, "jul" to 7, "july" to 7,
            "aug" to 8, "august" to 8, "sep" to 9, "sept" to 9, "september" to 9,
            "oct" to 10, "october" to 10, "nov" to 11, "november" to 11, "dec" to 12, "december" to 12,
        )

        val WHITESPACE = Regex("[\\s\\u00A0\\u202F]+")
        fun normalise(text: String) = text.replace(WHITESPACE, " ").trim()

        const val TIME = """(\d{1,2})[:.](\d{2})(?:\s?([ap])\.?m\.?)?"""
        const val MONTH = """([a-z]{3,9}\.?)"""
        const val WEEKDAYS = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
        const val OPTIONAL_WEEKDAY = """(?:[a-z]{3,9},? )?"""
        val I = RegexOption.IGNORE_CASE

        val DUE_PREFIX = Regex("""^due\s+""", I)
        val RELATIVE_DAY = Regex("""^(today|tomorrow|yesterday),?(?: at)? $TIME$""", I)
        val WEEKDAY = Regex("""^($WEEKDAYS),?(?: at)? $TIME$""", I)
        val DAY_MONTH_YEAR_TIME = Regex("""^$OPTIONAL_WEEKDAY(\d{1,2})(?:st|nd|rd|th)? $MONTH(?: (\d{4}))?,?(?: at)? $TIME$""", I)
        val MONTH_DAY_YEAR_TIME = Regex("""^$OPTIONAL_WEEKDAY$MONTH (\d{1,2})(?:st|nd|rd|th)?(?:,? (\d{4}))?,?(?: at)? $TIME$""", I)
        val DAY_MONTH_YEAR = Regex("""^$OPTIONAL_WEEKDAY(\d{1,2})(?:st|nd|rd|th)? $MONTH(?: (\d{4}))?$""", I)

        val LIST_DUE_LINE = Regex("""^due(?: at)? $TIME$""", I)
        val HEADER_DAY_MONTH = Regex("""^$OPTIONAL_WEEKDAY(\d{1,2}) $MONTH(?: (\d{4}))?$""", I)
        val HEADER_MONTH_DAY = Regex("""^$OPTIONAL_WEEKDAY$MONTH (\d{1,2})(?:,? (\d{4}))?$""", I)
        val LABEL_DAYS_AGO = Regex("""^(?:due )?(\d{1,3}) days? ago$""", I)
        val LABEL_RELATIVE = Regex("""^(?:due )?(today|tomorrow|yesterday)$""", I)
        val LABEL_WEEKDAY = Regex("""^($WEEKDAYS)$""", I)
    }
}

/** The widget's date sections, in display order. */
sealed class DueBucket(val order: Int) : Comparable<DueBucket> {
    data object Overdue : DueBucket(0)
    data object Today : DueBucket(1)
    data object Tomorrow : DueBucket(2)
    /** Two to six days ahead, labelled with the weekday name. */
    data class ThisWeek(val date: LocalDate) : DueBucket(3)
    /** A week or more ahead, labelled like `Fri 10 Oct`. */
    data class Later(val date: LocalDate) : DueBucket(4)
    data object NoDueDate : DueBucket(5)

    override fun compareTo(other: DueBucket): Int = compareValuesBy(
        this, other, { it.order }, { (it as? ThisWeek)?.date ?: (it as? Later)?.date },
    )
}
