package com.teamsassignments.widget.automation

import com.teamsassignments.widget.automation.TeamsScreens.ListScope
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
 *   together (see [OpenLists]): taken as handed in. Forthcoming seen in full is enough on its own
 *   for work due later than that, which nothing but a hand-in (or the teacher) takes off it.
 *   Past due counts only once it has been seen in full itself: nothing on another tab stands
 *   for it.
 *
 * A class team's own Assignments tab holds that class's work alone ([ListScope]), so it only
 * ever counts for that class. Its Past due reaches further back than the app's own list, which a
 * sync reads, so it only updates work already saved: work only it showed would come and go. A
 * list narrowed by Filter or a search adds and updates as any other, but never counts as whole.
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
         * [scope] says whose work it holds. [openLists] is set when this sighting completes a
         * view of the open tabs in full.
         */
        data class OnList(
            val tab: Tab,
            val cards: List<ListCard>,
            val scope: ListScope = ListScope(className = null, narrowed = false),
            val openLists: OpenLists? = null,
        ) : Sighting

        /** A detail screen, and whether its toolbar title is the class name ([TeamsScreens.classInToolbar]). */
        data class OnDetail(val detail: DetailScreen, val classInToolbar: Boolean) : Sighting
    }

    /**
     * Every card on Forthcoming and Past due, each list seen in full once it had loaded, the two
     * within [AutomationConfig.bothTabsWithinMs] of each other; or Forthcoming's alone, where
     * [dueAfter] is set. Work falling due between [movedFrom] and [movedTo] (epoch milliseconds)
     * may have moved from one to the other meanwhile, so it doesn't count as missing; with
     * Forthcoming alone, only work due after [dueAfter] can, since Past due may hold the rest.
     * [className] is the class whose own lists these are, or null for every class's. Work seen
     * open since [since], when the earlier of the views was taken, isn't missing from it: work
     * changed since (its [Assignment.lastSyncedAt]), and [seenOpen], unchanged but seen.
     */
    data class OpenLists(
        val ids: Set<String>,
        val movedFrom: Long,
        val movedTo: Long,
        val since: Long,
        val className: String? = null,
        val dueAfter: Long? = null,
        val seenOpen: Set<String> = emptySet(),
    ) {
        /** Whether open work due at [dueAt] would have to be on these lists. */
        fun wouldList(dueAt: Long?): Boolean = when (dueAfter) {
            null -> dueAt?.let { it in movedFrom..movedTo } != true
            else -> dueAt != null && dueAt > dueAfter
        }
    }

    /**
     * What [merge] made of a sighting: the new list, a line per change for the log, the keys Teams
     * showed as handed in (less those already remembered), and those it took as handed in for
     * being on neither open list. Only the former are remembered as handed in: were the latter
     * wrong, the next look at the list that does show them would bring them back. Work put back on
     * the list, its hand-in undone, needs no reporting: the store forgets a hand-in once its work
     * is listed again. [listsSeenAt] is set when both open lists, every class's, were seen in full.
     * [seenOpen] holds the saved work a detail screen showed open, changed or not, for [sawOpen].
     * [notes] are lines for the log about what was seen but left alone, and why: no change.
     */
    data class Merged(
        val assignments: List<Assignment>,
        val changes: List<String>,
        val handedIn: List<String> = emptyList(),
        val presumed: List<String> = emptyList(),
        val listsSeenAt: Long? = null,
        val seenOpen: List<String> = emptyList(),
        val notes: List<String> = emptyList(),
    )

    /** One open tab seen in full: every card on it, whether any showed as handed in, and when. */
    private data class FullView(val ids: Set<String>, val anyHandedIn: Boolean, val at: Long)

    /** A list the tree doesn't hold whole, as the user scrolls through it one settled view at a time. */
    private class Coverage(val tab: Tab, val className: String?) {
        val ids = mutableSetOf<String>()
        var anyHandedIn = false
        var lastIds = emptySet<String>()
        var sawTop = false
        var sawBottom = false
    }

    private var candidate: Sighting? = null
    private var candidateWhole = false
    private var candidateSince = 0L
    private var lastUsed: Sighting? = null

    /** Whether the list behind [lastUsed] was whole in the tree ([TeamsScreens.wholeListInTree]). */
    private var lastUsedWhole = false

    /** The last list looked at, trusted or not, and the rows that showed when its tab was selected. */
    private var lastSeenList: Sighting.OnList? = null
    private var rowsAtSwitch: List<ListCard>? = null
    private var changedSinceSwitch = true

    private var coverage: Coverage? = null

    /** Each open tab seen in full, by the class whose own tab it is (null for every class's). */
    private val fullViews = mutableMapOf<Pair<String?, Tab>, FullView>()
    private val completedIds = mutableMapOf<String?, Set<String>>()

    /**
     * When each assignment was last seen open, on any list or its own screen, by key. Kept here
     * beside [fullViews], which it's weighed against, rather than saved: a sighting that changes
     * nothing saves nothing, so the provider's apps aren't told of every look at Teams.
     */
    private val seenOpenAt = mutableMapOf<String, Long>()

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
        completedIds.clear()
        seenOpenAt.clear()
        settling = false
    }

    /** Notes [keys] seen open at [wallClock], from a sighting [merge] matched ([Merged.seenOpen]). */
    fun sawOpen(keys: Collection<String>, wallClock: Long) {
        keys.forEach { seenOpenAt[it] = wallClock }
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
        // Nor is a list whose toolbar isn't drawn yet: it can't say whose work it holds, the app's
        // own or one class team's, whose Past due reaches back further (Codex review).
        if (tab == null || TeamsScreens.toolbarTitle(root).isEmpty()) {
            candidate = null
            return null
        }
        return lookAtList(root, tab, now, wallClock, saved)
    }

    private fun lookAtList(root: UiNode, tab: Tab, now: Long, wallClock: Long, saved: List<Assignment>): Sighting? {
        // The sync's test for a loading list: an indicator on screen. A "load more" placeholder off
        // screen only means the list may not be whole yet, which fullView allows for.
        val loading = TeamsScreens.isLoading(root)
        val scope = TeamsScreens.listScope(root)
        val seen = Sighting.OnList(tab, TeamsScreens.cards(root).map { it.copy(bounds = IntRect.EMPTY) }, scope)
        noteSwitch(seen, loading)
        if (loading) {
            candidate = null
            return null
        }
        val inScope = saved.filter { scope.className == null || it.className == scope.className }
        val hold = when {
            seen.cards.isNotEmpty() -> config.settleMs
            tab != Tab.Completed && inScope.any { it.tab == tab.toAssignmentTab() } -> config.suspectEmptySettleMs
            else -> config.emptySettleMs
        }
        // Until the list has changed since its tab was selected, the rows may be the last tab's.
        // Whether it's whole is part of what must hold still: Teams may drop its "load more"
        // placeholder a moment before the cards it fetched arrive. Once it has held, a list
        // becoming whole is news even with the same cards.
        val whole = TeamsScreens.wholeListInTree(root)
        if (!heldStill(seen, now, hold, whole) || !changedSinceSwitch) return null
        if (tab != Tab.Completed) sawOpen(seen.cards.filterNot { it.isHandedIn }.map { it.id }, wallClock)
        if (seen == lastUsed && whole == lastUsedWhole) {
            // Nothing new, but what was seen in full still is: the ten minutes run from now.
            if (whole && !scope.narrowed) fullViews[scope.className to tab]?.let { fullViews[scope.className to tab] = it.copy(at = wallClock) }
            return null
        }
        lastUsed = seen
        lastUsedWhole = whole
        val openLists = when {
            tab == Tab.Completed -> {
                completedIds[scope.className] = seen.cards.map { it.id }.toSet()
                null
            }
            // Less than the tab holds: no view of it in full, now or by scrolling through.
            scope.narrowed -> {
                coverage = null
                null
            }
            else -> {
                // A tab seen in part now, its "load more" placeholder waiting say, may hold more than
                // an earlier view of it in full did: that view no longer stands for it.
                val view = fullView(root, seen, wallClock)
                val key = scope.className to tab
                if (view != null) fullViews[key] = view else fullViews.remove(key)
                openLists(scope.className, wallClock)
            }
        }
        return seen.copy(openLists = openLists)
    }

    /**
     * Whether [seen], and for a list whether it was [whole], has held still for [hold]
     * milliseconds. Sets [settling] while it hasn't yet.
     */
    private fun heldStill(seen: Sighting?, now: Long, hold: Long, whole: Boolean = false): Boolean {
        if (seen == null) {
            candidate = null
            return false
        }
        if (seen != candidate || whole != candidateWhole) {
            candidate = seen
            candidateWhole = whole
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
     * indicator). See [TeamsAutomation.awaitSettledList]. Another class's list, or every class's
     * after one class's, is a switch too.
     */
    private fun noteSwitch(seen: Sighting.OnList, loading: Boolean) {
        val last = lastSeenList
        if (last?.tab != seen.tab || last.scope.className != seen.scope.className) {
            rowsAtSwitch = last?.cards
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
        val className = seen.scope.className
        var covered = coverage?.takeIf { it.tab == seen.tab && it.className == className } ?: Coverage(seen.tab, className)
        if (ids.isNotEmpty() && covered.lastIds.isNotEmpty() && ids.none(covered.lastIds::contains)) {
            // A gap between this view and the last: start again from here.
            covered = Coverage(seen.tab, className)
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
     * [className]'s open tabs (every class's for null) seen in full: both, close enough together,
     * or Forthcoming lately on its own. Each must be plainly its own list. A newly selected tab
     * can show the rows of the one before it: the other open tab's, or Completed's (which show as
     * handed in). Either would make work that is there look missing.
     */
    private fun openLists(className: String?, wallClock: Long): OpenLists? {
        val forthcoming = fullViews[className to Tab.Forthcoming] ?: return null
        val pastDue = fullViews[className to Tab.PastDue]?.takeIf { abs(forthcoming.at - it.at) <= config.bothTabsWithinMs }
        val views = listOfNotNull(forthcoming, pastDue)
        val completed = completedIds[className]
        if (views.any { it.anyHandedIn || (it.ids.isNotEmpty() && it.ids == completed) }) return null
        if (pastDue == null) {
            if (wallClock - forthcoming.at > config.bothTabsWithinMs) return null
            // Work due later than this can't have moved to Past due since Forthcoming was seen.
            val after = forthcoming.at + config.movedTabsAfterMs
            return OpenLists(
                ids = forthcoming.ids,
                movedFrom = forthcoming.at - config.movedTabsBeforeMs,
                movedTo = after,
                since = forthcoming.at,
                className = className,
                dueAfter = after,
                seenOpen = seenOpenSince(forthcoming.at),
            )
        }
        if (forthcoming.ids.isNotEmpty() && forthcoming.ids == pastDue.ids) return null
        val since = minOf(forthcoming.at, pastDue.at)
        return OpenLists(
            ids = forthcoming.ids + pastDue.ids,
            movedFrom = since - config.movedTabsBeforeMs,
            movedTo = maxOf(forthcoming.at, pastDue.at) + config.movedTabsAfterMs,
            since = since,
            className = className,
            seenOpen = seenOpenSince(since),
        )
    }

    private fun seenOpenSince(at: Long): Set<String> = seenOpenAt.filterValues { it > at }.keys.toSet()

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
            val ofClass = sighting.scope.className
            val cards = sighting.cards.map { if (ofClass != null) it.copy(className = ofClass) else it }
            val out = saved.toMutableList()
            val changes = mutableListOf<String>()
            val handedIn = mutableListOf<String>()
            for (shown in sighting.cards) {
                // A class team's own tab leaves the class off its cards: its toolbar names it.
                val card = if (ofClass != null) shown.copy(className = ofClass) else shown
                val index = out.indexOfFirst { it.key == card.id }
                // Completed holds work handed in, or closed. A card there still showing a due line
                // is an open tab's, not yet replaced after a tab switch, and proves nothing.
                val completed = sighting.tab == Tab.Completed && !TeamsSelectors.CARD_DUE_LINE.containsMatchIn(card.dueLine)
                if (card.isHandedIn || completed) {
                    // Remembered, so a list Teams hasn't refreshed can't add it back. Only work that
                    // was on the list, though: Completed holds weeks of work, some of which can come
                    // back (returned for revision, reopened), and the rest needs keeping off nothing.
                    // Work already taken off for being on neither open list isn't remembered either:
                    // that came from lists that no longer showed it, and Teams doesn't go back to
                    // older lists than those, so none should add it back (Claude review).
                    if (index >= 0) {
                        if (card.id !in recentlyHandedIn) handedIn += card.id
                        changes += if (card.isHandedIn) "\"${out[index].title}\" handed in" else "\"${out[index].title}\" is on Completed"
                        out.removeAt(index)
                    }
                    continue
                }
                if (sighting.tab == Tab.Completed) continue

                val dueAt = parser.parseList(card.headerDate, card.headerLabel, card.dueLine, sighting.tab.toAssignmentTab())?.toEpochMilli()
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
                        // The list's due time noted for the first time is no change worth a line.
                        if (updated.copy(listDueAt = old.listDueAt) != old) changes += "updated \"${updated.title}\""
                    }
                    continue
                }
                // A detail screen seen on its own may have saved it already, without its GUID: found by its
                // row, or by its title and class where its own screen's due time differs from the list's ([takenOver]).
                val unkeyed = takenOver(card, dueAt, out.filterNot { TeamsSelectors.CARD_ID.matches(it.key) }, cards)?.let(out::indexOf) ?: -1
                // Handed in lately, and still listed: a list Teams hasn't refreshed, unless its own
                // screen has shown it open since, which is how it came to be saved again. Remembered
                // without its Teams id, it is told by its title, class and due time instead.
                val handedInLately = card.id in recentlyHandedIn || handedInUnkeyed.any { sameWork(it, card, dueAt) }
                if (handedInLately && unkeyed < 0) continue
                if (unkeyed < 0 && ofClass != null && sighting.tab == Tab.PastDue) continue

                val added = Assignment(
                    key = card.id,
                    title = card.title,
                    className = card.className,
                    dueText = listDueText(card),
                    dueAt = dueAt,
                    listDueAt = dueAt,
                    tab = tab,
                    lastSyncedAt = wallClock,
                )
                if (unkeyed >= 0) {
                    val earlier = out[unkeyed]
                    out[unkeyed] = added.copy(
                        // Its own screen's: a collapsed card's title can be cut short.
                        title = earlier.title,
                        className = earlier.className.ifEmpty { added.className },
                        description = earlier.description,
                        attachments = earlier.attachments,
                        links = earlier.links,
                        dueText = earlier.dueText,
                        detailReadAt = earlier.detailReadAt,
                        formerKey = Assignment.formerKey(earlier, added.key),
                    )
                    changes += "\"${earlier.title}\" took its Teams id"
                } else {
                    out += added
                    changes += "added \"${card.title}\""
                }
            }
            val presumed = mutableListOf<String>()
            sighting.openLists?.let { lists ->
                out.removeAll { assignment ->
                    val missing = TeamsSelectors.CARD_ID.matches(assignment.key) && assignment.key !in lists.ids &&
                        (lists.className == null || assignment.className == lists.className) &&
                        // Seen open after the earlier view was taken (its hand-in undone, say, or
                        // on its own screen): that view can't speak for it.
                        assignment.lastSyncedAt <= lists.since && assignment.key !in lists.seenOpen &&
                        lists.wouldList(assignment.dueAt)
                    if (missing) {
                        changes += "\"${assignment.title}\" is on neither Forthcoming nor Past due: taken as handed in"
                        presumed += assignment.key
                    }
                    missing
                }
            }
            val listsSeenAt = sighting.openLists?.takeIf { it.className == null && it.dueAfter == null }?.let { wallClock }
            return Merged(out, changes, handedIn, presumed, listsSeenAt)
        }

        /**
         * [old] as [card] shows it now. A changed title or due time makes the saved details
         * suspect, so the next sync reads them again, as it would for any changed row. The due
         * time is told changed list against list ([Assignment.listDueAt]): the detail screen's,
         * where read, stands until the list shows another.
         */
        private fun updated(old: Assignment, card: ListCard, dueAt: Long?, tab: AssignmentTab): Assignment {
            // A collapsed card's title is cut out of its text; the saved one stands while it fits.
            val title = if (card.collapsed && TeamsScreens.sameTitle(card.title, old.title)) old.title else card.title
            val moved = dueAt != null && dueAt != (old.listDueAt ?: old.dueAt)
            val changed = title != old.title || moved
            return old.copy(
                title = title,
                // The detail toolbar's class is authoritative, and a collapsed card's may include a tag.
                className = when {
                    old.className.isEmpty() -> card.className
                    card.collapsed || card.className.isEmpty() || old.detailReadAt != null -> old.className
                    else -> card.className
                },
                dueText = if (changed) listDueText(card) else old.dueText,
                dueAt = if (moved) dueAt else old.dueAt ?: dueAt,
                listDueAt = dueAt ?: old.listDueAt,
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
            // A row saved from a collapsed card may hold its title cut short: it counts only with
            // its class and due time both known and agreeing, and then takes the screen's title.
            // Another row of the full title is no bar unless it could be this one: last week's,
            // due at another time, can't.
            val cutShort = if (candidates.isEmpty() && className != null && dueAt != null) {
                saved.filter { TeamsScreens.sameTitle(it.title, title) && it.className == className && it.dueAt == dueAt }
            } else {
                emptyList()
            }
            val match = candidates.singleOrNull() ?: cutShort.singleOrNull()

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
                        attachments = detail.attachments,
                        links = detail.links,
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
                // while a same-titled row could be this class's (another week's, one of unknown
                // class, or one whose title a collapsed card cut short): that is left to the list,
                // where cards have GUIDs.
                if (saved.any { TeamsScreens.sameTitle(it.title, title) && (it.className.isEmpty() || classMayMatch(it.className, className)) }) {
                    return unchanged
                }
                val added = Assignment(
                    key = Assignment.fallbackKey(className, title),
                    title = title,
                    className = className,
                    description = detail.instructions,
                    attachments = detail.attachments,
                    links = detail.links,
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
                // Taking work off the list needs more than reading it does: with no due time to
                // tell them apart, last week's work handed in would take this week's, of the same
                // title and class, off the list for 12 hours. Completed's cards have GUIDs.
                if (dueAt == null || match.dueAt != dueAt) {
                    return unchanged.copy(notes = listOf("\"$title\" shows as handed in, with no due time to tell it by: left to Completed or a sync"))
                }
                return Merged(saved.filterIndexed { i, _ -> i != index }, listOf("\"${match.title}\" handed in"), listOf(match.key))
            }
            val updated = match.copy(
                title = title,
                className = className ?: match.className,
                description = detail.instructions,
                attachments = detail.attachments,
                links = detail.links,
                dueText = detail.dueText ?: match.dueText,
                dueAt = dueAt ?: match.dueAt,
                tab = when {
                    dueAt == null -> match.tab
                    dueAt <= wallClock -> AssignmentTab.PastDue
                    else -> AssignmentTab.Forthcoming
                },
                detailReadAt = match.detailReadAt,
            )
            // Read before and unchanged since: nothing worth saving, but it was seen open now.
            if (updated == match && match.detailReadAt != null) return unchanged.copy(seenOpen = listOf(match.key))
            val out = saved.toMutableList()
            out[index] = updated.copy(detailReadAt = wallClock, lastSyncedAt = wallClock)
            return Merged(out, listOf("read \"$title\""), seenOpen = listOf(match.key))
        }

        /** Whether [saved], kept without a GUID, is the assignment on [card]: same title, class and due time. */
        internal fun sameRow(saved: Assignment, card: ListCard, dueAt: Long?): Boolean =
            TeamsScreens.sameTitle(card.title, saved.title) &&
                (card.collapsed || classMatches(saved.className, card.className)) &&
                dueAt != null && dueAt == saved.dueAt

        /**
         * Whether [card] shows [work], handed in lately and remembered without a GUID. Unlike
         * [sameRow], a collapsed card's class counts here: another class can set work of the same
         * title for the same time, and passing its card by would keep it off the list. Only a class
         * left unknown, on either side, stands for any; a collapsed card's may also have a tag in
         * front of it.
         */
        private fun sameWork(work: Assignment, card: ListCard, dueAt: Long?): Boolean =
            TeamsScreens.sameTitle(card.title, work.title) && dueAt != null && dueAt == work.dueAt &&
                (
                    work.className.isEmpty() || card.className.isEmpty() || classMatches(work.className, card.className) ||
                        (card.collapsed && classMayMatch(work.className, card.className))
                    )

        /** The same class, by name. "Further Maths" is not "Maths". */
        private fun classMatches(a: String, b: String): Boolean = a == b

        /**
         * The row of [unkeyed] (saved from their own screens before a list gave them a Teams id, none
         * taken yet) that [card] takes over: the one of its title, class and due time ([sameRow]); else
         * the one of its title and class where no other of the [listed] cards is of them too, as its own
         * screen's due time and the list's can differ. Reading along and a sync both find it so.
         */
        internal fun takenOver(card: ListCard, dueAt: Long?, unkeyed: List<Assignment>, listed: List<ListCard>): Assignment? =
            unkeyed.firstOrNull { sameRow(it, card, dueAt) }
                ?: unkeyed.firstOrNull { saved -> named(saved, card) && listed.count { named(saved, it) } == 1 }

        /** Whether [card] is of [saved]'s title and class (a collapsed card's class can carry a tag, so it isn't asked). */
        private fun named(saved: Assignment, card: ListCard): Boolean =
            TeamsScreens.sameTitle(card.title, saved.title) && (card.collapsed || classMatches(saved.className, card.className))

        /**
         * Whether two class names could be the same class: the same, or one ending in the other, as
         * a collapsed card's does with a tag in front (`Challenge 12.2-PH3`), and as a row saved
         * from one before 0.5.0 may still. Only for holding back, never for picking a row to change.
         */
        private fun classMayMatch(a: String, b: String): Boolean = a == b || a.endsWith(" $b") || b.endsWith(" $a")

        /** The due text a sync saves for a card whose detail screen it didn't read. */
        private fun listDueText(card: ListCard): String = listOfNotNull(card.headerDate, card.dueLine).joinToString(" · ")
    }
}
