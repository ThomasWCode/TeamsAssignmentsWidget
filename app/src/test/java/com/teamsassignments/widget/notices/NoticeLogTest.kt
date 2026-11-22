package com.teamsassignments.widget.notices

import com.teamsassignments.widget.notices.NoticeLog.Entry
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoticeLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_800_000_000_000L

    private fun entry(at: Long, key: String = "k", title: String = "12.2-PH3", text: String = "New assignment: Rubber bands plan") =
        Entry(postedAt = at, key = key, channelId = "c", title = title, text = text, matched = true)

    @Test
    fun `every notice is kept whole, read back after a restart`() = runTest {
        val file = File(tmp.root, NoticeLog.FILE_NAME)
        assertTrue(NoticeLog(file).add(entry(now - 1_000, text = "see you at lunch").copy(matched = false), now))
        assertTrue(NoticeLog(file).add(entry(now, key = "j"), now))
        val read = NoticeLog(file).read()
        assertEquals(listOf("see you at lunch", "New assignment: Rubber bands plan"), read.map { it.text })
        assertEquals(listOf(false, true), read.map { it.matched })
    }

    @Test
    fun `a repost of one already kept isn't kept twice, but an update to it is`() = runTest {
        val log = NoticeLog(File(tmp.root, NoticeLog.FILE_NAME))
        assertTrue(log.add(entry(now - 5_000), now))
        // Android posts those still showing again when the listener binds: the same key and time.
        assertFalse(log.add(entry(now - 5_000), now))
        // The same notification updated (a chat's next message): a later time, kept.
        assertTrue(log.add(entry(now, text = "a second message"), now))
        assertEquals(2, log.read().size)
    }

    @Test
    fun `the newest are kept, none older than a month`() {
        val many = (1..NoticeLog.MAX_ENTRIES).map { entry(now - it * 1_000L, key = "k$it") }
        val after = NoticeLog.added(many, entry(now, key = "new"), now)
        assertEquals(NoticeLog.MAX_ENTRIES, after.size)
        assertEquals("new", after.last().key)
        assertFalse(after.any { it.key == "k${NoticeLog.MAX_ENTRIES}" }, "the oldest goes")

        val old = entry(now - NoticeLog.MAX_AGE_MS, key = "old")
        assertEquals(listOf("new"), NoticeLog.added(listOf(old), entry(now, key = "new"), now).map { it.key })
        assertEquals(emptyList(), NoticeLog.added(emptyList(), old, now))
    }

    @Test
    fun `an unreadable file reads as none, and the next notice starts it again`() = runTest {
        val file = File(tmp.root, NoticeLog.FILE_NAME).apply { writeText("not json") }
        val log = NoticeLog(file)
        assertEquals(emptyList(), log.read())
        assertTrue(log.add(entry(now), now))
        assertEquals(1, log.read().size)
    }
}
