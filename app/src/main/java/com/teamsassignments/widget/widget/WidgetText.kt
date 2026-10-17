package com.teamsassignments.widget.widget

import com.teamsassignments.widget.data.Assignment
import com.teamsassignments.widget.data.SyncStatus
import com.teamsassignments.widget.data.WidgetState
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The widget's words and timing, kept free of Android types so they can be unit tested. */
object WidgetText {

    /**
     * The line under the title: sync progress, the last failure or stop, or when the list was last
     * known up to date ([WidgetState.updatedAt]): the last sync, or reading along seeing both
     * open lists in full since.
     */
    fun subtitle(state: WidgetState, clock: Clock, use24Hour: Boolean, locale: Locale = Locale.getDefault()): String =
        when (val status = state.status) {
            is SyncStatus.Running -> if (status.total > 0) "Syncing ${status.done}/${status.total}…" else "Syncing…"
            is SyncStatus.Failed -> failure(state)?.let { "Last sync failed: ${it.reason}" } ?: updated(state, clock, use24Hour, locale)
            // A stop the user chose keeps the time, since the list is still as it was then.
            is SyncStatus.Stopped -> state.updatedAt
                ?.let { "${status.summary} · updated ${whenText(it, clock, use24Hour, locale)}" }
                ?: status.summary
            SyncStatus.Idle -> updated(state, clock, use24Hour, locale)
        }

    /**
     * The last sync's failure, unless both open lists have been seen in full in Teams since
     * ([WidgetState.listsSeenAt]): the list is up to date despite it, so the widget says so.
     */
    fun failure(state: WidgetState): SyncStatus.Failed? =
        (state.status as? SyncStatus.Failed)?.takeIf { failed -> state.listsSeenAt?.let { it > failed.at } != true }

    private fun updated(state: WidgetState, clock: Clock, use24Hour: Boolean, locale: Locale): String =
        state.updatedAt
            ?.let { "Updated ${whenText(it, clock, use24Hour, locale)} · ${dueCount(state.assignments.size)}" }
            ?: "Tap ↻ to sync with Teams"

    private fun dueCount(count: Int) = if (count == 0) "nothing due" else "$count due"

    /** `14:32` today, `Mon 14:32` within the last week, `12 Oct` before that. */
    fun whenText(epochMillis: Long, clock: Clock, use24Hour: Boolean, locale: Locale = Locale.getDefault()): String {
        val time = Instant.ofEpochMilli(epochMillis).atZone(clock.zone)
        val clockPattern = if (use24Hour) "HH:mm" else "h:mm a"
        val days = ChronoUnit.DAYS.between(time.toLocalDate(), LocalDate.now(clock))
        val pattern = when {
            days <= 0L -> clockPattern
            days < 7L -> "EEE $clockPattern"
            else -> "d MMM"
        }
        return DateTimeFormatter.ofPattern(pattern, locale).format(time)
    }

    /** Instructions squeezed onto one line for a row's preview. */
    fun descriptionPreview(description: String): String =
        description.lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString(" ")

    /**
     * When the widget next needs redrawing without a sync: the next midnight, when "Tomorrow"
     * becomes "Today", or just after the next deadline, when a row becomes overdue.
     */
    fun nextRedrawAt(assignments: List<Assignment>, clock: Clock): Long {
        val now = clock.millis()
        val midnight = LocalDate.now(clock).plusDays(1).atStartOfDay(clock.zone).toInstant().toEpochMilli()
        val nextDeadline = assignments.mapNotNull { it.dueAt }.filter { it > now }.minOrNull()
        return minOf(midnight, nextDeadline?.plus(DEADLINE_SLACK_MS) ?: Long.MAX_VALUE)
    }

    private const val DEADLINE_SLACK_MS = 1_000L
}
