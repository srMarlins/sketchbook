"""Append-only journal of executed action batches.

Crash safety pattern: every batch first writes an intent log to
`<journal_dir>/pending/<bid>.json`. After the action loop runs (whether all
succeed or one fails partway), the runner calls `finalize` which atomically
renames a tmp file into `<journal_dir>/<bid>.json` and removes the pending
record. If the process crashes between intent and finalize, the pending file
remains as forensic evidence and `reconcile_pending` (called at startup) emits
an "interrupted" entry into the journal so the user is alerted.

Entry status field:
- "complete" — every action in the batch ran to completion
- "partial" — at least one action ran but a later one failed; only the
  successful actions are in `actions[]`. The user can still undo those.
- "interrupted" — the process died between intent and finalize. We have no
  evidence which actions, if any, mutated state.
- "undo" — this entry records that another batch was reversed (see write_undo).
"""

from __future__ import annotations

import json
import os
import tempfile
import uuid
from datetime import UTC, datetime
from pathlib import Path

PENDING_DIRNAME = "pending"


def _gen_bid() -> str:
    return f"{datetime.now(UTC).strftime('%Y-%m-%dT%H-%M-%S')}_{uuid.uuid4().hex[:8]}"


def _atomic_write_json(path: Path, payload: dict) -> None:
    """Write JSON atomically — temp file in same dir, then os.replace."""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".tmp_", suffix=".json", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(payload, f, indent=2)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    except Exception:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def write_pending(journal_dir: str | Path, *, actor: str, intent: list[dict]) -> str:
    """Record intent before any action executes. Returns the batch id."""
    d = Path(journal_dir) / PENDING_DIRNAME
    bid = _gen_bid()
    payload = {
        "batch_id": bid,
        "actor": actor,
        "status": "pending",
        "intent": intent,
        "created_at": datetime.now(UTC).isoformat(),
    }
    _atomic_write_json(d / f"{bid}.json", payload)
    return bid


def finalize(
    journal_dir: str | Path,
    bid: str,
    *,
    actor: str,
    actions: list[dict],
    status: str = "complete",
    error: str | None = None,
    failed_at_index: int | None = None,
    intent: list[dict] | None = None,
) -> None:
    """Atomically write the final batch record and remove the pending file."""
    d = Path(journal_dir)
    payload: dict = {
        "batch_id": bid,
        "actor": actor,
        "status": status,
        "actions": actions,
        "finalized_at": datetime.now(UTC).isoformat(),
    }
    if error is not None:
        payload["error"] = error
    if failed_at_index is not None:
        payload["failed_at_index"] = failed_at_index
    if intent is not None:
        payload["intent"] = intent
    _atomic_write_json(d / f"{bid}.json", payload)
    pending = d / PENDING_DIRNAME / f"{bid}.json"
    try:
        pending.unlink()
    except FileNotFoundError:
        pass


def write_batch(journal_dir: str | Path, *, actor: str, actions: list[dict]) -> str:
    """Legacy single-step write. Used by tests that bypass the runner.
    Production code goes through write_pending + finalize."""
    bid = _gen_bid()
    payload = {
        "batch_id": bid,
        "actor": actor,
        "status": "complete",
        "actions": actions,
    }
    _atomic_write_json(Path(journal_dir) / f"{bid}.json", payload)
    return bid


def write_undo(journal_dir: str | Path, *, actor: str, undone_bid: str) -> str:
    """Record that a batch was reversed."""
    bid = _gen_bid()
    payload = {
        "batch_id": bid,
        "actor": actor,
        "status": "undo",
        "undone_bid": undone_bid,
        "actions": [],
        "finalized_at": datetime.now(UTC).isoformat(),
    }
    _atomic_write_json(Path(journal_dir) / f"{bid}.json", payload)
    return bid


def read_batch(journal_dir: str | Path, batch_id: str) -> dict:
    return json.loads((Path(journal_dir) / f"{batch_id}.json").read_text(encoding="utf-8"))


def list_batches(journal_dir: str | Path) -> list[dict]:
    d = Path(journal_dir)
    if not d.exists():
        return []
    return [
        json.loads(p.read_text(encoding="utf-8"))
        for p in sorted(d.glob("*.json"))
        if p.is_file()
    ]


def list_pending(journal_dir: str | Path) -> list[dict]:
    d = Path(journal_dir) / PENDING_DIRNAME
    if not d.exists():
        return []
    return [
        json.loads(p.read_text(encoding="utf-8"))
        for p in sorted(d.glob("*.json"))
        if p.is_file()
    ]


def reconcile_pending(journal_dir: str | Path) -> list[str]:
    """At startup, any pending entries without a corresponding final entry
    represent a process that crashed mid-batch. Promote them to status="interrupted"
    so the user can see them in the journal UI. Returns the list of bids reconciled.
    """
    d = Path(journal_dir)
    pending_dir = d / PENDING_DIRNAME
    if not pending_dir.exists():
        return []
    reconciled: list[str] = []
    for p in sorted(pending_dir.glob("*.json")):
        try:
            data = json.loads(p.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        bid = data.get("batch_id") or p.stem
        if (d / f"{bid}.json").exists():
            try:
                p.unlink()
            except OSError:
                pass
            continue
        finalize(
            d,
            bid,
            actor=data.get("actor", "unknown"),
            actions=[],
            status="interrupted",
            error="process exited between intent log and finalize",
            intent=data.get("intent"),
        )
        reconciled.append(bid)
    return reconciled
