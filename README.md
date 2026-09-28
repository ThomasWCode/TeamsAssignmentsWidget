# Teams Assignments Widget

An Android home-screen widget that lists your **Microsoft Teams assignments that haven't been handed in**. Each row shows the title, when it's due and the class, and rows are grouped under Overdue, Today, Tomorrow and so on. The **↻** button re-syncs, and tapping a row opens that assignment in Teams.

It's a personal, sideloaded app. It isn't on Google Play, and can't be, because it uses Android's accessibility API to read the Teams app.

## How it works

The official way to read assignments, the Microsoft Graph API, needs a school IT admin to approve a permission. Instead, this app automates the **Teams app on your phone**, the way a screen reader would:

1. You tap **↻** on the widget.
2. The *Teams Assignments sync* accessibility service opens Teams straight on Assignments. It reads the **Forthcoming** and **Past due** tabs (together, everything not handed in), then briefly opens each new or changed assignment for its class, exact due time and instructions.
3. It goes back to the home screen, and the widget shows the list.

A progress pill shows while it works ("Syncing assignments 3/7 · Cancel"). A first sync takes a few seconds per assignment. After that, an assignment is only reopened if its row has changed or its details are more than three days old. Most refreshes are quick, and every few days one rereads everything. **Full resync** in the app rereads everything straight away.

Tapping a row opens Teams and taps that assignment's card for you. Cards are found by the assignment's own ID, so the right one opens even when several share a title.

### Safety and privacy

- It **only presses tabs and assignment cards**. It never presses a button, and never *Hand in*, *Attach* or anything else that changes Teams. Taps go to the centre of a card's title, and are refused if a button, the notification shade or the keyboard covers that spot.
- It only acts when you tap ↻ or a row. Otherwise it ignores everything, and it only receives events from Teams.
- Everything stays on the phone: a small JSON file in the app's private storage, with nothing backed up or sent anywhere.
- If a sync fails or you cancel it, the previous list stays and the widget says why.

## Install

Download `teams-assignments-debug-apk` from the latest [CI run](../../actions), or build it yourself:

```bash
./gradlew installDebug
```

Then open **Teams Assignments** and work through the checklist:

1. **Microsoft Teams installed.** Signed in, with your school account.
2. **Turn on Teams Assignments sync.** *Accessibility settings* → *Installed apps* → *Teams Assignments sync* → On.
   - If the switch is **greyed out**, Android is blocking accessibility for an app installed from a file. Open *App info* → ⋮ → *Allow restricted settings*, then try again. Installing over `adb` avoids this.
3. **Keep it running in the background.** Tap *Allow* for unrestricted battery. On Samsung, also add the app under *Settings* → *Battery* → *Background usage limits* → *Never sleeping apps*, or One UI may switch the service off.
4. **Add the widget.** Tap *Add widget*, or long-press the home screen → *Widgets* → *Teams Assignments*. It's designed for 4×3 and resizes.

Then tap **↻**, and leave the phone alone until it returns to the home screen.

## Troubleshooting

| Symptom | What to do |
|---|---|
| Widget says *Tap to finish setup* | The accessibility service is off (Samsung sometimes turns it off). Open the app and switch it on again. |
| *Last sync failed: Teams was closed* | Something came over Teams mid-sync: another app, the notification shade, a call. Just sync again. |
| *Last sync failed: Couldn't read the … list* or similar | Teams may have changed its layout. See below. |
| Wrong or missing details | Run **Full resync** in the app. |

**When a Teams update breaks syncing**, the app gives you what's needed to fix it:

- A sync that fails by itself saves a capture of the screen it got stuck on. One you cancel, or leave by switching apps, doesn't.
- **Troubleshooting → Dump Teams screen** shows a *Capture* button over Teams. Go to the screen in question, tap it, and share the file.
- **Recent steps** lists what the automation did. The same log is in `adb logcat -s TeamsAutomation`.

Captures use the same format as the test fixtures, so a new capture can go straight into `app/src/test/resources/fixtures/teams`. The ids and texts the automation matches live in [`TeamsSelectors.kt`](app/src/main/java/com/teamsassignments/widget/automation/TeamsSelectors.kt). The one exception is the wording of dates ("Due tomorrow at 08:30", month names and so on), which is matched in [`DueDateParser.kt`](app/src/main/java/com/teamsassignments/widget/data/DueDateParser.kt). [`docs/teams-ui-notes.md`](docs/teams-ui-notes.md) describes the Teams screens as captured.

## Development

| | |
|---|---|
| Build | Gradle 9.3.1, AGP 9.0.1 with built-in Kotlin 2.2.10, JDK 17 |
| SDK | `minSdk` 26, `compileSdk`/`targetSdk` 36 (why the library versions are pinned: see `gradle/libs.versions.toml`) |
| UI | Jetpack Glance 1.2 (widget), Jetpack Compose + Material 3 (setup app) |
| CI | GitHub Actions: unit tests, lint, debug APK artifact |

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Everything that decides what to press or save is plain Kotlin, tested on the JVM against real captures of the Teams screens:

```
app/src/main/java/com/teamsassignments/widget/
  data/        Assignment model, JSON store, due-date parsing, date sections, class colours, sync log
  automation/  TeamsSelectors (every Teams-specific matcher), TeamsScreens (parsers),
               TeamsAutomation + SyncStateMachine + NavigateStateMachine (the workflows),
               TeamsAutomationService and the Android glue (node snapshots, taps, overlay, dumper)
  widget/      The Glance widget, its receiver, and redraw scheduling
  ui/          Setup screen, and the widget's invisible trampoline activities
app/src/test/  Unit tests, a fake phone that serves the captures, and the captures themselves
scripts/       derive_fixtures.py: builds the edge-case fixtures from the captures
```

[`PLAN.md`](PLAN.md) is the original design. [`docs/teams-ui-notes.md`](docs/teams-ui-notes.md) records what the real Teams UI looks like, and where it differed from the plan.

## Known limitations

- **Tied to the Teams app's layout and English (en-GB) wording.** An update to Teams can break syncing until `TeamsSelectors.kt` is updated.
- **Takes over the screen while syncing.** Syncing is manual only, and doesn't run while the phone is locked. The widget still redraws at midnight and as deadlines pass, from saved data, so "Today" and "Overdue" stay right.
- Assignments you've handed in, and anything older than Teams' *Past due* list, aren't shown.
