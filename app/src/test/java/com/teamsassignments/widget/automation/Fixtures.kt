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
) : UiNode {
    override fun perform(action: UiAction): Boolean = onAction(this, action)
    override fun toString(): String = describe()
}

/** Loads the Phase 0 captures in `src/test/resources/fixtures/teams`. */
object Fixtures {
    private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    fun exists(name: String): Boolean = javaClass.getResource("/fixtures/teams/$name.xml") != null

    fun load(name: String, onAction: (FakeNode, UiAction) -> Boolean = { _, _ -> false }): FakeNode {
        val stream = javaClass.getResourceAsStream("/fixtures/teams/$name.xml") ?: error("No fixture named $name")
        val document = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        val root = document.documentElement.childElements().first()
        return root.toNode(onAction)
    }

    private fun Element.toNode(onAction: (FakeNode, UiAction) -> Boolean): FakeNode = FakeNode(
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
        children = childElements().map { it.toNode(onAction) },
        onAction = onAction,
    )

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == "node" }
}
