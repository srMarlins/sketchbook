from __future__ import annotations

from pathlib import Path

import blake3


def hash_file(path: str | Path, chunk: int = 1 << 20) -> str:
    h = blake3.blake3()
    with open(path, "rb") as fh:
        while data := fh.read(chunk):
            h.update(data)
    return h.hexdigest()
