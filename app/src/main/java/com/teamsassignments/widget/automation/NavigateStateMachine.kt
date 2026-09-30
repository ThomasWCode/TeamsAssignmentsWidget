package com.teamsassignments.widget.automation

import com.teamsassignments.widget.automation.TeamsSelectors.Tab
import com.teamsassignments.widget.automation.TeamsSelectors.toAssignmentTab
import com.teamsassignments.widget.data.Assignment

/**
 * The row-tap workflow: open Teams Assignments and open [Assignment]'s card.
 *
 * Teams exposes no per-assignment deep link without the class id, so this finds the card by its
 * GUID, trying the tab it was last seen on first (a deadline may have moved it to Past due since).
 * Returns false when the card can't be found; Teams is then left on the Assignments list, and
 * [notListed] says whether the assignment is plainly on neither open tab.
 */
class NavigateStateMachine(
    device: TeamsDevice,
    // Room to read both tabs to the sync's standard, an empty one that had work included.
    config: AutomationConfig = AutomationConfig(globalTimeoutMs = 25_000),
    now: () -> Long,
    log: (String) -> Unit = {},
    isCancelled: () -> Boolean = { false },
    private val wallClock: () -> Long = System::currentTimeMillis,
) : TeamsAutomation(device, config, now, log, isCancelled) {

    /**
     * After a [run] that found nothing: whether both open tabs were read in full and neither lists
     * the assignment, so the caller may take it as handed in. In full means a list loaded by the
     * sync's standard (see [awaitSettledList]) and whole in the tree
     * ([TeamsScreens.wholeListInTree]): a list that isn't never counts, since this doesn't scroll
     * right through it. Work falling due around the search may just have moved between the tabs,
     * so it never counts as missing either.
     */
    var notListed = false
        private set

    /**
     * After a [run]: the title the list showed for the card it went to, unless that card was
     * collapsed (its title is then cut out of its text, and may be off).
     */
    var listedTitle: String? = null
        private set

    /** [saved] is the whole saved list: as in a sync, a tab that had work must stay empty for longer. */
    suspend fun run(target: Assignment, saved: List<Assignment> = emptyList()): Boolean {
        begin()
        notListed = false
        listedTitle = null
        val searchStart = wallClock()
        log("Opening \"${target.title}\"")
        openAssignments()

        // A GUID identifies exactly one assignment. Matching by title is only for the rare card
        // saved without one: with a GUID, a same-titled card (weekly "Prep") must never stand in.
        val hasGuid = TeamsSelectors.CARD_ID.matches(target.key)
        val first = TeamsSelectors.tabFor(target.tab)
        val tabs = listOf(first) + TeamsSelectors.OPEN_TABS.filter { it != first }
        var previousTab = emptyList<ListCard>()
        val missingFrom = mutableSetOf<Tab>()
        for (tab in tabs) {
            val hadWork = saved.any { it.tab == tab.toAssignmentTab() }
            val cards = try {
                selectTab(tab, previousTab, if (hadWork) config.suspectEmptySettleMs else config.emptySettleMs)
            } catch (_: StepTimeout) {
                continue
            }
            previousTab = cards
            if (hasGuid && cards.none { it.id == target.key } && readInFull(cards)) missingFrom += tab
            val id = if (hasGuid) {
                target.key
            } else {
                cards.firstOrNull { it.title == target.title && (it.className == target.className || it.collapsed) }?.id
                    ?: continue
            }
            listedTitle = cards.firstOrNull { it.id == id && !it.collapsed }?.title
            // openCard scrolls for a card that isn't in the tree (should the list be virtualised)
            // and checks the detail screen against the title the card shows now, so a card renamed
            // since the last sync still opens; the saved title is only a fallback.
            val detail = try {
                openCard(id, target.title)
            } catch (_: StepTimeout) {
                null
            }
            if (detail != null) {
                log("Opened \"${detail.title}\"")
                return true
            }
        }
        val dueDuringSearch = target.dueAt?.let {
            it in (searchStart - config.movedTabsBeforeMs)..(wallClock() + config.movedTabsAfterMs)
        } == true
        notListed = missingFrom.containsAll(TeamsSelectors.OPEN_TABS) && !dueDuringSearch
        log(if (notListed) "\"${target.title}\" is on neither Forthcoming nor Past due" else "Couldn't find \"${target.title}\"")
        return false
    }

    /**
     * Whether the list [cards] was read from is known whole: all of it in the tree, and plainly its
     * own tab's. Handed-in cards would be Completed's rows, still showing after a tab switch.
     */
    private fun readInFull(cards: List<ListCard>): Boolean {
        val root = device.teamsRoot() ?: return false
        return TeamsScreens.wholeListInTree(root) && cards.none { it.isHandedIn }
    }
}
