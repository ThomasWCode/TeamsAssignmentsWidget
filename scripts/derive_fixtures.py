"""Builds the derived test fixtures from the real Teams captures.

Some states are hard to catch on a phone: a card listed on both tabs, a tab selected before its
rows load, an empty or loading list, a detail screen with nothing readable yet. Each is made here by
editing a real capture, so the node shapes stay authentic.

Run from the repository root after replacing the captures:

    python scripts/derive_fixtures.py
"""

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


def unreadable_detail():
    """A detail screen that has opened but shows no text yet."""
    tree = load("detail_4c958b24")
    for node in by_id(tree, "assignmentViewerVisibilityContainer").iter("node"):
        node.set("text", "")
        node.set("content-desc", "")
    save(tree, "detail_unreadable")


if __name__ == "__main__":
    moved_cards()
    stale_rows()
    empty_and_loading()
    unreadable_detail()
