from audio_core.parser.model import PluginRef, ProjectMetadata, SampleRef


def test_project_metadata_round_trip():
    meta = ProjectMetadata(
        tempo=128.5,
        time_sig_numerator=4,
        time_sig_denominator=4,
        track_count=10,
        audio_track_count=4,
        midi_track_count=4,
        return_track_count=2,
        length_seconds=None,
        live_version="11.3.13",
        plugins=[PluginRef(name="Pro-Q 3", plugin_type="vst3", track_name="Master")],
        samples=[SampleRef(path="C:/samples/kick.wav", is_missing=False)],
    )
    assert meta.tempo == 128.5
    assert meta.plugins[0].plugin_type == "vst3"
