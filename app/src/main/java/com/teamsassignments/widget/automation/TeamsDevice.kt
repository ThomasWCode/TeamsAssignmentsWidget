package com.teamsassignments.widget.automation

/**
 * What the workflows need from the phone. The accessibility service implements it; the tests
 * fake it with the Phase 0 fixtures.
 */
interface TeamsDevice {
    /** Opens Teams on the Assignments list. Returns false if Teams can't be started. */
    fun launchAssignments(): Boolean

    /** A fresh snapshot of the Teams window, or null when Teams isn't showing. */
    fun teamsRoot(): UiNode?

    /** The package of the app window the user is looking at. */
    fun foregroundPackage(): String?

    /** Suspends until the screen may have changed (an accessibility event) or [timeoutMs] passes. */
    suspend fun awaitChange(timeoutMs: Long)

    fun back(): Boolean

    fun home(): Boolean

    /** Taps a point with an injected gesture: the fallback when a click action does nothing. */
    suspend fun tap(x: Int, y: Int): Boolean
}
