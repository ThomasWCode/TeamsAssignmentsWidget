"""Builds the derived test fixtures from the real Teams captures.

Some states are hard to catch on a phone: a card listed on both tabs, a tab selected before its
rows load, an empty or loading list, a single card moving tabs, a detail screen with nothing
readable yet. Each is made here by
editing a real capture, so the node shapes stay authentic.

Two carry a single capture over to other assignments: the handed-in detail screens copy what
detail_f63a23c9_handed_in, captured on the phone after a hand-in, shows. A greyed-out Hand in
button hasn't been seen at all, so that one is a guess, as are the virtualised lists (Teams keeps
every row in the tree) and a tab showing Completed's rows.

Run from the repository root after replacing the captures:

    python scripts/derive_fixtures.py
"""

import copy
import re
import xml.etree.ElementTree as ET
from pathlib import Path

FIXTURES = Path(__file__).resolve().parent.parent / "app/src/test/resources/fixtures/teams"


def load(name):
    return ET.parse(FIXTURES / f"{name}.xml")


def save(tree, name):
    tree.write(FIXTURES / f"{name}.xml", encoding="UTF-8", xml_declaration=True)
    print("wrote", name)


def by_id(tree, resource_id):
    return next(n for n in tree.getroot().iter("node") if n.get("resource-id") == resource_id)


def list_view(tree):
    return next(n for n in tree.getroot().iter("node") if n.get("class") == "android.widget.ListView")


def moved_cards():
    """Past due also showing Forthcoming's first date group, as if those deadlines just passed.
    The spliced group sits below the fold (zero height) so it can't overlap the real cards."""
    past, forthcoming = load("list_past_due"), load("list_forthcoming")
    group = next(iter(list_view(forthcoming)))
    for node in group.iter("node"):
        left, _, right, _ = map(int, re.findall(r"-?\d+", node.get("bounds")))
        node.set("bounds", f"[{left},2298][{right},2298]")
    list_view(past).insert(0, group)
    save(past, "list_past_due_with_moved_cards")


def stale_rows():
    """Past due selected while Forthcoming's rows are still showing."""
    tree = load("list_forthcoming")
    by_id(tree, "tab-Forthcoming").set("selected", "false")
    by_id(tree, "tab-Past-due").set("selected", "true")
    save(tree, "list_past_due_stale_rows")


def stale_past_due_rows():
    """Forthcoming selected while Past due's rows are still showing: the rows a paged Past due ended
    on, when a row tap searched it and moved on."""
    tree = load("list_past_due")
    by_id(tree, "tab-Past-due").set("selected", "false")
    by_id(tree, "tab-Forthcoming").set("selected", "true")
    save(tree, "list_forthcoming_stale_past_due_rows")


def empty_and_loading():
    """Past due with no cards, then the same with a spinner (Fluent's has role=progressbar)."""
    tree = load("list_past_due")
    rows = list_view(tree)
    for child in list(rows):
        rows.remove(child)
    save(tree, "list_past_due_empty")
    ET.SubElement(by_id(tree, "root"), "node", {
        "index": "9", "text": "", "resource-id": "", "class": "android.widget.ProgressBar",
        "package": "com.microsoft.teams", "content-desc": "", "clickable": "false", "enabled": "true",
        "focusable": "false", "scrollable": "false", "selected": "false", "bounds": "[500,1200][580,1280]",
    })
    save(tree, "list_past_due_loading")


def single_card_moved():
    """Forthcoming with one card, and Past due showing that same card under Past due's own kind of
    label, as when its deadline passes between the two reads."""
    guid = "36274911-c6dd-490d-956d-0273df409847"
    forthcoming = load("list_forthcoming")
    rows = list_view(forthcoming)
    for extra in list(rows)[1:]:
        rows.remove(extra)
    for child in list(rows[0]):
        if len(child.get("resource-id", "")) == 36 and child.get("resource-id") != guid:
            rows[0].remove(child)
    save(forthcoming, "list_forthcoming_single")

    past = load("list_past_due")
    rows = list_view(past)
    groups = [c for c in rows if c.get("class") == "android.view.View"]
    for extra in groups[1:]:
        rows.remove(extra)
    group = groups[0]
    date, label = [c for c in group if c.get("class") == "android.widget.TextView"][:2]
    date.set("text", "28 Sept")
    label.set("text", "Due today")
    old_card = next(c for c in group if len(c.get("resource-id", "")) == 36)
    moved = copy.deepcopy(next(n for n in list_view(load("list_forthcoming")).iter("node") if n.get("resource-id") == guid))
    group.insert(list(group).index(old_card), moved)
    group.remove(old_card)
    save(past, "list_past_due_single_moved")


def unreadable_detail():
    """A detail screen that has opened but shows no text yet."""
    tree = load("detail_4c958b24")
    for node in by_id(tree, "assignmentViewerVisibilityContainer").iter("node"):
        node.set("text", "")
        node.set("content-desc", "")
    save(tree, "detail_unreadable")


def hand_in_button(tree):
    return next(
        n for n in tree.getroot().iter("node")
        if n.get("class") == "android.widget.Button" and n.get("text", "").startswith("HAND IN")
    )


def handed_in_details():
    """Two more detail screens as the captured detail_f63a23c9_handed_in shows one once handed in:
    the status reads "Handed in late Wed 30 Sept 2026 at 10:54", the toolbar button "UNDO HAND-IN",
    and the Attach and New menus are disabled. The on-time wording ("Handed in ...") is inferred."""
    for guid, status in (("4c958b24", "Handed in"), ("88fafeb2", "Handed in late")):
        tree = load(f"detail_{guid}")
        container = by_id(tree, "assignmentViewerVisibilityContainer")
        next(n for n in container.iter("node") if n.get("text") == "Not handed in").set(
            "text", f"{status} Mon 28 Sept 2026 at 06:41"
        )
        hand_in_button(tree).set("text", "UNDO HAND-IN")
        for node in container.iter("node"):
            if node.get("content-desc") in ("Open Attach menu", "Open New menu"):
                node.set("enabled", "false")
        save(tree, f"detail_{guid}_handed_in")


def prefix_titled_detail():
    """Another assignment's screen, whose title only starts the chosen one's: "Particle Physics"
    for "Particle Physics Test", as a mis-tap could open. Opening a card allows for a title cut
    short (a collapsed card's is); handing in mustn't."""
    tree = load("detail_4c958b24")
    container = by_id(tree, "assignmentViewerVisibilityContainer")
    next(n for n in container.iter("node") if n.get("text") == "Particle Physics Test").set("text", "Particle Physics")
    save(tree, "detail_4c958b24_prefix_title")


def zero_height(node):
    _, top, _, bottom = map(int, re.findall(r"-?\d+", node.get("bounds")))
    return bottom <= top


def virtualised():
    """Lists as a virtualised one would expose them, holding only the rows in view: without the
    zero-height rows Teams keeps in the tree past the screen's edges. Teams doesn't do this today;
    if it did, a list would only count as seen in full once scrolled through. The last is a view
    further down sharing no card with the first, as after a fling past rows unseen."""
    for source in ("list_forthcoming", "list_forthcoming_scrolled"):
        tree = load(source)
        rows = list_view(tree)
        for row in list(rows):
            if zero_height(row):
                rows.remove(row)
                continue
            for child in list(row):
                if len(child.get("resource-id", "")) == 36 and zero_height(child):
                    row.remove(child)
        save(tree, source.replace("list_forthcoming", "list_forthcoming_virtualised"))

    tree = load("list_forthcoming_virtualised_scrolled")
    for row in list_view(tree):
        for child in list(row):
            if child.get("resource-id", "")[:8] in ("4c958b24", "839994fb"):
                row.remove(child)
    save(tree, "list_forthcoming_virtualised_end")


def stale_tab_rows():
    """A newly selected tab still showing another's rows: Completed over Forthcoming's (open
    cards), and Past due over Completed's (handed-in cards). The latter drops Completed's "load
    more" placeholder, which alone would keep it from counting, to leave the handed-in cards."""
    tree = load("list_forthcoming")
    by_id(tree, "tab-Forthcoming").set("selected", "false")
    by_id(tree, "tab-Completed").set("selected", "true")
    save(tree, "list_completed_stale_rows")

    tree = load("list_completed")
    by_id(tree, "tab-Completed").set("selected", "false")
    by_id(tree, "tab-Past-due").set("selected", "true")
    for parent in tree.getroot().iter("node"):
        for child in list(parent):
            if child.get("class") == "android.widget.ProgressBar":
                parent.remove(child)
    save(tree, "list_past_due_completed_rows")


def hand_in_disabled():
    """A detail screen whose Hand in button is greyed out."""
    tree = load("detail_4c958b24")
    hand_in_button(tree).set("enabled", "false")
    save(tree, "detail_4c958b24_hand_in_disabled")


if __name__ == "__main__":
    moved_cards()
    stale_rows()
    stale_past_due_rows()
    empty_and_loading()
    single_card_moved()
    unreadable_detail()
    handed_in_details()
    hand_in_disabled()
    prefix_titled_detail()
    virtualised()
    stale_tab_rows()
