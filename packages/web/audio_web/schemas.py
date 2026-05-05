"""Request/response schemas for the web API."""

from __future__ import annotations

from typing import Any, Literal

from audio_core.proposals import (
    InvalidProposal,
    validate_proposed_actions,
)
from pydantic import BaseModel

__all__ = [
    "ActionType",
    "HomeResponse",
    "InvalidProposal",
    "ProposalIn",
    "ProposalOut",
    "ProposedAction",
    "Shelf",
    "validate_proposed_actions",
]

ActionType = Literal[
    "RenameProject",
    "MoveProject",
    "SetColorTag",
    "SetTags",
    "ArchiveProject",
    "RepairMacPaths",
    "RelinkMissingSamples",
]


class ProposedAction(BaseModel):
    type: ActionType
    args: dict[str, Any]


class ProposalIn(BaseModel):
    actor: Literal["claude", "user"] = "claude"
    actions: list[ProposedAction]
    rationale: str | None = None


class ProposalOut(BaseModel):
    proposal_id: str
    actor: str
    actions: list[ProposedAction]
    rationale: str | None = None


class Shelf(BaseModel):
    """One horizontal row on the home page — a curated answer to
    'why might I want to find a project right now?'."""

    id: str
    title: str
    description: str
    see_all_query: str
    projects: list[dict[str, Any]]


class HomeResponse(BaseModel):
    shelves: list[Shelf]
