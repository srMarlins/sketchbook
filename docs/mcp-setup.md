# Registering `audio-mcp` with Claude Code

The `audio-mcp` server exposes these tools for AI-driven catalog work:

- `search(query, tempo_min, tempo_max, archived, limit)` — FTS over names/plugins/sample filenames + tempo range filter.
- `get_project(project_id)` — full project detail: metadata, plugins, samples, tags.
- `find_duplicates(limit)` — list byte-identical .als groups with a recommended keeper per group; pair with `propose_batch` to archive losers.
- `find_mac_imports(limit)` — list projects that look Mac-saved-on-Windows (Mac-prefix paths inside the .als and/or no `Ableton Project Info/` folder); pair with `propose_batch` + one `RepairMacPaths` action per `project_id` to fix.
- `find_missing_samples(limit)` — list missing-sample findings with optional auto-match candidates; pair with `propose_batch` + `RelinkMissingSamples` to fix.
- `propose_batch(actions, rationale)` — submit a proposed batch of write actions for the user to approve in the web UI or CLI. **Does not execute.**

The server reads `data/catalog.db` and writes proposals to `data/proposals/` under `AUDIO_ROOT` (default `Z:/User/audio`).

## Add it to a Claude Code `.mcp.json`

Drop this into the workspace's `.mcp.json` (or merge with the existing one):

```json
{
  "mcpServers": {
    "audio": {
      "command": "uv",
      "args": ["run", "--project", "Z:/User/audio", "audio-mcp"],
      "env": {
        "AUDIO_ROOT": "Z:/User/audio"
      }
    }
  }
}
```

`uv run audio-mcp` resolves the entry point declared in `packages/mcp/pyproject.toml` (`audio_mcp.main:run`) inside the workspace's venv.

## Verify

From a fresh Claude Code session in the workspace:

```
/mcp
```

You should see `audio` listed with three tools. Try:

```
mcp__audio__search query="diva"
```

…and confirm a result list comes back. Any write proposal will land in `data/proposals/<timestamp>_<id>.json` for the user to approve via `audio approve <id>` or the web UI.

## Notes

- The MCP server has the same path allowlist as the rest of the system (`Z:/User/audio/Projects`) — proposals are validated at approval time, not submission time, so a malformed proposal still gets rejected at the user's discretion.
- The server is read-mostly: it never executes a mutation. The only side effect of any tool is writing a JSON file to `data/proposals/`.
- `propose_batch` actions follow the same shape as `POST /api/proposals` in `audio-web` — see `docs/plans/2026-05-04-audio-catalog-design.md` for the full action schema.

## Indexing is automatic

The desktop app (and any `audio-web` server you run for local dev) owns the indexer. On launch it walks `AUDIO_ROOT/Projects`, runs any pending NULL-column backfills, then starts a filesystem watcher that catches subsequent saves within ~2 seconds. Progress streams to the UI over `/api/events` (SSE) and the IndexerStatus chip in the header surfaces live state.

You don't need to run anything manually. The `audio scan` CLI still exists, but it's for headless / scripting workflows only — running it from a terminal is not part of the user flow.
