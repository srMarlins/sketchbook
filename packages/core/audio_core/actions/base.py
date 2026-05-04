from __future__ import annotations

import sqlite3
from typing import Protocol


class Action(Protocol):
    """An audited mutation. Validate first; execute returns a journal entry dict
    that captures everything needed to undo it."""

    def validate(self, conn: sqlite3.Connection) -> None: ...

    def execute(self, conn: sqlite3.Connection) -> dict: ...
