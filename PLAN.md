# Teams Assignments Widget: Implementation Plan

## Context

The user wants a home-screen widget that lists their Microsoft Teams assignments that aren't turned in. Each row shows the **title, due date and class**. Tapping a row opens that assignment in the Teams app. A **refresh button in the top-right** re-syncs the list, and the list scrolls.

**Data source decision (confirmed with the user):** automate the Teams Android app with an **AccessibilityService**. The official route is the Microsoft Graph API (`GET /education/me/assignments`). It was rejected because its `EduAssignments.ReadBasic` permission needs a tenant admin to consent. The accessibility route needs no IT approval. Its costs are that it takes over the screen during a refresh, it breaks when Teams changes its UI, and it refreshes only when asked.

**Visual reference (from the user's screenshots):**
- A Material You dark card.
- A bold header with a tonal **pill button top-right**, which becomes ↻ refresh.
- Rows grouped by date, like the "3 days ago / Today / Tomorrow" section headers or the calendar's "Tue 29" date column.
- Each item is a rounded card showing the title, then a subtitle like `16:30 at Physics SYM`, with a **coloured stripe per class** on the left.

The project directory `C:\Users\thoma\Documents\TeamsAssignmentsWidget` contains only this plan, so everything else is new.

**Development environment already present:**
- Android Studio 2026.1 (build 261) with bundled JBR.
- JDK 17.
- SDK platforms 35 to 37 and build-tools 37.
- `adb`.
- A cached **Gradle 9.3.1** at `~/.gradle/wrapper/dists/gradle-9.3.1-bin/.../bin/gradle.bat`, so the wrapper can be generated offline.
- Cached AGP 9.0.1 and Kotlin Gradle plugin 2.2.10.

**Test phone (read over adb on 2026-09-28):**
- Samsung Galaxy S24 (`SM-S921B`), serial `R3CX20C33EF`
- Android 16 (SDK 36), screen 1080×2340
- Locale `en-GB`, so expect 24-hour times and day-month dates
- Microsoft Teams `1416/1.0.0.2026163804` (`versionCode 2026163845`)

---

## Tech stack

| Concern | Choice |
|---|---|
| Language / build | Kotlin, Gradle Kotlin DSL + `libs.versions.toml`, AGP 9.0.1 (built-in Kotlin) + Kotlin 2.2.10, Gradle 9.3.1 |
| SDK levels | `minSdk 26`, `compileSdk`/`targetSdk 36` |
| Widget | **Jetpack Glance** `androidx.glance:glance-appwidget:1.2.0` + `glance-material3:1.2.0` (latest stable, Aug 2026) |
| Setup app UI | Jetpack Compose + Material 3 |
| Scraper | `android.accessibilityservice.AccessibilityService`, coroutines |
| Storage | JSON file in `filesDir` via `kotlinx.serialization`, exposed as a `StateFlow` |
| Package | `com.teamsassignments.widget` |

Distribution is **sideload only**. Google Play's policy forbids using the Accessibility API for automation like this.

---

## Architecture

```
[Widget ↻ button] ──PendingIntent──► RefreshActivity (transparent trampoline)
                                        │ service enabled?  no → MainActivity (setup)
                                        ▼ yes
                              TeamsAutomationService.startSync()
                                        │ 1. launch Teams → Assignments (deep link)
                                        │ 2. read list (scroll until no new rows)
                                        │ 3. for each row: tap → read detail → Back
                                        │ 4. save → AssignmentStore
                                        │ 5. AssignmentsWidget.updateAll()
                                        ▼ 6. GLOBAL_ACTION_HOME (user sees updated widget)

[Widget row tap] ──► OpenAssignmentActivity ──► service.navigateTo(title, class)
                                               → open Assignments, find row, tap it
```

Why tapping a row can't use a direct link: a Graph `webUrl` contains the class ID and assignment ID, and the Teams UI never shows those IDs. So a row tap opens Teams Assignments and the service **navigates to the assignment by matching its title and class**. If the service is off or the row isn't found within 15 s, the user is left on the Assignments list and a toast explains why.

Starting activities from the background: widget clicks are an allowed launch source on Android 14+. An app with a system-bound AccessibilityService is also exempt from the background-activity-launch restrictions.

---

## Project layout (files to create)

```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradlew(.bat) + wrapper
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/res/xml/assignments_widget_info.xml      # appwidget-provider: 4x3 target, resizable, updatePeriodMillis=0
app/src/main/res/xml/automation_service_config.xml    # accessibility-service config
app/src/main/java/com/teamsassignments/widget/
  data/Assignment.kt            # @Serializable model + WidgetState/SyncStatus
  data/AssignmentStore.kt       # JSON file, StateFlow, atomic write (tmp + rename)
  data/DueDateParser.kt         # Teams due text → Instant + grouping bucket
  data/ClassColors.kt           # stable class name → colour from an 8-colour palette
  automation/TeamsAutomationService.kt   # AccessibilityService entry point, overlay, mode dispatch
  automation/SyncStateMachine.kt         # scrape workflow (testable, no Android types)
  automation/NavigateStateMachine.kt     # tap-to-open workflow
  automation/UiNode.kt                   # interface over AccessibilityNodeInfo (+ fake for tests)
  automation/TeamsSelectors.kt           # every text/ID matcher for Teams, in ONE file
  automation/TeamsLauncher.kt            # deep-link intents + launcher fallback
  automation/ScreenDumper.kt             # debug: dump the node tree to a file
  widget/AssignmentsWidget.kt            # GlanceAppWidget UI
  widget/AssignmentsWidgetReceiver.kt
  ui/MainActivity.kt                     # setup checklist + debug tools (Compose)
  ui/RefreshActivity.kt                  # trampoline for ↻
  ui/OpenAssignmentActivity.kt           # trampoline for a row tap
app/src/test/...                         # JVM unit tests + XML fixtures from Phase 0
docs/teams-ui-notes.md                   # what Phase 0 found on the real device
```

---

## Phase 0: Discover the real Teams UI (gate before writing selectors)

The selectors can't be written blind, because they depend on what Teams shows on this particular phone.

1. Enable USB debugging, connect the phone, and confirm with `adb devices`.
2. Test the deep link:
   ```
   adb shell am start -a android.intent.action.VIEW -d "https://teams.microsoft.com/l/entity/66aeee93-507d-479a-a3ef-8f494af43945/classroom" com.microsoft.teams
   ```
   `66aeee93-…` is the Assignments app ID; it appears in Graph `webUrl`s. If the link doesn't open Assignments, record the tap path instead (e.g. bottom bar → *More* → *Assignments*).
3. Capture three screens with `adb shell uiautomator dump` then `adb pull`: the Assignments list, a scrolled list, and an assignment detail. Note:
   - Whether the content is a WebView (text-only nodes, no resource IDs).
   - Tab names ("Assigned", "Upcoming", etc.) and how "Turned in" or overdue items are marked.
   - The due-date text formats (expect en-GB, e.g. `Due 3 Oct, 23:59`).
   - Which marker identifies the detail screen (e.g. "Instructions" or a "Turn in" button).
4. Save the XML files as test fixtures and write up the findings in `docs/teams-ui-notes.md`.

If `uiautomator dump` misses WebView content, use the app's own **Dump screen** tool (Phase 2). It reads the same tree the service sees.

---

## Phase 1: Skeleton and data layer

- Generate the wrapper from the cached Gradle 9.3.1 with `gradle.bat wrapper`, then scaffold the modules and the version catalog.
- **`Assignment`** fields:
  - `key` (hash of class + title)
  - `title`
  - `className`
  - `description` (plain text, capped at about 2 000 characters)
  - `dueText` (raw, for display fallback)
  - `dueAt: Long?`
  - `lastSyncedAt`
- **`WidgetState`** holds the assignments, the last successful sync time, and a `SyncStatus`, which is one of `Idle`, `Running(done, total)` or `Failed(reason)`.
- **`AssignmentStore`** reads and writes the JSON file. On a failed sync it **keeps the previous list** and only updates the status.
- **`DueDateParser`** turns the Phase 0 formats into an `Instant`. It takes an injected `Clock` and returns a bucket: `Overdue`, `Today`, `Tomorrow`, a weekday name for the next 7 days, a date like `Fri 10 Oct` beyond that, or `No due date`.
- **`ClassColors`** maps each class name to a stable colour, so a class keeps the same stripe colour across syncs.

## Phase 2: The accessibility service (the core)

**Config** (`automation_service_config.xml`):
- `packageNames="com.microsoft.teams"`
- `canRetrieveWindowContent="true"`
- `canPerformGestures="true"`
- flags `flagReportViewIds | flagRetrieveInteractiveWindows | flagIncludeNotImportantViews`
- event types `windowStateChanged | windowContentChanged | viewScrolled`

The service is declared with `BIND_ACCESSIBILITY_SERVICE`. It ignores all events while idle.

**Waiting primitive:** `suspend fun awaitScreen(match: (UiNode) -> Boolean, timeout = 10.s)` resumes on accessibility events and also polls the active window every 250 ms. Every step has a timeout, and the whole sync has a 3-minute cap.

**`SyncStateMachine` steps:**
1. **Launch.** `TeamsLauncher` fires the deep link with `setPackage("com.microsoft.teams")`, falling back to the launcher intent plus UI navigation. Wait until the Assignments list marker appears, then make sure the **Assigned / not-turned-in** tab is selected.
2. **Collect the list.** Read the visible rows (title, class, due text). Then run `ACTION_SCROLL_FORWARD` on the scroll container until no new rows appear, de-duplicating by key and skipping anything marked "Turned in". Then scroll back to the top.
3. **Read each row.** Find the row by title, scrolling forward if it's off-screen, and click its nearest clickable ancestor. Wait for the detail marker, then read the title, due date and instructions, scrolling the detail view if the instructions are long. Then `GLOBAL_ACTION_BACK` and wait for the list again. Retry a row once on timeout; after that, keep its list-level data with an empty description.
4. **Finish.** Save the results, call `AssignmentsWidget().updateAll(context)`, then `GLOBAL_ACTION_HOME`.

**Abort conditions:**
- The foreground package stays something other than Teams for more than 2 s (the user switched away).
- The user taps Cancel.
- The global timeout is hit.

On abort the status becomes `Failed(reason)` and the old data stays.

**Progress overlay:** a small `TYPE_ACCESSIBILITY_OVERLAY` banner at the top ("Syncing assignments 3/7 · Cancel"). Accessibility services can draw it without the overlay permission. All actions go through node `performAction` rather than injected touches, so the banner doesn't block them.

**`NavigateStateMachine`** (row tap): launch Assignments, find the row by title and class (scrolling if needed), and click it.

**`TeamsSelectors.kt`** holds every text or ID matcher, taken from the Phase 0 notes. When Teams changes its UI, this should be the only file that needs editing.

**`ScreenDumper`** writes the current Teams node tree (class, text, content description, view ID, bounds, clickable/scrollable) to `filesDir/dumps/…txt` and opens a share sheet. This is the maintenance tool for Teams updates.

## Phase 3: The widget (Glance)

The layout follows the screenshots:
```
┌─────────────────────────────────────────┐
│ Assignments                     ( ↻ )   │  bold header + tonal pill button (top-right)
│ Updated 14:32 · 5 due                   │  small muted subtitle; "Syncing 3/7…" while running
│ OVERDUE                                 │  section header (error colour)
│ ▌Pg 60&61                               │  ▌ = class-colour stripe, rounded card
│ ▌Yesterday 23:59 · Maths 9X             │
│ TOMORROW                                │
│ ▌Miss sym stretch physics               │  title (bold, max 2 lines)
│ ▌08:49 · Physics SYM                    │  due time · class
│ ▌Read pages 12–14 and answer…           │  description preview (1 muted line, only if non-empty)
└─────────────────────────────────────────┘
```

Implementation:
- `GlanceTheme` with dynamic colours, so it matches Material You like the reference widget.
- A `LazyColumn` of sections and rows, using `items(…, itemId = { key.hashCode().toLong() })` so rows keep stable IDs.
- `cornerRadius` on the cards (Android 12+).
- The ↻ pill uses `actionStartActivity<RefreshActivity>()`. While a sync is running it shows as disabled.
- Each row uses `actionStartActivity(Intent(OpenAssignmentActivity).putExtra(key))`.

Widget states:
- **Service not enabled:** "Tap to finish setup", which opens `MainActivity`.
- **Empty:** "Nothing due 🎉, tap ↻ to sync".
- **Failed:** the old list stays, and the subtitle reads "Last sync failed: …".

`assignments_widget_info.xml`:
- `minWidth 250dp`, `minHeight 180dp`, target 4×3 cells
- `resizeMode horizontal|vertical`
- `updatePeriodMillis 0` (refresh is manual only)
- A preview layout for the widget picker

## Phase 4: Setup app (`MainActivity`)

A checklist with live status:
1. **Teams installed**, with a link to Play if it's missing.
2. **Accessibility service enabled**, with a button that opens `Settings.ACTION_ACCESSIBILITY_SETTINGS`. The screen explains Android 13+ *restricted settings*: if the APK was sideloaded as a file, the user goes to App info → ⋮ → *Allow restricted settings* first. An `adb install` usually doesn't trigger this.
3. **Add the widget to the home screen** via `AppWidgetManager.requestPinAppWidget`.
4. **Sync now**.

Debug section:
- The last sync log (the last 50 steps, each with a timestamp).
- **Dump Teams screen**, which arms `ScreenDumper` for the next time Teams is in the foreground.

---

## Verification

1. **Unit tests** (JVM):
   ```
   .\gradlew.bat testDebugUnitTest
   ```
   They cover:
   - `DueDateParser` against every Phase 0 format, with a fixed clock.
   - Grouping and sorting.
   - `ClassColors` stability.
   - `SyncStateMachine` and `NavigateStateMachine` driven by a `FakeUiNode` tree built from the Phase 0 XML fixtures: list collection with scrolling, skipping turned-in items, detail extraction, the timeout/retry path, and the abort path.
2. **Build and install:** `.\gradlew.bat installDebug` with the phone connected. Watch the logs with `adb logcat -s TeamsAutomation`.
3. **Manual checks on the device:**
   - Enable the service and add the widget.
   - Tap ↻. Teams opens, the banner counts up, it goes back to the home screen, and the widget lists every not-turned-in assignment with the correct title, class, due date and description. Compare against Teams by hand.
   - Scroll a list longer than the widget.
   - Tap a row. Teams opens **that** assignment.
   - Tap Cancel mid-sync, and switch apps mid-sync. The old data stays and the widget shows the failure reason.
   - Turn the service off. The widget shows "finish setup".
   - Resize the widget, and check both light and dark themes.

---

## Known limitations (accepted with this approach)

- **Fragile to Teams updates.** Mitigated by keeping every selector in `TeamsSelectors.kt` and by the Dump screen tool.
- **Takes over the screen** for about 2 to 4 s per assignment. Refresh is manual only, and doesn't run while the phone is locked.
- **English (en-GB) Teams UI** assumed for the text matchers.
- Samsung's battery manager (the test phone is a Galaxy S24) can kill accessibility services. The setup screen links to the battery-optimisation exemption, and the app should be added to *Never sleeping apps*.
- If IT ever approves Graph access, a Graph-backed source could replace the scraper behind the same `AssignmentStore` API. That's out of scope for now.
