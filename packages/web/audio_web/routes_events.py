"""Server-Sent Events stream of indexer events.

Subscribers to ``/api/events`` receive a JSON-encoded SSE stream. The first
event is always a ``hello`` snapshot so clients can detect a successful
connection. Thereafter every event published on ``app.state.event_bus`` is
forwarded with its ``kind`` as the SSE ``event:`` field.

Tested two ways: ``test_routes_events.py`` drives :func:`event_stream`
(the inner async generator) directly for fast logic coverage, and
``test_routes_events_e2e.py`` runs uvicorn on a loopback port for true
end-to-end framing + bus → wire delivery. The unit-level tests bypass the
ASGI layer because httpx's ``ASGITransport`` and Starlette's ``TestClient``
buffer the entire response before returning, so neither can drive an
unending stream in-process.
"""

from __future__ import annotations

import json
import time
from collections.abc import AsyncIterator, Awaitable, Callable
from typing import Any

from audio_core.indexer.events import EventBus
from fastapi import APIRouter, Request
from sse_starlette.sse import EventSourceResponse

router = APIRouter()


async def event_stream(
    bus: EventBus,
    is_disconnected: Callable[[], Awaitable[bool]],
) -> AsyncIterator[dict[str, Any]]:
    """Yield SSE message dicts for every event published on ``bus``.

    ``is_disconnected`` should be ``request.is_disconnected`` in production;
    tests can pass any awaitable that returns False until they want to bail.
    The subscription is always removed on exit.
    """
    queue = bus.subscribe()
    try:
        yield {"event": "hello", "data": json.dumps({"kind": "hello", "ts": time.time()})}
        while True:
            if await is_disconnected():
                return
            ev = await queue.get()
            yield {"event": ev.get("kind", "event"), "data": json.dumps(ev)}
    finally:
        bus.unsubscribe(queue)


@router.get("/api/events")
async def events(request: Request) -> EventSourceResponse:
    bus: EventBus = request.app.state.event_bus
    return EventSourceResponse(event_stream(bus, request.is_disconnected))
