package com.teamsassignments.widget.notices

import com.teamsassignments.widget.notices.TeamsNotices.Notice
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeamsNoticesTest {

    @Test
    fun `a notice naming an assignment in any of its texts is about assignments`() {
        assertTrue(TeamsNotices.aboutAssignments(Notice("c", title = "12.2-PH3", text = "New assignment: Rubber bands plan")))
        assertTrue(TeamsNotices.aboutAssignments(Notice("c", title = "Assignments", text = "Rubber bands plan is due tomorrow")))
        assertTrue(TeamsNotices.aboutAssignments(Notice("c", title = "12-GE", text = "Vocabulary 1.2", subText = "Assignments")))
        assertTrue(TeamsNotices.aboutAssignments(Notice("c", bigText = "Your teacher returned the ASSIGNMENT with feedback")))
        assertTrue(TeamsNotices.aboutAssignments(Notice("c", summaryText = "2 assignments")))
        assertTrue(TeamsNotices.aboutAssignments(Notice("c", conversationTitle = "Assignments")))
        assertTrue(TeamsNotices.aboutAssignments(Notice("c", title = "Teams", lines = "A friend: hi\nNew assignment: Essay")))
    }

    @Test
    fun `other notices aren't, nor a word that only contains it`() {
        assertFalse(TeamsNotices.aboutAssignments(Notice("c", title = "A friend", text = "see you at lunch")))
        assertFalse(TeamsNotices.aboutAssignments(Notice("c", title = "Meeting", text = "Starts in 5 minutes")))
        assertFalse(TeamsNotices.aboutAssignments(Notice("c", text = "reassignments of rooms")))
        assertFalse(TeamsNotices.aboutAssignments(Notice("c")))
    }

    @Test
    fun `a group's summary never counts, as it repeats the notices under it`() {
        assertFalse(TeamsNotices.aboutAssignments(Notice("c", title = "Assignments", text = "New assignment", groupSummary = true)))
    }
}
