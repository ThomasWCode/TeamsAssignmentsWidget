package com.teamsassignments.widget.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import com.teamsassignments.widget.data.AssignmentStore
import com.teamsassignments.widget.data.DueDateParser
import com.teamsassignments.widget.data.SyncLog
import com.teamsassignments.widget.data.SyncStatus
import com.teamsassignments.widget.widget.WidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock

/**
 * Drives Teams for the widget. It ignores accessibility events unless a run is in progress
 * (or the screen dumper is armed), and only receives events from Teams at all (see
 * `res/xml/automation_service_config.xml`).
 */
class TeamsAutomationService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    @Volatile
    private var cancelRequested = false

    private lateinit var device: AndroidTeamsDevice
    private lateinit var banner: OverlayBanner
    private lateinit var dumper: ScreenDumper
    private val store by lazy { AssignmentStore.get(this) }
    private val log by lazy { SyncLog.get(this) }

    val isBusy: Boolean get() = job?.isActive == true
    val isDumperArmed: Boolean get() = ::dumper.isInitialized && dumper.isArmed

    override fun onServiceConnected() {
        super.onServiceConnected()
        device = AndroidTeamsDevice(this)
        banner = OverlayBanner(this)
        dumper = ScreenDumper(this, device, log, scope)
        instance = this
        _connected.value = true
        log.add("Service connected")
        scope.launch {
            // A run can't survive the service being unbound, so a Running status here is stale.
            if (store.state.value.status is SyncStatus.Running) store.markFailed(AssignmentStore.INTERRUPTED)
            WidgetUpdater.update(this@TeamsAutomationService)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (isBusy) device.onEvent()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        scope.cancel()
        super.onDestroy()
    }

    private fun disconnect() {
        if (instance !== this) return
        instance = null
        _connected.value = false
        cancelRequested = true
        job?.cancel()
        if (::banner.isInitialized) banner.hide()
        if (::dumper.isInitialized) dumper.disarm()
        log.add("Service disconnected")
        // The widget shows "finish setup" once the service is off. This scope is ending, so use another.
        CoroutineScope(Dispatchers.Default).launch { WidgetUpdater.update(applicationContext) }
    }

    /**
     * Starts a sync unless one is already running. [full] rereads every assignment's details.
     * Afterwards the phone goes to [returnTo] if given, otherwise to the home screen and its widget.
     */
    fun startSync(full: Boolean = false, returnTo: Intent? = null): Boolean {
        if (isBusy) return false
        cancelRequested = false
        dumper.disarm()
        job = scope.launch { runSync(full, returnTo) }
        return true
    }

    /** Opens the saved assignment with [key] in Teams. False if busy or unknown. */
    fun openAssignment(key: String): Boolean {
        if (isBusy) return false
        val target = store.state.value.assignments.firstOrNull { it.key == key } ?: return false
        cancelRequested = false
        dumper.disarm()
        job = scope.launch {
            banner.show("Opening assignment…", "Cancel", showSpinner = true) { cancelRequested = true }
            val opened = try {
                // Off the main thread: every step reads Teams' whole accessibility tree over IPC.
                withContext(Dispatchers.Default) {
                    NavigateStateMachine(
                        device = device,
                        now = SystemClock::elapsedRealtime,
                        log = log::add,
                        isCancelled = { cancelRequested },
                    ).run(target)
                }
            } catch (e: SyncAbort) {
                log.add("Opening stopped: ${e.reason}")
                if (e.byUser) null else false
            } finally {
                withContext(NonCancellable) {
                    banner.hide()
                    log.persist()
                }
            }
            if (opened == false) {
                Toast.makeText(this@TeamsAutomationService, "Couldn't find “${target.title}” in Teams", Toast.LENGTH_LONG).show()
            }
        }
        return true
    }

    fun armDumper() {
        if (!isBusy) dumper.arm()
    }

    fun disarmDumper() = dumper.disarm()

    private suspend fun runSync(full: Boolean, returnTo: Intent?) {
        log.add(if (full) "── Full sync ──" else "── Sync ──")
        banner.show("Syncing assignments…", "Cancel", showSpinner = true) { cancelRequested = true }
        store.markRunning(0, 0)
        WidgetUpdater.update(this)

        // Leave Teams afterwards, unless the user has already gone to another app.
        var leaveTeams = true
        try {
            val machine = SyncStateMachine(
                device = device,
                parser = DueDateParser(Clock.systemDefaultZone()),
                wallClock = System::currentTimeMillis,
                onProgress = { done, total ->
                    withContext(Dispatchers.Main.immediate) { banner.update("Syncing assignments $done/$total") }
                    store.markRunning(done, total)
                    WidgetUpdater.update(this)
                },
                now = SystemClock::elapsedRealtime,
                log = log::add,
                isCancelled = { cancelRequested },
            )
            // Off the main thread: every step reads Teams' whole accessibility tree over IPC.
            val result = withContext(Dispatchers.Default) { machine.run(store.state.value.assignments, full) }
            store.saveSuccess(result)
            log.add("Saved ${result.size} assignments")
        } catch (e: SyncAbort) {
            log.add("Sync stopped: ${e.reason}")
            if (!e.byUser) dumper.saveFailureDump()
            store.markFailed(e.reason)
            if (e.reason == SyncAbort.LEFT_TEAMS) leaveTeams = false
        } catch (e: CancellationException) {
            withContext(NonCancellable) { store.markFailed(AssignmentStore.INTERRUPTED) }
            throw e
        } catch (e: Exception) {
            Log.e(SyncLog.TAG, "Sync crashed", e)
            log.add("Sync crashed: $e")
            store.markFailed("Unexpected error")
        } finally {
            withContext(NonCancellable) {
                banner.hide()
                log.persist()
                WidgetUpdater.update(this@TeamsAutomationService)
            }
        }
        if (leaveTeams && device.foregroundPackage() == TeamsSelectors.TEAMS_PACKAGE) {
            if (returnTo != null) startActivity(returnTo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else device.home()
        }
    }

    companion object {
        @Volatile
        var instance: TeamsAutomationService? = null
            private set

        private val _connected = MutableStateFlow(false)

        /** Whether the system has the service bound right now. */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        /** Whether the service is switched on in Accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
            return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val info = it.resolveInfo.serviceInfo
                info.packageName == context.packageName && info.name == TeamsAutomationService::class.java.name
            }
        }

        /**
         * The running service, waiting briefly when it is enabled but not bound yet: a widget tap
         * can start the app's process a moment before the system reconnects the service.
         */
        suspend fun awaitInstance(context: Context, timeoutMs: Long = 2_000): TeamsAutomationService? {
            instance?.let { return it }
            if (!isEnabled(context)) return null
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (instance == null && SystemClock.elapsedRealtime() < deadline) delay(50)
            return instance
        }
    }
}
