from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path

EXCLUDED_DIRS = frozenset({"Backup", "_Archive", "Ableton Project Info"})


def walk_projects(root: str | Path) -> Iterator[Path]:
    root = Path(root)
    for p in root.rglob("*.als"):
        if any(part in EXCLUDED_DIRS for part in p.relative_to(root).parts):
            continue
        yield p
