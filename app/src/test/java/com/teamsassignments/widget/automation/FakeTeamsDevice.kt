package com.teamsassignments.widget.automation

import com.teamsassignments.widget.automation.TeamsSelectors.Tab
import kotlinx.coroutines.delay

/**
 * A scripted phone that serves the Phase 0 fixtures: each tab shows its captured list, a card
 * opens its captured detail screen, and Back returns to the list. Taps and click actions both
 * work by default; hooks simulate the ways a real run goes wrong.
 */
class FakeTeamsDevice(
    private val lists: Map<Tab, String> = mapOf(
        Tab.Forthcoming to "list_forthcoming",
        Tab.PastDue to "list_past_due",
        Tab.Completed to "list_completed",
    ),
    /** Virtual time, for tabs that take a while to load (see [slowTabs]). */
    private val now: () -> Long = { 0L },
) : TeamsDevice {

    sealed interface Screen {
        data object Home : Screen
        data object OtherApp : Screen
        data class List(val tab: Tab) : Screen
        data class Detail(val id: String, val from: Tab) : Screen
    }

    var screen: Screen = Screen.Home
    var backPresses = 0
    var teamsInstalled = true

    /** View ids that got a click action. */
    val clicked = mutableListOf<String>()

    /** Tap points, and the view id of the tab or card under each ("" for neither). */
    val tapped = mutableListOf<Pair<Int, Int>>()
    val tappedTargets = mutableListOf<String>()

    /** Cards whose detail screen was opened, by either means, in order. */
    val opened = mutableListOf<String>()

    /** Scroll actions requested (none ever moves: the captures already hold every card). */
    val scrolls = mutableListOf<UiAction>()

    /** Where the deep link lands; the Forthcoming list by default. */
    var launchLandsOn: Screen = Screen.List(Tab.Forthcoming)

    /** Card ids whose click actions are swallowed this many more times, like a WebView ignoring them. */
    val swallowClicks = mutableMapOf<String, Int>()
    var tapsOpenCards = true

    /** How many more tab click actions / tab taps to ignore. */
    var swallowTabClicks = 0
    var ignoreTabTaps = 0

    /** Card ids whose taps are ignored this many more times. */
    val ignoreCardTaps = mutableMapOf<String, Int>()

    /** A tab that loads slowly: for this long after switching to it, show this fixture instead. */
    val slowTabs = mutableMapOf<Tab, Pair<String, Long>>()

    /** Detail screens to show instead of the captured ones, by card id. */
    val detailOverrides = mutableMapOf<String, String>()

    /**
     * After Back, the list slides in: shifted by this many pixels for this long. On the phone it
     * was caught 337 px to the left, which put the Forthcoming tab's centre at a negative x.
     */
    var backTransition: Pair<Int, Long>? = null

    private var tabShownAt = 0L
    private var backAt = Long.MIN_VALUE / 2

    /** Runs after every action, to script events such as the user leaving Teams. */
    var afterAction: (FakeTeamsDevice) -> Unit = {}

    /** Every tab or card the automation pressed, by click action or tap. */
    val pressed: List<String> get() = clicked + tappedTargets

    private val trees = mutableMapOf<String, FakeNode>()
    private fun tree(name: String) = trees.getOrPut(name) { Fixtures.load(name, ::onAction) }

    private fun detailFixture(id: String) = "detail_${id.take(8)}".takeIf(Fixtures::exists)

    /** What a list tab shows right now, allowing for [slowTabs]. */
    private fun listFixture(tab: Tab): String {
        val (loading, forMs) = slowTabs[tab] ?: return lists.getValue(tab)
        return if (now() - tabShownAt < forMs) loading else lists.getValue(tab)
    }

    private fun showTab(tab: Tab) {
        screen = Screen.List(tab)
        tabShownAt = now()
    }

    override fun launchAssignments(): Boolean {
        if (!teamsInstalled) return false
        screen = launchLandsOn
        return true
    }

    override fun teamsRoot(): UiNode? = when (val s = screen) {
        is Screen.List -> listOnScreen(s.tab)
        is Screen.Detail -> tree(detailOverrides[s.id] ?: detailFixture(s.id) ?: error("No detail fixture for ${s.id}"))
        else -> null
    }

    /** The list as it is on screen right now: possibly still sliding in after Back. */
    private fun listOnScreen(tab: Tab): FakeNode {
        val list = tree(listFixture(tab))
        val (dx, forMs) = backTransition ?: return list
        return if (now() - backAt < forMs) list.shifted(dx) else list
    }

    override fun foregroundPackage(): String = when (screen) {
        is Screen.List, is Screen.Detail -> TeamsSelectors.TEAMS_PACKAGE
        Screen.OtherApp -> "com.whatsapp"
        Screen.Home -> "com.sec.android.app.launcher"
    }

    override suspend fun awaitChange(timeoutMs: Long) = delay(timeoutMs)

    override fun back(): Boolean {
        backPresses++
        backAt = now()
        screen = when (val s = screen) {
            is Screen.Detail -> Screen.List(s.from)
            is Screen.List -> Screen.Home
            else -> s
        }
        afterAction(this)
        return true
    }

    override fun home(): Boolean {
        screen = Screen.Home
        return true
    }

    override suspend fun tap(x: Int, y: Int): Boolean {
        tapped += x to y
        // Like the real device: a point off the screen isn't tapped.
        if (x !in 0 until SCREEN_WIDTH || y !in 0 until SCREEN_HEIGHT) {
            tappedTargets += ""
            return false
        }
        val list = screen as? Screen.List
        if (list == null) {
            tappedTargets += ""
        } else {
            val root = listOnScreen(list.tab)
            val tab = Tab.entries.firstOrNull { root.findById(it.viewId)?.bounds?.contains(x, y) == true }
            val card = root.walk().firstOrNull { TeamsSelectors.CARD_ID.matches(it.viewId) && it.bounds.contains(x, y) }
            tappedTargets += tab?.viewId ?: card?.viewId ?: ""
            val ignoredTaps = card?.let { ignoreCardTaps[it.viewId] } ?: 0
            when {
                tab != null -> if (ignoreTabTaps > 0) ignoreTabTaps-- else showTab(tab)
                card != null && ignoredTaps > 0 -> ignoreCardTaps[card.viewId] = ignoredTaps - 1
                card != null && tapsOpenCards -> open(card.viewId, list.tab)
            }
        }
        afterAction(this)
        return true
    }

    private fun onAction(node: FakeNode, action: UiAction): Boolean {
        val handled = when (action) {
            UiAction.Click -> click(node)
            UiAction.ShowOnScreen -> true
            // The captured lists hold every card, so there is never anything more to scroll to.
            UiAction.ScrollForward, UiAction.ScrollBackward -> {
                scrolls += action
                false
            }
        }
        afterAction(this)
        return handled
    }

    private fun click(node: FakeNode): Boolean {
        clicked += node.viewId
        Tab.entries.firstOrNull { it.viewId == node.viewId }?.let {
            if (swallowTabClicks > 0) swallowTabClicks-- else showTab(it)
            return true
        }
        val list = screen as? Screen.List ?: return false
        if (!TeamsSelectors.CARD_ID.matches(node.viewId)) return false
        val swallow = swallowClicks[node.viewId] ?: 0
        if (swallow > 0) {
            swallowClicks[node.viewId] = swallow - 1
            return true
        }
        open(node.viewId, list.tab)
        return true
    }

    private companion object {
        // The Galaxy S24 the fixtures were captured on.
        const val SCREEN_WIDTH = 1080
        const val SCREEN_HEIGHT = 2340
    }

    private fun open(id: String, from: Tab) {
        if (detailOverrides[id] == null && detailFixture(id) == null) return
        opened += id
        screen = Screen.Detail(id, from)
    }
}
