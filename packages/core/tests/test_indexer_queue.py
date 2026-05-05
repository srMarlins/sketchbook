import logging
import threading
import time

from audio_core.indexer.queue import JobQueue


def test_jobs_run_in_submission_order():
    q = JobQueue()
    out: list[int] = []
    q.start()
    for i in range(3):
        q.submit(lambda i=i: out.append(i))
    q.shutdown(wait=True)
    assert out == [0, 1, 2]


def test_only_one_job_runs_at_a_time():
    q = JobQueue()
    q.start()
    state = {"count": 0, "max": 0}
    lock = threading.Lock()

    def slow():
        with lock:
            state["count"] += 1
            state["max"] = max(state["max"], state["count"])
        time.sleep(0.05)
        with lock:
            state["count"] -= 1

    for _ in range(5):
        q.submit(slow)
    q.shutdown(wait=True)
    assert state["max"] == 1


def test_exception_does_not_kill_worker(caplog):
    caplog.set_level(logging.ERROR, logger="audio_core.indexer.queue")
    q = JobQueue()
    out: list[str] = []
    q.start()

    def boom():
        raise RuntimeError("boom")

    q.submit(boom)
    q.submit(lambda: out.append("after"))
    q.shutdown(wait=True)
    assert out == ["after"]
    assert any("boom" in r.getMessage() or r.exc_info for r in caplog.records)


def test_shutdown_without_wait_returns_immediately():
    q = JobQueue()
    q.start()

    def slow():
        time.sleep(0.05)

    q.submit(slow)
    t0 = time.monotonic()
    q.shutdown(wait=False)
    elapsed = time.monotonic() - t0
    assert elapsed < 0.04


def test_shutdown_is_idempotent():
    q = JobQueue()
    q.start()
    q.shutdown(wait=True)
    q.shutdown(wait=True)


def test_submit_before_start_then_start_runs_them():
    q = JobQueue()
    out: list[int] = []
    q.submit(lambda: out.append(1))
    q.submit(lambda: out.append(2))
    q.start()
    q.shutdown(wait=True)
    assert out == [1, 2]
