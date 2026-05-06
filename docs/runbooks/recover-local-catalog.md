# Recover the Local Catalog

The local SQLite catalog is a **rebuildable index**, not a system of record. The cloud blob store (`gs://sketchbook-jtf-2026/blobs/<blake3>`) and the user's `.als`/`.adv` files on disk are the source of truth. Anything in the catalog can be re-derived by re-scanning.

## Where the catalog lives

The desktop app picks a per-OS data dir and writes `catalog.db` there:

| OS | Path |
|---|---|
| Windows | `%APPDATA%\Sketchbook\catalog.db` |
| macOS | `~/Library/Application Support/Sketchbook/catalog.db` |
| Linux | `~/.local/share/sketchbook/catalog.db` |
| Override (test/dev) | env `SKETCHBOOK_DB_PATH=<path>` |

Cloud credentials (the GCS service-account JSON), bucket name, library roots, and self-contained-project flags live in `java.util.prefs.Preferences` (Windows registry `HKCU\Software\JavaSoft\Prefs\com\sketchbook\…`, Mac `~/Library/Preferences/com.apple.java.util.prefs.plist`, Linux `~/.java/.userPrefs/`). They will move to the OS keychain in v1.1.

This runbook covers three failure modes:

1. The DB file is corrupt or missing (`SQLITE_NOTADB`, `SQLITE_CORRUPT`, file deleted).
2. The catalog is out of sync with disk (rows reference files that have moved/been deleted).
3. A schema migration failed mid-flight and the app won't start.

## Backups

There is no off-machine backup of the catalog by design. If the file is gone, re-scan rebuilds it in a few minutes for a typical library and a few hours for a 10k-project library. Cloud blobs are not affected by local catalog loss.

If you want a manual backup before risky operations:

```powershell
# Windows
Copy-Item "$env:APPDATA\Sketchbook\catalog.db" "$env:APPDATA\Sketchbook\catalog.db.bak"
```

Restore by swapping the file back in while the app is closed.

## 1. Corrupt or missing DB

Symptom: app fails to launch with one of:

- `SQLITE_NOTADB: file is not a database`
- `SQLITE_CORRUPT: database disk image is malformed`
- `no such table: projects` on a previously-working install

Recovery:

```powershell
# 1. Close Sketchbook completely (check Task Manager — the JVM can outlive the window).
# 2. Move the bad DB aside so you can inspect later if needed.
$dir = "$env:APPDATA\Sketchbook"
Move-Item "$dir\catalog.db" "$dir\catalog.db.broken"
Move-Item "$dir\catalog.db-wal" "$dir\catalog.db-wal.broken" -ErrorAction SilentlyContinue
Move-Item "$dir\catalog.db-shm" "$dir\catalog.db-shm.broken" -ErrorAction SilentlyContinue

# 3. Restart Sketchbook. It will create a fresh catalog and run a full scan
#    on first launch. Progress shows in the dashboard.
```

If a recent `.bak` exists and is trustworthy (e.g. corruption happened seconds ago after a power loss), restore from it instead of re-scanning:

```powershell
Move-Item "$dir\catalog.db" "$dir\catalog.db.broken"
Copy-Item "$dir\catalog.db.bak" "$dir\catalog.db"
```

## 2. Catalog out of sync with disk

Symptom: search returns projects that no longer exist on disk, or recently added projects don't appear.

The file watcher should keep these in sync, but it can miss events if the app was killed while a large move was in progress, or if files were modified on a network share that doesn't deliver `WatchService` events.

Recovery (no DB rebuild required): Settings → Library → "Rescan now" forces a full crawl. This is incremental — unchanged files are skipped via mtime+size, so it's cheap to run repeatedly.

## 3. Failed schema migration

Symptom: app starts, then fails with `SQLITE_ERROR: ... near "..."` or `table projects already exists` after an update.

The schema bring-up is idempotent (gated on `PRAGMA user_version`), so a clean re-launch usually recovers. If it doesn't:

```powershell
# Capture the broken DB for the bug report:
Copy-Item "$env:APPDATA\Sketchbook\catalog.db" "$env:TEMP\sketchbook-catalog-broken-$(Get-Date -f yyyyMMdd-HHmm).db"

# Then follow recovery 1 (move aside + re-scan).
```

Attach the captured DB when filing an issue at <https://github.com/srMarlins/sketchbook/issues>.

## What the catalog stores (and why losing it is recoverable)

The DB holds derived data only:

- Project rows (uuid, path, name, mtime, parsed metadata) — re-derived from `.als` files.
- FTS index over names/plugins/sample paths — re-derived from each `.als`.
- Tag pairs and color tags — re-derived from each `.als` (Ableton stores them inline).
- Snapshot metadata (blob hash, size, created_at) — already in cloud.

User-facing data the catalog does NOT own:

- The `.als`/`.adv` files themselves (on disk).
- Snapshot blob contents (in `gs://sketchbook-jtf-2026/blobs/`).
- Auth credentials and library roots (in `java.util.prefs.Preferences`).

Losing the catalog DB is therefore safe. Losing your `Preferences` resets the app to first-run state — you'll re-add library roots and re-paste the GCS service-account JSON in Settings.
