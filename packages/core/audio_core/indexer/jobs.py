from __future__ import annotations

import sqlite3
import time
from dataclasses import dataclass
from pathlib import Path

from audio_core.indexer.discovery import Discovered, discover, plan
from audio_core.indexer.events import EventBus
from audio_core.scanner.scan import scan_one


@dataclass
class FullScan:
    """Walk the projects root, plan against the catalog, parse only NEW +
    CHANGED files, mark MISSING rows. Emits scan_started / scan_row /
    scan_progress / scan_finished events on the bus."""

    db_path: Path
    root: Path
    bus: EventBus

    def __call__(self) -> None:
        bus = self.bus
        # Open a thread-local connection — never reuse a connection across threads.
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        try:
            cols = {r[1] for r in conn.execute("PRAGMA table_info(projects)").fetchall()}
            catalog_rows = conn.execute(
                "SELECT path, last_modified, file_size_bytes FROM projects"
            ).fetchall()
            catalog = {
                r["path"]: {
                    "mtime": r["last_modified"] or 0.0,
                    "size": r["file_size_bytes"] or 0,
                }
                for r in catalog_rows
            }
            # scan_one canonicalizes via Path.resolve() before storing in the
            # catalog, so the planner must compare against resolved paths to
            # avoid spuriously re-classifying everything as NEW each pass.
            discovered: list[Discovered] = []
            for d in discover(self.root):
                resolved = str(Path(d.path).resolve())
                discovered.append(Discovered(path=resolved, mtime=d.mtime, size=d.size))
            p = plan(catalog, discovered)
            total = len(p.new) + len(p.changed)
            bus.publish({
                "kind": "scan_started",
                "discovered": len(discovered),
                "to_parse": total,
                "missing": len(p.missing),
                "started_at": time.time(),
            })
            done = 0
            failed = 0
            new_set = {d.path for d in p.new}
            for d in [*p.new, *p.changed]:
                status = "new" if d.path in new_set else "updated"
                try:
                    pid = scan_one(conn, d.path)
                    bus.publish({
                        "kind": "scan_row",
                        "project_id": pid,
                        "path": d.path,
                        "status": status,
                    })
                except Exception as exc:
                    failed += 1
                    bus.publish({
                        "kind": "scan_row",
                        "path": d.path,
                        "status": "failed",
                        "error": f"{type(exc).__name__}: {exc}",
                    })
                done += 1
                if done % 25 == 0 or done == total:
                    bus.publish({"kind": "scan_progress", "done": done, "total": total})
            if "is_missing" in cols and p.missing:
                conn.executemany(
                    "UPDATE projects SET is_missing=1 WHERE path=?",
                    [(path,) for path in p.missing],
                )
                conn.commit()
            bus.publish({
                "kind": "scan_finished",
                "new": len(p.new),
                "updated": len(p.changed),
                "unchanged": len(p.unchanged),
                "missing": len(p.missing),
                "failed": failed,
            })
        finally:
            conn.close()


@dataclass
class BackfillColumn:
    """Run a single BackfillSpec against the catalog: select rows whose target
    columns are NULL, call spec.fill_one per row, emit progress on the bus."""

    db_path: Path
    spec_name: str
    bus: EventBus

    def __call__(self) -> None:
        from audio_core.backfill import BACKFILL_SPECS

        spec = next(s for s in BACKFILL_SPECS if s.name == self.spec_name)
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        try:
            rows = list(conn.execute(spec.null_check_sql))
            self.bus.publish({"kind": "backfill_started", "name": spec.name, "total": len(rows)})
            done = failed = 0
            for r in rows:
                try:
                    spec.fill_one(conn, dict(r))
                except Exception as exc:
                    failed += 1
                    self.bus.publish({
                        "kind": "backfill_row_failed",
                        "name": spec.name,
                        "path": r["path"],
                        "error": f"{type(exc).__name__}: {exc}",
                    })
                done += 1
                if done % 25 == 0 or done == len(rows):
                    self.bus.publish({
                        "kind": "backfill_progress",
                        "name": spec.name,
                        "done": done,
                        "total": len(rows),
                    })
            conn.commit()
            self.bus.publish({
                "kind": "backfill_finished",
                "name": spec.name,
                "done": done,
                "failed": failed,
            })
        finally:
            conn.close()
