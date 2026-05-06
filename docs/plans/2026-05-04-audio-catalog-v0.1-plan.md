# Audio Catalog v0.1 Implementation Plan

**Status:** SUPERSEDED 2026-05-05. The Python `uv` workspace this plan describes was retired in PRs #49/#50 in favor of the Kotlin/Compose Multiplatform rewrite (`2026-05-05-kotlin-rewrite-impl-plan.md`). The data model and CLI feature surface mostly carried over; the implementation tree did not. Preserved for historical context — do not execute this plan.

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Ship a Python `uv` workspace with a `core` library, `cli`, FastAPI `web` backend, and `mcp` server that scans `Z:\User\audio\Projects` (~1,628 `.als` files), catalogs them in SQLite/FTS5, lets the user (and Claude) search / open / rename / move / tag / archive projects, and reverses any write via a JSON undo journal. The React frontend is deferred to a follow-up plan.

**Architecture:** Single Python `uv` workspace under `Z:\User\audio`. `core` package owns parser, db, scanner, actions, journal, safety. `cli` (Typer), `web` (FastAPI), and `mcp` (FastMCP) packages depend on `core` and expose the same primitives over different surfaces. SQLite + FTS5 is the catalog DB. All writes are `Action` subclasses with validate/execute/journal lifecycle. Claude can only `propose_batch(...)`; user approves to execute.

**Tech Stack:**
- Python ≥ 3.11, managed by `uv`
- `lxml` for `.als` XML parsing
- `sqlite3` (stdlib) with FTS5
- `typer` for CLI
- `fastapi` + `uvicorn` for web backend
- `fastmcp` for MCP server
- `psutil` for "is Live holding the file open?" check
- `pytest` + `pytest-asyncio` for tests
- `ruff` for lint/format
- `blake3` for content hashing

**Reference design:** `Z:\User\audio\docs\plans\2026-05-04-audio-catalog-design.md`

---

## Phase 0 — Workspace bootstrap

### Task 0.1: Initialize git repo

**Files:**
- Create: `Z:\User\audio\.gitignore`

**Step 1:** Initialize repo.
```powershell
git -C Z:\User\audio init
git -C Z:\User\audio branch -M main
```

**Step 2:** Write `.gitignore`:
```
# python
__pycache__/
*.py[cod]
.venv/
.uv-cache/
*.egg-info/
.pytest_cache/
.ruff_cache/

# project data
data/catalog.db
data/catalog.db-*
data/cache/
data/journal/
data/proposals/

# editors / os
.vscode/
.idea/
Thumbs.db
.DS_Store

# the actual library copy (keep paths only, never commit audio)
Projects/
```

**Step 3:** Stage existing design + memory artifacts and create the initial commit.
```powershell
git -C Z:\User\audio add .gitignore docs/
git -C Z:\User\audio commit -m "chore: initialize repo with design doc"
```

Expected: one commit on `main` containing the `.gitignore` and the existing `docs/plans/*.md`. The 1,628-project `Projects/` folder must be untracked.

---

### Task 0.2: Create uv workspace skeleton

**Files:**
- Create: `Z:\User\audio\pyproject.toml`
- Create: `Z:\User\audio\packages\core\pyproject.toml`
- Create: `Z:\User\audio\packages\cli\pyproject.toml`
- Create: `Z:\User\audio\packages\web\pyproject.toml`
- Create: `Z:\User\audio\packages\mcp\pyproject.toml`
- Create: empty `__init__.py` under `packages/<name>/audio_<name>/__init__.py`

**Step 1:** Root `pyproject.toml`:
```toml
[project]
name = "audio"
version = "0.1.0dev"
description = "Ableton Live project catalog & organizer"
requires-python = ">=3.11"

[tool.uv.workspace]
members = ["packages/core", "packages/cli", "packages/web", "packages/mcp"]

[tool.uv.sources]
audio-core = { workspace = true }
audio-cli  = { workspace = true }
audio-web  = { workspace = true }
audio-mcp  = { workspace = true }

[tool.ruff]
line-length = 100
target-version = "py311"

[tool.ruff.lint]
select = ["E", "F", "I", "UP", "B", "SIM"]

[tool.pytest.ini_options]
testpaths = ["packages/core/tests", "packages/cli/tests", "packages/web/tests", "packages/mcp/tests"]
asyncio_mode = "auto"
```

**Step 2:** `packages/core/pyproject.toml`:
```toml
[project]
name = "audio-core"
version = "0.1.0dev"
requires-python = ">=3.11"
dependencies = [
  "lxml>=5.0",
  "blake3>=1.0",
  "psutil>=5.9",
  "pydantic>=2.6",
]

[project.optional-dependencies]
dev = ["pytest>=8", "pytest-asyncio>=0.23", "ruff>=0.4"]

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["audio_core"]
```

**Step 3:** `packages/cli/pyproject.toml`:
```toml
[project]
name = "audio-cli"
version = "0.1.0dev"
requires-python = ">=3.11"
dependencies = ["audio-core", "typer>=0.12", "rich>=13"]

[project.scripts]
audio = "audio_cli.main:app"

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["audio_cli"]
```

**Step 4:** `packages/web/pyproject.toml`:
```toml
[project]
name = "audio-web"
version = "0.1.0dev"
requires-python = ">=3.11"
dependencies = ["audio-core", "fastapi>=0.110", "uvicorn[standard]>=0.27"]

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["audio_web"]
```

**Step 5:** `packages/mcp/pyproject.toml`:
```toml
[project]
name = "audio-mcp"
version = "0.1.0dev"
requires-python = ">=3.11"
dependencies = ["audio-core", "fastmcp>=0.3"]

[project.scripts]
audio-mcp = "audio_mcp.main:run"

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["audio_mcp"]
```

**Step 6:** Create empty package roots:
```powershell
New-Item -ItemType Directory -Force -Path Z:\User\audio\packages\core\audio_core,Z:\User\audio\packages\core\tests,Z:\User\audio\packages\cli\audio_cli,Z:\User\audio\packages\cli\tests,Z:\User\audio\packages\web\audio_web,Z:\User\audio\packages\web\tests,Z:\User\audio\packages\mcp\audio_mcp,Z:\User\audio\packages\mcp\tests | Out-Null
"" | Set-Content Z:\User\audio\packages\core\audio_core\__init__.py
"" | Set-Content Z:\User\audio\packages\cli\audio_cli\__init__.py
"" | Set-Content Z:\User\audio\packages\web\audio_web\__init__.py
"" | Set-Content Z:\User\audio\packages\mcp\audio_mcp\__init__.py
"" | Set-Content Z:\User\audio\packages\core\tests\__init__.py
"" | Set-Content Z:\User\audio\packages\cli\tests\__init__.py
"" | Set-Content Z:\User\audio\packages\web\tests\__init__.py
"" | Set-Content Z:\User\audio\packages\mcp\tests\__init__.py
```

**Step 7:** Sync workspace and run an empty pytest to confirm tooling is wired.
```powershell
uv sync --all-packages
uv run pytest -q
```
Expected: `no tests ran in <X>s` (success, zero tests).

**Step 8:** Commit.
```powershell
git -C Z:\User\audio add pyproject.toml packages/
git -C Z:\User\audio commit -m "chore: scaffold uv workspace with core/cli/web/mcp packages"
```

---

### Task 0.3: Pin a sample `.als` for parser development

**Files:**
- Create: `Z:\User\audio\packages\core\tests\fixtures\` (directory)

**Step 1:** Pick three reference projects from the test library covering a range of complexity. From PowerShell:
```powershell
Get-ChildItem Z:\User\audio\Projects -Filter *.als -Recurse | Select-Object FullName, Length | Sort-Object Length | Select-Object -First 1, -Index ([int](Get-ChildItem Z:\User\audio\Projects -Filter *.als -Recurse).Count/2), -Last 1
```
Take note of three paths: smallest, median, largest.

**Step 2:** Copy them into the fixtures dir as `tiny.als`, `median.als`, `huge.als`:
```powershell
Copy-Item "<smallest path>"  Z:\User\audio\packages\core\tests\fixtures\tiny.als
Copy-Item "<median path>"    Z:\User\audio\packages\core\tests\fixtures\median.als
Copy-Item "<largest path>"   Z:\User\audio\packages\core\tests\fixtures\huge.als
```

**Step 3:** Inspect schema. Decompress `tiny.als` to a temp file and skim the XML to confirm the XPaths we'll use:
```powershell
$bytes = [IO.File]::ReadAllBytes("Z:\User\audio\packages\core\tests\fixtures\tiny.als")
$ms = New-Object IO.MemoryStream(,$bytes)
$gz = New-Object IO.Compression.GzipStream($ms, [IO.Compression.CompressionMode]::Decompress)
$reader = New-Object IO.StreamReader($gz)
$reader.ReadToEnd() | Out-File Z:\User\audio\packages\core\tests\fixtures\tiny.als.xml -Encoding UTF8
```

Open `tiny.als.xml` and confirm these elements exist (note any deviations — Live versions vary):
- `<Tempo><Manual Value="120"/></Tempo>` (top-level transport tempo)
- `<EnumEvent ... Value="..."/>` for time signature inside `<TimeSignature>`
- `<Tracks>` containing `<MidiTrack>`, `<AudioTrack>`, `<ReturnTrack>`, `<GroupTrack>`
- `<PluginDevice>` and `<AuPluginDevice>`/`<VstPluginInfo>` for plugin metadata
- `<SampleRef><FileRef><Path Value="..."/></FileRef></SampleRef>` for sample paths
- Root `<Ableton MajorVersion="..." MinorVersion="..." Creator="..." />` for Live version

**Step 4:** Commit fixtures (the inspected XML stays out of git via `.gitignore` update).

Add to `.gitignore`:
```
packages/core/tests/fixtures/*.xml
```

Then:
```powershell
git -C Z:\User\audio add .gitignore packages/core/tests/fixtures/
git -C Z:\User\audio commit -m "test: add three .als fixtures (tiny/median/huge) for parser tests"
```

---

## Phase 1 — `.als` parser

### Task 1.1: Define `ProjectMetadata` model

**Files:**
- Create: `Z:\User\audio\packages\core\audio_core\parser\__init__.py`
- Create: `Z:\User\audio\packages\core\audio_core\parser\model.py`
- Create: `Z:\User\audio\packages\core\tests\test_parser_model.py`

**Step 1:** Write failing test `test_parser_model.py`:
```python
from audio_core.parser.model import ProjectMetadata, PluginRef, SampleRef

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
```

**Step 2:** Run, confirm fail (`ModuleNotFoundError`).
```powershell
uv run pytest packages/core/tests/test_parser_model.py -v
```

**Step 3:** Implement `parser/model.py`:
```python
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
```

Add to `parser/__init__.py`:
```python
from audio_core.parser.model import ProjectMetadata, PluginRef, SampleRef

__all__ = ["ProjectMetadata", "PluginRef", "SampleRef"]
```

**Step 4:** `uv run pytest packages/core/tests/test_parser_model.py -v` → PASS.

**Step 5:** Commit.
```powershell
git -C Z:\User\audio add packages/core/audio_core/parser packages/core/tests/test_parser_model.py
git -C Z:\User\audio commit -m "feat(core): add ProjectMetadata model"
```

---

### Task 1.2: `als_xml(path)` — read + gunzip + parse to lxml tree

**Files:**
- Create: `Z:\User\audio\packages\core\audio_core\parser\als.py`
- Create: `Z:\User\audio\packages\core\tests\test_als_xml.py`

**Step 1:** Write failing test:
```python
from pathlib import Path
from audio_core.parser.als import als_xml

FIX = Path(__file__).parent / "fixtures"

def test_als_xml_returns_root_element():
    root = als_xml(FIX / "tiny.als")
    assert root.tag == "Ableton"
    assert "MajorVersion" in root.attrib

def test_als_xml_raises_on_missing_file(tmp_path):
    import pytest
    with pytest.raises(FileNotFoundError):
        als_xml(tmp_path / "nope.als")
```

**Step 2:** Run, confirm fail.

**Step 3:** Implement:
```python
from __future__ import annotations
import gzip
from pathlib import Path
from lxml import etree

def als_xml(path: str | Path) -> etree._Element:
    p = Path(path)
    if not p.is_file():
        raise FileNotFoundError(p)
    with gzip.open(p, "rb") as fh:
        return etree.parse(fh).getroot()
```

**Step 4:** PASS.

**Step 5:** Commit `feat(core): add als_xml gzip+lxml loader`.

---

### Task 1.3: `parse_tempo`, `parse_time_signature`, `parse_live_version`

**Files:**
- Modify: `packages/core/audio_core/parser/als.py`
- Create: `packages/core/tests/test_parser_basics.py`

**Step 1:** Failing tests:
```python
from pathlib import Path
from audio_core.parser.als import als_xml, parse_tempo, parse_time_signature, parse_live_version

FIX = Path(__file__).parent / "fixtures"

def test_tempo_is_positive_float():
    t = parse_tempo(als_xml(FIX / "tiny.als"))
    assert t is not None and 20 < t < 999

def test_time_signature_is_pair_of_ints():
    n, d = parse_time_signature(als_xml(FIX / "tiny.als"))
    assert n is not None and d is not None
    assert 1 <= n <= 32 and d in (1, 2, 4, 8, 16, 32)

def test_live_version_string():
    v = parse_live_version(als_xml(FIX / "tiny.als"))
    assert v is not None and "." in v
```

**Step 2:** Run; fail.

**Step 3:** Implement (append to `als.py`). Use the XPaths confirmed in Task 0.3; if your fixtures use slightly different shapes, adjust here:
```python
def parse_tempo(root: etree._Element) -> float | None:
    el = root.find(".//Tempo/Manual")
    if el is None:
        el = root.find(".//Tempo")
    val = el.get("Value") if el is not None else None
    return float(val) if val else None

def parse_time_signature(root: etree._Element) -> tuple[int | None, int | None]:
    el = root.find(".//EnumEvent")  # inside <TimeSignature>
    if el is None:
        return None, None
    encoded = int(el.get("Value", "-1"))
    if encoded < 0:
        return None, None
    # Live encodes time-sig as denominator_index*99 + (numerator-1).
    # Numerators 1..99, denominators index 0..6 → 1, 2, 4, 8, 16, 32, 64.
    numerator = (encoded % 99) + 1
    denom_index = encoded // 99
    denominator = [1, 2, 4, 8, 16, 32, 64][denom_index] if 0 <= denom_index <= 6 else None
    return numerator, denominator

def parse_live_version(root: etree._Element) -> str | None:
    if root.tag != "Ableton":
        return None
    major = root.get("MajorVersion")
    minor = root.get("MinorVersion")
    return f"{major}.{minor}" if major and minor else None
```

**Step 4:** PASS. If tempo/time-sig fail on a fixture, open the corresponding `.xml` and refine the XPath; do not silence the test.

**Step 5:** Commit `feat(core): parse tempo, time signature, live version`.

---

### Task 1.4: `parse_tracks` — count by type

**Files:**
- Modify: `parser/als.py`
- Create: `tests/test_parse_tracks.py`

**Step 1:** Failing test:
```python
from pathlib import Path
from audio_core.parser.als import als_xml, parse_tracks

FIX = Path(__file__).parent / "fixtures"

def test_track_counts_nonneg_and_sum():
    counts = parse_tracks(als_xml(FIX / "huge.als"))
    assert counts.audio >= 0 and counts.midi >= 0 and counts.return_ >= 0
    assert counts.total >= counts.audio + counts.midi + counts.return_
```

**Step 2:** Fail.

**Step 3:** Implement:
```python
from dataclasses import dataclass

@dataclass(frozen=True)
class TrackCounts:
    audio: int
    midi: int
    return_: int
    group: int
    total: int

def parse_tracks(root: etree._Element) -> TrackCounts:
    audio = len(root.findall(".//Tracks/AudioTrack"))
    midi  = len(root.findall(".//Tracks/MidiTrack"))
    ret   = len(root.findall(".//Tracks/ReturnTrack"))
    group = len(root.findall(".//Tracks/GroupTrack"))
    total = audio + midi + ret + group
    return TrackCounts(audio, midi, ret, group, total)
```

**Step 4:** PASS.

**Step 5:** Commit `feat(core): count tracks by type`.

---

### Task 1.5: `parse_plugins` — collect VST/VST3/AU/native names

**Files:**
- Modify: `parser/als.py`
- Create: `tests/test_parse_plugins.py`

**Step 1:** Failing test:
```python
from pathlib import Path
from audio_core.parser.als import als_xml, parse_plugins

FIX = Path(__file__).parent / "fixtures"

def test_plugins_have_names_and_types():
    plugins = parse_plugins(als_xml(FIX / "huge.als"))
    assert len(plugins) > 0
    for p in plugins:
        assert p.name and isinstance(p.name, str)
        assert p.plugin_type in {"vst", "vst3", "au", "ableton-native", "unknown"}
```

**Step 2:** Fail.

**Step 3:** Implement. Plugins live in `<Devices>` under each track. VST2 metadata is in `<VstPluginInfo><PlugName Value="..."/></VstPluginInfo>`. VST3 in `<Vst3PluginInfo><Name Value="..."/>`. AU in `<AuPluginInfo>`. Native devices have an element name like `<Eq8>`, `<Compressor2>`, etc., directly under `<Devices>`.

```python
from audio_core.parser.model import PluginRef

VST_NATIVE_TAGS = {
    "Eq8", "EqEight", "Compressor2", "Limiter", "Saturator",
    "AutoFilter", "Reverb", "Delay", "Echo", "Operator", "Wavetable",
    "Simpler", "Sampler", "DrumGroupDevice", "InstrumentRack",
    # Not exhaustive; extend as we discover. The detection is name-based.
}

def parse_plugins(root: etree._Element) -> list[PluginRef]:
    out: list[PluginRef] = []
    for track in root.findall(".//Tracks/*"):
        track_name_el = track.find(".//Name/EffectiveName")
        track_name = track_name_el.get("Value") if track_name_el is not None else None
        for dev in track.findall(".//DeviceChain//Devices/*"):
            tag = etree.QName(dev).localname
            if tag in {"PluginDevice", "VstPluginDevice"}:
                pn = dev.find(".//PlugName")
                out.append(PluginRef(
                    name=pn.get("Value") if pn is not None else "Unknown VST",
                    plugin_type="vst",
                    track_name=track_name,
                ))
            elif tag in {"AuPluginDevice"}:
                n = dev.find(".//Manufacturer") or dev.find(".//Name")
                out.append(PluginRef(name=(n.get("Value") if n is not None else "Unknown AU"),
                                     plugin_type="au", track_name=track_name))
            elif dev.find(".//Vst3PluginInfo") is not None:
                n = dev.find(".//Vst3PluginInfo/Name")
                out.append(PluginRef(name=(n.get("Value") if n is not None else "Unknown VST3"),
                                     plugin_type="vst3", track_name=track_name))
            elif tag in VST_NATIVE_TAGS:
                out.append(PluginRef(name=tag, plugin_type="ableton-native", track_name=track_name))
    return out
```

**Step 4:** PASS. If `huge.als` has 0 plugins (unlikely), substitute `median.als` in the test. If specific Live versions emit different XML shapes, expand the conditions; do not loosen the assertion.

**Step 5:** Commit `feat(core): parse plugin references from .als`.

---

### Task 1.6: `parse_samples`

**Files:**
- Modify: `parser/als.py`
- Create: `tests/test_parse_samples.py`

**Step 1:** Failing test:
```python
from pathlib import Path
from audio_core.parser.als import als_xml, parse_samples

FIX = Path(__file__).parent / "fixtures"

def test_samples_have_paths():
    samples = parse_samples(als_xml(FIX / "huge.als"))
    for s in samples:
        assert s.path
```

**Step 2:** Fail.

**Step 3:** Implement:
```python
from audio_core.parser.model import SampleRef

def parse_samples(root: etree._Element) -> list[SampleRef]:
    out: list[SampleRef] = []
    for ref in root.findall(".//SampleRef"):
        path_el = ref.find(".//FileRef/Path")
        if path_el is not None and path_el.get("Value"):
            out.append(SampleRef(path=path_el.get("Value")))
            continue
        # Older Live versions: relative path components in <RelativePath><RelativePathElement Dir="..."/></RelativePath>
        rel = ref.find(".//FileRef/RelativePath")
        if rel is not None:
            parts = [e.get("Dir", "") for e in rel.findall("RelativePathElement")]
            name = ref.find(".//FileRef/Name")
            if name is not None and name.get("Value"):
                parts.append(name.get("Value"))
            out.append(SampleRef(path="/".join(p for p in parts if p)))
    return out
```

**Step 4:** PASS.

**Step 5:** Commit `feat(core): parse sample references from .als`.

---

### Task 1.7: Top-level `parse_als(path) -> ProjectMetadata`

**Files:**
- Modify: `parser/als.py`
- Modify: `parser/__init__.py`
- Create: `tests/test_parse_als.py`

**Step 1:** Failing test:
```python
from pathlib import Path
from audio_core.parser import parse_als

FIX = Path(__file__).parent / "fixtures"

def test_parse_tiny_als():
    m = parse_als(FIX / "tiny.als")
    assert m.tempo and m.tempo > 0
    assert m.live_version
    assert m.track_count >= 0
    assert isinstance(m.plugins, list)
    assert isinstance(m.samples, list)

def test_parse_huge_als():
    m = parse_als(FIX / "huge.als")
    assert m.track_count > 0
```

**Step 2:** Fail.

**Step 3:** Implement:
```python
def parse_als(path) -> ProjectMetadata:
    from audio_core.parser.model import ProjectMetadata
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
```
Export from `parser/__init__.py`:
```python
from audio_core.parser.als import parse_als
__all__ = ["parse_als", "ProjectMetadata", "PluginRef", "SampleRef"]
```

**Step 4:** PASS.

**Step 5:** Commit `feat(core): parse_als top-level entry point`.

---

## Phase 2 — Database

### Task 2.1: Schema + connection helper

**Files:**
- Create: `packages/core/audio_core/db/__init__.py`
- Create: `packages/core/audio_core/db/schema.sql`
- Create: `packages/core/audio_core/db/connection.py`
- Create: `tests/test_db_connection.py`

**Step 1:** Failing test:
```python
from audio_core.db.connection import open_db

def test_open_db_creates_tables(tmp_path):
    db_path = tmp_path / "test.db"
    conn = open_db(db_path)
    rows = conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
    ).fetchall()
    names = {r[0] for r in rows}
    expected = {"projects", "project_plugins", "project_samples", "project_tags", "tags",
                "schema_version"}
    assert expected.issubset(names)

def test_open_db_creates_fts_table(tmp_path):
    conn = open_db(tmp_path / "t.db")
    rows = conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='projects_fts'"
    ).fetchall()
    assert rows
```

**Step 2:** Fail.

**Step 3:** Implement `schema.sql`:
```sql
CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL);
INSERT INTO schema_version (version)
  SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM schema_version);

CREATE TABLE IF NOT EXISTS projects (
  id              INTEGER PRIMARY KEY,
  path            TEXT    NOT NULL UNIQUE,
  name            TEXT    NOT NULL,
  parent_dir      TEXT    NOT NULL,
  tempo           REAL,
  time_sig_num    INTEGER,
  time_sig_den    INTEGER,
  key             TEXT,
  track_count     INTEGER NOT NULL DEFAULT 0,
  audio_tracks    INTEGER NOT NULL DEFAULT 0,
  midi_tracks     INTEGER NOT NULL DEFAULT 0,
  return_tracks   INTEGER NOT NULL DEFAULT 0,
  length_seconds  REAL,
  live_version    TEXT,
  last_modified   REAL    NOT NULL,
  last_scanned    REAL    NOT NULL,
  file_hash       TEXT    NOT NULL,
  is_archived     INTEGER NOT NULL DEFAULT 0,
  color_tag       INTEGER,
  notes           TEXT
);

CREATE INDEX IF NOT EXISTS idx_projects_parent_dir ON projects(parent_dir);
CREATE INDEX IF NOT EXISTS idx_projects_last_modified ON projects(last_modified);
CREATE INDEX IF NOT EXISTS idx_projects_tempo ON projects(tempo);

CREATE TABLE IF NOT EXISTS project_plugins (
  id          INTEGER PRIMARY KEY,
  project_id  INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  plugin_name TEXT NOT NULL,
  plugin_type TEXT NOT NULL,
  track_name  TEXT
);
CREATE INDEX IF NOT EXISTS idx_pp_project ON project_plugins(project_id);
CREATE INDEX IF NOT EXISTS idx_pp_name ON project_plugins(plugin_name);

CREATE TABLE IF NOT EXISTS project_samples (
  id           INTEGER PRIMARY KEY,
  project_id   INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  sample_path  TEXT NOT NULL,
  sample_hash  TEXT,
  is_missing   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_ps_project ON project_samples(project_id);
CREATE INDEX IF NOT EXISTS idx_ps_hash ON project_samples(sample_hash);

CREATE TABLE IF NOT EXISTS tags (
  id   INTEGER PRIMARY KEY,
  name TEXT NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS project_tags (
  project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  tag_id     INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
  PRIMARY KEY (project_id, tag_id)
);

CREATE VIRTUAL TABLE IF NOT EXISTS projects_fts USING fts5(
  name, parent_dir, plugin_names, sample_filenames, notes,
  content='', tokenize='porter unicode61'
);
```

**Step 3b:** `db/connection.py`:
```python
from __future__ import annotations
import sqlite3
from pathlib import Path

SCHEMA_PATH = Path(__file__).parent / "schema.sql"

def open_db(path: str | Path) -> sqlite3.Connection:
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(p)
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")
    conn.executescript(SCHEMA_PATH.read_text(encoding="utf-8"))
    conn.commit()
    return conn
```

**Step 4:** PASS.

**Step 5:** Commit `feat(core): SQLite schema with FTS5`.

---

### Task 2.2: `upsert_project` and `delete_project_links`

**Files:**
- Create: `packages/core/audio_core/db/projects.py`
- Create: `tests/test_db_projects.py`

**Step 1:** Failing test:
```python
import time
from audio_core.db.connection import open_db
from audio_core.db.projects import upsert_project, get_project_by_path
from audio_core.parser.model import ProjectMetadata, PluginRef, SampleRef

def test_upsert_inserts_then_updates(tmp_path):
    conn = open_db(tmp_path / "t.db")
    meta = ProjectMetadata(
        tempo=120.0, time_sig_numerator=4, time_sig_denominator=4,
        track_count=3, audio_track_count=1, midi_track_count=2,
        live_version="11.3.13",
        plugins=[PluginRef(name="Pro-Q 3", plugin_type="vst3", track_name="Master")],
        samples=[SampleRef(path="C:/s/kick.wav")],
    )
    pid = upsert_project(conn, path=str(tmp_path / "p.als"), name="p",
                         parent_dir=str(tmp_path), file_hash="h1",
                         last_modified=time.time(), meta=meta)
    assert pid > 0
    row = get_project_by_path(conn, str(tmp_path / "p.als"))
    assert row["tempo"] == 120.0

    meta2 = meta.model_copy(update={"tempo": 128.0, "plugins": []})
    pid2 = upsert_project(conn, path=str(tmp_path / "p.als"), name="p",
                          parent_dir=str(tmp_path), file_hash="h2",
                          last_modified=time.time(), meta=meta2)
    assert pid2 == pid
    assert get_project_by_path(conn, str(tmp_path / "p.als"))["tempo"] == 128.0
    plugins = conn.execute("SELECT COUNT(*) FROM project_plugins WHERE project_id=?", (pid,)).fetchone()[0]
    assert plugins == 0  # links rebuilt
```

**Step 2:** Fail.

**Step 3:** Implement `db/projects.py`:
```python
from __future__ import annotations
import sqlite3, time
from audio_core.parser.model import ProjectMetadata

def upsert_project(conn: sqlite3.Connection, *, path: str, name: str, parent_dir: str,
                   file_hash: str, last_modified: float, meta: ProjectMetadata) -> int:
    now = time.time()
    cur = conn.execute("""
        INSERT INTO projects (path, name, parent_dir, tempo, time_sig_num, time_sig_den,
            track_count, audio_tracks, midi_tracks, return_tracks, length_seconds, live_version,
            last_modified, last_scanned, file_hash)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(path) DO UPDATE SET
            name=excluded.name, parent_dir=excluded.parent_dir,
            tempo=excluded.tempo, time_sig_num=excluded.time_sig_num,
            time_sig_den=excluded.time_sig_den, track_count=excluded.track_count,
            audio_tracks=excluded.audio_tracks, midi_tracks=excluded.midi_tracks,
            return_tracks=excluded.return_tracks, length_seconds=excluded.length_seconds,
            live_version=excluded.live_version, last_modified=excluded.last_modified,
            last_scanned=excluded.last_scanned, file_hash=excluded.file_hash
        RETURNING id
    """, (path, name, parent_dir, meta.tempo, meta.time_sig_numerator, meta.time_sig_denominator,
          meta.track_count, meta.audio_track_count, meta.midi_track_count, meta.return_track_count,
          meta.length_seconds, meta.live_version, last_modified, now, file_hash))
    pid = cur.fetchone()[0]
    conn.execute("DELETE FROM project_plugins WHERE project_id=?", (pid,))
    conn.execute("DELETE FROM project_samples WHERE project_id=?", (pid,))
    conn.executemany(
        "INSERT INTO project_plugins (project_id, plugin_name, plugin_type, track_name) VALUES (?,?,?,?)",
        [(pid, p.name, p.plugin_type, p.track_name) for p in meta.plugins],
    )
    conn.executemany(
        "INSERT INTO project_samples (project_id, sample_path) VALUES (?, ?)",
        [(pid, s.path) for s in meta.samples],
    )
    _refresh_fts(conn, pid)
    conn.commit()
    return pid

def _refresh_fts(conn: sqlite3.Connection, pid: int) -> None:
    row = conn.execute("SELECT name, parent_dir, COALESCE(notes, '') FROM projects WHERE id=?", (pid,)).fetchone()
    if not row:
        return
    plugins = " ".join(r[0] for r in conn.execute(
        "SELECT plugin_name FROM project_plugins WHERE project_id=?", (pid,)))
    samples = " ".join(r[0].split("/")[-1] for r in conn.execute(
        "SELECT sample_path FROM project_samples WHERE project_id=?", (pid,)))
    conn.execute("INSERT INTO projects_fts (rowid, name, parent_dir, plugin_names, sample_filenames, notes) "
                 "VALUES (?,?,?,?,?,?) "
                 "ON CONFLICT(rowid) DO UPDATE SET name=excluded.name, parent_dir=excluded.parent_dir, "
                 "plugin_names=excluded.plugin_names, sample_filenames=excluded.sample_filenames, "
                 "notes=excluded.notes",
                 (pid, row[0], row[1], plugins, samples, row[2]))

def get_project_by_path(conn: sqlite3.Connection, path: str) -> dict | None:
    conn.row_factory = sqlite3.Row
    row = conn.execute("SELECT * FROM projects WHERE path = ?", (path,)).fetchone()
    return dict(row) if row else None
```

Note: SQLite `INSERT ... ON CONFLICT ... RETURNING` requires SQLite ≥ 3.35 (Python 3.11 ships with this).

The `projects_fts` table uses `content=''` (external content); we maintain it manually via `_refresh_fts`. The `INSERT ... ON CONFLICT(rowid)` requires no UNIQUE constraint on rowid, which is satisfied by FTS5 default.

**Step 4:** PASS.

**Step 5:** Commit `feat(core): upsert_project + FTS refresh`.

---

### Task 2.3: Search query — `search_projects`

**Files:**
- Modify: `db/projects.py`
- Create: `tests/test_db_search.py`

**Step 1:** Failing test:
```python
import time, pytest
from audio_core.db.connection import open_db
from audio_core.db.projects import upsert_project, search_projects
from audio_core.parser.model import ProjectMetadata, PluginRef

def _seed(conn, path, tempo, plugin):
    upsert_project(conn, path=path, name=path.split("/")[-1].removesuffix(".als"),
                   parent_dir="/x", file_hash="h", last_modified=time.time(),
                   meta=ProjectMetadata(tempo=tempo,
                       plugins=[PluginRef(name=plugin, plugin_type="vst3")]))

def test_search_by_plugin_name(tmp_path):
    conn = open_db(tmp_path / "t.db")
    _seed(conn, "/x/a.als", 140.0, "Pro-Q 3")
    _seed(conn, "/x/b.als", 90.0,  "Diva")
    rows = search_projects(conn, query="Pro-Q")
    assert len(rows) == 1 and rows[0]["name"] == "a"

def test_search_by_tempo_range(tmp_path):
    conn = open_db(tmp_path / "t.db")
    _seed(conn, "/x/a.als", 140.0, "Pro-Q 3")
    _seed(conn, "/x/b.als", 90.0,  "Diva")
    rows = search_projects(conn, tempo_min=120, tempo_max=150)
    assert len(rows) == 1 and rows[0]["tempo"] == 140.0
```

**Step 2:** Fail.

**Step 3:** Implement (append to `db/projects.py`):
```python
def search_projects(conn: sqlite3.Connection, *, query: str | None = None,
                    tempo_min: float | None = None, tempo_max: float | None = None,
                    archived: bool | None = False, limit: int = 200) -> list[dict]:
    conn.row_factory = sqlite3.Row
    where, params = [], []
    base = "SELECT p.* FROM projects p"
    if query:
        base = ("SELECT p.* FROM projects p JOIN projects_fts f ON f.rowid = p.id")
        where.append("projects_fts MATCH ?")
        params.append(query)
    if tempo_min is not None:
        where.append("p.tempo >= ?"); params.append(tempo_min)
    if tempo_max is not None:
        where.append("p.tempo <= ?"); params.append(tempo_max)
    if archived is not None:
        where.append("p.is_archived = ?"); params.append(1 if archived else 0)
    sql = base + (" WHERE " + " AND ".join(where) if where else "")
    sql += " ORDER BY p.last_modified DESC LIMIT ?"
    params.append(limit)
    return [dict(r) for r in conn.execute(sql, params).fetchall()]
```

**Step 4:** PASS.

**Step 5:** Commit `feat(core): search_projects with FTS + tempo range`.

---

## Phase 3 — Scanner

### Task 3.1: BLAKE3 file hashing helper

**Files:**
- Create: `packages/core/audio_core/scanner/__init__.py`
- Create: `packages/core/audio_core/scanner/hashing.py`
- Create: `tests/test_hashing.py`

**Step 1:** Failing test:
```python
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
```

**Step 2:** Fail.

**Step 3:** Implement:
```python
from pathlib import Path
import blake3

def hash_file(path: str | Path, chunk: int = 1 << 20) -> str:
    h = blake3.blake3()
    with open(path, "rb") as fh:
        while data := fh.read(chunk):
            h.update(data)
    return h.hexdigest()
```

**Step 4:** PASS.

**Step 5:** Commit `feat(core): blake3 file hashing`.

---

### Task 3.2: `walk_projects(root)` — find every `.als`

**Files:**
- Create: `packages/core/audio_core/scanner/walker.py`
- Create: `tests/test_walker.py`

**Step 1:** Failing test:
```python
from pathlib import Path
from audio_core.scanner.walker import walk_projects

def test_walker_finds_als(tmp_path: Path):
    (tmp_path / "a Project").mkdir()
    (tmp_path / "a Project" / "a.als").write_bytes(b"x")
    (tmp_path / "a Project" / "Backup").mkdir()
    (tmp_path / "a Project" / "Backup" / "old.als").write_bytes(b"y")
    (tmp_path / "b.als").write_bytes(b"z")
    found = list(walk_projects(tmp_path))
    paths = {p.relative_to(tmp_path).as_posix() for p in found}
    assert "a Project/a.als" in paths
    assert "b.als" in paths
    # Backup/ excluded by default
    assert "a Project/Backup/old.als" not in paths
```

**Step 2:** Fail.

**Step 3:** Implement:
```python
from pathlib import Path
from collections.abc import Iterator

EXCLUDED_DIRS = {"Backup", "_Archive", "Ableton Project Info"}

def walk_projects(root: str | Path) -> Iterator[Path]:
    root = Path(root)
    for p in root.rglob("*.als"):
        if any(part in EXCLUDED_DIRS for part in p.relative_to(root).parts):
            continue
        yield p
```

**Step 4:** PASS.

**Step 5:** Commit `feat(core): walk_projects with backup exclusion`.

---

### Task 3.3: `scan_one(path, conn)` — parse + upsert single file

**Files:**
- Create: `packages/core/audio_core/scanner/scan.py`
- Create: `tests/test_scan_one.py`

**Step 1:** Failing test:
```python
import shutil
from pathlib import Path
from audio_core.db.connection import open_db
from audio_core.db.projects import get_project_by_path
from audio_core.scanner.scan import scan_one

FIX = Path(__file__).parent / "fixtures"

def test_scan_one_persists_metadata(tmp_path):
    src = FIX / "tiny.als"
    dst_dir = tmp_path / "tiny Project"
    dst_dir.mkdir()
    dst = dst_dir / "tiny.als"
    shutil.copy(src, dst)
    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, dst)
    assert pid > 0
    row = get_project_by_path(conn, str(dst))
    assert row["tempo"] is not None
```

**Step 2:** Fail.

**Step 3:** Implement:
```python
from pathlib import Path
import sqlite3
from audio_core.parser import parse_als
from audio_core.db.projects import upsert_project
from audio_core.scanner.hashing import hash_file

def scan_one(conn: sqlite3.Connection, als_path: str | Path) -> int:
    p = Path(als_path)
    meta = parse_als(p)
    return upsert_project(
        conn, path=str(p), name=p.stem, parent_dir=str(p.parent),
        file_hash=hash_file(p), last_modified=p.stat().st_mtime, meta=meta,
    )
```

**Step 4:** PASS.

**Step 5:** Commit `feat(core): scan_one parses + upserts a single .als`.

---

### Task 3.4: `scan_root(root, conn, on_progress)` with hash-skip

**Files:**
- Modify: `scanner/scan.py`
- Create: `tests/test_scan_root.py`

**Step 1:** Failing test:
```python
import shutil
from pathlib import Path
from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_root

FIX = Path(__file__).parent / "fixtures"

def test_scan_root_finds_and_persists(tmp_path):
    for name in ("tiny", "median"):
        d = tmp_path / f"{name} Project"
        d.mkdir()
        shutil.copy(FIX / f"{name}.als", d / f"{name}.als")
    conn = open_db(tmp_path / "c.db")
    stats = scan_root(conn, tmp_path)
    assert stats.scanned == 2 and stats.skipped == 0
    # second pass: hashes match, all skipped
    stats2 = scan_root(conn, tmp_path)
    assert stats2.scanned == 0 and stats2.skipped == 2
```

**Step 2:** Fail.

**Step 3:** Implement:
```python
from dataclasses import dataclass
from collections.abc import Callable
from audio_core.scanner.walker import walk_projects

@dataclass
class ScanStats:
    scanned: int = 0
    skipped: int = 0
    failed: int = 0

def scan_root(conn: sqlite3.Connection, root: str | Path,
              on_progress: Callable[[Path, str], None] | None = None) -> ScanStats:
    stats = ScanStats()
    for als in walk_projects(root):
        try:
            existing = conn.execute(
                "SELECT file_hash FROM projects WHERE path = ?", (str(als),)
            ).fetchone()
            current_hash = hash_file(als)
            if existing and existing[0] == current_hash:
                stats.skipped += 1
                if on_progress: on_progress(als, "skipped")
                continue
            scan_one(conn, als)  # re-hashes; cheap on hot cache
            stats.scanned += 1
            if on_progress: on_progress(als, "scanned")
        except Exception:
            stats.failed += 1
            if on_progress: on_progress(als, "failed")
    return stats
```

**Step 4:** PASS.

**Step 5:** Commit `feat(core): scan_root with hash-skip resumability`.

---

## Phase 4 — Safety + journal

### Task 4.1: Path allowlist

**Files:**
- Create: `packages/core/audio_core/safety/__init__.py`
- Create: `packages/core/audio_core/safety/paths.py`
- Create: `tests/test_safety_paths.py`

**Step 1:** Failing test:
```python
import pytest
from audio_core.safety.paths import ensure_within

def test_ensure_within_accepts_subpath(tmp_path):
    ensure_within(tmp_path / "sub" / "x.als", tmp_path)

def test_ensure_within_rejects_escape(tmp_path):
    other = tmp_path.parent
    with pytest.raises(PermissionError):
        ensure_within(other / "x.als", tmp_path)
```

**Step 2:** Fail.

**Step 3:** Implement:
```python
from pathlib import Path

def ensure_within(target: str | Path, root: str | Path) -> None:
    t = Path(target).resolve()
    r = Path(root).resolve()
    try:
        t.relative_to(r)
    except ValueError as e:
        raise PermissionError(f"path {t} escapes allowlisted root {r}") from e
```

**Step 4:** PASS.

**Step 5:** Commit `feat(core): path allowlist guard`.

---

### Task 4.2: "Live has it open" check

**Files:**
- Create: `packages/core/audio_core/safety/live_lock.py`
- Create: `tests/test_live_lock.py`

**Step 1:** Failing test:
```python
from audio_core.safety.live_lock import is_open_in_live

def test_returns_false_when_no_live_running(tmp_path):
    # When Live isn't holding the file, must return False.
    p = tmp_path / "x.als"
    p.write_bytes(b"x")
    assert is_open_in_live(p) is False
```

**Step 2:** Fail.

**Step 3:** Implement:
```python
from pathlib import Path
import psutil

LIVE_PROC_NAMES = {"Ableton Live.exe", "Live.exe", "Ableton Live"}

def is_open_in_live(path: str | Path) -> bool:
    target = str(Path(path).resolve()).lower()
    for proc in psutil.process_iter(["name"]):
        try:
            if (proc.info.get("name") or "") in LIVE_PROC_NAMES:
                for f in proc.open_files():
                    if f.path.lower() == target:
                        return True
        except (psutil.AccessDenied, psutil.NoSuchProcess):
            continue
    return False
```

**Step 4:** PASS. (`open_files()` may raise AccessDenied without elevated privileges; we treat that as "unknown — not blocked." If you want to be strict, change the except to return True. For v0.1 leave as written and document.)

**Step 5:** Commit `feat(core): is_open_in_live process check`.

---

### Task 4.3: Journal — write/read undo manifests

**Files:**
- Create: `packages/core/audio_core/journal/__init__.py`
- Create: `packages/core/audio_core/journal/manifest.py`
- Create: `tests/test_journal.py`

**Step 1:** Failing test:
```python
from audio_core.journal.manifest import write_batch, read_batch, list_batches

def test_journal_round_trip(tmp_path):
    journal_dir = tmp_path / "journal"
    bid = write_batch(journal_dir, actor="user", actions=[
        {"type": "RenameProject", "from_": "/a", "to": "/b", "hash_before": "x"}
    ])
    assert bid
    batch = read_batch(journal_dir, bid)
    assert batch["actor"] == "user"
    assert batch["actions"][0]["type"] == "RenameProject"
    assert bid in [b["batch_id"] for b in list_batches(journal_dir)]
```

**Step 2:** Fail.

**Step 3:** Implement:
```python
from __future__ import annotations
from pathlib import Path
from datetime import datetime, UTC
import json, uuid

def write_batch(journal_dir: str | Path, *, actor: str, actions: list[dict]) -> str:
    d = Path(journal_dir); d.mkdir(parents=True, exist_ok=True)
    bid = f"{datetime.now(UTC).strftime('%Y-%m-%dT%H-%M-%S')}_{uuid.uuid4().hex[:8]}"
    payload = {"batch_id": bid, "actor": actor, "actions": actions}
    (d / f"{bid}.json").write_text(json.dumps(payload, indent=2), encoding="utf-8")
    return bid

def read_batch(journal_dir: str | Path, batch_id: str) -> dict:
    return json.loads((Path(journal_dir) / f"{batch_id}.json").read_text(encoding="utf-8"))

def list_batches(journal_dir: str | Path) -> list[dict]:
    d = Path(journal_dir)
    if not d.exists(): return []
    return [json.loads(p.read_text(encoding="utf-8")) for p in sorted(d.glob("*.json"))]
```

**Step 4:** PASS.

**Step 5:** Commit `feat(core): undo journal read/write`.

---

## Phase 5 — Actions

### Task 5.1: `Action` base class + `RenameProject`

**Files:**
- Create: `packages/core/audio_core/actions/__init__.py`
- Create: `packages/core/audio_core/actions/base.py`
- Create: `packages/core/audio_core/actions/rename.py`
- Create: `tests/test_rename_action.py`

**Step 1:** Failing test:
```python
import shutil
from pathlib import Path
from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_one
from audio_core.actions.rename import RenameProject

FIX = Path(__file__).parent / "fixtures"

def test_rename_project_dir(tmp_path):
    src = tmp_path / "old name Project"
    src.mkdir()
    shutil.copy(FIX / "tiny.als", src / "old.als")
    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, src / "old.als")
    action = RenameProject(project_id=pid, new_dir_name="new name Project", root=tmp_path)
    action.validate(conn)
    entry = action.execute(conn)
    assert (tmp_path / "new name Project" / "old.als").is_file()
    assert not src.exists()
    assert entry["type"] == "RenameProject"
    assert entry["from_"].endswith("old name Project")
    assert entry["to"].endswith("new name Project")
```

**Step 2:** Fail.

**Step 3:** Implement `actions/base.py`:
```python
from __future__ import annotations
import sqlite3
from typing import Protocol

class Action(Protocol):
    def validate(self, conn: sqlite3.Connection) -> None: ...
    def execute(self, conn: sqlite3.Connection) -> dict: ...
```

`actions/rename.py`:
```python
from __future__ import annotations
import shutil, sqlite3
from dataclasses import dataclass
from pathlib import Path
from audio_core.safety.paths import ensure_within
from audio_core.safety.live_lock import is_open_in_live

@dataclass
class RenameProject:
    project_id: int
    new_dir_name: str
    root: Path

    def _row(self, conn):
        conn.row_factory = sqlite3.Row
        r = conn.execute("SELECT * FROM projects WHERE id=?", (self.project_id,)).fetchone()
        if r is None: raise LookupError(f"no project id={self.project_id}")
        return r

    def validate(self, conn: sqlite3.Connection) -> None:
        row = self._row(conn)
        old_dir = Path(row["parent_dir"])
        ensure_within(old_dir, self.root)
        new_dir = old_dir.parent / self.new_dir_name
        ensure_within(new_dir, self.root)
        if new_dir.exists():
            raise FileExistsError(new_dir)
        if is_open_in_live(row["path"]):
            raise RuntimeError("Live has this project open; close it first")

    def execute(self, conn: sqlite3.Connection) -> dict:
        row = self._row(conn)
        old_dir = Path(row["parent_dir"])
        new_dir = old_dir.parent / self.new_dir_name
        shutil.move(str(old_dir), str(new_dir))
        new_path = str(new_dir / Path(row["path"]).name)
        conn.execute("UPDATE projects SET parent_dir=?, path=? WHERE id=?",
                     (str(new_dir), new_path, self.project_id))
        conn.commit()
        return {
            "type": "RenameProject",
            "project_id": self.project_id,
            "from_": str(old_dir),
            "to": str(new_dir),
            "hash_before": row["file_hash"],
        }
```

**Step 4:** PASS.

**Step 5:** Commit `feat(core): RenameProject action with validation`.

---

### Task 5.2: `MoveProject`, `SetColorTag`, `SetTags`, `ArchiveProject`

Repeat the TDD cycle (write failing test → minimal impl → pass → commit) for each. Sketches:

**`actions/move.py`** — move project dir to a new parent dir under root:
```python
@dataclass
class MoveProject:
    project_id: int
    new_parent: Path
    root: Path
    # validate: new_parent within root, not collide; not open in Live
    # execute: shutil.move(old_dir, new_parent / old_dir.name); update parent_dir + path
```

**`actions/set_color_tag.py`** — write to DB only, no FS. Color is `int | None` in 0..13 (Ableton palette).

**`actions/set_tags.py`** — replace tag set for project; write `tags` + `project_tags`. No FS.

**`actions/archive.py`** — special case of `MoveProject` whose `new_parent` is `root / "_Archive"`, and toggles `is_archived = 1`. Reverses to original parent on undo.

For each: dedicated test file in `tests/`, dedicated commit `feat(core): <ActionName>`.

---

### Task 5.3: Action runner — execute a batch + journal it

**Files:**
- Create: `packages/core/audio_core/actions/runner.py`
- Create: `tests/test_action_runner.py`

**Step 1:** Failing test:
```python
import shutil
from pathlib import Path
from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_one
from audio_core.actions.rename import RenameProject
from audio_core.actions.runner import run_batch

FIX = Path(__file__).parent / "fixtures"

def test_run_batch_writes_journal(tmp_path):
    d = tmp_path / "old Project"; d.mkdir()
    shutil.copy(FIX / "tiny.als", d / "x.als")
    conn = open_db(tmp_path / "c.db")
    pid = scan_one(conn, d / "x.als")
    journal = tmp_path / "journal"
    bid = run_batch(conn, [
        RenameProject(project_id=pid, new_dir_name="new Project", root=tmp_path)
    ], actor="user", journal_dir=journal)
    assert (journal / f"{bid}.json").exists()
    assert (tmp_path / "new Project" / "x.als").is_file()
```

**Step 2:** Fail.

**Step 3:** Implement:
```python
from pathlib import Path
import sqlite3
from audio_core.journal.manifest import write_batch

def run_batch(conn: sqlite3.Connection, actions: list, *, actor: str,
              journal_dir: str | Path) -> str:
    for a in actions: a.validate(conn)
    entries = [a.execute(conn) for a in actions]
    return write_batch(journal_dir, actor=actor, actions=entries)
```

**Step 4:** PASS.

**Step 5:** Commit `feat(core): run_batch executes actions and writes journal`.

---

### Task 5.4: Undo

**Files:**
- Create: `packages/core/audio_core/actions/undo.py`
- Create: `tests/test_undo.py`

**Step 1:** Failing test: rename a project, undo, assert original folder is back.

**Step 2:** Fail.

**Step 3:** Implement reverse semantics:
- `RenameProject` undo = swap `from_` and `to` and rename back.
- `MoveProject`/`ArchiveProject` undo = move back to recorded original parent.
- `SetColorTag` undo = restore previous color (record `before` in entry).
- `SetTags` undo = restore previous tag set (record `before`).

```python
from pathlib import Path
import sqlite3, shutil
from audio_core.journal.manifest import read_batch

def undo_batch(conn: sqlite3.Connection, journal_dir: str | Path, batch_id: str) -> None:
    batch = read_batch(journal_dir, batch_id)
    for entry in reversed(batch["actions"]):
        t = entry["type"]
        if t in {"RenameProject", "MoveProject", "ArchiveProject"}:
            shutil.move(entry["to"], entry["from_"])
            new_dir = entry["from_"]
            conn.execute(
                "UPDATE projects SET parent_dir=?, path=replace(path, ?, ?) WHERE id=?",
                (new_dir, entry["to"], entry["from_"], entry["project_id"]),
            )
            if t == "ArchiveProject":
                conn.execute("UPDATE projects SET is_archived=0 WHERE id=?", (entry["project_id"],))
        elif t == "SetColorTag":
            conn.execute("UPDATE projects SET color_tag=? WHERE id=?",
                         (entry["before"], entry["project_id"]))
        elif t == "SetTags":
            # write entry["before"] back via the tags helpers
            ...
        else:
            raise NotImplementedError(t)
    conn.commit()
```

**Step 4:** PASS for at least the `RenameProject` round-trip; expand tests as you implement each action's undo.

**Step 5:** Commit `feat(core): undo_batch reverses any journaled batch`.

---

## Phase 6 — CLI

### Task 6.1: `audio scan` command

**Files:**
- Create: `packages/cli/audio_cli/main.py`
- Create: `packages/cli/audio_cli/config.py`
- Create: `packages/cli/tests/test_scan_command.py`

**Step 1:** Failing test (uses Typer's runner):
```python
import shutil
from pathlib import Path
from typer.testing import CliRunner
from audio_cli.main import app

FIX = Path(__file__).parents[2] / "core" / "tests" / "fixtures"

def test_scan_outputs_summary(tmp_path, monkeypatch):
    proj = tmp_path / "Projects" / "tiny Project"; proj.mkdir(parents=True)
    shutil.copy(FIX / "tiny.als", proj / "tiny.als")
    monkeypatch.setenv("AUDIO_ROOT", str(tmp_path))
    res = CliRunner().invoke(app, ["scan"])
    assert res.exit_code == 0
    assert "scanned: 1" in res.stdout.lower()
```

**Step 2:** Fail.

**Step 3:** Implement `config.py`:
```python
import os
from pathlib import Path

def workspace_root() -> Path:
    return Path(os.environ.get("AUDIO_ROOT", "Z:/User/audio"))

def projects_root() -> Path:
    return workspace_root() / "Projects"

def db_path() -> Path:
    return workspace_root() / "data" / "catalog.db"

def journal_dir() -> Path:
    return workspace_root() / "data" / "journal"

def proposals_dir() -> Path:
    return workspace_root() / "data" / "proposals"
```

`main.py`:
```python
import typer
from rich.console import Console
from audio_core.db.connection import open_db
from audio_core.scanner.scan import scan_root
from audio_cli.config import projects_root, db_path

app = typer.Typer(help="Audio: Ableton catalog & organizer")
con = Console()

@app.command()
def scan() -> None:
    """Scan the projects root and update the catalog."""
    conn = open_db(db_path())
    stats = scan_root(conn, projects_root(),
                      on_progress=lambda p, s: con.print(f"[dim]{s}[/dim] {p}"))
    con.print(f"[bold]scanned:[/bold] {stats.scanned}  skipped: {stats.skipped}  failed: {stats.failed}")
```

**Step 4:** PASS.

**Step 5:** Commit `feat(cli): audio scan command`.

---

### Task 6.2: `audio search` and `audio show <id>`

Repeat TDD cycle. `search` accepts `--query`, `--tempo-min`, `--tempo-max`, prints a Rich table. `show <id>` prints metadata + plugin list + sample list.

Commit each: `feat(cli): audio search`, `feat(cli): audio show`.

---

### Task 6.3: `audio undo last` / `audio undo <batch-id>`

Wire to `undo_batch`. Commit `feat(cli): audio undo`.

---

### Task 6.4: `audio rename <id> <new-name>`, `audio move`, `audio archive`, `audio tag`

Each command builds a single-action batch and invokes `run_batch`. Commit each.

---

## Phase 7 — Web backend (FastAPI)

### Task 7.1: App factory + `/api/health`

**Files:**
- Create: `packages/web/audio_web/app.py`
- Create: `packages/web/audio_web/main.py`
- Create: `packages/web/tests/test_health.py`

**Step 1:** Failing test:
```python
from fastapi.testclient import TestClient
from audio_web.app import create_app

def test_health():
    client = TestClient(create_app())
    r = client.get("/api/health")
    assert r.status_code == 200 and r.json() == {"ok": True}
```

**Step 2:** Fail.

**Step 3:** Implement:
```python
# app.py
from fastapi import FastAPI

def create_app() -> FastAPI:
    app = FastAPI(title="audio-web")
    @app.get("/api/health")
    def health(): return {"ok": True}
    return app
```
```python
# main.py
import uvicorn
from audio_web.app import create_app

def run() -> None:
    uvicorn.run(create_app(), host="127.0.0.1", port=7878)
```

**Step 4:** PASS.

**Step 5:** Commit `feat(web): app factory + /api/health`.

---

### Task 7.2: `GET /api/projects` (list + filter)

TDD: seed two projects via `scan_one`, then assert `client.get("/api/projects?query=...")` returns the right rows. Wire to `search_projects`. Commit `feat(web): list/search projects endpoint`.

---

### Task 7.3: `GET /api/projects/{id}` (detail with plugins + samples)

Commit `feat(web): project detail endpoint`.

---

### Task 7.4: `POST /api/proposals` (Claude submits a batch)

Body schema:
```python
class ProposedAction(BaseModel):
    type: Literal["RenameProject", "MoveProject", "SetColorTag", "SetTags", "ArchiveProject"]
    args: dict

class ProposalIn(BaseModel):
    actor: Literal["claude"] = "claude"
    actions: list[ProposedAction]
    rationale: str | None = None
```

Endpoint writes proposal JSON to `data/proposals/<bid>.json`, returns the proposal id. *Does not execute.*

Commit `feat(web): proposals submission endpoint`.

---

### Task 7.5: `POST /api/proposals/{id}/approve` (user approves; executes)

Reads proposal, materializes `Action` instances via a small `actions/factory.py`, runs `run_batch`, returns the journal `batch_id`.

Commit `feat(web): approve and execute proposal`.

---

### Task 7.6: `GET /api/journal` and `POST /api/journal/{id}/undo`

Commit `feat(web): journal listing and undo`.

---

## Phase 8 — MCP server

### Task 8.1: FastMCP server exposing read tools

**Files:**
- Create: `packages/mcp/audio_mcp/main.py`
- Create: `packages/mcp/tests/test_mcp_tools.py`

**Step 1:** Failing test (uses fastmcp's in-process client):
```python
import asyncio
from fastmcp import Client
from audio_mcp.main import build_server

async def _run():
    server = build_server()
    async with Client(server) as c:
        tools = [t.name for t in await c.list_tools()]
        assert "search_projects" in tools
        assert "get_project" in tools
        assert "propose_batch" in tools

def test_tool_surface():
    asyncio.run(_run())
```

**Step 2:** Fail.

**Step 3:** Implement:
```python
from fastmcp import FastMCP
from audio_core.db.connection import open_db
from audio_core.db.projects import search_projects, get_project_by_path
from audio_cli.config import db_path, proposals_dir
from pathlib import Path
import json, uuid
from datetime import datetime, UTC

def build_server() -> FastMCP:
    mcp = FastMCP("audio")

    @mcp.tool()
    def search(query: str | None = None, tempo_min: float | None = None,
               tempo_max: float | None = None, limit: int = 50) -> list[dict]:
        """Search the project catalog."""
        conn = open_db(db_path())
        return search_projects(conn, query=query, tempo_min=tempo_min,
                               tempo_max=tempo_max, limit=limit)

    @mcp.tool()
    def get_project(project_id: int) -> dict:
        """Get full details for a project by id."""
        conn = open_db(db_path())
        conn.row_factory = __import__("sqlite3").Row
        row = conn.execute("SELECT * FROM projects WHERE id=?", (project_id,)).fetchone()
        if not row: raise LookupError(project_id)
        proj = dict(row)
        proj["plugins"] = [dict(r) for r in conn.execute(
            "SELECT plugin_name, plugin_type, track_name FROM project_plugins WHERE project_id=?",
            (project_id,))]
        proj["samples"] = [dict(r) for r in conn.execute(
            "SELECT sample_path, sample_hash, is_missing FROM project_samples WHERE project_id=?",
            (project_id,))]
        return proj

    @mcp.tool()
    def propose_batch(actions: list[dict], rationale: str | None = None) -> str:
        """Submit a proposed batch of actions for user approval. Returns the proposal id.
        Each action is {type, args}. Does NOT execute."""
        d = proposals_dir(); d.mkdir(parents=True, exist_ok=True)
        pid = f"{datetime.now(UTC).strftime('%Y-%m-%dT%H-%M-%S')}_{uuid.uuid4().hex[:8]}"
        (d / f"{pid}.json").write_text(
            json.dumps({"proposal_id": pid, "actor": "claude",
                        "actions": actions, "rationale": rationale}, indent=2),
            encoding="utf-8")
        return pid
    return mcp

def run() -> None:
    build_server().run()
```

Rename `search_projects` tool to `search` (or whatever ergonomic name) — the test must reflect actual tool names. Adjust the test as you implement.

**Step 4:** PASS.

**Step 5:** Commit `feat(mcp): expose search, get_project, propose_batch`.

---

### Task 8.2: Document MCP server registration

**Files:**
- Create: `Z:\User\audio\docs\mcp-setup.md`

A short note: how to register `audio-mcp` with Claude Code. Example `.mcp.json` snippet for the workspace, the `uv run audio-mcp` command, and verification (`claude mcp list`).

Commit `docs: MCP setup`.

---

## Phase 9 — End-to-end smoke

### Task 9.1: Full scan of the real test library

**Step 1:** From the workspace root:
```powershell
uv run audio scan
```
Expected: prints scan progress; `data/catalog.db` is populated. The full scan should complete in minutes, not hours, on the 1,628-project test library. If it doesn't, profile (`scan_root` is dominated by hashing + parsing — verify `walk_projects` exclusion is working).

**Step 2:** Run a few searches to sanity-check:
```powershell
uv run audio search --tempo-min 138 --tempo-max 142
uv run audio search --query "diva OR serum"
```

**Step 3:** Pick a project, rename it, undo:
```powershell
uv run audio rename <id> "renamed test Project"
uv run audio undo last
```
Verify the folder name is back.

**Step 4:** Spin up the web backend and hit `/api/health` + `/api/projects?limit=5`:
```powershell
uv run python -c "from audio_web.main import run; run()"
# in another shell:
curl http://127.0.0.1:7878/api/health
curl 'http://127.0.0.1:7878/api/projects?limit=5'
```

**Step 5:** Register `audio-mcp` and from a fresh Claude Code session run `mcp__audio__search`. Confirm a result.

**Step 6:** Commit a short report:
```powershell
git -C Z:\User\audio commit --allow-empty -m "chore: v0.1 e2e smoke passed (1,628 projects scanned, search/rename/undo verified)"
```

---

## Phase 10 — Frontend (deferred)

The React frontend (Vite + TanStack Table + Tailwind) is intentionally not in this plan. Before scaffolding it, write `Z:\User\audio\docs\design-language.md` (color system, typography, table density, proposals-queue layout, project-detail layout, dark-mode tokens). Then write a follow-up plan: `docs/plans/<DATE>-audio-frontend-v0.1-plan.md`.

The backend ships fully usable headless via CLI + MCP; the UI is additive.

---

## Definition of done for v0.1

- `uv run audio scan` populates `data/catalog.db` with all ~1,628 projects (any failures logged but bounded).
- `uv run audio search ...` returns results in <100 ms warm.
- `uv run audio rename / move / archive / tag / set-color` round-trip cleanly with `audio undo last`.
- `audio-mcp` exposes at least `search`, `get_project`, `propose_batch` to a Claude Code session.
- FastAPI backend responds on 127.0.0.1:7878 with `/api/health`, `/api/projects`, `/api/projects/{id}`, `/api/proposals`, `/api/proposals/{id}/approve`, `/api/journal`, `/api/journal/{id}/undo`.
- All `core` tests pass; CLI smoke tests pass; web tests pass; MCP tool-surface test passes.
- Every write touched in v0.1 has a journal entry on disk and is reversible.

---

## Notes for the executor

- Run `uv run pytest -q` after every task; fail fast.
- `uv run ruff format .` and `uv run ruff check .` before each commit.
- Commits are per-task (test + impl together is fine; one task = one commit). The plan expects ~40 commits across phases 0–9.
- If a parser XPath fails on a real-world `.als`, **fix the parser**, don't loosen the test. The test fixtures are the spec.
- The `_Archive` directory under `Projects/` is implicitly created on first archive; safe — `walk_projects` excludes it.
- `psutil.open_files()` may need elevated privileges on Windows. If `is_open_in_live` is unreliable in your shell, document it and proceed; it's a best-effort guard, not a hard correctness requirement.
