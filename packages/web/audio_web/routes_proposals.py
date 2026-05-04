from __future__ import annotations

import json
import uuid
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from audio_core.actions.archive import ArchiveProject
from audio_core.actions.move import MoveProject
from audio_core.actions.rename import RenameProject
from audio_core.actions.runner import run_batch
from audio_core.actions.set_color_tag import SetColorTag
from audio_core.actions.set_tags import SetTags
from audio_core.config import db_path, journal_dir, projects_root, proposals_dir
from audio_core.db.connection import open_db
from fastapi import APIRouter, HTTPException

from audio_web.schemas import ProposalIn, ProposalOut

router = APIRouter(prefix="/api/proposals", tags=["proposals"])


def _new_proposal_id() -> str:
    return f"{datetime.now(UTC).strftime('%Y-%m-%dT%H-%M-%S')}_{uuid.uuid4().hex[:8]}"


def _proposal_path(proposal_id: str) -> Path:
    return proposals_dir() / f"{proposal_id}.json"


@router.post("", status_code=201)
def submit_proposal(body: ProposalIn) -> dict:
    proposals_dir().mkdir(parents=True, exist_ok=True)
    pid = _new_proposal_id()
    payload = {
        "proposal_id": pid,
        "actor": body.actor,
        "actions": [a.model_dump() for a in body.actions],
        "rationale": body.rationale,
    }
    _proposal_path(pid).write_text(json.dumps(payload, indent=2), encoding="utf-8")
    return {"proposal_id": pid}


@router.get("")
def list_proposals() -> list[ProposalOut]:
    d = proposals_dir()
    if not d.exists():
        return []
    return [
        ProposalOut.model_validate(json.loads(p.read_text(encoding="utf-8")))
        for p in sorted(d.glob("*.json"))
    ]


@router.get("/{proposal_id}")
def get_proposal(proposal_id: str) -> ProposalOut:
    p = _proposal_path(proposal_id)
    if not p.exists():
        raise HTTPException(status_code=404, detail=f"no proposal id={proposal_id}")
    return ProposalOut.model_validate(json.loads(p.read_text(encoding="utf-8")))


def _materialize(action_type: str, args: dict[str, Any]):
    """Build the concrete Action instance from a (type, args) pair, applying
    the path-allowlist root from config."""
    root = projects_root()
    if action_type == "RenameProject":
        return RenameProject(
            project_id=int(args["project_id"]),
            new_dir_name=str(args["new_dir_name"]),
            root=root,
        )
    if action_type == "MoveProject":
        return MoveProject(
            project_id=int(args["project_id"]),
            new_parent=Path(args["new_parent"]),
            root=root,
        )
    if action_type == "ArchiveProject":
        return ArchiveProject(project_id=int(args["project_id"]), root=root)
    if action_type == "SetColorTag":
        return SetColorTag(project_id=int(args["project_id"]), color=args.get("color"))
    if action_type == "SetTags":
        return SetTags(project_id=int(args["project_id"]), tags=list(args.get("tags") or []))
    raise HTTPException(status_code=400, detail=f"unknown action type: {action_type!r}")


@router.post("/{proposal_id}/approve")
def approve_proposal(proposal_id: str) -> dict:
    """Approve and execute a proposal. Returns the journal batch_id."""
    p = _proposal_path(proposal_id)
    if not p.exists():
        raise HTTPException(status_code=404, detail=f"no proposal id={proposal_id}")
    proposal = json.loads(p.read_text(encoding="utf-8"))
    actor = proposal.get("actor") or "claude"
    actions = [_materialize(a["type"], a["args"]) for a in proposal["actions"]]
    conn = open_db(db_path())
    try:
        bid = run_batch(conn, actions, actor=actor, journal_dir=journal_dir())
    except (PermissionError, FileExistsError, LookupError, ValueError, RuntimeError) as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    # Archive the proposal alongside the executed batch.
    p.unlink()
    return {"batch_id": bid}


@router.delete("/{proposal_id}", status_code=204)
def reject_proposal(proposal_id: str) -> None:
    p = _proposal_path(proposal_id)
    if not p.exists():
        raise HTTPException(status_code=404, detail=f"no proposal id={proposal_id}")
    p.unlink()
