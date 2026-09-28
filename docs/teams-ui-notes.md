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
  Button "HAND IN" / "HAND IN LATE"     ← NEVER CLICK
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

The automation only ever clicks **tab nodes** (`tab-*`) and **assignment cards** (a GUID id).
As a second guard, it refuses any node whose text or content-desc matches hand-in, turn-in, submit, attach, delete or similar.
The `HAND IN` / `HAND IN LATE` toolbar button and the `Open Attach menu` / `Open New menu` buttons are the dangerous controls on the detail screen.

## Consequences for the plan

1. The sync reads **Forthcoming then Past due**. Completed is ignored, and any card whose due line says `Submitted` is skipped defensively.
2. The assignment **key is the card GUID**.
3. **Row tap → open**: the service opens Assignments, selects the tab the assignment was last seen in (falling back to the other one), finds the card by GUID, scrolls it on screen and clicks it. Matching by GUID replaces the plan's match on title and class.
4. The due date is built from the date header + `Due at HH:MM` in the list, with the year inferred as the nearest date to now. The detail screen's due text is authoritative when it parses.
5. The class name comes from the detail toolbar when available, otherwise from the card.
