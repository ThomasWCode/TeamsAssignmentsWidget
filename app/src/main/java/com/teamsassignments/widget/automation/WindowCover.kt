package com.teamsassignments.widget.automation

import android.view.accessibility.AccessibilityWindowInfo

/** The parts of an on-screen window that matter for deciding where a tap would land. */
data class WindowInfo(val type: Int, val layer: Int, val bounds: IntRect, val packageName: String?)

/**
 * Finds what would receive a tap over the top app window. The notification shade, a heads-up
 * notification or the keyboard are system windows *above* Teams: while one covers a point, a tap
 * there would press it instead, so the automation must not tap through it. Accessibility overlays
 * (the automation's own progress pill) don't count.
 */
object WindowCover {

    /** The topmost window above the top app window that covers ([x], [y]), if any. */
    fun coveringWindow(windows: List<WindowInfo>, x: Int, y: Int): WindowInfo? {
        val app = topApp(windows) ?: return null
        return windows
            .filter { it.layer > app.layer && it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .sortedByDescending { it.layer }
            .firstOrNull { it.bounds.contains(x, y) }
    }

    fun topApp(windows: List<WindowInfo>): WindowInfo? =
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.maxByOrNull { it.layer }
}
