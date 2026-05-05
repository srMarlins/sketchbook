"""Workspace-relative paths shared by cli/web/mcp.

The single env override is `AUDIO_ROOT`, which points at the workspace root.
"""

from __future__ import annotations

import os
import tomllib
from pathlib import Path


def workspace_root() -> Path:
    return Path(os.environ.get("AUDIO_ROOT", "Z:/User/audio")).resolve()


def projects_root() -> Path:
    return workspace_root() / "Projects"


def db_path() -> Path:
    return workspace_root() / "data" / "catalog.db"


def journal_dir() -> Path:
    return workspace_root() / "data" / "journal"


def proposals_dir() -> Path:
    return workspace_root() / "data" / "proposals"


def _config_toml() -> dict:
    p = workspace_root() / "config.toml"
    if not p.is_file():
        return {}
    try:
        return tomllib.loads(p.read_text(encoding="utf-8"))
    except (OSError, tomllib.TOMLDecodeError):
        return {}


def sample_roots() -> list[Path]:
    """Extra audio-file roots the sample indexer walks (in addition to
    Projects/). Configured in `config.toml` as `sample_roots = ["...", ...]`.
    Nonexistent or unreadable entries are silently dropped — the catalog
    still works without them, and a typo shouldn't crash the indexer."""
    raw = _config_toml().get("sample_roots", [])
    out: list[Path] = []
    for entry in raw:
        try:
            p = Path(entry).resolve()
        except (OSError, ValueError):
            continue
        if p.is_dir():
            out.append(p)
    return out
