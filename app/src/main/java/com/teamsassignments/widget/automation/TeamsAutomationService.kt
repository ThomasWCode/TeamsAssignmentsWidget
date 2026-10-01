package com.teamsassignments.widget.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.teamsassignments.widget.automation.AndroidTeamsDevice.TeamsView
import com.teamsassignments.widget.data.Assignment
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
 * Drives Teams for the widget. It only receives events from Teams at all (see
 * `res/xml/automation_service_config.xml`). While a run is in progress they pace the run; the
 * rest of the time they only prompt a look at Teams, which reads Assignments when that's what
 * the user has open and presses nothing (see [TeamsObserver]).
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

    /** Only used on the main thread, like everything below it. */
    private val observer = TeamsObserver()
    private var watching: Job? = null
    private var teamsChanged = false

    /** Whether Teams has opened another screen since the last look. */
    private var teamsScreenChanged = false
    private val pacer = LookPacer()

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
        if (isBusy) {
            device.onEvent()
        } else {
            teamsChanged = true
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) teamsScreenChanged = true
            if (watching?.isActive != true) watching = scope.launch { watchTeams() }
        }
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
        stopWatching()
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
        stopWatching()
        job = scope.launch {
            runSync(full, returnTo)
            lookAfterwards()
        }
        return true
    }

    /** Opens the saved assignment with [key] in Teams. False if busy or unknown. */
    fun openAssignment(key: String): Boolean {
        if (isBusy) return false
        val target = store.state.value.assignments.firstOrNull { it.key == key } ?: return false
        cancelRequested = false
        dumper.disarm()
        stopWatching()
        job = scope.launch {
            banner.show("Opening assignment…", "Cancel", showSpinner = true) { cancelRequested = true }
            val navigate = NavigateStateMachine(
                device = device,
                now = SystemClock::elapsedRealtime,
                log = log::add,
                isCancelled = { cancelRequested },
            )
            val opened = try {
                // Off the main thread: every step reads Teams' whole accessibility tree over IPC.
                withContext(Dispatchers.Default) { navigate.run(target, store.state.value.assignments) }
            } catch (e: SyncAbort) {
                log.add("Opening stopped: ${e.reason}")
                if (e.byUser) null else false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A row tap must never take the app down (and the sync service with it).
                Log.e(SyncLog.TAG, "Opening an assignment crashed", e)
                log.add("Opening crashed: $e")
                false
            } finally {
                withContext(NonCancellable) {
                    banner.hide()
                    log.persist()
                }
            }
            if (opened == false && navigate.notListed) {
                // Both open tabs read in full, and it's on neither: as a sync would, drop it. Not
                // remembered as handed in, so were that wrong, the next look at its list restores it.
                store.markHandedIn(target.key, remembered = false)
                log.add("\"${target.title}\" taken as handed in")
                log.persist()
                WidgetUpdater.update(this@TeamsAutomationService)
                banner.showMessage("Taken as handed in: “${target.title}” is on neither Forthcoming nor Past due")
            } else if (opened == false) {
                banner.showMessage("Couldn't find “${target.title}” in Teams")
            }
            lookAfterwards()
        }
        return true
    }

    /**
     * Hands in the saved assignment with [key] on Teams. Only called once the user has confirmed
     * on the widget (see `ui/HandInActivity`). False if busy or unknown.
     */
    fun handIn(key: String): Boolean {
        if (isBusy) return false
        val target = store.state.value.assignments.firstOrNull { it.key == key } ?: return false
        cancelRequested = false
        dumper.disarm()
        stopWatching()
        job = scope.launch {
            runHandIn(target)
            lookAfterwards()
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
            if (e.byUser) {
                store.markStopped(cancelled = e.reason == SyncAbort.CANCELLED)
            } else {
                dumper.saveFailureDump()
                store.markFailed(e.reason)
            }
            if (e.reason == SyncAbort.LEFT_TEAMS) leaveTeams = false
        } catch (e: CancellationException) {
            withContext(NonCancellable) { store.markFailed(AssignmentStore.INTERRUPTED) }
            throw e
        } catch (e: Exception) {
            Log.e(SyncLog.TAG, "Sync crashed", e)
            log.add("Sync crashed: $e")
            dumper.saveFailureDump() // the failures most in need of a capture
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

    /**
     * The messages lead with the outcome, and go on the pill ([OverlayBanner.showMessage]): from
     * here, in the background, a toast would be dropped.
     */
    private suspend fun runHandIn(target: Assignment) {
        log.add("── Hand in ──")
        banner.show("Handing in…", "Cancel", showSpinner = true) { cancelRequested = true }
        val name = "“${target.title}”"
        var pressed = false
        var backToWidget = false
        val message = try {
            // Off the main thread: every step reads Teams' whole accessibility tree over IPC.
            val result = withContext(Dispatchers.Default) {
                HandInStateMachine(
                    device = device,
                    now = SystemClock::elapsedRealtime,
                    log = log::add,
                    isCancelled = { cancelRequested },
                    onPressing = {
                        // On the main thread, where Cancel is handled: either it was tapped already, or
                        // the pill loses its button now, and nothing can call the hand-in off after.
                        withContext(Dispatchers.Main.immediate) {
                            if (cancelRequested) {
                                false
                            } else {
                                pressed = true
                                banner.show("Handing in…", "", showSpinner = true) {}
                                true
                            }
                        }
                    },
                ).run(target, store.state.value.assignments)
            }
            log.add("Hand-in result: $result")
            when (result) {
                HandInResult.HandedIn, HandInResult.AlreadyHandedIn -> {
                    store.markHandedIn(target.key)
                    backToWidget = true
                    if (result == HandInResult.HandedIn) "Handed in $name" else "$name was already handed in"
                }
                // Both open tabs read in full, and it's on neither: as a sync would, drop it. Not
                // remembered as handed in, so were that wrong, the next look at its list restores it.
                HandInResult.NotListed -> {
                    store.markHandedIn(target.key, remembered = false)
                    backToWidget = true
                    "Nothing pressed: $name is on neither Forthcoming nor Past due, so it's taken as handed in."
                }
                // It couldn't all be read in full; a sync (↻) settles it.
                HandInResult.NotFound -> "Nothing handed in: couldn't find $name on Teams' Forthcoming or Past due list. ↻ updates the widget."
                HandInResult.Mismatch -> {
                    dumper.saveFailureDump()
                    "Nothing handed in: the screen Teams opened wasn't exactly $name."
                }
                HandInResult.NoButton -> {
                    dumper.saveFailureDump()
                    "Nothing handed in: Teams showed no Hand in button for $name."
                }
                HandInResult.Unconfirmed -> {
                    dumper.saveFailureDump()
                    "Hand in pressed, but Teams didn't confirm it. Check $name in Teams."
                }
            }
        } catch (e: SyncAbort) {
            // Every abort comes before Hand in is pressed: the workflow reports a stop after it as Unconfirmed.
            log.add("Hand-in stopped: ${e.reason}")
            if (!e.byUser) dumper.saveFailureDump()
            if (e.reason == SyncAbort.CANCELLED) "Hand-in cancelled. Nothing was handed in." else "Nothing handed in: ${e.reason}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A hand-in must never take the app down (and the sync service with it).
            Log.e(SyncLog.TAG, "Handing in crashed", e)
            log.add("Hand-in crashed: $e")
            dumper.saveFailureDump()
            if (pressed) "Hand in pressed, but then something went wrong. Check $name in Teams." else "Nothing handed in: something went wrong."
        } finally {
            withContext(NonCancellable) {
                banner.hide()
                log.persist()
                WidgetUpdater.update(this@TeamsAutomationService)
            }
        }
        // Back to the widget, which no longer lists it. Otherwise Teams stays open on what happened.
        if (backToWidget && device.foregroundPackage() == TeamsSelectors.TEAMS_PACKAGE) device.home()
        banner.showMessage(message)
    }

    /**
     * Keeps the list up to date from what the user looks at in Teams Assignments, pressing
     * nothing (see [TeamsObserver]). It looks shortly after Teams changes, again while the screen
     * is still settling, and when [LookPacer] says a look is owed; changes that arrive meanwhile
     * are caught by the next look.
     */
    private suspend fun watchTeams() {
        delay(LOOK_DELAY_MS)
        while (teamsChanged || observer.settling || pacer.owed) {
            teamsChanged = false
            if (isBusy) return
            lookAtTeams()
            delay(LOOK_INTERVAL_MS)
        }
    }

    private suspend fun lookAtTeams() {
        // Elsewhere in Teams, in a chat say, things change all the time: see LookPacer.
        val now = SystemClock.elapsedRealtime()
        if (!pacer.shouldLook(now, teamsScreenChanged)) return
        teamsScreenChanged = false
        var view = TeamsView.Other
        val root = withContext(Dispatchers.Default) {
            try {
                // That check is cheap; copying the whole window is only worth it where Assignments may be.
                view = device.teamsView()
                if (view == TeamsView.Other) null else device.teamsSnapshot()
            } catch (e: Exception) {
                Log.w(SyncLog.TAG, "Couldn't read Teams", e)
                null
            }
        }
        if (isBusy) return
        // A page that doesn't name Assignments counts only as an assignment's own screen.
        val onAssignments = view == TeamsView.Assignments || (root != null && TeamsScreens.isDetail(root))
        if (root == null || !onAssignments) {
            // Assignments whose tree couldn't be copied (changing underneath, say) is tried again
            // shortly, as is a page that may yet load as an assignment's. Anywhere but on
            // Assignments, what was seen of it no longer holds.
            when (view) {
                TeamsView.Assignments -> pacer.looked(LookPacer.Outcome.Failed, now)
                TeamsView.WebModule -> pacer.looked(LookPacer.Outcome.Unsure, now)
                TeamsView.Other -> pacer.looked(LookPacer.Outcome.Elsewhere, now)
            }
            if (view != TeamsView.Assignments) observer.reset()
            return
        }
        pacer.looked(LookPacer.Outcome.Read, now)
        val sighting = observer.look(root, SystemClock.elapsedRealtime(), System.currentTimeMillis(), store.state.value.assignments)
            ?: return
        var changes = emptyList<String>()
        store.applyObserved { saved, handedInLately ->
            val merged = TeamsObserver.merge(
                sighting, saved, DueDateParser(Clock.systemDefaultZone()), System.currentTimeMillis(), handedInLately,
            )
            changes = merged.changes
            AssignmentStore.Observed(merged.assignments, merged.handedIn)
        }
        if (changes.isEmpty()) return
        changes.forEach { log.add("Seen in Teams: $it") }
        log.persist()
        WidgetUpdater.update(this)
    }

    /**
     * After a workflow: looks at wherever it left Teams, such as the assignment a row tap opened.
     * Its events went to the workflow, and the user may just read the screen, prompting no more.
     */
    private fun lookAfterwards() {
        teamsChanged = true
        if (watching?.isActive != true) watching = scope.launch { watchTeams() }
    }

    /** Stops looking at Teams, for when a workflow is about to drive it. */
    private fun stopWatching() {
        watching?.cancel()
        watching = null
        pacer.reset()
        observer.reset()
    }

    companion object {
        /** How long after a change in Teams to take a look, so a burst of changes is seen once. */
        private const val LOOK_DELAY_MS = 500L

        /** Between looks while Teams keeps changing; longer than [TeamsObserver]'s settle time. */
        private const val LOOK_INTERVAL_MS = 700L

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
