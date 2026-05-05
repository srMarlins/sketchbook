import shutil
from pathlib import Path

from audio_core.db.connection import open_db
from audio_core.relink import (
    MissingSampleFinding,
    SampleCandidate,
    build_relink_proposal,
    find_missing_samples,
)
from audio_core.samples import upsert_sample
from audio_core.scanner.scan import scan_one


def test_clean_project_no_findings(tmp_path):
    conn = open_db(tmp_path / "c.db")
    fixtures = Path(__file__).parent / "fixtures"
    proj = tmp_path / "clean Project"
    proj.mkdir()
    shutil.copy(fixtures / "tiny.als", proj / "clean.als")
    scan_one(conn, proj / "clean.als")
    findings = find_missing_samples(conn)
    assert findings == [] or all(not f.candidates for f in findings)


def test_single_filename_match_is_auto(tmp_path):
    conn = open_db(tmp_path / "c.db")
    proj = tmp_path / "p Project"
    proj.mkdir()
    fixtures = Path(__file__).parent / "fixtures"
    shutil.copy(fixtures / "tiny.als", proj / "p.als")
    pid = scan_one(conn, proj / "p.als")
    conn.execute(
        "INSERT INTO project_samples (project_id, sample_path, is_missing, size_bytes) "
        "VALUES (?, ?, 1, NULL)",
        (pid, "Samples/kick.wav"),
    )
    conn.commit()
    cand = tmp_path / "lib" / "kick.wav"
    cand.parent.mkdir()
    cand.write_bytes(b"x")
    upsert_sample(conn, cand)

    findings = find_missing_samples(conn)
    by_path = {f.missing_path: f for f in findings}
    f = by_path["Samples/kick.wav"]
    assert f.project_id == pid
    assert f.auto_match is not None
    assert f.auto_match.path == str(cand.resolve())


def test_multiple_filename_matches_no_auto(tmp_path):
    conn = open_db(tmp_path / "c.db")
    proj = tmp_path / "p Project"
    proj.mkdir()
    fixtures = Path(__file__).parent / "fixtures"
    shutil.copy(fixtures / "tiny.als", proj / "p.als")
    pid = scan_one(conn, proj / "p.als")
    conn.execute(
        "INSERT INTO project_samples (project_id, sample_path, is_missing, size_bytes) "
        "VALUES (?, ?, 1, NULL)",
        (pid, "kick.wav"),
    )
    conn.commit()
    a = tmp_path / "a" / "kick.wav"
    b = tmp_path / "b" / "kick.wav"
    a.parent.mkdir()
    b.parent.mkdir()
    a.write_bytes(b"x")
    b.write_bytes(b"yy")
    upsert_sample(conn, a)
    upsert_sample(conn, b)

    findings = find_missing_samples(conn)
    by_path = {f.missing_path: f for f in findings}
    f = by_path["kick.wav"]
    assert f.auto_match is None
    assert {c.path for c in f.candidates} == {str(a.resolve()), str(b.resolve())}


def test_zero_matches(tmp_path):
    conn = open_db(tmp_path / "c.db")
    proj = tmp_path / "p Project"
    proj.mkdir()
    fixtures = Path(__file__).parent / "fixtures"
    shutil.copy(fixtures / "tiny.als", proj / "p.als")
    pid = scan_one(conn, proj / "p.als")
    conn.execute(
        "INSERT INTO project_samples (project_id, sample_path, is_missing, size_bytes) "
        "VALUES (?, ?, 1, NULL)",
        (pid, "ghost.wav"),
    )
    conn.commit()
    findings = find_missing_samples(conn)
    f = next(f for f in findings if f.missing_path == "ghost.wav")
    assert f.auto_match is None
    assert f.candidates == []


def test_archived_excluded(tmp_path):
    conn = open_db(tmp_path / "c.db")
    proj = tmp_path / "p Project"
    proj.mkdir()
    fixtures = Path(__file__).parent / "fixtures"
    shutil.copy(fixtures / "tiny.als", proj / "p.als")
    pid = scan_one(conn, proj / "p.als")
    conn.execute(
        "INSERT INTO project_samples (project_id, sample_path, is_missing, size_bytes) "
        "VALUES (?, ?, 1, NULL)",
        (pid, "kick.wav"),
    )
    conn.execute("UPDATE projects SET is_archived=1 WHERE id=?", (pid,))
    conn.commit()
    findings = find_missing_samples(conn)
    assert findings == []


def test_build_relink_proposal(tmp_path):
    findings = [
        MissingSampleFinding(
            project_id=10,
            project_path="/p1.als",
            project_name="p1",
            missing_path="kick.wav",
            candidates=[SampleCandidate(path="/lib/kick.wav", filename="kick.wav", size_bytes=1, mtime=0.0)],
            auto_match=SampleCandidate(path="/lib/kick.wav", filename="kick.wav", size_bytes=1, mtime=0.0),
        ),
        MissingSampleFinding(
            project_id=10,
            project_path="/p1.als",
            project_name="p1",
            missing_path="snare.wav",
            candidates=[SampleCandidate(path="/lib/snare1.wav", filename="snare.wav", size_bytes=2, mtime=0.0),
                        SampleCandidate(path="/lib/snare2.wav", filename="snare.wav", size_bytes=3, mtime=0.0)],
            auto_match=None,
        ),
    ]
    actions = build_relink_proposal(findings, picks={})
    assert actions == [
        {
            "type": "RelinkMissingSamples",
            "args": {"project_id": 10, "relinks": [{"old": "kick.wav", "new": "/lib/kick.wav"}]},
        }
    ]
    actions = build_relink_proposal(findings, picks={"snare.wav": "/lib/snare2.wav"})
    assert actions == [
        {
            "type": "RelinkMissingSamples",
            "args": {
                "project_id": 10,
                "relinks": [
                    {"old": "kick.wav", "new": "/lib/kick.wav"},
                    {"old": "snare.wav", "new": "/lib/snare2.wav"},
                ],
            },
        }
    ]
