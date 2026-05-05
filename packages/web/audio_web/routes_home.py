"""GET /api/home — discovery shelves for the home page.
GET /api/categories/{shelf_id} — full ungrouped list for a category detail view."""

from __future__ import annotations

import time

from audio_core.config import db_path
from audio_core.db.connection import open_db
from audio_web.home import category_full, compute_shelves
from audio_web.schemas import HomeResponse
from fastapi import APIRouter, HTTPException

router = APIRouter(tags=["home"])


@router.get("/api/home")
def home() -> HomeResponse:
    conn = open_db(db_path())
    return HomeResponse(shelves=compute_shelves(conn))


@router.get("/api/categories/{shelf_id}")
def category(shelf_id: str) -> list[dict]:
    """Full ungrouped list for one of the home shelves. The home endpoint
    caps each shelf at a small N for fast loading; this endpoint returns
    everything that qualifies, sorted in the shelf's natural order.

    Returns raw .als rows; callers that want one-per-project-folder should
    group client-side via deriveProjectGroups."""
    conn = open_db(db_path())
    try:
        return category_full(conn, shelf_id, now=time.time())
    except KeyError as e:
        raise HTTPException(status_code=404, detail=f"unknown category: {e.args[0]}")
