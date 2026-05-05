#!/usr/bin/env python3
"""Generate `src-tauri/icons/source.png` — a 1024x1024 placeholder app icon.

This is just enough to unblock `cargo build` (tauri-build requires icon.ico).
Replace the source PNG with real artwork and re-run `npx tauri icon` whenever
the brand catches up.

Output is a flat warm-cream square with a single inset terracotta block —
deliberately ugly so nobody mistakes it for the final design.
"""

from __future__ import annotations

import struct
import zlib
from pathlib import Path

SIZE = 1024
BG = (0xF5, 0xEA, 0xD3)         # warm cream (matches stationery theme)
FG = (0xC0, 0x57, 0x44)         # terracotta accent
INSET = SIZE // 4               # accent block: middle 50%


def png_chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def write_png(path: Path) -> None:
    rows: list[bytes] = []
    for y in range(SIZE):
        row = bytearray()
        row.append(0)  # filter byte: None
        for x in range(SIZE):
            inside = INSET <= x < SIZE - INSET and INSET <= y < SIZE - INSET
            r, g, b = FG if inside else BG
            row.extend((r, g, b, 0xFF))
        rows.append(bytes(row))
    raw = b"".join(rows)
    compressed = zlib.compress(raw, 9)
    ihdr = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", ihdr)
        + png_chunk(b"IDAT", compressed)
        + png_chunk(b"IEND", b"")
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)


def main() -> None:
    out = Path(__file__).resolve().parents[1] / "src-tauri" / "icons" / "source.png"
    write_png(out)
    print(f"wrote {out} ({SIZE}x{SIZE} placeholder)")
    print("next: cd web && npx tauri icon ../src-tauri/icons/source.png")


if __name__ == "__main__":
    main()
