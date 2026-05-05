from __future__ import annotations

import shutil
import sqlite3
from pathlib import Path

from audio_core.actions.set_tags import _set_tags
from audio_core.journal.manifest import read_batch, write_undo


class UndoError(RuntimeError):
    """Raised when undo cannot proceed safely (e.g. destination occupied,
    source missing AND the undone path also missing — neither state is intact)."""


def _safe_move(src: str, dst: str) -> None:
    """Filesystem move with idempotent guards.

    - If src exists and dst doesn't: do the move.
    - If src is missing but dst already exists: treat as already-undone (no-op).
    - If src exists and dst exists: bail — won't merge or overwrite.
    - If both missing: bail — nothing we can do.
    """
    src_p, dst_p = Path(src), Path(dst)
    src_exists, dst_exists = src_p.exists(), dst_p.exists()
    if src_exists and not dst_exists:
        dst_p.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(src, dst)
        return
    if not src_exists and dst_exists:
        return  # already undone
    if src_exists and dst_exists:
        raise UndoError(
            f"undo blocked: both source ({src}) and destination ({dst}) exist; refusing to merge"
        )
    raise UndoError(f"undo blocked: neither source ({src}) nor destination ({dst}) exists")


def _undo_fs_action(conn: sqlite3.Connection, entry: dict, action_type: str) -> None:
    if entry.get("noop"):
        return
    from_, to = entry["from_"], entry["to"]
    _safe_move(to, from_)

    if "path_before" in entry:
        # New format: explicit path values, no string substitution.
        conn.execute(
            "UPDATE projects SET parent_dir=?, path=? WHERE id=?",
            (from_, entry["path_before"], entry["project_id"]),
        )
    else:
        # Legacy format (pre-2026-05-04 fix): substring-replace in path.
        conn.execute(
            "UPDATE projects SET parent_dir=?, path=replace(path, ?, ?) WHERE id=?",
            (from_, to, from_, entry["project_id"]),
        )
    if action_type == "ArchiveProject":
        conn.execute("UPDATE projects SET is_archived=0 WHERE id=?", (entry["project_id"],))


def undo_batch(
    conn: sqlite3.Connection,
    journal_dir: str | Path,
    batch_id: str,
    *,
    actor: str = "user",
) -> str | None:
    """Reverse every action in a batch, in reverse order.

    Idempotent: if the batch was already undone (filesystem state already
    reflects the pre-batch shape), this is a no-op and returns None.

    Returns the bid of the journal entry recording this undo, or None if
    nothing was actually reversed (already undone).

    Refuses to undo:
    - a batch with status="undo" (can't undo an undo from this entry point —
      to redo, re-run the original proposal)
    - a batch already marked as undone (we record undo entries with
      `undone_bid` so a second call sees it).
    """
    batch = read_batch(journal_dir, batch_id)
    if batch.get("status") == "undo":
        raise UndoError(f"batch {batch_id} is itself an undo entry; cannot undo it")
    # Walk forward through the journal looking for a prior undo of this batch.
    from audio_core.journal.manifest import list_batches

    for b in list_batches(journal_dir):
        if b.get("status") == "undo" and b.get("undone_bid") == batch_id:
            raise UndoError(f"batch {batch_id} has already been undone by {b['batch_id']}")

    any_reversed = False
    for entry in reversed(batch.get("actions", [])):
        t = entry["type"]
        if t in {"RenameProject", "MoveProject", "ArchiveProject"}:
            _undo_fs_action(conn, entry, t)
            any_reversed = True
        elif t == "SetColorTag":
            conn.execute(
                "UPDATE projects SET color_tag=? WHERE id=?",
                (entry["before"], entry["project_id"]),
            )
            any_reversed = True
        elif t == "SetTags":
            _set_tags(conn, entry["project_id"], list(entry["before"]))
            any_reversed = True
        elif t == "RepairMacPaths":
            _undo_repair_mac_paths(entry, conn)
            any_reversed = True
        elif t == "RelinkMissingSamples":
            _undo_relink_missing_samples(entry, conn)
            any_reversed = True
        else:
            raise NotImplementedError(f"undo not implemented for action type {t!r}")
    conn.commit()
    if not any_reversed:
        return None
    return write_undo(journal_dir, actor=actor, undone_bid=batch_id)


def _undo_repair_mac_paths(entry: dict, conn: sqlite3.Connection) -> None:
    """Restore the .als from its .als.bak and re-scan to refresh the catalog row.

    The Ableton Project Info/ folder (if created by the repair) is left in place;
    undo doesn't delete directories. The .als.bak file is also left in place so a
    subsequent undo (or re-undo after redo) is cheap.
    """
    if entry.get("noop"):
        return
    from audio_core.scanner.scan import scan_one

    als = Path(entry["path"])
    bak = Path(entry["backup"])
    if not bak.exists():
        raise FileNotFoundError(
            f"cannot undo RepairMacPaths: backup missing at {bak}"
        )
    shutil.copy2(bak, als)
    scan_one(conn, als)


def _undo_relink_missing_samples(entry: dict, conn: sqlite3.Connection) -> None:
    """Restore the .als from its .als.bak and re-scan so project_samples
    revert to the pre-relink (missing) state."""
    if entry.get("noop"):
        return
    from audio_core.scanner.scan import scan_one

    als = Path(entry["path"])
    bak = Path(entry["backup"])
    if not bak.exists():
        raise FileNotFoundError(
            f"cannot undo RelinkMissingSamples: backup missing at {bak}"
        )
    shutil.copy2(bak, als)
    scan_one(conn, als)
