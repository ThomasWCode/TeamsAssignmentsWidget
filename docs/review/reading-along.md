# Reading along: review (17 Oct 2026, at 0.4.0)

This covers how the widget's list changes while no workflow runs and the user has Teams open: `TeamsAutomationService.watchTeams`/`lookAtTeams`, `LookPacer`, `AndroidTeamsDevice.teamsView`/`teamsSnapshot`, `TeamsObserver` (`look` and `merge`) and `AssignmentStore.applyObserved`. Findings marked **confirmed** were reproduced with a throwaway unit test that fed `TeamsObserver.merge` the sightings described (the test was then deleted). Those marked **by reading** follow from the code alone. Those marked **unverified** depend on Teams screens there are no captures of yet.

## Outcome (0.5.0)

The findings and improvements below are as first written. Everything was then fixed or built in 0.5.0, except improvement A, which was left out by choice. The two unverified findings were checked on the phone the same day: see [Class teams, filters and empty tabs](../teams-ui-notes.md#class-teams-filters-and-empty-tabs-17-oct) for the captures. Each fix has a test in `TeamsObserverTest`, `TeamsScreensTest`, `SyncStateMachineTest`, `NavigateStateMachineTest`, `LookPacerTest`, `DueDateParserTest`, `AssignmentStoreTest`, `WidgetTextTest` or `ProviderRowsTest`.

| | Finding | On the phone | 0.5.0 |
|---|---|---|---|
| 1 | A one-class list taking every other class's work as handed in | A class team's own tab was skipped, its toolbar titled with the class. But **Filter → Classes** narrows the app's own list with the toolbar still reading `Assignments`, so the finding stood, and a sync would have saved one class's work too | Lists carry their scope (`TeamsScreens.ListScope`). A class team's tab is read for that class alone, a narrowed list never counts as whole, and the workflows stop on a narrowed list and wait out a class team's |
| 2 | A handed-in screen with no due line removing another week's work | | Removal needs a due time known on both sides and agreeing |
| 3 | Completed remembered whole, hiding returned work | Completed went back weeks (to 9 Oct) | Only work that was on the list is remembered |
| 4 | The list's due time overriding the detail screen's | | `Assignment.listDueAt`: a changed row is told list against list, in reading along and the sync. List dates take the tab as a hint for the year |
| 5 | A cut-short collapsed title leaving a duplicate | | The row keeps its title when the card gives it its id; the detail screen matches a cut-short title with class and due time agreeing, and won't add beside one |
| 6 | The watch loop copying Teams every 0.7 s while copies failed | | `LookPacer` owns "settling": only a look that read the screen says so, so the 5-failure cap holds |
| 7 | An empty open tab never counting as seen in full | Confirmed: an empty tab has no list, only `No … assignments right now.` | Whole when the selected tab's words show, with no list and nothing loading. `list_past_due_empty` re-derived in that shape |
| 8 | A stale list taking an undone hand-in away again | | Work seen open after the earlier view is left alone: changed since (`lastSyncedAt`), or seen unchanged on any list or its own screen, which the observer notes in memory rather than saves, so the provider's apps aren't told of every look |
| 9 | Suffix class matching; a refused sighting spent; the 10-minute window; "added" for a key swap | `12.2-PH3` and `Physics Skills & Stretch 12.2-PH3` are both real teams | Exact class matching (a collapsed card with a tag leaves its class unknown); no look while a sync's status stands; an unchanged look refreshes a view's time; "took its Teams id" |

| | Improvement | 0.5.0 |
|---|---|---|
| A | Guard against mass removal | Not done, by choice |
| B | Stricter for removing than for adding | Findings 2, 3, 5 and 9 |
| C | The list's due time apart from the best one | Finding 4 |
| D | The watch loop's exit | Finding 6, with two `LookPacerTest` tests |
| E | Capture the missing screens | Six captures: two class team tabs, two empty ones, a filtered list and a class team's detail screen |
| F | The probes as regression tests | Each probe is now a test |
| G | Show what reading along confirmed | `WidgetState.listsSeenAt`: the widget's *Updated* time is the later of it and the last sync, and the provider's state row has `lists_seen_at` |

Three further improvements came from the phone (see [Found on the phone](#found-on-the-phone)): Forthcoming in full counting on its own for later work and reading a class team's own tab, both in 0.5.0, and the Past due tab's mark, built and then taken out at the user's request.

## How it works

### When it looks

1. Every Teams accessibility event (window state changed, content changed, view scrolled) that arrives while no workflow runs sets `teamsChanged`. A window-state change also sets `teamsScreenChanged`. The event starts `watchTeams` if it isn't already running (`TeamsAutomationService.kt:80`).
2. `watchTeams` waits 500 ms. It then loops every 700 ms while there is a fresh change, the observer is still `settling`, or the pacer says a look is `owed` (`TeamsAutomationService.kt:354`).
3. `LookPacer.shouldLook` holds looks back to one every 5 s once Teams was last found away from Assignments, unless another screen opens. A look it holds back is owed, and comes later even if Teams goes quiet.
4. A workflow ending (`lookAfterwards`) also prompts a look, so the screen a row tap left open gets read.

### What it looks for

| Stage | Signal | Meaning |
|---|---|---|
| Cheap check (native views, no copy) | `action_bar_title_text` or `action_bar_sub_title_text` reads exactly `Assignments` | The Assignments list or a detail screen opened from it |
| | `state_layout_web_module` present | Some app page: copied, but only counts if it holds a detail screen (the Activity feed case) |
| | Neither, or Teams not the top app window | Elsewhere: `observer.reset()` and back off for 5 s |
| Copy | `teamsSnapshot()`, up to 10,000 nodes | An incomplete copy counts as a failed look, retried up to 5 times |
| Trust | Root bounds not negative (`windowAtRest`) | Not mid-slide |
| | `assignmentViewerVisibilityContainer` | Detail screen: needs a title and nothing loading, then 600 ms unchanged |
| | `tab-Forthcoming` / `tab-Past-due` / `tab-Completed` with `selected` | List screen, and which tab |
| | On-screen `ProgressBar` or a bare `Loading` label | Still loading: not trusted |
| | Cards (GUID view ids), unchanged for 600 ms (empty: 2 s, or 6 s if that tab had saved work) | Settled |
| | Rows differ from the ones showing when the tab was selected | Not the previous tab's rows |
| Completeness | `wholeListInTree`: no zero-height `ProgressBar` (load more), and off-screen rows present at both ends | One look sees the whole tab |
| | Otherwise: overlapping settled views from the top of the list to the bottom (`Coverage`) | Seen in full by scrolling |
| | Forthcoming and Past due both in full, within 10 min of each other, with no handed-in cards, neither equal to Completed's ids, not equal to each other | `OpenLists`: enough to presume missing work handed in |

### What a sighting changes (`TeamsObserver.merge`)

- **Forthcoming / Past due list.** New cards are added without instructions. Known ones get their title, class and due time updated, and a changed title or time clears `detailReadAt`. A card that fell due earlier today goes under Past due. A row added earlier from a detail screen under a fallback key takes the card's GUID. Work handed in lately (kept 12 h in `WidgetState.handedIn`) isn't added back.
- **Completed list.** Every card without a `Due` line is removed from the widget and remembered as handed in.
- **Both open tabs in full.** GUID-keyed rows on neither tab are removed as "taken as handed in" (not remembered), unless they fall due in the window around the two reads.
- **Detail screen.** Matched to a saved row by exact normalised title, then class (when the toolbar names it), then due time (when known on both sides). A single match gets the screen's instructions, attachments, links and due time, or is removed if the screen shows it handed in. With no match, open work is either restored from `handedInWork` (hand-in undone) or added under a fallback key, unless a same-titled row of that class exists.

`AssignmentStore.applyObserved` applies the result under the store's mutex. While the status reads `Running` it changes nothing.

## Findings

Ranked by likely harm. Line numbers are as of commit `f62335c`.

### 1. A class team's own Assignments tab could take every other class's work as handed in (high if real, unverified)

Teams' Past due list ends with *"To view older assignments, navigate to an individual class team"*, so each class team has its own Assignments tab, filtered to that class. The cheap check (`AndroidTeamsDevice.kt:66`) accepts any screen whose toolbar title **or** subtitle reads `Assignments`. A channel tab named Assignments may well read that way. If it does, and its web content uses the same `tab-Forthcoming`/`tab-Past-due` ids:

- seeing both its tabs in full builds an `OpenLists` that holds only that class's GUIDs. `mergeList` (`TeamsObserver.kt:363`) then removes every other class's GUID-keyed row as "taken as handed in";
- its Past due tab lists older work that the main list leaves out. That work gets added, then the main list's next full view removes it again.

The removals aren't remembered, so the next full look at the main list puts them back. Until then the widget is missing most of its rows, and a hand-in or row tap on what is left works from a wrong picture.

**To do:** arm the dumper on a class team's Assignments tab and capture both tabs. Then tell the two views apart by what differs: the toolbar subtitle (team or channel name), cards without a class line, or every card sharing one class. Whatever the capture shows, add the guard in improvement A below as defence in depth.

### 2. A handed-in screen with no due line can remove another week's open work, and keep it off for 12 h (medium, confirmed)

`mergeDetail`'s candidate filter (`TeamsObserver.kt:417`) lets a screen with no parsed due time match any same-titled row of the class: `dueAt == null || it.dueAt == null || it.dueAt == dueAt`. The comment's rule, that a known due time must agree, only protects when both sides know one. A screen's due time is null when the assignment has no due date, or when its due line isn't in a format `DETAIL_DUE` and `parseDetail` know.

The scenario: weekly "Vocab quiz" work with no due date. The user opens last week's (handed in) from Completed. The only saved "Vocab quiz" is this week's open one, so it is the single match. `detail.isHandedIn` then removes it and reports its GUID as handed in (`TeamsObserver.kt:474`). For the next 12 h the list skips that card (`TeamsObserver.kt:336`), so it stays off the widget until a sync.

The probe result: `kept=[] handedIn=[<this week's GUID>] changes=["Vocab quiz" handed in]`.

**Fix:** removing a row on a detail screen's word should need a stricter match than reading one does. When the screen shows the work handed in, require a known due time that agrees on both sides, and otherwise leave it to the lists, where cards have GUIDs.

### 3. Everything seen on Completed is remembered as handed in, which hides returned work for up to 12 h (medium, confirmed)

`mergeList` remembers every Completed card's key (`TeamsObserver.kt:305`), not only keys on the widget's list. That includes work handed in weeks ago. If a teacher then returns work for revision, or reopens closed work, it comes back on Forthcoming or Past due, but reading along won't add it for up to 12 h after the last Completed look (`TeamsObserver.kt:336`). The detail screen can't bring it back either. A restore needs the status to read exactly `Not handed in` (not `Returned…`), and the work to be in `handedInWork`, which Completed-only cards never are. Only a sync restores it.

This also adds every Completed card (Completed showed 30) to `widget_state.json` for 12 h.

The probe result: after a Completed sighting `handedIn=[<GUID>]`, and a Forthcoming list showing the card then adds nothing.

**Fix:** the 12 h memory guards against a stale open list re-adding work that was just handed in, so it only needs keys that were on the saved list, or that were taken off it lately. For the rest, either don't remember them or give them a short memory (say 30 min). Separately, a detail screen whose status is `Returned…` with a Hand in button could count as open.

### 4. The list's due time overrides the detail screen's, and the detail screen then can't put it right (medium, confirmed)

`updated()` takes the list's due time whenever it differs from the saved one, and clears `detailReadAt` (`TeamsObserver.kt:384`, `:394`). The detail screen's next look then fails its own rule that due times agree, so it matches nothing: no instructions are read, and the "same-titled row of this class" guard stops an add. The sync does the opposite and prefers the detail's time (`SyncStateMachine.kt`, `detailDueAt ?: listDueAt`). So whenever the two screens disagree, each sync writes the detail's time, the next list look writes the list's, and the details are marked unread every time.

The two screens can disagree in these cases:

- **List header dates have no year.** `DueDateParser.date` picks the year closest to today (`DueDateParser.kt:114`). A Forthcoming card due more than about six months ahead (an end-of-year project seen in October) is then dated last year. The list rule `dueAt <= wallClock` files it under Past due, and the widget shows it overdue. This depends on Teams leaving the year off far-off headers, and `GROUP_DATE` allows for a year being there.
- **A teacher's change** showing on one screen before the other.
- **A relative detail line** (`Due tomorrow at 08:30`) rendered before midnight and read after.

The probe result: list `Due at 09:30` against a detail-read 08:30 gives `dueAt=09:30, detailReadAt=null`, and the detail screen then gives `changes=[]`.

**Fix:** save the list's own parsed due time (`listDueAt`) beside `dueAt`. Compare list with list to decide whether a row changed, in `updated()` and in the sync's `rowUnchanged` alike, and keep `dueAt` preferring the detail's time. Also give the parser the tab as a hint for the year: Forthcoming isn't in the past, and Past due isn't in the future.

### 5. A collapsed card with a cut-short title can leave a duplicate row (low to medium, confirmed)

Taking over a row that was added from a detail screen (`TeamsObserver.kt:349`) copies the earlier row's class, instructions and due text, but **takes the card's title**. `sameRow` allows a collapsed card's title to be a prefix of the saved one, so the saved title can end up the short one. The detail screen matches titles exactly (`TeamsObserver.kt:416`, not `sameTitle`), so its next look no longer matches and adds a second row under a fallback key. Rows that aren't GUID-keyed are never presumed handed in, so the duplicate stays until the next sync.

The probe result: `[11111111:Vocab quiz, h-f64396:Vocab quiz chapter 4]`.

How often this bites depends on how often a collapsed card's title really is cut short (`docs/teams-ui-notes.md` allows for it).

**Fix:** keep `earlier.title` when `sameTitle(card.title, earlier.title)`, as `updated()` already does for GUID-keyed rows.

### 6. The watch loop can copy Teams' window every 0.7 s for as long as copies fail (low likelihood, high cost; by reading)

When the copy fails on Assignments (the tree is over 10,000 nodes, or reading it throws), `lookAtTeams` returns before `observer.look` (`TeamsAutomationService.kt:388`). That leaves `observer.settling` as the last look set it. If that was `true`, `while (teamsChanged || observer.settling || pacer.owed)` never ends. `LookPacer`'s cap of 5 failures is bypassed: `owed` turns false, but `settling` stays true. Every ~0.7 s it does a full-window IPC copy, with no Teams event needed, until a copy succeeds or the user leaves Assignments.

**Fix:** clear `settling` on that path (for example `observer.unsettle()`), or base the loop on what the last look actually did.

### 7. An empty open tab may never count as seen in full (low, unverified)

`list_past_due_empty` is derived: a real Past due capture with its rows taken out and its `ListView` kept. If Teams' real empty state has no `ListView` (an "all caught up" picture, say), then `listNode` is null, `wholeListInTree` is false and `listInView` is null. `fullView` never completes, so `bothInFull` never fires while either open tab is empty. This errs safe. But the presumed-handed-in rule, the main way reading along notices work handed in on another device, goes quiet just when the student has cleared Past due.

**To do:** capture a real empty tab. If it has no list, treat "no list, an empty-state marker, nothing loading" as whole.

### 8. An undone hand-in can be taken away again by a list Teams hasn't refreshed (low, unverified)

The undo path restores the row under its GUID. If the user goes Back before Teams' list shows the work again, the next list sighting carries `OpenLists`, where the other tab's view dates from before the undo. The restored row is on neither, so it is removed as presumed. It comes back once a list shows it.

**Fix:** don't presume a row missing from a view older than its own last sighting as open. Skip rows whose `lastSyncedAt` is later than the older of the two full views. This rule also covers anything else the store learns between the two views.

### 9. Smaller points

- **Class names that end in another class's name count as the same class** (`classMatches`, `TeamsObserver.kt:516`). "Further Maths" matches "Maths". The match still needs the same title and, where known, the same due time, so harm is unlikely. The suffix allowance is only needed for a collapsed card's tag chip, so it could apply only when one side came from a collapsed card.
- **A sighting is spent even when the store refuses it.** `look` sets `lastUsed` before `applyObserved`, and that returns unchanged while the status is `Running`. Outside a run the status is very unlikely to read `Running`, so this is latent.
- **The 10-minute window is measured from when the observer saw each tab, not when Teams fetched it.** A tab looked at for over 10 min without change keeps its first time, since `fullViews[tab].at` only moves on a new sighting, so the user must reselect it. Staleness on Teams' side mostly errs safe, because the union of two views keeps more, not less.
- **The log says `added "…"` when a card's GUID replaces a detail-added row's key.** "took its Teams id" would read better.

## Improvements

**A. Guard against mass removal.** Whatever the cause (finding 1, a misread, a Teams redesign), a single sighting that presumes many rows handed in is more likely wrong than right. Cap the presumed removals per sighting, at say more than 2 rows or more than a third of the GUID-keyed list. When the cap trips, remove nothing and log `"N rows on neither list: not trusted; sync to check"`. A real clear-out still happens on the next sync.

**B. Be stricter for removing than for adding or reading.** Several findings (2, 3, 8) come from destructive rules matching as loosely as additive ones. As a principle: removing needs a GUID (a list card), or a detail match with title, class and due time all known and agreeing. Reading instructions can stay as loose as it is.

**C. Keep the list's due time apart from the best due time.** Finding 4 is a model problem: one `dueAt` field serves both as "what the list said", for change detection, and "the best due time", for display. Splitting it stops reading along and the sync fighting over the field. With the tab-aware year guess it also removes the far-future-shows-as-overdue case.

**D. Fix the watch loop's exit condition** (finding 6), and add a `LookPacerTest`-style test that drives a failing copy with `settling` set.

**E. Capture the missing screens.** The two unverified findings that matter (1 and 7) need two dumper captures: a class team's Assignments tab, both open tabs, and an open tab with nothing on it. They then become fixtures through `scripts/derive_fixtures.py`'s neighbours.

**F. Turn the probes into regression tests.** Findings 2 to 5 each reduce to a short `merge` call in `TeamsObserverTest`, with constructed `Sighting`s, no fixture needed. Each test should be written failing first and then fixed.

**G. Show what reading along has confirmed.** Reading along refreshes rows (`lastSyncedAt`) but not `lastSuccessAt`. Recording when both open tabs were last seen in full (say `WidgetState.openListsSeenAt`) would let the widget and the content provider say "checked in Teams 3 min ago", so the user doesn't run a sync that would find nothing new.

## Found on the phone

Looking through Teams on the phone (17 Oct, 0.4.0 installed) turned up these ways to see more, beyond the findings:

- **Work handed in elsewhere went unseen.** "Laptops", due on the 19th, was handed in at 11:40, almost certainly on another device, and stayed on the widget until the 14:29 sync. Only both open tabs seen in full within ten minutes took such work off, and Teams opens on Forthcoming. **Built:** Forthcoming seen in full is enough on its own for work due more than 2 minutes after the look. Nothing moves such work to Past due, so only a hand-in, or the teacher, took it off.
- **The Past due tab says whether it holds work.** On Forthcoming and Completed its description is `You have past due assignments` while it does, and it is a plain `Past due` when it doesn't. **Built, then taken out at the user's request:** unmarked, Past due counted as seen in full and empty, so late work handed in elsewhere went without opening Past due. Past due now counts only once it has been seen in full itself.
- **A class team's own Assignments tab** is another way in, and Teams' own Past due footer sends students there for older work. **Built:** it's read, scoped to that class (above).
- **Teams' Activity feed** lists every `<teacher> added an assignment`, with class, full title and due date, but no time. Left for now: tapping the alert already opens the assignment, which reading along reads and adds with its exact time, and alerts stay for days, so rows made from them could bring back work since handed in or deleted.
- **Teams' notifications** could show new work without the user opening Teams at all. There were none on the phone to learn their format from, and the service would need notification events (a config change). Left until a real one can be captured.
- An unrelated line in the phone's log: an app queried `content://com.teamsassignments.widget.assignments/returned`, which the provider doesn't offer (`No such table`). Probably Decrastination expecting a table this app doesn't have yet.
