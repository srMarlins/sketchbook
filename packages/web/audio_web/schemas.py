"""Request/response schemas for the web API."""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel

ActionType = Literal[
    "RenameProject",
    "MoveProject",
    "SetColorTag",
    "SetTags",
    "ArchiveProject",
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
