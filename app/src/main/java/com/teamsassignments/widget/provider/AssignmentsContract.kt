package com.teamsassignments.widget.provider

/**
 * What [AssignmentsProvider] offers apps signed with this app's key (Decrastination): the
 * assignments not handed in, how fresh they are, and two requests, sync and open. Nothing in it
 * changes Teams or the list. Other apps copy these names, so change them only together.
 *
 * - `content://com.teamsassignments.widget.assignments/assignments`: one row per assignment, in
 *   the widget's order, with the [Assignments] columns.
 * - `content://com.teamsassignments.widget.assignments/state`: one row, the [State] columns.
 * - Observers of either, or of the authority's root, hear of every change to the list or the sync
 *   status, and once whenever this app's process starts.
 * - [METHOD_REQUEST_SYNC] and [METHOD_OPEN] through `ContentResolver.call` on the root URI.
 *
 * Every entry point needs [PERMISSION]. Queries and calls block while this app's process starts
 * (and calls for up to two seconds more, while the sync service connects), so make them off the
 * main thread.
 */
object AssignmentsContract {
    /** Signature-level: granted, without asking, only to apps signed with this app's key. */
    const val PERMISSION = "com.teamsassignments.widget.permission.READ_ASSIGNMENTS"

    const val AUTHORITY = "com.teamsassignments.widget.assignments"

    object Assignments {
        const val PATH = "assignments"

        /** The Teams assignment GUID, or until Teams shows one a stand-in made from class and title ("h-…"). */
        const val KEY = "key"
        const val TITLE = "title"
        const val CLASS_NAME = "class_name"

        /** Plain-text instructions, at most 2,000 characters. Empty until the assignment's own screen has been read. */
        const val DESCRIPTION = "description"

        /** The due text as Teams showed it. */
        const val DUE_TEXT = "due_text"

        /** Due time in epoch milliseconds, or null if [DUE_TEXT] couldn't be read as one. */
        const val DUE_AT = "due_at"

        /** The tab it was listed under: "Forthcoming" or "PastDue". */
        const val TAB = "tab"

        /** When its own screen was last read, in epoch milliseconds, or null if only its list row is known. */
        const val DETAIL_READ_AT = "detail_read_at"

        /** When this app last saw it in Teams, during a sync or while Teams was open, in epoch milliseconds. */
        const val LAST_SYNCED_AT = "last_synced_at"

        val COLUMNS = arrayOf(KEY, TITLE, CLASS_NAME, DESCRIPTION, DUE_TEXT, DUE_AT, TAB, DETAIL_READ_AT, LAST_SYNCED_AT)
    }

    object State {
        const val PATH = "state"

        /** When the last sync finished, in epoch milliseconds, or null if none has. */
        const val LAST_SUCCESS_AT = "last_success_at"

        /** "idle", "running", "failed" or "stopped" (cancelled, or Teams left mid-sync). */
        const val STATUS = "status"

        /** What went wrong for "failed"; "Sync cancelled" or "Sync stopped" for "stopped"; otherwise null. */
        const val STATUS_MESSAGE = "status_message"

        /** When a "running" sync started, or the other statuses were set, in epoch milliseconds; null when "idle". */
        const val STATUS_AT = "status_at"

        const val ASSIGNMENT_COUNT = "assignment_count"

        /**
         * 1 if the sync service is switched on in Accessibility settings, else 0. Without it, the
         * two calls can't start. Read when queried: observers aren't told when it changes.
         */
        const val SYNC_SERVICE_ENABLED = "sync_service_enabled"

        val COLUMNS = arrayOf(LAST_SUCCESS_AT, STATUS, STATUS_MESSAGE, STATUS_AT, ASSIGNMENT_COUNT, SYNC_SERVICE_ENABLED)
    }

    /**
     * Syncs, as the widget's ↻ does. It takes over the screen until it returns to the home screen,
     * so ask only when the user has just asked for it.
     */
    const val METHOD_REQUEST_SYNC = "requestSync"

    /**
     * Opens the assignment whose [Assignments.KEY] is the call's `arg` in Teams, as a widget row tap
     * does, leaving the user on it. It takes over the screen the same way.
     */
    const val METHOD_OPEN = "open"

    /** In a call's result: true if the sync or the opening has started. */
    const val RESULT_STARTED = "started"

    /** In a call's result, when nothing started: [REASON_SERVICE_OFF], [REASON_BUSY] or [REASON_UNKNOWN_KEY]. */
    const val RESULT_REASON = "reason"

    /** The sync service is switched off in Accessibility settings. */
    const val REASON_SERVICE_OFF = "service_off"

    /** A sync, an opening or a hand-in is already driving Teams. */
    const val REASON_BUSY = "busy"

    /** No assignment on the list has that key (or none was given). */
    const val REASON_UNKNOWN_KEY = "unknown_key"
}
