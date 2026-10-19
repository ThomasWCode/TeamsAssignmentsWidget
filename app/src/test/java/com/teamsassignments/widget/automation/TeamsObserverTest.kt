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
            if (it.key == hausaufgabe) it.copy(dueAt = 0, listDueAt = 0, description = "Old instructions", detailReadAt = 1L) else it
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
        // Not even a timestamp: an unchanged list saves nothing, so the provider's apps
        // (Decrastination, which syncs and replans when told) aren't told of every look at Teams.
        listOf("list_forthcoming" to 0L, "list_past_due" to 2_000L, "detail_4c958b24" to 4_000L).forEach { (fixture, at) ->
            val merged = merge(observer.see(fixture, at = at), synced)
            assertEquals(emptyList(), merged.changes, fixture)
            assertEquals(synced, merged.assignments, fixture)
        }
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
    fun `remembers what Completed shows of the list, and nothing else`() {
        // Review: Completed holds weeks of work (on the phone on 17 Oct it went back to the 9th).
        // Remembering all of it as handed in kept any that came back, returned for revision or
        // reopened, off the list for 12 hours. Only work that was on the list needs keeping off a
        // list Teams hasn't refreshed.
        val cards = TeamsScreens.cards(Fixtures.load("list_completed"))
        val listed = cards.take(2).map { Assignment(key = it.id, title = it.title, className = it.className) }
        val merged = merge(TeamsObserver().see("list_completed"), listed)
        assertTrue(merged.assignments.isEmpty())
        assertEquals(listed.map { it.key }.toSet(), merged.handedIn.toSet())
        assertTrue(merge(TeamsObserver().see("list_completed"), emptyList()).handedIn.isEmpty())
        // Those already remembered aren't reported again.
        val again = merge(TeamsObserver().see("list_completed"), listed, recentlyHandedIn = merged.handedIn.associateWith { null })
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
        // Both lists seen in full: the list is as up to date as a sync would leave it.
        assertNull(forthcoming.listsSeenAt)
        assertNotNull(both.listsSeenAt)
        assertTrue("\"Handed in on the laptop\" is on neither Forthcoming nor Past due: taken as handed in" in both.changes)
    }

    @Test
    fun `reads a Past due list still waiting to load more, but judges it whole only once it has`() {
        // Captured on 8 Oct: Teams' "load more" placeholder below the seventh card, which reading
        // along took for a list forever loading. Its cards count; its end doesn't, until reached.
        val observer = TeamsObserver()
        val saved = merge(observer.see("list_forthcoming"), listOf(gone)).assignments
        val pending = merge(observer.see("list_past_due_load_more", at = 2_000), saved)
        assertTrue(pending.assignments.any { it.key.startsWith("8f3383b9") }, "a card only Past due lists is added")
        assertTrue(pending.presumed.isEmpty(), "the list may have more to load")

        // Scrolled to the end: the same cards over the list's footer. Whole now, it must still hold
        // still before it counts (Codex review): Teams can drop the placeholder a moment before the
        // next page arrives, and that page's work must not look missing meanwhile.
        val end = Fixtures.load("list_past_due_load_more_end")
        assertNull(observer.lookAt(end, 4_000))
        assertTrue(observer.settling)
        val whole = assertNotNull(observer.lookAt(end, 4_700))
        assertEquals(listOf(gone.key), merge(whole, pending.assignments).presumed)
    }

    @Test
    fun `a tab seen only in part since no longer counts as seen in full`() {
        // Codex review: both tabs seen in full, then Past due waiting to load more. Its earlier
        // full view may lack what its unloaded pages hold, so it stands for nothing now.
        val observer = TeamsObserver()
        observer.see("list_forthcoming")
        assertNotNull(assertIs<Sighting.OnList>(observer.see("list_past_due", at = 2_000)).openLists)

        // Forthcoming's view alone stands for now: only work due later than it was seen can be missing.
        val lists = assertNotNull(assertIs<Sighting.OnList>(observer.see("list_past_due_load_more", at = 4_000)).openLists)
        assertNotNull(lists.dueAfter)
        assertEquals(TeamsScreens.cards(Fixtures.load("list_forthcoming")).map { it.id }.toSet(), lists.ids)
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
            assertEquals(listOf("https://d3kp6tphcrvm0s.cloudfront.net/ebl21-22_11-7"), links)
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
            // Its links too, as its own screen gave them.
            assertEquals(listOf("https://d3kp6tphcrvm0s.cloudfront.net/ebl21-22_11-7"), links)
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
    fun `brings back undone work though another week's is still on the list`() {
        // Codex review: weekly work shares its title and class, and the check that keeps a new
        // same-titled row from being added ran first, so the undone assignment stayed away. Its
        // due time tells it from the other week's.
        val lastWeek = facts.copy(key = "11111111-2222-3333-4444-555555555555", dueAt = facts.dueAt!! - 7 * 24 * 3_600_000L)
        val undone = merge(
            TeamsObserver().see("detail_3a5b3795_hand_in_again"), listOf(lastWeek), recentlyHandedIn = mapOf(facts.key to facts),
        )
        assertEquals(listOf(lastWeek.key, facts.key), undone.assignments.map { it.key })
        assertEquals(lastWeek, undone.assignments.first())
        assertEquals(listOf("\"${facts.title}\" is no longer handed in"), undone.changes)
    }

    @Test
    fun `brings back undone work whose class was never read`() {
        // Codex review: a row saved from a card whose class couldn't be read has none, yet can be
        // handed in. An unknown class stands for any, as it does when matching a saved row.
        val undone = merge(
            TeamsObserver().see("detail_3a5b3795_hand_in_again"), emptyList(),
            recentlyHandedIn = mapOf(facts.key to facts.copy(className = "")),
        )
        with(undone.assignments.single()) {
            assertEquals(facts.key, key)
            assertEquals(facts.className, className)
        }
    }

    @Test
    fun `gives back a Teams id only where it's surely this assignment's`() {
        // Hand in goes by that id, so a guess could hand in another week's work. Without a due
        // time to check against the screen's, the work comes back as new, without an id.
        val noDueTime = merge(
            TeamsObserver().see("detail_3a5b3795_hand_in_again"), emptyList(),
            recentlyHandedIn = mapOf(facts.key to facts.copy(dueAt = null)),
        )
        assertEquals(Assignment.fallbackKey(facts.className, facts.title), noDueTime.assignments.single().key)

        // And where the screen could be either of two saved rows, nothing is touched.
        val rows = listOf("11111111-2222-3333-4444-555555555555", "66666666-7777-8888-9999-000000000000")
            .map { facts.copy(key = it, dueAt = null) }
        val ambiguous = merge(TeamsObserver().see("detail_3a5b3795_hand_in_again"), rows, recentlyHandedIn = mapOf(facts.key to facts))
        assertEquals(rows, ambiguous.assignments)
        assertTrue(ambiguous.changes.isEmpty())
    }

    @Test
    fun `gives back the Teams id of work that is also remembered without one`() {
        // Codex review: work first saved from its own screen is handed in under the key that stood
        // in for its Teams id. Handed in again once the list had supplied the id, it was remembered
        // twice, and with two to choose from its screen brought back neither.
        val before = facts.copy(key = Assignment.fallbackKey(facts.className, facts.title))
        val undone = merge(
            TeamsObserver().see("detail_3a5b3795_hand_in_again"), emptyList(),
            recentlyHandedIn = mapOf(before.key to before, facts.key to facts),
        )
        assertEquals(facts.key, undone.assignments.single().key)

        // Two with a Teams id can't be told apart, though: the work comes back as new.
        val twin = facts.copy(key = "11111111-2222-3333-4444-555555555555")
        val twins = merge(
            TeamsObserver().see("detail_3a5b3795_hand_in_again"), emptyList(),
            recentlyHandedIn = mapOf(twin.key to twin, facts.key to facts),
        )
        assertEquals(before.key, twins.assignments.single().key)
    }

    @Test
    fun `doesn't add back work handed in before the list had shown it`() {
        // Such work is remembered without its Teams id, which is all a card was checked against,
        // so a list Teams hadn't refreshed could add it straight back under that id.
        val fromDetail = merge(TeamsObserver().see("detail_4c958b24"), emptyList()).assignments.single()
        assertFalse(TeamsSelectors.CARD_ID.matches(fromDetail.key))
        val merged = merge(TeamsObserver().see("list_forthcoming"), emptyList(), recentlyHandedIn = mapOf(fromDetail.key to fromDetail))
        assertEquals(6, merged.assignments.size)
        assertTrue(merged.assignments.none { it.key == physics })

        // Another week's, due at another time, is nothing to do with it.
        val lastWeek = fromDetail.copy(dueAt = fromDetail.dueAt!! - 7 * 24 * 3_600_000L)
        val other = merge(TeamsObserver().see("list_forthcoming"), emptyList(), recentlyHandedIn = mapOf(lastWeek.key to lastWeek))
        assertEquals(7, other.assignments.size)
    }

    @Test
    fun `a collapsed card is only passed by for work of its own class`() {
        // Codex review: a collapsed card's class was left out of the comparison, as it is when a
        // saved row takes its Teams id from one. Two classes can set work of one title for one
        // time, and the other class's card was kept off the list for 12 hours.
        val handedIn = merge(TeamsObserver().see("detail_4c958b24"), emptyList()).assignments.single()
        fun collapsed(id: String, className: String) = Sighting.OnList(
            Tab.Forthcoming,
            listOf(
                ListCard(
                    id = id, title = "Particle Physics Test", dueLine = "Due at 08:30", className = className, tag = null,
                    headerDate = "29 Sept", headerLabel = "Tomorrow", collapsed = true, bounds = IntRect.EMPTY,
                ),
            ),
        )
        val remembered = mapOf<String, Assignment?>(handedIn.key to handedIn)

        val otherClass = merge(collapsed("11111111-2222-3333-4444-555555555555", "Chemistry 12.1-CH2"), emptyList(), recentlyHandedIn = remembered)
        assertEquals("Chemistry 12.1-CH2", otherClass.assignments.single().className)

        // Its own class's card is passed by, tag in front of the class or not, as is one of no known class.
        listOf("12.2-PH3", "Challenge 12.2-PH3", "").forEach { className ->
            val same = merge(collapsed(physics, className), emptyList(), recentlyHandedIn = remembered)
            assertTrue(same.assignments.isEmpty(), className)
        }
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
        assertTrue("\"Particle Physics Test\" took its Teams id" in fromList.changes, fromList.changes.toString())
        with(fromList.assignments.single { it.key == physics }) {
            assertTrue(description.startsWith("1) Use results"), description)
            assertNotNull(detailReadAt)
        }
    }

    @Test
    fun `adds nothing from a handed-in detail screen`() {
        assertTrue(merge(TeamsObserver().see("detail_4c958b24_handed_in"), emptyList()).assignments.isEmpty())
    }

    // From the review of reading along (docs/review/reading-along.md)

    @Test
    fun `a handed-in screen with no due time takes nothing off the list`() {
        // Last week's work, handed in and opened from Completed, against this week's of the same
        // title and class: with no due time to tell them apart, it took this week's off the list,
        // and kept it off for 12 hours.
        val detail = assertNotNull(TeamsScreens.detail(Fixtures.load("detail_4c958b24_handed_in"))).copy(dueText = null)
        val thisWeek = Assignment(key = physics, title = "Particle Physics Test", className = "12.2-PH3", dueAt = millis("2026-10-06T07:30:00Z"))
        val merged = merge(Sighting.OnDetail(detail, classInToolbar = true), listOf(thisWeek))
        assertEquals(listOf(thisWeek), merged.assignments)
        assertTrue(merged.handedIn.isEmpty())
        // Claude review: left alone, but said so in the log, so it can be told from a screen unread.
        assertTrue(merged.changes.isEmpty())
        assertEquals(listOf("\"Particle Physics Test\" shows as handed in, with no due time to tell it by: left to Completed or a sync"), merged.notes)
    }

    @Test
    fun `the list's due time doesn't overwrite its own screen's`() {
        // The list's time took over, so the detail screen no longer matched to put it right, and a
        // sync and reading along took turns at it, each time marking the details unread.
        val listTime = millis("2026-09-29T07:30:00Z")
        val screenTime = millis("2026-09-29T08:00:00Z")
        val read = Assignment(
            key = physics, title = "Particle Physics Test", className = "12.2-PH3", description = "Read",
            dueAt = screenTime, listDueAt = listTime, detailReadAt = 1L,
        )
        val merged = merge(TeamsObserver().see("list_forthcoming"), listOf(read))
        with(merged.assignments.single { it.key == physics }) {
            assertEquals(screenTime, dueAt)
            assertEquals(1L, detailReadAt)
        }
        assertTrue(merged.changes.none { "Particle" in it }, merged.changes.toString())

        // A row saved before 0.5.0 has no list time of its own: the list's is noted, quietly.
        val legacy = read.copy(dueAt = listTime, listDueAt = null)
        val noted = merge(TeamsObserver().see("list_forthcoming"), listOf(legacy))
        assertEquals(listTime, noted.assignments.single { it.key == physics }.listDueAt)
        assertTrue(noted.changes.none { "Particle" in it }, noted.changes.toString())
    }

    @Test
    fun `a collapsed card cut short gives a row its Teams id, and the row keeps its title`() {
        // It took the card's title, and its own screen, which matches titles exactly, then saw no
        // row of its title and added a second.
        val fromDetail = merge(TeamsObserver().see("detail_4c958b24"), emptyList()).assignments
        val cut = Sighting.OnList(
            Tab.Forthcoming,
            listOf(
                ListCard(
                    id = physics, title = "Particle Physics", dueLine = "Due at 08:30", className = "12.2-PH3", tag = null,
                    headerDate = "29 Sept", headerLabel = "Tomorrow", collapsed = true, bounds = IntRect.EMPTY,
                ),
            ),
        )
        val keyed = merge(cut, fromDetail)
        with(keyed.assignments.single()) {
            assertEquals(physics, key)
            assertEquals("Particle Physics Test", title)
            // The key it had, kept: other apps know it by that one.
            assertEquals(Assignment.fallbackKey("12.2-PH3", "Particle Physics Test"), formerKey)
        }
        assertEquals(listOf("\"Particle Physics Test\" took its Teams id"), keyed.changes)
        with(merge(TeamsObserver().see("detail_4c958b24"), keyed.assignments).assignments.single()) {
            assertEquals(Assignment.fallbackKey("12.2-PH3", "Particle Physics Test"), formerKey)
        }
    }

    @Test
    fun `its own screen puts right a title a collapsed card cut short`() {
        val cutShort = Assignment(key = physics, title = "Particle Physics", className = "12.2-PH3", dueAt = millis("2026-09-29T07:30:00Z"))
        val merged = merge(TeamsObserver().see("detail_4c958b24"), listOf(cutShort))
        assertEquals(listOf("read \"Particle Physics Test\""), merged.changes)
        assertEquals("Particle Physics Test", merged.assignments.single().title)
        // Not with the due time disagreeing: that's other work, left to the list, and no row is added.
        val otherWeek = cutShort.copy(dueAt = millis("2026-09-22T07:30:00Z"))
        assertEquals(listOf(otherWeek), merge(TeamsObserver().see("detail_4c958b24"), listOf(otherWeek)).assignments)

        // Codex review: last week's row of the full title, due at another time, is no bar to it.
        val lastWeek = Assignment(
            key = "11111111-2222-3333-4444-555555555555", title = "Particle Physics Test", className = "12.2-PH3",
            dueAt = millis("2026-09-22T07:30:00Z"),
        )
        val both = merge(TeamsObserver().see("detail_4c958b24"), listOf(lastWeek, cutShort))
        assertEquals(listOf("read \"Particle Physics Test\""), both.changes)
        assertEquals(lastWeek, both.assignments.first())
        with(both.assignments.single { it.key == physics }) {
            assertEquals("Particle Physics Test", title)
            assertTrue(description.startsWith("1) Use results"), description)
        }
    }

    @Test
    fun `work seen open, unchanged, since the earlier view isn't taken as handed in for missing from it`() {
        // Codex review: only a change moved lastSyncedAt on, so work seen open but unchanged since
        // the earlier view (on its own screen, say, or a list that can't count) still looked missing.
        // Here the two open tabs, seen in full, are Teams' lists before they've caught up with it.
        val read = merge(TeamsObserver().see("detail_3a5b3795_hand_in_again"), listOf(facts)).assignments.map { it.copy(lastSyncedAt = 0) }
        assertEquals(listOf(facts.key), read.map { it.key })
        val observer = TeamsObserver()
        observer.see("list_forthcoming") // in full, without it
        val seenOpen = merge(observer.see("detail_3a5b3795_hand_in_again", at = 2_000), read, wallClock = clock.millis() + 2_700)
        // Nothing on its screen has changed, so nothing is saved (and the provider's apps aren't
        // told of it): the sighting is noted by the observer, as the service does.
        assertTrue(seenOpen.changes.isEmpty())
        assertEquals(read, seenOpen.assignments)
        assertEquals(read.map { it.key }, seenOpen.seenOpen)
        observer.sawOpen(seenOpen.seenOpen, clock.millis() + 2_700)
        assertTrue(merge(observer.see("list_past_due", at = 4_000), read).presumed.isEmpty())
        // Not seen open since, it would have gone.
        val unseen = TeamsObserver()
        unseen.see("list_forthcoming")
        assertEquals(read.map { it.key }, merge(unseen.see("list_past_due", at = 4_000), read).presumed)

        // Seen on a list that can't count for every class's does the same, noted by the look
        // itself: here a class team's own tab (captured 17 Oct), between the app's two tabs.
        val laptops = Assignment(
            key = "2fe18689-f75e-4244-a4a0-9f44df63cef4", title = "Laptops and ESSAY PLAN for TUESDAY", className = german,
            dueAt = millis("2026-10-19T08:00:00Z"), listDueAt = millis("2026-10-19T08:00:00Z"),
        )
        val listed = TeamsObserver()
        listed.see("list_forthcoming")
        listed.see("list_class_forthcoming", at = 2_000)
        assertTrue(merge(listed.see("list_past_due", at = 4_000), listOf(laptops)).presumed.isEmpty())
    }

    @Test
    fun `a class whose name ends in another's is another class`() {
        // On the phone, "Physics Skills & Stretch 12.2-PH3" and "12.2-PH3" are two teams. Matching
        // one name's end against the other let one class's screen take the other's work off the list.
        val stretch = Assignment(
            key = physics, title = "Particle Physics Test", className = "Physics Skills & Stretch 12.2-PH3",
            dueAt = millis("2026-09-29T07:30:00Z"),
        )
        assertEquals(listOf(stretch), merge(TeamsObserver().see("detail_4c958b24_handed_in"), listOf(stretch)).assignments)
    }

    @Test
    fun `work seen open since the earlier view isn't taken as handed in for missing from it`() {
        // Its hand-in undone on its own screen between the two looks, say: Forthcoming's view, from
        // before, can't show it, and Teams' Past due may not have caught up.
        val observer = TeamsObserver()
        observer.see("list_forthcoming")
        val undone = gone.copy(lastSyncedAt = clock.millis() + 1_000)
        assertTrue(merge(observer.see("list_past_due", at = 2_000), listOf(undone)).presumed.isEmpty())
    }

    @Test
    fun `a list looked at again unchanged counts as seen in full as of then`() {
        // Its first look stood for it for ten minutes from then, however long the user stayed.
        val observer = TeamsObserver()
        observer.see("list_forthcoming")
        assertNull(observer.lookAt(Fixtures.load("list_forthcoming"), 9 * 60_000L))
        assertEquals(listOf(gone.key), merge(observer.see("list_past_due", at = 15 * 60_000L), listOf(gone)).presumed)
    }

    @Test
    fun `an empty open tab, shown without a list, counts as seen in full`() {
        // Captured on 17 Oct: a tab with nothing on it has no list, only the words saying so.
        val observer = TeamsObserver()
        val saved = merge(observer.see("list_forthcoming"), listOf(gone)).assignments
        val empty = Fixtures.load("list_past_due_empty")
        assertNull(observer.lookAt(empty, 2_000))
        val sighting = assertNotNull(observer.lookAt(empty, 4_000))
        assertEquals(listOf(gone.key), merge(sighting, saved).presumed)
    }

    // Forthcoming alone, and the Past due tab's mark

    @Test
    fun `Forthcoming seen in full takes later work missing from it as handed in`() {
        // On the phone on 17 Oct, work due on the 19th was handed in elsewhere at 11:40 and stayed on
        // the widget until the next sync: Past due had to be seen in full as well.
        val later = gone.copy(dueAt = millis("2026-10-02T07:30:00Z"))
        val merged = merge(TeamsObserver().see("list_forthcoming"), listOf(later))
        assertEquals(listOf(later.key), merged.presumed)
        assertTrue(merged.handedIn.isEmpty())
        assertNull(merged.listsSeenAt, "Past due wasn't seen")
    }

    @Test
    fun `Forthcoming alone leaves work that Past due may hold`() {
        // Due before Forthcoming was seen, or around then, or at no time known.
        val overdue = gone.copy(key = "22222222-3333-4444-5555-666666666666", dueAt = clock.millis() - 3_600_000, tab = AssignmentTab.PastDue)
        val fallingDue = gone.copy(key = "33333333-4444-5555-6666-777777777777", dueAt = clock.millis() + 60_000)
        val unknown = gone.copy(key = "44444444-5555-6666-7777-888888888888", dueAt = null)
        assertTrue(merge(TeamsObserver().see("list_forthcoming"), listOf(overdue, fallingDue, unknown)).presumed.isEmpty())
    }

    @Test
    fun `Past due counts only once seen itself, whatever the other tabs show of it`() {
        // Teams marks the Past due tab while it holds work (captured 17 Oct). Reading along once took
        // the tab without its mark for Past due seen in full and empty; the user asked it not to.
        val late = gone.copy(dueAt = clock.millis() - 3 * 3_600_000, tab = AssignmentTab.PastDue)
        val merged = merge(TeamsObserver().see("list_forthcoming_past_due_clear", saved = listOf(late)), listOf(late))
        assertTrue(merged.presumed.isEmpty())
        assertNull(merged.listsSeenAt, "Past due wasn't seen")
    }

    // Narrowed lists, and a class team's own tab

    /** 17 Oct 2026, 17:20 BST, when the class team and filtered lists were captured. */
    private val oct17 = Clock.fixed(Instant.parse("2026-10-17T16:20:00Z"), ZoneId.of("Europe/London"))
    private val german = "12.1 German 2026-27"

    private fun merge17(sighting: Sighting, saved: List<Assignment>) =
        TeamsObserver.merge(sighting, saved, DueDateParser(oct17), oct17.millis())

    /** As [see], on the 17 Oct captures' clock, the screen held still for [hold] milliseconds. */
    private fun TeamsObserver.see17(fixture: String, at: Long = 0L, saved: List<Assignment> = emptyList(), hold: Long = 700): Sighting {
        val root = Fixtures.load(fixture)
        assertNull(look(root, at, oct17.millis() + at, saved), fixture)
        return assertNotNull(look(root, at + hold, oct17.millis() + at + hold, saved), fixture)
    }

    @Test
    fun `a list narrowed by Filter never counts as seen in full`() {
        // Captured on 17 Oct with Filter → Classes set to 10H: the toolbar still reads Assignments,
        // and Forthcoming, empty for that class, would take every other class's work as handed in.
        val later = gone.copy(className = german, dueAt = millis("2026-10-19T08:00:00Z"))
        val filtered = assertIs<Sighting.OnList>(TeamsObserver().see17("list_forthcoming_filtered", saved = listOf(later), hold = 6_000))
        assertEquals(TeamsScreens.ListScope(className = null, narrowed = true), filtered.scope)
        assertNull(filtered.openLists)
        assertEquals(listOf(later), merge17(filtered, listOf(later)).assignments)
    }

    @Test
    fun `a list whose toolbar isn't drawn yet is read once it is`() {
        // Codex review: a class team's list settling before its title was drawn passed for every
        // class's, and its Past due's older work would have been added, with no class.
        val observer = TeamsObserver()
        val untitled = Fixtures.load("list_class_forthcoming_untitled")
        assertNull(observer.lookAt(untitled, 0))
        assertNull(observer.lookAt(untitled, 5_000))
        assertFalse(observer.settling)
        val titled = assertIs<Sighting.OnList>(observer.see("list_class_forthcoming", at = 6_000))
        assertEquals(TeamsScreens.ListScope(className = german, narrowed = false), titled.scope)
    }

    @Test
    fun `reads a class team's own tab, the class from its toolbar`() {
        // Captured on 17 Oct: the toolbar is titled with the class, and the cards leave it out.
        val sighting = assertIs<Sighting.OnList>(TeamsObserver().see17("list_class_forthcoming"))
        assertEquals(TeamsScreens.ListScope(className = german, narrowed = false), sighting.scope)
        val merged = merge17(sighting, emptyList())
        assertEquals(listOf("2fe18689-f75e-4244-a4a0-9f44df63cef4", "239336fa-d894-402c-95fa-fa68070ec910"), merged.assignments.map { it.key })
        assertTrue(merged.assignments.all { it.className == german })
        assertEquals(millis("2026-10-19T08:00:00Z"), merged.assignments.first().dueAt)
        assertNull(merged.listsSeenAt)
    }

    @Test
    fun `a class team's Past due only keeps what's saved up to date`() {
        // It reaches back further than the app's own list, which a sync reads: work only it
        // showed would come and go.
        val prep = Assignment(
            key = "d52b6b3b-0567-46d2-abc3-8acbc5744f50", title = "Prep and Assessment", className = german,
            dueAt = 0, listDueAt = 0, tab = AssignmentTab.PastDue,
        )
        val merged = merge17(TeamsObserver().see17("list_class_past_due"), listOf(prep))
        with(merged.assignments.single()) {
            assertEquals(prep.key, key)
            assertEquals("Prep and Assessment preparation", title)
            assertEquals(millis("2026-10-08T08:00:00Z"), dueAt)
        }
    }

    @Test
    fun `a class team's two tabs seen in full count for that class alone`() {
        val threeDaysAgo = oct17.millis() - 3 * 24 * 3_600_000L
        val missing = gone.copy(className = german, dueAt = threeDaysAgo, tab = AssignmentTab.PastDue)
        val otherClass = gone.copy(key = "66666666-7777-8888-9999-000000000000", className = "12.2-PH3", dueAt = threeDaysAgo, tab = AssignmentTab.PastDue)
        val observer = TeamsObserver()
        val forthcoming = merge17(observer.see17("list_class_forthcoming", saved = listOf(missing, otherClass)), listOf(missing, otherClass))
        assertTrue(forthcoming.presumed.isEmpty(), "late work may be on Past due")
        val both = merge17(observer.see17("list_class_past_due", at = 2_000, saved = forthcoming.assignments), forthcoming.assignments)
        assertEquals(listOf(missing.key), both.presumed)
        assertTrue(both.assignments.any { it.key == otherClass.key })
        assertNull(both.listsSeenAt, "one class's lists aren't every class's")
    }

    @Test
    fun `a class team's empty Forthcoming takes that class's later work as handed in`() {
        // Captured on 17 Oct: a class with nothing set. Forthcoming in full is enough for later work.
        val avg = "12.2-PH3 AVG (2026-28)"
        val later = gone.copy(className = avg, dueAt = millis("2026-10-20T08:00:00Z"))
        val otherClass = later.copy(key = "66666666-7777-8888-9999-000000000000", className = german)
        val sighting = TeamsObserver().see17("list_class_forthcoming_empty", saved = listOf(later, otherClass), hold = 6_000)
        assertEquals(listOf(later.key), merge17(sighting, listOf(later, otherClass)).presumed)
    }
}
