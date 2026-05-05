from __future__ import annotations

from collections.abc import Iterable, Iterator
from dataclasses import dataclass
from pathlib import Path

from audio_core.scanner.walker import walk_projects


@dataclass(frozen=True)
class Discovered:
    path: str
    mtime: float
    size: int


def discover(root: str | Path) -> Iterator[Discovered]:
    """Walk `root`, yield (path, mtime, size) for every reachable .als.
    Paths whose stat() raises OSError (vanished mid-walk, permission, etc.)
    are silently skipped — the next discovery cycle will catch them or mark
    them missing."""
    for p in walk_projects(root):
        try:
            st = p.stat()
        except OSError:
            continue
        yield Discovered(path=str(p), mtime=st.st_mtime, size=st.st_size)


@dataclass(frozen=True)
class Plan:
    new: list[Discovered]
    changed: list[Discovered]
    unchanged: list[Discovered]
    missing: list[str]


def plan(catalog: dict[str, dict], discovered: Iterable[Discovered]) -> Plan:
    """Bucketize discovered .als paths against a catalog snapshot.

    `catalog` is a {path: {mtime, size, ...}} dict — the caller fetches it
    with one query. UNCHANGED is decided by mtime+size match (cheap, no
    hash). CHANGED still gets a hash later, but only when this guard fires.
    MISSING is in-catalog-not-on-disk (drive disconnected, file deleted).
    """
    new: list[Discovered] = []
    changed: list[Discovered] = []
    unchanged: list[Discovered] = []
    seen: set[str] = set()
    for d in discovered:
        seen.add(d.path)
        row = catalog.get(d.path)
        if row is None:
            new.append(d)
        elif row.get("mtime") == d.mtime and row.get("size") == d.size:
            unchanged.append(d)
        else:
            changed.append(d)
    missing = [p for p in catalog if p not in seen]
    return Plan(new=new, changed=changed, unchanged=unchanged, missing=missing)
