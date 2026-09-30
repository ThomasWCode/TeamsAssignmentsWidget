package com.teamsassignments.widget.automation

import com.teamsassignments.widget.automation.TeamsObserver.Sighting
import com.teamsassignments.widget.automation.TeamsSelectors.Tab
import com.teamsassignments.widget.data.Assignment
import com.teamsassignments.widget.data.AssignmentTab
import com.teamsassignments.widget.data.DueDateParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TeamsObserverTest {

    /** When the fixtures were captured: Monday 28 Sept 2026, 06:40 BST. */
    private val clock = Clock.fixed(Instant.parse("2026-09-28T05:40:00Z"), ZoneId.of("Europe/London"))
    private val parser = DueDateParser(clock)

    private val hausaufgabe = "36274911-c6dd-490d-956d-0273df409847"
    private val physics = "4c958b24-de6c-429b-846b-1d02d0cbed0b"

    private fun millis(utc: String) = Instant.parse(utc).toEpochMilli()

    /** Looks at [fixture] twice, far enough apart for it to hold still, and returns what was seen. */
    private fun TeamsObserver.see(fixture: String, at: Long = 0L): Sighting {
        val root = Fixtures.load(fixture)
        assertNull(look(root, at), fixture)
        return assertNotNull(look(root, at + 700), fixture)
    }

    private fun merge(
        sighting: Sighting,
        saved: List<Assignment>,
        wallClock: Long = clock.millis(),
        recentlyHandedIn: Set<String> = emptySet(),
    ) = TeamsObserver.merge(sighting, saved, parser, wallClock, recentlyHandedIn)

    // Looking

    @Test
    fun `waits for a screen to hold still, then uses it once`() {
        val observer = TeamsObserver()
        val root = Fixtures.load("list_forthcoming")
        assertNull(observer.look(root, 0))
        assertTrue(observer.settling)
        assertNull(observer.look(root, 300))
        assertTrue(observer.settling)
        assertNotNull(observer.look(root, 700))
        assertFalse(observer.settling)
        assertNull(observer.look(root, 1_400))
    }

    @Test
    fun `scrolling the list doesn't make it new`() {
        val observer = TeamsObserver()
        observer.see("list_forthcoming")
        assertNull(observer.look(Fixtures.load("list_forthcoming_scrolled"), 2_000))
        assertFalse(observer.settling)
    }

    @Test
    fun `ignores screens that are loading or sliding in`() {
        listOf("list_past_due_loading", "list_past_due_mid_transition", "list_assignments_loading").forEach { fixture ->
            val observer = TeamsObserver()
            val root = Fixtures.load(fixture)
            assertNull(observer.look(root, 0), fixture)
            assertNull(observer.look(root, 5_000), fixture)
            assertFalse(observer.settling, fixture)
        }
    }

    @Test
    fun `doesn't take the previous tab's rows for a newly selected tab`() {
        // Codex review of the sync: Teams can mark a tab selected before replacing its rows.
        val observer = TeamsObserver()
        observer.see("list_forthcoming")
        val stale = Fixtures.load("list_past_due_stale_rows")
        assertNull(observer.look(stale, 1_000))
        assertNull(observer.look(stale, 5_000))
        assertEquals(Tab.PastDue, assertIs<Sighting.OnList>(observer.see("list_past_due", at = 6_000)).tab)
    }

    // Lists

    @Test
    fun `adds the cards on a list, without their instructions`() {
        val merged = merge(TeamsObserver().see("list_forthcoming"), emptyList())
        assertEquals(7, merged.assignments.size)
        assertEquals(7, merged.changes.size)
        with(merged.assignments.single { it.key == hausaufgabe }) {
            assertEquals("Hausaufgabe Jugendkultur Vokabeln", title)
            assertEquals("German Y12 2026/27 LKP", className)
            assertEquals("", description)
            assertEquals("28 Sept · Due at 08:00", dueText)
            assertEquals(millis("2026-09-28T07:00:00Z"), dueAt)
            assertEquals(AssignmentTab.Forthcoming, tab)
            assertNull(detailReadAt)
        }
        assertEquals("12.2-PH3", merged.assignments.single { it.key == physics }.className)
    }

    @Test
    fun `adds Past due cards under Past due`() {
        val merged = merge(TeamsObserver().see("list_past_due"), emptyList())
        assertEquals(3, merged.assignments.size)
        assertTrue(merged.assignments.all { it.tab == AssignmentTab.PastDue })
    }

    @Test
    fun `work that fell due earlier today goes under Past due`() {
        // At 08:56 BST the 08:00 homework is still under Forthcoming's Today, and also on Past due.
        val later = Clock.fixed(Instant.parse("2026-09-28T07:56:00Z"), ZoneId.of("Europe/London"))
        val merged = TeamsObserver.merge(TeamsObserver().see("list_forthcoming"), emptyList(), DueDateParser(later), later.millis())
        assertEquals(AssignmentTab.PastDue, merged.assignments.single { it.key == hausaufgabe }.tab)
        assertEquals(AssignmentTab.Forthcoming, merged.assignments.single { it.key == physics }.tab)
    }

    @Test
    fun `updates a changed row and drops its details until they're read again`() {
        val saved = merge(TeamsObserver().see("list_forthcoming"), emptyList()).assignments.map {
            if (it.key == hausaufgabe) it.copy(dueAt = 0, description = "Old instructions", detailReadAt = 1L) else it
        }
        val merged = merge(TeamsObserver().see("list_forthcoming"), saved)
        assertEquals(listOf("updated \"Hausaufgabe Jugendkultur Vokabeln\""), merged.changes)
        with(merged.assignments.single { it.key == hausaufgabe }) {
            assertEquals(millis("2026-09-28T07:00:00Z"), dueAt)
            assertEquals("Old instructions", description)
            assertNull(detailReadAt)
        }
    }

    @Test
    fun `changes nothing a sync has just read`() = runTest {
        val synced = SyncStateMachine(FakeTeamsDevice(), parser, { clock.millis() }, now = { testScheduler.currentTime })
            .run(emptyList())
        val observer = TeamsObserver()
        assertEquals(emptyList(), merge(observer.see("list_forthcoming"), synced).changes)
        assertEquals(emptyList(), merge(observer.see("list_past_due", at = 2_000), synced).changes)
        assertEquals(emptyList(), merge(observer.see("detail_4c958b24", at = 4_000), synced).changes)
    }

    @Test
    fun `never removes an assignment just for being missing from a list`() {
        val elsewhere = Assignment(key = "11111111-2222-3333-4444-555555555555", title = "On the other tab", className = "Maths")
        val merged = merge(TeamsObserver().see("list_forthcoming"), listOf(elsewhere))
        assertEquals(8, merged.assignments.size)
        assertTrue(elsewhere in merged.assignments)
    }

    @Test
    fun `removes work the Completed tab shows as handed in`() {
        val cards = TeamsScreens.cards(Fixtures.load("list_completed"))
        val handedIn = cards.first { it.isHandedIn }
        val saved = listOf(
            Assignment(key = handedIn.id, title = handedIn.title, className = handedIn.className),
            Assignment(key = physics, title = "Particle Physics Test", className = "12.2-PH3"),
        )
        val merged = merge(TeamsObserver().see("list_completed"), saved)
        assertEquals(listOf(physics), merged.assignments.map { it.key })
        assertEquals(listOf("\"${handedIn.title}\" handed in"), merged.changes)
        assertEquals(listOf(handedIn.id), merged.handedIn)
    }

    @Test
    fun `doesn't add back work just handed in, which a list not yet refreshed still shows`() {
        val merged = merge(TeamsObserver().see("list_forthcoming"), emptyList(), recentlyHandedIn = setOf(physics))
        assertEquals(6, merged.assignments.size)
        assertTrue(merged.assignments.none { it.key == physics })
    }

    @Test
    fun `a Completed card that was never handed in is left for a sync to judge`() {
        val closed = TeamsScreens.cards(Fixtures.load("list_completed")).first { !it.isHandedIn }
        val saved = listOf(Assignment(key = closed.id, title = closed.title, className = closed.className))
        assertEquals(saved, merge(TeamsObserver().see("list_completed"), saved).assignments)
    }

    // Detail screens

    @Test
    fun `reads the instructions when an assignment is opened`() {
        val observer = TeamsObserver()
        val saved = merge(observer.see("list_forthcoming"), emptyList()).assignments
        val merged = merge(observer.see("detail_4c958b24", at = 2_000), saved, wallClock = clock.millis() + 1)
        assertEquals(listOf("read \"Particle Physics Test\""), merged.changes)
        assertEquals(7, merged.assignments.size)
        with(merged.assignments.single { it.key == physics }) {
            assertEquals("12.2-PH3", className)
            assertTrue(description.startsWith("1) Use results"), description)
            assertEquals("Due tomorrow at 08:30", dueText)
            assertEquals(millis("2026-09-29T07:30:00Z"), dueAt)
            assertEquals(clock.millis() + 1, detailReadAt)
        }
    }

    @Test
    fun `removes an assignment whose own screen shows it handed in`() {
        val observer = TeamsObserver()
        val saved = merge(observer.see("list_forthcoming"), emptyList()).assignments
        val merged = merge(observer.see("detail_4c958b24_handed_in", at = 2_000), saved)
        assertEquals(listOf("\"Particle Physics Test\" handed in"), merged.changes)
        assertEquals(listOf(physics), merged.handedIn)
        assertEquals(6, merged.assignments.size)
        assertTrue(merged.assignments.none { it.key == physics })
    }

    @Test
    fun `removes an assignment handed in within Teams, as seen on the phone`() {
        // The phone's log: "Dr. Frost - Forces - Week 3" was read, then handed in in Teams, and
        // reading along took it off the list from the screen captured here.
        val observer = TeamsObserver()
        val saved = merge(observer.see("list_forthcoming"), emptyList()).assignments
        val read = merge(observer.see("detail_f63a23c9", at = 2_000), saved)
        assertEquals(listOf("read \"Dr. Frost - Forces - Week 3\""), read.changes)
        val handedIn = merge(observer.see("detail_f63a23c9_handed_in", at = 4_000), read.assignments)
        assertEquals(listOf("\"Dr. Frost - Forces - Week 3\" handed in"), handedIn.changes)
        assertEquals(listOf("f63a23c9-6d36-4c5c-a858-422d8a17a723"), handedIn.handedIn)
        assertEquals(6, handedIn.assignments.size)
    }

    @Test
    fun `adds open work seen only on its own screen, then takes its GUID from the list`() {
        // As when a Teams notification opens a new assignment straight on its detail screen.
        val observer = TeamsObserver()
        val fromDetail = merge(observer.see("detail_4c958b24"), emptyList())
        with(fromDetail.assignments.single()) {
            assertEquals(Assignment.fallbackKey("12.2-PH3", "Particle Physics Test"), key)
            assertEquals("12.2-PH3", className)
            assertTrue(description.startsWith("1) Use results"), description)
            assertEquals(millis("2026-09-29T07:30:00Z"), dueAt)
            assertEquals(AssignmentTab.Forthcoming, tab)
        }

        val fromList = merge(observer.see("list_forthcoming", at = 2_000), fromDetail.assignments)
        assertEquals(7, fromList.assignments.size)
        assertTrue(fromList.assignments.all { TeamsSelectors.CARD_ID.matches(it.key) })
        with(fromList.assignments.single { it.key == physics }) {
            assertTrue(description.startsWith("1) Use results"), description)
            assertEquals("Due tomorrow at 08:30", dueText)
            assertNotNull(detailReadAt)
        }
    }

    @Test
    fun `leaves the list alone when a screen matches no single assignment`() {
        // Weekly work can share a title and class; with neither due time matching, it can't be told apart.
        val saved = listOf(
            Assignment(key = "11111111-2222-3333-4444-555555555555", title = "Particle Physics Test", className = "12.2-PH3", dueAt = 1L),
            Assignment(key = "66666666-7777-8888-9999-000000000000", title = "Particle Physics Test", className = "12.2-PH3", dueAt = 2L),
        )
        val merged = merge(TeamsObserver().see("detail_4c958b24"), saved)
        assertEquals(saved, merged.assignments)
        assertTrue(merged.changes.isEmpty())
    }

    @Test
    fun `adds nothing from a detail screen whose toolbar doesn't name the class`() {
        val detail = assertNotNull(TeamsScreens.detail(Fixtures.load("detail_4c958b24")))
        val merged = merge(Sighting.OnDetail(detail, classInToolbar = false), emptyList())
        assertTrue(merged.assignments.isEmpty())
    }

    @Test
    fun `adds nothing from a handed-in detail screen`() {
        assertTrue(merge(TeamsObserver().see("detail_4c958b24_handed_in"), emptyList()).assignments.isEmpty())
    }
}
