"""Workspace-relative paths shared by cli/web/mcp.

The single env override is `AUDIO_ROOT`, which points at the workspace root.
"""

from __future__ import annotations

import os
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
