# Post-UI Implementation Plan — Parser, Sync, Feature Screens

> **For Claude:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan
> task-by-task. Read `docs/plans/2026-05-05-sync-versioning-design.md` and
> `docs/plans/2026-05-04-effort-score-design.md` first; this is the *how*, not the *what*.

**Context.** PR #63 shipped the desktop home dashboard, effort-score scaffold (`compute_effort`
ported faithfully from Python; falls back to a file-size proxy until parser data arrives), and
sync-surface UI (per-row pip, sidebar caption, ActivityBar Syncing state, Settings cloud-sync
section, detail-panel sync pill + "Sync now"). The data layer remains stub-shaped:

- **No streaming `.als` parser** is wired into the scanner. Every `ProjectRow` ships with
  `colorTag = null`, `parseStatus = Pending`, `missingSampleCount = 0`, `tempo/trackCount`
  unset, no plugins, no sample refs. Effort-driven shelves (`forgotten-gems`) lean on the
  proxy; color-driven shelves (`almost-done`, `has-potential`) are uniformly empty;
  `broken` is uniformly empty.
- **`SyncQueue` is `InMemorySyncQueue`** — a deterministic stub seeded by `id mod 7`. There
  is no real upload, no manifest write, no conflict detection.
- **`Proposals`, `Needs attention`, `Timeline`** are feature-stubs: composables exist,
  state-holders observe their repos, but the repos are in-memory empty.

This plan covers the three phases needed to unblock that. Each phase is one PR.

---

## PR-A — Streaming `.als` parser wired through the scanner

**Goal.** Replace the file-size proxy with a real `compute_effort`. Make `colorTag`,
`parseStatus`, `missingSampleCount`, plugin/sample/track tabs in the detail panel populate.
Surface parser errors as `parseStatus = Failed` so the `broken` shelf becomes meaningful.

**The parser already exists** at `shared/parser-als/src/jvmMain/kotlin/com/sketchbook/als/AlsParser.kt`
(streaming StAX over a gunzipped stream, no DOM, faithful port of `packages/core/audio_core/parser/als.py`).
PR-A's work is the *integration*, not the parser itself.

### Files

- `shared/core/src/commonMain/kotlin/com/sketchbook/core/Project.kt` — extend `ProjectMetadata`
  with `colorTag: Int?` if not already present (the parser reads it from `<Color Value="…"/>`
  on `LiveSet/Tracks/.../Color`; verify and wire through).
- `shared/parser-als/src/jvmMain/kotlin/com/sketchbook/als/AlsParser.kt` — verify the parser
  emits `colorTag` and the missing-sample resolver hook; if not, add it (the path-resolution
  pass that decides "this `<SampleRef>` doesn't resolve to a file on disk" lives one layer
  above the streaming core because it touches I/O outside the gzipped stream).
- `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/Scanner.kt` — change the per-`.als`
  pipeline from "stat the file → emit empty `ProjectRow`" to "stat → parse → resolve sample
  refs against project root → compute effort → emit fully-populated `ProjectRow`". Parser
  failures become `parseStatus = Failed` rows so they show up in the `broken` shelf.
- `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/SampleResolver.kt` (new) — given a
  `ProjectMetadata.sampleRefs` list and a project root path, count how many refs resolve to
  a file. Mirrors `packages/core/audio_core/sample_resolver.py`.
- `shared/parser-als/src/jvmTest/kotlin/com/sketchbook/als/AlsParserTest.kt` — add cases for
  the user's actual library: a project the user knows the color of (verify color round-trips),
  a project with deliberate broken samples (verify count), the largest project in the
  library (verify heap stays bounded — peak < 200 MB).
- `app-desktop/src/jvmTest/kotlin/com/sketchbook/desktop/ScannerTest.kt` — add cases that
  force a parser failure (truncated gzip, malformed XML) and assert `parseStatus = Failed`.

### Tasks (TDD where applicable)

1. **Verify parser color-tag extraction.** Open Live, color-tag a known project red, save.
   Pipe it through `AlsParser.parse(path).colorTag` from a small `main` and confirm the
   integer. Adjust the parser if the tag isn't reaching `ProjectMetadata`. Commit.
2. **Add `SampleResolver`.** Pure function: `resolve(refs, projectRoot): Resolution(found, missing)`.
   Walks `refs` and for each: try the absolute path if Mac-style, then resolve against
   `projectRoot/Samples/...` joined from `relParts + relName`. Count misses. Tests with
   a fixture project that has 1 missing + 2 found samples. Commit.
3. **Move scan off `Files.walkFileTree` into a chunked `Flow`.** Today the scanner emits a
   single big `addRows` call when the walk completes. With parsing, every file takes
   200ms-2s; emit per-file so the UI updates as it goes. Use `flow { … }.flowOn(IO)` and
   keep `Progress.Scanning(filesVisited, projectsFound)` updating. Commit.
4. **Wire the parser into the scanner's per-file step.** Catch `IOException` and parser
   exceptions, attach as `parseStatus = Failed` with a short reason. Compute effort via
   `EffortScore.compute(meta, fileSizeBytes)`. Commit.
5. **Update `EffortScore` callers.** Remove the file-size-only fallback path (it stays in
   the function for safety, but `Scanner.kt` should always have meta now). Commit.
6. **Performance pass.** Run a full scan of `Z:/User/audio/Projects` (~1924 `.als`).
   Measure wall time + heap peak. If wall time > 2 minutes, parallelize per-file parsing
   with a bounded `Semaphore` (4–8 concurrent). If heap peak > 500 MB, audit `pendingPlugin` /
   `pendingSample` allocations. Commit.
7. **Detail panel: real Tracks/Samples/Plugins tabs.** Replace the placeholder copy with
   tables driven by the now-real `ProjectMetadata`. Tracks: Audio/MIDI/Return/Group counts +
   per-track names. Samples: each `SampleRef` with a "missing"/"found" flag. Plugins: name +
   format + track. Commit.
8. **Verify shelves populate.** Run the desktop, scan, screenshot home dashboard. Expect:
   `currently-working` non-empty (as before, mtime branch), `forgotten-gems` non-empty
   (real effort scores), `almost-done` non-empty (warm-tagged projects), `has-potential`
   non-empty (purple-tagged), `broken` non-empty (parser failures or missing samples).
   Attach to PR body. Commit.

### Acceptance

- A full scan of the test library finishes in under 90 seconds wall time on the dev
  machine, with peak heap under 500 MB.
- Every chip in the home strip has a non-zero count except where the user genuinely has
  no projects matching that bucket.
- Detail panel's Tracks/Samples/Plugins tabs show real data, not the placeholder.
- A `broken` row exists for at least one parser-failure fixture in the test suite, and
  the `broken` shelf surfaces it with the reason.
- `AlsParserTest` includes a memory-bound test on the user's largest `.als` (must finish
  in < 5 s with peak heap < 200 MB).

### Test plan (PR body)

- [ ] `./gradlew :shared:parser-als:check` green.
- [ ] `./gradlew :app-desktop:check` green.
- [ ] Full library scan: report wall time + heap peak in PR body.
- [ ] Screenshots: home dashboard with all 6 chips populated; detail panel Tracks tab; detail
      panel Samples tab showing a missing sample; detail panel Plugins tab.

### PR description template

```
feat(parser): wire streaming .als parser into the scanner

Replaces the file-size effort-score proxy with the real compute_effort
formula now that ProjectMetadata is filled. Color tags, missing samples,
plugin/sample/track counts populate. Parser failures surface as
parseStatus=Failed and feed the Broken shelf.

Scan performance: <wall time> over <N> files, peak heap <X> MB.
```

---

## PR-B — Real cloud `SyncQueue` (B2 / R2)

**Goal.** Replace `InMemorySyncQueue` with an upload pipeline that writes content-addressed
blobs to a backing object store, persists per-project sync state to local SQLite (so it
survives restart), and exposes the same `SyncQueue` interface so the UI doesn't change.

**Authoritative design.** `docs/plans/2026-05-05-sync-versioning-design.md` is the source
of truth on manifests, the dedup model, and the upload state machine. This PR implements
that design; reread it before starting.

### Pre-decisions to make before opening the branch

1. **B2 or R2?** The Kotlin rewrite plan picks GCS for v1 with R2 reconsidered at v1.2.
   B2 is cheaper for our blob mix (large infrequent reads). Pick one and write the
   decision into the design doc as an addendum before coding.
2. **Credentials shape.** The current Settings UI calls a credential blob "service-account
   JSON" (GCS shape). For B2 / R2 this is `{ access_key_id, secret_access_key, endpoint, bucket }`.
   Update the schema in `:shared:repository`.
3. **Conflict policy.** When local and remote heads diverge: do we auto-merge, take-local,
   take-remote, or stop and ask the user? Decide before coding the conflict path.

### Files

- `shared/sync/` (new module) — owns `SnapshotPipeline`, `BlobStore`, `Manifest`. The cloud
  contract.
- `shared/sync/src/commonMain/kotlin/com/sketchbook/sync/BlobStore.kt` — thin interface:
  `head(hash)`, `put(hash, bytes)`, `get(hash) -> bytes`. Two impls: `B2BlobStore` and
  `LocalCacheBlobStore` (write-through to disk under `~/.cache/sketchbook/blobs`).
- `shared/sync/src/commonMain/kotlin/com/sketchbook/sync/SnapshotPipeline.kt` — orchestrates:
  parse `.als` → split samples → BLAKE3-hash each → `head` then `put` if missing → write
  manifest → upload manifest.
- `shared/catalog/src/commonMain/sqldelight/com/sketchbook/catalog/db/Sync.sq` — new tables:
  `project_sync_state(project_id, state, last_remote_rev, last_local_rev, last_error,
  last_attempt_at)`, `pending_uploads(blob_hash, queued_at)`.
- `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/repo/SqlSyncQueue.kt` (new) —
  replaces `InMemorySyncQueue`, backed by SqlDelight. The UI doesn't change.
- `shared/feature-settings` — credential UI updates (B2/R2 fields instead of GCS JSON).

### Tasks

1. **Decision write-up.** Append a "Cloud backend: B2" (or R2) addendum to
   `2026-05-05-sync-versioning-design.md`. Include the credential shape and conflict policy.
   Commit.
2. **Add `:shared:sync` module skeleton + `BlobStore` interface + `LocalCacheBlobStore`.**
   Test: round-trip a 10 MB sample through put/get. Commit.
3. **Add `B2BlobStore` (or `R2BlobStore`).** Use Ktor 3.2 client with the chosen S3-compatible
   API. Test against a live test bucket; flag-gated so CI without creds skips it. Commit.
4. **Add `SnapshotPipeline`.** Integrate the parser (already done in PR-A) so it can split
   `<SampleRef>` payloads from the project archive. Hash with BLAKE3. Test against a known
   fixture: same project hashed twice = identical manifest. Commit.
5. **SqlDelight `Sync.sq`.** Run a migration; verify SQL passes the `:shared:catalog` tests.
   Commit.
6. **`SqlSyncQueue` impl + Metro graph swap.** `DesktopAppGraph.provideSyncQueue` now wires
   `SqlSyncQueue(catalog, scope, pipeline)`. Boot integration test: app starts, queue
   reflects DB state, queue updates as `pushNowById(id)` runs. Commit.
7. **Settings credential UI.** B2/R2 fields with a "Test connection" button that runs `head`
   against a known blob path. Commit.
8. **End-to-end smoke test.** Add one project, push, verify the manifest + at least one
   blob round-trip in the test bucket. Commit.

### Acceptance

- A push of a single project results in one manifest object + N blob objects in the bucket.
  Re-pushing yields zero new blobs (dedup verified).
- App restart preserves per-project sync state from the DB.
- The "Sync now" button in the detail panel actually uploads; ActivityBar's Syncing state
  reflects the real queue depth.
- A simulated network outage flips per-project state to a `Failed` variant with a stored
  reason; the next manual `pushNowById` retries.

### Test plan (PR body)

- [ ] `./gradlew :shared:sync:check` green.
- [ ] One project pushed end-to-end against a real test bucket; counts verified.
- [ ] App restart preserves sync state.
- [ ] Conflict path tested with a deliberate divergence.

### PR description template

```
feat(sync): real B2-backed SyncQueue with content-addressed blobs

Replaces the InMemorySyncQueue stub. SnapshotPipeline parses .als,
splits sample refs, BLAKE3-hashes, dedups via head-then-put, writes
manifest. SqlSyncQueue persists per-project state so restart preserves
queue. Conflict policy: <policy>.

Test bucket: <region>/<bucket>. Credentials in 1Password under
"Sketchbook B2 dev".
```

---

## PR-C — `Proposals`, `Needs attention`, `Timeline` real implementations

**Goal.** Take three feature-stub screens and back them with real data flowing from the
parser (PR-A) and the sync pipeline (PR-B).

### Files

- `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/ProposalsRepository.kt` —
  refine the contract. A "proposal" is a candidate action the app suggests: archive (untouched
  in 18 months, no color tag), tag-from-folder-name (folder is `2024-trap`, tag with
  `[year, genre]`), missing-sample-repair (point at a sibling `.als` whose path resolves).
- `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/RepairRepository.kt` —
  driven by `ProjectMetadata.macPathsCount > 0` and `missingSampleCount > 0`. Surfaces:
  "this project references `/Volumes/Drobo/...` which doesn't exist on this machine — point
  at a replacement folder?"
- `shared/feature-timeline/src/commonMain/kotlin/com/sketchbook/featuretimeline/...` —
  reads `SnapshotRepository.history(uuid)` (now populated by PR-B's `SnapshotPipeline`).
- `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/repo/SqlProposalsRepository.kt`,
  `SqlRepairRepository.kt` (new).
- `shared/catalog/src/commonMain/sqldelight/com/sketchbook/catalog/db/Proposals.sq`,
  `Repair.sq` (new).

### Tasks

1. **Proposals: archive candidates.** SQL query for "untouched > 18mo, no color tag,
   not already archived". Render as a list with "Archive" / "Dismiss" actions. Commit.
2. **Proposals: tag-from-folder-name.** Extract year + genre from project root path
   when it matches `^\d{4}` or known genre keywords; suggest as tags. Commit.
3. **Proposals: missing-sample-repair.** When `missingSampleCount > 0`, look at sibling
   project folders to find a path that resolves the same sample basename. Suggest the
   resolved root as a "repair root". Commit.
4. **Needs attention: macOS path repair.** When `macPathsCount > 0`, present a UI to
   re-map `/Volumes/X/...` to a Windows path. Persist the mapping per library root.
   Commit.
5. **Timeline.** Read `SnapshotRepository.history(uuid)` (now real after PR-B). Render
   each snapshot with the manifest summary (file count, total bytes, host name). Click a
   snapshot to compare against current. Commit.
6. **Wire into NotebookSidebar status counts.** Sidebar items get a small "(N)" suffix
   when their feature has unread items. Commit.

### Acceptance

- Proposals screen shows at least 5 distinct proposal types against the real library.
- Needs attention shows every project with `parseStatus=Failed` or `missingSampleCount > 0`,
  with a working repair flow for at least the macOS-path case.
- Timeline opens for any project with snapshot history and renders entries chronologically.

### Test plan (PR body)

- [ ] `./gradlew check` green.
- [ ] Screenshots: proposals (3 types visible), needs-attention (mac-path repair flow),
      timeline (5+ entries).

### PR description template

```
feat(features): real Proposals / Needs attention / Timeline screens

All three screens now read from real SQL-backed repositories, populated
by the parser (PR-A) and sync pipeline (PR-B). Proposals surfaces N
candidate actions; Needs Attention drives mac-path repair; Timeline
renders snapshot history per project.
```

---

## Sequencing

1. **PR-A first.** It's the only one of the three that's gated on prerequisites already
   shipped (the parser exists; the integration is the work). Without parser data, every
   downstream UI surface is empty.
2. **PR-B second.** Sync depends on the parser's `ProjectMetadata` to know what to upload.
3. **PR-C third.** Both repository data sources (parser + sync) need to be real first.

Each PR has its own branch off `main` and merges via squash. Don't stack PR-B on PR-A's
branch — wait for A to merge to `main`, then branch B fresh. Same for C off B.

## Risks and unknowns

- **Parser color-tag accuracy.** Live's color encoding may differ between versions. The
  Python parser was tested against versions 9–12; the Kotlin port reuses the same logic.
  Verify against at least one Live 11 and one Live 12 project before declaring PR-A done.
- **Cloud-store choice latency.** B2's API has S3 compatibility but quirks (no `IfNoneMatch`
  on PUT). Plan accordingly — the dedup `head`-then-`put` race is fine because two clients
  uploading the same hash converge.
- **Conflict UX.** `Conflict` per-project state is rendered today (the pip + the pill in the
  detail panel). The actual *resolution* UI doesn't exist yet — stub a "force local" /
  "force remote" picker in PR-B and improve in a later pass once we have a real conflict on
  hand to test against.
- **Per-file scan latency.** Parsing 1924 `.als` could push scan past 2 minutes. PR-A task 6
  has a parallelization fallback; if even that isn't enough, we may need to fall back to a
  cheap-pass / deep-pass split (cheap = file size + mtime, deep = parse on detail-open).
