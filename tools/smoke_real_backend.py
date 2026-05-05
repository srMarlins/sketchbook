"""Real-backend smoke for the web API.

Drives every endpoint the React UI consumes against the actual FastAPI
app + the project's existing SQLite catalog. Uses TestClient so it
doesn't need a separately-running uvicorn process.

Set AUDIO_ROOT before running if your catalog isn't at the default
Z:/User/audio (e.g. when running from a worktree that points at a
different db).

Usage:
  uv run python tools/smoke_real_backend.py
"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import Any


def main() -> int:
    audio_root = os.environ.get("AUDIO_ROOT")
    if not audio_root:
        # Default to the main repo so the smoke runs against a real catalog
        # even when invoked from a worktree.
        audio_root = "Z:/User/audio"
        os.environ["AUDIO_ROOT"] = audio_root

    catalog_db = Path(audio_root) / "data" / "catalog.db"
    if not catalog_db.exists():
        print(f"FAIL: no catalog at {catalog_db}", file=sys.stderr)
        print("Run `uv run audio-cli scan` to populate it first.", file=sys.stderr)
        return 1

    # Import after env is set so config.workspace_root() picks it up.
    from audio_web.app import create_app
    from fastapi.testclient import TestClient

    client = TestClient(create_app())

    def check(label: str, ok: bool, detail: str = "") -> None:
        marker = "OK  " if ok else "FAIL"
        line = f"  {marker}  {label}"
        if detail:
            line += f"  - {detail}"
        print(line)
        if not ok:
            failures.append(label)

    failures: list[str] = []

    print(f"AUDIO_ROOT = {audio_root}")
    print(f"catalog    = {catalog_db}")
    print()

    # /api/health
    print("/api/health")
    r = client.get("/api/health")
    check("status 200", r.status_code == 200, str(r.status_code))
    print()

    # /api/projects
    print("/api/projects")
    r = client.get("/api/projects", params={"limit": 50})
    check("status 200", r.status_code == 200, str(r.status_code))
    body = r.json() if r.status_code == 200 else {"items": [], "next_cursor": None}
    rows = body.get("items", [])
    check("returns paginated shape", isinstance(rows, list) and "next_cursor" in body)
    if rows:
        first: dict[str, Any] = rows[0]
        check("row has tags array", isinstance(first.get("tags"), list))
        check(
            "row has expected ProjectSummary fields",
            all(
                k in first
                for k in ("id", "name", "path", "parent_dir", "tempo", "color_tag")
            ),
        )
    print(f"  --> {len(rows)} projects returned (next_cursor={body.get('next_cursor')!r})")
    print()

    # cursor pagination — fetch second page if there's one
    if body.get("next_cursor"):
        print("/api/projects (page 2 via cursor)")
        r2 = client.get(
            "/api/projects", params={"limit": 50, "cursor": body["next_cursor"]}
        )
        check("status 200", r2.status_code == 200, str(r2.status_code))
        page2 = r2.json().get("items", [])
        page1_ids = {p["id"] for p in rows}
        page2_ids = {p["id"] for p in page2}
        check("page 2 has no overlap with page 1", page1_ids.isdisjoint(page2_ids))
        print(f"  --> {len(page2)} projects on page 2")
        print()

    # query filter
    print("/api/projects?query=...")
    r = client.get("/api/projects", params={"query": "test", "limit": 10})
    check("status 200", r.status_code == 200, str(r.status_code))
    print()

    # tempo range
    print("/api/projects?tempo_min&tempo_max")
    r = client.get("/api/projects", params={"tempo_min": 100, "tempo_max": 140})
    check("status 200", r.status_code == 200, str(r.status_code))
    band = r.json().get("items", []) if r.status_code == 200 else []
    check(
        "all returned tempos within [100,140]",
        all(p.get("tempo") is None or 100 <= p["tempo"] <= 140 for p in band),
    )
    print()

    # detail
    if rows:
        pid = rows[0]["id"]
        print(f"/api/projects/{pid}")
        r = client.get(f"/api/projects/{pid}")
        check("status 200", r.status_code == 200, str(r.status_code))
        body = r.json() if r.status_code == 200 else {}
        check("has plugins array", isinstance(body.get("plugins"), list))
        check("has samples array", isinstance(body.get("samples"), list))
        check("has tags array", isinstance(body.get("tags"), list))
        print()

    # 404
    print("/api/projects/9999999")
    r = client.get("/api/projects/9999999")
    check("status 404", r.status_code == 404, str(r.status_code))
    print()

    # /api/proposals
    print("/api/proposals")
    r = client.get("/api/proposals")
    check("status 200", r.status_code == 200, str(r.status_code))
    proposals = r.json() if r.status_code == 200 else []
    check("returns list", isinstance(proposals, list))
    print(f"  --> {len(proposals)} proposals on disk")
    print()

    # /api/journal
    print("/api/journal")
    r = client.get("/api/journal")
    check("status 200", r.status_code == 200, str(r.status_code))
    journal = r.json() if r.status_code == 200 else []
    check("returns list", isinstance(journal, list))
    print(f"  --> {len(journal)} batches in journal")
    print()

    # propose --> approve --> undo (uses a SetTags action - DB-only mutation, reversible)
    if rows:
        pid = rows[0]["id"]
        before_tags = rows[0].get("tags", [])
        new_tags = list({*before_tags, "smoke-test-tag"})

        print("propose --> approve --> undo (SetTags)")
        body = {
            "actor": "user",
            "actions": [
                {"type": "SetTags", "args": {"project_id": pid, "tags": new_tags}}
            ],
            "rationale": "smoke test",
        }
        r = client.post("/api/proposals", json=body)
        check("submit status 201", r.status_code == 201, str(r.status_code))
        proposal_id = r.json().get("proposal_id") if r.status_code == 201 else None

        if proposal_id:
            r = client.post(f"/api/proposals/{proposal_id}/approve")
            check("approve status 200", r.status_code == 200, str(r.status_code))
            batch_id = r.json().get("batch_id") if r.status_code == 200 else None

            if batch_id:
                # Verify the row now has the new tag
                r = client.get(f"/api/projects/{pid}")
                rb = r.json()
                check(
                    "tag landed on project",
                    "smoke-test-tag" in rb.get("tags", []),
                )

                # Undo
                r = client.post(f"/api/journal/{batch_id}/undo")
                check("undo status 200", r.status_code == 200, str(r.status_code))

                # Verify tags reverted
                r = client.get(f"/api/projects/{pid}")
                ra = r.json()
                check(
                    "tag rolled back",
                    "smoke-test-tag" not in ra.get("tags", []),
                )
        print()

    if failures:
        print(f"\n{len(failures)} failed:")
        for f in failures:
            print(f"  - {f}")
        return 1

    print("\nall checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
