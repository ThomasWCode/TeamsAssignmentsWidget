package com.teamsassignments.widget.notices

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.teamsassignments.widget.automation.TeamsSelectors
import com.teamsassignments.widget.data.AssignmentStore
import com.teamsassignments.widget.data.SyncLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Notes when Teams posts a notification about assignments ([TeamsNotices]), so an app reading the
 * list (Decrastination) can sync soon after, rather than at its next timed sync. It reads Teams'
 * notifications alone, keeps nothing of them but the time of the last that matched (and its title
 * in the log), and never syncs or touches a notification itself. Optional: without notification
 * access, everything else works as before.
 */
class TeamsNoticeListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onListenerConnected() {
        // Those already showing when access was given, or the listener was bound again.
        runCatching { activeNotifications }.getOrNull()?.forEach(::look)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = look(sbn)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun look(sbn: StatusBarNotification) {
        if (sbn.packageName != TeamsSelectors.TEAMS_PACKAGE) return
        val notice = notice(sbn.notification)
        val matched = TeamsNotices.aboutAssignments(notice)
        // The channel alone, never the text: for checking the rule against Teams' real notices.
        Log.i(SyncLog.TAG, "Teams notice on ${notice.channelId}: ${if (matched) "about assignments" else "not about assignments"}")
        if (!matched) return
        scope.launch {
            val store = AssignmentStore.get(this@TeamsNoticeListener)
            val before = store.state.value.noticedAt
            store.markNoticed(sbn.postTime)
            // Each notice once, though Android hands those still showing over again on a rebind.
            if (before == null || sbn.postTime > before) {
                val log = SyncLog.get(this@TeamsNoticeListener)
                log.add("Teams notice about assignments: \"${notice.title}\"")
                log.persist()
            }
        }
    }

    private fun notice(notification: Notification): TeamsNotices.Notice {
        val extras = notification.extras ?: Bundle.EMPTY
        fun text(key: String) = extras.getCharSequence(key)?.toString().orEmpty()
        return TeamsNotices.Notice(
            channelId = notification.channelId.orEmpty(),
            title = text(Notification.EXTRA_TITLE),
            text = text(Notification.EXTRA_TEXT),
            bigText = text(Notification.EXTRA_BIG_TEXT),
            subText = text(Notification.EXTRA_SUB_TEXT),
            summaryText = text(Notification.EXTRA_SUMMARY_TEXT),
            conversationTitle = text(Notification.EXTRA_CONVERSATION_TITLE),
            groupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        )
    }

    companion object {
        /** Whether the user has given this app access to notifications (Settings → Notification access). */
        fun isEnabled(context: Context): Boolean = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        fun component(context: Context) = ComponentName(context, TeamsNoticeListener::class.java)
    }
}
