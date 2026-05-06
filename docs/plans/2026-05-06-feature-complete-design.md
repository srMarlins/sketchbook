# Sketchbook v1 Feature-Complete Design

**Date:** 2026-05-06
**Status:** Approved — implementation in PRs D–G
**Predecessors:** [post-UI plan](2026-05-05-post-ui-implementation-plan.md), [Kotlin rewrite plan](2026-05-05-kotlin-rewrite-impl-plan.md), [sync+versioning design](2026-05-05-sync-versioning-design.md)

## Goal

Take the Kotlin/Compose desktop from "every screen reads from the catalog" (PR-C, just shipped) to "every advertised feature actually works." After this, the four user-visible verbs — **scan**, **sync**, **repair**, **propose** — produce real outcomes end-to-end without any in-memory placeholders, stubs, or write-only code paths.

## Scope

In:

- **Settings persistence** — library roots, GCS credentials, bucket name, cache settings survive a restart.
- **Proposal execution** — approving a proposal mutates the catalog (archive, set-tags, rename, etc.), not just the ack table.
- **Sample corpus + auto-match** — scanner populates `samples`; missing-sample findings carry candidates the user can one-click apply.
- **Sync engine completion** — `materializeAt` actually restores; `PullPoller` runs in the background; lease/lock state has heartbeat + expiry.
- **Polish** — Detail panel Tracks/Samples/Plugins tabs read parser data; journal viewer; dedup-savings stat per snapshot.

Out (already deferred, not regressing):

- OS-keychain rotation for credentials (v1.1).
- GCS object-generation tracking for stricter CAS (v1.2).
- Project dedup engine (separate design, not started).
- Songstrip readability fixes (separate design).

## Phasing

Four PRs, sequenced by dependency and "felt-pain-per-line-of-code":

### PR-D: Settings persistence + proposal execution

Smallest. Two independent fixes that share a PR because each is a half-day on its own.

**Settings.** Replace `InMemorySettingsRepository` with a `Preferences`-backed impl (`java.util.prefs.Preferences.userNodeForPackage(...)`). Library roots serialize as a typed JSON list (`Projects` / `UserSamples` / `External` discriminator preserved). Credential JSON stored as-is (plaintext for now; v1.1 rotates to OS keychain). The `Settings` data class shape doesn't change, so the holders/screens stay untouched.

**Proposal execution.** Currently `ProposalsStateHolder.approve()` calls `repository.approve()` which only writes the ack. Add a step *before* the ack: dispatch the proposal's `ProposalAction` list through `ActionExecutor` (a thin new class in `:shared:actions` that maps `ArchiveProject` → `ProjectRepository.archive`, `SetTags` → `ProjectRepository.setTags`). On execution failure, don't write the ack — surface a `Failed` effect instead. v1 supports just `ArchiveProject` (the only action `SqlProposalsRepository` emits today); the executor's design lets us extend by registering more handlers without touching the holder.

### PR-E: Sample corpus + missing-sample auto-match

**Corpus population.** Extend `JvmScanner` (or add `JvmSampleScanner`) to walk every `LibraryRoot.UserSamples` path on scan, hashing nothing — just `(path, filename, size_bytes, mtime, parent_dir)` tuples into the `samples` table. Idempotent via `INSERT OR REPLACE` keyed on `path`. Runs after the project scan finishes so sample-resolution can use the same data on subsequent scans.

**Auto-match.** Mirror Python's `_resolve_missing_sample`: for each `MissingSampleFinding`, query `samples WHERE filename = ? AND size_bytes = ?` (exact match — same name + same size = high confidence) and fall back to filename-only (medium confidence) capped at five candidates. Populate `MissingSampleFinding.autoMatch` (single best, or null) and `candidates` (list). The Needs Attention screen already has a render slot — the Python parity UI just needs the data.

**Apply.** New `RepairRepository.applyMissingSampleMatch(projectId, missingPath, candidatePath)` writes a journal entry + updates `project_samples.is_missing = 0` for that row. The `.als` itself is *not* rewritten in v1 — that needs the .als writer which doesn't exist yet. Instead the catalog records "user mapped this missing sample to that file" and a future "rewrite .als" PR can act on the ledger. Rationale: lets the user clear the Needs Attention queue today; persists the decision so we don't lose work when the writer ships.

### PR-F: Sync engine completion

The biggest of the four. Three subsystems share `sync_state` semantics, so one PR.

**Materializer.** New `:shared:sync` class that, given `(uuid, rev)`, downloads the manifest, fetches every blob ref by hash from GCS into `blob_cache`, and lays files down at the catalog-tracked working-tree path (atomic rename per file). Wires into `SqlSnapshotRepository.materializeAt`. After laydown, writes a fresh snapshot row marking the rewind (so timeline shows it).

**PullPoller.** Wire the existing `PullPoller` class — instantiate from `DesktopAppGraph` with a 30-second cadence, scoped to `appScope`. On each tick: list `sync_state` rows, for each project fetch the cloud `head` pointer, if `cloud_head_rev > local_rev` call `snapshots.recordSnapshot(...)` for the new head (without materializing — that's an explicit user action). Surface "remote update available" through the existing `SyncQueueState`.

**Lease/lock.** Replace `InMemoryLockRepository` with a real impl that writes `sync_state.lock_owner` + `lock_expires` on `take()`, refreshes via 60s heartbeat from a coroutine launched in `appScope`, and treats expired leases as "free to take." `forceTake()` writes a journal entry. Conflict semantics: if the cloud manifest CAS fails on push (someone else's lease wrote first), mark the project `Conflict` in `SyncQueueState` and surface a "remote diverged" UI affordance — but don't auto-merge; v1 just blocks further pushes until the user resolves manually.

This PR does not implement *automated* conflict resolution (three-way merge of catalog rows). That's a v1.1 design pass.

### PR-G: Polish

Three small, parallel-safe improvements that don't gate each other:

- **Detail panel parser tabs.** `DetailTracksTab` / `DetailSamplesTab` / `DetailPluginsTab` already render data when supplied; bind them to the project-detail state holder's existing `samples`/`plugins` fields (the holder already loads them). Drop the "wire up via repository in PR-18" placeholders.
- **Journal viewer.** New screen reachable from a sidebar entry below Settings (or a sub-tab on the project detail History tab — pick during impl). Reads `JournalRepository.observe()`; renders a chronological list of writes with actor + action-type + project. Read-only.
- **Dedup-savings stat.** When `SnapshotPipeline` writes a manifest, sum `(uploaded_blob_size_total - manifest_total_bytes)` and pass through to `recordSnapshot.new_bytes`. Surface on the Timeline rev rows ("12 MB new, 240 MB reused").

## Risks & non-risks

- **Sync engine has the highest blast radius.** Materializer overwrites files on disk. Mitigation: operate on a temp dir + atomic rename per file; never delete the working tree until laydown succeeds; gate behind an explicit "Rewind to this snapshot" affordance, not a passive UI.
- **Settings migration is non-existent** because there's no on-disk format today — the in-memory repo never wrote anywhere. First persist write is a clean baseline; nothing to migrate.
- **PR-D's proposal executor is a small new abstraction.** Resist the urge to over-design — register-by-string handler map, not a sealed-class hierarchy with validation, until we have more than one action type that exercises it.

## Acceptance

After PR-G merges:

1. Quit + relaunch the app: library roots, GCS creds, cache settings still set.
2. Approve an archive proposal: project disappears from Projects, journal entry exists.
3. Plug in UserSamples root, run scan: missing-sample finding shows "Match: <candidate>" with one-click apply that clears the row.
4. Push a project, change something, "Rewind to rev N" from Timeline: working tree reverts.
5. Two machines pointed at the same bucket: pull poll on machine B picks up A's push within 30s, surfaces it, lets B materialize.
6. Detail panel shows real track/sample/plugin lists for parsed projects; journal screen lists every write; Timeline rows show new-vs-reused byte split.

No screens display "wire up in PR-N" or "lands as a follow-up." Every approved action mutates state.
