"""Path-allowlist enforcement.

`ensure_within(target, root)` validates two ways:

- Lexical: `os.path.abspath(target)` (which collapses `..`) must remain inside
  `os.path.abspath(root)`. This catches `../escape` traversal and absolute
  paths to elsewhere even before any filesystem call.
- Resolved: `os.path.realpath(target)` (which follows symlinks/junctions) must
  remain inside `os.path.realpath(root)`. This catches symlinks pointing
  outside the allowlist.

BOTH must pass. Anything that fails either form raises PermissionError.

Case-insensitive on Windows (via `os.path.normcase`) so `Z:/User/...` and
`z:/user/...` compare equal.
"""

from __future__ import annotations

import os
from pathlib import Path


def _norm(p: str | Path) -> str:
    return os.path.normcase(os.path.normpath(os.fspath(p)))


def _within(target: str | Path, root: str | Path) -> bool:
    t, r = _norm(target), _norm(root)
    return t == r or t.startswith(r + os.sep)


def ensure_within(target: str | Path, root: str | Path) -> None:
    """Raise PermissionError if `target` is not inside `root` after both
    lexical and symlink-resolved comparison."""
    t_str = os.fspath(target)
    r_str = os.fspath(root)
    # Lexical form — collapses ../, absolute substitutions
    t_lex = os.path.abspath(t_str)
    r_lex = os.path.abspath(r_str)
    if not _within(t_lex, r_lex):
        raise PermissionError(
            f"path {t_str!r} (lexical {t_lex!r}) escapes allowlisted root {r_str!r}"
        )
    # Resolved form — follows symlinks/junctions. Non-existent components
    # are tolerated by realpath (they pass through unchanged).
    try:
        t_real = os.path.realpath(t_str)
    except OSError:
        t_real = t_lex
    try:
        r_real = os.path.realpath(r_str)
    except OSError:
        r_real = r_lex
    if not _within(t_real, r_real):
        raise PermissionError(
            f"path {t_str!r} (resolved {t_real!r}) escapes allowlisted root {r_real!r}"
        )
