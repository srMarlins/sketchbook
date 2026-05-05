"""Proposal schemas — shared between web (HTTP submit/approve) and MCP
(tool-call propose_batch). The single source of truth for "is this action
structurally valid?" so a malicious or buggy proposal cannot slip past via
"validate at one entry point only".

Per-action arg models are defined here; HTTP/MCP-specific outer models
(ProposalIn, ProposalOut) live in audio_web/audio_mcp respectively.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field, ValidationError


class RenameArgs(BaseModel):
    project_id: int
    new_dir_name: str = Field(min_length=1, max_length=255)


class MoveArgs(BaseModel):
    project_id: int
    new_parent: str = Field(min_length=1)


class ArchiveArgs(BaseModel):
    project_id: int


class SetColorArgs(BaseModel):
    project_id: int
    color: int | None = Field(default=None, ge=0, le=13)


class SetTagsArgs(BaseModel):
    project_id: int
    tags: list[str] = Field(default_factory=list)


class RepairMacPathsArgs(BaseModel):
    project_id: int


class RelinkSpec(BaseModel):
    old: str = Field(min_length=1)
    new: str = Field(min_length=1)


class RelinkMissingSamplesArgs(BaseModel):
    project_id: int
    relinks: list[RelinkSpec] = Field(min_length=1)


ARG_SCHEMAS: dict[str, type[BaseModel]] = {
    "RenameProject": RenameArgs,
    "MoveProject": MoveArgs,
    "ArchiveProject": ArchiveArgs,
    "SetColorTag": SetColorArgs,
    "SetTags": SetTagsArgs,
    "RepairMacPaths": RepairMacPathsArgs,
    "RelinkMissingSamples": RelinkMissingSamplesArgs,
}


class InvalidProposal(ValueError):
    """Raised when a proposed action is malformed (unknown type, bad args,
    or path outside the allowlist)."""


def validate_proposed_actions(actions: list[dict[str, Any]], *, projects_root: Any) -> None:
    """Validate `[{type, args}, ...]`. Raises `InvalidProposal` on any
    structural problem or path-allowlist violation. `projects_root` is a
    path-like used for the path-bearing actions' allowlist check."""
    from audio_core.safety.paths import ensure_within

    for i, action in enumerate(actions):
        if not isinstance(action, dict):
            raise InvalidProposal(f"action[{i}] is not an object: {action!r}")
        if "type" not in action or "args" not in action:
            raise InvalidProposal(f"action[{i}] missing 'type' or 'args': {action!r}")
        atype = action["type"]
        args = action["args"]
        schema = ARG_SCHEMAS.get(atype)
        if schema is None:
            raise InvalidProposal(f"action[{i}] unknown type: {atype!r}")
        if not isinstance(args, dict):
            raise InvalidProposal(f"action[{i}] args is not an object: {args!r}")
        try:
            parsed = schema.model_validate(args)
        except ValidationError as e:
            raise InvalidProposal(f"action[{i}] ({atype}) invalid args: {e}") from e
        if atype == "MoveProject":
            try:
                ensure_within(parsed.new_parent, projects_root)  # type: ignore[attr-defined]
            except PermissionError as e:
                raise InvalidProposal(f"action[{i}] (MoveProject): {e}") from e
