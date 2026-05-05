"""End-to-end test of /api/events against a real uvicorn server.

The unit tests in test_routes_events.py drive the inner generator directly
because httpx's ASGITransport (and Starlette's TestClient) buffer the full
response body before returning, which deadlocks against a long-lived SSE
stream that never sets ``more_body=False``. To actually exercise the wire
framing and lifespan/bus/route plumbing, we run uvicorn on a loopback port
in a background thread and connect with a streaming httpx client.
"""

from __future__ import annotations

import shutil
import socket
import threading
import time
from collections.abc import Iterator
from pathlib import Path

import httpx
import pytest
import uvicorn

FIX = Path(__file__).parents[2] / "core" / "tests" / "fixtures"


def _free_port() -> int:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


@pytest.fixture
def live_server(tmp_path, monkeypatch) -> Iterator[str]:
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    (tmp_path / "Projects").mkdir()
    (tmp_path / "data").mkdir()

    port = _free_port()
    config = uvicorn.Config(
        app="audio_web.app:create_app",
        factory=True,
        host="127.0.0.1",
        port=port,
        log_level="error",
        loop="asyncio",
        lifespan="on",
    )
    server = uvicorn.Server(config)
    thread = threading.Thread(target=server.run, name="test-uvicorn", daemon=True)
    thread.start()

    deadline = time.monotonic() + 5.0
    while time.monotonic() < deadline:
        if server.started:
            break
        time.sleep(0.05)
    else:
        server.should_exit = True
        thread.join(timeout=2.0)
        pytest.fail("uvicorn did not become ready within 5s")

    try:
        yield f"http://127.0.0.1:{port}"
    finally:
        server.should_exit = True
        thread.join(timeout=5.0)


class SSEReader:
    """Wraps a streaming response so multiple ``read_until`` calls share one
    underlying iterator. Each call resumes from the same cursor; httpx forbids
    re-iterating ``aiter_bytes`` on a single response."""

    def __init__(self, resp: httpx.Response) -> None:
        self._iter = resp.aiter_bytes()
        self.buf = b""

    async def read_until(self, needle: bytes, *, timeout: float) -> bytes:
        if needle in self.buf:
            return self.buf
        deadline = time.monotonic() + timeout
        async for chunk in self._iter:
            self.buf += chunk
            if needle in self.buf:
                return self.buf
            if time.monotonic() > deadline:
                break
        raise AssertionError(
            f"did not see {needle!r} within {timeout}s; got {self.buf[:512]!r}"
        )


async def test_sse_streams_hello_over_real_http(live_server: str):
    """The basic plumbing: connecting yields the hello frame with the right
    content-type, proving ASGI streaming works (which httpx ASGITransport
    cannot do — its body buffering deadlocks against an unending stream)."""
    async with httpx.AsyncClient(timeout=10.0) as client:
        async with client.stream("GET", f"{live_server}/api/events") as resp:
            assert resp.status_code == 200
            assert resp.headers["content-type"].startswith("text/event-stream")
            reader = SSEReader(resp)
            buf = await reader.read_until(b"event: hello", timeout=2.0)
    assert b'"kind": "hello"' in buf


async def test_sse_delivers_watcher_event_to_connected_client(
    live_server: str, tmp_path: Path
):
    """Bus → SSE end-to-end: drop a real .als into Projects/ after we are
    subscribed; the FsWatcher debounces, IncrementalScan runs, and the
    resulting scan_row event must reach the connected client."""
    async with httpx.AsyncClient(timeout=15.0) as client:
        async with client.stream("GET", f"{live_server}/api/events") as resp:
            assert resp.status_code == 200
            reader = SSEReader(resp)
            await reader.read_until(b"event: hello", timeout=2.0)
            proj = tmp_path / "Projects" / "live Project"
            proj.mkdir(parents=True)
            shutil.copy(FIX / "tiny.als", proj / "x.als")
            buf = await reader.read_until(b"event: scan_row", timeout=10.0)
    assert b'"kind": "scan_row"' in buf
    assert b'"status": "new"' in buf or b'"status": "updated"' in buf


async def test_sse_disconnect_cleans_up_subscription(live_server: str):
    """Closing the streaming response unsubscribes the queue from the bus.
    We can't read the bus directly across threads, but we verify the server
    keeps responding to subsequent connects (no leaked state, no hang)."""
    async with httpx.AsyncClient(timeout=10.0) as client:
        async with client.stream("GET", f"{live_server}/api/events") as resp:
            await SSEReader(resp).read_until(b"event: hello", timeout=2.0)
        async with client.stream("GET", f"{live_server}/api/events") as resp2:
            await SSEReader(resp2).read_until(b"event: hello", timeout=2.0)
            assert resp2.status_code == 200
