import asyncio
import json
import shutil
import time
from pathlib import Path

from audio_core.db.connection import open_db
from audio_core.db.projects import upsert_project
from audio_core.parser.model import PluginRef, ProjectMetadata, SampleRef
from audio_core.scanner.scan import scan_one
from audio_mcp.main import build_server
from fastmcp import Client

FIX = Path(__file__).parents[2] / "core" / "tests" / "fixtures"


def _seed(tmp_path):
    """Seed via DB-only upsert (faster than full scan; fine for tool tests)."""
    conn = open_db(tmp_path / "data" / "catalog.db")
    a = upsert_project(
        conn,
        path="/x/a.als",
        name="alpha_track",
        parent_dir="/x",
        file_hash="ha",
        last_modified=time.time(),
        meta=ProjectMetadata(
            tempo=140.0,
            plugins=[PluginRef(name="Pro-Q 3", plugin_type="vst3", track_name="Master")],
            samples=[SampleRef(path="/x/kick.wav")],
        ),
    )
    return a


def _seed_fs(tmp_path):
    """Seed via real filesystem scan (needed for proposal tests that reference real project_id)."""
    proj = tmp_path / "Projects" / "p Project"
    proj.mkdir(parents=True)
    als = proj / "x.als"
    shutil.copy(FIX / "tiny.als", als)
    conn = open_db(tmp_path / "data" / "catalog.db")
    return scan_one(conn, als)


def test_tool_surface(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    server = build_server()

    async def go():
        async with Client(server) as c:
            tools = {t.name for t in await c.list_tools()}
            assert {"search", "get_project", "propose_batch"}.issubset(tools)

    asyncio.run(go())


def test_search_tool_returns_results(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    _seed(tmp_path)
    server = build_server()

    async def go():
        async with Client(server) as c:
            res = await c.call_tool("search", {"query": "alpha"})
            data = res.data
            assert isinstance(data, list)
            assert len(data) == 1
            assert data[0]["name"] == "alpha_track"

    asyncio.run(go())


def test_get_project_tool(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    pid = _seed(tmp_path)
    server = build_server()

    async def go():
        async with Client(server) as c:
            res = await c.call_tool("get_project", {"project_id": pid})
            data = res.data
            assert data["name"] == "alpha_track"
            assert any(p["plugin_name"] == "Pro-Q 3" for p in data["plugins"])

    asyncio.run(go())


def test_propose_batch_writes_proposal_file(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    pid = _seed_fs(tmp_path)
    server = build_server()

    async def go():
        async with Client(server) as c:
            res = await c.call_tool(
                "propose_batch",
                {
                    "actions": [
                        {
                            "type": "RenameProject",
                            "args": {"project_id": pid, "new_dir_name": "renamed"},
                        }
                    ],
                    "rationale": "snake_case all dirs",
                },
            )
            proposal_id = res.data["proposal_id"]
            proposal_path = tmp_path / "data" / "proposals" / f"{proposal_id}.json"
            assert proposal_path.exists()
            payload = json.loads(proposal_path.read_text(encoding="utf-8"))
            assert payload["actor"] == "claude"
            assert payload["actions"][0]["type"] == "RenameProject"
            assert payload["rationale"] == "snake_case all dirs"
            # Critically: the proposal was NOT executed.
            assert (tmp_path / "Projects" / "p Project" / "x.als").is_file()

    asyncio.run(go())


def test_search_tool_supports_effort_params(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    pid = _seed(tmp_path)
    # Force a known effort score
    conn = open_db(tmp_path / "data" / "catalog.db")
    conn.execute("UPDATE projects SET effort_score=75 WHERE id=?", (pid,))
    conn.commit()
    server = build_server()

    async def go():
        async with Client(server) as c:
            res = await c.call_tool(
                "search",
                {"min_effort": 50, "order_by": "effort", "order_dir": "desc"},
            )
            data = res.data
            assert isinstance(data, list)
            assert len(data) == 1
            assert data[0]["effort_score"] == 75
            # below floor
            res2 = await c.call_tool("search", {"min_effort": 90})
            assert res2.data == []

    asyncio.run(go())


def test_find_mac_imports_tool_returns_findings(tmp_path, monkeypatch):
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    proj = tmp_path / "p Project"
    proj.mkdir()
    shutil.copy(FIX / "mac_imported_tiny.als", proj / "x.als")
    pid = scan_one(conn, proj / "x.als")

    server = build_server()

    async def go():
        async with Client(server) as c:
            res = await c.call_tool("find_mac_imports", {})
            data = res.data
            assert len(data) == 1
            assert data[0]["project_id"] == pid
            assert data[0]["mac_paths_count"] == 3

    asyncio.run(go())


def test_find_duplicates_tool_returns_groups(tmp_path, monkeypatch):
    from audio_core.db.connection import open_db
    from audio_core.db.projects import upsert_project
    from audio_core.parser.model import ProjectMetadata
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    conn = open_db(tmp_path / "data" / "catalog.db")
    keep = upsert_project(
        conn, path="/k.als", name="k", parent_dir="/",
        file_hash="abc", last_modified=2000.0, meta=ProjectMetadata(),
    )
    drop = upsert_project(
        conn, path="/d.als", name="d", parent_dir="/",
        file_hash="abc", last_modified=1000.0, meta=ProjectMetadata(),
    )
    server = build_server()

    async def go():
        async with Client(server) as c:
            res = await c.call_tool("find_duplicates", {})
            data = res.data
            assert len(data) == 1
            assert data[0]["file_hash"] == "abc"
            assert data[0]["keeper"]["id"] == keep
            assert [l["id"] for l in data[0]["losers"]] == [drop]

    asyncio.run(go())
