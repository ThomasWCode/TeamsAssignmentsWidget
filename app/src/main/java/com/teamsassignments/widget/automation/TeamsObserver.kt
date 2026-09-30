package com.teamsassignments.widget.automation

import com.teamsassignments.widget.automation.TeamsScreens.normalizedTitle
import com.teamsassignments.widget.automation.TeamsSelectors.Tab
import com.teamsassignments.widget.data.Assignment
import com.teamsassignments.widget.data.AssignmentTab
import com.teamsassignments.widget.data.DueDateParser

/**
 * Keeps the list up to date from what the user looks at in Teams themselves. It never presses,
 * scrolls or shows anything: while nothing is running and Teams shows Assignments, the service
 * hands it a copy of Teams' window, and it reads what is there.
 *
 * [look] waits for a screen to hold still for [settleMs] before trusting it, then [merge] applies it:
 * - the Forthcoming or Past due list adds cards not seen before (without instructions, which only
 *   the detail screen shows) and updates the title, class and due time of known ones;
 * - an assignment's detail screen fills in its class, exact due time and instructions, and adds
 *   it if it is open work the list hasn't shown yet;
 * - whatever Teams shows as handed in (a Completed card, a handed-in detail screen) is removed.
 *
 * Nothing is removed just for being missing from a list: the list may still be loading, or the
 * work may be on the other tab. A sync (↻) settles that. Nor is anything just handed in added back
 * from a list, which Teams may not have refreshed since.
 */
class TeamsObserver(private val settleMs: Long = 600) {

    /** A screen worth reading, once it has held still. */
    sealed interface Sighting {
        /** A list tab's cards, without their positions: scrolling doesn't change what's there. */
        data class OnList(val tab: Tab, val cards: List<ListCard>) : Sighting

        /** A detail screen, and whether its toolbar title is the class name ([TeamsScreens.classInToolbar]). */
        data class OnDetail(val detail: DetailScreen, val classInToolbar: Boolean) : Sighting
    }

    /**
     * What [merge] made of a sighting: the new list, a line per change for the log, and the keys
     * it removed because Teams showed them handed in.
     */
    data class Merged(val assignments: List<Assignment>, val changes: List<String>, val handedIn: List<String> = emptyList())

    private var candidate: Sighting? = null
    private var candidateSince = 0L
    private var lastUsed: Sighting? = null
    private var lastList: Sighting.OnList? = null

    /** Whether the last [look] found a screen that hasn't held still for long enough yet. */
    var settling = false
        private set

    /** Forgets what was seen, for when a workflow has been driving Teams. */
    fun reset() {
        candidate = null
        lastUsed = null
        lastList = null
        settling = false
    }

    /**
     * What [root] shows, once the same has been seen for [settleMs] (and only the first time
     * after that), else null. [now] is a monotonic clock in milliseconds.
     */
    fun look(root: UiNode, now: Long): Sighting? {
        settling = false
        val seen = sighting(root)
        if (seen == null) {
            candidate = null
            return null
        }
        if (seen != candidate) {
            candidate = seen
            candidateSince = now
            settling = true
            return null
        }
        if (now - candidateSince < settleMs) {
            settling = true
            return null
        }
        if (seen == lastUsed || (seen is Sighting.OnList && isPreviousTabsRows(seen))) return null
        lastUsed = seen
        if (seen is Sighting.OnList) lastList = seen
        return seen
    }

    private fun sighting(root: UiNode): Sighting? {
        // A screen still sliding in or loading isn't trusted yet.
        if (!TeamsScreens.windowAtRest(root) || TeamsScreens.isLoadingOnScreen(root)) return null
        if (TeamsScreens.isDetail(root)) {
            val detail = TeamsScreens.detail(root)?.takeIf { it.title != null } ?: return null
            return Sighting.OnDetail(detail, TeamsScreens.classInToolbar(root))
        }
        if (!TeamsScreens.isList(root)) return null
        val tab = TeamsScreens.selectedTab(root) ?: return null
        return Sighting.OnList(tab, TeamsScreens.cards(root).map { it.copy(bounds = IntRect.EMPTY) })
    }

    /**
     * Teams can mark a newly selected tab before it replaces the previous tab's rows (see
     * [TeamsAutomation.awaitSettledList]), so rows identical to the last tab's aren't taken as this one's.
     */
    private fun isPreviousTabsRows(seen: Sighting.OnList): Boolean {
        val last = lastList ?: return false
        return seen.tab != last.tab && seen.cards.isNotEmpty() && seen.cards == last.cards
    }

    companion object {
        /**
         * Applies [sighting] to the [saved] list. [wallClock] is the time now, in epoch milliseconds.
         * [recentlyHandedIn] holds keys handed in lately: a list still showing them as open is out of
         * date, so they aren't added back from one.
         */
        fun merge(
            sighting: Sighting,
            saved: List<Assignment>,
            parser: DueDateParser,
            wallClock: Long,
            recentlyHandedIn: Set<String> = emptySet(),
        ): Merged = when (sighting) {
            is Sighting.OnList -> mergeList(sighting, saved, parser, wallClock, recentlyHandedIn)
            is Sighting.OnDetail -> mergeDetail(sighting, saved, parser, wallClock)
        }

        private fun mergeList(
            sighting: Sighting.OnList,
            saved: List<Assignment>,
            parser: DueDateParser,
            wallClock: Long,
            recentlyHandedIn: Set<String>,
        ): Merged {
            val out = saved.toMutableList()
            val changes = mutableListOf<String>()
            val handedIn = mutableListOf<String>()
            for (card in sighting.cards) {
                val index = out.indexOfFirst { it.key == card.id }
                if (card.isHandedIn) {
                    if (index >= 0) {
                        changes += "\"${out[index].title}\" handed in"
                        handedIn += card.id
                        out.removeAt(index)
                    }
                    continue
                }
                // Completed also lists work that closed without being handed in: that's for a sync to judge.
                if (sighting.tab == Tab.Completed) continue

                val dueAt = parser.parseList(card.headerDate, card.headerLabel, card.dueLine)?.toEpochMilli()
                // Teams lists work that fell due earlier today on both tabs; it belongs under Past due.
                val tab = if (sighting.tab == Tab.PastDue || (dueAt != null && dueAt <= wallClock)) {
                    AssignmentTab.PastDue
                } else {
                    AssignmentTab.Forthcoming
                }
                if (index >= 0) {
                    val old = out[index]
                    val updated = updated(old, card, dueAt, tab)
                    if (updated != old) {
                        out[index] = updated.copy(lastSyncedAt = wallClock)
                        changes += "updated \"${updated.title}\""
                    }
                    continue
                }
                if (card.id in recentlyHandedIn) continue

                val added = Assignment(
                    key = card.id,
                    title = card.title,
                    className = card.className,
                    dueText = listDueText(card),
                    dueAt = dueAt,
                    tab = tab,
                    lastSyncedAt = wallClock,
                )
                // A detail screen seen on its own may have saved it already, without its GUID.
                val unkeyed = out.indexOfFirst { !TeamsSelectors.CARD_ID.matches(it.key) && sameRow(it, card, dueAt) }
                if (unkeyed >= 0) {
                    val earlier = out[unkeyed]
                    out[unkeyed] = added.copy(
                        className = earlier.className.ifEmpty { added.className },
                        description = earlier.description,
                        dueText = earlier.dueText,
                        detailReadAt = earlier.detailReadAt,
                    )
                } else {
                    out += added
                }
                changes += "added \"${card.title}\""
            }
            return Merged(out, changes, handedIn)
        }

        /**
         * [old] as [card] shows it now. A changed title or due time makes the saved details
         * suspect, so the next sync reads them again, as it would for any changed row.
         */
        private fun updated(old: Assignment, card: ListCard, dueAt: Long?, tab: AssignmentTab): Assignment {
            // A collapsed card's title is cut out of its text; the saved one stands while it fits.
            val title = if (card.collapsed && TeamsScreens.sameTitle(card.title, old.title)) old.title else card.title
            val changed = title != old.title || (dueAt != null && dueAt != old.dueAt)
            return old.copy(
                title = title,
                // The detail toolbar's class is authoritative, and a collapsed card's may include a tag.
                className = when {
                    old.className.isEmpty() -> card.className
                    card.collapsed || card.className.isEmpty() || old.detailReadAt != null -> old.className
                    else -> card.className
                },
                dueText = if (changed) listDueText(card) else old.dueText,
                dueAt = dueAt ?: old.dueAt,
                tab = tab,
                detailReadAt = if (changed) null else old.detailReadAt,
            )
        }

        private fun mergeDetail(sighting: Sighting.OnDetail, saved: List<Assignment>, parser: DueDateParser, wallClock: Long): Merged {
            val detail = sighting.detail
            val unchanged = Merged(saved, emptyList())
            val title = detail.title ?: return unchanged
            val className = detail.className?.takeIf { sighting.classInToolbar }
            val dueAt = detail.dueText?.let(parser::parseDetail)?.toEpochMilli()

            // The detail screen has no GUID, so it is matched on its title, then its class, then its due time.
            val sameTitle = saved.filter { it.title.normalizedTitle() == title.normalizedTitle() }
            val match = sameTitle
                .filter { className == null || it.className.isEmpty() || classMatches(it.className, className) }
                .let { found -> if (found.size > 1 && dueAt != null) found.filter { it.dueAt == dueAt } else found }
                .singleOrNull()

            if (match == null) {
                // Open work the list hasn't shown yet, say from a Teams notification: add it once
                // there's enough to show, and let the list swap in its GUID when it's seen there.
                val open = detail.status?.let(TeamsSelectors.DETAIL_NOT_HANDED_IN_STATUS::matches) == true
                if (sameTitle.isNotEmpty() || !open || className == null || dueAt == null) return unchanged
                val added = Assignment(
                    key = Assignment.fallbackKey(className, title),
                    title = title,
                    className = className,
                    description = detail.instructions,
                    dueText = detail.dueText.orEmpty(),
                    dueAt = dueAt,
                    tab = if (dueAt <= wallClock) AssignmentTab.PastDue else AssignmentTab.Forthcoming,
                    detailReadAt = wallClock,
                    lastSyncedAt = wallClock,
                )
                return Merged(saved + added, listOf("added \"$title\""))
            }

            val index = saved.indexOf(match)
            if (detail.isHandedIn) {
                return Merged(saved.filterIndexed { i, _ -> i != index }, listOf("\"${match.title}\" handed in"), listOf(match.key))
            }
            val updated = match.copy(
                title = title,
                className = className ?: match.className,
                description = detail.instructions,
                dueText = detail.dueText ?: match.dueText,
                dueAt = dueAt ?: match.dueAt,
                tab = when {
                    dueAt == null -> match.tab
                    dueAt <= wallClock -> AssignmentTab.PastDue
                    else -> AssignmentTab.Forthcoming
                },
                detailReadAt = match.detailReadAt,
            )
            // Read before and unchanged since: nothing worth saving.
            if (updated == match && match.detailReadAt != null) return unchanged
            val out = saved.toMutableList()
            out[index] = updated.copy(detailReadAt = wallClock, lastSyncedAt = wallClock)
            return Merged(out, listOf("read \"$title\""))
        }

        /** Whether [saved], kept without a GUID, is the assignment on [card]: same title, class and due time. */
        private fun sameRow(saved: Assignment, card: ListCard, dueAt: Long?): Boolean =
            TeamsScreens.sameTitle(card.title, saved.title) &&
                (card.collapsed || classMatches(saved.className, card.className)) &&
                dueAt != null && dueAt == saved.dueAt

        /** Class names as the toolbar and a card show them; a collapsed card's may start with its tag. */
        private fun classMatches(a: String, b: String): Boolean = a == b || a.endsWith(" $b") || b.endsWith(" $a")

        /** The due text a sync saves for a card whose detail screen it didn't read. */
        private fun listDueText(card: ListCard): String = listOfNotNull(card.headerDate, card.dueLine).joinToString(" · ")
    }
}
