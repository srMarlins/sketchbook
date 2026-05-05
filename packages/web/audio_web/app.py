from __future__ import annotations

import logging
import os
import sqlite3
import sys
import traceback
from contextlib import asynccontextmanager
from logging.handlers import RotatingFileHandler
from pathlib import Path

from audio_core.config import db_path, journal_dir, projects_root, sample_roots, workspace_root
from audio_core.indexer import driver
from audio_core.indexer.events import EventBus
from audio_core.indexer.queue import JobQueue
from audio_core.indexer.watcher import FsWatcher
from audio_core.journal.manifest import reconcile_pending
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

from audio_web.routes_events import router as events_router
from audio_web.routes_home import router as home_router
from audio_web.routes_journal import router as journal_router
from audio_web.routes_projects import router as projects_router
from audio_web.routes_proposals import router as proposals_router
from audio_web.routes_repair import router as repair_router

LOGGER_NAME = "audio_web"


def _setup_logging() -> logging.Logger:
    """Configure structured logging once. Logs go to data/logs/audio.log
    with rotation (5MB × 5 files) and to stderr at INFO."""
    logger = logging.getLogger(LOGGER_NAME)
    if logger.handlers:
        return logger
    logger.setLevel(logging.INFO)
    fmt = logging.Formatter(
        "%(asctime)s %(levelname)-7s %(name)s: %(message)s",
        datefmt="%Y-%m-%dT%H:%M:%S",
    )
    log_dir = workspace_root() / "data" / "logs"
    try:
        log_dir.mkdir(parents=True, exist_ok=True)
        fh = RotatingFileHandler(
            log_dir / "audio.log",
            maxBytes=5 * 1024 * 1024,
            backupCount=5,
            encoding="utf-8",
        )
        fh.setFormatter(fmt)
        logger.addHandler(fh)
    except OSError:
        pass
    sh = logging.StreamHandler(sys.stderr)
    sh.setFormatter(fmt)
    sh.setLevel(logging.WARNING)
    logger.addHandler(sh)
    logger.propagate = False
    return logger


def create_app() -> FastAPI:
    logger = _setup_logging()

    @asynccontextmanager
    async def _lifespan(app: FastAPI):
        try:
            reconciled = reconcile_pending(journal_dir())
            if reconciled:
                logger.warning(
                    "reconcile_pending: promoted %d interrupted batches: %s",
                    len(reconciled),
                    reconciled,
                )
        except OSError as e:
            logger.error("reconcile_pending failed: %s", e)

        bus = EventBus()
        queue = JobQueue()
        queue.start()
        app.state.event_bus = bus
        app.state.job_queue = queue
        roots = sample_roots()
        driver.boot(
            db_path=db_path(),
            root=projects_root(),
            bus=bus,
            queue=queue,
            sample_roots=roots,
        )
        watcher = FsWatcher(
            root=projects_root(),
            queue=queue,
            bus=bus,
            db_path=db_path(),
            sample_roots=roots,
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

    app = FastAPI(title="audio-web", version="0.1.0dev", lifespan=_lifespan)

    @app.exception_handler(sqlite3.Error)
    async def _sqlite_handler(request: Request, exc: sqlite3.Error) -> JSONResponse:
        logger.error("sqlite error on %s: %s\n%s", request.url.path, exc, traceback.format_exc())
        return JSONResponse(status_code=500, content={"detail": f"database error: {exc}"})

    @app.exception_handler(OSError)
    async def _os_handler(request: Request, exc: OSError) -> JSONResponse:
        logger.error("os error on %s: %s\n%s", request.url.path, exc, traceback.format_exc())
        return JSONResponse(status_code=500, content={"detail": f"filesystem error: {exc}"})

    @app.get("/api/health")
    def health() -> dict:
        return {"ok": True}

    app.include_router(home_router)
    app.include_router(projects_router)
    app.include_router(proposals_router)
    app.include_router(repair_router)
    app.include_router(journal_router)
    app.include_router(events_router)

    # In packaged-app mode (Tauri shell), the bundled React build lives at a
    # known path supplied via $AUDIO_WEB_DIST. In dev mode (`npm run dev`),
    # Vite serves the frontend on its own port and proxies `/api/*` to us;
    # we don't mount static files. Mounting is last so /api/* routes still
    # win the path match.
    dist = _frontend_dist_dir()
    if dist is not None:
        app.mount("/", StaticFiles(directory=dist, html=True), name="frontend")
        logger.info("serving frontend from %s", dist)
    return app


def _frontend_dist_dir() -> Path | None:
    """Resolve where to find the built React app, or None to skip mounting."""
    env = os.environ.get("AUDIO_WEB_DIST")
    if env:
        p = Path(env)
        return p if p.is_dir() else None
    fallback = workspace_root() / "web" / "dist"
    return fallback if fallback.is_dir() else None
