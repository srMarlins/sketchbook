from __future__ import annotations

import gzip
import re
from dataclasses import dataclass
from pathlib import Path

from lxml import etree

# Live encodes the song-level time signature as a single int in <TimeSignature><Manual Value="N"/>.
# Numerator = (value % 99) + 1, denominator index = value // 99 → table below.
_DENOM_TABLE = [1, 2, 4, 8, 16, 32, 64]
_CREATOR_RE = re.compile(r"Ableton Live\s+([\d.]+)")


# Live projects can embed very large automation/clip data; default lxml limits are too small.
_PARSER = etree.XMLParser(huge_tree=True, recover=False)


def als_xml(path: str | Path) -> etree._Element:
    p = Path(path)
    if not p.is_file():
        raise FileNotFoundError(p)
    with gzip.open(p, "rb") as fh:
        return etree.parse(fh, _PARSER).getroot()


def parse_tempo(root: etree._Element) -> float | None:
    el = root.find(".//Tempo/Manual")
    if el is None:
        return None
    val = el.get("Value")
    return float(val) if val else None


def parse_time_signature(root: etree._Element) -> tuple[int | None, int | None]:
    el = root.find(".//TimeSignature/Manual")
    if el is None:
        return None, None
    raw = el.get("Value")
    if raw is None:
        return None, None
    encoded = int(raw)
    if encoded < 0:
        return None, None
    numerator = (encoded % 99) + 1
    denom_index = encoded // 99
    denominator = _DENOM_TABLE[denom_index] if 0 <= denom_index < len(_DENOM_TABLE) else None
    return numerator, denominator


@dataclass(frozen=True)
class TrackCounts:
    audio: int
    midi: int
    return_: int
    group: int
    total: int


def parse_tracks(root: etree._Element) -> TrackCounts:
    audio = len(root.findall(".//Tracks/AudioTrack"))
    midi = len(root.findall(".//Tracks/MidiTrack"))
    ret = len(root.findall(".//Tracks/ReturnTrack"))
    group = len(root.findall(".//Tracks/GroupTrack"))
    return TrackCounts(audio, midi, ret, group, audio + midi + ret + group)


def parse_live_version(root: etree._Element) -> str | None:
    if root.tag != "Ableton":
        return None
    creator = root.get("Creator")
    if creator:
        m = _CREATOR_RE.search(creator)
        if m:
            return m.group(1)
        return creator
    return None
