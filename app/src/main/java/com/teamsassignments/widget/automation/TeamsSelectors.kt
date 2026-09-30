package com.teamsassignments.widget.automation

import com.teamsassignments.widget.data.AssignmentTab

/**
 * Every id, text and pattern the automation matches in Teams, taken from docs/teams-ui-notes.md.
 * When a Teams update breaks syncing, this should be the only file that needs changing.
 */
object TeamsSelectors {
    const val TEAMS_PACKAGE = "com.microsoft.teams"

    /** The Assignments app's id; also appears in Graph `webUrl`s. */
    const val ASSIGNMENTS_APP_ID = "66aeee93-507d-479a-a3ef-8f494af43945"
    const val ASSIGNMENTS_DEEP_LINK = "https://teams.microsoft.com/l/entity/$ASSIGNMENTS_APP_ID/classroom"

    // List screen

    enum class Tab(val viewId: String, val label: String) {
        Forthcoming("tab-Forthcoming", "Forthcoming"),
        PastDue("tab-Past-due", "Past due"),
        Completed("tab-Completed", "Completed"),
    }

    /** The tabs holding work that hasn't been handed in, in the order a sync reads them. */
    val OPEN_TABS = listOf(Tab.Forthcoming, Tab.PastDue)

    fun tabFor(tab: AssignmentTab): Tab = when (tab) {
        AssignmentTab.Forthcoming -> Tab.Forthcoming
        AssignmentTab.PastDue -> Tab.PastDue
    }

    fun Tab.toAssignmentTab(): AssignmentTab = when (this) {
        Tab.PastDue -> AssignmentTab.PastDue
        else -> AssignmentTab.Forthcoming
    }

    /** Cards are WebView nodes whose id is the assignment GUID. */
    val CARD_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** The title's id is `fui-CardHeader__headerEDUASSIGN-r6`; the suffix changes every render. */
    const val CARD_TITLE_ID_PREFIX = "fui-CardHeader__header"
    const val CARD_HOVER_ACTION_ID_PREFIX = "AssignmentCard-HoverAction-"

    /** The line under a card's title: `Due at 08:30`, or `Submitted at 09:18` on handed-in work. */
    val CARD_STATUS_LINE = Regex("""^(Due|Submitted|Handed in|Turned in)\b.*""", RegexOption.IGNORE_CASE)
    val HANDED_IN_LINE = Regex("""^(Submitted|Handed in|Turned in)\b""", RegexOption.IGNORE_CASE)

    /** Separator between a card's due line and an optional tag chip. */
    const val SEPARATOR = "•"

    /**
     * A card that collapsed into one node after its detail screen was visited:
     * `<title> Due at 08:30 <class>`. The title group is greedy, so the *last* due phrase is taken
     * as the card's, keeping a title like `Homework Due at 09:00` intact.
     */
    val COLLAPSED_CARD = Regex(
        """^(.+) ((?:Due|Submitted|Handed in|Turned in)(?: at)? \d{1,2}[:.]\d{2}(?: ?[AaPp]\.?[Mm]\.?)?)(?: (.+))?$""",
    )

    /** Group header date, `28 Sept` / `1 Oct` (en-GB) or `Sep 28` (en-US). */
    val GROUP_DATE = Regex("""^(\d{1,2} [A-Za-z]{3,9}\.?|[A-Za-z]{3,9}\.? \d{1,2})(,? \d{4})?$""")

    /**
     * A bare loading label. Exact on purpose: a card titled "Loading and unloading forces"
     * must not look like a list that never finishes loading.
     */
    val LOADING_LABEL = Regex("""^Loading(\.\.\.|…)?$""", RegexOption.IGNORE_CASE)

    /**
     * Group header label next to the date: `Today`, `Wednesday`, `Due 2 days ago`, or
     * `Due earlier today` for work that passed its time this morning.
     */
    val GROUP_LABEL = Regex(
        """^((Due )?((earlier|later) )?today|Tomorrow|Yesterday|Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday|(Due )?\d{1,3} days? ago)$""",
        RegexOption.IGNORE_CASE,
    )

    // Detail screen

    /** Present only on the assignment detail screen. */
    const val DETAIL_CONTAINER = "assignmentViewerVisibilityContainer"

    /** The native toolbar title: the class name on the detail screen, `Assignments` on the list. */
    const val TOOLBAR_TITLE = "action_bar_title_text"

    /** The line under the toolbar title on the detail screen: `Assignments`. */
    const val TOOLBAR_SUBTITLE = "action_bar_sub_title_text"

    /** The native toolbar itself, which holds the detail screen's Hand in button. */
    const val TOOLBAR = "toolbar"

    /** The toolbar title on the list, and the subtitle on a detail screen. */
    const val ASSIGNMENTS_TITLE = "Assignments"

    /**
     * The detail screen's due line. Every format Teams uses includes a time ("Due today at
     * 08:00", "Due 30 September 2026 08:30"), which keeps a title like "Due process essay" out.
     */
    val DETAIL_DUE = Regex("""^Due\b.*\b\d{1,2}[:.]\d{2}\b.*""", RegexOption.IGNORE_CASE)
    val DETAIL_STATUS = Regex(
        """^(Not handed in|Not turned in|Handed in.*|Turned in.*|Submitted.*|Returned.*|Graded.*|Missing|Late)$""",
        RegexOption.IGNORE_CASE,
    )
    val DETAIL_HANDED_IN_STATUS = Regex("""^(Handed in|Turned in|Submitted)\b""", RegexOption.IGNORE_CASE)

    /** Work that is plainly still open: the only status a detail screen on its own may add to the list. */
    val DETAIL_NOT_HANDED_IN_STATUS = Regex("""^Not (handed|turned) in$""", RegexOption.IGNORE_CASE)

    const val INSTRUCTIONS_HEADING = "Instructions"

    /** Headings that end the instructions block. */
    val DETAIL_SECTION_HEADINGS = setOf(
        "Reference materials", "My work", "Points", "Rubric", "Feedback", "Grade", "Student work",
    )

    /** Numbered and bulleted list markers, which Teams renders as separate nodes. */
    val LIST_MARKER = Regex("""^(\d{1,3}[.)]|[a-zA-Z][.)]|[•·◦▪‣*-])$""")

    // Handing in

    /**
     * The detail screen's toolbar button, `HAND IN` or `HAND IN LATE` (`TURN IN` in en-US). Only
     * the hand-in workflow presses it, after the user confirms on the widget.
     */
    val HAND_IN_BUTTON = Regex("""^(hand|turn) ?in( late)?$""", RegexOption.IGNORE_CASE)

    /** What that button is expected to read once the work is handed in. Never pressed. */
    val UNDO_HAND_IN_BUTTON = Regex("""^undo (hand|turn) ?in$""", RegexOption.IGNORE_CASE)

    // Safety

    /**
     * Controls the automation must never activate. Clicks are already limited to tab nodes and
     * GUID cards; this is the second guard, checked against a tab's text and any node under a tap.
     * The one exception, the Hand in button, has its own check in [HandInStateMachine].
     */
    val FORBIDDEN_CONTROL = Regex(
        """\b(hand\s*in|turn\s*in|undo|submit|attach|delete|remove|upload|send|new menu|add work)\b""",
        RegexOption.IGNORE_CASE,
    )
}
