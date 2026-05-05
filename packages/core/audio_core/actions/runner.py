from __future__ import annotations

import sqlite3
from pathlib import Path

from audio_core.actions.base import Action
from audio_core.journal.manifest import finalize, write_pending


def run_batch(
    conn: sqlite3.Connection,
    actions: list[Action],
    *,
    actor: str,
    journal_dir: str | Path,
    intent: list[dict] | None = None,
) -> str:
    """Validate every action first, then execute them in order.

    Crash safety: an intent log is written to <journal_dir>/pending/<bid>.json
    before any execute(). On full success the batch is finalized with
    status="complete". On per-action failure, the actions that DID succeed are
    finalized with status="partial" and the original exception re-raises.

    `intent` is the list of {type, args} dicts as submitted (typically from a
    Proposal). It is recorded so we can attribute partial/interrupted batches.
    Returns the batch id.
    """
    for a in actions:
        a.validate(conn)
    if intent is None:
        intent = [{"type": type(a).__name__, "args": {}} for a in actions]
    bid = write_pending(journal_dir, actor=actor, intent=intent)
    completed: list[dict] = []
    try:
        for idx, a in enumerate(actions):
            entry = a.execute(conn)
            completed.append(entry)
    except Exception as e:
        finalize(
            journal_dir,
            bid,
            actor=actor,
            actions=completed,
            status="partial",
            error=f"{type(e).__name__}: {e}",
            failed_at_index=idx,
            intent=intent,
        )
        raise
    finalize(journal_dir, bid, actor=actor, actions=completed, status="complete")
    return bid
