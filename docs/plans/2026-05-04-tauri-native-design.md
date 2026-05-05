# Native app (Tauri + Python sidecar) — design

**Status:** proposal, awaiting approval before any implementation work.
**Date:** 2026-05-04.
**Scope:** v1 — turn the existing localhost web app into a single-binary
desktop app for macOS, Windows, and Linux. No new features.

## Why

The catalog is single-user, localhost-only, and reads/writes the user's own
filesystem. A browser tab is the wrong package for that:

- An app icon in the dock/start menu beats `npm run dev` + tab.
- Native file dialogs ("add a project root") and OS-level drag-drop become
  trivial.
- Running Live-process detection (`is_live_running`) is one syscall away
  instead of "ask the daemon over HTTP".
- No accidental "I closed the wrong tab" loss of UI state.
- No port-conflict on 7878 if you already have something else there.

What we keep: the entire backend (FastAPI, SQLite catalog, parser, journal,
proposals, MCP server). All the safety work just landed; throwing it out
would be expensive churn for no user benefit.

## Decision: Tauri, not Electron

**Tauri** wins for this project:
- Bundle size: ~10–15 MB vs Electron's ~150 MB. Important when distributing
  to your own machines.
- The shell is a native webview (WebView2 on Windows, WKWebView on macOS),
  not a bundled Chromium — less RAM, faster startup.
- Same React/Vite frontend. No frontend rewrite.
- First-class sidecar support for shipping a Python interpreter alongside.

What Tauri costs you:
- A Rust toolchain on the build machine (one-time install).
- A small Rust shell (`src-tauri/src/main.rs`) that spawns the sidecar and
  hosts the webview. Hundreds of lines, not thousands.

## Architecture: Python as a sidecar

```
+----------------------------------+
|       Tauri shell (Rust)         |
|  - spawns python sidecar on      |
|    app launch, kills on quit     |
|  - hosts WebView pointed at      |
|    http://127.0.0.1:<port>       |
|  - exposes invoke() for OS-only  |
|    actions (file picker, open    |
|    .als, native menu)            |
+----------------------------------+
            | spawn
            v
+----------------------------------+
|   audio-web (FastAPI, sidecar)   |
|  - same code as today            |
|  - bound to 127.0.0.1:<port>     |
|  - port written to a temp file   |
|    or stdout for shell to read   |
+----------------------------------+
            |
            v
+----------------------------------+
|       audio-core (lib)           |
|       SQLite at AUDIO_ROOT       |
+----------------------------------+
```

The sidecar IS the existing FastAPI app. No rewrite — just a different way
to launch and tear it down.

### Port discovery

Today the server hardcodes 7878. For a packaged app that's wrong: another
instance might be running, or another tool might own the port. New behavior:

1. Sidecar binds to port 0 (OS picks a free port).
2. Sidecar prints `AUDIO_WEB_PORT=<n>` to stdout on first line.
3. Tauri shell reads that line, points the WebView at `http://127.0.0.1:n`.
4. Sidecar still accepts `AUDIO_WEB_PORT` env var override for dev.

### Sidecar lifecycle

- `on_app_startup`: shell spawns sidecar, waits up to 10s for the port line.
  If no line by then: show an error dialog and quit.
- `on_app_shutdown`: shell sends SIGTERM (Windows: `CTRL_BREAK_EVENT`),
  waits 5s, force-kills if still alive. The sidecar registers a signal
  handler that closes the SQLite connection cleanly.
- Crash recovery: if the sidecar dies while the app is running, the shell
  shows a "backend crashed, restart?" dialog and offers to restart it. The
  journal's `reconcile_pending` runs at the next sidecar startup, so any
  in-flight batch is recorded as `interrupted` even across crashes.

### Bundling Python

Two options:

1. **Standalone Python via `python-build-standalone`** — Astral's pre-built
   self-contained interpreter (~30 MB). Bundle it inside the app, ship our
   `.venv` site-packages alongside. Cross-platform, no system Python
   required. Recommended.
2. **PyInstaller / PyOxidizer** — single executable. Smaller in theory but
   the gzip+lxml+blake3 native deps make this fiddly on Windows.

Go with (1). `uv` already produces a portable venv we can copy into
`src-tauri/binaries/`.

## OS integration (the "why bother" features)

These are post-MVP for the port, but they're the user-visible payoff:

- **Native file pickers** for adding a project root. Today AUDIO_ROOT is
  an env var; in the app you'd click "Library Settings → Add Root".
- **Open .als in Live** uses `os.startfile` already; native shell makes this
  faster (no HTTP roundtrip).
- **System tray icon** showing scan progress / proposal count.
- **Drag a folder onto the app icon** to scan it.
- **OS notifications** when a long scan finishes or a proposal needs review.
- **macOS / Windows native menu bar** with "Scan now", "Show journal",
  "Quit" instead of in-page buttons.

Each is one Tauri `invoke()` plus a frontend hook.

## Build pipeline

- Dev: `npm run tauri dev`. Vite serves the frontend; Tauri spawns the
  sidecar from the local venv. Hot reload still works.
- Release: `npm run tauri build` produces `.msi` (Windows), `.dmg` (macOS),
  `.deb`/`.AppImage` (Linux). Each contains: the Rust shell binary, the
  bundled Python interpreter, the audio_* packages, the built React app,
  and a signed manifest.

## Deferred from this v1 port

- **Auto-update.** Tauri has a built-in updater. Wire it later when there's
  more than one user.
- **Code signing.** macOS requires it for non-warning launch; Windows is
  optional but improves the SmartScreen prompt. Skip for the first build
  on your own machine; revisit if anyone else runs it.
- **MCP integration changes.** The MCP server stays a separate process you
  launch from Claude Desktop's config. The native app doesn't host it.
  (Could be added later via a "MCP server: on/off" toggle in Settings.)

## Migration plan (rough)

1. **Restructure repo.** Add `src-tauri/` at the root with `Cargo.toml`,
   `tauri.conf.json`, and `src/main.rs`. Keep `web/` and `packages/` as-is.
2. **Add port-discovery to FastAPI.** Argparse `--port` (defaults to 0,
   accepts an integer), print `AUDIO_WEB_PORT=<n>` on startup.
3. **Wire the Rust shell.** Spawn sidecar, capture stdout, point WebView.
4. **Bundle Python.** Add `python-build-standalone` artifacts to
   `src-tauri/binaries/` for each platform during CI build.
5. **Smoke test on each OS.** Approve→undo round-trip from the native app.
6. **Replace `uv run audio-web` in the README.** The new entry point is the
   app icon. Keep the CLI commands intact for headless use.
7. **(Optional, post-v1)** Migrate MoveProject's "pick a folder" UX from a
   text input to a native dialog.

## What I'm NOT changing

- The catalog database, schema, journal format. Same files, same paths.
- The frontend. Same Vite/React/TanStack code.
- The MCP server or its tool surface.
- The CLI (`audio` Typer commands).
- Any safety / journal / proposal correctness work.

## Risk

- **Tauri's WebView is platform-dependent.** WebView2 on Windows is auto-
  installed on Win11 (your machine — fine). On older Win10 we'd need to
  ship a bootstrapper. Not relevant for you, flag for distribution.
- **Sidecar startup time.** ~1–2s for FastAPI cold-start on a packaged
  Python. Show a splash screen during that wait.
- **First-time Rust toolchain install** is a one-time cost (~5 min).
- **Cross-platform builds** require CI runners for each target if you want
  to release on more than your own machine. Local-only builds are fine for
  one user.

## Estimate

- Skeleton (Rust shell + sidecar spawn + WebView) — half a day.
- Bundling Python + cross-platform packaging — 1–2 days.
- Polish (splash screen, crash dialog, native menu) — half a day.
- OS integrations (file picker, drag-drop, tray) — incremental, each ~1 hr.

Total: about 3 days to a working installable native app on Windows. macOS
and Linux follow the same recipe.

## Open question

Do you want this v1 port to also add the native-only features above (file
picker, drag-drop, tray), or strictly keep behavioral parity with the web
version and stack those features as follow-ups?

My recommendation: ship parity first (3 days, low risk), then add native
features incrementally as you find the moments where they'd matter.
