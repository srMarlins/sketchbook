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
    """Parse a .als file into a full lxml DOM. Memory-heavy on large projects;
    prefer parse_als() (which streams) for the production catalog scan."""
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
    return _decode_ts(int(raw))


def _decode_ts(encoded: int) -> tuple[int | None, int | None]:
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
    """Walk up to the nearest containing track and return its EffectiveName.
    Used by the DOM-based parse_plugins helper; the streaming parse_als has its own logic."""
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
    for dev in root.iter("AuPluginDevice"):
        if dev.find(".//AuPluginInfo") is not None:
            continue
        n = dev.find(".//Name") or dev.find(".//Manufacturer")
        out.append(
            PluginRef(
                name=(n.get("Value") if n is not None and n.get("Value") else "Unknown AU"),
                plugin_type="au",
                track_name=_track_name_for(dev),
            )
        )
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
    # Older Live (10): <RelativePath><RelativePathElement Dir="X"/>...</RelativePath>
    #                  <Name Value="kick.wav"/>
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


# ---------------------------------------------------------------------------
# Streaming parser — single iterparse pass, memory-bounded regardless of file size.
# ---------------------------------------------------------------------------

# Tags whose end events we react to. Filtering at iterparse() level is the cheapest way
# to avoid the per-element Python overhead on multi-million-element trees.
_INTERESTING_END_TAGS = frozenset(
    {
        "Ableton",
        "Manual",
        "EffectiveName",
        "PluginDevice",
        "AuPluginDevice",
        "SampleRef",
        *_TRACK_TAGS,
        *NATIVE_DEVICE_TAGS,
    }
)


def _free_subtree(elem: etree._Element) -> None:
    """Clear an element's subtree and drop any already-processed preceding siblings.
    Crucial for memory: the gap between the previous interesting element and this one
    (e.g. a giant <Clips> or <AutomationEnvelope>) is what blows up RAM otherwise."""
    elem.clear()
    parent = elem.getparent()
    if parent is None:
        return
    # Drop earlier siblings — their end events have already fired (document order).
    while elem.getprevious() is not None:
        del parent[0]


def parse_als(path: str | Path) -> ProjectMetadata:
    """Stream-parse a .als file into ProjectMetadata in one pass.

    Memory-bounded regardless of file size: clears each major subtree (PluginDevice,
    SampleRef, native devices, finished tracks, automation/clip noise between them)
    as soon as we have what we need from it.
    """
    p = Path(path)
    if not p.is_file():
        raise FileNotFoundError(p)

    tempo: float | None = None
    ts_value: int | None = None
    live_version: str | None = None
    audio = midi = ret_ = group = 0
    plugins: list[PluginRef] = []
    samples: list[SampleRef] = []

    # Stack of [tag, name] for currently-open track ancestors (innermost on top).
    track_stack: list[list] = []

    def _current_track_name() -> str | None:
        return track_stack[-1][1] if track_stack else None

    with gzip.open(p, "rb") as fh:
        ctx = etree.iterparse(
            fh,
            events=("start", "end"),
            huge_tree=True,
        )
        for event, elem in ctx:
            tag = elem.tag

            if event == "start":
                if tag == "Ableton" and live_version is None:
                    creator = elem.get("Creator")
                    if creator:
                        m = _CREATOR_RE.search(creator)
                        live_version = m.group(1) if m else creator
                elif tag in _TRACK_TAGS:
                    track_stack.append([tag, None])
                    if tag == "AudioTrack":
                        audio += 1
                    elif tag == "MidiTrack":
                        midi += 1
                    elif tag == "ReturnTrack":
                        ret_ += 1
                    elif tag == "GroupTrack":
                        group += 1
                continue

            # event == "end"
            if tag not in _INTERESTING_END_TAGS:
                continue

            if tag == "Manual":
                parent = elem.getparent()
                if parent is not None:
                    if parent.tag == "Tempo" and tempo is None:
                        v = elem.get("Value")
                        if v:
                            tempo = float(v)
                    elif parent.tag == "TimeSignature" and ts_value is None:
                        v = elem.get("Value")
                        if v:
                            ts_value = int(v)

            elif tag == "EffectiveName":
                # Track names sit at <Track>/Name/EffectiveName. Inner racks/devices also
                # have EffectiveName, so guard on grandparent being a track.
                parent = elem.getparent()
                if parent is not None and parent.tag == "Name":
                    grand = parent.getparent()
                    if grand is not None and grand.tag in _TRACK_TAGS and track_stack:
                        # Only set on the innermost open track, and only the first time.
                        for entry in reversed(track_stack):
                            if entry[0] == grand.tag and entry[1] is None:
                                entry[1] = elem.get("Value")
                                break

            elif tag == "PluginDevice":
                tname = _current_track_name()
                vst3 = elem.find(".//Vst3PluginInfo")
                if vst3 is not None:
                    n = vst3.find("Name")
                    plugins.append(
                        PluginRef(
                            name=(
                                n.get("Value")
                                if n is not None and n.get("Value")
                                else "Unknown VST3"
                            ),
                            plugin_type="vst3",
                            track_name=tname,
                        )
                    )
                else:
                    vst2 = elem.find(".//VstPluginInfo")
                    if vst2 is not None:
                        pn = vst2.find("PlugName")
                        plugins.append(
                            PluginRef(
                                name=(
                                    pn.get("Value")
                                    if pn is not None and pn.get("Value")
                                    else "Unknown VST"
                                ),
                                plugin_type="vst",
                                track_name=tname,
                            )
                        )
                    else:
                        au = elem.find(".//AuPluginInfo")
                        if au is not None:
                            n = au.find("Name") or au.find("Manufacturer")
                            plugins.append(
                                PluginRef(
                                    name=(
                                        n.get("Value")
                                        if n is not None and n.get("Value")
                                        else "Unknown AU"
                                    ),
                                    plugin_type="au",
                                    track_name=tname,
                                )
                            )
                _free_subtree(elem)

            elif tag == "AuPluginDevice":
                # Skip if already counted via an enclosing PluginDevice.
                anc = elem.getparent()
                inside_plugin_device = False
                while anc is not None:
                    if anc.tag == "PluginDevice":
                        inside_plugin_device = True
                        break
                    anc = anc.getparent()
                if not inside_plugin_device:
                    n = elem.find(".//Name") or elem.find(".//Manufacturer")
                    plugins.append(
                        PluginRef(
                            name=(
                                n.get("Value") if n is not None and n.get("Value") else "Unknown AU"
                            ),
                            plugin_type="au",
                            track_name=_current_track_name(),
                        )
                    )
                _free_subtree(elem)

            elif tag in NATIVE_DEVICE_TAGS:
                parent = elem.getparent()
                if parent is not None and parent.tag == "Devices":
                    plugins.append(
                        PluginRef(
                            name=tag,
                            plugin_type="ableton-native",
                            track_name=_current_track_name(),
                        )
                    )
                _free_subtree(elem)

            elif tag == "SampleRef":
                file_ref = elem.find("FileRef")
                if file_ref is not None:
                    sp = _path_from_fileref(file_ref)
                    if sp:
                        samples.append(SampleRef(path=sp))
                _free_subtree(elem)

            elif tag in _TRACK_TAGS:
                # Pop the matching open-track entry (innermost first).
                for i in range(len(track_stack) - 1, -1, -1):
                    if track_stack[i][0] == tag:
                        track_stack.pop(i)
                        break
                _free_subtree(elem)

    n, d = (None, None) if ts_value is None else _decode_ts(ts_value)
    return ProjectMetadata(
        tempo=tempo,
        time_sig_numerator=n,
        time_sig_denominator=d,
        track_count=audio + midi + ret_ + group,
        audio_track_count=audio,
        midi_track_count=midi,
        return_track_count=ret_,
        live_version=live_version,
        plugins=plugins,
        samples=samples,
    )
