from audio_core.journal.manifest import list_batches, read_batch, write_batch


def test_journal_round_trip(tmp_path):
    journal_dir = tmp_path / "journal"
    bid = write_batch(
        journal_dir,
        actor="user",
        actions=[{"type": "RenameProject", "from_": "/a", "to": "/b", "hash_before": "x"}],
    )
    assert bid
    batch = read_batch(journal_dir, bid)
    assert batch["actor"] == "user"
    assert batch["actions"][0]["type"] == "RenameProject"
    assert batch["batch_id"] == bid


def test_list_batches_returns_all_in_order(tmp_path):
    journal_dir = tmp_path / "journal"
    a = write_batch(journal_dir, actor="user", actions=[{"type": "X"}])
    b = write_batch(journal_dir, actor="claude", actions=[{"type": "Y"}])
    batches = list_batches(journal_dir)
    assert {x["batch_id"] for x in batches} == {a, b}


def test_list_batches_empty_dir(tmp_path):
    assert list_batches(tmp_path / "no_such_dir") == []


def test_write_batch_creates_dir(tmp_path):
    nested = tmp_path / "deeply" / "nested" / "journal"
    bid = write_batch(nested, actor="user", actions=[])
    assert (nested / f"{bid}.json").exists()


def test_batch_id_includes_actor_field(tmp_path):
    journal_dir = tmp_path / "journal"
    bid = write_batch(journal_dir, actor="claude", actions=[{"type": "Z"}])
    batch = read_batch(journal_dir, bid)
    assert batch["actor"] == "claude"
