"""Re-export the shared workspace config from audio_core for CLI users."""

from audio_core.config import db_path, journal_dir, projects_root, proposals_dir, workspace_root

__all__ = ["db_path", "journal_dir", "projects_root", "proposals_dir", "workspace_root"]
