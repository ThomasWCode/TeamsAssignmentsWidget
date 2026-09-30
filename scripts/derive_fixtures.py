"""Builds the derived test fixtures from the real Teams captures.

Some states are hard to catch on a phone: a card listed on both tabs, a tab selected before its
rows load, an empty or loading list, a single card moving tabs, a detail screen with nothing
readable yet. Each is made here by
editing a real capture, so the node shapes stay authentic.

Others haven't been seen at all yet, and are a best guess until they are captured: a detail
screen just after Hand in, and one whose Hand in button is disabled.

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
    """Detail screens as Teams is expected to show them once handed in, not yet captured: the
    status reads Handed in (or Handed in late), and the toolbar button Undo hand in."""
    for guid, status in (("4c958b24", "Handed in"), ("88fafeb2", "Handed in late")):
        tree = load(f"detail_{guid}")
        container = by_id(tree, "assignmentViewerVisibilityContainer")
        next(n for n in container.iter("node") if n.get("text") == "Not handed in").set("text", status)
        hand_in_button(tree).set("text", "UNDO HAND IN")
        save(tree, f"detail_{guid}_handed_in")


def hand_in_disabled():
    """A detail screen whose Hand in button is greyed out."""
    tree = load("detail_4c958b24")
    hand_in_button(tree).set("enabled", "false")
    save(tree, "detail_4c958b24_hand_in_disabled")


if __name__ == "__main__":
    moved_cards()
    stale_rows()
    empty_and_loading()
    single_card_moved()
    unreadable_detail()
    handed_in_details()
    hand_in_disabled()
