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
        if (intent.action != WidgetUpdater.ACTION_REDRAW) {
            super.onReceive(context, intent)
            return
        }
        // The midnight / deadline redraw: no sync, just draw the stored list again.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WidgetUpdater.update(context)
            } finally {
                pending.finish()
            }
        }
    }
}
