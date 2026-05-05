#!/usr/bin/env python3
"""Parity harness: diff Python (v0.1) vs Kotlin (v1) outputs for a frozen catalog.

Pass a path to a copy of the production catalog.db plus a fixtures file listing
queries to drive both backends with. The harness:

  1. Opens the catalog with the Python `audio_core` API and runs each query.
  2. Launches the Kotlin MCP server (`./gradlew :app-mcp:run`) as a subprocess
     and invokes the same queries via JSON-RPC 2.0 over stdio.
  3. Normalizes both outputs and diffs them, printing a per-query result.

This is a scaffold: PR-23 only wires up `search_projects`. `get_project` and
`list_recent` follow the same pattern; add them once `search_projects` is
green on a real catalog.

Usage:

    uv run --project packages/cli python tools/parity/parity_runner.py \
        --catalog Z:/User/audio/catalog.db \
        --queries tools/parity/fixtures/queries.json \
        --kotlin-mcp ./gradlew :app-mcp:run
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sqlite3
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


@dataclass(frozen=True)
class QueryFixture:
    name: str
    query: str
    limit: int = 50


# ---- Python backend -------------------------------------------------------

def py_search(con: sqlite3.Connection, query: str, limit: int) -> list[dict[str, Any]]:
    from audio_core.db.projects import search_projects

    rows = search_projects(con, query=query, limit=limit)
    return [
        {
            "id": row["id"],
            "name": row["name"],
            "path": row["path"],
            "tempo": row["tempo"],
            "track_count": row["track_count"],
        }
        for row in rows
    ]


# ---- Kotlin backend (MCP over stdio) --------------------------------------

class KotlinMcpClient:
    def __init__(self, command: list[str]) -> None:
        self._proc = subprocess.Popen(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
        )
        self._next_id = 1

    def __enter__(self) -> "KotlinMcpClient":
        return self

    def __exit__(self, *_: Any) -> None:
        if self._proc.stdin and not self._proc.stdin.closed:
            self._proc.stdin.close()
        self._proc.wait(timeout=10)

    def call(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        request = {
            "jsonrpc": "2.0",
            "id": self._next_id,
            "method": method,
            "params": params,
        }
        self._next_id += 1
        assert self._proc.stdin is not None and self._proc.stdout is not None
        self._proc.stdin.write(json.dumps(request) + "\n")
        self._proc.stdin.flush()
        line = self._proc.stdout.readline()
        if not line:
            raise RuntimeError("MCP server closed stdout before responding")
        return json.loads(line)

    def search(self, query: str, limit: int) -> list[dict[str, Any]]:
        resp = self.call(
            "tools/call",
            {"name": "search_projects", "arguments": {"query": query, "limit": limit}},
        )
        result = resp.get("result", {})
        # MCP response shape: { content: [{ type: "text", text: "<json>" }] }
        items = result.get("content", [])
        if not items:
            return []
        payload = json.loads(items[0]["text"])
        return [
            {
                "id": p["id"],
                "name": p["name"],
                "path": p["path"],
                "tempo": p.get("tempo"),
                "track_count": p["track_count"],
            }
            for p in payload.get("projects", [])
        ]


# ---- Diff -----------------------------------------------------------------

def normalize(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Sort by id so ordering doesn't cause spurious diffs across backends."""
    return sorted(rows, key=lambda r: r["id"])


def diff(py_rows: list[dict[str, Any]], kt_rows: list[dict[str, Any]]) -> list[str]:
    diffs: list[str] = []
    py_by_id = {r["id"]: r for r in py_rows}
    kt_by_id = {r["id"]: r for r in kt_rows}
    only_py = py_by_id.keys() - kt_by_id.keys()
    only_kt = kt_by_id.keys() - py_by_id.keys()
    if only_py:
        diffs.append(f"only in python: {sorted(only_py)}")
    if only_kt:
        diffs.append(f"only in kotlin: {sorted(only_kt)}")
    for shared_id in py_by_id.keys() & kt_by_id.keys():
        for k in ("name", "path", "tempo", "track_count"):
            if py_by_id[shared_id].get(k) != kt_by_id[shared_id].get(k):
                diffs.append(
                    f"id={shared_id} field={k} py={py_by_id[shared_id].get(k)!r} kt={kt_by_id[shared_id].get(k)!r}"
                )
    return diffs


# ---- Driver ---------------------------------------------------------------

def load_fixtures(path: Path) -> Iterable[QueryFixture]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    for entry in raw["queries"]:
        yield QueryFixture(
            name=entry["name"],
            query=entry["query"],
            limit=entry.get("limit", 50),
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--queries", type=Path, required=True)
    parser.add_argument("--kotlin-mcp", type=str, required=True,
                        help="Command to launch the Kotlin MCP server (space-separated)")
    args = parser.parse_args()

    con = sqlite3.connect(str(args.catalog))
    con.row_factory = sqlite3.Row

    failures = 0
    fixtures = list(load_fixtures(args.queries))
    with KotlinMcpClient(args.kotlin_mcp.split()) as kt:
        for f in fixtures:
            py_rows = normalize(py_search(con, f.query, f.limit))
            kt_rows = normalize(kt.search(f.query, f.limit))
            diffs = diff(py_rows, kt_rows)
            if diffs:
                failures += 1
                print(f"[FAIL] {f.name} ({f.query!r})")
                for d in diffs:
                    print(f"   - {d}")
            else:
                print(f"[ OK ] {f.name} ({f.query!r}) — {len(py_rows)} rows match")

    print(f"\n{len(fixtures) - failures}/{len(fixtures)} parity checks passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
