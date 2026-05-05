# Sketchbook

A versioning and sync layer for an Ableton Live library.

Download: <https://srmarlins.github.io/sketchbook/>

Run from source:

```pwsh
./gradlew :app-desktop:run
```

MCP server for Claude Desktop (config in `docs/mcp-setup.md`):

```pwsh
./gradlew :app-mcp:run
```

Architecture lives in `docs/plans/2026-05-05-sync-versioning-design.md`. Release flow in `docs/runbooks/release.md`.

Apache-2.0. Packaged with [Conveyor](https://hydraulic.dev).
