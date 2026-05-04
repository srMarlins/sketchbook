from __future__ import annotations

from typing import Literal

from pydantic import BaseModel

PluginType = Literal["vst", "vst3", "au", "ableton-native", "unknown"]


class PluginRef(BaseModel):
    name: str
    plugin_type: PluginType = "unknown"
    track_name: str | None = None


class SampleRef(BaseModel):
    path: str
    is_missing: bool = False


class ProjectMetadata(BaseModel):
    tempo: float | None = None
    time_sig_numerator: int | None = None
    time_sig_denominator: int | None = None
    track_count: int = 0
    audio_track_count: int = 0
    midi_track_count: int = 0
    return_track_count: int = 0
    length_seconds: float | None = None
    live_version: str | None = None
    plugins: list[PluginRef] = []
    samples: list[SampleRef] = []
