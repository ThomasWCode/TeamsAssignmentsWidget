# Teams Assignments Widget

An Android home-screen widget that lists your **Microsoft Teams assignments that haven't been handed in**. Each row shows the title, when it's due and the class, and rows are grouped under Overdue, Today, Tomorrow and so on. The **↻** button re-syncs, tapping a row opens that assignment in Teams, and each row's **Hand in** button hands it in, after asking. Browsing Assignments in Teams yourself keeps the list up to date too.

It's a personal, sideloaded app. It isn't on Google Play, and can't be, because it uses Android's accessibility API to read the Teams app.

## How it works

The official way to read assignments, the Microsoft Graph API, needs a school IT admin to approve a permission. Instead, this app automates the **Teams app on your phone**, the way a screen reader would:

1. You tap **↻** on the widget.
2. The *Teams Assignments sync* accessibility service opens Teams straight on Assignments. It reads the **Forthcoming** and **Past due** tabs (together, everything not handed in), then briefly opens each new or changed assignment for its class, exact due time and instructions.
3. It goes back to the home screen, and the widget shows the list.

A progress pill shows while it works ("Syncing assignments 3/7 · Cancel"). A first sync takes a few seconds per assignment. After that, an assignment is only reopened if its row has changed or its details are more than three days old. Most refreshes are quick, and every few days one rereads everything. **Full resync** in the app rereads everything straight away.

Tapping a row opens Teams and taps that assignment's card for you. Cards are found by the assignment's own ID, so the right one opens even when several share a title.

### Handing in

Each row has a **Hand in** button. It asks first ("Hand in late?" once the work is overdue). Then the service opens the assignment the way a row tap does, checks that Teams still shows it as not handed in, and presses Teams' own **Hand in** (or **Hand in late**) button once. When Teams shows it as handed in, the row goes and the phone returns to the home screen.

- It hands in whatever work is already attached in Teams. It can't attach anything.
- If Teams doesn't show it as handed in within 20 seconds, Teams stays open on the assignment so you can check, and a capture of the screen is saved.
- An assignment the app has only seen on its own screen (see below) needs a sync first, so it can be found by its ID.
- If it's on neither Forthcoming nor Past due, with both lists read in full (as below), nothing is pressed and it's taken as handed in, as a sync would. Tapping a row does the same.

### While you use Teams

When you open Assignments in Teams yourself, the app reads what's on screen, without pressing anything or showing anything:

- new assignments on the Forthcoming or Past due list are added, without their instructions until you open one or sync;
- changed titles and due times are updated;
- opening an assignment saves its instructions, and adds it if the list hadn't shown it yet (say you came from a Teams notification);
- anything Teams shows as done is removed: everything on the Completed list, and an assignment whose own screen says it's handed in;
- an assignment on **neither** Forthcoming nor Past due is taken as handed in, once you've seen both lists in full, within ten minutes of each other.

A list only counts once it has fully loaded, by the same tests a sync uses: the tab is selected, nothing is loading, the cards have stayed the same for 0.6 s (an empty list for 2 s, or 6 s if that tab had work at the last sync), and a tab you've just switched to isn't still showing the last tab's cards. *In full* means every card. Teams currently puts the whole list where the app can read it, cards off screen included, so one look is enough; if an update ever stopped that, you'd have to scroll from one end of the list to the other, pausing as you go, for it to count.

Work falling due around the time you looked is left alone, since it may simply have moved from one list to the other. And anything taken as handed in this way comes back as soon as a list shows it again.

### Safety and privacy

- **Syncing and opening only press tabs and assignment cards.** They never press a button, and never *Hand in*, *Attach* or anything else that changes Teams. Taps go to the centre of a card's title, and are refused if a button, the notification shade or the keyboard covers that spot.
- **Hand in is the one exception, and only when you ask.** Once you confirm on the widget, it presses the *Hand in* button in Teams' toolbar for that assignment, found by its ID, and nothing else: never *Undo hand in*, *Attach* or anything that changes your work.
- It only acts when you tap ↻, a row or *Hand in*. Otherwise it only reads, and only while Teams shows Assignments. It only receives events from Teams.
- Everything stays on the phone: a small JSON file in the app's private storage, with nothing backed up or sent anywhere.
- If a sync fails or you cancel it, the previous list stays and the widget says what happened.

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
| *Sync stopped* | Teams went out of view mid-sync: you went Home or to another app, pulled down the notification shade, or a call came in. The list is from the time shown. Just sync again. |
| *Last sync failed: Couldn't read the … list* or similar | Teams may have changed its layout. See below. |
| Wrong or missing details | Run **Full resync** in the app. |
| *Hand in pressed, but Teams didn't confirm it* | Check the assignment in Teams: it may have been handed in anyway. If it was, a sync or a look at Completed takes it off the list. The saved capture shows what Teams did instead. |
| *Nothing handed in: Teams showed no Hand in button …* | The assignment's screen had no Hand in button that could be pressed, for example because it's closed. Teams is left open on it. |
| *Nothing pressed: … is on neither Forthcoming nor Past due, so it's taken as handed in* (or *Taken as handed in: …* after a row tap) | It was most likely handed in on another device, or the teacher removed it. If it shouldn't have gone, **↻** brings it back. |
| *Nothing handed in: couldn't find … on Teams' Forthcoming or Past due list* | A list couldn't be read in full, perhaps still loading. Nothing was pressed or removed; try again, or tap **↻**. |

**When a Teams update breaks syncing**, the app gives you what's needed to fix it:

- A sync that fails by itself saves a capture of the screen it got stuck on, as does a hand-in Teams doesn't confirm. One you cancel, or leave by switching apps, doesn't.
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
               TeamsAutomation + SyncStateMachine + NavigateStateMachine + HandInStateMachine
               (the workflows), TeamsObserver (reading while you use Teams),
               TeamsAutomationService and the Android glue (node snapshots, taps, overlay, dumper)
  widget/      The Glance widget, its receiver, and redraw scheduling
  ui/          Setup screen, the widget's invisible trampoline activities, and its hand-in confirmation
app/src/test/  Unit tests, a fake phone that serves the captures, and the captures themselves
scripts/       derive_fixtures.py: builds the edge-case fixtures from the captures
```

[`PLAN.md`](PLAN.md) is the original design. [`docs/teams-ui-notes.md`](docs/teams-ui-notes.md) records what the real Teams UI looks like, and where it differed from the plan.

**Tested on** a Samsung Galaxy S24 (Android 16, One UI). Checked there:
- full and routine syncs, including ↻ from the widget and a row tap opening the right assignment;
- a sync stopped by Cancel, by going Home, and by pulling down the notification shade;
- the service switched off and back on;
- light and dark themes;
- resizing down to the launcher's smallest size, 3×2;
- handing in from the widget (a late one), and reading along while Teams is open: instructions saved on opening an assignment, and an assignment handed in within Teams taken off the list.

## Known limitations

- **Tied to the Teams app's layout and English (en-GB) wording.** An update to Teams can break syncing until `TeamsSelectors.kt` is updated.
- **Takes over the screen while syncing** and while handing in. Syncing is manual only, and doesn't run while the phone is locked; browsing Assignments in Teams updates the list without taking over. The widget still redraws at midnight and as deadlines pass, from saved data, so "Today" and "Overdue" stay right.
- Assignments you've handed in, and anything older than Teams' *Past due* list, aren't shown.
