package com.teamsassignments.widget.automation

/**
 * Reads One UI's Recents screen (the launcher's `RecentsActivity`), captured on the phone in
 * 0.7.0. Each app's card is a `taskView` node named with the app's label, with a click action
 * that brings the app back as it was and a "Close" action that removes it, as swiping it away
 * does. "Close all" is a separate button (`clear_all`), never a card.
 */
object RecentsScreen {
    const val CARD_ID = "taskView"
    const val CLOSE_ACTION = "Close"

    /** The containers only the Recents screen has, not the home screen of the same launcher. */
    private val RECENTS_IDS = setOf("recentContainer", "taskListContainer")

    data class Card(val label: String, val node: UiNode)

    fun isRecents(root: UiNode): Boolean = root.walk().any { it.viewId in RECENTS_IDS }

    /** Every app's card, in the order the screen lists them. */
    fun cards(root: UiNode): List<Card> =
        root.walk().filter { it.viewId == CARD_ID }.map { Card(it.contentDescription.trim(), it) }.toList()

    /** The cards named exactly [label] (a pair in split screen is named for both apps, so not one of them). */
    fun cardsOf(root: UiNode, label: String): List<Card> = cards(root).filter { it.label == label }
}
