# Audio: cross-device sync + versioning + Kotlin/Compose Multiplatform rewrite — design

**Date:** 2026-05-05
**Owner:** srmarlins@gmail.com
**Status:** Approved, ready for implementation plan
**Supersedes (in part):** `2026-05-04-tauri-native-design.md` — the Tauri+Python shell is replaced by a Kotlin/Compose Multiplatform app. The catalog/organizer architecture from `2026-05-04-audio-catalog-design.md` is preserved; only the implementation stack changes.

## 1. Scope & non-goals

**v1.0 ships:**
- Cross-device sync between Mac and Windows desktops, with cloud as the single source of truth.
- Automatic snapshot on every Ableton save (A1 — every save preserved as a manifest + blobs).
- Coalesced human-readable timeline (A2 — auto-snapshots promoted to `named` snapshots on idle/timer/manual checkpoint).
- Content-addressed sample blob store with global dedup, hardlinked into project working trees.
- Per-project "self-contained" escape hatch (full-copy snapshots for projects you want zero magic on).
- Lease-lock + auto-fork conflict handling for "open on two machines."
- Cloud blob store *is* the backup — no separate backup product.
- Rewind/restore UI: pick any project + any point in history, materialize that exact tree on disk.
- Catalog feature parity with the existing Python implementation (parser, scanner, FTS5 search, action/journal/proposal pattern, MCP).
- Compose Desktop app (Mac + Windows installable bundles).
- GitHub-PR development workflow with AI agent guidelines.

**Explicitly out of scope (deferred):**
- Mobile previewer — v1.1.
- Multi-user / sharing — v1.2 (a Cloudflare Worker coordinator wraps the existing bucket).
- Audio analysis (pyloudnorm/librosa/matchering, Python sidecar) — v1.3.
- Cross-project intelligence (find-similar) — v1.4.
- AbletonOSC live integration — v1.5.
- Templates, plugin catalog, preset/rack library, sample library curator — v1.6+.
- Real-time multi-user collab (Figma-style for DAWs). Not happening.
- Three-way merge of `.als` files. Auto-fork is the merge story.
- Auto-update for the desktop app, code signing, web target.

**Success criteria:**
1. Save a project on Windows. Within 30 seconds, the Mac sees the new snapshot in the catalog, and opening that project on Mac materializes the exact bytes the Windows save produced.
2. Open the same project on both machines. Both desktops show "currently open on `<other-host>`, last beat 12s ago." If the second machine saves anyway, the loser's version becomes a named branch — nothing lost.
3. Rewind any project to any save in its history; the rewound copy opens cleanly in Ableton.
4. Adding a 4 MB kick drum to project #847 results in roughly 4 MB of cloud upload, even if 49 other projects already use that exact kick.
5. Catalog DB, journal, proposals queue, MCP, and CLI continue working (in Kotlin) with same semantics as the Python v0.1.

## 2. Architecture

**Stack (locked):** Kotlin + Compose Multiplatform. Desktop targets first (JVM, Mac + Windows). Mobile (iOS + Android) deferred to v1.1. Web target not pursued.

### 2.1 Library / version pins

| Concern | Library | Version |
|---|---|---|
| Kotlin / Gradle | — | 2.3.x / 9.0 |
| DI | Metro (ZacSweers/metro) | 0.7.x |
| DB | SQLDelight + FTS5 dialect | 2.3.x |
| State holders | plain Kotlin StateFlow + sealed-class intents | — |
| Navigation | in-house sealed-class `NavStack` + `MutableStateFlow<List<Screen>>` | — |
| Coroutines | kotlinx-coroutines | 1.10.x |
| Filesystem I/O | kotlinx-io | 0.9.x |
| HTTP | Ktor client (CIO desktop, Darwin iOS, OkHttp Android) | 3.2.x |
| Serialization | kotlinx.serialization | 1.8.x |
| File watching (JVM) | io.methvin:directory-watcher | 0.18.x |
| Hashing | io.lktk:blake3-jni | 0.5.x |
| Logging | Kermit (Touchlab) | 2.x |
| UI | Compose Multiplatform | 1.11.x |
| Adaptive layouts | material3-adaptive | 1.1.x |
| Test runner | kotlin.test + Kotest assertions | 6.x |
| Flow tests | Turbine | 1.2.x |
| Mocking | hand-written fakes (commonTest); MockK only on JVM-only edges | — |
| Power-Assert | enabled | (built into Kotlin 2.x) |
| Compose Hot Reload | enabled | (CMP 1.10+) |

**Rejected / avoided:** MVIKotlin, Decompose, Roborazzi screenshot tests, KAPT, Anvil, Realm Kotlin, Moko-resources, `androidx.lifecycle:viewmodel-compose`, Koin, Room (FTS5 codegen is Android-only on KMP).

### 2.2 Module layout (Gradle, multi-module, acyclic dependencies)

```
audio/
  build.gradle.kts                        (root, conventions)
  gradle/libs.versions.toml               (version catalog)
  build-logic/                            (composite build: kmp-library, kmp-compose, kmp-test convention plugins)
  shared/
    core/                                 domain models, value types (ProjectId, BlobHash, ProjectPath),
                                          errors. Pure Kotlin, no platform deps.
    parser-als/                           StAX streaming parser, free-subtree pattern
    catalog/                              SQLDelight schema + DAO; scanner; FTS5 queries
    cloud/                                CloudBackend interface + DirectB2Backend impl;
                                          SigV4 signer in commonMain (~150 lines)
    sync-io/                              file watcher (Flow over WatchService),
                                          BLAKE3 hashing, snapshot pipeline,
                                          materialization (hardlink + copy fallback),
                                          lease-lock state machine
    repository/                           THE SEAM. Exposes Flow<Domain>; owns write semantics,
                                          journal entries, proposal coordination.
                                          UI + sync engine BOTH go through here.
    actions/                              Move/Rename/Archive/SetTags; journal write helpers
    sync/                                 Orchestration: schedules pipeline, coalesce job,
                                          pull poller per subscribed project
    mcp-server/                           Kotlin MCP server (uses io.modelcontextprotocol:kotlin-sdk).
                                          Separate JVM process; reads catalog.db.
    ui-shared/                            Theme tokens, primitives. Depends on `core` ONLY.
                                          No repository, no state-holders.
    feature-projects/                     ProjectListStateHolder + UI screen
    feature-project-detail/               …
    feature-timeline/                     snapshot history + rewind UI
    feature-proposals/                    AI proposals queue
    feature-settings/                     library roots, cloud config, self-contained toggles
    feature-needs-attention/              repair surface
  app-desktop/
    src/jvmMain/                          Compose Desktop entry, Metro root @DependencyGraph,
                                          NavStack root, OS integration (tray, file dialogs,
                                          native menu, "open in Live"), watcher service start
  sidecar-py/                             (deferred to v1.3) Python subprocess for librosa/matchering
  docs/
    plans/                                (existing)
    design-language/                      Compose theme spec
    ai/                                   AI agent guidelines (Claude Code, Junie, Copilot)
```

**Strict dependency rules** (acyclic):
- `core` is the root; nothing depends on platform types from it.
- `ui-shared` depends on `core` only — pure design system, no data flow.
- `repository` is the only path from a feature to data. Features never touch SQLDelight or `cloud` directly.
- `sync` and `actions` write via `repository` (so journal entries are emitted); they never reach into `catalog` directly.
- State-holders live in `feature-*` modules in the shared source set. `app-desktop` is a thin shell.

### 2.3 Data flow (unidirectional, DB is the rendezvous)

```
[Watcher]  ──┐
             │
[Cloud      ]┐                                    Compose UI
 poller    ──┘                                       │
               sync engine ──writes via             intent
                            repository ──┐           ▼
                                         │       StateHolder.accept(intent)
                                         │           │
                                         ▼           ▼
                                   catalog (SQLDelight) ◄── repository.write()
                                         │
                                         ▼
                                   Flow<Domain> ──► state-holder reducer
                                                         │
                                                         ▼
                                                    StateFlow<State> ──► Compose recompose
```

There is exactly one place data lives: the local SQLite catalog. Everything else is a view.

### 2.4 State-holder pattern (canonical for every screen)

```kotlin
class ProjectListStateHolder(
    private val scope: CoroutineScope,
    private val repo: ProjectRepository,
) {
    sealed interface Intent {
        data class Search(val q: String) : Intent
        data class Open(val id: ProjectId) : Intent
    }
    data class State(
        val query: String = "",
        val rows: List<ProjectRow> = emptyList(),
        val loading: Boolean = true,
    )
    sealed interface Effect {
        data class Navigate(val id: ProjectId) : Effect
    }

    private val query = MutableStateFlow("")
    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 8)
    val effects: SharedFlow<Effect> = _effects

    val state: StateFlow<State> =
        query.flatMapLatest { q ->
            repo.observeProjects(q).map { rows ->
                State(query = q, rows = rows, loading = false)
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), State())

    fun accept(intent: Intent) {
        when (intent) {
            is Intent.Search -> query.value = intent.q
            is Intent.Open   -> scope.launch { _effects.emit(Effect.Navigate(intent.id)) }
        }
    }
}
```

Compose reads `val s by holder.state.collectAsState()` and calls `holder.accept(...)`. Effects collected once at screen root via `LaunchedEffect`.

### 2.5 Navigation

In-house sealed-class `Screen` hierarchy + a `NavStack` value object held in `MutableStateFlow<List<Screen>>` at the root state-holder. ~50 lines, MPP-clean, no framework.

```kotlin
sealed interface Screen {
    data object ProjectList : Screen
    data class ProjectDetail(val id: ProjectId) : Screen
    data class Timeline(val id: ProjectId) : Screen
    data object Proposals : Screen
    data object Settings : Screen
}
class RootStateHolder(scope: CoroutineScope, …) {
    private val _stack = MutableStateFlow<List<Screen>>(listOf(Screen.ProjectList))
    val stack: StateFlow<List<Screen>> = _stack
    fun push(s: Screen) { _stack.value = _stack.value + s }
    fun pop()           { _stack.value = _stack.value.dropLast(1).ifEmpty { listOf(Screen.ProjectList) } }
}
```

Each `Screen` corresponds to a `feature-*` module. The root composable matches on the top of the stack. We can replace this with Decompose/Navigation 3 later if a real need emerges; v1 doesn't need it.

### 2.6 DI wiring (Metro)

- One `@DependencyGraph(AppScope::class) interface DesktopAppGraph` in `app-desktop`. Mobile shells later get their own roots.
- Modules contribute via `@ContributesTo(AppScope::class)` interfaces; root graph aggregates automatically (Anvil-style).
- Per-screen lifetimes via `@GraphExtension` so a state-holder's scope dies with its screen.
- Cross-module: `repository` `@ContributesBinding`s `ProjectRepository`; `sync` and features inject the interface, never the impl.

### 2.7 UI conventions (`ui-shared`)

- Slot APIs over param soup: `content: @Composable () -> Unit`, plus typed slots (`leading`, `trailing`).
- Stateless components by default; hoisted state. If state must live inside, expose `rememberFooState()`.
- Theme via `CompositionLocal`: `LocalAppColors`, `LocalAppTypography`, `LocalAppSpacing`. Same shape on desktop + future mobile.
- `modifier: Modifier = Modifier` always the first non-required parameter.

### 2.8 Coroutines best practices baked in

- Inject dispatchers via Metro (`@Provides @IODispatcher fun() = Dispatchers.IO`). Never hard-reference `Dispatchers.IO` in code under test.
- `Flow.stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)` for hot views.
- `channelFlow { awaitClose { } }` for watcher-style sources.
- Long-running work in `repository`-scoped coroutines, not state-holder-scoped; state-holders die with screens, sync work shouldn't.
- Tests: `runTest { }` + `StandardTestDispatcher` + `advanceUntilIdle()`; Turbine for Flow assertions; hand-written fakes in commonTest.

## 3. Data model

### 3.1 Manifest (the unit of a snapshot, JSON in cloud bucket)

```json
{
  "v": 1,
  "owner_user_id": "default",
  "project_uuid": "01HZQX5N3M8F9G2K7B1A6Y4WCE",
  "rev": 47,
  "parent_rev": 46,
  "timestamp": "2026-05-05T14:22:31.412Z",
  "host_id": "macstudio-9d4c",
  "host_name": "MacStudio",
  "kind": "auto",
  "label": null,
  "self_contained": false,
  "files": {
    "Project.als":            {"hash":"b3:1f2c…","size":312488,"mtime":"…"},
    "Samples/Imported/k.wav": {"hash":"b3:9a07…","size":4189112,"mtime":"…"}
  },
  "stats": {"file_count": 217, "total_bytes": 4831244012, "new_bytes": 312488}
}
```

- `kind ∈ {auto, named, branch}`. `auto` = every save (A1). `named` = coalesced timeline (A2). `branch` = conflict auto-forks.
- `owner_user_id` is forward-compat for v1.2 multi-user. v1 uses `"default"`.
- `self_contained` per-project flag controls dedup behavior (see §3.3).

### 3.2 Cloud layout (B2 or R2 bucket; per-tenant prefix from day one)

```
bucket/
  <user_id>/blobs/<hash[0:2]>/<hash>                  immutable, content-addressed
  <user_id>/blobs-private/<project_uuid>/<hash>       self-contained projects (no global dedup)
  <user_id>/manifests/<project_uuid>/<rev:08d>-<timestamp>-<host>.json
  <user_id>/manifests/<project_uuid>/HEAD             pointer to latest mainline rev (CAS target)
  <user_id>/locks/<project_uuid>.lock                 lease sidecar (see §5)
  schemas/manifest.v1.json                            shared
```

v1 hardcodes `user_id="default"`. v1.2 multi-user adds more `user_id` prefixes. No schema change required.

### 3.3 Local on-disk layout

```
$LIBRARY_ROOT/Projects/<name>/<...>                   normal Ableton project (working tree)
$LIBRARY_ROOT/Projects/<name>/.audio-id               sidecar JSON: {"uuid":"...","created_at":"..."}
$APP_DATA/cache/blobs/<hash[0:2]>/<hash>              content-addressed blob cache
$APP_DATA/cache/manifests/<project_uuid>/             downloaded manifest cache
$APP_DATA/catalog.db                                  SQLite (SQLDelight)
$APP_DATA/journal/                                    undo journal (preserved from v0.1)
$APP_DATA/proposals/                                  AI proposals (preserved from v0.1)
```

The `.audio-id` sidecar lets a project survive folder rename/move and still match its cloud history.

### 3.4 SQLDelight schema (additions to existing v0.1 catalog)

```sql
-- Stable identity for sync; existing projects.id stays as local int PK.
CREATE TABLE project_identity (
  project_id  INTEGER PRIMARY KEY REFERENCES projects(id),
  uuid        TEXT NOT NULL UNIQUE,
  created_at  TEXT NOT NULL
);

CREATE TABLE snapshots (
  project_uuid     TEXT NOT NULL,
  rev              INTEGER NOT NULL,
  parent_rev       INTEGER,
  timestamp        TEXT NOT NULL,
  host_id          TEXT NOT NULL,
  kind             TEXT NOT NULL CHECK(kind IN ('auto','named','branch')),
  label            TEXT,
  manifest_path    TEXT NOT NULL,         -- path in cloud bucket
  manifest_hash    TEXT NOT NULL,         -- BLAKE3 of manifest JSON
  file_count       INTEGER NOT NULL,
  total_bytes      INTEGER NOT NULL,
  new_bytes        INTEGER NOT NULL,
  PRIMARY KEY (project_uuid, rev)
);
CREATE INDEX snapshots_by_uuid_kind ON snapshots(project_uuid, kind, rev DESC);

CREATE TABLE blob_cache (
  hash         TEXT PRIMARY KEY,
  size         INTEGER NOT NULL,
  last_used    TEXT NOT NULL,
  pinned       INTEGER NOT NULL DEFAULT 0   -- pinned blobs survive GC
);

CREATE TABLE sync_state (
  project_uuid     TEXT PRIMARY KEY,
  local_rev        INTEGER NOT NULL,
  cloud_head_rev   INTEGER NOT NULL,
  dirty            INTEGER NOT NULL DEFAULT 0,
  self_contained   INTEGER NOT NULL DEFAULT 0,
  lock_owner       TEXT,
  lock_expires     TEXT,
  last_pulled      TEXT
);

CREATE TABLE pending_uploads (
  hash         TEXT PRIMARY KEY,
  size         INTEGER NOT NULL,
  source_path  TEXT NOT NULL,
  enqueued_at  TEXT NOT NULL,
  attempts     INTEGER NOT NULL DEFAULT 0
);
```

Existing tables (`projects`, `project_plugins`, `project_samples`, `tags`, `projects_fts`) are preserved as-is. First-run migration: assign UUIDs to existing projects, write `.audio-id` sidecars, populate `sync_state` with `local_rev=0, cloud_head_rev=0`.

## 4. Save → snapshot → sync flow

### 4.1 Trigger

A `WatchService`-backed Flow over each `Projects/*/Backup/` folder. New file CREATE event (Ableton drops a fresh file there on every successful save) → debounce 300ms → emit `SaveDetected(project_path)`.

### 4.2 Snapshot pipeline (Kotlin Flow / coroutine)

```
SaveDetected
  → loadProject(path) → project_uuid (from .audio-id), last_known_manifest
  → acquireLease(uuid)
       ├─ ours/free → continue
       └─ held by other host → emit Warning, queue retry, abort this save's sync
  → walkProjectTree(path) → List<FileEntry(rel_path, mtime, size)>
  → diff(entries, last_manifest) → (unchanged_paths, candidate_changes)
  → for each candidate:
       hash = blake3(file)
       if hash matches manifest entry for that path → mark unchanged
       else → enqueue (hash, path) for upload
  → for each new hash:
       if cloud HEAD <hash> exists → skip
       else → PUT blob (single-PUT < 100MB; multipart above)
  → composeManifest(uuid, rev=cloudHead+1, parent=cloudHead, kind="auto", files=...)
  → conditionalPutHEAD(uuid, expected_etag=cloudHeadEtag, new_manifest)
       ├─ success → write snapshot row, update sync_state
       └─ etag conflict → another host advanced HEAD; re-fetch HEAD,
                          save our work as kind="branch",
                          label="auto-fork: <our-host>-<ts>"
  → refreshLease(uuid)
  → emit SaveSnapshotted(rev)
```

The pipeline is `Flow<SnapshotProgress>` so the UI shows live progress: `Hashing 3/217 files`, `Uploading 4.2 MB / 12.7 MB`, `Writing manifest`, `Done — rev 47`.

### 4.3 Pull pipeline

Long-running coroutine per "subscribed" project (subscribed = opened on this host in last 30 days, or marked Pinned). Polls `manifests/<uuid>/HEAD` every 30s with `If-None-Match`. On new HEAD: fetch manifest JSON, write `snapshots` row. Does NOT materialize files until the user opens the project — manifests-only is kilobytes.

### 4.4 Materialization (when user opens a project at a specific rev)

```
for each (path, hash) in manifest.files:
  if Projects/<name>/<path> already exists with matching hash → keep
  else if cache/blobs/<hash> exists → hardlink (or copy if cross-volume) into project tree
  else download blob to cache, then hardlink/copy
```

Cross-volume hardlink fallback to copy is auto-detected by attempting `Files.createLink` and catching the cross-device exception.

### 4.5 Coalesce job (A2)

Background coroutine, runs every 5 min. Promotes one `auto` snapshot per project per "idle period" to `named` with auto-generated label `"checkpoint 2026-05-05 14:22"`. Heuristic: if ≥30 min since the last `named` AND project is idle (no save in 10+ min), promote the most recent `auto`. Manual "checkpoint" button always promotes-and-labels immediately. Default UI timeline shows `named` + `branch` only. "Show all saves" toggle reveals all `auto`. Nothing is ever deleted.

### 4.6 Self-contained projects

When `sync_state.self_contained=true`, skip dedup HEAD-check and PUT every blob fresh under `<user_id>/blobs-private/<uuid>/<hash>`. Storage cost is full-copy-per-snapshot. Toggle is per-project, persists in DB.

## 5. Conflict handling

### 5.1 Lease lock format (`<user_id>/locks/<project_uuid>.lock`)

```json
{
  "host_id": "macstudio-9d4c",
  "host_name": "MacStudio",
  "user_label": "srMarlins",
  "acquired_at": "2026-05-05T14:00:00Z",
  "heartbeat_at": "2026-05-05T14:14:30Z",
  "expires_at": "2026-05-05T14:29:30Z"
}
```

### 5.2 Acquisition

Conditional PUT (B2 has `If-None-Match`; R2 supports both `If-Match` and `If-None-Match`). Sequence:
1. GET current lock + etag.
2. If absent OR `expires_at < now`: PUT with `If-None-Match: *` (or `If-Match: <etag>`). On 412, retry from step 1.
3. If present and not expired: held by someone else.

### 5.3 Heartbeat

While the project's `.als` was saved-to recently (< 15 min) OR Ableton is detected having it open (process+handle scan): refresh `heartbeat_at` and `expires_at` every 5 min via conditional PUT (etag unchanged AND host_id matches). Background coroutine; cancels on shutdown.

### 5.4 Release

On Ableton-closed-and-no-recent-save (idle 15 min) OR app shutdown: PUT a tombstone (or DELETE if backend supports conditional delete with etag).

### 5.5 UI behavior

- Lock free → no badge.
- Held by us → small "editing" indicator.
- Held by other → orange badge: `Open on MacStudio · last beat 24s ago`. Save attempts proceed but auto-fork on commit. "Force-take" button explicit.
- Lock stale (heartbeat > 60s ago, expires soon) → yellow badge: `MacStudio went quiet 1m 12s ago`. Auto-takes on expiry.

### 5.6 Save divergence

Already covered in §4.2: on conditional-PUT-HEAD failure, our work becomes a `branch` manifest, never lost. UI surfaces it: `Your save became a branch because MacStudio saved first. View / merge / discard.`

### 5.7 Three-way merge?

Out of scope. Manifest-level diff is what we expose ("these files differ"). User picks a side. Both branches kept as snapshots cost ~kilobytes since blobs are deduped.

## 6. Roadmap & migration

**v1.0 — desktop sync + versioning + catalog parity (this design).**
**v1.1 — mobile previewer (iOS + Android via Compose Multiplatform).** Read-only timeline browser; pre-rendered preview blobs (waveform + 30s mp3) downloaded selectively. No sample sync, no edit.
**v1.2 — multi-user sharing.** Cloudflare Worker coordinator, ACLs, presigned-URL minting, server-mediated lease locks.
**v1.3 — audio analysis (Python sidecar).** pyloudnorm, librosa, matchering, JSON-RPC over stdin/stdout.
**v1.4 — cross-project intelligence (find-similar by chroma+MFCC).**
**v1.5 — AbletonOSC live integration.**
**v1.6+ — templates, plugin catalog, preset/rack library, sample library curator** (everything from existing v0.7–v1.0 catalog roadmap, ported).

### 6.1 Migration from existing repo

- **Same repo, same git history.** Push existing `audio` to GitHub. Existing Python `packages/` stays as the *reference implementation* — kept building and tested, treated as oracle/parity-check during the Kotlin port. Retired (deleted in one PR) once Compose Desktop hits feature parity.
- **No data migration.** Same SQLite catalog.db read by both implementations during parity period. Kotlin reads same schema; new sync tables are additive.
- **Existing design docs preserved.** This new design lives at `docs/plans/2026-05-05-sync-versioning-design.md`.
- **Tauri-native design (`2026-05-04-tauri-native-design.md`)** is superseded by this rewrite. SUPERSEDED note added rather than deletion.

## 7. Open questions (resolved)

- **Cloud schema for future multi-user?** → Per-tenant prefix from day one (`<user_id>/...`), `user_id="default"` in v1. Manifest schema includes `owner_user_id`.
- **Project UUID across rename/move?** → `.audio-id` sidecar inside each project + `project_identity` DB row.
- **iOS distribution model?** → Deferred; not laid out at v1.
- **MVI library?** → No library. Plain Kotlin `StateFlow` state-holders + sealed-class intents.
- **Navigation library?** → No library. In-house sealed-class `NavStack` + `MutableStateFlow<List<Screen>>`.
- **Screenshot tests?** → No. Manual local-run visual verification.
- **AWS SDK for B2/R2?** → No (`aws-crt-kotlin` is JVM-only). Hand-rolled SigV4 in commonMain.
- **Multipart upload threshold?** → 100 MB. Single PUT below; multipart above.
- **MCP server topology?** → Separate JVM process spawned by Claude Desktop config; reads catalog.db.

## 8. Validation criteria for the implementation plan

Implementation plan must include:
- Big-chunk PR breakdown (no per-file PRs).
- Each chunk lands in a feature branch and merges via PR review.
- AI agent guidelines (Claude Code, Google Junie, GitHub Copilot) under `docs/ai/`.
- Visual verification approach: agents run the Compose Desktop app locally, exercise the feature, attach screenshots/notes to the PR.
- AI code review approach: agents post review comments via `gh pr review`/MCP; PR description mandates a self-review checklist.
- Parity tracking against the existing Python implementation until retirement.
