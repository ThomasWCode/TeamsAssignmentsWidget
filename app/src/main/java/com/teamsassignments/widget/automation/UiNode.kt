package com.teamsassignments.widget.automation

/** Screen bounds in pixels, like android.graphics.Rect but usable in JVM tests. */
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom

    fun intersect(other: IntRect): IntRect = IntRect(
        maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom),
    )

    companion object {
        val EMPTY = IntRect(0, 0, 0, 0)
    }
}

enum class UiAction { Click, ScrollForward, ScrollBackward, ShowOnScreen }

/**
 * A snapshot of one accessibility node. The service wraps AccessibilityNodeInfo; tests load the
 * Phase 0 uiautomator dumps. The workflows never hold a node across steps: each step takes a fresh
 * snapshot, because Teams re-renders the WebView and old nodes go stale.
 */
interface UiNode {
    val className: String
    val text: String
    val contentDescription: String
    /** View id without the `package:id/` prefix. WebView nodes report their HTML id as-is. */
    val viewId: String
    val bounds: IntRect
    val isClickable: Boolean
    val isScrollable: Boolean
    val isSelected: Boolean
    val isEnabled: Boolean
    val children: List<UiNode>

    fun perform(action: UiAction): Boolean
}

/** Every node in the tree, in document (pre-)order. */
fun UiNode.walk(): Sequence<UiNode> = sequence {
    val stack = ArrayDeque<UiNode>().apply { addLast(this@walk) }
    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        yield(node)
        for (i in node.children.indices.reversed()) stack.addLast(node.children[i])
    }
}

fun UiNode.findById(id: String): UiNode? = walk().firstOrNull { it.viewId == id }

/** The text a person would read for this node. */
val UiNode.label: String get() = text.ifBlank { contentDescription }

/** A short, single-line description for logs. */
fun UiNode.describe(): String = buildString {
    append(className.substringAfterLast('.'))
    if (viewId.isNotEmpty()) append(" #").append(viewId)
    val shown = label.replace('\n', ' ').trim()
    if (shown.isNotEmpty()) append(" \"").append(shown.take(40)).append(if (shown.length > 40) "…\"" else "\"")
}

/** Strips the `com.example:id/` prefix that native views report. */
fun shortViewId(resourceName: String?): String = resourceName?.substringAfter(":id/") ?: ""
