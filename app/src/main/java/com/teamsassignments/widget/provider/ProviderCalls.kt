package com.teamsassignments.widget.provider

import com.teamsassignments.widget.data.Assignment
import com.teamsassignments.widget.provider.AssignmentsContract.REASON_BUSY
import com.teamsassignments.widget.provider.AssignmentsContract.REASON_SERVICE_OFF
import com.teamsassignments.widget.provider.AssignmentsContract.REASON_UNKNOWN_KEY

/** What the provider's calls need from the sync service: an interface, so they can be tested on the JVM. */
internal interface SyncControl {
    /** Whether a sync, an opening or a hand-in is driving Teams. */
    val isBusy: Boolean

    /** Starts a sync; false if one is already running. */
    fun startSync(): Boolean

    /** Opens the saved assignment with [key]; false if busy or unknown. */
    fun openAssignment(key: String): Boolean
}

/** A call's result: whether it started and, if not, why (see [AssignmentsContract.RESULT_REASON]). */
internal enum class CallOutcome(val started: Boolean, val reason: String?) {
    Started(true, null),
    ServiceOff(false, REASON_SERVICE_OFF),
    Busy(false, REASON_BUSY),
    UnknownKey(false, REASON_UNKNOWN_KEY),
}

/** The two calls, given the service if it's running ([control] is null when it's switched off). */
internal object ProviderCalls {

    fun requestSync(control: SyncControl?): CallOutcome = when {
        control == null -> CallOutcome.ServiceOff
        control.startSync() -> CallOutcome.Started
        else -> CallOutcome.Busy
    }

    fun open(control: SyncControl?, key: String?, saved: List<Assignment>): CallOutcome = when {
        key == null || saved.none { it.key == key } -> CallOutcome.UnknownKey
        control == null -> CallOutcome.ServiceOff
        control.openAssignment(key) -> CallOutcome.Started
        // The service refuses when busy, or when the key has just left the list.
        control.isBusy -> CallOutcome.Busy
        else -> CallOutcome.UnknownKey
    }
}
