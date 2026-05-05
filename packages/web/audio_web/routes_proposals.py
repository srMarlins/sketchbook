from __future__ import annotations

import json
import logging
import os
import sqlite3
import uuid
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

_log = logging.getLogger("audio_web")

from audio_core.actions.archive import ArchiveProject
from audio_core.actions.move import MoveProject
from audio_core.actions.rename import RenameProject
from audio_core.actions.relink_missing_samples import Relink, RelinkMissingSamples
from audio_core.actions.repair_mac_paths import RepairMacPaths
from audio_core.actions.runner import run_batch
from audio_core.actions.set_color_tag import SetColorTag
from audio_core.actions.set_tags import SetTags
from audio_core.config import db_path, journal_dir, projects_root, proposals_dir
from audio_core.db.connection import open_db
from fastapi import APIRouter, HTTPException

from audio_web.schemas import (
    InvalidProposal,
    ProposalIn,
    ProposalOut,
    validate_proposed_actions,
)

router = APIRouter(prefix="/api/proposals", tags=["proposals"])


def _new_proposal_id() -> str:
    return f"{datetime.now(UTC).strftime('%Y-%m-%dT%H-%M-%S')}_{uuid.uuid4().hex[:8]}"


def _proposal_path(proposal_id: str) -> Path:
    return proposals_dir() / f"{proposal_id}.json"


@router.post("", status_code=201)
def submit_proposal(body: ProposalIn) -> dict:
    actions_dump = [a.model_dump() for a in body.actions]
    try:
        validate_proposed_actions(actions_dump, projects_root=projects_root())
    except InvalidProposal as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    proposals_dir().mkdir(parents=True, exist_ok=True)
    pid = _new_proposal_id()
    payload = {
        "proposal_id": pid,
        "actor": body.actor,
        "actions": actions_dump,
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
    if action_type == "RepairMacPaths":
        return RepairMacPaths(project_id=int(args["project_id"]), root=root)
    if action_type == "RelinkMissingSamples":
        return RelinkMissingSamples(
            project_id=int(args["project_id"]),
            relinks=[Relink(old=r["old"], new=r["new"]) for r in args["relinks"]],
            root=root,
        )
    if action_type == "SetColorTag":
        return SetColorTag(project_id=int(args["project_id"]), color=args.get("color"))
    if action_type == "SetTags":
        return SetTags(project_id=int(args["project_id"]), tags=list(args.get("tags") or []))
    raise HTTPException(status_code=400, detail=f"unknown action type: {action_type!r}")


@router.post("/{proposal_id}/approve")
def approve_proposal(proposal_id: str) -> dict:
    """Approve and execute a proposal. Returns the journal batch_id.

    Concurrency: the proposal file is atomically renamed to a `.processing`
    suffix before run_batch fires. A second concurrent approve call will see
    the original path missing and 404. After success, the .processing file is
    deleted; on failure (validation, FS error, etc.) it is renamed back so
    the user can retry.
    """
    p = _proposal_path(proposal_id)
    processing = p.with_suffix(p.suffix + ".processing")
    if not p.exists():
        raise HTTPException(status_code=404, detail=f"no proposal id={proposal_id}")
    try:
        os.replace(p, processing)
    except FileNotFoundError as e:
        # Lost the race to another approve call.
        raise HTTPException(status_code=409, detail=f"proposal {proposal_id} is being processed") from e
    try:
        proposal = json.loads(processing.read_text(encoding="utf-8"))
        actor = proposal.get("actor") or "claude"
        intent = proposal["actions"]
        try:
            validate_proposed_actions(intent, projects_root=projects_root())
        except InvalidProposal as e:
            raise HTTPException(status_code=400, detail=str(e)) from e
        actions = [_materialize(a["type"], a["args"]) for a in intent]
        conn = open_db(db_path())
        try:
            bid = run_batch(
                conn, actions, actor=actor, journal_dir=journal_dir(), intent=intent
            )
        except (
            PermissionError,
            FileExistsError,
            FileNotFoundError,
            LookupError,
            ValueError,
            RuntimeError,
            OSError,
            sqlite3.Error,
            KeyError,
        ) as e:
            raise HTTPException(status_code=400, detail=str(e)) from e
    except HTTPException:
        # Restore the proposal so the user can fix and retry.
        try:
            os.replace(processing, p)
        except OSError:
            pass
        raise
    except BaseException:
        try:
            os.replace(processing, p)
        except OSError:
            pass
        raise
    # Success — delete the processing file
    try:
        processing.unlink()
    except OSError:
        pass
    _log.info(
        "approve proposal_id=%s actor=%s batch_id=%s n_actions=%d",
        proposal_id, actor, bid, len(actions),
    )
    return {"batch_id": bid}


@router.delete("/{proposal_id}", status_code=204)
def reject_proposal(proposal_id: str) -> None:
    p = _proposal_path(proposal_id)
    if not p.exists():
        raise HTTPException(status_code=404, detail=f"no proposal id={proposal_id}")
    p.unlink()
