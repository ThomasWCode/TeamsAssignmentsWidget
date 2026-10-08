package com.teamsassignments.widget.data

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** A date group in the widget: a header and its assignments. */
data class Section(val bucket: DueBucket, val assignments: List<Assignment>)

/** The widget's order: by due time, undated ones last, then by title. The provider uses it too. */
val widgetOrder: Comparator<Assignment> =
    compareBy<Assignment> { it.dueAt == null }
        .thenBy { it.dueAt ?: 0L }
        .thenBy { it.title.lowercase() }

/**
 * Sorts assignments into [widgetOrder] and groups them into [DueBucket]s.
 * Buckets only move forward in time, so every group comes out contiguous and in display order.
 */
fun groupIntoSections(assignments: List<Assignment>, parser: DueDateParser): List<Section> =
    assignments
        .sortedWith(widgetOrder)
        .groupBy { parser.bucket(it.dueAt?.let(Instant::ofEpochMilli)) }
        .map { (bucket, items) -> Section(bucket, items) }

/** Formats section headers and each row's due text for the widget. */
class DueFormatter(
    private val clock: Clock,
    private val locale: Locale = Locale.getDefault(),
    use24Hour: Boolean = true,
) {
    private val timeFormat = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale)
    private val shortDateFormat = DateTimeFormatter.ofPattern("EEE d MMM", locale)

    fun sectionTitle(bucket: DueBucket): String = when (bucket) {
        DueBucket.Overdue -> "Overdue"
        DueBucket.Today -> "Today"
        DueBucket.Tomorrow -> "Tomorrow"
        is DueBucket.ThisWeek -> bucket.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        is DueBucket.Later -> shortDateFormat.format(bucket.date)
        DueBucket.NoDueDate -> "No due date"
    }

    /**
     * The due part of a row's subtitle. The section header already names the day, so this is
     * just the time, except for overdue rows, which share one header and so also need the day
     * (`Yesterday 23:59`, `Thu 25 Sept 23:59`).
     */
    fun rowDue(assignment: Assignment, bucket: DueBucket): String {
        val dueAt = assignment.dueAt?.let(Instant::ofEpochMilli)?.atZone(clock.zone)
            ?: return assignment.dueText.removePrefix("Due ").trim()
        val time = timeFormat.format(dueAt)
        if (bucket != DueBucket.Overdue) return time
        val today = LocalDate.now(clock)
        val day = when (dueAt.toLocalDate()) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> shortDateFormat.format(dueAt)
        }
        return "$day $time"
    }

    fun time(epochMillis: Long): String = timeFormat.format(Instant.ofEpochMilli(epochMillis).atZone(clock.zone))
}
