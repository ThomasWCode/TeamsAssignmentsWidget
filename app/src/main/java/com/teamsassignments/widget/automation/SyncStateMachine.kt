package com.teamsassignments.widget.automation

import com.teamsassignments.widget.automation.TeamsSelectors.Tab
import com.teamsassignments.widget.automation.TeamsSelectors.toAssignmentTab
import com.teamsassignments.widget.data.Assignment
import com.teamsassignments.widget.data.DueDateParser
import java.util.concurrent.TimeUnit

/**
 * The refresh workflow:
 * 1. open Teams Assignments;
 * 2. read the Forthcoming and Past due lists (both mean "not handed in");
 * 3. open each assignment to read its class, exact due time and instructions, then go Back.
 *    Details read in the last [detailReuseMs] are reused while the row is unchanged, which keeps
 *    a routine refresh to a few seconds. A full sync re-reads everything.
 *
 * A row that won't open is retried once, then kept with its list data. Anything that ends the
 * whole run throws [SyncAbort], and the caller keeps the previous list.
 */
class SyncStateMachine(
    device: TeamsDevice,
    private val parser: DueDateParser,
    private val wallClock: () -> Long,
    private val onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    config: AutomationConfig = AutomationConfig(),
    now: () -> Long,
    log: (String) -> Unit = {},
    isCancelled: () -> Boolean = { false },
    private val detailReuseMs: Long = TimeUnit.DAYS.toMillis(3),
) : TeamsAutomation(device, config, now, log, isCancelled) {

    private data class Listed(val tab: Tab, val card: ListCard)

    suspend fun run(previous: List<Assignment>, full: Boolean = false): List<Assignment> {
        begin()
        log(if (full) "Full sync started" else "Sync started")
        openAssignments()

        val listed = collectLists(previous)
        val previousByKey = previous.associateBy { it.key }
        // Start with the tab that is already open, to save a switch.
        val openTab = device.teamsRoot()?.let(TeamsScreens::selectedTab)
        val ordered = listed.sortedBy { if (it.tab == openTab) 0 else 1 }

        val results = mutableListOf<Assignment>()
        onProgress(0, ordered.size)
        for ((index, item) in ordered.withIndex()) {
            checkAbort()
            val (tab, card) = item
            val listDueAt = parser.parseList(card.headerDate, card.headerLabel, card.dueLine)?.toEpochMilli()
            val old = previousByKey[card.id]
            val assignment = if (!full && old != null && isFresh(old, card, listDueAt)) {
                log("Kept saved details for \"${card.title}\"")
                old.copy(tab = tab.toAssignmentTab(), lastSyncedAt = wallClock())
            } else {
                readDetail(tab, card, listDueAt, old)
            }
            assignment?.let(results::add)
            onProgress(index + 1, ordered.size)
        }
        log("Sync finished: ${results.size} assignments")
        return results
    }

    /**
     * Reads both open tabs. A card listed on both counts once, under the later tab, Past due,
     * which is where it now lives. Teams lists work that fell due earlier today on both, and a
     * deadline can also pass between the two reads.
     */
    private suspend fun collectLists(previous: List<Assignment>): List<Listed> {
        val found = LinkedHashMap<String, Listed>()
        var previousTab = emptyList<ListCard>()
        for (tab in TeamsSelectors.OPEN_TABS) {
            val cards = try {
                // A tab that had work at the last sync and now looks empty may just be slow to load.
                val hadWork = previous.any { it.tab == tab.toAssignmentTab() }
                val emptySettle = if (hadWork) config.suspectEmptySettleMs else config.emptySettleMs
                selectTab(tab, previousTab, emptySettle).let { collectMore(tab, it) }
            } catch (_: StepTimeout) {
                throw SyncAbort("Couldn't read the ${tab.label} list")
            }
            previousTab = cards
            val open = cards.filterNot { it.isHandedIn }
            log("${tab.label}: ${open.size} not handed in")
            open.forEach { found[it.id] = Listed(tab, it) }
        }
        return found.values.toList()
    }

    /**
     * Teams currently puts every card in the tree, off-screen ones included. In case a future
     * version virtualises the list (where every row in the tree *is* on screen), scroll down while
     * new cards keep appearing, then back up. Skipped only when the list visibly ends on screen.
     */
    private suspend fun collectMore(tab: Tab, firstPage: List<ListCard>): List<ListCard> {
        val root = device.teamsRoot() ?: return firstPage
        if (listEndsOnScreen(root, firstPage)) return firstPage
        val cards = LinkedHashMap<String, ListCard>().apply { firstPage.forEach { put(it.id, it) } }
        var scrolls = 0
        while (scrolls < config.maxScrolls) {
            val scroller = device.teamsRoot()?.walk()?.firstOrNull { it.isScrollable } ?: break
            if (!scroller.perform(UiAction.ScrollForward)) break
            scrolls++
            val page = awaitSettledList(tab)
            val before = cards.size
            page.forEach { cards.putIfAbsent(it.id, it) }
            if (cards.size == before) break
        }
        val top = cards.keys.firstOrNull()
        if (scrolls > 0 && top != null) {
            device.teamsRoot()?.let { TeamsScreens.findCard(it, top) }?.perform(UiAction.ShowOnScreen)
        }
        return cards.values.toList()
    }

    private suspend fun readDetail(tab: Tab, card: ListCard, listDueAt: Long?, old: Assignment?): Assignment? {
        var detail: DetailScreen? = null
        for (attempt in 1..2) {
            ensureOnList(tab)
            detail = try {
                openCard(card.id, card.title)
            } catch (_: StepTimeout) {
                null
            }
            if (detail != null) break
            log("Couldn't open \"${card.title}\" (attempt $attempt of 2)")
        }
        if (detail == null) {
            ensureOnList(tab)
            return toAssignment(tab, card, null, listDueAt, old)
        }
        backToList()
        if (detail.isHandedIn) {
            log("\"${card.title}\" has been handed in; leaving it out")
            return null
        }
        return toAssignment(tab, card, detail, listDueAt, old)
    }

    /** Gets back to [tab]'s list from wherever a failed step left Teams. */
    private suspend fun ensureOnList(tab: Tab) {
        val root = try {
            awaitScreen("Teams") { it }
        } catch (_: StepTimeout) {
            throw SyncAbort("Teams stopped responding")
        }
        if (!TeamsScreens.isList(root) || TeamsScreens.isDetail(root)) backToList()
        val current = device.teamsRoot()
        if (current == null || TeamsScreens.selectedTab(current) != tab) {
            try {
                selectTab(tab)
            } catch (_: StepTimeout) {
                throw SyncAbort("Couldn't switch to the ${tab.label} tab")
            }
        }
    }

    private fun toAssignment(
        tab: Tab,
        card: ListCard,
        detail: DetailScreen?,
        listDueAt: Long?,
        old: Assignment?,
    ): Assignment {
        val detailDueAt = detail?.dueText?.let(parser::parseDetail)?.toEpochMilli()
        return Assignment(
            key = card.id,
            title = detail?.title ?: card.title,
            // The toolbar is authoritative; a collapsed card's class may include a tag.
            className = detail?.className ?: old?.className?.takeIf { card.collapsed } ?: card.className,
            description = detail?.instructions ?: old?.description.orEmpty(),
            dueText = detail?.dueText ?: listOfNotNull(card.headerDate, card.dueLine).joinToString(" · "),
            dueAt = detailDueAt ?: listDueAt,
            tab = tab.toAssignmentTab(),
            // An earlier read only stays valid while the row is unchanged; otherwise the next
            // sync must try the details again rather than trust stale ones for days.
            detailReadAt = when {
                detail != null -> wallClock()
                old != null && rowUnchanged(old, card, listDueAt) -> old.detailReadAt
                else -> null
            },
            lastSyncedAt = wallClock(),
        )
    }

    private fun isFresh(old: Assignment, card: ListCard, listDueAt: Long?): Boolean {
        val readAt = old.detailReadAt ?: return false
        return wallClock() - readAt < detailReuseMs && rowUnchanged(old, card, listDueAt)
    }

    private fun rowUnchanged(old: Assignment, card: ListCard, listDueAt: Long?): Boolean =
        old.title == card.title && old.dueAt == listDueAt
}
