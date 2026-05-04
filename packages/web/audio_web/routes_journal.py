from __future__ import annotations

from audio_core.actions.undo import undo_batch
from audio_core.config import db_path, journal_dir
from audio_core.db.connection import open_db
from audio_core.journal.manifest import list_batches, read_batch
from fastapi import APIRouter, HTTPException

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
        undo_batch(conn, journal_dir(), batch_id)
    except (FileNotFoundError, NotImplementedError) as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    return {"undone": batch_id}
