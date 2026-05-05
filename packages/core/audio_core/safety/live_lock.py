from __future__ import annotations

from pathlib import Path

import psutil

LIVE_PROC_NAMES = frozenset({"Ableton Live.exe", "Live.exe", "Ableton Live"})


def is_live_running() -> bool:
    """True if any Ableton Live process is currently running.

    This is a coarse but reliable signal on Windows: enumerating processes
    by name does not require admin (unlike enumerating their open files).
    Use this as a hard guard for mutating actions: if Live is running, we
    can't safely rename/move/archive any project, because we cannot
    distinguish 'the one Live has open' from 'the others'.
    """
    for proc in psutil.process_iter(["name"]):
        try:
            if (proc.info.get("name") or "") in LIVE_PROC_NAMES:
                return True
        except (psutil.AccessDenied, psutil.NoSuchProcess):
            continue
    return False


def is_open_in_live(path: str | Path) -> bool:
    """Refuse to mutate while Ableton Live is running.

    On Windows, `psutil.Process.open_files()` typically requires elevated
    privileges to enumerate file handles for processes you don't own — so
    a per-file check returns False on a typical user account, even when
    Live IS holding the file. We therefore widen the guard: if any Ableton
    Live process is running at all, treat every project as "open in Live"
    and refuse the mutation. The user has to close Live first.

    If you actually need finer-grained checks (e.g. you trust that a single
    Live instance has only one project open), do that work outside of this
    function.
    """
    if not is_live_running():
        return False
    # Live IS running. Try the per-file check; if that returns True, great.
    # If it returns False (likely on non-admin Windows accounts), still
    # report True — we cannot prove the file isn't open.
    target = str(Path(path).resolve()).lower()
    for proc in psutil.process_iter(["name"]):
        try:
            if (proc.info.get("name") or "") not in LIVE_PROC_NAMES:
                continue
            for f in proc.open_files():
                if f.path.lower() == target:
                    return True
        except (psutil.AccessDenied, psutil.NoSuchProcess):
            continue
    # Live is running but we couldn't prove ownership of this file.
    # Conservative default: refuse anyway.
    return True
