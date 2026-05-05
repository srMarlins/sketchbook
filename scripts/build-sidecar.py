#!/usr/bin/env python3
"""Build the audio-web sidecar bundle for a target platform.

Pipeline (each step skippable when its inputs haven't changed):
  1. Download python-build-standalone for the target (cached on disk).
  2. Verify SHA256 against a pinned constant — fails loud on mismatch.
  3. Extract under src-tauri/binaries/<target>/python/.
  4. `uv build` workspace wheels, only when sources are newer than artifacts.
  5. `uv pip install` the workspace wheels into that python's site-packages.
  6. `cargo build --release` (or `--profile dev` with --debug) the launcher.
  7. Stage everything for Tauri:
       src-tauri/binaries/<target>/audio-web<.exe>   ← launcher
       src-tauri/binaries/<target>/python/...        ← interpreter + deps
       src-tauri/binaries/<target>/dist/...          ← built React app
       src-tauri/binaries/audio-web-<triple>(.exe)   ← Tauri externalBin
       src-tauri/binaries/python/...                 ← dev-mode walk-up target
       src-tauri/binaries/dist/...                   ← dev-mode walk-up target

Subcommands:
  build <target>   — full bundle build (default).
  sync             — fast incremental: rebuild wheels if needed, reinstall,
                     rebuild launcher, restage. Skips download/extract.
                     Used by Tauri's beforeDevCommand for hot iteration.

Usage:
  uv run scripts/build-sidecar.py <target>
  uv run scripts/build-sidecar.py sync
  uv run scripts/build-sidecar.py <target> --debug --no-download
"""

from __future__ import annotations

import argparse
import hashlib
import os
import platform
import shutil
import subprocess
import sys
import tarfile
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SRC_TAURI = REPO / "src-tauri"
BINARIES = SRC_TAURI / "binaries"

# Pin a known-good python-build-standalone release. Bump deliberately; changing
# the date changes the bundled CPython. To bump: query
#   https://api.github.com/repos/astral-sh/python-build-standalone/releases/latest
# and pick the latest 3.12 install_only asset. Avoid the `_stripped` flavor —
# it removes test files we don't need but also strips bytecode that lxml's
# install relies on at runtime on Windows.
PBS_DATE = "20260504"
PBS_PYTHON = "3.12.13"

# (target → (archive_name, target_triple, sha256))
# sha256 = None for targets we haven't independently verified yet. The script
# treats None as "fetch SHA256SUMS from upstream and trust on first use, then
# print the hex so a human can pin it here". Production targets MUST be pinned.
TARGETS: dict[str, tuple[str, str, str | None]] = {
    "windows-x86_64": (
        f"cpython-{PBS_PYTHON}+{PBS_DATE}-x86_64-pc-windows-msvc-install_only.tar.gz",
        "x86_64-pc-windows-msvc",
        "94024f71b93798700774521b8a5774c6ce9c435d97ca2c430418b2af36368541",
    ),
    "macos-arm64": (
        f"cpython-{PBS_PYTHON}+{PBS_DATE}-aarch64-apple-darwin-install_only.tar.gz",
        "aarch64-apple-darwin",
        None,
    ),
    "macos-x86_64": (
        f"cpython-{PBS_PYTHON}+{PBS_DATE}-x86_64-apple-darwin-install_only.tar.gz",
        "x86_64-apple-darwin",
        None,
    ),
    "linux-x86_64": (
        f"cpython-{PBS_PYTHON}+{PBS_DATE}-x86_64-unknown-linux-gnu-install_only.tar.gz",
        "x86_64-unknown-linux-gnu",
        None,
    ),
}

PBS_BASE_URL = (
    f"https://github.com/astral-sh/python-build-standalone/releases/download/{PBS_DATE}"
)


# ---------------------------- utilities ----------------------------

def _log(msg: str) -> None:
    # Force UTF-8 on stdout — Windows defaults to cp1252 in some shells, which
    # blows up on the `->` arrow if it sneaks back in. Reconfigure once.
    if not getattr(_log, "_configured", False):  # type: ignore[attr-defined]
        try:
            sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
        except (AttributeError, OSError):
            pass
        _log._configured = True  # type: ignore[attr-defined]
    print(msg, flush=True)


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _download(url: str, dest: Path, *, allow_download: bool) -> None:
    if dest.exists():
        _log(f"  cached {dest.name}")
        return
    if not allow_download:
        raise SystemExit(f"  --no-download set but {dest.name} not cached")
    dest.parent.mkdir(parents=True, exist_ok=True)
    _log(f"  downloading {url}")
    with urllib.request.urlopen(url) as r, open(dest, "wb") as f:
        shutil.copyfileobj(r, f)


def _verify_sha256(archive: Path, expected: str | None) -> None:
    actual = _sha256(archive)
    _log(f"  sha256: {actual}")
    if expected is None:
        _log(
            f"  WARNING: no sha256 pin for {archive.name} — pin it in TARGETS "
            f"after verifying out-of-band."
        )
        return
    if actual != expected:
        raise SystemExit(
            f"\nSHA256 mismatch for {archive.name}\n"
            f"  expected: {expected}\n"
            f"  got:      {actual}\n"
            f"Either the upstream archive changed (security risk!) or the pin is stale.\n"
            f"Verify out-of-band, then update the pin in scripts/build-sidecar.py."
        )


def _extract(archive: Path, dest: Path) -> None:
    if (dest / "python").exists():
        _log(f"  python tree already present at {dest / 'python'}")
        return
    dest.mkdir(parents=True, exist_ok=True)
    _log(f"  extracting {archive.name} → {dest}")
    with tarfile.open(archive, "r:gz") as tf:
        tf.extractall(dest)


def _bundled_python(target_dir: Path, target: str) -> Path:
    if target.startswith("windows"):
        return target_dir / "python" / "python.exe"
    return target_dir / "python" / "bin" / "python3"


def _newer_than(srcs: list[Path], dst: Path) -> bool:
    """True when any file under any src dir is newer than dst (or dst missing)."""
    if not dst.exists():
        return True
    dst_mtime = dst.stat().st_mtime
    for s in srcs:
        if s.is_file():
            if s.stat().st_mtime > dst_mtime:
                return True
            continue
        for p in s.rglob("*"):
            if p.is_file() and p.stat().st_mtime > dst_mtime:
                return True
    return False


# ---------------------------- pipeline steps ----------------------------

def _maybe_build_wheels() -> list[Path]:
    """Rebuild workspace wheels only when source files are newer than the
    existing dist/audio_*.whl files. Returns the list of wheel paths."""
    wheels_dir = REPO / "dist"
    wheels_dir.mkdir(exist_ok=True)
    wheels = sorted(wheels_dir.glob("audio_*-*.whl"))
    sources = [REPO / "packages" / pkg for pkg in ("core", "cli", "web", "mcp")]
    sources_dirty = (
        not wheels
        or any(_newer_than([s], min(wheels, key=lambda w: w.stat().st_mtime)) for s in sources)
    )
    if sources_dirty:
        _log("  building wheels (sources newer than artifacts)")
        # Wipe stale wheels so we don't accumulate old versions.
        for old in wheels_dir.glob("audio_*-*.whl"):
            old.unlink()
        for old in wheels_dir.glob("audio_*-*.tar.gz"):
            old.unlink()
        subprocess.check_call(
            ["uv", "build", "--out-dir", str(wheels_dir), "--all-packages"],
            cwd=REPO,
        )
        wheels = sorted(wheels_dir.glob("audio_*-*.whl"))
    else:
        _log("  wheels are fresh — skipping uv build")
    if not wheels:
        raise SystemExit("no wheels produced under dist/audio_*.whl")
    return wheels


def _install_wheels(python: Path, wheels: list[Path]) -> None:
    """Install workspace wheels into the bundled python.

    Uses `uv pip install --python <bundled>` which is markedly faster than
    `<bundled> -m pip install`. Reinstalls workspace packages without deps
    to avoid pip's "same-version skip" — we treat wheel mtime, not the
    version string, as the source of truth.

    Skipped entirely when a sentinel file's mtime is newer than every wheel,
    which is the common case when nothing has changed since last sync."""
    sentinel = python.parent / ".audio-wheels-installed"
    if sentinel.exists():
        sentinel_mtime = sentinel.stat().st_mtime
        if all(w.stat().st_mtime <= sentinel_mtime for w in wheels):
            _log("  wheels already installed at this version — skipping pip")
            return

    _log(f"  uv pip install workspace wheels into {python}")
    # First-pass: install with deps so PyPI dependencies resolve.
    subprocess.check_call(
        [
            "uv", "pip", "install",
            "--python", str(python),
            "--no-cache",
            *[str(w) for w in wheels],
        ]
    )
    # Second-pass: force-reinstall just our wheels (no deps) so any dev edit
    # to source actually replaces installed bytes — `uv pip install` skips
    # files when the version string is unchanged.
    subprocess.check_call(
        [
            "uv", "pip", "install",
            "--python", str(python),
            "--no-cache",
            "--reinstall-package", "audio-core",
            "--reinstall-package", "audio-web",
            "--reinstall-package", "audio-cli",
            "--reinstall-package", "audio-mcp",
            "--no-deps",
            *[str(w) for w in wheels],
        ]
    )
    sentinel.touch()


def _build_launcher(triple: str, *, debug: bool) -> Path:
    profile = "dev" if debug else "release"
    out_subdir = "debug" if debug else "release"
    _log(f"  cargo build (--profile {profile}) launcher for {triple}")
    launcher_dir = SRC_TAURI / "launcher"
    cmd = [
        "cargo", "build",
        "--profile", profile,
        "--target", triple,
        "--manifest-path", str(launcher_dir / "Cargo.toml"),
    ]
    subprocess.check_call(cmd)
    exe_name = "audio-web.exe" if triple.endswith("windows-msvc") else "audio-web"
    return launcher_dir / "target" / triple / out_subdir / exe_name


def _stage_dist(target_dir: Path) -> None:
    """Copy the React build into <target_dir>/dist. Hard-fail if missing —
    a sidecar bundle without a UI is broken in production."""
    src = REPO / "web" / "dist"
    if not src.is_dir():
        raise SystemExit(
            f"  ERROR: {src} does not exist — run `npm run build --prefix web` first.\n"
            f"  A sidecar bundle without a UI tree will produce a blank app."
        )
    dst = target_dir / "dist"
    if dst.exists():
        shutil.rmtree(dst)
    _log(f"  staging React build → {dst}")
    shutil.copytree(src, dst)


def _stage_launcher(launcher: Path, target_dir: Path, triple: str) -> None:
    """Copy launcher into target_dir, AND also into binaries/audio-web-<triple>
    so Tauri's externalBin resolution finds it.

    The launcher locates its bundled python by looking at `./python/` next to
    its own exe — so we also mirror `python/` and `dist/` directly under
    `binaries/` next to the registered Tauri launcher copy. This is what makes
    the dev walk-up resolution work; production bundling instead ships the
    target_dir tree via tauri.conf.json's `bundle.resources`.
    """
    target_exe = target_dir / launcher.name
    _log(f"  staging launcher → {target_exe}")
    shutil.copy(launcher, target_exe)
    suffix = ".exe" if launcher.suffix == ".exe" else ""
    final = BINARIES / f"audio-web-{triple}{suffix}"
    _log(f"  registering with Tauri at {final}")
    shutil.copy(launcher, final)
    for sibling in ("python", "dist"):
        src = target_dir / sibling
        if not src.is_dir():
            continue
        dst = BINARIES / sibling
        if dst.exists():
            shutil.rmtree(dst)
        _log(f"  mirroring {sibling}/ → {dst}")
        shutil.copytree(src, dst, symlinks=True)


def _detect_host_target() -> str:
    """For `sync`: figure out which target the host is currently running on."""
    sys_name = platform.system().lower()
    machine = platform.machine().lower()
    if sys_name == "windows":
        return "windows-x86_64"
    if sys_name == "darwin":
        return "macos-arm64" if machine in {"arm64", "aarch64"} else "macos-x86_64"
    if sys_name == "linux":
        return "linux-x86_64"
    raise SystemExit(f"unsupported host: {sys_name}/{machine}")


# ---------------------------- entrypoints ----------------------------

def cmd_build(target: str, *, debug: bool, allow_download: bool) -> None:
    archive_name, triple, expected_sha = TARGETS[target]
    target_dir = BINARIES / target
    archive_path = BINARIES / "_cache" / archive_name

    _log(f"== building sidecar for {target} ({triple})")
    _download(f"{PBS_BASE_URL}/{archive_name}", archive_path, allow_download=allow_download)
    _verify_sha256(archive_path, expected_sha)
    _extract(archive_path, target_dir)
    python = _bundled_python(target_dir, target)
    if not python.exists():
        raise SystemExit(f"bundled python not found at {python}")
    wheels = _maybe_build_wheels()
    _install_wheels(python, wheels)
    launcher = _build_launcher(triple, debug=debug)
    _stage_dist(target_dir)
    _stage_launcher(launcher, target_dir, triple)
    _log(f"== sidecar bundle ready at {target_dir}")


def cmd_sync(*, debug: bool) -> None:
    """Fast incremental rebuild — assumes the python tarball is already
    extracted. Skips download, extract, and SHA verification. Used by
    Tauri's beforeDevCommand."""
    target = _detect_host_target()
    archive_name, triple, _ = TARGETS[target]
    target_dir = BINARIES / target
    python = _bundled_python(target_dir, target)
    if not python.exists():
        raise SystemExit(
            f"sync requires a prior full build — run\n"
            f"  uv run scripts/build-sidecar.py {target}"
        )
    _log(f"== sync ({target})")
    wheels = _maybe_build_wheels()
    _install_wheels(python, wheels)
    launcher = _build_launcher(triple, debug=debug)
    # Skip _stage_dist on sync — vite dev server provides the UI in dev mode,
    # and a stale dist/ from an earlier build doesn't break the sidecar.
    if (REPO / "web" / "dist").is_dir():
        _stage_dist(target_dir)
    _stage_launcher(launcher, target_dir, triple)
    _log("== sync complete")


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd")
    # Default-positional shape kept for backward compatibility:
    # `uv run scripts/build-sidecar.py windows-x86_64` should still work.
    parser.add_argument("target", nargs="?", choices=sorted(TARGETS))
    parser.add_argument("--debug", action="store_true",
                        help="build launcher with --profile dev (faster iteration)")
    parser.add_argument("--no-download", action="store_true",
                        help="fail if the PBS archive isn't already cached")

    sync = sub.add_parser("sync", help="fast incremental rebuild of host target")
    sync.add_argument("--debug", action="store_true")

    args = parser.parse_args()

    if args.cmd == "sync":
        cmd_sync(debug=args.debug)
        return
    if not args.target:
        parser.error("target is required (or use the `sync` subcommand)")
    cmd_build(args.target, debug=args.debug, allow_download=not args.no_download)


if __name__ == "__main__":
    main()
