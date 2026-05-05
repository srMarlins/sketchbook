"""GET /api/home — discovery shelves for the home page.
GET /api/categories/{shelf_id} — full ungrouped list for a category detail view."""

from __future__ import annotations

import time
from typing import Annotated

from audio_core.config import db_path
from audio_core.db.connection import open_db
from audio_core.db.projects import InvalidCursor
from audio_web.home import category_full, compute_shelves
from audio_web.schemas import HomeResponse
from fastapi import APIRouter, HTTPException, Query

router = APIRouter(tags=["home"])


@router.get("/api/home")
def home() -> HomeResponse:
    conn = open_db(db_path())
    return HomeResponse(shelves=compute_shelves(conn))


@router.get("/api/categories/{shelf_id}")
def category(
    shelf_id: str,
    limit: Annotated[int, Query(ge=1, le=500)] = 200,
    cursor: Annotated[str | None, Query()] = None,
) -> dict:
    """Cursor-paginated category list. Returns:
        {"items": [...], "next_cursor": str | None}
    Forward `next_cursor` as the next page's `cursor` query param. When
    `next_cursor` is null the iteration is exhausted.

    Returns raw .als rows; callers that want one-per-project-folder should
    group client-side via deriveProjectGroups."""
    conn = open_db(db_path())
    try:
        return category_full(conn, shelf_id, now=time.time(), limit=limit, cursor=cursor)
    except KeyError as e:
        shelf = e.args[0] if e.args else shelf_id
        raise HTTPException(status_code=404, detail=f"unknown category: {shelf}") from e
    except InvalidCursor as e:
        raise HTTPException(status_code=400, detail=f"invalid cursor: {e}") from e
