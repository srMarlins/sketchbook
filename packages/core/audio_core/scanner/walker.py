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


SAMPLE_EXTENSIONS = frozenset({".wav", ".aif", ".aiff", ".flac", ".mp3", ".ogg"})


def walk_samples(root: str | Path) -> Iterator[Path]:
    """Walk `root` and yield every audio sample file (by extension). Reuses
    the project walker's `EXCLUDED_DIRS` so we don't crawl Backup/, _Archive/,
    or Ableton Project Info/."""
    root = Path(root)
    for p in root.rglob("*"):
        if p.suffix.lower() not in SAMPLE_EXTENSIONS:
            continue
        if not p.is_file():
            continue
        try:
            rel_parts = p.relative_to(root).parts
        except ValueError:
            continue
        if any(part in EXCLUDED_DIRS for part in rel_parts):
            continue
        yield p
