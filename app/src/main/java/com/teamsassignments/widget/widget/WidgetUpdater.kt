package com.teamsassignments.widget.widget

import android.content.Context

/** Redraws every placed widget after the state changes. */
object WidgetUpdater {
    // The Glance widget arrives in the next stage; until then there is nothing to redraw.
    @Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
    suspend fun update(context: Context) = Unit
}
