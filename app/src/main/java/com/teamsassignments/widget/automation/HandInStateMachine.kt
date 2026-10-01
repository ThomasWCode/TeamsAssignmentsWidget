package com.teamsassignments.widget.automation

import com.teamsassignments.widget.automation.TeamsScreens.normalizedTitle
import com.teamsassignments.widget.data.Assignment

/** How a hand-in ended. Only [HandedIn] and [Unconfirmed] mean Hand in was pressed. */
enum class HandInResult {
    /** Teams showed the work as handed in. */
    HandedIn,

    /** Teams already showed it as handed in, so nothing was pressed. */
    AlreadyHandedIn,

    /** The assignment wasn't found among the work to hand in. Nothing was pressed. */
    NotFound,

    /**
     * Both open tabs were read in full and neither lists it, so it's taken as handed in already
     * (see [NavigateStateMachine.notListed]). Nothing was pressed.
     */
    NotListed,

    /**
     * The screen that opened wasn't exactly the chosen assignment's, going by its title (a
     * same-named one, say, whose title only starts the chosen one's). Nothing was pressed.
     */
    Mismatch,

    /** Its screen had no Hand in button that could be pressed. Nothing was pressed. */
    NoButton,

    /** Hand in was pressed, but Teams didn't show the work as handed in. It may still have gone through. */
    Unconfirmed,
}

/**
 * The hand-in workflow, run only after the user confirms on the widget:
 * 1. open the assignment the way a row tap does, by its GUID (see [NavigateStateMachine]);
 * 2. check that the screen is exactly the chosen assignment's, still not handed in, with a Hand
 *    in button that can be pressed;
 * 3. press that button with its click action. It is a native button in Teams' toolbar, which
 *    takes click actions the way TalkBack presses it; only the WebView content ignores them;
 * 4. wait for Teams to show the work as handed in.
 *
 * Safety: besides tabs and cards, the only thing ever pressed is the toolbar button reading
 * exactly `HAND IN`, `HAND IN LATE` or `HAND IN AGAIN` (see [requireHandInButton]). It is
 * pressed once; a second press happens only when Teams reports that the first didn't go through
 * at all. Undo hand in, Attach and the rest are never pressed. An assignment saved without a
 * GUID is refused, so a same-titled card can never stand in for it.
 */
class HandInStateMachine(
    device: TeamsDevice,
    config: AutomationConfig = AutomationConfig(globalTimeoutMs = 45_000),
    now: () -> Long,
    log: (String) -> Unit = {},
    isCancelled: () -> Boolean = { false },
    /**
     * Called just before Hand in is pressed, where the user can cancel: false if they already
     * have, and then nothing is pressed. After true, the run can't be called off.
     */
    private val onPressing: suspend () -> Boolean = { true },
) : TeamsAutomation(device, config, now, log, isCancelled) {

    // The hand-in's own timings, so a slow Teams gets the same allowance while it's being found.
    private val navigate = NavigateStateMachine(device, config, now, log, isCancelled)

    /** [saved] is the whole saved list, which sets how long an empty tab must stay empty (see [NavigateStateMachine.run]). */
    suspend fun run(target: Assignment, saved: List<Assignment> = emptyList()): HandInResult {
        if (!TeamsSelectors.CARD_ID.matches(target.key)) {
            log("\"${target.title}\" has no Teams id yet, so it isn't handed in")
            return HandInResult.NotFound
        }
        log("Handing in \"${target.title}\"")
        if (!navigate.run(target, saved)) return if (navigate.notListed) HandInResult.NotListed else HandInResult.NotFound

        begin()
        val (root, detail) = try {
            awaitScreen("the assignment") { root ->
                TeamsScreens.detail(root)?.takeIf { it.title != null }?.let { root to it }
            }
        } catch (_: StepTimeout) {
            log("The assignment's screen couldn't be read")
            return HandInResult.NotFound
        }
        val title = detail.title.orEmpty()
        // Opening a card lets its title through on a prefix, as a collapsed card's is cut short. Hand
        // in goes only on a screen whose title is exactly the chosen assignment's: as the list
        // showed it, or as saved. A tap that opened a same-named assignment must not count.
        if (listOfNotNull(navigate.listedTitle, target.title).none { exactTitle(it, title) }) {
            log("\"$title\" isn't exactly \"${navigate.listedTitle ?: target.title}\", so it isn't handed in")
            return HandInResult.Mismatch
        }
        if (detail.isHandedIn || TeamsScreens.offersUndoHandIn(root)) {
            log("\"$title\" is already handed in")
            return HandInResult.AlreadyHandedIn
        }
        val button = TeamsScreens.handInButton(root)?.takeIf { it.isEnabled } ?: run {
            log("\"$title\" has no Hand in button to press")
            return HandInResult.NoButton
        }

        log("Pressing Hand in on \"$title\"")
        // The last chance to cancel: checked where the Cancel button lives, as it is taken away.
        if (!onPressing()) throw SyncAbort(SyncAbort.CANCELLED)
        if (!pressHandIn(button)) {
            // The click didn't go through, so nothing happened: try once more on a fresh copy of the
            // screen, in case Teams redrew its toolbar, as long as it still offers Hand in.
            val again = device.teamsRoot()
                ?.takeIf { stillOpen(it, title) }
                ?.let(TeamsScreens::handInButton)
                ?.takeIf { it.isEnabled }
            if (again == null || !pressHandIn(again)) {
                log("Teams didn't take the press on Hand in")
                return HandInResult.NoButton
            }
        }
        return try {
            if (awaitHandedIn(title)) HandInResult.HandedIn else HandInResult.Unconfirmed
        } catch (e: SyncAbort) {
            // Hand in has been pressed, so however the wait ends, it may have gone through.
            log("Stopped waiting for Teams: ${e.reason}")
            HandInResult.Unconfirmed
        }
    }

    /** Presses Teams' Hand in button with its click action. False if the click didn't go through. */
    private fun pressHandIn(button: UiNode): Boolean {
        requireHandInButton(button)
        log("Click ${button.describe()}")
        return button.perform(UiAction.Click).also { if (!it) log("Teams reported the click on ${button.describe()} failed") }
    }

    /** The one control besides tabs and cards that may be pressed: Teams' own, enabled Hand in button. */
    internal fun requireHandInButton(node: UiNode) {
        val label = node.label.replace(WHITESPACE, " ").trim()
        val safe = node.className.endsWith("Button") && node.isEnabled && TeamsSelectors.HAND_IN_BUTTON.matches(label)
        if (!safe) {
            log("Refused to press ${node.describe()}")
            throw SyncAbort("Blocked an unexpected click")
        }
    }

    /** Whether [root] still shows the assignment titled [title] as not handed in. */
    private fun stillOpen(root: UiNode, title: String): Boolean {
        val detail = TeamsScreens.detail(root) ?: return false
        return detail.title?.let { exactTitle(it, title) } == true && !detail.isHandedIn && !TeamsScreens.offersUndoHandIn(root)
    }

    private fun exactTitle(a: String, b: String) = a.normalizedTitle() == b.normalizedTitle()

    /**
     * Waits for Teams to show the work as handed in: its status says so, or its toolbar offers to
     * undo the hand-in. Either must be on the same assignment's screen.
     */
    private suspend fun awaitHandedIn(title: String): Boolean = try {
        awaitScreen("Teams to show it handed in", config.handInConfirmTimeoutMs) { root ->
            val detail = TeamsScreens.detail(root)
            val sameAssignment = detail?.title?.let { exactTitle(it, title) } ?: true
            val handedIn = detail?.isHandedIn == true || TeamsScreens.offersUndoHandIn(root)
            true.takeIf { handedIn && sameAssignment }
        }
    } catch (_: StepTimeout) {
        false
    }

    private companion object {
        val WHITESPACE = Regex("[\\s\\u00A0\\u202F]+")
    }
}
