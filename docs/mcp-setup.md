# Registering the Sketchbook MCP server with Claude Code

The `:app-mcp` Kotlin server exposes these tools for AI-driven catalog work:

- `search(query, tempo_min, tempo_max, archived, limit)` — FTS over names/plugins/sample filenames + tempo range filter.
- `get_project(project_id)` — full project detail: metadata, plugins, samples, tags.
- `find_duplicates(limit)` — list byte-identical .als groups with a recommended keeper per group; pair with `propose_batch` to archive losers.
- `find_mac_imports(limit)` — list projects that look Mac-saved-on-Windows (Mac-prefix paths inside the .als and/or no `Ableton Project Info/` folder); pair with `propose_batch` + one `RepairMacPaths` action per `project_id` to fix.
- `find_missing_samples(limit)` — list missing-sample findings with optional auto-match candidates; pair with `propose_batch` + `RelinkMissingSamples` to fix.
- `propose_batch(actions, rationale)` — submit a proposed batch of write actions for the user to approve in the desktop app. **Does not execute.**

The server reads `data/catalog.db` and writes proposals to `data/proposals/` under `AUDIO_ROOT` (default `Z:/User/audio`).

## Add it to a Claude Code `.mcp.json`

```json
{
  "mcpServers": {
    "sketchbook": {
      "command": "Z:/User/audio/gradlew.bat",
      "args": ["--quiet", ":app-mcp:run"],
      "env": {
        "AUDIO_ROOT": "Z:/User/audio"
      }
    }
  }
}
```

`./gradlew :app-mcp:run` launches `com.sketchbook.mcp.app.MainKt` with the MCP server on stdio.

## Verify

From a fresh Claude Code session in the workspace:

```
/mcp
```

You should see `sketchbook` listed with its tools. Try:

```
mcp__sketchbook__search query="diva"
```

…and confirm a result list comes back. Any write proposal will land in `data/proposals/<timestamp>_<id>.json` for the user to approve via the desktop app's Proposals screen.

## Notes

- The MCP server has the same path allowlist as the rest of the system (`Z:/User/audio/Projects`) — proposals are validated at approval time, not submission time, so a malformed proposal still gets rejected at the user's discretion.
- The server is read-mostly: it never executes a mutation. The only side effect of any tool is writing a JSON file to `data/proposals/`.
- The `propose_batch` action schema lives in `shared/actions/` (`Action` sealed interface) and `shared/repository/` (`ProposalAction` shape). Look there, not at the deleted Python design doc.

## Indexing is automatic

The desktop app owns the indexer. On launch it walks `AUDIO_ROOT/Projects`, runs any pending NULL-column backfills, then the file watcher catches subsequent saves. Progress streams to the UI live; the IndexerStatus chip in the header surfaces state.

You don't need to run anything manually before launching the MCP server — but the desktop app must have been run at least once against this `AUDIO_ROOT` so the catalog DB exists.
