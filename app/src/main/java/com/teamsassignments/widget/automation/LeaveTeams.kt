package com.teamsassignments.widget.automation

/** Where the phone was before a sync opened Teams, so that it can go back there afterwards. */
sealed interface Origin {
    /** Teams itself: the sync leaves it on the Assignments list it ends on. */
    data object Teams : Origin

    /** The home screen, the widget's; also where it goes when the app in front couldn't be told. */
    data object Home : Origin

    /**
     * Another app, this one's own screen included. [label] is its name on its Recents card, null
     * if it couldn't be read; [reopen] starts it afresh when its card can't be used, false if it can't.
     */
    class App(val packageName: String, val label: String?, val reopen: () -> Boolean) : Origin
}

data class LeaveConfig(
    /** Longest wait between checks: the launcher's events don't reach the service, so it polls. */
    val pollMs: Long = 150,
    val openTimeoutMs: Long = 3_000,
    val closeTimeoutMs: Long = 2_000,
    val returnTimeoutMs: Long = 3_000,
)

/**
 * Leaves Teams once a sync is done: closes Teams' card on the Recents screen, so it isn't left
 * there to swipe away, then goes back to the [Origin]. No app may remove another's task, and
 * Teams' link always opens Teams in a fresh one (it replaces any older one itself), so the card's
 * own Close action is the only way. Then the origin's card is clicked, which brings that app back
 * as it was.
 *
 * Safety: on Recents, only the Close action of the cards named [teamsLabel] and the click action
 * of the origin's card are used, never "Close all" or anything else. When Recents doesn't open
 * or doesn't look as expected, it goes Home as before 0.7.0 (Teams' card left where it is), and
 * once the user has left Recents themselves, nothing more is pressed.
 */
class LeaveTeams(
    private val device: TeamsDevice,
    private val now: () -> Long,
    private val log: (String) -> Unit,
    private val teamsLabel: String,
    private val config: LeaveConfig = LeaveConfig(),
) {
    suspend fun run(origin: Origin) {
        if (origin == Origin.Teams) {
            log("Teams was in front before the sync: left on Assignments")
            return
        }
        val recents = if (device.recents()) await(config.openTimeoutMs) { device.recentsRoot() } else null
        if (recents == null) {
            log("Recents didn't open: Teams left there")
            fallBack(origin)
            return
        }
        closeTeams(recents)
        if (device.recentsRoot() == null) {
            log("Recents was left: nothing more pressed")
            return
        }
        goBack(origin)
    }

    private suspend fun closeTeams(recents: UiNode) {
        val cards = RecentsScreen.cardsOf(recents, teamsLabel)
        if (cards.isEmpty()) {
            log("No Teams card on Recents")
            return
        }
        val pressed = cards.count { it.node.performCustom(RecentsScreen.CLOSE_ACTION) }
        if (pressed == 0) {
            log("Teams' card had no Close action")
            return
        }
        val gone = await(config.closeTimeoutMs) { device.recentsRoot()?.takeIf { RecentsScreen.cardsOf(it, teamsLabel).isEmpty() } }
        log(if (gone != null) "Closed Teams' card on Recents" else "Teams' card stayed on Recents")
    }

    private suspend fun goBack(origin: Origin) {
        val app = origin as? Origin.App
        if (app == null) {
            device.home()
            return
        }
        val card = app.label?.let { label -> device.recentsRoot()?.let { RecentsScreen.cardsOf(it, label).firstOrNull() } }
        if (card != null && card.node.perform(UiAction.Click)) {
            if (await(config.returnTimeoutMs) { device.foregroundPackage()?.takeIf { it == app.packageName } } != null) {
                log("Back to ${app.label}")
                return
            }
            // Whatever is in front now, the user's choice or the card's, is left alone.
            if (device.recentsRoot() == null) {
                log("${app.label}'s card didn't bring it back")
                return
            }
        }
        fallBack(origin)
    }

    /** Back to the origin without Recents: the app started afresh, or failing that the home screen. */
    private fun fallBack(origin: Origin) {
        if (origin is Origin.App && origin.reopen()) {
            log("${origin.label ?: origin.packageName} reopened")
            return
        }
        device.home()
    }

    /** Polls [check] until it gives a value or [timeoutMs] passes. */
    private suspend fun <T : Any> await(timeoutMs: Long, check: () -> T?): T? {
        val deadline = now() + timeoutMs
        while (true) {
            check()?.let { return it }
            if (now() >= deadline) return null
            device.awaitChange(config.pollMs)
        }
    }
}
