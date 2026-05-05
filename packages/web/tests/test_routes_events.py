"""Tests for the SSE /api/events route.

httpx's ``ASGITransport`` and Starlette's ``TestClient`` both buffer the full
response before returning, so neither can drive a long-lived streaming
endpoint in-process. Instead we drive the inner async generator
(:func:`audio_web.routes_events.event_stream`) directly and verify the
plumbing into FastAPI separately.
"""

from __future__ import annotations

import asyncio
import json

from audio_core.indexer.events import EventBus
from audio_web.app import create_app
from audio_web.routes_events import event_stream
from fastapi.testclient import TestClient


async def _never_disconnected() -> bool:
    return False


async def test_hello_on_connect():
    bus = EventBus()
    gen = event_stream(bus, _never_disconnected)
    try:
        first = await asyncio.wait_for(gen.__anext__(), timeout=1.0)
    finally:
        await gen.aclose()
    assert first["event"] == "hello"
    payload = json.loads(first["data"])
    assert payload["kind"] == "hello"
    assert "ts" in payload


async def test_bus_event_reaches_client():
    bus = EventBus()
    gen = event_stream(bus, _never_disconnected)
    try:
        # Drain hello so the generator is parked on queue.get().
        await asyncio.wait_for(gen.__anext__(), timeout=1.0)
        assert len(bus._subs) == 1
        bus.publish({"kind": "scan_started", "n": 7})
        msg = await asyncio.wait_for(gen.__anext__(), timeout=1.0)
    finally:
        await gen.aclose()
    assert msg["event"] == "scan_started"
    assert json.loads(msg["data"]) == {"kind": "scan_started", "n": 7}


async def test_disconnect_unsubscribes():
    bus = EventBus()
    assert len(bus._subs) == 0
    gen = event_stream(bus, _never_disconnected)
    await asyncio.wait_for(gen.__anext__(), timeout=1.0)
    assert len(bus._subs) == 1
    # Closing the generator runs the ``finally`` branch where we unsubscribe.
    await gen.aclose()
    assert len(bus._subs) == 0


async def test_disconnect_short_circuits_loop():
    """is_disconnected returning True should bail before the next queue.get()."""
    bus = EventBus()
    flag = {"disconnected": False}

    async def is_disconnected() -> bool:
        return flag["disconnected"]

    gen = event_stream(bus, is_disconnected)
    try:
        # Hello.
        await asyncio.wait_for(gen.__anext__(), timeout=1.0)
        # Flip the flag *before* asking for the next event so the loop sees it.
        flag["disconnected"] = True
        # The generator should return cleanly (StopAsyncIteration).
        try:
            await asyncio.wait_for(gen.__anext__(), timeout=1.0)
        except StopAsyncIteration:
            pass
        else:
            raise AssertionError("expected generator to stop after disconnect")
    finally:
        await gen.aclose()
    assert len(bus._subs) == 0


def test_route_registered_on_app():
    """The /api/events route must be wired into create_app()."""
    app = create_app()
    paths = {getattr(r, "path", None) for r in app.router.routes}
    assert "/api/events" in paths


def test_existing_routes_unaffected(tmp_path, monkeypatch):
    """Adding the SSE router must not break the existing /api/health route."""
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    client = TestClient(create_app())
    res = client.get("/api/health")
    assert res.status_code == 200
    assert res.json() == {"ok": True}
