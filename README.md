# Sketchbook

Cross-device catalog, versioning, and AI-assisted curation for your Ableton Live projects.

**Landing page + downloads:** <https://srmarlins.github.io/sketchbook/>

**Status (2026-05-05):** Kotlin / Compose Multiplatform rewrite landed. Python `packages/` retained as the parity reference until validation completes (see `docs/parity.md`). The Tauri/web shell that previously lived in `web/` and `src-tauri/` is retired — Compose Desktop is the v1 surface.

## Install

Latest release: <https://srmarlins.github.io/sketchbook/>. The page detects your OS and links the right artifact:

- **macOS** (Apple Silicon / Intel) — `.zip` containing the `.app`.
- **Windows** — `.exe` launcher that installs the signed MSIX and wires up auto-update.

Already running Sketchbook? It checks for updates on launch and applies them in place — no reinstall needed. See `docs/runbooks/release.md` for the release flow.

## Run from source

```pwsh
./gradlew :app-desktop:run
```

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

## License + packaging

Apache 2.0 — see [`LICENSE`](LICENSE).

Packaged with [Conveyor](https://hydraulic.dev) (free for open-source projects). Conveyor handles cross-platform installers, code signing, and the static auto-update site.
