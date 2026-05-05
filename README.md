# Sketchbook

Cross-device catalog, versioning, and AI-assisted curation for your Ableton Live projects.

**Status (2026-05-05):** Kotlin / Compose Multiplatform rewrite landed. Python `packages/` retained as the parity reference until validation completes (see `docs/parity.md`). The Tauri/web shell that previously lived in `web/` and `src-tauri/` is retired — Compose Desktop is the v1 surface.

## Run the desktop app

```pwsh
./gradlew :app-desktop:run
```

Builds installers (`.dmg` for Mac, `.msi` for Windows) via Conveyor — see `docs/runbooks/release.md`.

## Run the MCP server (for Claude Desktop)

```pwsh
./gradlew :app-mcp:run
```

See `docs/mcp-setup.md` for the Claude Desktop config snippet.

## Repo layout

- `app-desktop/` — Compose Desktop entry; Compose Navigation 3 for routing.
- `app-mcp/` — JVM MCP server (JSON-RPC 2.0 over stdio).
- `shared/`
  - `core/`, `parser-als/`, `catalog/`, `cloud/`, `sync-io/`, `actions/`, `sync/`, `repository/`, `mcp-server/`
  - `ui-shared/`, `feature-projects/`, `feature-project-detail/`, `feature-timeline/`, `feature-proposals/`, `feature-needs-attention/`, `feature-settings/`
- `docs/` — design docs, plans, runbooks, parity log.
- `tools/parity/` — Python ↔ Kotlin diff harness for cutover validation.
- `packages/` — original Python implementation (parity reference; will be deleted in a follow-up after parity passes).

## Plans + design

- `docs/plans/2026-05-05-sync-versioning-design.md` — authoritative architecture.
- `docs/plans/2026-05-05-kotlin-rewrite-impl-plan.md` — PR-by-PR roadmap.
