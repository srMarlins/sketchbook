from __future__ import annotations

import sqlite3
from pathlib import Path as _Path

import typer
from audio_core.actions.archive import ArchiveProject
from audio_core.actions.move import MoveProject
from audio_core.actions.rename import RenameProject
from audio_core.actions.runner import run_batch
from audio_core.actions.set_color_tag import SetColorTag
from audio_core.actions.set_tags import SetTags
from audio_core.actions.undo import undo_batch
from audio_core.db.connection import open_db
from audio_core.db.projects import search_projects
from audio_core.journal.manifest import list_batches
from audio_core.scanner.scan import scan_root
from rich.console import Console
from rich.table import Table

from audio_cli.config import db_path, journal_dir, projects_root

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


@app.command()
def search(
    query: str = typer.Option(None, "--query", "-q", help="FTS query (name, plugin, sample)."),
    tempo_min: float = typer.Option(None, "--tempo-min", help="Minimum tempo (BPM)."),
    tempo_max: float = typer.Option(None, "--tempo-max", help="Maximum tempo (BPM)."),
    archived: bool = typer.Option(
        False, "--archived/--no-archived", help="Show only archived projects."
    ),
    limit: int = typer.Option(50, "--limit", help="Max rows."),
) -> None:
    """Search the catalog. Combine flags freely."""
    conn = open_db(db_path())
    rows = search_projects(
        conn,
        query=query,
        tempo_min=tempo_min,
        tempo_max=tempo_max,
        archived=archived,
        limit=limit,
    )
    if not rows:
        con.print("[dim]no results[/dim]")
        return
    table = Table(show_lines=False)
    table.add_column("id", justify="right", style="cyan", no_wrap=True)
    table.add_column("name")
    table.add_column("tempo", justify="right")
    table.add_column("ts")
    table.add_column("tracks", justify="right")
    table.add_column("live")
    table.add_column("path", overflow="fold")
    for r in rows:
        ts = f"{r['time_sig_num']}/{r['time_sig_den']}" if r["time_sig_num"] is not None else ""
        table.add_row(
            str(r["id"]),
            r["name"],
            f"{r['tempo']:.0f}" if r["tempo"] is not None else "",
            ts,
            str(r["track_count"]),
            r["live_version"] or "",
            r["path"],
        )
    con.print(table)


@app.command()
def show(project_id: int = typer.Argument(..., help="Project id from `audio search`.")) -> None:
    """Show full details for a project: metadata + plugins + samples."""
    conn = open_db(db_path())
    conn.row_factory = sqlite3.Row
    row = conn.execute("SELECT * FROM projects WHERE id=?", (project_id,)).fetchone()
    if row is None:
        con.print(f"[red]no project with id={project_id}[/red]")
        raise typer.Exit(code=1)

    con.print(f"[bold cyan]{row['name']}[/bold cyan]  (id={row['id']})")
    con.print(f"  path        {row['path']}")
    con.print(f"  parent      {row['parent_dir']}")
    con.print(
        f"  tempo       {row['tempo']}  "
        f"ts {row['time_sig_num']}/{row['time_sig_den']}  "
        f"live {row['live_version']}"
    )
    con.print(
        f"  tracks      {row['track_count']} "
        f"(audio {row['audio_tracks']} / midi {row['midi_tracks']} / return {row['return_tracks']})"
    )
    con.print(f"  archived    {'yes' if row['is_archived'] else 'no'}  color {row['color_tag']}")

    plugins = conn.execute(
        "SELECT plugin_name, plugin_type, track_name FROM project_plugins "
        "WHERE project_id=? ORDER BY plugin_type, plugin_name",
        (project_id,),
    ).fetchall()
    if plugins:
        con.print(f"\n[bold]plugins[/bold] ({len(plugins)})")
        ptable = Table(box=None, show_header=False)
        ptable.add_column("type", style="dim")
        ptable.add_column("name")
        ptable.add_column("track", style="dim")
        for p in plugins:
            ptable.add_row(p["plugin_type"], p["plugin_name"], p["track_name"] or "")
        con.print(ptable)

    samples = conn.execute(
        "SELECT sample_path FROM project_samples WHERE project_id=? ORDER BY sample_path",
        (project_id,),
    ).fetchall()
    if samples:
        con.print(f"\n[bold]samples[/bold] ({len(samples)})")
        for s in samples[:20]:
            con.print(f"  {s['sample_path']}")
        if len(samples) > 20:
            con.print(f"  [dim]... and {len(samples) - 20} more[/dim]")


# ---------------------------------------------------------------------------
# Mutating commands — each builds a single-action batch via run_batch so it
# lands in the journal and is reversible by `audio undo`.
# ---------------------------------------------------------------------------


def _run(actions, *, actor: str = "user") -> str:
    conn = open_db(db_path())
    return run_batch(conn, actions, actor=actor, journal_dir=journal_dir())


@app.command()
def rename(
    project_id: int = typer.Argument(..., help="Project id from `audio search`."),
    new_dir_name: str = typer.Argument(..., help="New directory name (basename only)."),
) -> None:
    """Rename a project's containing directory."""
    bid = _run(
        [RenameProject(project_id=project_id, new_dir_name=new_dir_name, root=projects_root())]
    )
    con.print(f"renamed (batch {bid})")


@app.command()
def move(
    project_id: int = typer.Argument(...),
    new_parent: str = typer.Argument(..., help="New parent directory (under projects root)."),
) -> None:
    """Move a project to a new parent directory."""
    bid = _run(
        [MoveProject(project_id=project_id, new_parent=_Path(new_parent), root=projects_root())]
    )
    con.print(f"moved (batch {bid})")


@app.command()
def archive(project_id: int = typer.Argument(...)) -> None:
    """Move a project into <projects-root>/_Archive/ and flag is_archived=1."""
    bid = _run([ArchiveProject(project_id=project_id, root=projects_root())])
    con.print(f"archived (batch {bid})")


@app.command()
def color(
    project_id: int = typer.Argument(...),
    color: int | None = typer.Argument(None, help="Ableton palette index 0..13, or omit to clear."),
) -> None:
    """Set or clear a project's color tag (Ableton palette 0..13)."""
    bid = _run([SetColorTag(project_id=project_id, color=color)])
    con.print(f"color set (batch {bid})")


@app.command()
def tag(
    project_id: int = typer.Argument(...),
    tags: list[str] = typer.Argument(None, help="Tags to assign (replaces previous set)."),
) -> None:
    """Replace a project's tag set."""
    bid = _run([SetTags(project_id=project_id, tags=list(tags or []))])
    con.print(f"tags set (batch {bid})")


@app.command()
def undo(
    batch_id: str = typer.Argument(
        "last", help='Batch id to undo, or "last" for the most recent batch.'
    ),
) -> None:
    """Reverse a journaled batch."""
    conn = open_db(db_path())
    if batch_id == "last":
        batches = list_batches(journal_dir())
        if not batches:
            con.print("[red]no batches in the journal[/red]")
            raise typer.Exit(code=1)
        batch_id = batches[-1]["batch_id"]
    undo_batch(conn, journal_dir(), batch_id)
    con.print(f"undone batch {batch_id}")


@app.command()
def journal(
    limit: int = typer.Option(20, "--limit", help="Max rows."),
) -> None:
    """List recent journal batches."""
    batches = list_batches(journal_dir())
    if not batches:
        con.print("[dim]journal is empty[/dim]")
        return
    table = Table()
    table.add_column("batch_id", style="cyan")
    table.add_column("actor", style="dim")
    table.add_column("actions")
    for b in batches[-limit:]:
        types = ", ".join(a.get("type", "?") for a in b["actions"])
        table.add_row(b["batch_id"], b["actor"], types)
    con.print(table)
