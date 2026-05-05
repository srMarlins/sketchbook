"""audio-web entrypoint.

Sidecar contract (used by the Tauri shell):
- Argument `--port <int>` (default 0) — bind to that port. 0 means "OS picks
  a free port", which is what the Tauri shell wants so port collisions never
  happen.
- On startup, the bound port is printed to stdout as a single line:
      AUDIO_WEB_PORT=<n>
  The shell reads this line, then points the WebView at http://127.0.0.1:<n>.
- Logs go to stderr; stdout is reserved for the port handshake (and any future
  protocol messages). Don't add `print(...)` to startup paths.
"""

from __future__ import annotations

import argparse
import socket
import sys

import uvicorn

from audio_web.app import create_app


def _claim_port(requested: int) -> int:
    """Bind to `requested` (0 = ask kernel for a free one), close, return.
    Race-y in theory — another process could grab the port between our close
    and uvicorn's bind — but on a single-user desktop that's not a real risk.
    The benefit: uvicorn binds to a known port we can announce up-front."""
    if requested != 0:
        return requested
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]
    finally:
        s.close()


def run() -> None:
    parser = argparse.ArgumentParser(prog="audio-web")
    parser.add_argument(
        "--port",
        type=int,
        default=int_env("AUDIO_WEB_PORT", 7878),
        help="TCP port to bind on 127.0.0.1. 0 = OS picks a free one.",
    )
    parser.add_argument(
        "--host",
        type=str,
        default="127.0.0.1",
        help="Bind host. Default 127.0.0.1; do not change unless you understand "
        "the security implications (the API is unauthenticated).",
    )
    args = parser.parse_args()
    port = _claim_port(args.port)
    # Sidecar handshake: announce the bound port BEFORE uvicorn starts, on a
    # single line, prefixed so the shell can grep for it without ambiguity.
    print(f"AUDIO_WEB_PORT={port}", flush=True)
    uvicorn.run(create_app(), host=args.host, port=port, log_level="info")


def int_env(name: str, default: int) -> int:
    """Read an env var as int, falling back to `default` on missing/invalid."""
    import os

    v = os.environ.get(name)
    if not v:
        return default
    try:
        return int(v)
    except ValueError:
        return default


if __name__ == "__main__":  # `python -m audio_web.main`
    run()
