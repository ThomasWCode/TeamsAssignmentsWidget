package com.teamsassignments.widget.notices

/**
 * Which of Teams' notifications are about assignments: work set, changed or handed back, which
 * the list may not show yet. The wording is matched, not the channel: Teams makes its channels'
 * ids at run time, and they say nothing of the app behind a notice. Assignments' notices are taken
 * to name the word ("New assignment", "Assignments" as the app's name); none had been captured on
 * the phone when this was written, so [TeamsNoticeListener] logs each Teams notice's channel and
 * whether it matched, for the rule to be checked against the first real one. A chat message that
 * names an assignment costs a sync at most, and the app that asks for syncs paces them.
 *
 * Pure, so it is tested on the JVM; [TeamsNoticeListener] hands it each notification's text.
 */
object TeamsNotices {

    /** What a notification says, as Android gives it: each of its texts, any of them empty. */
    data class Notice(
        val channelId: String,
        val title: String = "",
        val text: String = "",
        val bigText: String = "",
        val subText: String = "",
        val summaryText: String = "",
        val conversationTitle: String = "",
        /** A group's summary repeats the notifications under it, so it is never one of its own. */
        val groupSummary: Boolean = false,
    )

    private val ASSIGNMENT = Regex("""\bassignments?\b""", RegexOption.IGNORE_CASE)

    /** Whether [notice] is about assignments. */
    fun aboutAssignments(notice: Notice): Boolean {
        if (notice.groupSummary) return false
        return listOf(notice.title, notice.text, notice.bigText, notice.subText, notice.summaryText, notice.conversationTitle)
            .any { ASSIGNMENT.containsMatchIn(it) }
    }
}
