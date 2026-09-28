package com.teamsassignments.widget.automation

import com.teamsassignments.widget.automation.TeamsSelectors.Tab
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeamsScreensTest {

    private fun cards(fixture: String) = TeamsScreens.cards(Fixtures.load(fixture))
    private fun detail(fixture: String) = TeamsScreens.detail(Fixtures.load(fixture))!!

    // List screens

    @Test
    fun `reads every Forthcoming card with its group header`() {
        val cards = cards("list_forthcoming")
        assertEquals(
            listOf(
                "Hausaufgabe Jugendkultur Vokabeln",
                "Independent work cover lesson 25.09.2026",
                "Particle Physics Test",
                "w/sheet - prepositions, cases, adjective endings",
                "Prep 1",
                "Dr. Frost - Forces - Week 3",
                "Gefahren in den sozialen Netzwerken. Vor- und Nachteile",
            ),
            cards.map { it.title },
        )

        val first = cards.first()
        assertEquals("36274911-c6dd-490d-956d-0273df409847", first.id)
        assertEquals("Due at 08:00", first.dueLine)
        assertEquals("German Y12 2026/27 LKP", first.className)
        assertEquals("28 Sept", first.headerDate)
        assertEquals("Today", first.headerLabel)
        assertFalse(first.collapsed)

        val drFrost = cards[5]
        assertEquals("12.34 - Further Maths Mechanics - Mr Ryder Richardson 26/27", drFrost.className)
        assertEquals("30 Sept", drFrost.headerDate)
        assertEquals("Wednesday", drFrost.headerLabel)

        val gefahren = cards.last()
        assertEquals("1 Oct", gefahren.headerDate)
        assertEquals("Thursday", gefahren.headerLabel)
        assertTrue(cards.none { it.isHandedIn })
    }

    @Test
    fun `separates a tag chip from the class`() {
        val prep = cards("list_forthcoming").single { it.title == "Prep 1" }
        assertEquals("Due at 23:59", prep.dueLine)
        assertEquals("Challenge", prep.tag)
        assertEquals("Physics Skills & Stretch 12.2-PH3", prep.className)
        assertEquals("29 Sept", prep.headerDate)
    }

    @Test
    fun `reads Past due cards and their relative labels`() {
        val cards = cards("list_past_due")
        assertEquals(listOf("88fafeb2", "d3f67007", "d53f5f50"), cards.map { it.id.take(8) })
        assertEquals(listOf("25 Sept", "21 Sept", "17 Sept"), cards.map { it.headerDate })
        assertEquals(listOf("Due 2 days ago", "Due 7 days ago", "Due 11 days ago"), cards.map { it.headerLabel })
        assertEquals("Text - LESEN", cards[1].title)
        assertEquals("12.1 German 2026-27", cards[1].className)
    }

    @Test
    fun `parses a card that collapsed after its detail screen was visited`() {
        val collapsed = cards("list_forthcoming_after_back").single { it.collapsed }
        assertEquals("4c958b24-de6c-429b-846b-1d02d0cbed0b", collapsed.id)
        assertEquals("Particle Physics Test", collapsed.title)
        assertEquals("Due at 08:30", collapsed.dueLine)
        assertEquals("12.2-PH3", collapsed.className)
        assertEquals("29 Sept", collapsed.headerDate)
        assertEquals(7, cards("list_forthcoming_after_back").size)

        val pastDue = cards("list_past_due_after_back").single { it.collapsed }
        assertEquals("Prep 18/09/2026 - Chapter 12 review", pastDue.title)
        assertEquals("Further Maths Year 12 (Mechanics mixed) RGAB", pastDue.className)
    }

    /** A hand-built node, for shapes the captures don't happen to contain. */
    private fun node(text: String, id: String = "", children: List<FakeNode> = emptyList()) = FakeNode(
        className = "android.view.View", text = text, contentDescription = "", viewId = id,
        bounds = IntRect(0, 0, 100, 100), isClickable = false, isScrollable = false, isSelected = false,
        children = children, onAction = { _, _ -> false },
    )

    @Test
    fun `an assignment called Instructions keeps its real instructions`() {
        // Codex review: the heading search must start after the title and due line.
        val root = node(
            "",
            children = listOf(
                node(
                    "",
                    id = TeamsSelectors.DETAIL_CONTAINER,
                    children = listOf(
                        node("Not handed in"),
                        node(
                            "",
                            children = listOf(
                                node("Instructions"),
                                node("Due tomorrow at 09:00"),
                                node("Instructions"),
                                node("Read chapter 3"),
                                node("My work"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val detail = TeamsScreens.detail(root)!!
        assertEquals("Instructions", detail.title)
        assertEquals("Due tomorrow at 09:00", detail.dueText)
        assertEquals("Read chapter 3", detail.instructions)
    }

    @Test
    fun `a card title starting with a status word isn't mistaken for the status line`() {
        // Codex review: "Submitted report analysis" would have read as handed in and been dropped.
        val card = node(
            "",
            id = "12345678-1234-1234-1234-123456789abc",
            children = listOf(
                node("Submitted report analysis", id = "${TeamsSelectors.CARD_TITLE_ID_PREFIX}EDUASSIGN-r1"),
                node("Due at 09:00"),
                node("Chemistry 12C"),
            ),
        )
        val parsed = TeamsScreens.cards(node("", children = listOf(node("2 Oct"), card))).single()
        assertEquals("Submitted report analysis", parsed.title)
        assertEquals("Due at 09:00", parsed.dueLine)
        assertEquals("Chemistry 12C", parsed.className)
        assertFalse(parsed.isHandedIn)
    }

    @Test
    fun `a detail title starting with Due isn't mistaken for the due line`() {
        // Codex review: "Due process essay" would have left the title unreadable.
        val root = node(
            "",
            children = listOf(
                node(
                    "",
                    id = TeamsSelectors.DETAIL_CONTAINER,
                    children = listOf(
                        node("Not handed in"),
                        node(
                            "",
                            children = listOf(
                                node("Due process essay"),
                                node("Due tomorrow at 09:00"),
                                node("Instructions"),
                                node("Write 500 words"),
                                node("My work"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val detail = TeamsScreens.detail(root)!!
        assertEquals("Due process essay", detail.title)
        assertEquals("Due tomorrow at 09:00", detail.dueText)
        assertEquals("Write 500 words", detail.instructions)
    }

    @Test
    fun `spots a loading spinner but not a title that starts with Loading`() {
        assertTrue(TeamsScreens.isLoading(Fixtures.load("list_past_due_loading")))
        assertFalse(TeamsScreens.isLoading(Fixtures.load("list_forthcoming")))
        assertFalse(TeamsScreens.isLoading(node("", children = listOf(node("Loading and unloading forces")))))
        assertTrue(TeamsScreens.isLoading(node("", children = listOf(node("Loading…")))))
    }

    @Test
    fun `the Assignments module still loading is no list yet`() {
        // Captured on the phone just after launch: Teams' toolbar over an empty WebView. There is
        // no spinner to spot, so it is the missing tabs that keep this from reading as an empty list.
        val loading = Fixtures.load("list_assignments_loading")
        assertFalse(TeamsScreens.isList(loading))
        assertFalse(TeamsScreens.isDetail(loading))
        assertFalse(TeamsScreens.isLoading(loading))
        assertNull(TeamsScreens.selectedTab(loading))
        assertTrue(TeamsScreens.cards(loading).isEmpty())
    }

    @Test
    fun `a collapsed title may itself contain a due phrase`() {
        // Codex review: the card's own due line is the last one, so the title keeps its "Due at".
        val root = node(
            "",
            children = listOf(
                node("12 Oct"),
                node("Homework Due at 09:00 Due at 10:00 Maths", id = "12345678-1234-1234-1234-123456789abc"),
            ),
        )

        val card = TeamsScreens.cards(root).single()
        assertEquals("Homework Due at 09:00", card.title)
        assertEquals("Due at 10:00", card.dueLine)
        assertEquals("Maths", card.className)
        assertTrue(card.collapsed)
    }

    @Test
    fun `knows whether the list ends on screen`() {
        val probe = object : TeamsAutomation(FakeTeamsDevice(), AutomationConfig(), { 0L }, {}, { false }) {
            fun ends(fixture: String) = Fixtures.load(fixture).let { listEndsOnScreen(it, TeamsScreens.cards(it)) }
        }
        assertFalse(probe.ends("list_forthcoming")) // the last cards are below the fold
        assertTrue(probe.ends("list_forthcoming_scrolled")) // scrolled to the end
        assertTrue(probe.ends("list_past_due")) // three cards and a footer
    }

    @Test
    fun `a window caught sliding in is not at rest`() {
        // Captured on the phone mid-Back: the whole window 337 px to the left.
        val sliding = Fixtures.load("list_past_due_mid_transition")
        assertFalse(TeamsScreens.windowAtRest(sliding))
        assertEquals(IntRect(-292, 306, 39, 401), TeamsScreens.tabNode(sliding, Tab.Forthcoming)!!.bounds)
        assertTrue(TeamsScreens.windowAtRest(Fixtures.load("list_past_due_earlier_today")))
        assertTrue(TeamsScreens.windowAtRest(Fixtures.load("list_forthcoming")))
    }

    @Test
    fun `reads work that fell due earlier today`() {
        // Captured on the phone at 08:56: the 08:00 homework had moved from Forthcoming to Past due.
        val cards = cards("list_past_due_earlier_today")
        assertEquals(listOf("36274911", "88fafeb2", "d3f67007", "d53f5f50"), cards.map { it.id.take(8) })
        with(cards.first()) {
            assertEquals("Hausaufgabe Jugendkultur Vokabeln", title)
            assertEquals("28 Sept", headerDate)
            assertEquals("Due earlier today", headerLabel)
            assertEquals("Due at 08:00", dueLine)
        }
        // The same list mid-slide still reads the same.
        assertEquals(cards.map { it.id }, cards("list_past_due_mid_transition").map { it.id })
    }

    @Test
    fun `completed cards are marked handed in`() {
        val cards = cards("list_completed")
        assertEquals(30, cards.size)
        assertEquals(28, cards.count { it.isHandedIn })
        assertEquals("Submitted at 09:18", cards.first().dueLine)
    }

    @Test
    fun `a card with no status line still yields its title and class`() {
        // Two Completed cards (finished without a submission) show only a title and a class.
        val bare = cards("list_completed").single { it.title == "Factor Theorem practice" }
        assertEquals("", bare.dueLine)
        assertEquals("Year 12-13 Further Maths (Pure) Dr Gabriel 2026-2028", bare.className)
        assertNull(bare.tag)
        assertEquals("17 Sept", bare.headerDate)
    }

    @Test
    fun `recognises each screen and the selected tab`() {
        val lists = mapOf(
            "list_forthcoming" to Tab.Forthcoming,
            "list_forthcoming_scrolled" to Tab.Forthcoming,
            "list_past_due" to Tab.PastDue,
            "list_completed" to Tab.Completed,
        )
        lists.forEach { (fixture, tab) ->
            val root = Fixtures.load(fixture)
            assertTrue(TeamsScreens.isList(root), fixture)
            assertFalse(TeamsScreens.isDetail(root), fixture)
            assertEquals(tab, TeamsScreens.selectedTab(root), fixture)
        }
        val detail = Fixtures.load("detail_36274911")
        assertTrue(TeamsScreens.isDetail(detail))
        assertFalse(TeamsScreens.isList(detail))
        assertNull(TeamsScreens.detail(Fixtures.load("list_forthcoming")))
    }

    // Detail screens

    @Test
    fun `reads the detail header`() {
        with(detail("detail_4c958b24")) {
            assertEquals("12.2-PH3", className)
            assertEquals("Not handed in", status)
            assertEquals("Particle Physics Test", title)
            assertEquals("Due tomorrow at 08:30", dueText)
            assertFalse(isHandedIn)
        }
        with(detail("detail_88fafeb2")) {
            assertEquals("Further Maths Year 12 (Mechanics mixed) RGAB", className)
            assertEquals("Prep 18/09/2026 - Chapter 12 review", title)
            assertEquals("Due 25 September 2026 23:59", dueText)
        }
    }

    @Test
    fun `every captured detail screen parses`() {
        listOf(
            "36274911", "738f66ce", "4c958b24", "839994fb", "b0ccf04e",
            "f63a23c9", "66fcdab0", "88fafeb2", "d3f67007", "d53f5f50",
        ).forEach { id ->
            val d = detail("detail_$id")
            assertEquals("Not handed in", d.status, id)
            assertTrue(d.title!!.isNotBlank(), id)
            assertTrue(d.dueText!!.startsWith("Due "), id)
            assertTrue(d.className!!.isNotBlank(), id)
            assertTrue(d.instructions.isNotBlank(), id)
            assertFalse("My work" in d.instructions, id)
        }
    }

    @Test
    fun `instructions - a single paragraph`() {
        assertEquals("Learn new vocabulary p 67, 3.3", detail("detail_36274911").instructions)
    }

    @Test
    fun `instructions - numbered list with a link`() {
        assertEquals(
            "1) Use results from your diagnostic prep to do targeted revision and recap. Use lesson ppt (OneNote), " +
                "textbook, and Eduqas Blended Learning https://d3kp6tphcrvm0s.cloudfront.net/ebl21-22_11-7.\n" +
                "2) Complete Exam Practice Questions\n" +
                "3) Mark exam practice and do any follow up revision on weaknesses identified.",
            detail("detail_4c958b24").instructions,
        )
    }

    @Test
    fun `instructions - paragraphs and explicit line breaks`() {
        assertEquals(
            "Dear all,\nComplete the Dr. Frost on Forces with answers in your book.\nThanks,\nMr RR.",
            detail("detail_f63a23c9").instructions,
        )
    }

    @Test
    fun `instructions - inline spans are joined without extra spaces`() {
        assertEquals(
            "Hausaufgaben\n" +
                "1. Learn Vocabulary . 46-47 ( das Internet und Soziale Netzwerke only). We will have a short test .\n" +
                "2. Transaltion in AS style will be in our e-mails. Print the sheet out and hand in in class.",
            detail("detail_d53f5f50").instructions,
        )
    }

    @Test
    fun `instructions - bullets`() {
        val text = detail("detail_738f66ce").instructions
        assertTrue(text.startsWith("1. Finish the exercises from last lesson.\n2. Complete the following exercises"), text)
        assertTrue("\n• page 62, 1,2,3\n• page 63, 1\n• page 64, 3\n• page 65, 6\n" in text, text)
        assertTrue(text.endsWith("• Learn new vocabulary p. 67, 3.3"), text)
    }

    @Test
    fun `instructions - stop at reference materials`() {
        val text = detail("detail_b0ccf04e").instructions
        assertTrue(
            text.startsWith(
                "Start academic reading\n1. Take a physics or engineering book out of the library.\n" +
                    "2. start reading it\nEveryone:\nComplete the attached prep 1.",
            ),
            text,
        )
        assertTrue("Challenge 2 (optional for a merit with shown working):" in text, text)
        assertFalse("Reference materials" in text, text)
        assertFalse(".docx" in text, text)
        assertFalse("Show details" in text, text)
    }
}
