# Registering `audio-mcp` with Claude Code

The `audio-mcp` server exposes three tools for AI-driven catalog work:

- `search(query, tempo_min, tempo_max, archived, limit)` — FTS over names/plugins/sample filenames + tempo range filter.
- `get_project(project_id)` — full project detail: metadata, plugins, samples, tags.
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
