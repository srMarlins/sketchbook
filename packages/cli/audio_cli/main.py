from __future__ import annotations

import typer
from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_root
from rich.console import Console

from audio_cli.config import db_path, projects_root

app = typer.Typer(help="Audio: Ableton catalog & organizer", no_args_is_help=True)
con = Console()


@app.command()
def version() -> None:
    """Show the audio-cli version."""
    from importlib.metadata import version as _v

    con.print(_v("audio-cli"))


@app.command()
def scan(
    quiet: bool = typer.Option(False, "--quiet", "-q", help="Suppress per-file progress."),
) -> None:
    """Scan the projects root and update the catalog."""
    conn = open_db(db_path())
    on_progress = (
        None if quiet else (lambda p, s: con.print(f"[dim]{s}[/dim] {p}", highlight=False))
    )
    stats = scan_root(conn, projects_root(), on_progress=on_progress)
    con.print(
        f"[bold]scanned:[/bold] {stats.scanned}  skipped: {stats.skipped}  failed: {stats.failed}"
    )
