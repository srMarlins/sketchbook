from audio_core.scanner.hashing import hash_file


def test_hash_is_deterministic(tmp_path):
    p = tmp_path / "a.bin"
    p.write_bytes(b"hello world" * 1000)
    assert hash_file(p) == hash_file(p)


def test_hash_changes_with_content(tmp_path):
    p = tmp_path / "a.bin"
    p.write_bytes(b"hello")
    h1 = hash_file(p)
    p.write_bytes(b"hello!")
    assert hash_file(p) != h1


def test_hash_handles_large_file(tmp_path):
    """Verify chunked read works on a multi-MB file."""
    p = tmp_path / "big.bin"
    p.write_bytes(b"x" * (5 * 1024 * 1024 + 7))  # 5 MB + remainder
    h = hash_file(p)
    assert isinstance(h, str) and len(h) == 64  # blake3 default = 32 bytes hex
