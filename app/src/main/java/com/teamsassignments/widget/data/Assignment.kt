package com.teamsassignments.widget.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** The Teams tab an assignment was listed under. Both mean "not handed in". */
@Serializable
enum class AssignmentTab { Forthcoming, PastDue }

@Serializable
data class Assignment(
    /** The Teams assignment GUID (the card's view id), or [fallbackKey] if Teams didn't expose one. */
    val key: String,
    val title: String,
    val className: String,
    /** Plain-text instructions, at most [MAX_DESCRIPTION] characters. Empty until the detail screen is read. */
    val description: String = "",
    /** The due text as Teams showed it, kept for display when [dueAt] couldn't be parsed. */
    val dueText: String = "",
    /** Due time in epoch milliseconds, or null if unknown. */
    val dueAt: Long? = null,
    /** Where the card was found, so a row tap knows which tab to open. */
    val tab: AssignmentTab = AssignmentTab.Forthcoming,
    /** When the detail screen was last read; null if only the list row is known. */
    val detailReadAt: Long? = null,
    val lastSyncedAt: Long = 0,
) {
    companion object {
        const val MAX_DESCRIPTION = 2_000

        /** A stable key for a card without a GUID. */
        fun fallbackKey(className: String, title: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$className\u0000$title".toByteArray(Charsets.UTF_8))
            return "h-" + digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}

@Serializable
sealed interface SyncStatus {
    @Serializable
    @SerialName("idle")
    data object Idle : SyncStatus

    @Serializable
    @SerialName("running")
    data class Running(val done: Int = 0, val total: Int = 0, val startedAt: Long = 0) : SyncStatus

    @Serializable
    @SerialName("failed")
    data class Failed(val reason: String, val at: Long = 0) : SyncStatus

    /** The user stopped the run, with Cancel or by leaving Teams. Not a failure: nothing went wrong. */
    @Serializable
    @SerialName("stopped")
    data class Stopped(val cancelled: Boolean, val at: Long = 0) : SyncStatus {
        val summary: String get() = if (cancelled) "Sync cancelled" else "Sync stopped"
    }
}

@Serializable
data class WidgetState(
    val assignments: List<Assignment> = emptyList(),
    /** When the last sync finished successfully, in epoch milliseconds. */
    val lastSuccessAt: Long? = null,
    val status: SyncStatus = SyncStatus.Idle,
    /** Class name → index into [ClassColors.PALETTE], kept so colours survive re-syncs. */
    val classColors: Map<String, Int> = emptyMap(),
    /**
     * Assignments handed in lately: key → when, in epoch milliseconds. A list Teams hasn't
     * refreshed may still show them as open, so reading along doesn't add them back (see
     * [AssignmentStore.recentlyHandedIn]). Kept here so a restart doesn't forget them.
     */
    val handedIn: Map<String, Long> = emptyMap(),
)
