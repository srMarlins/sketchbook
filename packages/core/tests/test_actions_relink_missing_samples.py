import gzip
import shutil
from pathlib import Path

import pytest
from lxml import etree

from audio_core.actions.relink_missing_samples import Relink, RelinkMissingSamples
from audio_core.actions.runner import run_batch
from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_one


def _seed_relink_project(tmp_path):
    fixtures = Path(__file__).parent / "fixtures"
    root = tmp_path / "Projects"
    proj = root / "p Project"
    proj.mkdir(parents=True)
    shutil.copy(fixtures / "missing_sample_tiny.als", proj / "x.als")
    cand = tmp_path / "lib" / "relink_test_kick.wav"
    cand.parent.mkdir()
    cand.write_bytes(b"\x00" * 42)

    conn = open_db(tmp_path / "data" / "catalog.db")
    pid = scan_one(conn, proj / "x.als")
    return tmp_path, root, conn, pid, proj / "x.als", cand


def test_relink_rewrites_path_and_clears_missing(tmp_path):
    base, root, conn, pid, als, cand = _seed_relink_project(tmp_path)
    miss_rows = conn.execute(
        "SELECT sample_path FROM project_samples WHERE project_id=? AND is_missing=1",
        (pid,),
    ).fetchall()
    missing_path = next(r[0] for r in miss_rows if "relink_test_kick.wav" in r[0])

    run_batch(
        conn,
        [
            RelinkMissingSamples(
                project_id=pid,
                relinks=[Relink(old=missing_path, new=str(cand))],
                root=root,
            )
        ],
        actor="test",
        journal_dir=base / "data" / "journal",
    )

    assert (als.with_suffix(".als.bak")).is_file()

    with gzip.open(als, "rb") as fh:
        xroot = etree.parse(fh, etree.XMLParser(huge_tree=True)).getroot()
    paths = [
        n.get("Value", "")
        for n in xroot.iter("Path")
        if n.getparent() is not None and n.getparent().tag == "FileRef"
    ]
    assert any(str(cand) in p or str(cand.resolve()) in p for p in paths)

    after = conn.execute(
        "SELECT is_missing FROM project_samples WHERE project_id=? AND sample_path=?",
        (pid, str(cand.resolve())),
    ).fetchone()
    assert after is not None and after[0] == 0


def test_relink_idempotent(tmp_path):
    base, root, conn, pid, als, cand = _seed_relink_project(tmp_path)
    missing_path = conn.execute(
        "SELECT sample_path FROM project_samples "
        "WHERE project_id=? AND is_missing=1 AND sample_path LIKE '%relink_test_kick.wav'",
        (pid,),
    ).fetchone()[0]
    args = dict(project_id=pid, relinks=[Relink(old=missing_path, new=str(cand))], root=root)
    run_batch(conn, [RelinkMissingSamples(**args)], actor="test", journal_dir=base / "data" / "journal")
    run_batch(conn, [RelinkMissingSamples(**args)], actor="test", journal_dir=base / "data" / "journal")


def test_relink_refuses_outside_root(tmp_path):
    base, root, conn, pid, als, cand = _seed_relink_project(tmp_path)
    bogus = tmp_path / "elsewhere"
    bogus.mkdir()
    a = RelinkMissingSamples(
        project_id=pid,
        relinks=[Relink(old="anything", new=str(cand))],
        root=bogus,
    )
    with pytest.raises(Exception):
        a.validate(conn)


def test_relink_refuses_when_live_open(tmp_path, monkeypatch):
    base, root, conn, pid, als, cand = _seed_relink_project(tmp_path)
    monkeypatch.setattr(
        "audio_core.actions.relink_missing_samples.is_open_in_live", lambda _p: True
    )
    a = RelinkMissingSamples(
        project_id=pid,
        relinks=[Relink(old="anything", new=str(cand))],
        root=root,
    )
    with pytest.raises(RuntimeError):
        a.validate(conn)


def test_undo_restores_als_from_backup(tmp_path):
    from audio_core.actions.undo import undo_batch

    base, root, conn, pid, als, cand = _seed_relink_project(tmp_path)
    pre = als.read_bytes()
    missing_path = conn.execute(
        "SELECT sample_path FROM project_samples "
        "WHERE project_id=? AND is_missing=1 AND sample_path LIKE '%relink_test_kick.wav'",
        (pid,),
    ).fetchone()[0]

    bid = run_batch(
        conn,
        [
            RelinkMissingSamples(
                project_id=pid,
                relinks=[Relink(old=missing_path, new=str(cand))],
                root=root,
            )
        ],
        actor="test",
        journal_dir=base / "data" / "journal",
    )
    assert als.read_bytes() != pre
    undo_batch(conn, base / "data" / "journal", bid)
    assert als.read_bytes() == pre
    miss = conn.execute(
        "SELECT is_missing FROM project_samples WHERE project_id=? AND sample_path=?",
        (pid, missing_path),
    ).fetchone()
    assert miss is not None and miss[0] == 1


def test_relink_refuses_missing_candidate(tmp_path):
    base, root, conn, pid, als, cand = _seed_relink_project(tmp_path)
    cand.unlink()
    missing_path = conn.execute(
        "SELECT sample_path FROM project_samples WHERE project_id=? AND is_missing=1 LIMIT 1",
        (pid,),
    ).fetchone()[0]
    a = RelinkMissingSamples(
        project_id=pid,
        relinks=[Relink(old=missing_path, new=str(cand))],
        root=root,
    )
    with pytest.raises(FileNotFoundError):
        a.validate(conn)
