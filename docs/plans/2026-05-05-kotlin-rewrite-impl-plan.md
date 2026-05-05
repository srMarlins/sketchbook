# Sketchbook — Kotlin / Compose Multiplatform Rewrite Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Read `docs/plans/2026-05-05-sync-versioning-design.md` *first* (authoritative architecture); this plan is the *how*, not the *what*.

**Goal:** Rewrite the audio app (now named **Sketchbook**) in Kotlin + Compose Multiplatform with cross-device sync, versioning, and content-addressed cloud storage. The existing Python implementation stays as a parity reference until cutover.

**Architecture:** See `docs/plans/2026-05-05-sync-versioning-design.md`. Multi-module Gradle KMP; Compose Desktop first (Mac + Windows JVM); Metro DI; SQLDelight + FTS5; Ktor 3.2 (CIO); content-addressed B2/R2 blobs; plain Kotlin `StateFlow` state-holders + sealed-class intents (no MVI library); in-house sealed-class `NavStack`; `repository` as the single seam between data and features.

**Tech Stack:** Kotlin 2.3.x, Gradle 9.0, Compose Multiplatform 1.11.x, Metro 0.7.x, SQLDelight 2.3.x, Ktor 3.2.x, kotlinx-io 0.9.x, kotlinx-coroutines 1.10.x, kotlinx.serialization 1.8.x, BLAKE3-jni 0.5.x, directory-watcher 0.18.x, Kermit 2.x, material3-adaptive 1.1.x, Turbine 1.2.x, Kotest assertions, Power-Assert plugin.

---

## How this plan is organized

- **Phases** group related PRs.
- **PR-N** is one merge unit. PRs are chunky but coherent.
- **Tasks** inside a PR are bite-sized (TDD where applicable: write test → run failing → implement → run passing → commit). Many commits inside a PR; squash-merge at PR close.
- Each PR has: **Goal · Files · Tasks · Acceptance · Test plan · PR description template.**

## Working agreements

1. **One PR = one feature branch.** Branch name: `pr-<NN>-<slug>`. Examples: `pr-04-catalog-sqldelight`, `pr-12-feature-projects`.
2. **Conventional Commits inside the PR**, no `--no-verify`. Example commits: `feat(catalog): add FTS5 virtual table`, `test(parser-als): cover free-subtree memory bound`.
3. **Squash-merge at PR close.** Final squash message follows the same Conventional Commits format. Body links the design doc section that's being implemented.
4. **No PR merges without:**
   - Green CI (`./gradlew check` passes).
   - At least one AI code review (`gh pr review` from Claude Code) — see §AI Agent Workflow below.
   - Self-review checklist completed in PR body (template in `.github/pull_request_template.md`).
   - For UI-touching PRs: a screenshot or screen-recording attached to the PR body, taken from a local run.
5. **Branch protection on `main`:** require PR + 1 AI review + green CI. No direct pushes.
6. **No backwards-compat hacks.** This is greenfield Kotlin; nothing else consumes our APIs yet. Refactor freely within the PR.
7. **No premature abstractions.** `expect/actual` only when a second target exists or is imminent. `interface` only when there are ≥2 implementations or a test fake.
8. **No new libraries** without an entry in `gradle/libs.versions.toml` and a one-line justification in the PR body.

## AI Agent Workflow

Three AI surfaces, three roles:

- **Claude Code** — primary implementer. Reads `docs/ai/CLAUDE.md`. Owns: writing tasks per the plan, running tests locally, attaching screenshots from local Compose Desktop runs, posting `gh pr review` after self-review.
- **Google Junie** — second-opinion reviewer for architecture-shaped PRs (modules, schema, public APIs). Reads `docs/ai/JUNIE.md`. Posts a review comment focused on Kotlin idiom, MPP best practices, and module-boundary violations.
- **GitHub Copilot** — line-level coauthor inside the editor. Reads `docs/ai/COPILOT.md`. Lower trust; suggestions must pass tests + the human reviewer's eye.

Visual verification process for UI PRs (Claude Code performs):

1. Check out the PR branch.
2. Run `./gradlew :app-desktop:run` to launch the Compose Desktop app locally.
3. Exercise the new feature end-to-end against a copy of the test library (`Z:\User\audio\Projects` is the destructive-test root).
4. Capture a screenshot per state shown by the feature (empty / loaded / error).
5. Drop screenshots into the PR body via `gh pr comment <pr> --body-file <md>`.
6. Note any unexpected behavior; if found, fix in the same PR before requesting human review.

Code review process (Claude Code performs after self-review):

1. Run `gh pr diff <pr>` and read it cold.
2. Walk the diff against the design doc section the PR claims to implement.
3. Post review via `gh pr review <pr> --comment` with concrete file:line citations for any concerns.
4. Approve only after: tests pass, design alignment confirmed, no obvious regressions.

---

## Phase 0 — Project bootstrap

### PR-0: GitHub repo, AI guidelines, CI scaffold, PR/branch protection

**Goal:** Push existing repo to GitHub, install AI agent guidelines + PR template + CI workflow, enable branch protection. No code yet.

**Files:**
- Create: `.github/pull_request_template.md`
- Create: `.github/workflows/ci.yml`
- Create: `.github/CODEOWNERS`
- Create: `docs/ai/CLAUDE.md`
- Create: `docs/ai/JUNIE.md`
- Create: `docs/ai/COPILOT.md`
- Create: `CONTRIBUTING.md`
- Create: `.gitattributes` (force LF in `*.kt`, `*.kts`, `*.sq`, `*.json`, `*.yml`, `*.md`)

**Tasks:**

1. Create GitHub repo `srMarlins/sketchbook` (private). Push existing local `main`. Set default branch `main`.
2. Add `.gitattributes` with LF normalization for code files. Commit.
3. Write `CONTRIBUTING.md`: branch naming, commit format, PR workflow, "no merges without AI review", visual-verification requirement for UI PRs.
4. Write `docs/ai/CLAUDE.md`: project context (point to design doc + this plan); module boundary rules; state-holder pattern; repository-as-seam; Metro DI conventions; testing approach (kotlin.test + Turbine + hand-written fakes; no MockK in commonTest); PR workflow; visual-verification steps; explicit list of *avoided* libraries (MVI frameworks, Decompose, screenshot tests, Koin, Room).
5. Write `docs/ai/JUNIE.md`: review focus areas (Kotlin idiom, MPP best practices, module boundaries, coroutine scoping); when to defer to design doc vs raise concern.
6. Write `docs/ai/COPILOT.md`: line-level coauthor scope; do not invent module boundaries; respect the existing design doc; lower trust by default.
7. Write `.github/pull_request_template.md` with the self-review checklist (design alignment, tests added, screenshots if UI, no new libs without justification).
8. Write `.github/CODEOWNERS`: assign self as default owner.
9. Write `.github/workflows/ci.yml` with placeholder jobs (will fill in PR-1 once Gradle exists). Job: `setup-java@v4` JDK 21, Gradle wrapper invocation. Skip-on-empty for now.
10. Enable branch protection on `main` via GitHub UI: require PR, require status check `ci`, require 1 review, no force-push.
11. Commit + open PR-0 against itself (special case: first PR can self-merge after CI placeholder green).

**Acceptance:**
- Repo on GitHub with `main` protected.
- `docs/ai/*` and `CONTRIBUTING.md` present.
- PR template renders on new PR creation.
- `.github/workflows/ci.yml` exists (may be a no-op until PR-1).

**Test plan:** Open a dummy PR; confirm template renders + protection blocks direct push.

**PR body template:** "Bootstrap: GitHub setup, AI agent guidelines, CI scaffold, PR template. No code. Closes none."

---

## Phase 1 — Foundation modules

### PR-1: Gradle multi-module skeleton + version catalog + convention plugins

**Goal:** Establish the multi-module KMP build with version catalog, convention plugins in `build-logic/`, hierarchical source sets, and KSP2 wired. No application code yet — just empty modules with `build.gradle.kts` files that compile.

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `build-logic/settings.gradle.kts`, `build-logic/build.gradle.kts`
- Create: `build-logic/src/main/kotlin/kmp-library.gradle.kts`
- Create: `build-logic/src/main/kotlin/kmp-compose.gradle.kts`
- Create: `build-logic/src/main/kotlin/kmp-test.gradle.kts`
- Create: `shared/{core,parser-als,catalog,cloud,sync-io,repository,actions,sync,mcp-server,ui-shared}/build.gradle.kts` (empty modules with the right plugin)
- Create: `app-desktop/build.gradle.kts` (Compose Desktop application plugin only)
- Modify: `.github/workflows/ci.yml` — wire `./gradlew check`

**Tasks:**

1. Initialize Gradle wrapper to 9.0. Commit.
2. Write `gradle/libs.versions.toml` with every pinned version from the design doc §2.1.
3. Write `build-logic/` composite build with three convention plugins:
   - `kmp-library`: applies `kotlin("multiplatform")`, sets up `jvm()` target, default hierarchy, common test deps.
   - `kmp-compose`: applies `kmp-library` + `org.jetbrains.compose` plugin, adds Compose dependencies.
   - `kmp-test`: configures `kotlin-test`, Kotest assertions, Turbine for the test source sets.
4. Write `settings.gradle.kts` with all 11 modules and `app-desktop`. Use type-safe project accessors.
5. Write each module's `build.gradle.kts` applying the correct convention plugin. Empty `commonMain/kotlin/.gitkeep` placeholders.
6. Wire Power-Assert plugin in `build.gradle.kts`. Wire Compose Hot Reload in `kmp-compose`.
7. Wire CI: `.github/workflows/ci.yml` runs `./gradlew check` on PR + push.
8. Confirm `./gradlew check` is green locally + on CI.

**Acceptance:** Empty multi-module build compiles. CI green. `./gradlew projects` lists all 12 modules.

**Test plan:** Add one trivial test in `shared/core/commonTest`, confirm it runs via `./gradlew :shared:core:check`.

**PR body:** "Foundation: Gradle 9, KMP multi-module skeleton, convention plugins, version catalog, Power-Assert + Compose Hot Reload enabled. CI green."

---

### PR-2: `core` module — domain types, errors, value classes

**Goal:** Pure-Kotlin domain types referenced by every other module. No platform deps; no I/O.

**Files:**
- Create: `shared/core/src/commonMain/kotlin/com/sketchbook/core/Ids.kt` — `value class ProjectId`, `value class BlobHash`, `value class SnapshotRev`, `value class UserId`.
- Create: `shared/core/src/commonMain/kotlin/com/sketchbook/core/ProjectPath.kt` — own value type (no `java.io.File`/`okio.Path` leak).
- Create: `shared/core/src/commonMain/kotlin/com/sketchbook/core/Errors.kt` — sealed `SketchbookError` hierarchy.
- Create: `shared/core/src/commonMain/kotlin/com/sketchbook/core/Project.kt` — `Project`, `ProjectRow`, `ProjectMetadata`.
- Create: `shared/core/src/commonMain/kotlin/com/sketchbook/core/Snapshot.kt` — `Snapshot`, `SnapshotKind`.
- Create: `shared/core/src/commonMain/kotlin/com/sketchbook/core/Manifest.kt` — `Manifest` (matches §3.1 schema).
- Create: `shared/core/src/commonTest/kotlin/com/sketchbook/core/*Test.kt`

**Tasks:**

1. TDD `BlobHash`: write test asserting it parses `b3:` prefix and rejects malformed input. Run failing → implement → green.
2. TDD `ProjectPath`: write test for path normalization (forward-slash internal) and `relativeTo` semantics. Run failing → implement → green.
3. Define `SketchbookError` sealed hierarchy with `NotFound`, `Conflict`, `IntegrityError`, `IoFailure`, `RemoteFailure(status, body)`.
4. Define `Manifest` with `kotlinx.serialization` `@Serializable` + `@SerialName("v")` for the version field.
5. Roundtrip-test `Manifest` JSON serialization (encode → decode → equals).
6. Commit progressively.

**Acceptance:** `:shared:core:check` green; types referenced by later modules without modification.

---

### PR-3: `parser-als` module — StAX streaming `.als` parser with free-subtree pattern

**Goal:** Port the Python `lxml`-iterparse parser to Kotlin StAX. Hard requirement: bounded memory on a 543MB project (the prior Python DOM blew to 25 GB).

**Files:**
- Create: `shared/parser-als/src/jvmMain/kotlin/com/sketchbook/als/AlsParser.kt`
- Create: `shared/parser-als/src/jvmTest/kotlin/com/sketchbook/als/AlsParserTest.kt`
- Add: small fixture `.als` files in `shared/parser-als/src/jvmTest/resources/fixtures/`

**Tasks:**

1. TDD: write a test that opens a 100MB+ fixture and asserts max heap stays under 256MB during parse. Use `Runtime.getRuntime().totalMemory()` + GC sample.
2. Implement gunzip stream + `XMLInputFactory` event reader. After processing each major element (`<Track>`, `<DeviceChain>`), null out references to subtree.
3. Extract the v0.1 minimum metadata: tempo, time signature, track counts by type, plugin names per track, sample references, last-saved Live version.
4. Add unit tests for each metadata field on a known fixture.
5. Add a 543MB stress-test fixture (or synthesized equivalent — generated test only run via `./gradlew :shared:parser-als:stressTest`).

**Acceptance:** Parses fixtures correctly; stress test passes under 256MB heap; CI runs unit tests (stress test runs nightly only).

**Test plan:** Fixtures include: empty project, project with VST3 + AU + native devices, project with broken sample refs, project with non-ASCII names.

---

### PR-4: `catalog` module — SQLDelight schema, FTS5, scanner, DAO

**Goal:** Replicate the v0.1 catalog schema in SQLDelight; add new sync-related tables (§3.4); wire the scanner (parallel walk + parser invocation + upsert).

**Files:**
- Create: `shared/catalog/src/commonMain/sqldelight/com/sketchbook/catalog/Catalog.sq` — full schema including FTS5 virtual table.
- Create: `shared/catalog/src/commonMain/kotlin/com/sketchbook/catalog/CatalogDb.kt` — driver setup (xerial JDBC on JVM).
- Create: `shared/catalog/src/commonMain/kotlin/com/sketchbook/catalog/Scanner.kt`.
- Create: `shared/catalog/src/jvmTest/kotlin/com/sketchbook/catalog/*Test.kt`

**Tasks:**

1. Add SQLDelight Gradle plugin to convention plugin or directly here.
2. TDD: empty-DB test — open driver, run schema, confirm 1 row in `sqlite_master` for each table + FTS5 virtual.
3. Write the `.sq` schema covering existing v0.1 tables (`projects`, `project_plugins`, `project_samples`, `tags`, `project_tags`, `projects_fts`) and new sync tables (`project_identity`, `snapshots`, `blob_cache`, `sync_state`, `pending_uploads`).
4. TDD FTS5: insert 5 fake projects, run a `MATCH 'kick'` query, assert correct rows ordered by `bm25()`.
5. Implement `Scanner.scan(root: ProjectPath): Flow<ScanProgress>`: walk projects, parse each, upsert. Use `flow { }` + `Dispatchers.IO` (injected).
6. Test scanner against a fixture directory of 3 fake projects.
7. Commit.

**Acceptance:** Schema migrations reproducible; FTS5 search works; scanner emits progress events.

---

## Phase 2 — Repository, cloud, sync engine

### PR-5: `repository` module — the seam

**Goal:** Define `ProjectRepository`, `SnapshotRepository`, `JournalRepository` interfaces in `shared/repository/commonMain`. SQLDelight-backed impl. UI never reaches past this.

**Files:**
- Create: `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/*.kt` (interfaces + impls)
- Create: `shared/repository/src/commonTest/kotlin/com/sketchbook/repo/*Test.kt`

**Tasks:**

1. Define `ProjectRepository` with `observeProjects(query: String): Flow<List<ProjectRow>>`, `observeProject(id): Flow<Project>`, `setQuery(...)`, `move(...)`, `rename(...)`, `archive(...)`, `setTags(...)`. Each write returns `Either<AudioError, JournalEntry>` or similar result type.
2. Define `SnapshotRepository` with `observeHistory(project_uuid): Flow<List<Snapshot>>`, `recordSnapshot(...)`, `materializeAt(rev)`.
3. Define `JournalRepository` mirroring v0.1 journal semantics.
4. Implement against SQLDelight; map row classes to domain models (do NOT leak SQLDelight types past this module).
5. TDD: write a fake `Catalog` driver (in-memory SQLDelight) and assert observe→write→observe round-trips emit new state via Turbine.
6. Commit.

**Acceptance:** Public API typed in domain models only. Row types kept internal.

---

### PR-6: `cloud` module — `CloudBackend` interface + SigV4 signer + `DirectB2Backend`

**Goal:** Build the dumb-cloud client. Conditional PUT/GET, multipart for >100MB, content-addressed paths, lock CAS primitives.

**Files:**
- Create: `shared/cloud/src/commonMain/kotlin/com/sketchbook/cloud/CloudBackend.kt` (interface).
- Create: `shared/cloud/src/commonMain/kotlin/com/sketchbook/cloud/SigV4.kt` (hand-rolled).
- Create: `shared/cloud/src/commonMain/kotlin/com/sketchbook/cloud/DirectB2Backend.kt` (Ktor CIO impl).
- Create: `shared/cloud/src/commonTest/kotlin/com/sketchbook/cloud/SigV4Test.kt` — vectors against AWS-published SigV4 test vectors.
- Create: `shared/cloud/src/jvmTest/kotlin/com/sketchbook/cloud/DirectB2BackendTest.kt` — uses MockEngine.

**Tasks:**

1. TDD SigV4 with the published test vectors (`https://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html`). 4-5 vectors covering canonical request, string-to-sign, signing key, final signature.
2. Define `CloudBackend`:
   ```kotlin
   interface CloudBackend {
     suspend fun headBlob(hash: BlobHash): Boolean
     suspend fun putBlob(hash: BlobHash, source: RawSource, size: Long)
     suspend fun getBlob(hash: BlobHash): RawSource
     suspend fun readManifest(uuid: ProjectUuid, rev: SnapshotRev): Manifest
     suspend fun listManifests(uuid: ProjectUuid, sinceRev: SnapshotRev?): List<ManifestRef>
     suspend fun appendManifestHead(uuid: ProjectUuid, expectedHeadEtag: String?, manifest: Manifest): Result<String>
     suspend fun acquireLock(uuid: ProjectUuid, lock: LeaseLock): LeaseAcquireResult
     suspend fun refreshLock(uuid: ProjectUuid, lock: LeaseLock, expectedEtag: String): LeaseRefreshResult
     suspend fun releaseLock(uuid: ProjectUuid, expectedEtag: String)
   }
   ```
3. Implement `DirectB2Backend` using Ktor CIO + the SigV4 signer. Single PUT path; multipart path (>100MB).
4. TDD with `MockEngine`: assert correct paths, headers (`If-None-Match`/`If-Match`), body hash.
5. Wire to a real B2 sandbox bucket (env vars only, never committed). Smoke test optional in CI nightly.
6. Commit.

**Acceptance:** Unit tests + MockEngine green. SigV4 vectors pass byte-for-byte.

---

### PR-7: `sync-io` module — file watcher Flow + BLAKE3 hashing + materialization

**Goal:** Pure I/O primitives: watcher Flow over `Backup/`, BLAKE3 hashing of files, hardlink-or-copy materialization. No orchestration here.

**Files:**
- Create: `shared/sync-io/src/jvmMain/kotlin/com/sketchbook/syncio/Watcher.kt` — wraps `directory-watcher` in a Kotlin Flow.
- Create: `shared/sync-io/src/jvmMain/kotlin/com/sketchbook/syncio/Hasher.kt` — BLAKE3 over `RawSource` chunks.
- Create: `shared/sync-io/src/jvmMain/kotlin/com/sketchbook/syncio/Materializer.kt` — `Files.createLink` with cross-volume copy fallback.
- Create: `shared/sync-io/src/jvmTest/kotlin/com/sketchbook/syncio/*Test.kt`

**Tasks:**

1. TDD `Watcher.watch(path: Path): Flow<SaveEvent>`: write a temp file in a tmp watched dir, assert `SaveEvent(path)` is emitted. Use `runTest` + `Turbine`.
2. TDD debounce: rapidly write 5 files; assert exactly 1 event per file with 300ms debounce.
3. TDD `Hasher.hash(path: Path): BlobHash`: known fixture → known BLAKE3 hex.
4. TDD `Materializer.materialize(blob, target)`: same-volume → hardlink (assert same inode via `Files.readAttributes`); cross-volume → copy (assert different inode + content equal).
5. Add JNI native lib loading guard so blake3-jni doesn't break tests on unusual platforms.
6. Commit.

**Acceptance:** All tests green; watcher robust against atomic rename + temp files.

---

### PR-8: `actions` module — Move/Rename/Archive/SetTags + journal writer

**Goal:** Port v0.1 `Action` types to Kotlin. Each goes through `repository`. Journal entries written to disk in the same format as Python (so the parity period works).

**Files:**
- Create: `shared/actions/src/commonMain/kotlin/com/sketchbook/actions/Action.kt` — sealed interface + impls.
- Create: `shared/actions/src/commonMain/kotlin/com/sketchbook/actions/Journal.kt` — JSON writer matching Python's format.
- Tests: `shared/actions/src/commonTest/kotlin/...`

**Tasks:**

1. Define `sealed interface Action { suspend fun validate(repo); suspend fun execute(repo): JournalEntry }`.
2. Implement `MoveProject`, `RenameProject`, `SetColorTag`, `SetTags`, `ArchiveProject`, `Undo`.
3. TDD each action against an in-memory `ProjectRepository` fake.
4. TDD journal compatibility: encode action → JSON → assert exact parity with Python journal fixtures (copy 2-3 from `data/journal/`).
5. Commit.

**Acceptance:** Round-trip parity with Python journal format. Undo reverses any action.

---

### PR-9: `sync` orchestration — pipeline, coalesce, pull poller

**Goal:** Compose `sync-io` + `cloud` + `repository` into the snapshot pipeline (§4.2), the pull poller (§4.3), and the coalesce job (§4.5).

**Files:**
- Create: `shared/sync/src/commonMain/kotlin/com/sketchbook/sync/SnapshotPipeline.kt`
- Create: `shared/sync/src/commonMain/kotlin/com/sketchbook/sync/PullPoller.kt`
- Create: `shared/sync/src/commonMain/kotlin/com/sketchbook/sync/CoalesceJob.kt`
- Create: `shared/sync/src/commonMain/kotlin/com/sketchbook/sync/LeaseLockState.kt` (state machine)
- Tests: in-memory fake `CloudBackend` + `ProjectRepository`.

**Tasks:**

1. Implement `SnapshotPipeline.run(saveEvent): Flow<SnapshotProgress>` exactly matching §4.2 algorithm.
2. TDD happy path: 1 changed file + 1 unchanged → 1 blob upload + 1 manifest write.
3. TDD dedup: same blob already in cloud → no upload, manifest still written.
4. TDD divergence: stale parent_rev → manifest landed as `kind=branch` with auto-fork label.
5. Implement `PullPoller.subscribe(uuid)` — polls HEAD every 30s, writes `snapshots` rows on new manifests.
6. Implement `CoalesceJob.run()` — promotes oldest unpromoted `auto` snapshot to `named` per the §4.5 rules.
7. Implement `LeaseLockState` — acquire/heartbeat/release state machine with `StateFlow<LockState>`.
8. TDD lease lock: simulated heartbeat keeps lock alive; missed heartbeat lets it expire; conflicting host returns `Held` state.
9. Commit progressively.

**Acceptance:** Full pipeline from `SaveEvent` to `SnapshotSaved` works against fake cloud + fake repo. Conflict scenarios produce correct branch manifests.

---

### PR-10: `mcp-server` — Kotlin MCP server using the official SDK

**Goal:** Replace FastMCP. Same tool surface as v0.1 (queries: `search_projects`, `get_project`, `list_recent`; actions: `propose_batch`).

**Files:**
- Create: `shared/mcp-server/src/jvmMain/kotlin/com/sketchbook/mcp/McpServer.kt`
- Create: `shared/mcp-server/src/jvmMain/kotlin/com/sketchbook/mcp/Tools.kt`
- Create: `app-mcp/` — separate JVM entry point, depends on `:shared:mcp-server` + `:shared:repository`.
- Update: Claude Desktop config snippet in `docs/mcp-setup.md`.

**Tasks:**

1. Add `io.modelcontextprotocol:kotlin-sdk` to version catalog.
2. Define each tool with proper JSON Schema for params (kotlinx.serialization).
3. Wire `propose_batch` to write JSON to `data/proposals/` (matching v0.1 exact format so parity holds).
4. TDD each tool against a fake repository.
5. Add an `app-mcp/run` task in Gradle for local launch.
6. Smoke test: launch, send `tools/list`, get expected schema response.
7. Update `docs/mcp-setup.md` with the new launch command for Claude Desktop config.
8. Commit.

**Acceptance:** Claude Desktop can connect to the new server and invoke each tool with parity behavior.

---

## Phase 3 — UI scaffolding

### PR-11: `ui-shared` — theme tokens + primitive components

**Goal:** Headless-style design system. `LocalAppColors`/`LocalAppTypography`/`LocalAppSpacing` `CompositionLocal`s. Reusable primitives that depend on `core` only.

**Files:**
- Create: `shared/ui-shared/src/commonMain/kotlin/com/sketchbook/uishared/theme/*.kt` (Colors, Typography, Spacing, Theme)
- Create: `shared/ui-shared/src/commonMain/kotlin/com/sketchbook/uishared/components/{Button,TextField,Surface,RowItem,Badge,Tag,Pill,EmptyState}.kt`
- Create: `app-gallery/` Compose Desktop module that loads every component (used for visual review during PRs).

**Tasks:**

1. Define color tokens (light + dark; warm cream/parchment in light, walnut in dark per existing stationery aesthetic memory).
2. Build `AppTheme { content }` composable + provide `CompositionLocal`s.
3. Build primitives with slot APIs:
   - `Button(modifier, onClick, leading? trailing? content)`
   - `TextField(modifier, value, onChange, placeholder?, leading?, trailing?)`
   - `Surface(modifier, padding, content)`
   - `RowItem(modifier, leading?, title, subtitle?, trailing?)`
   - `Badge(modifier, color, content)`
   - `Tag(modifier, color, label, onRemove?)`
   - `Pill(modifier, color)` (Ableton color tag display)
4. Build `app-gallery` that renders every primitive in light + dark.
5. Run `./gradlew :app-gallery:run` locally; capture screenshots; attach to PR.
6. Commit.

**Acceptance:** All primitives stateless (state hoisted); each works in light + dark; gallery launches.

---

### PR-12: `feature-projects` — list state-holder + screen

**Goal:** First feature module. Project list with search-as-you-type. Establishes the per-feature pattern.

**Files:**
- Create: `shared/feature-projects/src/commonMain/kotlin/com/sketchbook/featureprojects/ProjectListStateHolder.kt`
- Create: `shared/feature-projects/src/commonMain/kotlin/com/sketchbook/featureprojects/ProjectListScreen.kt`
- Create: `shared/feature-projects/src/commonMain/kotlin/com/sketchbook/featureprojects/ProjectListComponent.kt` — DI binding via Metro.
- Tests: `shared/feature-projects/src/commonTest/...`

**Tasks:**

1. TDD `ProjectListStateHolder`: feeds intents (`Search`, `Open`), observes a fake `ProjectRepository`, asserts `state.rows` updates correctly.
2. TDD effects: `Open(id)` emits `Effect.Navigate(id)` once.
3. Implement Compose screen using `ui-shared` primitives:
   - Top: `TextField` for query.
   - Body: `LazyColumn` of `RowItem`s. `key = { it.id.value }`. ~1.6k rows works fine.
   - Empty state: `EmptyState` with hint.
4. Add to `app-gallery` for visual preview.
5. Commit.

**Acceptance:** Screen renders, scrolls smoothly with 1,628 rows, search filters live.

---

### PR-13: `feature-project-detail` — single project pane

**Goal:** Project detail with tabs: overview / tracks / samples / plugins / history.

**Files:** `shared/feature-project-detail/...`

**Tasks:**

1. TDD `ProjectDetailStateHolder` — observe single project + its history.
2. Build tabbed screen using `ui-shared`. Reuse `RowItem` for samples + plugins.
3. Wire "Open in Live" via desktop `Desktop.open()` (added in PR-18 but stubbed here).
4. Commit.

**Acceptance:** Detail screen for any project; history tab populated when snapshots exist.

---

### PR-14: `feature-timeline` — snapshot history + rewind

**Goal:** The killer feature. Timeline view of `named` + `branch` snapshots; "Show all saves" reveals `auto`. Rewind action materializes a project at a given rev.

**Files:** `shared/feature-timeline/...`

**Tasks:**

1. TDD timeline state-holder: fake history, assert filter/order correctness.
2. Build timeline UI with vertical day-grouped list. Each row: rev, kind, label, host, file_count, total_bytes.
3. Implement rewind action: confirm dialog → `repository.materializeAt(rev)` → progress UI.
4. Visual: branch snapshots get a different visual treatment (slight indent + auto-fork label).
5. Commit.

**Acceptance:** Can rewind any project to any rev; rewound copy opens in Ableton without errors.

---

### PR-15: `feature-proposals` — AI proposals queue

**Goal:** Port v0.1 proposals UI. Approve/reject batches.

**Tasks:** Standard MVI-shaped state-holder + Compose screen. Reuse v0.1 logic via `repository`.

---

### PR-16: `feature-needs-attention` — repair surface

**Goal:** Port the broken-projects-repair UI.

**Tasks:** State-holder + screen that surfaces missing-samples + macpath-repair findings (already in repository as `findings_summary`).

---

### PR-17: `feature-settings` — library roots, cloud config, self-contained toggles

**Goal:** UI to configure library root, cloud credentials (B2 keys), per-project self-contained toggle.

**Tasks:** State-holder + screen. B2 credentials stored via OS keychain (JVM: `java.util.prefs.Preferences` is fine for v1; rotate to OS keychain in v1.1).

---

## Phase 4 — App shell

### PR-18: `app-desktop` — Compose Desktop entry, Metro graph, NavStack root, OS integration

**Goal:** Wire everything. Single executable produces `.dmg` (Mac) and `.msi` (Windows).

**Files:**
- Create: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/Main.kt`
- Create: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt` (Metro `@DependencyGraph`)
- Create: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootStateHolder.kt` (NavStack)
- Create: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/Os.kt` (file dialogs, "open in Live", system tray)

**Tasks:**

1. Wire Metro `@DependencyGraph(AppScope::class)` aggregating all modules' contributions.
2. Implement `RootStateHolder` with `NavStack` + per-screen `ScreenScope`.
3. Build root composable that switches on `stack.value.last()`.
4. Wire OS integration:
   - Native menu: File → Scan Now, Library Settings, Quit.
   - System tray icon with current scan status + proposal count badge.
   - File dialogs for "Add Library Root."
   - `Desktop.getDesktop().open(als)` for "Open in Live."
5. Ship `compose.desktop { application { mainClass = "..."; nativeDistributions { targetFormats(Dmg, Msi) } } }` config.
6. `./gradlew :app-desktop:packageDmg` + `:packageMsi` produce installable artifacts.
7. Manual visual run + screenshots in PR body.

**Acceptance:** App launches, all screens reachable, scan works against test library.

---

## Phase 5 — Polish & cutover

### PR-19: First-run migration

**Goal:** When the new app launches against an existing v0.1 catalog.db: assign UUIDs, write `.audio-id` sidecars, populate `sync_state`.

**Tasks:** Migration runner with progress UI; idempotent; tested on a copy of the production catalog.

### PR-20: Self-contained project toggle wiring

**Goal:** UI toggle in feature-settings + sync engine respects it (skip dedup, use `blobs-private/<uuid>/...`).

### PR-21: Conflict UI

**Goal:** Lease-lock badges (free/ours/other/stale), auto-fork visualization in timeline, "force-take lock" button with confirmation.

### PR-22: Materialization UI / cache management

**Goal:** Download progress for "open project at rev" when blobs aren't cached. Cache GC settings (size limit, LRU).

### PR-23: Parity validation

**Goal:** Run both Python and Kotlin against a frozen copy of the catalog and assert outputs match (project list, search results, journal entries for the same actions, MCP tool responses).

**Tasks:**
1. Write a parity harness in `tools/parity/` (Python script) that drives both backends.
2. Compare JSON outputs byte-by-byte for: scan results, FTS search, journal write+undo round-trip, MCP `search_projects` + `get_project`.
3. Iterate fixes until parity is 100% on the test library.
4. Document residual divergences (if any) in `docs/parity.md`.

### PR-24: Retire Python `packages/`

**Goal:** Single PR that deletes `packages/`, `web/`, `src-tauri/`, removes Python from CI, updates README to point at the Kotlin entry point.

**Tasks:**
1. Delete dead trees.
2. Remove Python jobs from `.github/workflows/ci.yml`.
3. Update `README.md`: "v1.0 desktop app — `./gradlew :app-desktop:run`."
4. Tag the last Python commit as `python-final` for archaeological access.
5. Commit + merge.

---

## Risks & mitigations

- **Compose Desktop perf with 1,628 rows.** `LazyColumn` virtualizes fine; mitigate with `key = { it.id.value }` and `contentType`. If rendering stutters, profile with the Compose tracer.
- **B2 multipart upload edge cases.** Mitigate by smoke-testing against a real bucket in CI nightly with a dedicated test prefix.
- **`.audio-id` sidecar conflict.** If a project already has an unrelated `.audio-id` file (it won't, but defensively): refuse to overwrite, emit a `NeedsAttention` finding.
- **iOS/Android path lurking.** Resist the urge to write `expect/actual` for `Watcher`, `Materializer`, `BlobHasher` until v1.1 actually needs them. v1 is JVM-only — `jvmMain` is fine.
- **MCP server topology.** If shared catalog.db locking becomes an issue (separate JVM process holding open writer locks while desktop app also writes), switch to read-only mode in MCP and route writes via desktop-app RPC. Defer until measured.

## What this plan deliberately does NOT include

- Mobile app (v1.1).
- Multi-user coordinator + ACLs (v1.2).
- Audio analysis sidecar (v1.3).
- AbletonOSC, templates, plugin/preset/sample library curators.
- Code signing for distribution. Personal-use unsigned builds for v1.

## Hand-off

After this plan is approved and PR-0 lands, choose execution mode:

1. **Subagent-Driven (this session)** — fresh subagent per PR, code review between PRs, fast iteration.
2. **Parallel Session (separate)** — open new session with `superpowers:executing-plans`; batch execution with checkpoints (per the no-batch-checkpoint feedback memory, "checkpoint" here means PR-level only — do not pause between tasks within a PR).
