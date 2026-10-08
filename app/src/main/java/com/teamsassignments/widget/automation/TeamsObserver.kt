package com.teamsassignments.widget.automation

import com.teamsassignments.widget.automation.TeamsScreens.normalizedTitle
import com.teamsassignments.widget.automation.TeamsSelectors.Tab
import com.teamsassignments.widget.automation.TeamsSelectors.toAssignmentTab
import com.teamsassignments.widget.data.Assignment
import com.teamsassignments.widget.data.AssignmentTab
import com.teamsassignments.widget.data.DueDateParser
import kotlin.math.abs

/**
 * Keeps the list up to date from what the user looks at in Teams themselves. It never presses,
 * scrolls or shows anything: while nothing is running and Teams shows Assignments, the service
 * hands it a copy of Teams' window, and it reads what is there.
 *
 * [look] only trusts a list once it has loaded, by the tests a sync uses (see
 * [TeamsAutomation.awaitSettledList]): the tab is selected, nothing is loading, the cards have held
 * still for [AutomationConfig.settleMs] (an empty list for [AutomationConfig.emptySettleMs], or
 * [AutomationConfig.suspectEmptySettleMs] if that tab had work), and a newly selected tab isn't
 * still showing the rows it was selected over. Then [merge] applies it:
 * - the Forthcoming or Past due list adds cards not seen before (without instructions, which only
 *   the detail screen shows) and updates the title, class and due time of known ones;
 * - an assignment's detail screen fills in its class, exact due time and instructions, and adds
 *   it if it is open work the list hasn't shown yet;
 * - whatever Teams shows as handed in (a card on Completed, a handed-in detail screen) is removed;
 * - so is whatever is on neither Forthcoming nor Past due, once both have been seen in full, close
 *   together (see [OpenLists]): taken as handed in.
 *
 * Nor is anything just handed in added back from a list, which Teams may not have refreshed since.
 * Its own screen showing it as not handed in is another matter: the hand-in has been undone, and
 * the assignment comes back as it was.
 */
class TeamsObserver(private val config: AutomationConfig = AutomationConfig()) {

    /** A screen worth reading, once it can be trusted. */
    sealed interface Sighting {
        /**
         * A list tab's cards, without their positions: scrolling doesn't change what's there.
         * [openLists] is set when this sighting completes a view of both open tabs in full.
         */
        data class OnList(val tab: Tab, val cards: List<ListCard>, val openLists: OpenLists? = null) : Sighting

        /** A detail screen, and whether its toolbar title is the class name ([TeamsScreens.classInToolbar]). */
        data class OnDetail(val detail: DetailScreen, val classInToolbar: Boolean) : Sighting
    }

    /**
     * Every card on Forthcoming and Past due, each list seen in full once it had loaded, the two
     * within [AutomationConfig.bothTabsWithinMs] of each other. Work falling due between
     * [movedFrom] and [movedTo] (epoch milliseconds) may have moved from one to the other
     * meanwhile, so it doesn't count as missing.
     */
    data class OpenLists(val ids: Set<String>, val movedFrom: Long, val movedTo: Long)

    /**
     * What [merge] made of a sighting: the new list, a line per change for the log, the keys Teams
     * showed as handed in (whether or not they were still listed, less those already remembered),
     * and those it took as handed in for being on neither open list. Only the former are
     * remembered as handed in: were the latter wrong, the next look at the list that does show
     * them would bring them back. Work put back on the list, its hand-in undone, needs no
     * reporting: the store forgets a hand-in once its work is listed again.
     */
    data class Merged(
        val assignments: List<Assignment>,
        val changes: List<String>,
        val handedIn: List<String> = emptyList(),
        val presumed: List<String> = emptyList(),
    )

    /** One open tab seen in full: every card on it, whether any showed as handed in, and when. */
    private data class FullView(val ids: Set<String>, val anyHandedIn: Boolean, val at: Long)

    /** A list the tree doesn't hold whole, as the user scrolls through it one settled view at a time. */
    private class Coverage(val tab: Tab) {
        val ids = mutableSetOf<String>()
        var anyHandedIn = false
        var lastIds = emptySet<String>()
        var sawTop = false
        var sawBottom = false
    }

    private var candidate: Sighting? = null
    private var candidateSince = 0L
    private var lastUsed: Sighting? = null

    /** Whether the list behind [lastUsed] was whole in the tree ([TeamsScreens.wholeListInTree]). */
    private var lastUsedWhole = false

    /** The last list looked at, trusted or not, and the rows that showed when its tab was selected. */
    private var lastSeenList: Sighting.OnList? = null
    private var rowsAtSwitch: List<ListCard>? = null
    private var changedSinceSwitch = true

    private var coverage: Coverage? = null
    private val fullViews = mutableMapOf<Tab, FullView>()
    private var completedIds = emptySet<String>()

    /** Whether the last [look] found a screen that hasn't held still for long enough yet. */
    var settling = false
        private set

    /** Forgets what was seen, for when a workflow has been driving Teams, or the user has left. */
    fun reset() {
        candidate = null
        lastUsed = null
        lastUsedWhole = false
        lastSeenList = null
        rowsAtSwitch = null
        changedSinceSwitch = true
        coverage = null
        fullViews.clear()
        completedIds = emptySet()
        settling = false
    }

    /**
     * What [root] shows, once it can be trusted (and only the first time after that), else null.
     * [now] is a monotonic clock and [wallClock] the time of day, both in milliseconds. [saved] is
     * the list as it stands: an open tab that had work must stay empty for longer to count.
     */
    fun look(root: UiNode, now: Long, wallClock: Long, saved: List<Assignment> = emptyList()): Sighting? {
        settling = false
        // A screen still sliding in isn't trusted yet.
        if (!TeamsScreens.windowAtRest(root)) {
            candidate = null
            return null
        }
        if (TeamsScreens.isDetail(root)) {
            val detail = TeamsScreens.detail(root)?.takeIf { it.title != null && !TeamsScreens.isLoading(root) }
            val seen = detail?.let { Sighting.OnDetail(it, TeamsScreens.classInToolbar(root)) }
            if (!heldStill(seen, now, config.settleMs) || seen == lastUsed) return null
            lastUsed = seen
            return seen
        }
        val tab = TeamsScreens.selectedTab(root)?.takeIf { TeamsScreens.isList(root) }
        if (tab == null) {
            candidate = null
            return null
        }
        return lookAtList(root, tab, now, wallClock, saved)
    }

    private fun lookAtList(root: UiNode, tab: Tab, now: Long, wallClock: Long, saved: List<Assignment>): Sighting? {
        // The sync's test for a loading list: an indicator on screen. A "load more" placeholder off
        // screen only means the list may not be whole yet, which fullView allows for.
        val loading = TeamsScreens.isLoading(root)
        val seen = Sighting.OnList(tab, TeamsScreens.cards(root).map { it.copy(bounds = IntRect.EMPTY) })
        noteSwitch(seen, loading)
        if (loading) {
            candidate = null
            return null
        }
        val hold = when {
            seen.cards.isNotEmpty() -> config.settleMs
            tab != Tab.Completed && saved.any { it.tab == tab.toAssignmentTab() } -> config.suspectEmptySettleMs
            else -> config.emptySettleMs
        }
        // Until the list has changed since its tab was selected, the rows may be the last tab's.
        // A list becoming whole, its "load more" placeholder reached, is news even with the same cards.
        val whole = TeamsScreens.wholeListInTree(root)
        if (!heldStill(seen, now, hold) || !changedSinceSwitch || (seen == lastUsed && whole == lastUsedWhole)) return null
        lastUsed = seen
        lastUsedWhole = whole
        val openLists = if (tab == Tab.Completed) {
            completedIds = seen.cards.map { it.id }.toSet()
            null
        } else {
            fullView(root, seen, wallClock)?.let { fullViews[tab] = it }
            bothInFull()
        }
        return seen.copy(openLists = openLists)
    }

    /** Whether [seen] has held still for [hold] milliseconds. Sets [settling] while it hasn't yet. */
    private fun heldStill(seen: Sighting?, now: Long, hold: Long): Boolean {
        if (seen == null) {
            candidate = null
            return false
        }
        if (seen != candidate) {
            candidate = seen
            candidateSince = now
            settling = true
            return false
        }
        if (now - candidateSince < hold) {
            settling = true
            return false
        }
        return true
    }

    /**
     * The sync's rule for a newly selected tab: Teams can mark a tab selected before it replaces
     * the previous tab's rows, so the rows that showed when it was selected don't count as its own
     * until the list has visibly changed at least once (to other rows, to empty, or to a loading
     * indicator). See [TeamsAutomation.awaitSettledList].
     */
    private fun noteSwitch(seen: Sighting.OnList, loading: Boolean) {
        if (lastSeenList?.tab != seen.tab) {
            rowsAtSwitch = lastSeenList?.cards
            changedSinceSwitch = rowsAtSwitch.isNullOrEmpty()
            coverage = null
        }
        if (loading || seen.cards != rowsAtSwitch) changedSinceSwitch = true
        lastSeenList = seen
    }

    /**
     * [seen] as a view of its whole open tab: at once when the tree holds the whole list
     * ([TeamsScreens.wholeListInTree]), otherwise once the user has scrolled from one end of the
     * list to the other, each settled view overlapping the last so that no rows went by unseen.
     */
    private fun fullView(root: UiNode, seen: Sighting.OnList, wallClock: Long): FullView? {
        val ids = seen.cards.map { it.id }.toSet()
        val anyHandedIn = seen.cards.any { it.isHandedIn }
        if (TeamsScreens.wholeListInTree(root)) {
            coverage = null
            return FullView(ids, anyHandedIn, wallClock)
        }
        var covered = coverage?.takeIf { it.tab == seen.tab } ?: Coverage(seen.tab)
        if (ids.isNotEmpty() && covered.lastIds.isNotEmpty() && ids.none(covered.lastIds::contains)) {
            // A gap between this view and the last: start again from here.
            covered = Coverage(seen.tab)
        }
        coverage = covered
        covered.ids += ids
        covered.anyHandedIn = covered.anyHandedIn || anyHandedIn
        if (ids.isNotEmpty()) covered.lastIds = ids
        TeamsScreens.listInView(root)?.let {
            covered.sawTop = covered.sawTop || it.top
            // Not the end while Teams' "load more" placeholder still waits below the last card.
            covered.sawBottom = covered.sawBottom || (it.bottom && !TeamsScreens.loadMorePending(root))
        }
        return FullView(covered.ids.toSet(), covered.anyHandedIn, wallClock).takeIf { covered.sawTop && covered.sawBottom }
    }

    /**
     * Both open tabs seen in full, close enough together, and each plainly its own list. A newly
     * selected tab can show the rows of the one before it: the other open tab's, or Completed's
     * (which show as handed in). Either would make work that is there look missing.
     */
    private fun bothInFull(): OpenLists? {
        val forthcoming = fullViews[Tab.Forthcoming] ?: return null
        val pastDue = fullViews[Tab.PastDue] ?: return null
        if (abs(forthcoming.at - pastDue.at) > config.bothTabsWithinMs) return null
        val views = listOf(forthcoming, pastDue)
        if (views.any { it.anyHandedIn || (it.ids.isNotEmpty() && it.ids == completedIds) }) return null
        if (forthcoming.ids.isNotEmpty() && forthcoming.ids == pastDue.ids) return null
        return OpenLists(
            ids = forthcoming.ids + pastDue.ids,
            movedFrom = minOf(forthcoming.at, pastDue.at) - config.movedTabsBeforeMs,
            movedTo = maxOf(forthcoming.at, pastDue.at) + config.movedTabsAfterMs,
        )
    }

    companion object {
        /**
         * Applies [sighting] to the [saved] list. [wallClock] is the time now, in epoch milliseconds.
         * [recentlyHandedIn] holds what was handed in lately: each key, with the assignment as it
         * stood if it was on the list. A list still showing one as open is out of date, so it isn't
         * added back from one; its own screen showing it as not handed in brings it back.
         */
        fun merge(
            sighting: Sighting,
            saved: List<Assignment>,
            parser: DueDateParser,
            wallClock: Long,
            recentlyHandedIn: Map<String, Assignment?> = emptyMap(),
        ): Merged = when (sighting) {
            is Sighting.OnList -> mergeList(sighting, saved, parser, wallClock, recentlyHandedIn)
            is Sighting.OnDetail -> mergeDetail(sighting, saved, parser, wallClock, recentlyHandedIn.values.filterNotNull())
        }

        private fun mergeList(
            sighting: Sighting.OnList,
            saved: List<Assignment>,
            parser: DueDateParser,
            wallClock: Long,
            recentlyHandedIn: Map<String, Assignment?>,
        ): Merged {
            // Work handed in before the list had shown it is remembered without its Teams id.
            val handedInUnkeyed = recentlyHandedIn.values.filterNotNull().filterNot { TeamsSelectors.CARD_ID.matches(it.key) }
            val out = saved.toMutableList()
            val changes = mutableListOf<String>()
            val handedIn = mutableListOf<String>()
            for (card in sighting.cards) {
                val index = out.indexOfFirst { it.key == card.id }
                // Completed holds work handed in, or closed. A card there still showing a due line
                // is an open tab's, not yet replaced after a tab switch, and proves nothing.
                val completed = sighting.tab == Tab.Completed && !TeamsSelectors.CARD_DUE_LINE.containsMatchIn(card.dueLine)
                if (card.isHandedIn || completed) {
                    // Remembered even when it's no longer listed (taken as handed in, say), so a list
                    // Teams hasn't refreshed can't add it back.
                    if (card.id !in recentlyHandedIn) handedIn += card.id
                    if (index >= 0) {
                        changes += if (card.isHandedIn) "\"${out[index].title}\" handed in" else "\"${out[index].title}\" is on Completed"
                        out.removeAt(index)
                    }
                    continue
                }
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
                // A detail screen seen on its own may have saved it already, without its GUID.
                val unkeyed = out.indexOfFirst { !TeamsSelectors.CARD_ID.matches(it.key) && sameRow(it, card, dueAt) }
                // Handed in lately, and still listed: a list Teams hasn't refreshed, unless its own
                // screen has shown it open since, which is how it came to be saved again. Remembered
                // without its Teams id, it is told by its title, class and due time instead.
                val handedInLately = card.id in recentlyHandedIn || handedInUnkeyed.any { sameWork(it, card, dueAt) }
                if (handedInLately && unkeyed < 0) continue

                val added = Assignment(
                    key = card.id,
                    title = card.title,
                    className = card.className,
                    dueText = listDueText(card),
                    dueAt = dueAt,
                    tab = tab,
                    lastSyncedAt = wallClock,
                )
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
            val presumed = mutableListOf<String>()
            sighting.openLists?.let { lists ->
                out.removeAll { assignment ->
                    val missing = TeamsSelectors.CARD_ID.matches(assignment.key) && assignment.key !in lists.ids &&
                        assignment.dueAt?.let { it in lists.movedFrom..lists.movedTo } != true
                    if (missing) {
                        changes += "\"${assignment.title}\" is on neither Forthcoming nor Past due: taken as handed in"
                        presumed += assignment.key
                    }
                    missing
                }
            }
            return Merged(out, changes, handedIn, presumed)
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

        private fun mergeDetail(
            sighting: Sighting.OnDetail,
            saved: List<Assignment>,
            parser: DueDateParser,
            wallClock: Long,
            handedInWork: List<Assignment>,
        ): Merged {
            val detail = sighting.detail
            val unchanged = Merged(saved, emptyList())
            val title = detail.title ?: return unchanged
            val className = detail.className?.takeIf { sighting.classInToolbar }
            val dueAt = detail.dueText?.let(parser::parseDetail)?.toEpochMilli()

            // The detail screen has no GUID, so it is matched on its title, class and due time. A
            // known due time must agree even for a lone candidate: weekly work repeats its title and
            // class, and a due date that really changed shows on the list, where cards have GUIDs.
            val sameTitle = saved.filter { it.title.normalizedTitle() == title.normalizedTitle() }
            val candidates = sameTitle
                .filter { className == null || it.className.isEmpty() || classMatches(it.className, className) }
                .filter { dueAt == null || it.dueAt == null || it.dueAt == dueAt }
            val match = candidates.singleOrNull()

            if (match == null) {
                val open = detail.status?.let(TeamsSelectors.DETAIL_NOT_HANDED_IN_STATUS::matches) == true
                if (!open || className == null || dueAt == null) return unchanged
                // Handed in lately, and now open again on its own screen: the hand-in was undone.
                // It comes back as it was, Teams id included, so it can be handed in again. That id
                // must be this very assignment's, so its due time has to be the screen's exactly,
                // and no saved row may be one this screen could belong to. Another week's row, due
                // at another time, is no bar. A class left unknown stands for any, as above.
                val remembered = handedInWork.takeIf { candidates.isEmpty() }.orEmpty().filter {
                    it.title.normalizedTitle() == title.normalizedTitle() &&
                        (it.className.isEmpty() || classMatches(it.className, className)) && it.dueAt == dueAt
                }
                // Work handed in both before and after the list gave it its Teams id can be
                // remembered twice over: the one with the id is the one to give back.
                val undone = remembered.singleOrNull { TeamsSelectors.CARD_ID.matches(it.key) } ?: remembered.singleOrNull()
                if (undone != null) {
                    val restored = undone.copy(
                        title = title,
                        className = className,
                        description = detail.instructions,
                        dueText = detail.dueText.orEmpty(),
                        dueAt = dueAt,
                        tab = if (dueAt <= wallClock) AssignmentTab.PastDue else AssignmentTab.Forthcoming,
                        detailReadAt = wallClock,
                        lastSyncedAt = wallClock,
                    )
                    return Merged(saved + restored, listOf("\"$title\" is no longer handed in"))
                }
                // Open work the list hasn't shown yet, say from a Teams notification: add it once
                // there's enough to show, and let the list swap in its GUID when it's seen there. Not
                // while a same-titled row could be this class's (another week's, or one of unknown
                // class): that is left to the list, where cards have GUIDs.
                if (sameTitle.any { it.className.isEmpty() || classMatches(it.className, className) }) return unchanged
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

        /**
         * Whether [card] shows [work], handed in lately and remembered without a GUID. Unlike
         * [sameRow], a collapsed card's class counts here: another class can set work of the same
         * title for the same time, and passing its card by would keep it off the list. Only a class
         * left unknown, on either side, stands for any.
         */
        private fun sameWork(work: Assignment, card: ListCard, dueAt: Long?): Boolean =
            TeamsScreens.sameTitle(card.title, work.title) && dueAt != null && dueAt == work.dueAt &&
                (work.className.isEmpty() || card.className.isEmpty() || classMatches(work.className, card.className))

        /** Class names as the toolbar and a card show them; a collapsed card's may start with its tag. */
        private fun classMatches(a: String, b: String): Boolean = a == b || a.endsWith(" $b") || b.endsWith(" $a")

        /** The due text a sync saves for a card whose detail screen it didn't read. */
        private fun listDueText(card: ListCard): String = listOfNotNull(card.headerDate, card.dueLine).joinToString(" · ")
    }
}
