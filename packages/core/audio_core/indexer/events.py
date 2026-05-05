from __future__ import annotations

import asyncio
import threading
from typing import Any


class EventBus:
    """Thread-safe pub/sub. publish() callable from any thread; subscribe()
    returns an asyncio.Queue that must be consumed from the asyncio loop bound
    when subscribe() was called. Bounded queues drop oldest with a 'dropped'
    marker so a slow subscriber can't backpressure publishers.
    """

    def __init__(self, *, max_queue_size: int = 256) -> None:
        self._max = max_queue_size
        self._subs: list[tuple[asyncio.AbstractEventLoop, asyncio.Queue[dict[str, Any]]]] = []
        self._lock = threading.Lock()

    def subscribe(self) -> asyncio.Queue[dict[str, Any]]:
        loop = asyncio.get_running_loop()
        # +1 reserves a slot for a coalesced 'dropped' marker so the marker
        # never displaces the freshest event when the consumer is slow.
        q: asyncio.Queue[dict[str, Any]] = asyncio.Queue(maxsize=self._max + 1)
        with self._lock:
            self._subs.append((loop, q))
        return q

    def unsubscribe(self, q: asyncio.Queue[dict[str, Any]]) -> None:
        with self._lock:
            self._subs = [(l, qq) for l, qq in self._subs if qq is not q]

    def publish(self, event: dict[str, Any]) -> None:
        with self._lock:
            subs = list(self._subs)
        dead: list[asyncio.Queue[dict[str, Any]]] = []
        for loop, q in subs:
            try:
                loop.call_soon_threadsafe(self._push, q, event)
            except RuntimeError:
                dead.append(q)
        if dead:
            with self._lock:
                self._subs = [(l, qq) for l, qq in self._subs if qq not in dead]

    @staticmethod
    def _push(q: asyncio.Queue[dict[str, Any]], event: dict[str, Any]) -> None:
        # Internal deque peek lets us coalesce 'dropped' markers so a single
        # marker indicates "you missed events" without filling the queue with
        # markers. Reserve two slots when adding a fresh marker, one otherwise.
        internal: Any = q._queue  # type: ignore[attr-defined]
        marker_at_head = bool(internal) and isinstance(internal[0], dict) \
            and internal[0].get("kind") == "dropped"
        reserve = 1 if marker_at_head else 2
        dropped = False
        while q.qsize() > q.maxsize - reserve:
            try:
                q.get_nowait()
                dropped = True
            except asyncio.QueueEmpty:
                break
        if dropped and not marker_at_head:
            try:
                q.put_nowait({"kind": "dropped"})
            except asyncio.QueueFull:
                pass
        try:
            q.put_nowait(event)
        except asyncio.QueueFull:
            pass
