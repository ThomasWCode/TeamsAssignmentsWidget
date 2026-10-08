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
     * ([TeamsScreens.wholeListInTree]), judged again after the search for the card, which loads
     * the rest of a paged list: a list that still isn't whole never counts, since this doesn't
     * scroll right through it. Work falling due around the search may just have moved between the tabs,
     * so it never counts as missing either, and nor does anything when both tabs showed the very
     * same cards (one tab's rows, still showing after the switch).
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
        // Each tab read in full without the target, and the cards it showed.
        val missingFrom = mutableMapOf<Tab, Set<String>>()
        for (tab in tabs) {
            val hadWork = saved.any { it.tab == tab.toAssignmentTab() }
            val cards = try {
                selectTab(tab, previousTab, if (hadWork) config.suspectEmptySettleMs else config.emptySettleMs)
            } catch (_: StepTimeout) {
                continue
            }
            previousTab = cards
            if (hasGuid && cards.none { it.id == target.key } && readInFull(cards)) missingFrom[tab] = cards.map { it.id }.toSet()
            val id = if (hasGuid) {
                target.key
            } else {
                // Saved without a Teams id: found by title and class, on a later page should Teams page the list.
                val matches: (ListCard) -> Boolean = { it.title == target.title && (it.className == target.className || it.collapsed) }
                (cards.firstOrNull(matches) ?: pagedFor(tab, cards, matches))?.id ?: continue
            }
            // openCard loads the rest of a paged list, or scrolls, for a card that isn't in the tree,
            // and checks the detail screen against the title the card shows now, so a card renamed
            // since the last sync still opens; the saved title is only a fallback.
            val detail = try {
                openCard(id, target.title)
            } catch (_: StepTimeout) {
                null
            }
            if (detail != null) {
                // As the list showed it, whichever page it was on.
                listedTitle = listedCard?.takeIf { !it.collapsed }?.title
                log("Opened \"${detail.title}\"")
                return true
            }
            // The search may have loaded the rest of a paged list: judge the tab again as it stands
            // now, and take all its rows as the ones the next tab mustn't be mistaken for.
            val now = device.teamsRoot()?.takeIf { TeamsScreens.selectedTab(it) == tab && TeamsScreens.isList(it) }
            val all = now?.let(TeamsScreens::cards) ?: continue
            previousTab = all
            if (hasGuid && tab !in missingFrom && all.none { it.id == target.key } && readInFull(all)) missingFrom[tab] = all.map { it.id }.toSet()
        }
        val dueDuringSearch = target.dueAt?.let {
            it in (searchStart - config.movedTabsBeforeMs)..(wallClock() + config.movedTabsAfterMs)
        } == true
        // A spinner over the last tab's rows counts as the list changing, so rows that then stay put
        // can pass as the new tab's; as in TeamsObserver.bothInFull, identical tabs prove nothing.
        val sameCards = missingFrom.values.distinct().size == 1 && missingFrom.values.first().isNotEmpty()
        notListed = missingFrom.keys.containsAll(TeamsSelectors.OPEN_TABS) && !dueDuringSearch && !sameCards
        log(if (notListed) "\"${target.title}\" is on neither Forthcoming nor Past due" else "Couldn't find \"${target.title}\"")
        return false
    }

    /**
     * For work saved without a Teams id: the first card on [tab]'s list that [matches], loading
     * the rest of the list when Teams pages it ([collectRest]). Null if there's none, or Teams
     * pages nothing more in; what did load is still searched.
     */
    private suspend fun pagedFor(tab: Tab, cards: List<ListCard>, matches: (ListCard) -> Boolean): ListCard? {
        val root = device.teamsRoot() ?: return null
        if (!TeamsScreens.loadMorePending(root)) return null
        val all = try {
            collectRest(tab, cards) { TeamsScreens.cards(it).any(matches) }
        } catch (_: StepTimeout) {
            device.teamsRoot()?.let(TeamsScreens::cards).orEmpty()
        }
        return all.firstOrNull(matches)
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
