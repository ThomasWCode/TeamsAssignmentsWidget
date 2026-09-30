package com.teamsassignments.widget.automation

/**
 * When the service looks at Teams while nothing runs (see [TeamsObserver]). A look only copies
 * Teams' window on Assignments, but even the check for that reaches into Teams, so:
 * - once a look has found Teams elsewhere (in a chat, say, where things change all the time), the
 *   next comes when Teams opens another screen, or after [recheckMs]; a change held back meanwhile
 *   is [owed] its look, which comes then even if Teams has gone quiet;
 * - a look that found Assignments but couldn't copy it (the tree changing underneath, or too big)
 *   is owed another, up to [maxFailures] in a row, and doesn't count as elsewhere.
 */
class LookPacer(private val recheckMs: Long = 5_000, private val maxFailures: Int = 5) {

    enum class Outcome {
        /** Assignments was read. */
        Read,

        /** Teams wasn't showing Assignments. */
        Elsewhere,

        /** Teams was showing Assignments, but it couldn't be read. */
        Failed,
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
        }
    }

    /** Starts afresh, for when a workflow has been driving Teams. */
    fun reset() {
        elsewhereAt = null
        failures = 0
        owed = false
    }
}
