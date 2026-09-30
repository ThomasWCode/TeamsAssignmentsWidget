package com.teamsassignments.widget.automation

import com.teamsassignments.widget.automation.TeamsSelectors.Tab
import com.teamsassignments.widget.data.Assignment
import kotlin.math.abs

/** One row of the Assignments list. */
data class ListCard(
    /** The assignment GUID. */
    val id: String,
    val title: String,
    /** `Due at 08:30`, or `Submitted at …` on handed-in work. */
    val dueLine: String,
    val className: String,
    /** An optional tag chip such as `Challenge`. */
    val tag: String?,
    /** The group header date above the card, e.g. `28 Sept`. */
    val headerDate: String?,
    /** The group's relative label, e.g. `Today` or `Due 2 days ago`. */
    val headerLabel: String?,
    /** The card had collapsed into one node, so [className] may include a tag. */
    val collapsed: Boolean,
    val bounds: IntRect,
) {
    val isHandedIn: Boolean get() = TeamsSelectors.HANDED_IN_LINE.containsMatchIn(dueLine)
}

/** What the assignment detail screen shows. */
data class DetailScreen(
    /** From the native toolbar title. */
    val className: String?,
    /** e.g. `Not handed in`. */
    val status: String?,
    val title: String?,
    /** e.g. `Due tomorrow at 08:30`. */
    val dueText: String?,
    val instructions: String,
) {
    val isHandedIn: Boolean
        get() = status != null && TeamsSelectors.DETAIL_HANDED_IN_STATUS.containsMatchIn(status)
}

/** Reads the Assignments list and detail screens. The selectors come from [TeamsSelectors]. */
object TeamsScreens {

    fun isList(root: UiNode): Boolean = Tab.entries.any { root.findById(it.viewId) != null }

    fun tabNode(root: UiNode, tab: Tab): UiNode? = root.findById(tab.viewId)

    fun selectedTab(root: UiNode): Tab? = Tab.entries.firstOrNull { root.findById(it.viewId)?.isSelected == true }

    /**
     * Whether Teams' window is fully in place. While a screen slides in, its window reports an
     * offset: captured on the phone mid-Back as `[-337,0][743,2340]` instead of `[0,0][1080,2340]`.
     */
    fun windowAtRest(root: UiNode): Boolean = root.bounds.left >= 0 && root.bounds.top >= 0

    /** Whether Teams is showing a loading indicator: a spinner, or a bare "Loading" label. */
    fun isLoading(root: UiNode): Boolean = root.walk().any {
        it.className.endsWith("ProgressBar") || TeamsSelectors.LOADING_LABEL.matches(it.label.squash())
    }

    /**
     * [isLoading], counting only indicators on screen. The Completed list keeps a "load more"
     * placeholder (a `ProgressBar`) off screen below its last card for as long as it's open.
     */
    fun isLoadingOnScreen(root: UiNode): Boolean = root.walk().any {
        !it.bounds.isEmpty && (it.className.endsWith("ProgressBar") || TeamsSelectors.LOADING_LABEL.matches(it.label.squash()))
    }

    /** Every card in the tree, in list order, with the group header each sits under. */
    fun cards(root: UiNode): List<ListCard> {
        val cards = mutableListOf<ListCard>()
        var headerDate: String? = null
        var headerLabel: String? = null

        fun visit(node: UiNode) {
            if (TeamsSelectors.CARD_ID.matches(node.viewId)) {
                parseCard(node, headerDate, headerLabel)?.let(cards::add)
                return
            }
            if (node.children.isEmpty()) {
                val text = node.text.squash()
                when {
                    TeamsSelectors.GROUP_DATE.matches(text) -> {
                        headerDate = text
                        headerLabel = null
                    }
                    TeamsSelectors.GROUP_LABEL.matches(text) -> headerLabel = text
                }
            }
            node.children.forEach(::visit)
        }

        visit(root)
        return cards
    }

    /** Which ends of the selected tab's list are on screen, going by the bounds of the list itself. */
    data class ListInView(val top: Boolean, val bottom: Boolean)

    fun listInView(root: UiNode): ListInView? {
        val list = listNode(root) ?: return null
        val view = viewport(root)
        return ListInView(top = list.bounds.top > view.top, bottom = list.bounds.bottom < view.bottom)
    }

    /**
     * Whether the tree holds the whole of the selected tab's list, so that one look sees every
     * card: at each end the list runs off screen, the rows beyond it are in the tree. Teams keeps
     * every row there, reporting those out of view with zero height at the edge they're past (at
     * the top once scrolled past, at the bottom until reached), so this holds scrolled or not. A
     * list that only held the rows in view, as a virtualised one would, has to be scrolled through.
     */
    fun wholeListInTree(root: UiNode): Boolean {
        val list = listNode(root) ?: return false
        val view = viewport(root)
        val ends = listInView(root) ?: return false
        // The list's own rows (date groups, the Past due footer) and its cards.
        val rows = list.children + list.walk().filter { TeamsSelectors.CARD_ID.matches(it.viewId) }
        val top = ends.top || rows.any { it.bounds.isEmpty && it.bounds.bottom <= view.top }
        val bottom = ends.bottom || rows.any { it.bounds.isEmpty && it.bounds.top >= view.bottom }
        return top && bottom
    }

    /** The list holding the cards; with no cards to go by, the first list on the page. */
    private fun listNode(root: UiNode): UiNode? {
        val lists = root.walk().filter { it.className.endsWith(TeamsSelectors.LIST_CLASS) }
        return lists.firstOrNull { list -> list.walk().any { TeamsSelectors.CARD_ID.matches(it.viewId) } } ?: lists.firstOrNull()
    }

    /** The part of the page on screen: its scrolling area, the WebView. */
    private fun viewport(root: UiNode): IntRect = (root.walk().firstOrNull { it.isScrollable } ?: root).bounds

    fun findCard(root: UiNode, id: String): UiNode? =
        root.walk().firstOrNull { it.viewId == id && TeamsSelectors.CARD_ID.matches(it.viewId) }

    /** The title a card node shows now (collapsed or not). */
    fun cardTitle(card: UiNode): String? = parseCard(card, headerDate = null, headerLabel = null)?.title

    private fun parseCard(card: UiNode, headerDate: String?, headerLabel: String?): ListCard? {
        if (card.children.isEmpty()) return parseCollapsedCard(card, headerDate, headerLabel)

        val leaves = card.walk()
            .drop(1)
            .filter { it.children.isEmpty() && !it.viewId.startsWith(TeamsSelectors.CARD_HOVER_ACTION_ID_PREFIX) }
            .filter { it.text.squash().isNotEmpty() }
            .toList()
        val titleNode = card.walk()
            .firstOrNull { it.viewId.startsWith(TeamsSelectors.CARD_TITLE_ID_PREFIX) && it.text.squash().isNotEmpty() }
            ?: leaves.firstOrNull()
            ?: return null
        val title = titleNode.text.squash()

        // Everything under the title, excluding the title node itself: a title such as
        // "Submitted report analysis" must not be mistaken for the status line.
        val lines = leaves.filter { it !== titleNode }.map { it.text.squash() }
        val dueIndex = lines.indexOfFirst { TeamsSelectors.CARD_STATUS_LINE.matches(it) }
        val afterDue = lines.drop(dueIndex + 1).filter { it != TeamsSelectors.SEPARATOR }

        return ListCard(
            id = card.viewId,
            title = title,
            dueLine = lines.getOrNull(dueIndex).orEmpty(),
            // The class is the last line; anything between the due line and it is a tag chip.
            className = afterDue.lastOrNull().orEmpty(),
            tag = afterDue.dropLast(1).joinToString(" ").ifEmpty { null },
            headerDate = headerDate,
            headerLabel = headerLabel,
            collapsed = false,
            bounds = card.bounds,
        )
    }

    /** `Particle Physics Test Due at 08:30 12.2-PH3` → title, due line, class. */
    private fun parseCollapsedCard(card: UiNode, headerDate: String?, headerLabel: String?): ListCard? {
        val text = card.label.squash()
        if (text.isEmpty()) return null
        val match = TeamsSelectors.COLLAPSED_CARD.matchEntire(text)
        return ListCard(
            id = card.viewId,
            title = match?.groupValues?.get(1) ?: text,
            dueLine = match?.groupValues?.get(2).orEmpty(),
            // With a tag the rest reads `• Challenge Physics …`, which can't be split reliably;
            // the detail screen's toolbar supplies the real class name.
            className = match?.groupValues?.get(3)?.removePrefix(TeamsSelectors.SEPARATOR)?.trim().orEmpty(),
            tag = null,
            headerDate = headerDate,
            headerLabel = headerLabel,
            collapsed = true,
            bounds = card.bounds,
        )
    }

    fun isDetail(root: UiNode): Boolean = root.findById(TeamsSelectors.DETAIL_CONTAINER) != null

    fun detail(root: UiNode): DetailScreen? {
        val container = root.findById(TeamsSelectors.DETAIL_CONTAINER) ?: return null
        val tokens = textTokens(container)
        val texts = tokens.map { it.text.squash() }

        val dueIndex = texts.indexOfFirst { TeamsSelectors.DETAIL_DUE.matches(it) }
        val statusIndex = if (texts.firstOrNull()?.let(TeamsSelectors.DETAIL_STATUS::matches) == true) 0 else -1
        val titleIndex = when {
            dueIndex > 0 -> dueIndex - 1
            statusIndex == 0 -> 1
            else -> 0
        }.takeIf { it != statusIndex && it in texts.indices }

        // Look for the Instructions heading only after the title and due line, in case an
        // assignment is itself called "Instructions".
        val headerEnd = maxOf(titleIndex ?: -1, dueIndex)
        return DetailScreen(
            className = root.findById(TeamsSelectors.TOOLBAR_TITLE)?.text?.squash()?.takeIf { it.isNotEmpty() },
            status = texts.getOrNull(statusIndex),
            title = titleIndex?.let(texts::get)?.takeIf { it.isNotEmpty() },
            dueText = texts.getOrNull(dueIndex),
            instructions = instructions(tokens, from = headerEnd + 1),
        )
    }

    /**
     * Whether the toolbar title is the class name, as on a detail screen opened from the
     * Assignments list: the subtitle under it reads `Assignments`.
     */
    fun classInToolbar(root: UiNode): Boolean =
        root.findById(TeamsSelectors.TOOLBAR_SUBTITLE)?.text?.squash() == TeamsSelectors.ASSIGNMENTS_TITLE

    /**
     * The detail screen's Hand in button: a native button in Teams' toolbar reading exactly
     * `HAND IN` or `HAND IN LATE`. Null on any other screen, and once the work is handed in.
     */
    fun handInButton(root: UiNode): UiNode? = toolbarButton(root, TeamsSelectors.HAND_IN_BUTTON)

    /** Whether the toolbar offers to undo a hand-in, as it should once the work is handed in. */
    fun offersUndoHandIn(root: UiNode): Boolean = toolbarButton(root, TeamsSelectors.UNDO_HAND_IN_BUTTON) != null

    private fun toolbarButton(root: UiNode, pattern: Regex): UiNode? =
        root.findById(TeamsSelectors.TOOLBAR)?.walk()?.firstOrNull {
            it.className.endsWith("Button") && pattern.matches(it.label.squash())
        }

    /** Whether a title read from the screen is [expected]'s, ignoring case and spacing. */
    fun sameTitle(actual: String?, expected: String): Boolean {
        val a = actual?.normalizedTitle() ?: return false
        val e = expected.normalizedTitle()
        // A collapsed card's title is parsed from concatenated text, so allow a prefix match.
        return a == e || e.startsWith(a)
    }

    /** A title as [sameTitle] compares it. */
    fun String.normalizedTitle() = lowercase().replace(Regex("\\s+"), " ").trim()

    /** A piece of text on screen and where it sits, used to tell paragraphs from inline spans. */
    private data class Token(val text: String, val bounds: IntRect)

    /** Leaf texts in document order. Buttons are skipped, so their labels never become content. */
    private fun textTokens(node: UiNode): List<Token> {
        if (node.className.endsWith("Button")) return emptyList()
        if (node.children.isEmpty()) return if (node.text.isEmpty()) emptyList() else listOf(Token(node.text, node.bounds))
        val fromChildren = node.children.flatMap(::textTokens)
        return if (fromChildren.isEmpty() && node.text.isNotEmpty()) listOf(Token(node.text, node.bounds)) else fromChildren
    }

    /**
     * Rebuilds the instructions as plain text. From the Phase 0 captures:
     * - paragraphs span the full content width, so a full-width token starts a new line;
     * - inline spans (bold and so on) carry their own spaces and are joined as-is;
     * - list markers (`1)`, `1.`, `•`) are separate nodes and start a new line;
     * - nodes whose text is only a line break are line breaks.
     */
    private fun instructions(tokens: List<Token>, from: Int): String {
        val start = (from until tokens.size).firstOrNull { tokens[it].text.squash() == TeamsSelectors.INSTRUCTIONS_HEADING }
            ?: return ""
        val heading = tokens[start].bounds
        val body = tokens.drop(start + 1).takeWhile { it.text.squash() !in TeamsSelectors.DETAIL_SECTION_HEADINGS }

        val out = StringBuilder()
        fun startLine() {
            if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
        }
        for (token in body) {
            val text = token.text.replace(NON_BREAKING_SPACES, " ")
            val trimmed = text.trim()
            when {
                trimmed.isEmpty() -> when {
                    '\n' in text -> out.append('\n')
                    out.isNotEmpty() && !out.last().isWhitespace() -> out.append(' ')
                }
                TeamsSelectors.LIST_MARKER.matches(trimmed) -> {
                    startLine()
                    out.append(trimmed).append(' ')
                }
                isParagraph(token.bounds, heading) -> {
                    startLine()
                    out.append(text.trimStart())
                }
                else -> out.append(text)
            }
        }
        return out.toString()
            .lines()
            .joinToString("\n") { it.replace(SPACES, " ").trim() }
            .replace(EXTRA_BLANK_LINES, "\n\n")
            .trim()
            .take(Assignment.MAX_DESCRIPTION)
    }

    private fun isParagraph(bounds: IntRect, heading: IntRect): Boolean =
        bounds.width > 0 && heading.width > 0 &&
            abs(bounds.left - heading.left) <= PARAGRAPH_SLOP && abs(bounds.right - heading.right) <= PARAGRAPH_SLOP

    private const val PARAGRAPH_SLOP = 8
    private val WHITESPACE = Regex("[\\s\\u00A0\\u202F]+")
    private val NON_BREAKING_SPACES = Regex("[\\u00A0\\u202F]")
    private val SPACES = Regex(" {2,}")
    private val EXTRA_BLANK_LINES = Regex("\n{3,}")

    private fun String.squash(): String = replace(WHITESPACE, " ").trim()
}
