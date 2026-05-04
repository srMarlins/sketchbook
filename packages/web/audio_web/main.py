from __future__ import annotations

import uvicorn

from audio_web.app import create_app


def run() -> None:
    uvicorn.run(create_app(), host="127.0.0.1", port=7878)
