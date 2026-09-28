package com.teamsassignments.widget.data

import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = Clock.fixed(Instant.parse("2026-09-28T05:40:00Z"), ZoneOffset.UTC)
    private val file get() = File(tmp.root, "log.json")

    @Test
    fun `keeps only the newest entries`() {
        val log = SyncLog(file, clock, capacity = 3)
        repeat(5) { log.add("step $it") }
        assertEquals(listOf("step 2", "step 3", "step 4"), log.entries.value.map { it.message })
        assertTrue(log.entries.value.all { it.at == clock.millis() })
    }

    @Test
    fun `saved entries survive a restart`() = runTest {
        SyncLog(file, clock).apply {
            add("Sync started")
            add("Saved 10 assignments")
            persist()
        }
        assertEquals(listOf("Sync started", "Saved 10 assignments"), SyncLog(file, clock).entries.value.map { it.message })
    }

    @Test
    fun `a missing or corrupt file starts empty`() {
        assertEquals(emptyList(), SyncLog(file, clock).entries.value)
        file.writeText("not json")
        assertEquals(emptyList(), SyncLog(file, clock).entries.value)
    }
}
