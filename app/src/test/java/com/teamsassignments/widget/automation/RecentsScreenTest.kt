package com.teamsassignments.widget.automation

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Recents screen as One UI showed it on the phone, opened from Teams (other apps' names made up). */
class RecentsScreenTest {

    private val recents = Fixtures.loadFrom("recents", "recents_from_teams")

    @Test
    fun `reads every app's card by its name, in order`() {
        assertEquals(listOf("Notes", "Music", "Teams"), RecentsScreen.cards(recents).map { it.label })
    }

    @Test
    fun `each card has a Close action`() {
        RecentsScreen.cards(recents).forEach { assertEquals(listOf(RecentsScreen.CLOSE_ACTION), it.node.customActions) }
    }

    @Test
    fun `finds Teams' card by its exact name only`() {
        assertEquals(1, RecentsScreen.cardsOf(recents, "Teams").size)
        assertTrue(RecentsScreen.cardsOf(recents, "Teams Assignments").isEmpty())
        assertTrue(RecentsScreen.cardsOf(recents, "Team").isEmpty())
    }

    @Test
    fun `Close all and the suggested apps are never cards`() {
        val labels = RecentsScreen.cards(recents).map { it.node.label }
        assertFalse("Close all" in labels)
        assertFalse("Store" in labels)
        assertTrue(recents.walk().any { it.text == "Close all" })
    }

    @Test
    fun `tells Recents apart from a Teams screen`() {
        assertTrue(RecentsScreen.isRecents(recents))
        assertFalse(RecentsScreen.isRecents(Fixtures.load("list_forthcoming")))
    }
}
