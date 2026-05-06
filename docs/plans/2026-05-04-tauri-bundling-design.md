# Tauri sidecar bundling — design

**Status:** SUPERSEDED 2026-05-05. Tauri + Python sidecar is no longer the desktop shell; the Kotlin/Compose Multiplatform rewrite (`2026-05-05-sync-versioning-design.md`, `2026-05-05-kotlin-rewrite-impl-plan.md`) replaces it entirely. This file is preserved for historical context — do not implement against it.

**Date:** 2026-05-04.
**Scope:** how to ship the FastAPI backend as a Tauri sidecar binary that runs
on a user's machine without requiring a system Python install. Windows-first;
macOS and Linux follow the same recipe.

## Constraints

- **No system Python required.** The user can install the .msi and the app
  works. We bundle our own interpreter.
- **Reasonable bundle size.** Standalone CPython is ~30 MB; our deps add
  another ~20 MB. Total app weight ~50–80 MB — fine for desktop.
- **Reproducible.** The build pipeline is a script in the repo, not manual
  steps.
- **Cross-platform-ready.** Windows first, but the recipe should be the same
  shape on macOS and Linux so we're not painting ourselves into a corner.

## Approach: python-build-standalone + relocatable venv

We use [Astral's python-build-standalone](https://github.com/astral-sh/python-build-standalone)
distributions, which are pre-built fully self-contained CPython tarballs
designed to relocate. Apple ships one for arm64 / x86_64; PSF doesn't ship
relocatable builds.

The pipeline (per platform):

1. **Download** the matching `python-build-standalone` archive into
   `src-tauri/binaries/<platform>/`.
2. **Extract** to `src-tauri/binaries/<platform>/python/`.
3. **Pip-install** our `audio-core` and `audio-web` wheels into that
   interpreter's site-packages (`python -m pip install ...`). uv produces
   wheels via `uv build`.
4. **Generate a launcher** at `src-tauri/binaries/audio-web<.exe>` whose only
   job is `exec python -m audio_web.main "$@"` with the bundled python.
   - Windows: a tiny C program built once, or a `.exe` produced from a
     hand-written Rust stub. Simplest: a 50-line Rust binary that finds
     the bundled python relative to its own exe path and execs it.
   - macOS / Linux: a shell script with `#!/bin/sh` shebang that does the
     same. Marked executable, packaged inside the .app bundle.
5. **Tauri externalBin** picks up `binaries/audio-web` (the launcher) and
   wraps it into the installer.

## Why the launcher layer

Tauri's `externalBin` expects a single executable to exec. python-build-standalone
gives us a directory tree, not a single binary. The launcher is the
indirection that lets Tauri's sidecar API work unchanged.

```
binaries/
├── audio-web.exe          ← Tauri's sidecar entrypoint (~50KB)
└── python/
    ├── python.exe
    ├── Lib/site-packages/audio_core/...
    ├── Lib/site-packages/audio_web/...
    └── ...                ← ~50MB total
```

The launcher's pseudocode:

```rust
// src-tauri/launcher/src/main.rs
use std::env;
use std::process::{Command, ExitCode};

fn main() -> ExitCode {
    let exe = env::current_exe().expect("current_exe");
    let dir = exe.parent().unwrap();
    let python = dir.join("python").join(if cfg!(windows) { "python.exe" } else { "bin/python3" });
    let args: Vec<String> = env::args().skip(1).collect();
    let status = Command::new(&python)
        .arg("-m").arg("audio_web.main")
        .args(&args)
        .status()
        .expect("could not spawn bundled python");
    ExitCode::from(status.code().unwrap_or(1) as u8)
}
```

This is ~50 lines, zero deps, compiles to a tiny exe. It also gives us a
clean place to set `AUDIO_WEB_DIST` (path to the bundled React assets) and
`AUDIO_ROOT` (default workspace location, e.g. `~/.audio`) before exec.

## Build script

```
scripts/build-sidecar.py <target>
```

Where `<target>` is `windows-x86_64`, `macos-arm64`, etc. The script:

1. Resolves the python-build-standalone URL for `<target>` (hardcoded version pin).
2. Downloads + verifies sha256.
3. Extracts to `src-tauri/binaries/<target>/python/`.
4. Runs `pip install` (using the bundled python) of the workspace wheels.
   Wheels are produced via `uv build` against the workspace.
5. Compiles the launcher: `cargo build --release --manifest-path src-tauri/launcher/Cargo.toml --target <triple>`.
6. Copies the launcher to `src-tauri/binaries/audio-web` (no platform suffix
   — Tauri appends `-{target_triple}` itself when packaging).

`tauri.conf.json` references `binaries/audio-web`. Tauri at build time looks
for `binaries/audio-web-{TARGET}` (e.g. `binaries/audio-web-x86_64-pc-windows-msvc.exe`),
so the build script's last step is to copy/rename appropriately for the
host's target triple.

## Bundle layout (final installed)

```
audio.app/                           (or C:\Program Files\audio\)
├── audio.exe                        (Tauri shell, the icon you click)
└── resources/
    ├── audio-web.exe                (sidecar launcher)
    └── python/
        ├── python.exe
        └── Lib/site-packages/...    (audio_core, audio_web, fastapi, lxml, …)
```

The Tauri shell knows about `audio-web.exe` via `externalBin`; everything
under `resources/` is auto-shipped.

## Frontend bundling

The React build (`web/dist/`) is included in the Tauri bundle as a separate
resource path, accessed by FastAPI via the `AUDIO_WEB_DIST` env var (set by
the launcher). This keeps a single origin for all HTTP traffic — both `/`
(static React) and `/api/*` come out of FastAPI.

Alternative considered: have Tauri load the React app from `frontendDist`
(embedded resource) and have the React app fetch `/api/*` cross-origin to
`http://127.0.0.1:<port>`. Rejected because:

- Forces CORS configuration on FastAPI (security exposure increases).
- React app needs to know the port at runtime, which means a Tauri
  `invoke()` to read the port from the shell — extra moving parts.
- Same-origin keeps the API surface symmetric with the dev experience
  (where Vite proxies `/api/*` to FastAPI).

## Per-platform notes

### Windows
- Standalone tarball: `cpython-3.12.<n>+<date>-x86_64-pc-windows-msvc-install_only.tar.gz`
- WebView2 is preinstalled on Win11. Win10 ships an Edge-Chromium webview;
  Tauri shows a bootstrapper if missing.
- Code signing optional; SmartScreen will warn first-run without it.

### macOS
- Tarball: `cpython-3.12.<n>+<date>-aarch64-apple-darwin-install_only.tar.gz` (M-series) and the x86_64 variant.
- Universal binary requires building both targets and `lipo`'ing the launcher.
- Code signing + notarization required for non-warning launch on Gatekeeper.
  Pay-as-you-go: skip until you actually distribute.

### Linux
- Tarball: `cpython-3.12.<n>+<date>-x86_64-unknown-linux-gnu-install_only.tar.gz`
- AppImage and .deb both supported by Tauri. AppImage is the easier "drop
  on the desktop and run" target.
- WebKit2GTK 4.1 must be installed by the user (or AppImage bundles it).

## Test plan

- **Smoke**: launch the bundled app on a fresh Windows VM with no Python
  installed. Verify the splash → main window flow, then approve→undo a
  proposal. The smoke test in `tools/smoke_real_backend.py` is reusable
  here — run it via TestClient against the bundled FastAPI.
- **Sidecar lifecycle**: kill the sidecar manually (Task Manager). Verify
  the shell shows the `sidecar-died` toast and offers a restart action.
- **Multiple instances**: launch the app twice. Verify each gets its own
  port (no collision) and each has independent state (the data dir is
  intentionally shared, so they read the same catalog — that's expected
  for v1; multi-instance isolation is a future feature).
- **Path bundling**: verify `AUDIO_ROOT` defaults to a sensible per-OS
  location when not set:
  - Windows: `%APPDATA%\audio`
  - macOS: `~/Library/Application Support/audio`
  - Linux: `~/.local/share/audio`
  This is a `audio_core/config.py` change — the current default is hardcoded
  to `Z:/User/audio`, which is wrong for an installed app.

## Estimate

- Build script (download + extract + pip install + launcher) — half a day.
- Launcher binary — 1 hour.
- Tauri config glue — 1 hour.
- First successful Windows .msi build — half a day of debugging.
- macOS / Linux builds — 1 hour each once Windows works.

Total: ~2 days for a one-platform installable app, ~3 days for all three.

## What I'm NOT building yet

- Auto-updater. Tauri has a built-in one but configuring it requires a
  release server. Skip until there's more than one user.
- Code signing. Requires Apple Developer ID / Authenticode cert. Skip for
  personal use.
- Universal macOS binaries. Build x86_64 and arm64 separately for now;
  combine later if needed.
- Reduced Python distribution. The full python-build-standalone tarball
  includes `idle`, `tkinter`, etc. — we don't use any of that. Could shave
  ~10MB by using the `install_only` flavor's `--strip-tests` extras, but
  not worth the time vs. just shipping the full thing.

## Open questions for the implementer

1. **Default `AUDIO_ROOT` per-OS** (the `Z:/User/audio` hardcode in
   `audio_core/config.py`). What's the desired location for the user's
   library on macOS / Linux? Suggested defaults above.
2. **First-run UX.** When `AUDIO_ROOT` is empty (no projects scanned yet),
   what does the home page show? Suggest a "pick your library folder"
   onboarding screen using the native file picker.
3. **MCP server.** Should the Tauri shell also spawn `audio-mcp` so
   Claude Desktop can connect to the same backend, or keep MCP as a
   separately-launched stdio server? Suggest: keep separate, document the
   `claude_desktop_config.json` snippet in the README.
