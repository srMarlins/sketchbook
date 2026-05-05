import asyncio
import pytest
from audio_core.indexer.events import EventBus


@pytest.mark.asyncio
async def test_subscribers_get_events():
    bus = EventBus()
    sub = bus.subscribe()
    bus.publish({"kind": "ping", "n": 1})
    bus.publish({"kind": "ping", "n": 2})
    e1 = await asyncio.wait_for(sub.get(), 1)
    e2 = await asyncio.wait_for(sub.get(), 1)
    assert (e1["n"], e2["n"]) == (1, 2)


@pytest.mark.asyncio
async def test_late_subscriber_only_gets_future_events():
    bus = EventBus()
    bus.publish({"kind": "ping", "n": 0})
    sub = bus.subscribe()
    bus.publish({"kind": "ping", "n": 1})
    e = await asyncio.wait_for(sub.get(), 1)
    assert e["n"] == 1


@pytest.mark.asyncio
async def test_unsubscribe_stops_receiving():
    bus = EventBus()
    sub = bus.subscribe()
    bus.unsubscribe(sub)
    bus.publish({"kind": "ping", "n": 1})
    with pytest.raises(asyncio.TimeoutError):
        await asyncio.wait_for(sub.get(), 0.05)


@pytest.mark.asyncio
async def test_bounded_queue_drops_oldest():
    bus = EventBus(max_queue_size=2)
    sub = bus.subscribe()
    for n in range(5):
        bus.publish({"kind": "x", "n": n})
    # Yield once so the publish thread-safe scheduling can drain.
    await asyncio.sleep(0)
    events = []
    while not sub.empty():
        events.append(sub.get_nowait())
    assert any(e["kind"] == "dropped" for e in events)
    nums = [e["n"] for e in events if e["kind"] == "x"]
    # Most recent two events kept.
    assert nums[-2:] == [3, 4]


def test_subscribe_outside_running_loop_raises():
    bus = EventBus()
    import pytest
    with pytest.raises(RuntimeError):
        bus.subscribe()


@pytest.mark.asyncio
async def test_publish_skips_dead_subscriber_loop():
    """A subscriber whose loop has closed must not break publish for others."""
    import asyncio
    bus = EventBus()
    live = bus.subscribe()
    # Manually inject a dead-loop subscriber: a queue paired with a closed loop.
    dead_loop = asyncio.new_event_loop()
    dead_q: asyncio.Queue[dict] = asyncio.Queue()
    dead_loop.close()
    with bus._lock:
        bus._subs.append((dead_loop, dead_q))
    bus.publish({"kind": "ping", "n": 1})
    # The live subscriber still receives the event.
    e = await asyncio.wait_for(live.get(), 1)
    assert e["n"] == 1
    # The dead subscriber was reaped.
    with bus._lock:
        assert all(qq is not dead_q for _, qq in bus._subs)
