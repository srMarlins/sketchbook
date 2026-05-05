from __future__ import annotations

from contextlib import asynccontextmanager

from audio_core.config import db_path, projects_root
from audio_core.indexer import driver
from audio_core.indexer.events import EventBus
from audio_core.indexer.queue import JobQueue
from audio_core.indexer.watcher import FsWatcher
from fastapi import FastAPI

from audio_web.routes_events import router as events_router
from audio_web.routes_home import router as home_router
from audio_web.routes_journal import router as journal_router
from audio_web.routes_open import router as open_router
from audio_web.routes_projects import router as projects_router
from audio_web.routes_proposals import router as proposals_router


@asynccontextmanager
async def lifespan(app: FastAPI):
    bus = EventBus()
    queue = JobQueue()
    queue.start()
    app.state.event_bus = bus
    app.state.job_queue = queue
    driver.boot(db_path=db_path(), root=projects_root(), bus=bus, queue=queue)
    watcher = FsWatcher(
        root=projects_root(),
        queue=queue,
        bus=bus,
        db_path=db_path(),
    )
    try:
        watcher.start()
        app.state.fs_watcher = watcher
        yield
    finally:
        try:
            watcher.stop()
        finally:
            queue.shutdown(wait=True)


def create_app() -> FastAPI:
    app = FastAPI(title="audio-web", version="0.1.0dev", lifespan=lifespan)

    @app.get("/api/health")
    def health() -> dict:
        return {"ok": True}

    app.include_router(home_router)
    app.include_router(projects_router)
    app.include_router(open_router)
    app.include_router(proposals_router)
    app.include_router(journal_router)
    app.include_router(events_router)
    return app
