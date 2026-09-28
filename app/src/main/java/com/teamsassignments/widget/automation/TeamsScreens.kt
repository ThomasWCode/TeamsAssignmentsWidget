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

    fun findCard(root: UiNode, id: String): UiNode? =
        root.walk().firstOrNull { it.viewId == id && TeamsSelectors.CARD_ID.matches(it.viewId) }

    private fun parseCard(card: UiNode, headerDate: String?, headerLabel: String?): ListCard? {
        if (card.children.isEmpty()) return parseCollapsedCard(card, headerDate, headerLabel)

        val texts = card.walk()
            .drop(1)
            .filter { it.children.isEmpty() && !it.viewId.startsWith(TeamsSelectors.CARD_HOVER_ACTION_ID_PREFIX) }
            .map { it.text.squash() }
            .filter { it.isNotEmpty() }
            .toList()
        val title = card.walk()
            .firstOrNull { it.viewId.startsWith(TeamsSelectors.CARD_TITLE_ID_PREFIX) }
            ?.text?.squash()?.takeIf { it.isNotEmpty() }
            ?: texts.firstOrNull()
            ?: return null

        val dueIndex = texts.indexOfFirst { TeamsSelectors.CARD_STATUS_LINE.matches(it) }
        val afterDue = texts.drop(if (dueIndex >= 0) dueIndex + 1 else 1)
            .filter { it != TeamsSelectors.SEPARATOR && it != title }

        return ListCard(
            id = card.viewId,
            title = title,
            dueLine = texts.getOrNull(dueIndex).orEmpty(),
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

        return DetailScreen(
            className = root.findById(TeamsSelectors.TOOLBAR_TITLE)?.text?.squash()?.takeIf { it.isNotEmpty() },
            status = texts.getOrNull(statusIndex),
            title = titleIndex?.let(texts::get)?.takeIf { it.isNotEmpty() },
            dueText = texts.getOrNull(dueIndex),
            instructions = instructions(tokens),
        )
    }

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
    private fun instructions(tokens: List<Token>): String {
        val start = tokens.indexOfFirst { it.text.squash() == TeamsSelectors.INSTRUCTIONS_HEADING }
        if (start < 0) return ""
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
