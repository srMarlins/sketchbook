from __future__ import annotations

from pathlib import Path


def ensure_within(target: str | Path, root: str | Path) -> None:
    """Raise PermissionError if `target` is not inside `root` (after resolving)."""
    t = Path(target).resolve()
    r = Path(root).resolve()
    try:
        t.relative_to(r)
    except ValueError as e:
        raise PermissionError(f"path {t} escapes allowlisted root {r}") from e
