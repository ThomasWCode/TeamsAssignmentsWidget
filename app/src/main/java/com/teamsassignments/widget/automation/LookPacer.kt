package com.teamsassignments.widget.automation

/**
 * When the service looks at Teams while nothing runs (see [TeamsObserver]). A look only copies
 * Teams' window on Assignments, but even the check for that reaches into Teams, so:
 * - once a look has found Teams elsewhere (in a chat, say, where things change all the time), the
 *   next comes when Teams opens another screen, or after [recheckMs]; a change held back meanwhile
 *   is [owed] its look, which comes then even if Teams has gone quiet;
 * - a look that found Assignments but couldn't copy it (the tree changing underneath, or too big)
 *   is owed another, up to [maxFailures] in a row, and doesn't count as elsewhere;
 * - so is one that found an app's page it couldn't place. An assignment opened from Teams'
 *   Activity feed is such a page until it has loaded, a second or two on the phone. After that
 *   many looks in a row, the page is some other app's, and counts as elsewhere.
 */
class LookPacer(private val recheckMs: Long = 5_000, private val maxFailures: Int = 5) {

    enum class Outcome {
        /** Assignments was read. */
        Read,

        /** Teams wasn't showing Assignments. */
        Elsewhere,

        /** Teams was showing Assignments, but it couldn't be read. */
        Failed,

        /** Teams was showing an app's page that isn't an assignment's, or isn't yet. */
        Unsure,
    }

    private var elsewhereAt: Long? = null
    private var failures = 0

    /** Whether a look is due without a further change in Teams: one held back, or one to retry. */
    var owed = false
        private set

    /**
     * Whether to look now. [now] is a monotonic clock in milliseconds; [screenChanged] is whether
     * Teams has opened another screen since the last look.
     */
    fun shouldLook(now: Long, screenChanged: Boolean): Boolean {
        val lastElsewhere = elsewhereAt
        if (lastElsewhere != null && !screenChanged && now - lastElsewhere < recheckMs) {
            owed = true
            return false
        }
        owed = false
        return true
    }

    fun looked(outcome: Outcome, now: Long) {
        when (outcome) {
            Outcome.Read -> {
                elsewhereAt = null
                failures = 0
            }
            Outcome.Elsewhere -> {
                elsewhereAt = now
                failures = 0
            }
            Outcome.Failed -> owed = ++failures < maxFailures
            // The count stands once it's reached, so a page that stays unplaced gets one look at a
            // time from then on; a read, or leaving the page, starts it afresh.
            Outcome.Unsure -> if (++failures < maxFailures) {
                elsewhereAt = null
                owed = true
            } else {
                elsewhereAt = now
            }
        }
    }

    /** Starts afresh, for when a workflow has been driving Teams. */
    fun reset() {
        elsewhereAt = null
        failures = 0
        owed = false
    }
}
