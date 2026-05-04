from __future__ import annotations

import gzip
import re
from dataclasses import dataclass
from pathlib import Path

from lxml import etree

from audio_core.parser.model import PluginRef, ProjectMetadata, SampleRef

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


# Tags of Ableton native devices we recognize. Detection is name-based; not exhaustive.
NATIVE_DEVICE_TAGS = frozenset(
    {
        "Eq8",
        "EqEight",
        "Compressor2",
        "Compressor",
        "Limiter",
        "Saturator",
        "AutoFilter",
        "Reverb",
        "Delay",
        "Echo",
        "Operator",
        "Wavetable",
        "Simpler",
        "Sampler",
        "DrumGroupDevice",
        "InstrumentRack",
        "AudioEffectGroupDevice",
        "MidiEffectGroupDevice",
        "InstrumentGroupDevice",
        "Utility",
        "Gate",
        "Glue",
        "MultibandDynamics",
        "Spectrum",
        "Tuner",
        "Phaser",
        "Chorus",
        "Flanger",
        "AutoPan",
        "BeatRepeat",
        "Vocoder",
        "FrequencyShifter",
        "GrainDelay",
        "DrumBuss",
        "Pedal",
        "Amp",
        "Cabinet",
        "Erosion",
        "Overdrive",
    }
)

_TRACK_TAGS = frozenset(
    {"MidiTrack", "AudioTrack", "ReturnTrack", "GroupTrack", "MasterTrack", "MainTrack"}
)


def _track_name_for(node: etree._Element) -> str | None:
    """Walk up to the nearest containing track and return its EffectiveName."""
    cur = node.getparent()
    while cur is not None:
        if cur.tag in _TRACK_TAGS:
            name_el = cur.find(".//Name/EffectiveName")
            if name_el is not None:
                return name_el.get("Value")
            return None
        cur = cur.getparent()
    return None


def parse_plugins(root: etree._Element) -> list[PluginRef]:
    out: list[PluginRef] = []
    # External plugins: every plugin instance lives in a <PluginDevice>; the info child
    # tells us VST2 vs VST3 vs AU.
    for dev in root.iter("PluginDevice"):
        track_name = _track_name_for(dev)
        vst3 = dev.find(".//Vst3PluginInfo")
        if vst3 is not None:
            n = vst3.find("Name")
            out.append(
                PluginRef(
                    name=(n.get("Value") if n is not None and n.get("Value") else "Unknown VST3"),
                    plugin_type="vst3",
                    track_name=track_name,
                )
            )
            continue
        vst2 = dev.find(".//VstPluginInfo")
        if vst2 is not None:
            pn = vst2.find("PlugName")
            out.append(
                PluginRef(
                    name=(pn.get("Value") if pn is not None and pn.get("Value") else "Unknown VST"),
                    plugin_type="vst",
                    track_name=track_name,
                )
            )
            continue
        au = dev.find(".//AuPluginInfo")
        if au is not None:
            n = au.find("Name") or au.find("Manufacturer")
            out.append(
                PluginRef(
                    name=(n.get("Value") if n is not None and n.get("Value") else "Unknown AU"),
                    plugin_type="au",
                    track_name=track_name,
                )
            )
            continue
        # Older standalone <AuPluginDevice> shape:
    for dev in root.iter("AuPluginDevice"):
        if dev.find(".//AuPluginInfo") is not None:
            continue  # already counted above via PluginDevice path
        n = dev.find(".//Name") or dev.find(".//Manufacturer")
        out.append(
            PluginRef(
                name=(n.get("Value") if n is not None and n.get("Value") else "Unknown AU"),
                plugin_type="au",
                track_name=_track_name_for(dev),
            )
        )
    # Native Ableton devices: direct children of any <Devices> element whose tag is in our set.
    for devices in root.iter("Devices"):
        for child in devices:
            if child.tag in NATIVE_DEVICE_TAGS:
                out.append(
                    PluginRef(
                        name=child.tag,
                        plugin_type="ableton-native",
                        track_name=_track_name_for(child),
                    )
                )
    return out


def _path_from_fileref(file_ref: etree._Element) -> str | None:
    # Newer Live: <FileRef Path="..."/> or <FileRef><Path Value="..."/></FileRef>;
    # in practice both attribute and child element forms exist depending on Live version.
    path_attr = file_ref.get("Path")
    if path_attr:
        return path_attr
    path_child = file_ref.find("Path")
    if path_child is not None and path_child.get("Value"):
        return path_child.get("Value")
    rel_attr = file_ref.get("RelativePath")
    if rel_attr:
        return rel_attr
    # Older Live (10): <RelativePath><RelativePathElement Dir="X"/>...</RelativePath><Name Value="kick.wav"/>
    rel = file_ref.find("RelativePath")
    if rel is not None:
        parts = [e.get("Dir", "") for e in rel.findall("RelativePathElement")]
        name_el = file_ref.find("Name")
        if name_el is not None and name_el.get("Value"):
            parts.append(name_el.get("Value"))
        joined = "/".join(p for p in parts if p)
        if joined:
            return joined
    return None


def parse_als(path: str | Path) -> ProjectMetadata:
    root = als_xml(path)
    n, d = parse_time_signature(root)
    counts = parse_tracks(root)
    return ProjectMetadata(
        tempo=parse_tempo(root),
        time_sig_numerator=n,
        time_sig_denominator=d,
        track_count=counts.total,
        audio_track_count=counts.audio,
        midi_track_count=counts.midi,
        return_track_count=counts.return_,
        live_version=parse_live_version(root),
        plugins=parse_plugins(root),
        samples=parse_samples(root),
    )


def parse_samples(root: etree._Element) -> list[SampleRef]:
    out: list[SampleRef] = []
    for ref in root.iter("SampleRef"):
        # Use the *direct* FileRef child (the active location) — not nested OriginalFileRef.
        file_ref = ref.find("FileRef")
        if file_ref is None:
            continue
        path = _path_from_fileref(file_ref)
        if path:
            out.append(SampleRef(path=path))
    return out
