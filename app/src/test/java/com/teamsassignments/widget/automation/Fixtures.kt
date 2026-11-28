package com.teamsassignments.widget.automation

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** A node loaded from a uiautomator dump. Actions go to the owning fake device. */
class FakeNode(
    override val className: String,
    override val text: String,
    override val contentDescription: String,
    override val viewId: String,
    override val bounds: IntRect,
    override val isClickable: Boolean,
    override val isScrollable: Boolean,
    override val isSelected: Boolean,
    override val children: List<FakeNode>,
    private val onAction: (FakeNode, UiAction) -> Boolean,
    override val isEnabled: Boolean = true,
    override val customActions: List<String> = emptyList(),
    private val onCustom: (FakeNode, String) -> Boolean = { _, _ -> false },
) : UiNode {
    override fun perform(action: UiAction): Boolean = onAction(this, action)
    override fun performCustom(label: String): Boolean = label in customActions && onCustom(this, label)
    override fun toString(): String = describe()

    /** The same tree [dx] pixels to the side, as Teams reports it while a screen slides in. */
    fun shifted(dx: Int): FakeNode = FakeNode(
        className, text, contentDescription, viewId,
        bounds.copy(left = bounds.left + dx, right = bounds.right + dx),
        isClickable, isScrollable, isSelected, children.map { it.shifted(dx) }, onAction, isEnabled, customActions, onCustom,
    )
}

/**
 * Loads the Phase 0 captures in `src/test/resources/fixtures/teams`, and those of other screens
 * in the folder named. A node's `custom-actions` (not uiautomator's) lists its labelled actions.
 */
object Fixtures {
    private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    fun exists(name: String): Boolean = javaClass.getResource("/fixtures/teams/$name.xml") != null

    fun load(name: String, onAction: (FakeNode, UiAction) -> Boolean = { _, _ -> false }): FakeNode =
        loadFrom("teams", name, onAction = onAction)

    /** A capture in `fixtures/[folder]`; [onCustom] gets the labelled actions (onAction stays last, for a trailing lambda). */
    fun loadFrom(
        folder: String,
        name: String,
        onCustom: (FakeNode, String) -> Boolean = { _, _ -> false },
        onAction: (FakeNode, UiAction) -> Boolean = { _, _ -> false },
    ): FakeNode {
        val stream = javaClass.getResourceAsStream("/fixtures/$folder/$name.xml") ?: error("No fixture named $name")
        val document = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        val root = document.documentElement.childElements().first()
        return root.toNode(onAction, onCustom)
    }

    private fun Element.toNode(onAction: (FakeNode, UiAction) -> Boolean, onCustom: (FakeNode, String) -> Boolean): FakeNode = FakeNode(
        className = getAttribute("class"),
        text = getAttribute("text"),
        contentDescription = getAttribute("content-desc"),
        viewId = shortViewId(getAttribute("resource-id")),
        bounds = BOUNDS.matchEntire(getAttribute("bounds"))
            ?.groupValues?.drop(1)?.map(String::toInt)
            ?.let { (l, t, r, b) -> IntRect(l, t, r, b) } ?: IntRect.EMPTY,
        isClickable = getAttribute("clickable") == "true",
        isScrollable = getAttribute("scrollable") == "true",
        isSelected = getAttribute("selected") == "true",
        children = childElements().map { it.toNode(onAction, onCustom) },
        onAction = onAction,
        isEnabled = getAttribute("enabled") != "false",
        customActions = getAttribute("custom-actions").split(',').filter(String::isNotEmpty),
        onCustom = onCustom,
    )

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == "node" }
}
