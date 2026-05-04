from __future__ import annotations

import sqlite3
from pathlib import Path

from audio_core.actions.base import Action
from audio_core.journal.manifest import write_batch


def run_batch(
    conn: sqlite3.Connection,
    actions: list[Action],
    *,
    actor: str,
    journal_dir: str | Path,
) -> str:
    """Validate every action first, then execute them in order. All-or-nothing on
    validation; once execute starts, partial failures leave a partial journal.
    Returns the batch id."""
    for a in actions:
        a.validate(conn)
    entries = [a.execute(conn) for a in actions]
    return write_batch(journal_dir, actor=actor, actions=entries)
