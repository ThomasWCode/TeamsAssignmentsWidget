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

    /** Looks at [root] [at] milliseconds in, the wall clock running from the fixtures' capture time. */
    private fun TeamsObserver.lookAt(root: UiNode, at: Long, saved: List<Assignment> = emptyList()) =
        look(root, at, clock.millis() + at, saved)

    /** Looks at [fixture] twice, far enough apart for it to hold still, and returns what was seen. */
    private fun TeamsObserver.see(fixture: String, at: Long = 0L, saved: List<Assignment> = emptyList()): Sighting {
        val root = Fixtures.load(fixture)
        assertNull(lookAt(root, at, saved), fixture)
        return assertNotNull(lookAt(root, at + 700, saved), fixture)
    }

    private fun merge(
        sighting: Sighting,
        saved: List<Assignment>,
        wallClock: Long = clock.millis(),
        recentlyHandedIn: Map<String, Assignment?> = emptyMap(),
    ) = TeamsObserver.merge(sighting, saved, parser, wallClock, recentlyHandedIn)

    // Looking

    @Test
    fun `waits for a screen to hold still, then uses it once`() {
        val observer = TeamsObserver()
        val root = Fixtures.load("list_forthcoming")
        assertNull(observer.lookAt(root, 0))
        assertTrue(observer.settling)
        assertNull(observer.lookAt(root, 300))
        assertTrue(observer.settling)
        assertNotNull(observer.lookAt(root, 700))
        assertFalse(observer.settling)
        assertNull(observer.lookAt(root, 1_400))
    }

    @Test
    fun `scrolling the list doesn't make it new`() {
        val observer = TeamsObserver()
        observer.see("list_forthcoming")
        assertNull(observer.lookAt(Fixtures.load("list_forthcoming_scrolled"), 2_000))
        assertFalse(observer.settling)
    }

    @Test
    fun `ignores screens that are loading or sliding in`() {
        listOf("list_past_due_loading", "list_past_due_mid_transition", "list_assignments_loading").forEach { fixture ->
            val observer = TeamsObserver()
            val root = Fixtures.load(fixture)
            assertNull(observer.lookAt(root, 0), fixture)
            assertNull(observer.lookAt(root, 5_000), fixture)
            assertFalse(observer.settling, fixture)
        }
    }

    @Test
    fun `doesn't take the previous tab's rows for a newly selected tab`() {
        // Codex review of the sync: Teams can mark a tab selected before replacing its rows.
        val observer = TeamsObserver()
        observer.see("list_forthcoming")
        val stale = Fixtures.load("list_past_due_stale_rows")
        assertNull(observer.lookAt(stale, 1_000))
        assertNull(observer.lookAt(stale, 5_000))
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
    fun `one open tab alone never removes an assignment for being missing`() {
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
        assertTrue(handedIn.id in merged.handedIn)
    }

    @Test
    fun `remembers what Completed shows, even once it's off the list`() {
        // Codex review: work already taken as handed in, then seen on Completed, is remembered
        // too, or a list Teams hasn't refreshed could add it back.
        val cards = TeamsScreens.cards(Fixtures.load("list_completed"))
        val merged = merge(TeamsObserver().see("list_completed"), emptyList())
        assertTrue(merged.assignments.isEmpty())
        assertTrue(merged.changes.isEmpty())
        assertEquals(cards.map { it.id }.toSet(), merged.handedIn.toSet())
        // Those already remembered aren't reported again.
        val again = merge(TeamsObserver().see("list_completed"), emptyList(), recentlyHandedIn = merged.handedIn.associateWith { null })
        assertTrue(again.handedIn.isEmpty())
    }

    @Test
    fun `doesn't add back work just handed in, which a list not yet refreshed still shows`() {
        val merged = merge(TeamsObserver().see("list_forthcoming"), emptyList(), recentlyHandedIn = mapOf(physics to null))
        assertEquals(6, merged.assignments.size)
        assertTrue(merged.assignments.none { it.key == physics })
    }

    @Test
    fun `files work under Past due once its time has passed, though Forthcoming still lists it`() {
        // Captured on 1 Oct at 12:15: Forthcoming still held that morning's 08:30 homework.
        val noon = Clock.fixed(Instant.parse("2026-10-01T11:15:00Z"), ZoneId.of("Europe/London"))
        val merged = TeamsObserver.merge(
            TeamsObserver().see("list_forthcoming_earlier_today"), emptyList(), DueDateParser(noon), noon.millis(),
        )
        assertEquals(7, merged.assignments.size)
        val overdue = merged.assignments.filter { it.tab == AssignmentTab.PastDue }
        assertEquals(listOf("66fcdab0-2ed3-44b9-9ea3-3fba18d79567"), overdue.map { it.key })
        assertEquals(millis("2026-10-01T07:30:00Z"), overdue.single().dueAt)
    }

    @Test
    fun `a card on the Completed list is taken as handed in, status line or none`() {
        // Two Completed cards (closed without a submission) show no status line at all.
        val cards = TeamsScreens.cards(Fixtures.load("list_completed"))
        val submitted = cards.first { it.isHandedIn }
        val closed = cards.first { !it.isHandedIn }
        val saved = listOf(submitted, closed).map { Assignment(key = it.id, title = it.title, className = it.className) } +
            Assignment(key = physics, title = "Particle Physics Test", className = "12.2-PH3")
        val merged = merge(TeamsObserver().see("list_completed"), saved)
        assertEquals(listOf(physics), merged.assignments.map { it.key })
        assertTrue(merged.handedIn.containsAll(listOf(submitted.id, closed.id)))
        assertTrue(merged.presumed.isEmpty())
    }

    @Test
    fun `a newly selected Completed tab still showing open cards removes nothing`() {
        // Seen switching from Forthcoming, its rows aren't taken as Completed's at all.
        val observer = TeamsObserver()
        observer.see("list_forthcoming")
        val stale = Fixtures.load("list_completed_stale_rows")
        assertNull(observer.lookAt(stale, 1_000))
        assertNull(observer.lookAt(stale, 5_000))
        // Even with the switch unseen, a card showing a due line isn't one of Completed's own.
        val saved = merge(TeamsObserver().see("list_forthcoming"), emptyList()).assignments
        val merged = merge(TeamsObserver().see("list_completed_stale_rows"), saved)
        assertEquals(saved, merged.assignments)
        assertTrue(merged.handedIn.isEmpty())
    }

    // Taken as handed in for being on neither open tab

    /** Handed in elsewhere: on neither open tab, and not falling due around now. */
    private val gone = Assignment(
        key = "11111111-2222-3333-4444-555555555555",
        title = "Handed in on the laptop",
        className = "Maths",
        tab = AssignmentTab.Forthcoming,
    )

    @Test
    fun `takes work on neither open tab as handed in, once both have been seen in full`() {
        val observer = TeamsObserver()
        val forthcoming = merge(observer.see("list_forthcoming"), listOf(gone))
        assertTrue(gone in forthcoming.assignments, "one tab isn't enough")

        val both = merge(observer.see("list_past_due", at = 2_000), forthcoming.assignments)
        assertEquals(10, both.assignments.size)
        assertTrue(both.assignments.none { it.key == gone.key })
        assertEquals(listOf(gone.key), both.presumed)
        // Not remembered as handed in: were it wrong, the list showing it would bring it back.
        assertTrue(both.handedIn.isEmpty())
        assertTrue("\"Handed in on the laptop\" is on neither Forthcoming nor Past due: taken as handed in" in both.changes)
    }

    @Test
    fun `the two open tabs must be seen within ten minutes of each other`() {
        val observer = TeamsObserver()
        val saved = merge(observer.see("list_forthcoming"), listOf(gone)).assignments
        val late = merge(observer.see("list_past_due", at = 11 * 60_000L), saved)
        assertTrue(late.presumed.isEmpty())
        // Back to Forthcoming a minute later: now the two views are close enough.
        assertEquals(listOf(gone.key), merge(observer.see("list_forthcoming", at = 12 * 60_000L), late.assignments).presumed)
    }

    @Test
    fun `work falling due around the reads is left alone`() {
        // It may have moved from Forthcoming to Past due between the two looks.
        val fallingDue = gone.copy(dueAt = clock.millis() + 60_000)
        val longPast = gone.copy(key = "22222222-3333-4444-5555-666666666666", dueAt = clock.millis() - 3 * 3_600_000)
        val observer = TeamsObserver()
        val saved = merge(observer.see("list_forthcoming"), listOf(fallingDue, longPast)).assignments
        assertEquals(listOf(longPast.key), merge(observer.see("list_past_due", at = 2_000), saved).presumed)
    }

    @Test
    fun `an empty open tab must stay empty for 2 s, or 6 s if it had work`() {
        val empty = Fixtures.load("list_past_due_empty")
        val observer = TeamsObserver()
        assertNull(observer.lookAt(empty, 0))
        assertNull(observer.lookAt(empty, 1_500))
        assertTrue(observer.settling)
        assertNotNull(observer.lookAt(empty, 2_000))

        val hadWork = listOf(gone.copy(tab = AssignmentTab.PastDue))
        val wary = TeamsObserver()
        assertNull(wary.lookAt(empty, 0, hadWork))
        assertNull(wary.lookAt(empty, 5_000, hadWork))
        assertTrue(wary.settling)
        assertNotNull(wary.lookAt(empty, 6_000, hadWork))
    }

    @Test
    fun `a newly selected tab still showing the other's rows proves nothing`() {
        // Seen switching: Past due's stale rows are never trusted, and nothing goes until its own load.
        val observer = TeamsObserver()
        val saved = merge(observer.see("list_forthcoming"), listOf(gone)).assignments
        val stale = Fixtures.load("list_past_due_stale_rows")
        assertNull(observer.lookAt(stale, 1_000))
        assertNull(observer.lookAt(stale, 5_000))
        assertEquals(listOf(gone.key), merge(observer.see("list_past_due", at = 6_000), saved).presumed)

        // Unseen switch: the two open tabs showing the same cards never count together.
        val unseen = TeamsObserver()
        unseen.see("list_past_due_stale_rows")
        unseen.see("list_completed", at = 2_000)
        assertTrue(merge(unseen.see("list_forthcoming", at = 4_000), listOf(gone)).presumed.isEmpty())

        // An open tab showing handed-in cards is Completed's rows, not its own. (Derived without
        // Completed's "load more" placeholder, which would keep it from counting in the first place.)
        val completedRows = TeamsObserver()
        completedRows.see("list_forthcoming")
        assertTrue(merge(completedRows.see("list_past_due_completed_rows", at = 2_000), listOf(gone)).presumed.isEmpty())
    }

    @Test
    fun `a list the tree doesn't hold whole counts once scrolled through, with no gap`() {
        // Derived: a virtualised list, which Teams' isn't, only holds the rows in view.
        val topOnly = TeamsObserver()
        topOnly.see("list_forthcoming_virtualised")
        assertTrue(merge(topOnly.see("list_past_due", at = 2_000), listOf(gone)).presumed.isEmpty(), "only the top seen")

        // Scrolled from the top to the end, the two views overlapping: the whole list seen.
        val observer = TeamsObserver()
        observer.see("list_forthcoming_virtualised")
        observer.see("list_forthcoming_virtualised_scrolled", at = 2_000)
        assertEquals(listOf(gone.key), merge(observer.see("list_past_due", at = 4_000), listOf(gone)).presumed)

        // A fling from the top straight to the end skips rows that were never in view.
        val flung = TeamsObserver()
        flung.see("list_forthcoming_virtualised")
        flung.see("list_forthcoming_virtualised_end", at = 2_000)
        assertTrue(merge(flung.see("list_past_due", at = 4_000), listOf(gone)).presumed.isEmpty())
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
        // reading along took it off the list from the screen captured here, on its due date.
        val phone = Clock.fixed(Instant.parse("2026-09-30T09:54:00Z"), ZoneId.of("Europe/London"))
        fun mergeOnPhone(sighting: Sighting, saved: List<Assignment>) =
            TeamsObserver.merge(sighting, saved, DueDateParser(phone), phone.millis())
        val observer = TeamsObserver()
        val saved = mergeOnPhone(observer.see("list_forthcoming"), emptyList()).assignments
        val read = mergeOnPhone(observer.see("detail_f63a23c9", at = 2_000), saved)
        assertEquals(listOf("read \"Dr. Frost - Forces - Week 3\""), read.changes)
        val handedIn = mergeOnPhone(observer.see("detail_f63a23c9_handed_in", at = 4_000), read.assignments)
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
    fun `a same-titled assignment due at another time is left alone`() {
        // Codex review: weekly work repeats its title and class. A lone saved match due at another
        // time is another week's, so this screen neither overwrites it nor removes it.
        val lastWeek = Assignment(
            key = physics, title = "Particle Physics Test", className = "12.2-PH3",
            dueAt = millis("2026-09-22T07:30:00Z"), description = "Last week's",
        )
        val opened = merge(TeamsObserver().see("detail_4c958b24"), listOf(lastWeek))
        assertEquals(listOf(lastWeek), opened.assignments)
        assertTrue(opened.changes.isEmpty())
        assertEquals(listOf(lastWeek), merge(TeamsObserver().see("detail_4c958b24_handed_in"), listOf(lastWeek)).assignments)
    }

    @Test
    fun `adds same-titled work from a plainly different class`() {
        // Codex review: a generic title recurs across classes, and the toolbar names this one's.
        val otherClass = Assignment(
            key = "11111111-2222-3333-4444-555555555555", title = "Particle Physics Test",
            className = "Chemistry 12.1-CH2", dueAt = millis("2026-09-29T07:30:00Z"),
        )
        val opened = merge(TeamsObserver().see("detail_4c958b24"), listOf(otherClass))
        assertEquals(
            listOf(otherClass.key, Assignment.fallbackKey("12.2-PH3", "Particle Physics Test")),
            opened.assignments.map { it.key },
        )
        assertEquals(listOf("added \"Particle Physics Test\""), opened.changes)

        // One of unknown class might be this one's, another week's: that's left to the list.
        val unknownClass = otherClass.copy(className = "", dueAt = millis("2026-09-22T07:30:00Z"))
        assertEquals(listOf(unknownClass), merge(TeamsObserver().see("detail_4c958b24"), listOf(unknownClass)).assignments)
    }

    @Test
    fun `adds nothing from a detail screen whose toolbar doesn't name the class`() {
        val detail = assertNotNull(TeamsScreens.detail(Fixtures.load("detail_4c958b24")))
        val merged = merge(Sighting.OnDetail(detail, classInToolbar = false), emptyList())
        assertTrue(merged.assignments.isEmpty())
    }

    @Test
    fun `reads an assignment opened from the Activity feed, and adds one that's new`() {
        // On the phone on 1 Oct this screen wasn't looked at: its toolbar holds the class name
        // alone, with no Assignments subtitle. It reads like any other.
        val title = "Soziale Netzwerke. Fluch oder Segen?"
        val listed = Assignment(
            key = "885e3273-5cc3-4fe9-a75d-4f65c5585f4f", title = title, className = "Ms Cloud year 12 2026/27",
            dueAt = millis("2026-10-06T07:30:00Z"),
        )
        val read = merge(TeamsObserver().see("detail_885e3273_from_activity"), listOf(listed))
        assertEquals(listOf("read \"$title\""), read.changes)
        with(read.assignments.single()) {
            assertEquals(listed.key, key)
            assertTrue(description.startsWith("Hausaufgaben"), description)
        }

        val added = merge(TeamsObserver().see("detail_885e3273_from_activity"), emptyList())
        assertEquals(listOf("added \"$title\""), added.changes)
        assertEquals(Assignment.fallbackKey("Ms Cloud year 12 2026/27", title), added.assignments.single().key)
    }

    // A hand-in undone

    private val facts = Assignment(
        key = "3a5b3795-5fbb-40f8-8fb2-a2f800777d3d",
        title = "Hausaufgabe: 5 facts \"Familie\"",
        className = "German Y12 1-1 Speaking Sessions 2026-27",
        description = "For this week, learn 5 facts on the topic of \"Die Familie\"",
        dueAt = Instant.parse("2026-10-05T07:00:00Z").toEpochMilli(),
        detailReadAt = 1L,
    )

    @Test
    fun `brings back work whose hand-in was undone, Teams id and all`() {
        // On the phone on 1 Oct: handed in from the widget, then undone in Teams. The row came
        // back from this screen without its Teams id, which the list couldn't then give it.
        val undone = merge(TeamsObserver().see("detail_3a5b3795_hand_in_again"), emptyList(), recentlyHandedIn = mapOf(facts.key to facts))
        assertEquals(listOf("\"${facts.title}\" is no longer handed in"), undone.changes)
        with(undone.assignments.single()) {
            assertEquals(facts.key, key)
            assertEquals(facts.description, description)
            assertEquals("Due 5 October 2026 08:00", dueText)
            assertEquals(AssignmentTab.Forthcoming, tab)
        }
    }

    @Test
    fun `only brings back the very assignment that was handed in`() {
        // Weekly work repeats its title and class: another week's, due at another time, isn't it.
        val lastWeek = facts.copy(dueAt = facts.dueAt!! - 7 * 24 * 3_600_000L)
        val otherWeek = merge(TeamsObserver().see("detail_3a5b3795_hand_in_again"), emptyList(), recentlyHandedIn = mapOf(facts.key to lastWeek))
        assertEquals(Assignment.fallbackKey(facts.className, facts.title), otherWeek.assignments.single().key)

        // Nor is anything brought back from a screen still showing the work as handed in.
        val stillIn = merge(TeamsObserver().see("detail_3a5b3795_handed_in"), emptyList(), recentlyHandedIn = mapOf(facts.key to facts))
        assertTrue(stillIn.assignments.isEmpty())
    }

    @Test
    fun `the list gives a row its Teams id once its own screen has shown the hand-in undone`() {
        // Work seen on Completed is remembered by its id alone, so its own screen can only add it
        // back without one. The list then supplies the id, where otherwise it would pass the card by.
        val remembered = mapOf<String, Assignment?>(physics to null)
        val fromDetail = merge(TeamsObserver().see("detail_4c958b24"), emptyList(), recentlyHandedIn = remembered)
        assertEquals(Assignment.fallbackKey("12.2-PH3", "Particle Physics Test"), fromDetail.assignments.single().key)

        val fromList = merge(TeamsObserver().see("list_forthcoming"), fromDetail.assignments, recentlyHandedIn = remembered)
        assertEquals(7, fromList.assignments.size)
        assertTrue("added \"Particle Physics Test\"" in fromList.changes, fromList.changes.toString())
        with(fromList.assignments.single { it.key == physics }) {
            assertTrue(description.startsWith("1) Use results"), description)
            assertNotNull(detailReadAt)
        }
    }

    @Test
    fun `adds nothing from a handed-in detail screen`() {
        assertTrue(merge(TeamsObserver().see("detail_4c958b24_handed_in"), emptyList()).assignments.isEmpty())
    }
}
