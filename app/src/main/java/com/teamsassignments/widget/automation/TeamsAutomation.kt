package com.teamsassignments.widget.automation

import com.teamsassignments.widget.automation.TeamsSelectors.Tab

/** Ends a run. The previous data is kept and [reason] is shown on the widget. */
class SyncAbort(val reason: String) : Exception(reason) {
    /** The user stopped the run themselves, by cancelling or by leaving Teams. */
    val byUser: Boolean get() = reason == CANCELLED || reason == LEFT_TEAMS

    companion object {
        const val CANCELLED = "Cancelled"
        const val LEFT_TEAMS = "Teams was closed"
    }
}

/** One step didn't reach its screen in time. Recoverable: the caller may retry or skip. */
class StepTimeout(what: String) : Exception("Timed out waiting for $what")

data class AutomationConfig(
    /** Longest wait between checks when no accessibility event arrives. */
    val pollMs: Long = 250,
    val launchTimeoutMs: Long = 20_000,
    val stepTimeoutMs: Long = 10_000,
    /** How long to wait for a detail screen after each press of a card. */
    val openDetailTimeoutMs: Long = 5_000,
    /** How long to wait for a tab to show as selected after each press. */
    val tabSwitchTimeoutMs: Long = 2_500,
    /** How long a list or detail screen must stay unchanged to count as loaded. */
    val settleMs: Long = 600,
    /** How long an empty list must stay empty, with nothing loading, before it is believed. */
    val emptySettleMs: Long = 2_000,
    /** The same, for a tab that had assignments at the last sync, where "empty" is more suspect. */
    val suspectEmptySettleMs: Long = 6_000,
    /** How long the user can be away from Teams before the run is abandoned. */
    val foregroundGraceMs: Long = 2_000,
    val globalTimeoutMs: Long = 180_000,
    /** Wait before repeating Back when the previous press seems to have been missed. */
    val backRetryMs: Long = 3_000,
    val maxScrolls: Int = 15,
)

/**
 * The steps both workflows share: waiting for screens, switching tabs and opening a card.
 *
 * Every wait goes through [awaitScreen], which also checks the abort conditions: the user
 * cancelled, left Teams for longer than [AutomationConfig.foregroundGraceMs], or the whole run
 * passed [AutomationConfig.globalTimeoutMs].
 *
 * Tabs and cards are pressed with a gesture tap first. On the phone, Teams' web content ignored
 * every accessibility click action (each returned true and did nothing) but responded to taps, so
 * the click action is only the fallback, for a target with no safe point on screen.
 *
 * Safety: only tabs and assignment cards are ever pressed (see [requirePressable]), and a tap
 * lands inside the target, never on a button or dangerous control (see [safeTapPoint]).
 */
abstract class TeamsAutomation(
    protected val device: TeamsDevice,
    protected val config: AutomationConfig,
    protected val now: () -> Long,
    protected val log: (String) -> Unit,
    private val isCancelled: () -> Boolean,
) {
    private var startedAt = 0L
    private var teamsSeenAt = 0L
    private var teamsShown = false

    private enum class Press { Tap, Click }

    protected fun begin() {
        startedAt = now()
        teamsShown = false
    }

    protected fun checkAbort() {
        if (isCancelled()) throw SyncAbort(SyncAbort.CANCELLED)
        val t = now()
        if (t - startedAt > config.globalTimeoutMs) throw SyncAbort("Took too long")
        if (device.foregroundPackage() == TeamsSelectors.TEAMS_PACKAGE) {
            teamsShown = true
            teamsSeenAt = t
        } else if (teamsShown && t - teamsSeenAt > config.foregroundGraceMs) {
            throw SyncAbort(SyncAbort.LEFT_TEAMS)
        }
    }

    /** Waits until [match] finds what it needs on the Teams screen, checking abort conditions throughout. */
    protected suspend fun <T : Any> awaitScreen(
        what: String,
        timeoutMs: Long = config.stepTimeoutMs,
        match: (UiNode) -> T?,
    ): T {
        val deadline = now() + timeoutMs
        while (true) {
            checkAbort()
            device.teamsRoot()?.let(match)?.let { return it }
            val remaining = deadline - now()
            if (remaining <= 0) throw StepTimeout(what)
            device.awaitChange(minOf(config.pollMs, remaining))
        }
    }

    /** Opens Teams on the Assignments list, backing out of a detail screen if Teams resumes on one. */
    protected suspend fun openAssignments() {
        log("Opening Teams Assignments")
        if (!device.launchAssignments()) throw SyncAbort("Teams isn't installed")
        // Give the deep link a moment to land before assuming Teams stayed on an old detail screen.
        var lastBack = now() - config.backRetryMs / 2
        try {
            awaitScreen("the Assignments list", config.launchTimeoutMs) { root ->
                when {
                    TeamsScreens.isList(root) -> true
                    TeamsScreens.isDetail(root) && now() - lastBack > config.backRetryMs -> {
                        log("Teams resumed on a detail screen; going back")
                        device.back()
                        lastBack = now()
                        null
                    }
                    else -> null
                }
            }
        } catch (_: StepTimeout) {
            throw SyncAbort("Couldn't open Teams Assignments")
        }
    }

    /**
     * Selects [tab] if needed and returns its cards once the list has finished loading.
     * Each press is checked before the next: tap, then the click action, then tap again.
     * [emptySettleMs] is how long an empty list must hold before it is believed.
     */
    protected suspend fun selectTab(
        tab: Tab,
        previousTabIds: Set<String> = emptySet(),
        emptySettleMs: Long = config.emptySettleMs,
    ): List<ListCard> {
        for ((i, how) in TAB_PRESSES.withIndex()) {
            val root = awaitScreen("the ${tab.label} tab") { it.takeIf { r -> TeamsScreens.tabNode(r, tab) != null } }
            if (TeamsScreens.selectedTab(root) == tab) break
            val node = TeamsScreens.tabNode(root, tab) ?: continue
            if (press(root, node, node.bounds, how) && awaitTabSelected(tab)) {
                if (i > 0) log("${tab.label} tab selected on attempt ${i + 1}")
                break
            }
            log("${tab.label} tab didn't switch (attempt ${i + 1} of ${TAB_PRESSES.size})")
        }
        return awaitSettledList(tab, previousTabIds, emptySettleMs)
    }

    private suspend fun awaitTabSelected(tab: Tab): Boolean = try {
        awaitScreen("the ${tab.label} tab to be selected", config.tabSwitchTimeoutMs) { root ->
            true.takeIf { TeamsScreens.selectedTab(root) == tab }
        }
    } catch (_: StepTimeout) {
        false
    }

    /**
     * Waits for [tab]'s list to finish loading, which matters because a sync saves whatever this
     * returns. Teams can mark a tab selected before its rows are replaced, so:
     * - the previous tab's rows, still showing, are never taken as this tab's (an assignment is
     *   on one tab only), however long they stay;
     * - nothing counts while a loading indicator shows;
     * - an empty list must hold for [emptySettleMs] before it is believed.
     * If it never settles, the step times out and the caller keeps the data it had.
     */
    protected suspend fun awaitSettledList(
        tab: Tab,
        previousTabIds: Set<String> = emptySet(),
        emptySettleMs: Long = config.emptySettleMs,
    ): List<ListCard> {
        var lastIds: List<String>? = null
        var stableSince = 0L
        return awaitScreen("the ${tab.label} list", config.stepTimeoutMs + emptySettleMs) { root ->
            if (TeamsScreens.selectedTab(root) != tab) {
                lastIds = null
                return@awaitScreen null
            }
            val cards = TeamsScreens.cards(root)
            val ids = cards.map { it.id }
            val t = now()
            if (ids != lastIds || TeamsScreens.isLoading(root)) {
                lastIds = ids
                stableSince = t
                return@awaitScreen null
            }
            if (ids.isNotEmpty() && ids.toSet() == previousTabIds) return@awaitScreen null // the old tab's rows
            val needed = if (ids.isEmpty()) emptySettleMs else config.settleMs
            cards.takeIf { t - stableSince >= needed }
        }
    }

    /**
     * Whether the list visibly ends on screen: the last card sits above the bottom of the
     * scrolling area, so nothing can be hidden below it. Teams currently puts every card in the
     * tree anyway, but a virtualised list would only hold the rows in view.
     */
    protected fun listEndsOnScreen(root: UiNode, cards: List<ListCard>): Boolean {
        val scroller = root.walk().firstOrNull { it.isScrollable } ?: return true
        val last = cards.lastOrNull() ?: return false
        return !last.bounds.isEmpty && last.bounds.bottom < scroller.bounds.bottom - LIST_END_MARGIN_PX
    }

    /**
     * Opens the card with GUID [id] on the current list and returns its detail screen, or null if
     * it didn't open or the wrong assignment opened (in which case this goes back to the list).
     */
    protected suspend fun openCard(id: String, expectedTitle: String?): DetailScreen? {
        val card = findCardNode(id) ?: run {
            log("Card $id is not on the list")
            return null
        }
        card.perform(UiAction.ShowOnScreen)

        var detail: DetailScreen? = null
        for (how in CARD_PRESSES) {
            // Press only while the list is showing: a detail screen that opened late would have
            // its own buttons where the card was.
            val root = awaitCardOnScreen(id) ?: break
            val target = TeamsScreens.findCard(root, id) ?: break
            if (!press(root, target, cardTapArea(root, target), how)) {
                log("${target.describe()}: the ${how.name.lowercase()} didn't go through")
                continue
            }
            detail = awaitDetail()
            if (detail != null) break
            if (device.teamsRoot()?.let(TeamsScreens::isDetail) == true) {
                detail = awaitDetail() // it opened, just slowly
                break
            }
            log("${target.describe()} didn't open (${how.name.lowercase()})")
        }

        if (detail == null && device.teamsRoot()?.let(TeamsScreens::isDetail) == true) {
            // A detail screen opened but never became readable: leave it, so the caller is on the list.
            log("The detail screen couldn't be read; going back")
            backToList()
            return null
        }
        if (detail != null && expectedTitle != null && !sameTitle(detail.title, expectedTitle)) {
            log("Opened \"${detail.title}\" instead of \"$expectedTitle\"")
            backToList()
            return null
        }
        return detail
    }

    /** The list, once the card has stopped moving after being scrolled into view. */
    private suspend fun awaitCardOnScreen(id: String): UiNode? {
        var lastBounds: IntRect? = null
        return try {
            awaitScreen("the card to come into view", config.settleMs * 3) { root ->
                if (!TeamsScreens.isList(root) || TeamsScreens.isDetail(root)) return@awaitScreen null
                val bounds = TeamsScreens.findCard(root, id)?.bounds ?: return@awaitScreen null
                val settled = bounds == lastBounds && !bounds.isEmpty
                lastBounds = bounds
                root.takeIf { settled }
            }
        } catch (_: StepTimeout) {
            // Still off screen (or never stopped moving): return the list; the press falls back to a click.
            device.teamsRoot()?.takeIf { TeamsScreens.isList(it) && !TeamsScreens.isDetail(it) }
        }
    }

    /** The detail screen once its content has rendered and stopped changing, or null on timeout. */
    private suspend fun awaitDetail(): DetailScreen? {
        var last: DetailScreen? = null
        var stableSince = 0L
        return try {
            awaitScreen("the detail screen", config.openDetailTimeoutMs) { root ->
                val detail = TeamsScreens.detail(root)?.takeIf { it.title != null } ?: return@awaitScreen null
                val t = now()
                if (detail != last) {
                    last = detail
                    stableSince = t
                    null
                } else {
                    detail.takeIf { t - stableSince >= config.settleMs }
                }
            }
        } catch (_: StepTimeout) {
            null
        }
    }

    /** Presses Back until the list shows, repeating only if Teams seems to have missed the press. */
    protected suspend fun backToList() {
        device.back()
        var lastBack = now()
        try {
            awaitScreen("the Assignments list") { root ->
                when {
                    TeamsScreens.isList(root) -> true
                    TeamsScreens.isDetail(root) && now() - lastBack > config.backRetryMs -> {
                        device.back()
                        lastBack = now()
                        null
                    }
                    else -> null
                }
            }
        } catch (_: StepTimeout) {
            throw SyncAbort("Couldn't get back to the Assignments list")
        }
    }

    /**
     * Finds a card by GUID. If it isn't in the tree and the list doesn't visibly end on screen,
     * scrolls down and then up looking for it, in case Teams ever virtualises the list.
     */
    private suspend fun findCardNode(id: String): UiNode? {
        val first = device.teamsRoot() ?: return null
        TeamsScreens.findCard(first, id)?.let { return it }
        // A list that ends on screen has nothing more below, but may still have rows above.
        val directions = if (listEndsOnScreen(first, TeamsScreens.cards(first))) {
            listOf(UiAction.ScrollBackward)
        } else {
            listOf(UiAction.ScrollForward, UiAction.ScrollBackward)
        }
        for (direction in directions) {
            var scrolls = 0
            while (scrolls < config.maxScrolls) {
                checkAbort()
                val root = device.teamsRoot() ?: return null
                TeamsScreens.findCard(root, id)?.let { return it }
                val scroller = root.walk().firstOrNull { it.isScrollable } ?: return null
                if (!scroller.perform(direction)) break
                scrolls++
                device.awaitChange(config.settleMs)
            }
        }
        return device.teamsRoot()?.let { TeamsScreens.findCard(it, id) }
    }

    /**
     * Taps [node] inside [area], or uses its click action when [how] says so or nowhere is safe to
     * tap. Returns false when the press didn't go through, so the caller needn't wait for it.
     */
    private suspend fun press(root: UiNode, node: UiNode, area: IntRect, how: Press): Boolean {
        requirePressable(node)
        val point = if (how == Press.Tap) safeTapPoint(root, node, area) else null
        if (point == null) return click(node)
        log("Tap ${node.describe()}")
        return device.tap(point.first, point.second)
    }

    /** Uses a tab's or card's click action. Anything else is refused, and ends the run. */
    protected fun click(node: UiNode): Boolean {
        requirePressable(node)
        log("Click ${node.describe()}")
        return node.perform(UiAction.Click).also { if (!it) log("Teams reported the click on ${node.describe()} failed") }
    }

    /** Only tabs and assignment cards may be pressed, never buttons or dangerous controls. */
    private fun requirePressable(node: UiNode) {
        val isTab = Tab.entries.any { it.viewId == node.viewId }
        val isCard = TeamsSelectors.CARD_ID.matches(node.viewId)
        val safe = (isTab || isCard) &&
            !node.className.endsWith("Button") &&
            !(isTab && TeamsSelectors.FORBIDDEN_CONTROL.containsMatchIn(node.label))
        if (!safe) {
            log("Refused to press ${node.describe()}")
            throw SyncAbort("Blocked an unexpected click")
        }
    }

    /** A card's title, below the tab bar: the part of a card it is safe to tap. */
    private fun cardTapArea(root: UiNode, card: UiNode): IntRect {
        val title = card.walk().firstOrNull { it.viewId.startsWith(TeamsSelectors.CARD_TITLE_ID_PREFIX) } ?: card
        val tabBarBottom = Tab.entries.mapNotNull { TeamsScreens.tabNode(root, it)?.bounds?.bottom }.maxOrNull() ?: 0
        return title.bounds.intersect(card.bounds).let { it.copy(top = maxOf(it.top, tabBarBottom + 1)) }
    }

    /**
     * A point inside a card's title, below the tab bar, that no button or dangerous control
     * covers (the list has a floating "About Assignments" button, for one).
     */
    internal fun safeTapPoint(root: UiNode, card: UiNode): Pair<Int, Int>? =
        safeTapPoint(root, card, cardTapArea(root, card))

    /** The centre of [area] inside [target], unless a button or dangerous control outside [target] covers it. */
    internal fun safeTapPoint(root: UiNode, target: UiNode, area: IntRect): Pair<Int, Int>? {
        if (area.isEmpty) return null
        val x = area.centerX
        val y = area.centerY
        val inTarget = target.walk().toSet()
        val blocked = root.walk().any { node ->
            node !in inTarget && node.bounds.contains(x, y) &&
                (node.className.endsWith("Button") || TeamsSelectors.FORBIDDEN_CONTROL.containsMatchIn(node.label))
        }
        return if (blocked) null else x to y
    }

    private fun sameTitle(actual: String?, expected: String): Boolean {
        val a = actual?.normalizedTitle() ?: return false
        val e = expected.normalizedTitle()
        // A collapsed card's title is parsed from concatenated text, so allow a prefix match.
        return a == e || e.startsWith(a)
    }

    private fun String.normalizedTitle() = lowercase().replace(Regex("\\s+"), " ").trim()

    private companion object {
        // Taps are what work on the phone, so a missed tap gets a second one; the click action in
        // between costs little and covers a target that has scrolled out of reach.
        val TAB_PRESSES = listOf(Press.Tap, Press.Click, Press.Tap)
        val CARD_PRESSES = listOf(Press.Tap, Press.Click, Press.Tap)

        /** How far above the bottom of the scrolling area the last card must end to count as the end. */
        const val LIST_END_MARGIN_PX = 24
    }
}
