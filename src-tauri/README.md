# audio — native shell (Tauri)

This directory holds the Rust shell that wraps the FastAPI backend and React
frontend into a single desktop app via [Tauri 2](https://tauri.app/).

The shell:

1. Spawns the `audio-web` sidecar with `--port 0` (kernel picks a free port).
2. Reads stdout looking for the handshake line `AUDIO_WEB_PORT=<n>`.
3. Opens the main window pointed at `http://127.0.0.1:<n>`.
4. Kills the sidecar on app exit.

The React app and the API are served from the same origin (FastAPI mounts
`web/dist/` at `/` when `AUDIO_WEB_DIST` is set), so frontend code uses
plain `/api/*` URLs without knowing the port.

## Prerequisites

One-time per machine:

| Tool | Why | Install |
|---|---|---|
| Rust ≥ 1.77 | Build the Tauri shell | https://rustup.rs |
| `tauri` CLI | Drives dev/build | `cargo install tauri-cli --version "^2.0"` (or rely on the npm devDep `@tauri-apps/cli`) |
| Node + npm | Frontend toolchain | already required for `web/` |
| Python 3.12 + uv | Backend toolchain | already required |
| WebView2 (Windows) | Web rendering | preinstalled on Windows 11 |

On macOS additionally: Xcode Command Line Tools (`xcode-select --install`).
On Linux: `webkit2gtk-4.1` and `libayatana-appindicator3-dev`.

## Dev workflow

First-time setup on a fresh checkout (downloads python-build-standalone,
verifies SHA256, extracts, installs wheels, builds the Rust launcher):

```bash
uv run scripts/build-sidecar.py windows-x86_64
```

After that, day-to-day:

```bash
# from web/
npm install            # installs @tauri-apps/cli too
npm run tauri:dev      # syncs sidecar + boots vite + spawns + opens window
```

`tauri:dev` runs `tauri:sync` first (incremental rebuild of wheels and
launcher only when sources changed — typically <2s no-op), then starts vite,
then Tauri spawns the sidecar via the registered launcher binary.

Manual one-shot resync (e.g. after a `git pull` that changed Python or
launcher code while the dev session was running):

```bash
npm run tauri:sync --prefix web
```

Set `AUDIO_LAUNCHER_DEBUG=1` before launch to get verbose path-resolution
logs from the launcher; off by default.

## Production build (Windows-first)

`npm run tauri:build` chains everything: builds the React app, runs the
sidecar build pipeline (download/verify/extract/install/launcher-release),
and then `tauri build` packages the `.msi` installer.

Steps in detail:

1. **React.** `npm run build` (run automatically) produces `web/dist/`.
2. **Sidecar.** `uv run scripts/build-sidecar.py windows-x86_64` (run
   automatically). Downloads python-build-standalone, verifies SHA256
   against the pin in the script, extracts under `src-tauri/binaries/`,
   `uv pip install`s the workspace wheels, builds the launcher in release
   profile, and stages everything for Tauri.
3. **Bundle.** `tauri build` reads `bundle.externalBin` (the launcher) and
   `bundle.resources` (`binaries/python/`, `binaries/dist/`) and packs them
   into the installer. At runtime the launcher reads `$AUDIO_BUNDLE_DIR`
   (set by the Rust shell to `resource_dir()`) to find python.

Cross-platform release: each target needs a CI runner of that OS. The pin
table in `scripts/build-sidecar.py::TARGETS` lists all four targets but
non-windows SHA256s are unset — verify and pin them when first building
on those platforms.

## Sidecar contract

Defined and parsed in two places — keep them in sync:

- **Backend prints the handshake**: `packages/web/audio_web/main.py`
- **Shell parses it**: `src-tauri/src/lib.rs::wait_for_port`

Protocol:

- One line on stdout, on first startup, exactly: `AUDIO_WEB_PORT=<n>\n`.
- Every subsequent line on stdout/stderr is treated as log output and
  forwarded to `log::info!` / `log::warn!`.
- If the sidecar exits before the handshake, the shell shows a fatal-error
  splash. If it exits after, the shell emits a `sidecar-died` event.

## Layout

```
src-tauri/
├── Cargo.toml          # Rust shell dependencies
├── build.rs            # tauri-build glue
├── tauri.conf.json     # window, bundle, sidecar config
├── src/
│   ├── main.rs         # binary entry
│   └── lib.rs          # boot + sidecar wiring
├── icons/              # app icons (generated; only source.png checked in)
└── binaries/           # sidecar binaries (built, never committed)
```

## What's NOT done yet

- Python sidecar bundling pipeline (task 19 — see design doc).
- Native menu bar (File / Edit / Help with stock items).
- Tray icon for "scan in progress / N proposals pending".
- Native file picker for adding project roots (would replace the
  `AUDIO_ROOT` env variable for end users).
- Auto-update via Tauri's built-in updater.

These are enumerated as TODOs in the design doc; pick them up in v1.1.
