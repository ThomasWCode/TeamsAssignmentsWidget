package com.teamsassignments.widget.automation

import com.teamsassignments.widget.automation.TeamsSelectors.Tab

/** Ends a run. The previous data is kept and [reason] is shown on the widget. */
class SyncAbort(val reason: String) : Exception(reason)

/** One step didn't reach its screen in time. Recoverable: the caller may retry or skip. */
class StepTimeout(what: String) : Exception("Timed out waiting for $what")

data class AutomationConfig(
    /** Longest wait between checks when no accessibility event arrives. */
    val pollMs: Long = 250,
    val launchTimeoutMs: Long = 20_000,
    val stepTimeoutMs: Long = 10_000,
    /** How long to wait for a detail screen after each way of opening a card. */
    val openDetailTimeoutMs: Long = 5_000,
    /** How long a list or detail screen must stay unchanged to count as loaded. */
    val settleMs: Long = 600,
    /** An empty list, or one identical to the previous tab's, must stay put longer. */
    val slowSettleMs: Long = 2_000,
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
 * Safety: the only nodes ever clicked are the tabs and assignment cards (see [click]); gesture
 * taps land inside a card and never on a button (see [safeTapPoint]).
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

    protected fun begin() {
        startedAt = now()
        teamsShown = false
    }

    protected fun checkAbort() {
        if (isCancelled()) throw SyncAbort("Cancelled")
        val t = now()
        if (t - startedAt > config.globalTimeoutMs) throw SyncAbort("Took too long")
        if (device.foregroundPackage() == TeamsSelectors.TEAMS_PACKAGE) {
            teamsShown = true
            teamsSeenAt = t
        } else if (teamsShown && t - teamsSeenAt > config.foregroundGraceMs) {
            throw SyncAbort("Teams was closed")
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

    /** Selects [tab] if needed and returns its cards once the list has finished loading. */
    protected suspend fun selectTab(tab: Tab, previousTabIds: Set<String> = emptySet()): List<ListCard> {
        val root = awaitScreen("the ${tab.name} tab") { root -> root.takeIf { TeamsScreens.tabNode(it, tab) != null } }
        if (TeamsScreens.selectedTab(root) != tab) {
            click(TeamsScreens.tabNode(root, tab) ?: throw StepTimeout("the ${tab.name} tab"))
        }
        return awaitSettledList(tab, previousTabIds)
    }

    /**
     * Waits for [tab]'s list to stop changing. A list that is empty, or still identical to the
     * previous tab's, has to hold for longer, in case the new tab is still loading.
     */
    protected suspend fun awaitSettledList(tab: Tab, previousTabIds: Set<String> = emptySet()): List<ListCard> {
        var lastIds: List<String>? = null
        var stableSince = 0L
        return awaitScreen("the ${tab.name} list") { root ->
            if (TeamsScreens.selectedTab(root) != tab) {
                lastIds = null
                return@awaitScreen null
            }
            val cards = TeamsScreens.cards(root)
            val ids = cards.map { it.id }
            val t = now()
            if (ids != lastIds) {
                lastIds = ids
                stableSince = t
                return@awaitScreen null
            }
            val needed = if (ids.isEmpty() || ids.toSet() == previousTabIds) config.slowSettleMs else config.settleMs
            cards.takeIf { t - stableSince >= needed }
        }
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
        device.awaitChange(config.pollMs)

        val fresh = device.teamsRoot()?.let { TeamsScreens.findCard(it, id) } ?: card
        click(fresh)
        var detail = awaitDetail()

        if (detail == null) {
            // Only tap while the list is definitely still showing: a detail screen that opened
            // late would put its own buttons where the card was.
            val root = device.teamsRoot()?.takeIf { TeamsScreens.isList(it) && !TeamsScreens.isDetail(it) }
            val target = root?.let { TeamsScreens.findCard(it, id) }
            val point = if (root != null && target != null) safeTapPoint(root, target) else null
            if (point == null) {
                log("Click on ${fresh.describe()} did nothing and there is no safe point to tap")
                return null
            }
            log("Click did nothing; tapping (${point.first}, ${point.second})")
            device.tap(point.first, point.second)
            detail = awaitDetail()
        }

        if (detail != null && expectedTitle != null && !sameTitle(detail.title, expectedTitle)) {
            log("Opened \"${detail.title}\" instead of \"$expectedTitle\"")
            backToList()
            return null
        }
        return detail
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

    /** Finds a card by GUID, scrolling through the list if Teams ever virtualises it. */
    private suspend fun findCardNode(id: String): UiNode? {
        device.teamsRoot()?.let { TeamsScreens.findCard(it, id) }?.let { return it }
        for (direction in listOf(UiAction.ScrollForward, UiAction.ScrollBackward)) {
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

    /** Clicks a tab or an assignment card. Anything else is refused, and ends the run. */
    protected fun click(node: UiNode) {
        val isTab = Tab.entries.any { it.viewId == node.viewId }
        val isCard = TeamsSelectors.CARD_ID.matches(node.viewId)
        val safe = (isTab || isCard) &&
            !node.className.endsWith("Button") &&
            !(isTab && TeamsSelectors.FORBIDDEN_CONTROL.containsMatchIn(node.label))
        if (!safe) {
            log("Refused to click ${node.describe()}")
            throw SyncAbort("Blocked an unexpected click")
        }
        log("Click ${node.describe()}")
        if (!node.perform(UiAction.Click)) log("Teams reported the click on ${node.describe()} failed")
    }

    /**
     * A point inside the card's title, below the tab bar, that no button or dangerous control
     * covers (the list has a floating "About Assignments" button, for one).
     */
    internal fun safeTapPoint(root: UiNode, card: UiNode): Pair<Int, Int>? {
        val title = card.walk().firstOrNull { it.viewId.startsWith(TeamsSelectors.CARD_TITLE_ID_PREFIX) } ?: card
        val tabBarBottom = Tab.entries.mapNotNull { TeamsScreens.tabNode(root, it)?.bounds?.bottom }.maxOrNull() ?: 0
        val area = title.bounds.intersect(card.bounds).let { it.copy(top = maxOf(it.top, tabBarBottom + 1)) }
        if (area.isEmpty) return null
        val x = area.centerX
        val y = area.centerY
        val inCard = card.walk().toSet()
        val blocked = root.walk().any { node ->
            node !in inCard && node.bounds.contains(x, y) &&
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
}
