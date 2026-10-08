# Teams UI notes (Phase 0)

Captured on 2026-09-28 from the test phone:

- Samsung Galaxy S24 (`SM-S921B`), Android 16 (SDK 36), locale `en-GB`.
- Microsoft Teams `1416/1.0.0.2026163804` (`versionCode 2026163845`).
- Tool: `adb shell uiautomator dump`. It reads the same `AccessibilityNodeInfo` tree the service sees.

The raw dumps are the test fixtures in [`app/src/test/resources/fixtures/teams`](../app/src/test/resources/fixtures/teams).
When Teams changes, re-capture with the app's **Dump Teams screen** tool and compare against these notes.
Every matcher derived from these notes lives in `automation/TeamsSelectors.kt`.

## Getting to Assignments

The deep link from the plan works. It opens the Assignments list directly, even on a cold start:

```
adb shell am start -a android.intent.action.VIEW \
  -d "https://teams.microsoft.com/l/entity/66aeee93-507d-479a-a3ef-8f494af43945/classroom" com.microsoft.teams
```

| Screen | Activity |
|---|---|
| Launch | `com.microsoft.skype.teams.views.activities.SplashActivity` (brief) |
| Assignments list | `com.microsoft.skype.teams.views.activities.CustomTabsShellActivity` |
| Assignment detail | `com.microsoft.skype.teams.views.activities.TeamsJsHostActivity` (a new activity on top) |

Back from the detail returns to the list with the same tab still selected.

## The content is a WebView, and it exposes everything

The native shell is a toolbar plus a `WebView`. The WebView's accessibility tree is complete and useful:

- **HTML `id`s become view IDs.** WebView nodes report the raw id (`tab-Forthcoming`). Native views report the usual `com.microsoft.teams:id/<name>`. Matchers compare the part after `:id/`.
- **The whole list is in the tree, including off-screen rows.** Off-screen nodes have bounds clamped to the viewport edge with zero height (for example `[45,2298][1023,2298]`). All 7 Forthcoming cards and all 30 Completed cards were present without scrolling. The sync still scrolls defensively in case a longer list is virtualised.
  - Rows scrolled past stay in the tree too, clamped to the **top** edge (`[45,261][1023,261]` in `list_forthcoming_scrolled`). The `ListView` holding the cards is clamped the same way, so its bounds show which ends of the list are on screen: its top below the viewport's top means the list's start is in view, its bottom above the viewport's bottom means its end is. Past due's closing line sits inside the `ListView`, clamped at the bottom when off screen.
  - So `TeamsScreens.wholeListInTree` counts a list as whole when, at each end where it runs off screen, something of it is clamped to that edge. A virtualised list, holding only the rows in view, would have nothing there (derived fixtures `list_forthcoming_virtualised…`).
  - **But a long list comes in pages** (found 8 Oct). Below the last card Teams puts a "load more" placeholder, a `ProgressBar` with the id `SHIMMER_GROUP`, clamped with zero height like any row out of view. Completed always has one. Past due had one once it held seven cards and ran off the screen (`list_past_due_load_more`); on 1 Oct, seven Forthcoming cards that also ran off it had none. Teams only fetches more once the placeholder comes into view, then adds the cards or swaps it for the list's end: on Past due, *To view older assignments, navigate to an individual class team.* (`list_past_due_load_more_end`). So a list still holding the placeholder isn't whole (`TeamsScreens.loadMorePending`), and the placeholder isn't "loading" either: counting it as a spinner, as 0.3.0 did, made every sync and row tap wait for it until they timed out. See *Paged lists* below.
- **Each card's id is the assignment's GUID** (for example `36274911-c6dd-490d-956d-0273df409847`, the Graph assignment id). This gives a stable key and lets a row tap find its exact card. It replaces the plan's hash of class + title.
- **`fui-CardHeader__headerEDUASSIGN-r6` style ids are not stable.** The `r6` suffix changes on every render. Only the `fui-CardHeader__header` prefix can be matched.

## List screen

Native toolbar: `overflow_menu_button` (content-desc `Back`), `action_bar_title_text` = `Assignments`, a `Filter` button and `js_overflow` (`More options`).

### Tabs

The UI does **not** have the "Assigned" tab the plan assumed. There are three tabs, each a clickable WebView node:

| id | text | Meaning |
|---|---|---|
| `tab-Forthcoming` | `Forthcoming` | Not handed in, not yet due |
| `tab-Past-due` | `Past due` | Not handed in, overdue. Content-desc becomes `You have past due assignments` when there are any (the red dot) |
| `tab-Completed` | `Completed` | Handed in. Cards say `Submitted at HH:MM` |

The selected tab has `selected=true`. **"Not turned in" = Forthcoming + Past due**, so a sync reads both tabs.

A dismissible banner `You have past due assignments / View assignments / Close` can sit above the Forthcoming list.

### Structure

```
ListView
  View                                   ← one per date group
    TextView "28 Sept"                   ← date header, en-GB abbreviations ("Sept", "Oct")
    TextView "Today"                     ← relative label
    View #<assignment GUID>  [focusable] ← one per card; not marked clickable
      TextView (avatar initials, empty text) | View > Image cd=<title>
      TextView #fui-CardHeader__header…  "Hausaufgabe Jugendkultur Vokabeln"
      TextView "Due at 08:00"
      TextView "•"                       ← only when a tag follows
      View > TextView "Challenge"        ← optional tag chip
      TextView "German Y12 2026/27 LKP"  ← class
      View > TextView #AssignmentCard-HoverAction-<GUID>
```

Relative labels seen:

| Tab | Labels |
|---|---|
| Forthcoming | `Today`, `Tomorrow`, weekday names (`Wednesday`, `Thursday`) |
| Past due | `Due 2 days ago`, `Due 7 days ago`, `Due 11 days ago` |
| Completed | `Today`, `Yesterday`, weekday names (`Friday`) |

The Past due list ends with `To view older assignments, navigate to an individual class team.`

The Completed list ends with a zero-height `ProgressBar` (`SHIMMER_GROUP`) below its last card, a placeholder for loading more. It is in the tree whenever the tab is open, so reading along only counts loading indicators that are on screen.

### Collapsed cards

After you return from a detail screen, **the card you just visited collapses into a single leaf**. Its children disappear and the card node's own text becomes their concatenation:

```
View #4c958b24-… t='Particle Physics Test Due at 08:30 12.2-PH3'
```

Only the most recently visited (focused) card collapses. The parser handles both shapes. With a tag chip the collapsed text is ambiguous (`… Due at 23:59 • Challenge Physics Skills …`), so the class name from the detail toolbar is preferred.

## Detail screen

```
Toolbar (native)
  ImageButton #overflow_menu_button cd='Back'
  TextView #action_bar_title_text       ← CLASS NAME
  TextView #action_bar_sub_title_text   ← "Assignments"
  Button "HAND IN" / "HAND IN LATE"     ← only the hand-in workflow presses it; "UNDO HAND-IN" once handed in,
                                          "HAND IN AGAIN" once that is undone
WebView
  View #assignmentViewerVisibilityContainer   ← detail-screen marker
    TextView "Not handed in"                  ← status
    View
      TextView <title>
      TextView "Due tomorrow at 08:30"
      TextView "•"
      TextView "Multiple submissions allowed"
      Button "Show details"                   ← sometimes; not needed
      TextView "Instructions"
      … instruction nodes …
      TextView "Reference materials"          ← optional, ends the instructions
      Button #<GUID>-card cd='Reference materials Prep 1.pdf'
      TextView "My work"                      ← ends the instructions
      Button #studentAddResourcesButton cd='Open Attach menu'
      Button cd='Open New menu'
      TextView "Points" / "No points"
  Button "Immersive Reader"
```

### Due text formats

| Where | Examples |
|---|---|
| List card + date header | `Due at 08:00` under `28 Sept` / `1 Oct` (no year) |
| Detail, today or tomorrow | `Due today at 08:00`, `Due tomorrow at 08:30` |
| Detail, otherwise | `Due 30 September 2026 08:30`, `Due 25 September 2026 23:59` |

All times are 24-hour. The list gives day, month and time. The detail adds the year. The parser also accepts `Due yesterday at …` and the `Sep`/`Sept` spellings.

### Instructions markup

Instructions are the nodes between `Instructions` and the first of `Reference materials` or `My work`. Observed shapes:

- Plain paragraphs, sometimes ending in a non-breaking space (` `).
- Numbered lists whose markers are separate nodes: `View t='1)'` or `View t='1.'`, then the item text.
- Bullets as separate `View t='• '` nodes.
- Explicit line breaks as nodes whose text is just `\n`.
- Inline spans (bold and so on) split one sentence across several `TextView`s, with their own spaces.
- Links appear twice: `View [clickable] cd=<url>` and a child `TextView t=<url>`.

## Safety

Syncing and opening an assignment only ever press **tab nodes** (`tab-*`) and **assignment cards** (a GUID id), and never anything whose class is a `Button`.
As a second guard, they refuse a tab whose label matches hand-in, turn-in, submit, attach, delete or similar.
The `HAND IN` / `HAND IN LATE` / `HAND IN AGAIN` toolbar button and the `Open Attach menu` / `Open New menu` buttons are the dangerous controls on the detail screen.

The one exception is the **hand-in workflow**, which runs only after the user confirms on the widget. It opens the assignment by its GUID (never by title), checks that the screen's title is exactly the assignment's (as the list showed it, or as saved: opening a card allows a prefix, for a collapsed card's cut-short title, but handing in doesn't) and that the status isn't handed in, and presses the `Button` in the native `toolbar` whose text is exactly `HAND IN`, `HAND IN LATE` or `HAND IN AGAIN`, and enabled. It uses the button's click action: it's a native view, which takes click actions the way TalkBack presses it, unlike the WebView content. It presses once, and a second time only if Teams reports that the first click didn't go through. It never presses `UNDO HAND-IN` or anything else, and never taps the toolbar.

Gesture taps (see below) have extra rules:

- A tap lands in the centre of the card's title (or the tab), below the tab bar.
- It is refused if that point is inside any button or dangerous control outside the target.
- It is also refused while any window above Teams covers the point, such as the notification shade, a heads-up notification or the keyboard.
- The progress pill sits over Teams' toolbar, so the toolbar area (where Hand in lives) is never reachable by a stray tap.

## Behaviour on the phone (first live syncs)

Findings from running the service itself, after Phase 0:

- **Teams ignores accessibility click actions.** `performAction(ACTION_CLICK)` on a tab or card returns `true` and does nothing, every time. An injected gesture tap (`dispatchGesture`) at the node's centre works every time. The workflows therefore tap first, with the click action only as a fallback, and verify every press (tab selected? detail screen open?).
- `ACTION_SHOW_ON_SCREEN` does scroll an off-screen card into view, so it can then be tapped.
- **Screens slide in, and the tree reports them mid-slide.** Just after Back from an assignment, a failure dump caught the whole list window at `[-337,0][743,2340]` instead of `[0,0][1080,2340]`. A tap taken from that snapshot pointed at x = -126, which `GestureDescription` rejects outright. Taps therefore wait for their target to be at rest: the same bounds twice in a row, in a window that isn't offset (`TeamsScreens.windowAtRest`). Captured as `list_past_due_mid_transition`.
- **"Due earlier today"** is Past due's label for work that passed its time earlier the same day (an 08:00 homework, seen at 08:56). Captured as `list_past_due_earlier_today`.
- **Work that fell due earlier today is listed on both tabs.** Syncs at 11:16 and 13:20 found 8 + 5 and 9 + 5 open cards but only 11 and 12 different ones. The two on both tabs were the day's 08:00 and 09:00 homework, still under Forthcoming's "Today" while Past due listed them as "Due earlier today". The sync keys cards by GUID, so each counts once, under Past due. First inferred from the counts, then captured on 1 Oct as `list_forthcoming_earlier_today`: at 12:15 an 08:30 homework sat under Forthcoming's `1 Oct` / `Today` while Past due listed it as `Due earlier today`.
- **While the Assignments module loads, the WebView is empty.** A capture just after launch showed Teams' toolbar over a WebView with no children: no tabs, no cards and no spinner. It isn't mistaken for an empty list, because a list needs its tabs. Captured as `list_assignments_loading`.
- **Forthcoming's "Next week" divider isn't in the tree.** Teams draws it between this week's date groups and later ones, but the accessibility tree holds only the usual groups (`5 Oct` / `Monday`).
- **`uiautomator dump` switches accessibility services off while it runs.** The service logged "Service disconnected", then reconnected about a second later. A dump taken during a sync would end it, so use the app's **Dump Teams screen** mid-run, and adb dumps only while nothing is syncing.
- A full read of all 10 assignments matched Teams exactly: titles, classes, due times and instructions. Later syncs matched Teams' lists too.

Not yet seen, and worth capturing with **Dump Teams screen** when they turn up:

- An **empty** Forthcoming or Past due tab. For now an empty list is believed only after holding for 2 s (6 s if that tab had work at the last sync) with no loading indicator.
- What Teams shows **while a tab loads** after a switch. (The whole module loading at launch is captured, above.) A spinner surfaces as a `ProgressBar` node, and an exact "Loading" label is also treated as loading.
- A detail screen opened **from a system notification**. One opened from Teams' Activity feed has been captured (see [Found on the way](#found-on-the-way)); a notification very likely opens the same screen, but that hasn't been seen.

Some test fixtures are **derived** from the captures rather than captured: `list_past_due_with_moved_cards`, `list_past_due_stale_rows`, `list_completed_stale_rows`, `list_past_due_completed_rows`, `list_past_due_empty`, `list_past_due_loading`, `list_forthcoming_single`, `list_past_due_single_moved`, `list_forthcoming_virtualised` (with `_scrolled` and `_end`), `detail_unreadable`, `detail_4c958b24_handed_in`, `detail_88fafeb2_handed_in`, `detail_4c958b24_hand_in_disabled` and `detail_4c958b24_prefix_title`. Each builds a state that's hard to catch live (a card on both tabs, a tab selected before its rows load or still showing another tab's, an empty or loading list, a single card moving tabs, an unreadable detail screen, a same-named assignment's screen whose title only starts the chosen one's), seen only once (a handed-in detail screen, carried over from the captured `detail_f63a23c9_handed_in` to other assignments), or not seen at all (a greyed-out Hand in button, a list holding only the rows in view), by editing a real capture. [`scripts/derive_fixtures.py`](../scripts/derive_fixtures.py) regenerates them after fresh captures.

## Handing in and reading along (0.2.0 on the phone)

- **The detail screen once handed in**, captured as `detail_f63a23c9_handed_in` just after "Dr. Frost - Forces - Week 3" was handed in within Teams:
  - the status reads `Handed in late Wed 30 Sept 2026 at 10:54`;
  - the toolbar button reads `UNDO HAND-IN`, **hyphenated**, where the Hand in button reads `HAND IN`. The first matcher, written before this capture, missed the hyphen; the status line had confirmed hand-ins regardless;
  - the `Open Attach menu` and `Open New menu` buttons are disabled;
  - Teams also puts the status in a `screenReaderAnnouncement` node, outside the detail container.
- **Handing in from the widget** ("Prep 18/09/2026 - Chapter 12 review", past due): `HAND IN LATE` took the click action, and the status read handed in within 2 s, with no prompt or dialog in between.
- **Reading along** saved the Dr. Frost assignment's details when it was opened, and took it off the list as soon as Teams showed it handed in.
- A hand-in for work handed in before 0.2.0 was installed (so the widget still listed it) found the card on neither open tab and pressed nothing. A sync then dropped it.

## Deferred live tests

These were the checks left over from the 0.2.0 run. They ran on the phone on 2026-10-01, on a build of `main` (`edea024`). For the checks that need work handed in elsewhere, "Text - LESEN" was handed in and undone on a PC, three times.

- [x] **Taken as handed in while reading along.** With only Past due open, nothing went. Forthcoming, opened 10 min 50 s later, took nothing either. Past due again, 9 s after that, took the row, and the log read `Seen in Teams: "Text - LESEN" is on neither Forthcoming nor Past due: taken as handed in`. Nothing was remembered as handed in.
- [x] **Nothing taken on too little.** Both halves, as above: one tab on its own, and the two tabs more than 10 minutes apart.
- [x] **Brought back.** With the hand-in undone on the PC and Teams refreshed, Past due logged `Seen in Teams: added "Text - LESEN"`. It comes back without its instructions, until it's opened or synced.
- [x] **Taken as handed in by a row tap or a hand-in.** Each read both tabs, found the card on neither and pressed nothing. The log read `"Text - LESEN" taken as handed in` after the row tap, and `Hand-in result: NotListed` after the hand-in. Neither toast showed (see below).
- [x] **Cancel.** Cancel was tapped 0.7 s after confirming, while Teams was still opening: `Hand-in stopped: Cancelled`, nothing pressed, and Teams still listed the work as not handed in. The toast didn't show. A Cancel timed to the very moment of the press hasn't been tried; nobody can tap that reliably.
- [x] **Hand in on time.** `HAND IN` took the click action, and Teams showed the work handed in 0.8 s later ("Hausaufgabe: 5 facts "Familie"", undone in Teams straight after). The status read `Handed in Thu 1 Oct 2026 at 12:33`, captured as `detail_3a5b3795_handed_in`.
- [x] **Instructions saved after a row tap.** `Seen in Teams: read "…"` came 1.4 s after the row tap opened the assignment, with no touch.
- [x] **Looks resume after other screens.** Chat, then Assignments 3 s later, then an assignment: its details were read. Teams' Chat list stood in for a chat, to leave the unread marks alone.
- [ ] **Work from a notification.** Not run as written, as no new assignment arrived. An assignment opened from Teams' Activity feed showed that reading along didn't look at that screen at all (see below; it does since 0.2.1). The Hand in button's answer for work without a Teams id did show: *Sync with ↻ first, so Teams can find this assignment*.
- [x] **The confirmation dialog** reads well in light and dark themes.
- [ ] **The Hand in pill as an icon** can't be reached on this phone. On its 4-column grid the widget's narrowest size is 294 dp, above the 250 dp where the icon takes over.

### Found on the way

- **The service's toasts never show.** Each one drew this in logcat: `E/NotificationService: Suppressing toast from package com.teamsassignments.widget by user request.` The app holds no notification permission (it declares none), and Android drops toasts from an app in the background whose notifications are off. The service is in the background whenever it toasts, so every outcome it reported that way was lost: *Handed in …*, *Taken as handed in …*, *Hand-in cancelled …*, *Couldn't find …* and the reasons a hand-in stopped. A toast from the widget's own activity does show (the *Sync with ↻ first* answer), since that activity is in front at the time. There's no setting to switch on, so 0.2.1 shows these on the service's own pill instead.
- **Once a hand-in is undone, the button reads `HAND IN AGAIN`**, on time or late (`detail_3a5b3795_hand_in_again`, `detail_d3f67007_hand_in_again`). The matcher took only `HAND IN` and `HAND IN LATE`, so a hand-in from the widget ended with `"…" has no Hand in button to press`, pressed nothing and saved a failure dump. With the toast lost too, Teams was simply left open on the assignment. 0.2.1 takes `HAND IN AGAIN` as well.
- **Opened from the Activity feed, a detail screen has no `Assignments` subtitle.** Its toolbar holds the class name as the title, the Hand in button and a `More options` button (`js_overflow`), and nothing else (`detail_885e3273_from_activity`). The cheap check that decides whether to look at Teams wanted `Assignments` in the title or the subtitle, so reading along never looked at this screen: an assignment opened this way was neither read nor added. 0.2.1 looks at it.
- **Teams doesn't refetch its lists by itself.** After a change on another device, the Assignments tab went on showing its old rows, and pulling down did nothing. ⋮ → Refresh reloads it, with the WebView blank for a few seconds. A row tap, a hand-in and a sync open Assignments by its link, and each time that list was up to date: a sync two minutes after an undo on the PC found the work without a refresh.
- **Undoing a hand-in the widget made** left the row without its Teams id for a while. The undone screen reads `Not handed in`, so reading along added it back from there, keyed by class and title. The list couldn't then give it its GUID, since the widget's own hand-in is remembered for 12 hours. Its Hand in button answered *Sync with ↻ first*, and a sync did restore the GUID. 0.2.1 brings it back whole.
- **The Assignments tab in Teams' main screen** differs from the one the link opens: a `Navigation` avatar button in place of Back, and Teams' bottom bar under the WebView (`list_forthcoming_earlier_today`). Reading along works in both.
- One tap on ↻ sent over adb never reached the launcher (it logged no click). The next one did. Nothing in the app was involved.

### Fixed in 0.2.1

Each fix was then tried on the phone, the same day:

- **How a row tap or hand-in went shows on the pill**, near the bottom of the screen for 5 s, or until it's tapped. *Handed in "Text - LESEN"* showed over the home screen, and no toast was attempted. The dumper's one message goes the same way.
- **`HAND IN AGAIN` is pressed like the other two.** On "Text - LESEN", whose hand-in had been undone: `Click Button "HAND IN AGAIN"`, and Teams showed it handed in 0.7 s later.
- **A detail screen without the `Assignments` subtitle is read.** Any page Teams hosts for an app sits in a native `state_layout_web_module` view. Where the toolbar doesn't name Assignments but that view is there, the window is copied, and counts if it holds the detail screen's container. Its toolbar title is then taken as the class name. Opened from the Activity feed, the page was still loading at the first two looks (2 s and 3 s after the tap) and was read at the third; such a page gets up to five looks 0.7 s apart before it's left alone like a chat.
- **An undone hand-in comes back whole.** The assignment itself is now kept with its key for those 12 hours. When its own screen shows it as not handed in, it returns as it was, Teams id included, and the hand-in is forgotten. It is matched on title, class and due time, the due time exactly, since Hand in goes by that id; another week's row still on the list is no bar. On the phone: 4 s after Undo was pressed in Teams, the log read `Seen in Teams: "Text - LESEN" is no longer handed in`. Work remembered by its id alone (seen on Completed, never on the widget's list) still comes back from its screen without one; the list then supplies it, where before it wouldn't for 12 hours. Whatever puts work back on the list, a sync included, its hand-in is forgotten there and then, so a later one is remembered from its own time. Work handed in before the list had shown it is remembered without a Teams id, and told by its title, class and due time: a list Teams hasn't refreshed can't add that back either.

A hand-in undone on another device is still not picked up from a list within those 12 hours, since a list alone can't be told from one Teams hasn't refreshed. The assignment's own screen, or a sync, brings it back.

Still worth a capture with **Dump Teams screen** if one turns up:

- a hand-in that stops because the screen Teams opened wasn't exactly the assignment's;
- a hand-in Teams doesn't confirm within 20 s;
- a slow Teams launch during a hand-in, which now has the hand-in's 45 s, not 25 s, to find the assignment;
- a copy of Assignments that fails, and is retried;
- a newly selected tab still showing the last tab's cards after a spinner;
- work falling due while the tabs are read, which is left alone;
- work opened from a notification that shares its title with a saved assignment: another class's is added alongside, another week's in the same class is left to the list;
- a list Teams hasn't refreshed still showing work handed in, after a restart or after Completed showed it: it isn't added back;
- a list that doesn't hold all its rows in the tree, which counts only once scrolled from end to end.

## Paged lists (0.3.1 on the phone, 8 Oct)

On 8 Oct, with seven assignments overdue, every sync failed with *Couldn't read the Past due list*, and a row tap on overdue work waited 17 s on Past due and then couldn't find the card. The morning's sync had worked, while two of the seven were still Forthcoming. The capture the failed sync saved showed all seven cards and, below them, the `SHIMMER_GROUP` placeholder, which the loading test counted as a spinner that never went away. Scrolling Past due to the bottom by hand made Teams swap it for the list's footer, with the same seven cards.

0.3.1:

- **A loading indicator only counts on screen.** One off screen is a list's placeholder, which is about whether the list is whole, not whether it has loaded.
- **The sync brings the placeholder into view**, which is what makes Teams load the rest, and keeps going while new cards turn up or a placeholder still waits; failing that, it scrolls. `ACTION_SHOW_ON_SCREEN` on the placeholder worked on the phone: Teams answered within 2.6 s, and the sync read all seven cards and saved all 11 assignments. A placeholder that never goes fails the step, so the previous list is kept rather than one that may be short.
- **A list still waiting to load more isn't whole**, so a row tap or a hand-in never takes work as handed in for being missing from it. Reading along takes its cards in as before; scrolled to its end, the list counts as seen in full, even though its cards haven't changed.
- **A row tap or hand-in whose card isn't loaded yet** brings in the rest of the list the way a sync does, waiting for each page, rather than giving up after a plain scroll.
- **The placeholder going counts as a change**, in the sync's settling and in reading along alike: Teams may drop it a moment before the cards it fetched arrive, so the list without it must hold still for 600 ms, as after any change, before it counts as whole.

## Consequences for the plan

1. The sync reads **Forthcoming then Past due**. Completed is ignored, and any card whose due line says `Submitted` is skipped defensively.
2. The assignment **key is the card GUID**.
3. **Row tap → open**: the service opens Assignments, selects the tab the assignment was last seen in (falling back to the other one), finds the card by GUID, scrolls it on screen and taps it. Matching by GUID replaces the plan's match on title and class.
4. The due date is built from the date header + `Due at HH:MM` in the list, with the year inferred as the nearest date to now. The detail screen's due text is authoritative when it parses.
5. The class name comes from the detail toolbar when available, otherwise from the card.

Added later:

6. **Hand in** presses the toolbar button described under Safety, then waits for the detail screen to show the work as handed in. Cancel holds right up to the press: the check for it and the removal of the pill's button happen together on the main thread, where Cancel is handled.
7. **Reading along**: while nothing runs, a change in Teams prompts a look, as does the end of a workflow, for the screen it left open (the assignment a row tap opened, say). A cheap check of Teams' native views decides whether to copy the window at all: the toolbar reading `Assignments` (`action_bar_title_text` or `action_bar_sub_title_text`), or failing that an app's page (`state_layout_web_module`), which counts only if the copy holds an assignment's detail screen. A list counts once it has loaded by the sync's own tests (tab selected, no loading indicator, cards unchanged for 600 ms, an empty list for 2 s or 6 s, and not the rows the tab was selected over), a "load more" placeholder off screen aside, since that only means the list may not be whole yet. Lists add and update cards; a detail screen, matched to a saved assignment by title, class and due time (a due time known on both sides must agree, even for a lone match, since weekly work repeats its title and class), adds its instructions; anything on Completed, or shown as handed in, is removed. Away from Assignments, Teams is checked at most every 5 s unless it opens another screen, and a change held back meanwhile still gets its look; a copy of Assignments that fails is retried, up to 5 times in a row. What Teams showed as handed in (still listed or not), and what the widget handed in, is kept in the saved state for 12 hours, so a list Teams hasn't refreshed can't add it back, even after a restart. The assignment is kept with it: its own screen reading `Not handed in` means the hand-in was undone, and brings it back as it was. Nothing on the list stays remembered as handed in.
8. **Taken as handed in**: an assignment is also removed for being on neither open tab, but only once both have been seen in full (whole in the tree, or scrolled through from end to end with every settled view overlapping the last) within 10 minutes of each other. Neither may show handed-in cards (Completed's rows, still showing) or the same cards as the other. Work falling due from 10 minutes before the first look to 2 minutes after the second is left alone, as it may have moved between the tabs. These removals aren't remembered, so a list showing the assignment again restores it. Row taps and hand-ins apply the same rule to the two tabs they read, but only to lists whole in the tree, since they don't scroll right through a list.
