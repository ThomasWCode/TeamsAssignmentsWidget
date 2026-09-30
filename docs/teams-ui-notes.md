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
  Button "HAND IN" / "HAND IN LATE"     ← only the hand-in workflow presses it; "UNDO HAND-IN" once handed in
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
The `HAND IN` / `HAND IN LATE` toolbar button and the `Open Attach menu` / `Open New menu` buttons are the dangerous controls on the detail screen.

The one exception is the **hand-in workflow**, which runs only after the user confirms on the widget. It opens the assignment by its GUID (never by title), checks that the status isn't handed in, and presses the `Button` in the native `toolbar` whose text is exactly `HAND IN` or `HAND IN LATE`, and enabled. It uses the button's click action: it's a native view, which takes click actions the way TalkBack presses it, unlike the WebView content. It presses once, and a second time only if Teams reports that the first click didn't go through. It never presses `UNDO HAND-IN` or anything else, and never taps the toolbar.

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
- **Work that fell due earlier today is listed on both tabs.** Syncs at 11:16 and 13:20 found 8 + 5 and 9 + 5 open cards but only 11 and 12 different ones. The two on both tabs were the day's 08:00 and 09:00 homework, still under Forthcoming's "Today" while Past due listed them as "Due earlier today". The sync keys cards by GUID, so each counts once, under Past due. This is inferred from the counts; no Forthcoming capture from such a time exists yet.
- **While the Assignments module loads, the WebView is empty.** A capture just after launch showed Teams' toolbar over a WebView with no children: no tabs, no cards and no spinner. It isn't mistaken for an empty list, because a list needs its tabs. Captured as `list_assignments_loading`.
- **Forthcoming's "Next week" divider isn't in the tree.** Teams draws it between this week's date groups and later ones, but the accessibility tree holds only the usual groups (`5 Oct` / `Monday`).
- **`uiautomator dump` switches accessibility services off while it runs.** The service logged "Service disconnected", then reconnected about a second later. A dump taken during a sync would end it, so use the app's **Dump Teams screen** mid-run, and adb dumps only while nothing is syncing.
- A full read of all 10 assignments matched Teams exactly: titles, classes, due times and instructions. Later syncs matched Teams' lists too.

Not yet seen, and worth capturing with **Dump Teams screen** when they turn up:

- An **empty** Forthcoming or Past due tab. For now an empty list is believed only after holding for 2 s (6 s if that tab had work at the last sync) with no loading indicator.
- What Teams shows **while a tab loads** after a switch. (The whole module loading at launch is captured, above.) A spinner surfaces as a `ProgressBar` node, and an exact "Loading" label is also treated as loading.
- An **on-time** hand-in's status. Only a late one has been captured (below); the check accepts any status starting `Handed in`, `Turned in` or `Submitted`.
- A detail screen opened **from a Teams notification**, rather than from the list. Reading along only trusts its toolbar title as the class name while the subtitle reads `Assignments`.

Some test fixtures are **derived** from the captures rather than captured: `list_past_due_with_moved_cards`, `list_past_due_stale_rows`, `list_past_due_empty`, `list_past_due_loading`, `list_forthcoming_single`, `list_past_due_single_moved`, `detail_unreadable`, `detail_4c958b24_handed_in`, `detail_88fafeb2_handed_in` and `detail_4c958b24_hand_in_disabled`. Each builds a state that's hard to catch live (a card on both tabs, a tab selected before its rows load, an empty or loading list, a single card moving tabs, an unreadable detail screen), seen only once (a handed-in detail screen, carried over from the captured `detail_f63a23c9_handed_in` to other assignments), or not seen at all (a greyed-out Hand in button), by editing a real capture. [`scripts/derive_fixtures.py`](../scripts/derive_fixtures.py) regenerates them after fresh captures.

## Handing in and reading along (0.2.0 on the phone)

- **The detail screen once handed in**, captured as `detail_f63a23c9_handed_in` just after "Dr. Frost - Forces - Week 3" was handed in within Teams:
  - the status reads `Handed in late Wed 30 Sept 2026 at 10:54`;
  - the toolbar button reads `UNDO HAND-IN`, **hyphenated**, where the Hand in button reads `HAND IN`. The first matcher, written before this capture, missed the hyphen; the status line had confirmed hand-ins regardless;
  - the `Open Attach menu` and `Open New menu` buttons are disabled;
  - Teams also puts the status in a `screenReaderAnnouncement` node, outside the detail container.
- **Handing in from the widget** ("Prep 18/09/2026 - Chapter 12 review", past due): `HAND IN LATE` took the click action, and the status read handed in within 2 s, with no prompt or dialog in between.
- **Reading along** saved the Dr. Frost assignment's details when it was opened, and took it off the list as soon as Teams showed it handed in.
- A hand-in for work handed in before 0.2.0 was installed (so the widget still listed it) found the card on neither open tab and pressed nothing. A sync then dropped it.

## Consequences for the plan

1. The sync reads **Forthcoming then Past due**. Completed is ignored, and any card whose due line says `Submitted` is skipped defensively.
2. The assignment **key is the card GUID**.
3. **Row tap → open**: the service opens Assignments, selects the tab the assignment was last seen in (falling back to the other one), finds the card by GUID, scrolls it on screen and taps it. Matching by GUID replaces the plan's match on title and class.
4. The due date is built from the date header + `Due at HH:MM` in the list, with the year inferred as the nearest date to now. The detail screen's due text is authoritative when it parses.
5. The class name comes from the detail toolbar when available, otherwise from the card.

Added later:

6. **Hand in** presses the toolbar button described under Safety, then waits for the detail screen to show the work as handed in.
7. **Reading along**: while nothing runs, a change in Teams prompts a look. A cheap check of the native toolbar (`action_bar_title_text` or `action_bar_sub_title_text` reading `Assignments`) decides whether to copy the window at all. A list counts once it has loaded by the sync's own tests (tab selected, no loading indicator, cards unchanged for 600 ms, an empty list for 2 s or 6 s, and not the rows the tab was selected over), Completed's off-screen placeholder aside. Lists add and update cards; a detail screen, matched to a saved assignment by title, then class, then due time, adds its instructions; anything on Completed, or shown as handed in, is removed.
8. **Taken as handed in**: an assignment is also removed for being on neither open tab, but only once both have been seen in full (whole in the tree, or scrolled through from end to end with every settled view overlapping the last) within 10 minutes of each other. Neither may show handed-in cards (Completed's rows, still showing) or the same cards as the other. Work falling due from 10 minutes before the first look to 2 minutes after the second is left alone, as it may have moved between the tabs. These removals aren't remembered, so a list showing the assignment again restores it. Row taps and hand-ins apply the same rule to the two tabs they read, but only to lists whole in the tree, since they don't scroll right through a list.
