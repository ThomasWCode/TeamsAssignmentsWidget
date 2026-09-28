package com.teamsassignments.widget.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import com.teamsassignments.widget.data.AssignmentStore
import com.teamsassignments.widget.data.WidgetState
import java.time.Clock

/** Redraws every placed widget after the state changes, and schedules the next redraw. */
object WidgetUpdater {
    const val ACTION_REDRAW = "com.teamsassignments.widget.action.REDRAW"

    suspend fun update(context: Context) {
        AssignmentsWidget().updateAll(context)
        scheduleRedraw(context, AssignmentStore.get(context).state.value)
    }

    /**
     * Sets a non-waking alarm for the next midnight or deadline (see [WidgetText.nextRedrawAt]).
     * It fires the next time the phone is in use after that moment, which is all a home-screen
     * widget needs, and costs nothing while the phone sleeps. Each call replaces the last alarm.
     */
    fun scheduleRedraw(context: Context, state: WidgetState) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val redraw = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, AssignmentsWidgetReceiver::class.java).setAction(ACTION_REDRAW),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarms.set(AlarmManager.RTC, WidgetText.nextRedrawAt(state.assignments, Clock.systemDefaultZone()), redraw)
    }
}
