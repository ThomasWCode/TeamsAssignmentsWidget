# Close Teams after a sync: plan (31 Oct 2026, at 0.5.1)

**Status: plan only, nothing built.**

## The change

After a sync, Teams should not appear in the phone's set of open apps (Recents). That only holds if Teams wasn't already open: a Teams the user had open keeps its card.

## Where it stands

- A sync ends in `TeamsAutomationService.runSync`: `device.home()`, or `returnTo` for the app's *Sync now*. That only sends Teams to the background, so its task stays in Recents.
- The sync starts Teams with `TeamsLauncher.assignmentsIntent()`, the Assignments deep link with `FLAG_ACTIVITY_NEW_TASK`. `SyncStateMachine.run` reaches it through `TeamsAutomation.openAssignments()` and `TeamsDevice.launchAssignments()`.
- Row tap (`NavigateStateMachine`) and hand-in (`HandInStateMachine`) launch Teams the same way. Both are out of scope: a row tap leaves Teams open on purpose, and a hand-in already goes home.

## What Android allows

| Way to remove Teams' card | Possible? |
|---|---|
| `ActivityManager.removeTask` | No: needs `REMOVE_TASKS`, a signature permission |
| `AppTask.finishAndRemoveTask` | No: own app's tasks only |
| `killBackgroundProcesses` | Kills the process but leaves the card in Recents, and adds a permission |
| Drive the Recents screen (`GLOBAL_ACTION_RECENTS`, then swipe the card away) | Yes, but Option B below |
| Launch the task hidden from Recents (`FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS`) | Yes: **Option A, the one planned** |

Android has no way for a third-party app to list another app's tasks. So whether Teams was already open can't be asked directly. The foreground app can be read, but a sync starts from the widget on the home screen, so Teams is hardly ever in the foreground then.

## Option A: launch Teams hidden from Recents

- Add `Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS` to the sync's deep-link launch. A task created with it is never listed in Recents.
- It covers every way a sync can end (finished, failed, cancelled, the user leaving Teams): there is no cleanup step to run.
- "Only if Teams wasn't already open" is left to Android, which should apply the flag only when it creates the task. **This is unverified** (see the spike). The flag might instead take an existing task out of Recents.
- Teams' process isn't killed. It may stay cached in memory, which is not what "open apps" lists.

### Code changes

- `TeamsLauncher.assignmentsIntent(excludeFromRecents: Boolean = false)` adds the flag when true. `launchAssignments(context, excludeFromRecents: Boolean = false)` passes it on.
- `TeamsDevice.launchAssignments(excludeFromRecents: Boolean = false)`, with `AndroidTeamsDevice` passing it to `TeamsLauncher` and `FakeTeamsDevice` recording it.
- `TeamsAutomation.openAssignments(excludeFromRecents: Boolean = false)` passes it to the device.
- `SyncStateMachine.run` calls `openAssignments(excludeFromRecents = true)`. `NavigateStateMachine` and `HandInStateMachine` stay as they are.
- `TeamsAutomationService.runSync`'s end-of-sync `home()` / `returnTo` is unchanged.

### Tests

- `FakeTeamsDevice` records the flag it was launched with.
- `SyncStateMachineTest`: a sync launches with the flag set.
- `NavigateStateMachineTest` and `HandInStateMachineTest`: they launch without it.
- Run them with:

```
./gradlew testDebugUnitTest lintDebug assembleDebug
```

### Docs

- `README.md`: a sync no longer leaves Teams in Recents, and what it can't promise for a Teams that was already open.
- `docs/teams-ui-notes.md`: the spike's result, with the phone, Android and Teams versions.

## Spike (before any code)

On the test phone (Galaxy S24, Android 16), start Teams over adb with the exclude flag (`-f 0x00800000`) and the deep link from `docs/teams-ui-notes.md`, press Home, then look at Recents and `adb shell dumpsys activity recents`.

1. **Teams not running.** Is its card hidden? Teams hops from `SplashActivity` to `CustomTabsShellActivity`, and the hidden state may not survive that.
2. **Teams already in Recents** (opened normally, then Home). Does its card stay?
3. **After case 1,** open Teams from the launcher. Does it appear in Recents then, or stay hidden until the task is recreated?

| Result | Then |
|---|---|
| Case 1 hides it, case 2 keeps the card | Build A as planned: no detection needed |
| Case 1 hides it, case 2 removes the card | Add a guard that sets the flag only when Teams isn't the foreground app. It is weak: a Teams sitting in Recents is still hidden. Choose between that and Option B |
| Case 1 doesn't hide it | Option A can't work: Option B |
| Case 3 leaves Teams hidden from Recents for ordinary use | Decide whether that is acceptable before shipping |

## Option B (fallback): dismiss the card from Recents

- After the sync, `GLOBAL_ACTION_RECENTS`, find Teams' card in the launcher's Recents window, dismiss it (`ACTION_DISMISS` or a swipe by `dispatchGesture`, already permitted in `automation_service_config.xml`), then go home.
- Costs:
  - Recents flashes on screen.
  - It depends on the launcher's layout (One UI), which an update can change.
  - To know whether Teams was already open, Recents has to be read before the sync too.
  - It must run on every exit path (`finally`).
  - It needs fixtures captured from Recents, as the Teams screens have.
- Only worth building if Option A fails the spike.

## Open decision

- What counts as "already open" if Android doesn't leave an existing task alone: the foreground app only (cheap, wrong for a Teams in Recents), or Option B (accurate, brittle). Settled by the spike, not before.
