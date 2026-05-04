from __future__ import annotations

from fastapi import FastAPI

from audio_web.routes_projects import router as projects_router


def create_app() -> FastAPI:
    app = FastAPI(title="audio-web", version="0.1.0dev")

    @app.get("/api/health")
    def health() -> dict:
        return {"ok": True}

    app.include_router(projects_router)
    return app
