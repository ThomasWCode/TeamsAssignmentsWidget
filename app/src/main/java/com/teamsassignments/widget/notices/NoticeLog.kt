package com.teamsassignments.widget.notices

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Every Teams notification the listener has seen, its texts whole, matched or not: for checking
 * [TeamsNotices]' rule against what Teams really posts (read over adb with `run-as`, the file
 * being [FILE_NAME] in the app's private files). The newest [MAX_ENTRIES], none older than
 * [MAX_AGE_MS]; kept on the phone only, never backed up (the app allows no backup), and shown
 * nowhere in the app. Written whole to a temporary file and renamed over the last, as the store is.
 */
class NoticeLog(private val file: File) {

    /** One notification as Android gave it: when it was posted, its [key] (Android's, the same on a repost), and its texts. */
    @Serializable
    data class Entry(
        val postedAt: Long,
        val key: String,
        val channelId: String,
        val title: String = "",
        val text: String = "",
        val bigText: String = "",
        val subText: String = "",
        val summaryText: String = "",
        val conversationTitle: String = "",
        val lines: String = "",
        val groupSummary: Boolean = false,
        /** Whether [TeamsNotices.aboutAssignments] took it as about assignments. */
        val matched: Boolean = false,
    )

    private val mutex = Mutex()

    /** Adds [entry] unless it's already there (Android posts those still showing again when the listener binds); false then. */
    suspend fun add(entry: Entry, now: Long): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val before = read()
            val after = added(before, entry, now)
            if (after == before) return@withContext false
            write(after)
            true
        }
    }

    fun read(): List<Entry> = runCatching { if (file.exists()) json.decodeFromString<List<Entry>>(file.readText()) else null }.getOrNull().orEmpty()

    private fun write(entries: List<Entry>) {
        val dir = requireNotNull(file.absoluteFile.parentFile) { "The notice log needs a parent directory" }
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(entries))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val FILE_NAME = "teams_notices.json"
        const val MAX_ENTRIES = 300
        const val MAX_AGE_MS = 30L * 24 * 60 * 60_000

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * [entries] with [entry] added, unless one of its key and post time is there already (an
         * update to a notification, a chat's next message say, is posted again with a later time, and
         * kept), the oldest dropped past [MAX_ENTRIES] and any older than [MAX_AGE_MS] at [now].
         */
        fun added(entries: List<Entry>, entry: Entry, now: Long): List<Entry> {
            val kept = entries.filter { now - it.postedAt < MAX_AGE_MS }
            if (kept.any { it.key == entry.key && it.postedAt == entry.postedAt }) return kept
            if (now - entry.postedAt >= MAX_AGE_MS) return kept
            return (kept + entry).sortedBy { it.postedAt }.takeLast(MAX_ENTRIES)
        }

        @Volatile
        private var instance: NoticeLog? = null

        fun get(context: Context): NoticeLog = instance ?: synchronized(this) {
            instance ?: NoticeLog(File(context.applicationContext.filesDir, FILE_NAME)).also { instance = it }
        }
    }
}
