package com.teamsassignments.widget.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction

/**
 * A [UiNode] copied from a live accessibility node. The whole tree is copied in one pass, so the
 * workflows read a consistent screen; actions still go to the live node.
 */
class AndroidUiNode private constructor(
    private val info: AccessibilityNodeInfo,
    override val className: String,
    override val text: String,
    override val contentDescription: String,
    override val viewId: String,
    override val bounds: IntRect,
    override val isClickable: Boolean,
    override val isScrollable: Boolean,
    override val isSelected: Boolean,
    override val children: List<AndroidUiNode>,
) : UiNode {

    override fun perform(action: UiAction): Boolean = when (action) {
        UiAction.Click -> info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        UiAction.ScrollForward -> info.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        UiAction.ScrollBackward -> info.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        UiAction.ShowOnScreen -> info.performAction(AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
    }

    override fun toString(): String = describe()

    companion object {
        /** Copies the tree under [root], stopping after [maxNodes] nodes. */
        fun snapshot(root: AccessibilityNodeInfo, maxNodes: Int = 4_000): AndroidUiNode {
            var budget = maxNodes
            val rect = Rect()

            fun copy(info: AccessibilityNodeInfo): AndroidUiNode {
                budget--
                info.getBoundsInScreen(rect)
                val bounds = IntRect(rect.left, rect.top, rect.right, rect.bottom)
                val children = ArrayList<AndroidUiNode>(info.childCount)
                for (i in 0 until info.childCount) {
                    if (budget <= 0) break
                    info.getChild(i)?.let { children += copy(it) }
                }
                return AndroidUiNode(
                    info = info,
                    className = info.className?.toString().orEmpty(),
                    text = info.text?.toString().orEmpty(),
                    contentDescription = info.contentDescription?.toString().orEmpty(),
                    viewId = shortViewId(info.viewIdResourceName),
                    bounds = bounds,
                    isClickable = info.isClickable,
                    isScrollable = info.isScrollable,
                    isSelected = info.isSelected,
                    children = children,
                )
            }

            return copy(root)
        }
    }
}
