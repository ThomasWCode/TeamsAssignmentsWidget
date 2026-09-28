package com.teamsassignments.widget.automation

import com.teamsassignments.widget.automation.TeamsSelectors.Tab
import kotlinx.coroutines.delay

/**
 * A scripted phone that serves the Phase 0 fixtures: each tab shows its captured list, a card
 * opens its captured detail screen, and Back returns to the list. Hooks simulate the ways a real
 * run goes wrong.
 */
class FakeTeamsDevice(
    private val lists: Map<Tab, String> = mapOf(
        Tab.Forthcoming to "list_forthcoming",
        Tab.PastDue to "list_past_due",
        Tab.Completed to "list_completed",
    ),
) : TeamsDevice {

    sealed interface Screen {
        data object Home : Screen
        data object OtherApp : Screen
        data class List(val tab: Tab) : Screen
        data class Detail(val id: String, val from: Tab) : Screen
    }

    var screen: Screen = Screen.Home
    val clicked = mutableListOf<String>()
    val tapped = mutableListOf<Pair<Int, Int>>()
    var backPresses = 0
    var teamsInstalled = true

    /** Where the deep link lands; the Forthcoming list by default. */
    var launchLandsOn: Screen = Screen.List(Tab.Forthcoming)

    /** Card ids whose clicks are swallowed this many more times, like a WebView ignoring them. */
    val swallowClicks = mutableMapOf<String, Int>()
    var tapsOpenCards = true

    /** Runs after every action, to script events such as the user leaving Teams. */
    var afterAction: (FakeTeamsDevice) -> Unit = {}

    val cardClicks: Int get() = clicked.count { TeamsSelectors.CARD_ID.matches(it) }

    private val trees = mutableMapOf<String, FakeNode>()
    private fun tree(name: String) = trees.getOrPut(name) { Fixtures.load(name, ::onAction) }

    private fun detailFixture(id: String) = "detail_${id.take(8)}".takeIf(Fixtures::exists)

    override fun launchAssignments(): Boolean {
        if (!teamsInstalled) return false
        screen = launchLandsOn
        return true
    }

    override fun teamsRoot(): UiNode? = when (val s = screen) {
        is Screen.List -> tree(lists.getValue(s.tab))
        is Screen.Detail -> tree(detailFixture(s.id) ?: error("No detail fixture for ${s.id}"))
        else -> null
    }

    override fun foregroundPackage(): String = when (screen) {
        is Screen.List, is Screen.Detail -> TeamsSelectors.TEAMS_PACKAGE
        Screen.OtherApp -> "com.whatsapp"
        Screen.Home -> "com.sec.android.app.launcher"
    }

    override suspend fun awaitChange(timeoutMs: Long) = delay(timeoutMs)

    override fun back(): Boolean {
        backPresses++
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
        val list = screen as? Screen.List
        if (list != null && tapsOpenCards) {
            val card = tree(lists.getValue(list.tab)).walk()
                .firstOrNull { TeamsSelectors.CARD_ID.matches(it.viewId) && it.bounds.contains(x, y) }
            if (card != null && detailFixture(card.viewId) != null) screen = Screen.Detail(card.viewId, list.tab)
        }
        afterAction(this)
        return true
    }

    private fun onAction(node: FakeNode, action: UiAction): Boolean {
        val handled = when (action) {
            UiAction.Click -> click(node)
            UiAction.ShowOnScreen -> true
            // The captured lists hold every card, so there is never anything more to scroll to.
            UiAction.ScrollForward, UiAction.ScrollBackward -> false
        }
        afterAction(this)
        return handled
    }

    private fun click(node: FakeNode): Boolean {
        clicked += node.viewId
        Tab.entries.firstOrNull { it.viewId == node.viewId }?.let {
            screen = Screen.List(it)
            return true
        }
        val list = screen as? Screen.List ?: return false
        if (!TeamsSelectors.CARD_ID.matches(node.viewId)) return false
        val swallow = swallowClicks[node.viewId] ?: 0
        if (swallow > 0) {
            swallowClicks[node.viewId] = swallow - 1
            return true
        }
        if (detailFixture(node.viewId) != null) screen = Screen.Detail(node.viewId, list.tab)
        return true
    }
}
