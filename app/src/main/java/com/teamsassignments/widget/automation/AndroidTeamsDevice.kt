package com.teamsassignments.widget.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** [TeamsDevice] backed by the accessibility service. */
class AndroidTeamsDevice(private val service: AccessibilityService) : TeamsDevice {

    private val changes = Channel<Unit>(Channel.CONFLATED)

    /** Called for every accessibility event from Teams while a workflow runs. */
    fun onEvent() {
        changes.trySend(Unit)
    }

    override fun launchAssignments(): Boolean = TeamsLauncher.launchAssignments(service)

    /**
     * A snapshot of Teams' window. A tree too big to copy whole ends the run rather than pass as
     * complete: a sync saves what it reads, and a cut-off list would drop assignments.
     */
    override fun teamsRoot(): UiNode? {
        val root = teamsWindowRoot() ?: return null
        val snapshot = AndroidUiNode.snapshot(root)
        if (!snapshot.complete) throw SyncAbort("Teams showed more than can be read at once")
        return snapshot.root
    }

    /** The root of Teams' window, but only while Teams is the app on top. */
    fun teamsWindowRoot(): AccessibilityNodeInfo? =
        topAppWindow()?.root?.takeIf { it.packageName == TeamsSelectors.TEAMS_PACKAGE }

    /**
     * The app the user is looking at. A system window over the middle of the screen, such as the
     * notification shade, counts too: the user has pulled something over Teams, so the workflow
     * treats it like leaving Teams (it waits, then stops) rather than working underneath it.
     */
    override fun foregroundPackage(): String? {
        val windows = windowInfos()
        val app = WindowCover.topApp(windows) ?: return service.rootInActiveWindow?.packageName?.toString()
        return (WindowCover.coveringWindow(windows, app.bounds.centerX, app.bounds.centerY) ?: app).packageName
    }

    private fun topAppWindow(): AccessibilityWindowInfo? =
        service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.maxByOrNull { it.layer }

    private fun windowInfos(): List<WindowInfo> {
        val rect = Rect()
        return service.windows.map { window ->
            window.getBoundsInScreen(rect)
            WindowInfo(
                type = window.type,
                layer = window.layer,
                bounds = IntRect(rect.left, rect.top, rect.right, rect.bottom),
                packageName = window.root?.packageName?.toString(),
            )
        }
    }

    override suspend fun awaitChange(timeoutMs: Long) {
        val changed = withTimeoutOrNull(timeoutMs) { changes.receive() }
        // Teams sends bursts of content-changed events while rendering; let a burst finish.
        if (changed != null) delay(EVENT_BURST_MS)
    }

    override fun back(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    override fun home(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    /**
     * Taps a point, unless another window (the notification shade, a heads-up notification, the
     * keyboard) covers it: an injected tap goes to whatever is on top, never through it.
     */
    override suspend fun tap(x: Int, y: Int): Boolean {
        if (WindowCover.coveringWindow(windowInfos(), x, y) != null) return false
        return suspendCancellableCoroutine { continuation ->
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
                .build()
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            if (!service.dispatchGesture(gesture, callback, null) && continuation.isActive) continuation.resume(false)
        }
    }

    private companion object {
        const val EVENT_BURST_MS = 60L
        const val TAP_MS = 60L
    }
}
