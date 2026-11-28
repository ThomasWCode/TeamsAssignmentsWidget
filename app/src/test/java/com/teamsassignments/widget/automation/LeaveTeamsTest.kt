package com.teamsassignments.widget.automation

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LeaveTeamsTest {

    /**
     * A phone at the end of a sync, Teams in front, whose Recents screen is the capture: Notes,
     * Music and Teams. A card's Close action removes it, its click opens its app.
     */
    private class RecentsPhone : TeamsDevice {
        sealed interface Screen {
            data object Teams : Screen
            data object Recents : Screen
            data object Home : Screen
            data class App(val packageName: String) : Screen
        }

        var screen: Screen = Screen.Teams
        var opensRecents = true
        var closeWorks = true
        var clickWorks = true
        var homes = 0

        /** Every action on Recents: "Close <card>" or "Click <node>". */
        val pressed = mutableListOf<String>()
        private val closed = mutableSetOf<String>()

        /** Runs as Recents is asked for, before it shows: the user going elsewhere meanwhile, say. */
        var onRecents: (RecentsPhone) -> Unit = {}

        /** Runs after every action, to script the user taking over. */
        var afterAction: (RecentsPhone) -> Unit = {}

        private val tree = Fixtures.loadFrom(
            "recents",
            "recents_from_teams",
            onAction = { node, _ ->
                pressed += "Click ${node.label}"
                val opened = clickWorks && node.viewId == RecentsScreen.CARD_ID
                if (opened) screen = Screen.App(PACKAGES.getValue(node.contentDescription))
                afterAction(this)
                opened
            },
            onCustom = { node, label ->
                pressed += "$label ${node.contentDescription}"
                if (closeWorks) closed += node.contentDescription
                afterAction(this)
                true
            },
        )

        override fun recents(): Boolean {
            if (opensRecents) screen = Screen.Recents
            onRecents(this)
            return true
        }

        override fun recentsRoot(): UiNode? = if (screen == Screen.Recents) tree.without(closed) else null

        override fun home(): Boolean {
            homes++
            screen = Screen.Home
            return true
        }

        override fun foregroundPackage(): String = when (val s = screen) {
            Screen.Teams -> TeamsSelectors.TEAMS_PACKAGE
            Screen.Recents, Screen.Home -> LAUNCHER
            is Screen.App -> s.packageName
        }

        override suspend fun awaitChange(timeoutMs: Long) = delay(timeoutMs)
        override fun launchAssignments(): Boolean = error("not used")
        override fun teamsRoot(): UiNode? = null
        override fun back(): Boolean = error("not used")
        override suspend fun tap(x: Int, y: Int): Boolean = error("not used")

        /** The tree without the cards named in [labels], as Recents shows it once they're closed. */
        private fun FakeNode.without(labels: Set<String>): FakeNode {
            val original = this // actions go to the captured node, which records them
            return FakeNode(
                className, text, contentDescription, viewId, bounds, isClickable, isScrollable, isSelected,
                children.filterNot { it.viewId == RecentsScreen.CARD_ID && it.contentDescription in labels }.map { it.without(labels) },
                { _, action -> original.perform(action) }, isEnabled, customActions, { _, label -> original.performCustom(label) },
            )
        }
    }

    private val reopened = mutableListOf<String>()
    private val logs = mutableListOf<String>()

    private fun music(label: String? = "Music", reopens: Boolean = true) =
        Origin.App(MUSIC, label) { reopened += MUSIC; reopens }

    private suspend fun TestScope.leave(phone: RecentsPhone, origin: Origin) =
        LeaveTeams(phone, now = { testScheduler.currentTime }, log = { logs += it }, teamsLabel = "Teams", launcherPackage = LAUNCHER).run(origin)

    @Test
    fun `closes Teams' card, then brings back the app that was in front`() = runTest {
        val phone = RecentsPhone()
        leave(phone, music())
        assertEquals(listOf("Close Teams", "Click Music"), phone.pressed)
        assertEquals(RecentsPhone.Screen.App(MUSIC), phone.screen)
        assertTrue("Closed Teams' card on Recents" in logs)
        assertEquals(0, phone.homes)
    }

    @Test
    fun `closes Teams' card, then goes home when the sync came from the home screen`() = runTest {
        val phone = RecentsPhone()
        leave(phone, Origin.Home)
        assertEquals(listOf("Close Teams"), phone.pressed)
        assertEquals(1, phone.homes)
    }

    @Test
    fun `leaves Teams on Assignments when it was in front before the sync`() = runTest {
        val phone = RecentsPhone()
        leave(phone, Origin.Teams)
        assertEquals(RecentsPhone.Screen.Teams, phone.screen)
        assertTrue(phone.pressed.isEmpty())
        assertEquals(0, phone.homes)
    }

    @Test
    fun `goes home, Teams left, when Recents doesn't open`() = runTest {
        val phone = RecentsPhone().apply { opensRecents = false }
        leave(phone, Origin.Home)
        assertTrue(phone.pressed.isEmpty())
        assertEquals(1, phone.homes)
    }

    @Test
    fun `reopens the app when Recents doesn't open`() = runTest {
        val phone = RecentsPhone().apply { opensRecents = false }
        leave(phone, music())
        assertEquals(listOf(MUSIC), reopened)
        assertEquals(0, phone.homes)
    }

    @Test
    fun `presses nothing when the user opens another app before Recents shows`() = runTest {
        val phone = RecentsPhone().apply { onRecents = { it.screen = RecentsPhone.Screen.App("com.example.chat") } }
        leave(phone, music())
        assertTrue(phone.pressed.isEmpty())
        assertTrue(reopened.isEmpty())
        assertEquals(0, phone.homes)
        assertEquals(RecentsPhone.Screen.App("com.example.chat"), phone.screen)
    }

    @Test
    fun `goes home, nothing reopened, when the launcher shows something other than the Recents captured`() = runTest {
        // Home pressed as Recents was asked for, or a Recents screen One UI has since changed.
        val phone = RecentsPhone().apply { onRecents = { it.screen = RecentsPhone.Screen.Home } }
        leave(phone, music())
        assertTrue(phone.pressed.isEmpty())
        assertTrue(reopened.isEmpty())
        assertEquals(1, phone.homes)
    }

    @Test
    fun `still goes back to the app when Teams' card won't close`() = runTest {
        val phone = RecentsPhone().apply { closeWorks = false }
        leave(phone, music())
        assertEquals(listOf("Close Teams", "Click Music"), phone.pressed)
        assertTrue("Teams' card stayed on Recents" in logs)
        assertEquals(RecentsPhone.Screen.App(MUSIC), phone.screen)
    }

    @Test
    fun `closes nothing when no card is named Teams`() = runTest {
        val phone = RecentsPhone()
        LeaveTeams(phone, now = { testScheduler.currentTime }, log = { logs += it }, teamsLabel = "Microsoft Teams", launcherPackage = LAUNCHER).run(music())
        assertEquals(listOf("Click Music"), phone.pressed)
        assertTrue("No Teams card on Recents" in logs)
    }

    @Test
    fun `reopens the app when it has no card, or goes home if it can't`() = runTest {
        val phone = RecentsPhone()
        leave(phone, music(label = "Podcasts"))
        assertEquals(listOf("Close Teams"), phone.pressed)
        assertEquals(listOf(MUSIC), reopened)

        val second = RecentsPhone()
        leave(second, music(label = null, reopens = false))
        assertEquals(1, second.homes)
    }

    @Test
    fun `reopens the app when its card doesn't bring it back`() = runTest {
        val phone = RecentsPhone().apply { clickWorks = false }
        leave(phone, music())
        assertEquals(listOf("Close Teams", "Click Music"), phone.pressed)
        assertEquals(listOf(MUSIC), reopened)
    }

    @Test
    fun `presses nothing more once the user has left Recents`() = runTest {
        val phone = RecentsPhone().apply { afterAction = { it.screen = RecentsPhone.Screen.App("com.example.chat") } }
        leave(phone, music())
        assertEquals(listOf("Close Teams"), phone.pressed)
        assertEquals(RecentsPhone.Screen.App("com.example.chat"), phone.screen)
        assertTrue(reopened.isEmpty())
        assertEquals(0, phone.homes)
    }

    @Test
    fun `never presses Close all or another app's Close`() = runTest {
        for (origin in listOf(music(), Origin.Home, music(label = "Notes"))) {
            val phone = RecentsPhone()
            leave(phone, origin)
            assertTrue(phone.pressed.none { it == "Click Close all" || (it.startsWith("Close ") && it != "Close Teams") }, "$origin: ${phone.pressed}")
        }
    }

    private companion object {
        const val MUSIC = "com.example.music"
        const val LAUNCHER = "com.sec.android.app.launcher"
        val PACKAGES = mapOf("Music" to MUSIC, "Notes" to "com.example.notes", "Teams" to TeamsSelectors.TEAMS_PACKAGE)
    }
}
