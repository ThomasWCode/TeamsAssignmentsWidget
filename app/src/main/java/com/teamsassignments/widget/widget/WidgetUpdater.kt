package com.teamsassignments.widget.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import com.teamsassignments.widget.data.AssignmentStore
import com.teamsassignments.widget.data.WidgetState
import java.time.Clock

/** Redraws every placed widget after the state changes, and schedules the next redraw. */
object WidgetUpdater {
    const val ACTION_REDRAW = "com.teamsassignments.widget.action.REDRAW"

    /**
     * How late a redraw may arrive. Android 12+ widens shorter windows to 10 minutes anyway, and
     * anything tighter would need the exact-alarm permission; a plain set() has no bound at all.
     */
    private const val REDRAW_WINDOW_MS = 10 * 60_000L

    suspend fun update(context: Context) {
        AssignmentsWidget().updateAll(context)
        scheduleRedraw(context, AssignmentStore.get(context).state.value)
    }

    /**
     * Sets a non-waking alarm for the next midnight or deadline (see [WidgetText.nextRedrawAt]),
     * delivered within [REDRAW_WINDOW_MS] while the phone is in use and at the next wake-up
     * otherwise. Each call replaces the last alarm; with no widget placed there is nothing to
     * redraw, so the alarm is cancelled instead.
     */
    fun scheduleRedraw(context: Context, state: WidgetState) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        if (!hasWidgets(context)) {
            alarms.cancel(redrawIntent(context))
            return
        }
        val at = WidgetText.nextRedrawAt(state.assignments, Clock.systemDefaultZone())
        alarms.setWindow(AlarmManager.RTC, at, REDRAW_WINDOW_MS, redrawIntent(context))
    }

    /** Called when the last widget is removed. */
    fun cancelRedraw(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(redrawIntent(context))
    }

    private fun hasWidgets(context: Context): Boolean =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, AssignmentsWidgetReceiver::class.java))
            .isNotEmpty()

    private fun redrawIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, AssignmentsWidgetReceiver::class.java).setAction(ACTION_REDRAW),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
