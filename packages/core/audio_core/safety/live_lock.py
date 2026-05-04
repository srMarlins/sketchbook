from __future__ import annotations

from pathlib import Path

import psutil

LIVE_PROC_NAMES = frozenset({"Ableton Live.exe", "Live.exe", "Ableton Live"})


def is_open_in_live(path: str | Path) -> bool:
    """Best-effort check: is an Ableton Live process currently holding `path` open?

    On Windows, `psutil.Process.open_files()` typically requires elevated privileges
    to enumerate file handles for processes you don't own. AccessDenied is treated as
    "unknown — not blocked"; a hard guarantee would require an OS-level lock check.
    Treat this as advisory only; downstream actions still validate via filesystem.
    """
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
    return False
