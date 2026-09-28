package com.teamsassignments.widget.widget

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AssignmentsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AssignmentsWidget()

    /** The last widget was removed: stop the midnight/deadline redraws. */
    override fun onDisabled(context: Context) {
        WidgetUpdater.cancelRedraw(context)
        super.onDisabled(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in REDRAW_ACTIONS) {
            super.onReceive(context, intent)
            return
        }
        // No sync, just draw the stored list again: at midnight or a deadline, or because the
        // time zone or clock changed, which moves both the day boundaries and the next alarm.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WidgetUpdater.update(context)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val REDRAW_ACTIONS = setOf(
            WidgetUpdater.ACTION_REDRAW,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
        )
    }
}
