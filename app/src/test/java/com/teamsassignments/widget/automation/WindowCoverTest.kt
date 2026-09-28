package com.teamsassignments.widget.automation

import android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
import android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
import android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
import android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowCoverTest {

    // The Galaxy S24's screen, and where Teams puts things (Phase 0 captures).
    private val teams = WindowInfo(TYPE_APPLICATION, layer = 10, IntRect(0, 0, 1080, 2340), "com.microsoft.teams")
    private val statusBar = WindowInfo(TYPE_SYSTEM, layer = 30, IntRect(0, 0, 1080, 103), "com.android.systemui")
    private val navBar = WindowInfo(TYPE_SYSTEM, layer = 31, IntRect(0, 2298, 1080, 2340), "com.android.systemui")
    private val pill = WindowInfo(TYPE_ACCESSIBILITY_OVERLAY, layer = 40, IntRect(240, 124, 840, 270), "com.teamsassignments.widget")
    private val pastDueTab = 528 to 353
    private val cardTitle = 577 to 938

    private fun covering(windows: List<WindowInfo>, point: Pair<Int, Int>) =
        WindowCover.coveringWindow(windows, point.first, point.second, ownPackage = "com.teamsassignments.widget")

    @Test
    fun `the status bar, navigation bar and our own pill don't block taps on Teams`() {
        val windows = listOf(teams, statusBar, navBar, pill)
        assertNull(covering(windows, pastDueTab))
        assertNull(covering(windows, cardTitle))
        assertNull(covering(windows, 540 to 200)) // under the pill, which is ours
    }

    @Test
    fun `the notification shade blocks every tap`() {
        // Codex review: the shade is a system window over Teams, so taps would press notifications.
        val shade = WindowInfo(TYPE_SYSTEM, layer = 35, IntRect(0, 0, 1080, 2340), "com.android.systemui")
        val windows = listOf(teams, statusBar, shade, navBar, pill)
        assertEquals(shade, covering(windows, pastDueTab))
        assertEquals(shade, covering(windows, cardTitle))
    }

    @Test
    fun `a heads-up notification blocks only what it covers`() {
        val headsUp = WindowInfo(TYPE_SYSTEM, layer = 36, IntRect(24, 110, 1056, 420), "com.android.systemui")
        val windows = listOf(teams, statusBar, headsUp)
        assertEquals(headsUp, covering(windows, pastDueTab))
        assertNull(covering(windows, cardTitle))
    }

    @Test
    fun `the keyboard blocks what it covers`() {
        val keyboard = WindowInfo(TYPE_INPUT_METHOD, layer = 20, IntRect(0, 1500, 1080, 2340), "com.samsung.android.honeyboard")
        val windows = listOf(teams, keyboard)
        assertNull(covering(windows, cardTitle))
        assertEquals(keyboard, covering(windows, 577 to 1834))
    }

    @Test
    fun `another accessibility service's floating button blocks what it covers`() {
        // Codex review: only our own overlay may be tapped through; another service's is its own control.
        val menuButton = WindowInfo(TYPE_ACCESSIBILITY_OVERLAY, layer = 41, IntRect(380, 300, 680, 420), "com.samsung.accessibility")
        val windows = listOf(teams, statusBar, pill, menuButton)
        assertEquals(menuButton, covering(windows, pastDueTab))
        assertNull(covering(windows, cardTitle))
    }

    @Test
    fun `windows below the top app don't count`() {
        val wallpaper = WindowInfo(TYPE_SYSTEM, layer = 1, IntRect(0, 0, 1080, 2340), "android")
        assertNull(covering(listOf(wallpaper, teams), cardTitle))
        assertEquals(teams, WindowCover.topApp(listOf(wallpaper, teams)))
    }
}
