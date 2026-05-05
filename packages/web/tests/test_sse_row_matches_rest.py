"""Contract: a scan_row event's project_id is dereferenceable via
GET /api/projects/{id}, and the path the event carries matches the
REST snapshot's path. The front-end relies on this to patch its
TanStack cache from SSE without an extra round-trip lookup.
"""

from __future__ import annotations

import asyncio
import shutil
from pathlib import Path

from audio_core.indexer import driver
from audio_web.app import create_app
from fastapi.testclient import TestClient

FIX = Path(__file__).parents[2] / "core" / "tests" / "fixtures"


async def test_scan_row_project_id_matches_rest(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    proj = tmp_path / "Projects" / "contract Project"
    proj.mkdir(parents=True)
    shutil.copy(FIX / "tiny.als", proj / "c.als")
    (tmp_path / "data").mkdir()

    captured: dict = {}
    real_boot = driver.boot

    def spy_boot(*, db_path, root, bus, queue, sample_roots=None):
        captured["event_queue"] = bus.subscribe()
        real_boot(db_path=db_path, root=root, bus=bus, queue=queue, sample_roots=sample_roots)

    monkeypatch.setattr("audio_web.app.driver.boot", spy_boot)

    with TestClient(create_app()) as client:
        q = captured["event_queue"]
        # The bus subscribed inside TestClient's anyio portal loop, while the
        # test runs on pytest-asyncio's loop. asyncio.Queue.get() across loops
        # never wakes from put_nowait, so we drain via get_nowait + sleep.
        deadline = asyncio.get_event_loop().time() + 5.0
        seen_kinds: list[str] = []
        event: dict | None = None
        while asyncio.get_event_loop().time() < deadline and event is None:
            try:
                ev = q.get_nowait()
            except asyncio.QueueEmpty:
                await asyncio.sleep(0.05)
                continue
            seen_kinds.append(ev.get("kind", ""))
            if (
                ev.get("kind") == "scan_row"
                and ev.get("status") in {"new", "updated"}
                and "project_id" in ev
            ):
                event = ev

        assert event is not None, f"scan_row with project_id not seen; saw={seen_kinds}"
        assert isinstance(event["project_id"], int)
        assert "path" in event

        res = client.get(f"/api/projects/{event['project_id']}")
        assert res.status_code == 200, res.text
        body = res.json()
        assert body["id"] == event["project_id"]
        assert body["path"] == event["path"]
