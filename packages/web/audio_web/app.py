from __future__ import annotations

from fastapi import FastAPI


def create_app() -> FastAPI:
    app = FastAPI(title="audio-web", version="0.1.0dev")

    @app.get("/api/health")
    def health() -> dict:
        return {"ok": True}

    return app
