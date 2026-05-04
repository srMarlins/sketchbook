from __future__ import annotations

import json
import uuid
from datetime import UTC, datetime
from pathlib import Path


def write_batch(journal_dir: str | Path, *, actor: str, actions: list[dict]) -> str:
    d = Path(journal_dir)
    d.mkdir(parents=True, exist_ok=True)
    bid = f"{datetime.now(UTC).strftime('%Y-%m-%dT%H-%M-%S')}_{uuid.uuid4().hex[:8]}"
    payload = {"batch_id": bid, "actor": actor, "actions": actions}
    (d / f"{bid}.json").write_text(json.dumps(payload, indent=2), encoding="utf-8")
    return bid


def read_batch(journal_dir: str | Path, batch_id: str) -> dict:
    return json.loads((Path(journal_dir) / f"{batch_id}.json").read_text(encoding="utf-8"))


def list_batches(journal_dir: str | Path) -> list[dict]:
    d = Path(journal_dir)
    if not d.exists():
        return []
    return [json.loads(p.read_text(encoding="utf-8")) for p in sorted(d.glob("*.json"))]
