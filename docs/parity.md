# Parity validation (PR-23)

The Kotlin rewrite must reproduce the Python v0.1 surface byte-for-byte for the
features still in scope at v1: catalog scan, FTS search, journal write+undo,
and the MCP `search_projects` / `get_project` tool responses.

## How it runs

`tools/parity/parity_runner.py` drives both backends against the **same frozen
copy of `catalog.db`** and diffs their outputs. The Python side imports
`audio_core` directly. The Kotlin side launches `:app-mcp` as a subprocess and
talks JSON-RPC 2.0 over stdio.

### One-time setup

```pwsh
# Freeze the catalog so accidental writes don't pollute parity runs
Copy-Item Z:\User\audio\catalog.db Z:\User\audio\catalog.parity.db

# Build the Kotlin MCP server once so launches are fast
./gradlew :app-mcp:installDist
```

### Running

`packages/` was deleted with the v1 cutover; `audio_core` lives on the
`python-final` git tag. `uv` pulls it directly:

```pwsh
uv run --with "git+https://github.com/srMarlins/sketchbook@python-final#subdirectory=packages/core" `
       python tools\parity\parity_runner.py `
  --catalog Z:\User\audio\catalog.parity.db `
  --queries tools\parity\fixtures\queries.json `
  --kotlin-mcp "app-mcp\build\install\app-mcp\bin\app-mcp.bat"
```

Each query reports `[ OK ]` or `[FAIL]` with the per-row deltas. Exit code is
non-zero on any failure so CI can gate on it.

## What's covered

- ✅ `search_projects` (this PR)
- ⏳ `get_project` — same harness shape, follow-up
- ⏳ `list_recent` — same harness shape, follow-up
- ⏳ Scan output diff — needs the Kotlin scanner wired into a CLI surface
- ⏳ Journal write+undo round-trip — needs the actions module wired through MCP

## Recording residual divergences

When parity is *intentionally* not byte-equal (e.g. Python truncates at 100
rows; Kotlin returns the full set), document the divergence here with a
heading and a one-paragraph rationale, and update `parity_runner.py` to
normalize the difference rather than report it.

So far: none.
