package com.teamsassignments.widget.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClassColorsTest {

    /** The nine classes on the test phone in Phase 0. */
    private val classes = listOf(
        "German Y12 2026/27 LKP",
        "12.2-PH3",
        "12.1 German 2026-27",
        "Physics Skills & Stretch 12.2-PH3",
        "12.34 - Further Maths Mechanics - Mr Ryder Richardson 26/27",
        "Ms Cloud year 12 2026/27",
        "Further Maths Year 12 (Mechanics mixed) RGAB",
        "12.2-PH3 AVG (2026-28)",
        "Year 12-13 Further Maths (Pure) Dr Gabriel 2026-2028",
    )

    @Test
    fun `up to eight classes all get different colours`() {
        val assigned = ClassColors.assign(emptyMap(), classes.take(8))
        assertEquals(8, assigned.values.toSet().size)
    }

    @Test
    fun `a ninth class shares with only one other`() {
        val assigned = ClassColors.assign(emptyMap(), classes)
        val counts = assigned.values.groupingBy { it }.eachCount()
        assertEquals(8, counts.size)
        assertEquals(listOf(2), counts.values.filter { it > 1 })
    }

    @Test
    fun `colours are stable across syncs and input order`() {
        val first = ClassColors.assign(emptyMap(), classes)
        assertEquals(first, ClassColors.assign(emptyMap(), classes.reversed()))
        assertEquals(first, ClassColors.assign(first, classes))
    }

    @Test
    fun `new classes never recolour existing ones`() {
        val before = ClassColors.assign(emptyMap(), classes.take(3))
        val after = ClassColors.assign(before, classes)
        before.forEach { (name, index) -> assertEquals(index, after[name], name) }
    }

    @Test
    fun `a class that drops off the list keeps its colour for when it returns`() {
        val before = ClassColors.assign(emptyMap(), listOf("A", "B"))
        val during = ClassColors.assign(before, listOf("B"))
        assertEquals(before["A"], during["A"])
        assertEquals(before, ClassColors.assign(during, listOf("A", "B")))
    }

    @Test
    fun `colorFor falls back to the hash slot for unassigned classes`() {
        val color = ClassColors.colorFor("Unseen class", emptyMap())
        assertTrue(color in ClassColors.PALETTE)
        assertEquals(color, ClassColors.colorFor("Unseen class", emptyMap()))
    }
}
