package com.teamsassignments.widget.automation

import com.teamsassignments.widget.data.Assignment

/**
 * The row-tap workflow: open Teams Assignments and open [Assignment]'s card.
 *
 * Teams exposes no per-assignment deep link without the class id, so this finds the card by its
 * GUID, trying the tab it was last seen on first (a deadline may have moved it to Past due since).
 * Returns false when the card can't be found; Teams is then left on the Assignments list.
 */
class NavigateStateMachine(
    device: TeamsDevice,
    config: AutomationConfig = AutomationConfig(globalTimeoutMs = 15_000),
    now: () -> Long,
    log: (String) -> Unit = {},
    isCancelled: () -> Boolean = { false },
) : TeamsAutomation(device, config, now, log, isCancelled) {

    suspend fun run(target: Assignment): Boolean {
        begin()
        log("Opening \"${target.title}\"")
        openAssignments()

        val first = TeamsSelectors.tabFor(target.tab)
        val tabs = listOf(first) + TeamsSelectors.OPEN_TABS.filter { it != first }
        var previousTabIds = emptySet<String>()
        for (tab in tabs) {
            val cards = try {
                selectTab(tab, previousTabIds)
            } catch (_: StepTimeout) {
                continue
            }
            previousTabIds = cards.map { it.id }.toSet()
            val card = cards.firstOrNull { it.id == target.key }
                ?: cards.firstOrNull { it.title == target.title && (it.className == target.className || it.collapsed) }
                ?: continue
            val detail = try {
                openCard(card.id, card.title)
            } catch (_: StepTimeout) {
                null
            }
            if (detail != null) {
                log("Opened \"${detail.title}\"")
                return true
            }
        }
        log("Couldn't find \"${target.title}\"")
        return false
    }
}
