from __future__ import annotations

from fastapi import FastAPI

from audio_web.routes_home import router as home_router
from audio_web.routes_journal import router as journal_router
from audio_web.routes_open import router as open_router
from audio_web.routes_projects import router as projects_router
from audio_web.routes_proposals import router as proposals_router


def create_app() -> FastAPI:
    app = FastAPI(title="audio-web", version="0.1.0dev")

    @app.get("/api/health")
    def health() -> dict:
        return {"ok": True}

    app.include_router(home_router)
    app.include_router(projects_router)
    app.include_router(open_router)
    app.include_router(proposals_router)
    app.include_router(journal_router)
    return app
