package com.teamsassignments.widget.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
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

    override fun teamsRoot(): UiNode? = teamsWindowRoot()?.let { AndroidUiNode.snapshot(it) }

    /** The root of Teams' window, but only while Teams is the app on top. */
    fun teamsWindowRoot(): AccessibilityNodeInfo? =
        topAppRoot()?.takeIf { it.packageName == TeamsSelectors.TEAMS_PACKAGE }

    override fun foregroundPackage(): String? = topAppRoot()?.packageName?.toString()

    /**
     * The topmost application window. System windows (the notification shade) and our own
     * overlay aren't application windows, so they don't count as the user leaving Teams.
     */
    private fun topAppRoot(): AccessibilityNodeInfo? {
        val window = service.windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .maxByOrNull { it.layer }
        return window?.root ?: service.rootInActiveWindow
    }

    override suspend fun awaitChange(timeoutMs: Long) {
        val changed = withTimeoutOrNull(timeoutMs) { changes.receive() }
        // Teams sends bursts of content-changed events while rendering; let a burst finish.
        if (changed != null) delay(EVENT_BURST_MS)
    }

    override fun back(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    override fun home(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    override suspend fun tap(x: Int, y: Int): Boolean = suspendCancellableCoroutine { continuation ->
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

    private companion object {
        const val EVENT_BURST_MS = 60L
        const val TAP_MS = 60L
    }
}
