from audio_core.db.connection import open_db
from audio_core.indexer import driver
from audio_core.indexer.events import EventBus
from audio_core.indexer.jobs import FullSampleScan
from audio_core.indexer.queue import JobQueue


def test_boot_submits_full_sample_scan(tmp_path):
    db = tmp_path / "c.db"
    open_db(db).close()
    root = tmp_path / "Projects"
    root.mkdir()
    extra = tmp_path / "Samples"
    extra.mkdir()
    bus = EventBus()
    q = JobQueue()
    submitted: list = []
    q.submit = lambda fn: submitted.append(fn)
    driver.boot(db_path=db, root=root, bus=bus, queue=q, sample_roots=[extra])
    assert any(isinstance(s, FullSampleScan) for s in submitted)
    fss = next(s for s in submitted if isinstance(s, FullSampleScan))
    assert root in fss.roots
    assert extra in fss.roots
