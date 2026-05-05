from __future__ import annotations

import logging
import sqlite3

from audio_core.actions.undo import UndoError, undo_batch
from audio_core.config import db_path, journal_dir
from audio_core.db.connection import open_db
from audio_core.journal.manifest import list_batches, read_batch
from fastapi import APIRouter, HTTPException

_log = logging.getLogger("audio_web")
router = APIRouter(prefix="/api/journal", tags=["journal"])


@router.get("")
def list_journal(limit: int = 200) -> list[dict]:
    return list_batches(journal_dir())[-limit:]


@router.get("/{batch_id}")
def get_batch(batch_id: str) -> dict:
    try:
        return read_batch(journal_dir(), batch_id)
    except FileNotFoundError as e:
        raise HTTPException(status_code=404, detail=f"no batch id={batch_id}") from e


@router.post("/{batch_id}/undo")
def undo(batch_id: str) -> dict:
    try:
        read_batch(journal_dir(), batch_id)
    except FileNotFoundError as e:
        raise HTTPException(status_code=404, detail=f"no batch id={batch_id}") from e
    conn = open_db(db_path())
    try:
        undo_bid = undo_batch(conn, journal_dir(), batch_id)
    except UndoError as e:
        _log.warning("undo refused batch_id=%s: %s", batch_id, e)
        raise HTTPException(status_code=409, detail=str(e)) from e
    except (FileNotFoundError, NotImplementedError, OSError, sqlite3.Error) as e:
        _log.error("undo failed batch_id=%s: %s", batch_id, e)
        raise HTTPException(status_code=400, detail=str(e)) from e
    _log.info("undo batch_id=%s undo_entry=%s", batch_id, undo_bid)
    return {"undone": batch_id, "undo_batch_id": undo_bid}
