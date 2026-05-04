"""GET /api/home — discovery shelves for the home page."""

from __future__ import annotations

from audio_core.config import db_path
from audio_core.db.connection import open_db
from audio_web.home import compute_shelves
from audio_web.schemas import HomeResponse
from fastapi import APIRouter

router = APIRouter(prefix="/api/home", tags=["home"])


@router.get("")
def home() -> HomeResponse:
    conn = open_db(db_path())
    return HomeResponse(shelves=compute_shelves(conn))
