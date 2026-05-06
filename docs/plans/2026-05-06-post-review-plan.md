# Sketchbook Post-Review Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task.

**Goal:** Address every finding from the 2026-05-06 end-to-end review (feature-completeness, performance, architecture, UI/UX) and ship 1.0, then layer on five high-leverage features researched from competing tools.

**Architecture:** Twelve sequential PRs (`PR-L` through `PR-V`). PRs L–O are the **1.0 release gate** — after PR-O ships, `git tag v1.0.0` is honest. PRs P–V are post-1.0 feature additions; each is independently shippable. Every PR is one focused unit (per `feedback_one_pr_at_a_time`). No batch checkpoints during execution (per `feedback_no_batch_checkpoints`); drive through tasks within a PR, browser-verify visually as you go.

**Tech Stack:** Kotlin 2.3 + Compose Multiplatform 1.11, SQLDelight 2.3 + FTS5, Metro DI, Ktor 3.2 CIO, kotlinx.io, kotlinx.serialization, kotlin.test + Kotest + Turbine. **No new dependencies anywhere in this plan.**

**Predecessors:**
- [`2026-05-06-feature-complete-design.md`](2026-05-06-feature-complete-design.md) — what 1.0 promised
- [`2026-05-06-1.0-release-readiness-plan.md`](2026-05-06-1.0-release-readiness-plan.md) — release engineering (PRs H–K)
- [`2026-05-05-sync-versioning-design.md`](2026-05-05-sync-versioning-design.md) — sync architecture

**PR sequence:**
| PR  | Theme                          | Severity tier   | Ships 1.0? |
|-----|--------------------------------|-----------------|------------|
| L   | Critical wiring fixes          | critical        | yes        |
| M   | Scale prep (perf criticals)    | critical        | yes        |
| N   | Stationery consistency (UI)    | important       | yes        |
| O   | Architecture cleanup + docs    | important       | yes — tag v1.0 here |
| P   | Arrangement thumbnail strip    | feature (top 1) | post       |
| Q   | Idea fingerprint clusters      | feature (top 2) | post       |
| R   | Stage chip auto-inference      | feature (top 3) | post       |
| S   | Sample family tree             | feature (B)     | post       |
| T   | Plugin risk forecast           | feature (B)     | post       |
| U   | Snapshot diff view             | feature (B)     | post       |
| V   | Bounce aggregator + player     | feature (B)     | post       |

---

## PR-L: Critical wiring fixes (1.0 blocker)

**Closes:** C-FC-1 (Watcher unwired), C-FC-3 (in-memory journal in production), C-UX-1 (schema migration miss), C-UX-2 (Timeline unreachable), C-P-1 (blob OOM), C-UX-3 (single-click apply).

### Task L1: ~~Pin schema-migration regression with a failing test~~ — **DONE in #97 (95c0c7d)**

Skip this task. Commit `95c0c7d` ("fix(catalog): add v2 to v3 migration for repair_acks + proposal_acks") shipped the migration via `2.sqm` + an extended pre-tracking-DB version probe in `CatalogDb.kt`, with a test in `CatalogDbTest.kt`. C-UX-1 closed.

### Task L1 (original content kept below for reference)

**Files:**
- Create: `shared/catalog/src/jvmTest/kotlin/com/sketchbook/catalog/SchemaMigrationTest.kt`

**Step 1: Write failing test**

```kotlin
class SchemaMigrationTest {
    @Test
    fun `existing db gets proposal_acks and repair_acks tables on bring-up`() {
        val tmp = createTempFile("catalog", ".db")
        // Simulate an old DB that pre-dates these tables
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$tmp").use { c ->
            c.createStatement().execute("CREATE TABLE projects (id INTEGER PRIMARY KEY, path TEXT)")
        }
        val db = CatalogDb.openOnDisk(tmp)
        assertNotNull(db.proposalAcksQueries.selectAll().executeAsList())
        assertNotNull(db.repairAcksQueries.selectAll().executeAsList())
    }
}
```

**Step 2:** Run `./gradlew :shared:catalog:jvmTest --tests SchemaMigrationTest`. Expect: SQLITE_ERROR no such table.

**Step 3: Fix `CatalogDb.openOnDisk`**

Modify `shared/catalog/src/jvmMain/kotlin/com/sketchbook/catalog/CatalogDb.kt:105` so the "idempotent schema bring-up" actually runs `Schema.migrate(driver, oldVersion, newVersion)` between the existing user_version and the current schema. Read SQLDelight's generated `Catalog.Schema.version` and call migrate when it's lower than the on-disk `PRAGMA user_version`. Set `PRAGMA user_version` after success.

**Step 4:** Rerun test. Expect PASS.

**Step 5: Commit**
```
git add shared/catalog/src/{jvmMain,jvmTest}
git commit -m "fix(catalog): apply pending schema migrations on existing DBs"
```

### Task L2: Add `journal_entries` SQL table

**Files:**
- Modify: `shared/catalog/src/commonMain/sqldelight/com/sketchbook/catalog/db/Catalog.sq`

**Step 1:** Add at the end of the file (before any `selectAll` queries):

```sql
CREATE TABLE journal_entries (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    occurred_at  INTEGER NOT NULL,
    actor        TEXT    NOT NULL,
    action_type  TEXT    NOT NULL,
    project_id   INTEGER,
    payload_json TEXT    NOT NULL DEFAULT '{}'
);
CREATE INDEX journal_entries_project_idx ON journal_entries(project_id, occurred_at DESC);
CREATE INDEX journal_entries_recency_idx ON journal_entries(occurred_at DESC);

insertJournalEntry:
INSERT INTO journal_entries(occurred_at, actor, action_type, project_id, payload_json)
VALUES (?, ?, ?, ?, ?);

selectJournalRecent:
SELECT * FROM journal_entries ORDER BY occurred_at DESC LIMIT :limit;

selectJournalForProject:
SELECT * FROM journal_entries WHERE project_id = ? ORDER BY occurred_at DESC LIMIT :limit;

countJournal:
SELECT COUNT(*) FROM journal_entries;
```

**Step 2:** Run `./gradlew :shared:catalog:generateSqlDelightInterface`. Expect generated `JournalEntriesQueries`.

**Step 3:** Bump `Schema.version` in the SQLDelight extension block in `shared/catalog/build.gradle.kts` (find the `sqldelight { databases { create(...) {` block and bump `migrationOutputDirectory`/version by one).

**Step 4:** Add a migration file `shared/catalog/src/commonMain/sqldelight/migrations/N.sqm` (where N is the new version) containing only the `CREATE TABLE` + indices above (without the queries — migrations don't carry queries).

**Step 5:** Run `./gradlew :shared:catalog:verifySqlDelightMigrations`. Expect PASS.

**Step 6: Commit**
```
git commit -am "feat(catalog): add journal_entries table + migration"
```

### Task L3: Implement `SqlJournalRepository`

**Files:**
- Create: `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/impl/SqlJournalRepository.kt`
- Create: `shared/repository/src/commonTest/kotlin/com/sketchbook/repo/impl/SqlJournalRepositoryTest.kt`

**Step 1: Write failing test** using an in-memory SQLDelight driver factory the existing tests already use (search for `inMemoryDriver` in `:shared:repository`):

```kotlin
class SqlJournalRepositoryTest {
    @Test
    fun `append then observe returns entries newest-first`() = runTest {
        val (db, clock) = inMemoryCatalog()
        val repo = SqlJournalRepository(db, clock, ioDispatcher = StandardTestDispatcher(testScheduler))
        repo.append(JournalEntry(actor = "user", actionType = "Archive", projectId = ProjectId(7), payloadJson = "{}"))
        clock.advance(Duration.parse("1s"))
        repo.append(JournalEntry(actor = "user", actionType = "SetTags", projectId = ProjectId(7), payloadJson = "{}"))
        val entries = repo.observe(limit = 50).first()
        assertEquals(2, entries.size)
        assertEquals("SetTags", entries[0].actionType)
    }

    @Test
    fun `survives round trip across repo instances when on-disk`() { /* uses createTempFile catalog */ }
}
```

**Step 2:** Run test. Expect FAIL (class doesn't exist).

**Step 3: Implement** using the same `mutate { }` style as `SqlProjectRepository`:

```kotlin
class SqlJournalRepository(
    private val db: Catalog,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher,
) : JournalRepository {

    private val ticks = MutableStateFlow(0L)

    override suspend fun append(entry: JournalEntry) = withContext(ioDispatcher) {
        db.transaction {
            db.journalEntriesQueries.insertJournalEntry(
                occurred_at = clock.now().toEpochMilliseconds(),
                actor = entry.actor,
                action_type = entry.actionType,
                project_id = entry.projectId?.value,
                payload_json = entry.payloadJson,
            )
        }
        ticks.update { it + 1 }
    }

    override fun observe(limit: Int): Flow<List<JournalEntry>> =
        ticks.map { db.journalEntriesQueries.selectJournalRecent(limit.toLong()).executeAsList().map(::toDomain) }

    override fun observeForProject(projectId: ProjectId, limit: Int): Flow<List<JournalEntry>> =
        ticks.map {
            db.journalEntriesQueries.selectJournalForProject(projectId.value, limit.toLong())
                .executeAsList().map(::toDomain)
        }

    private fun toDomain(row: Journal_entries) = JournalEntry(
        id = JournalEntryId(row.id),
        occurredAt = Instant.fromEpochMilliseconds(row.occurred_at),
        actor = row.actor,
        actionType = row.action_type,
        projectId = row.project_id?.let(::ProjectId),
        payloadJson = row.payload_json,
    )
}
```

**Step 4:** Run test. Expect PASS.

**Step 5: Commit**
```
git commit -am "feat(repository): SqlJournalRepository backed by catalog DB"
```

### Task L4: Swap `InMemoryJournalRepository` for SQL impl in graphs

**Files:**
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt:117`
- Modify: `app-mcp/src/main/kotlin/com/sketchbook/mcp/app/Main.kt:37`

**Step 1:** In both files replace `InMemoryJournalRepository()` with `SqlJournalRepository(catalog, clock, ioDispatcher)`. Both processes share the same on-disk catalog so MCP edits will appear in the desktop journal viewer.

**Step 2:** Update the class doc on `InMemoryJournalRepository.kt:16` (or delete the class entirely if no test fakes need it — keep if `tests/integration` uses it).

**Step 3: Manual verification**
- `./gradlew :app-desktop:run`
- Archive a project. Quit. Relaunch. Open Journal. Entry persists. ✓

**Step 4: Commit**
```
git commit -am "feat(app): wire SqlJournalRepository in desktop + MCP graphs"
```

### Task L5: Wire the Ableton-save Watcher

**Files:**
- Create: `shared/sync-io/src/jvmTest/kotlin/com/sketchbook/syncio/WatcherWiringTest.kt` (integration shape)
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt`

**Step 1: Write failing test** that points a `Watcher` at a tmp dir matching the `Projects/*/Backup/` shape, touches a `.als` file, and asserts the SyncStateStore receives a `markDirty(uuid)` call within 5 s:

```kotlin
@Test
fun `Watcher debounce + markDirty fires on Backup save`() = runTest {
    val root = createTempDirectory("projects")
    val backup = root.resolve("MyTrack Project/Backup").createDirectories()
    val store = FakeSyncStateStore()
    val watcher = Watcher(root, store, dispatcher = ioDispatcher, debounce = 250.ms)
    val job = launch { watcher.run() }
    backup.resolve("MyTrack [2026-05-06 14-22-01].als").writeBytes(byteArrayOf(0x1f, 0x8b.toByte()))
    advanceTimeBy(1.s)
    assertContains(store.dirtied, "MyTrack Project")
    job.cancel()
}
```

**Step 2:** Run test. Expect FAIL — Watcher exists but no caller invokes `markDirty` correctly.

**Step 3:** Inspect `Watcher.kt:38`. If it already emits a `Saved(projectDir)` flow, write a thin `WatcherJob` in `:shared:sync` that resolves project_dir → uuid via `SyncStateStore.identityFor(...)` and calls `markDirty`. If `Watcher` has no flow, finish its API in this task — but resist scope creep: emit a `Flow<Path>` of project-root paths, no extras.

**Step 4:** In `DesktopAppGraph` add to the `init` block (after `pullPoller` launch, around line ~270):

```kotlin
appScope.launch {
    val watcher = Watcher(libraryRoots.projectsRoot, ioDispatcher = ioDispatcher)
    watcher.savedProjects().collect { projectDir ->
        val identity = syncStateStore.identityFor(projectDir)
        syncStateStore.markDirty(identity.uuid)
    }
}
```

(Adjust to the actual API surface you finalize in step 3.)

**Step 5:** Run integration test from step 1. Expect PASS.

**Step 6: Manual verification**
- `./gradlew :app-desktop:run`
- Save a real project in Ableton. Within ~30 s the sidebar sync caption should tick and a row should appear in `sync_state` with `dirty=1`. Confirm with `sqlite3 ~/.sketchbook/catalog.db "select uuid, dirty from sync_state where dirty=1"`.

**Step 7: Commit**
```
git commit -am "feat(sync): wire Watcher → SyncStateStore.markDirty in DesktopAppGraph"
```

### Task L6: Stream `JvmBlobCache.fetch` instead of `readByteArray`

**Files:**
- Modify: `shared/sync/src/jvmMain/kotlin/com/sketchbook/sync/JvmBlobCache.kt:59`
- Create test: `shared/sync/src/jvmTest/kotlin/com/sketchbook/sync/JvmBlobCacheStreamingTest.kt`

**Step 1: Write failing test** with a 200 MB synthetic blob source, assert peak heap delta during `fetch` is below 16 MB (use `Runtime.getRuntime().totalMemory() - freeMemory()` snapshots before/after):

```kotlin
@Test
fun `fetch does not buffer entire blob in heap`() {
    val blobBytes = ByteArray(200 * 1024 * 1024) { (it % 251).toByte() }
    val backend = FakeCloudBackend(blobBytes)
    val cache = JvmBlobCache(root = createTempDirectory(), backend = backend, ...)
    val before = peakHeap()
    runBlocking { cache.getOrFetch(Hash.of(blobBytes)) }
    val peak = peakHeap()
    assertTrue(peak - before < 16 * 1024 * 1024, "peak heap delta = ${(peak-before)/1_000_000} MB")
}
```

**Step 2:** Run test. Expect FAIL (peak heap > 200 MB).

**Step 3: Replace** the `val bytes = source.buffered().readByteArray()` block with a buffered loop that reads through a 64 KB array into the temp file's `OutputStream`:

```kotlin
val tempPath = blobPath.parent.resolve("${blobPath.fileName}.tmp")
var totalBytes = 0L
Files.newOutputStream(tempPath, StandardOpenOption.CREATE_NEW).use { out ->
    val buf = ByteArray(64 * 1024)
    source.buffered().use { src ->
        while (true) {
            val n = src.readAtMostTo(buf, 0, buf.size)
            if (n <= 0) break
            out.write(buf, 0, n)
            totalBytes += n
        }
    }
}
Files.move(tempPath, blobPath, StandardCopyOption.ATOMIC_MOVE)
return BlobEntry(path = blobPath, size = totalBytes)
```

**Step 4:** Run test. Expect PASS.

**Step 5: Commit**
```
git commit -am "perf(sync): stream blob fetch to disk; cap heap at 64 KB buffer"
```

### Task L7: ~~Confirm step on Needs-Attention "Pick"~~ — **DEFERRED**

Skip. The parallel design `2026-05-06-proposals-needs-journal-ux-design.md` redesigns the entire Needs Attention screen including bulk apply, snackbar undo, and append-only compensating-entry semantics. My single-row 5s undo would conflict with that flow. C-UX-3 closes via that workstream.

### Task L7 (original content kept below for reference)

**Files:**
- Modify: `shared/feature-needs-attention/src/commonMain/kotlin/com/sketchbook/featureneedsattention/NeedsAttentionScreen.kt:196-208`

**Step 1:** The user's memory says inline-only (no modal redesign). Add a 5-second undo affordance: clicking "Pick" dispatches `ApplyMatch` *and* surfaces an inline pill on the same row reading "Picked. Undo (5s)" using existing `accentSecondary` token. After 5 s the pill fades; before then, click "Undo" dispatches `UndoMatch(findingId)`.

**Step 2:** Add `UndoMatch(findingId: FindingId)` to the holder's intent sealed interface. The corresponding repository call is a delete on the `repair_acks` row + restore `project_samples.is_missing = 1` (and reverse the journal entry — use a compensating `RepairUndone` action, don't mutate history).

**Step 3:** Test in `NeedsAttentionStateHolderTest`:

```kotlin
@Test
fun `Pick then Undo within window restores missing finding`() = runTest {
    holder.dispatch(Intent.ApplyMatch(findingId, candidate))
    assertEquals(0, fakeRepo.findings.count { it.id == findingId })
    holder.dispatch(Intent.UndoMatch(findingId))
    assertEquals(1, fakeRepo.findings.count { it.id == findingId })
}
```

**Step 4: Commit**
```
git commit -am "feat(needs-attention): 5s undo on candidate Pick"
```

### Task L8: Add Timeline route entry

**Files:**
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt` near the side-panel `DetailVersionsTab` (around `:606`)

**Step 1:** Inside the panel's Versions tab, add a small chip (per `feedback_layer_dont_redesign`) at the bottom: "View full timeline →" using the existing `ButtonVariant.Ghost` style. On click: `backStack.add(Screen.Timeline(projectId))`.

**Step 2:** Manual verification — open a project's panel, click "View full timeline", assert TimelineScreen renders with the rewind dialog.

**Step 3: Commit**
```
git commit -am "feat(detail): chip to open full Timeline from Versions tab"
```

### Task L9: PR-L wrap-up

**Step 1:** Run all tests: `./gradlew check`. Expect PASS.

**Step 2:** Verify the v1.0 acceptance criteria from `2026-05-06-feature-complete-design.md`:
1. ✓ Quit/relaunch — settings + journal persist
2. ✓ Approve archive proposal — project disappears, journal entry appears
3. ✓ Plug UserSamples root + scan — auto-match candidate clears
4. ✓ Push then "Rewind to rev N" from Timeline — works (now reachable)
5. ✓ Two machines pointed at same bucket — pull poller + auto-materialize works
6. ✓ Detail panel shows real data; journal lists every write; Timeline shows new-vs-reused

**Step 3:** Push branch and open PR titled `PR-L: critical wiring fixes (watcher, sql journal, schema migration, blob streaming)`.

---

## PR-M: Scale prep — performance criticals

**Closes:** C-P-2 (one poller per project), C-P-3 (`SyncStateStore.observeAll` re-scans), I-P-4 (state-holder re-derive), I-P-5 (correlated COUNT), I-P-6 (N+1 in repair findings).

### Task M1: Single bulk-LIST PullPoller

**Files:**
- Modify: `shared/sync/src/commonMain/kotlin/com/sketchbook/sync/PullPoller.kt`
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt:255-266`

**Step 1: Write failing test** in `:shared:sync` commonTest using `FakeCloudBackend`:

```kotlin
@Test
fun `poller does one LIST per tick regardless of project count`() = runTest {
    val backend = FakeCloudBackend(/* 500 manifests under userId/manifests/ */)
    val store = FakeSyncStateStore(uuids = (1..500).map { Uuid.random() })
    val poller = PullPoller(backend, store, snapshotRepo, period = 30.s, prefix = "default/manifests/")
    val job = launch { poller.run() }
    advanceTimeBy(31.s)
    assertEquals(1, backend.listCalls)
    job.cancel()
}
```

**Step 2:** Run test. Expect FAIL — current impl is one poll per uuid.

**Step 3: Refactor** so `PullPoller.run()` does:

```kotlin
suspend fun run() {
    while (currentCoroutineContext().isActive) {
        val manifests = backend.list(prefix = "$tenantPrefix/manifests/")
        val byUuid = manifests.groupBy { extractUuid(it.key) }
        val states = store.allStates()
        for (state in states) {
            val cloudHead = byUuid[state.uuid]?.maxByOrNull { it.lastModified } ?: continue
            if (cloudHead.rev > state.localRev) snapshotRepo.recordSnapshot(state.uuid, cloudHead.toSnapshot())
        }
        delay(period)
    }
}
```

**Step 4:** Update `DesktopAppGraph`: replace the per-uuid coroutine launch loop with a single `appScope.launch { pullPoller.run() }`.

**Step 5:** Run test from step 1 + existing PullPoller tests. Expect PASS.

**Step 6: Commit**
```
git commit -am "perf(sync): single bulk-LIST poller; one coroutine for all projects"
```

### Task M2: SQLDelight Flow on `sync_state`

**Files:**
- Modify: `shared/catalog/src/jvmMain/kotlin/com/sketchbook/catalog/SyncStateStore.kt:154`

**Step 1:** Replace the `version` `MutableStateFlow` + `selectAllSyncStates().executeAsList()` pattern with the SQLDelight `asFlow().mapToList(ioDispatcher)` pattern that the rest of the catalog already uses. Search for `mapToList(` in the project to mirror the exact import + style.

**Step 2:** Verify downstream callers don't break. Specifically `SwappableSyncQueue.drainOnce` and `RootContent.kt` sync-state observers should still get a deduped stream.

**Step 3:** Performance smoke: create a `SyncStateStorePerfTest` that inserts 1,628 rows, calls `markDirty(uuid)` 100 times, and asserts `observeAll().first()` returns within 100 ms after the last write. (Generous bound — Skia + SQLDelight invalidation is fast.)

**Step 4: Commit**
```
git commit -am "perf(catalog): use SQLDelight flow for sync_state observeAll"
```

### Task M3: Memoize `ProjectListStateHolder` derived state

**Files:**
- Modify: `shared/feature-projects/src/commonMain/kotlin/com/sketchbook/featureprojects/ProjectListStateHolder.kt:59-104`

**Step 1:** Split the giant `combine` into:

```kotlin
private val derived: Flow<DerivedProjectModel> = combine(rowsFlow, archivedRowsFlow) { rows, archived ->
    DerivedProjectModel(
        groups = deriveProjectGroups(rows),
        archivedGroups = deriveProjectGroups(archived),
        buckets = bucketize(rows),
        archivedBuckets = bucketize(archived),
    )
}.distinctUntilChanged()

val state: StateFlow<ProjectListUiState> = combine(derived, query, seed, zoomShelf, /* cheap UI flows */) { d, q, s, z ->
    /* compose UI state from derived + UI flags only */
}.stateIn(scope, SharingStarted.Eagerly, initial)
```

**Step 2:** Add `data class DerivedProjectModel` next to the holder.

**Step 3: Test** that typing a query doesn't re-run `deriveProjectGroups`:

```kotlin
@Test
fun `query change does not re-derive groups`() = runTest {
    val derivations = AtomicInteger(0)
    val holder = ProjectListStateHolder(repo = SpyingRepo(onGroup = { derivations.incrementAndGet() }), ...)
    holder.dispatch(Intent.Search("a"))
    holder.dispatch(Intent.Search("ab"))
    holder.dispatch(Intent.Search("abc"))
    holder.state.first { it.query == "abc" }
    assertEquals(1, derivations.get())  // only initial derivation
}
```

**Step 4: Commit**
```
git commit -am "perf(projects): memoize derived groups; only re-run on row changes"
```

### Task M4: Replace correlated COUNT with LEFT JOIN

**Files:**
- Modify: `shared/catalog/src/commonMain/sqldelight/com/sketchbook/catalog/db/Catalog.sq:182-188`

**Step 1:** Rewrite `selectAllProjectsWithMissing`:

```sql
selectAllProjectsWithMissing:
SELECT p.*, COALESCE(m.missing_count, 0) AS missing_samples
FROM projects p
LEFT JOIN (
    SELECT project_id, COUNT(*) AS missing_count
    FROM project_samples
    WHERE is_missing = 1
    GROUP BY project_id
) m ON m.project_id = p.id;
```

**Step 2:** Run `./gradlew :shared:catalog:generateSqlDelightInterface`. If column types changed, update mappers in `:shared:repository`.

**Step 3:** Add an `EXPLAIN QUERY PLAN` smoke test that asserts no `CORRELATED SUBQUERY` substring in the plan (SQLite explainer output is text).

**Step 4: Commit**
```
git commit -am "perf(catalog): LEFT JOIN replaces correlated COUNT in selectAllProjectsWithMissing"
```

### Task M5: Fold N+1 in `SqlRepairRepository.observeFindings`

**Files:**
- Modify: `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/impl/SqlRepairRepository.kt:80-86`
- Modify: `shared/catalog/src/commonMain/sqldelight/com/sketchbook/catalog/db/Catalog.sq` (add new query)

**Step 1:** Add SQL:

```sql
selectMissingSamplesWithCandidates:
SELECT
    ps.project_id, ps.path AS missing_path, ps.size_bytes AS missing_size,
    s.path AS candidate_path, s.size_bytes AS candidate_size,
    CASE WHEN s.filename = substr(ps.path, ifnull(rindex(ps.path, '/'), 0) + 1) AND s.size_bytes = ps.size_bytes
         THEN 'high' ELSE 'medium' END AS confidence
FROM project_samples ps
LEFT JOIN samples s ON s.filename = ?  -- filename of ps.path computed in app
WHERE ps.is_missing = 1
LIMIT 5;
```

(SQLite has no built-in `rindex`. Either compute filename in app code by passing it as a bound param per row, OR — better — denormalize `filename` onto `project_samples` so the join is direct. Pick denormalize: it's a 1-line schema migration and saves the per-row CTE.)

**Step 2:** Migration `(N+1).sqm`: `ALTER TABLE project_samples ADD COLUMN filename TEXT NOT NULL DEFAULT '';` then a one-shot UPDATE populating from path on bring-up.

**Step 3:** Refactor `observeFindings` to one query, group results in app code by `(project_id, missing_path)`.

**Step 4: Test** that 100 missing-sample findings produce exactly 1 query (use a counting driver wrapper).

**Step 5: Commit**
```
git commit -am "perf(repair): single-query findings + denormalized filename"
```

### Task M6: PR-M wrap-up

**Step 1:** Synthetic-load smoke: create a fixture of 1,628 projects (cheap — just rows, no real `.als`) and benchmark `ProjectListStateHolder.state.first()` cold start. Target: <500 ms on the user's machine.

**Step 2:** Push and open PR `PR-M: scale prep (single poller, flow observers, query rewrites)`.

---

## PR-N: Stationery consistency (UI drift)

**Closes:** I-UX-4 (PageHeader missing on 5 screens), I-UX-5 (two ProjectDetail UIs), I-UX-6/7/8 (color drift), I-UX-9 (Sync from Home).

### Task N1: PageHeader on every primary screen

**Note:** Proposals, NeedsAttention, and Journal are owned by the parallel `2026-05-06-proposals-needs-journal-ux-design.md` workstream — that redesign uses `PageHeader` as part of its rebuild. **Do NOT touch those 3 screens here.** Only Timeline + ProjectDetail belong to PR-N.

**Files (one per screen, single commit per screen):**
- ~~`shared/feature-proposals/.../ProposalsScreen.kt:40`~~ — owned by parallel workstream
- ~~`shared/feature-needs-attention/.../NeedsAttentionScreen.kt:44`~~ — owned by parallel workstream
- ~~`shared/feature-journal/.../JournalScreen.kt:39`~~ — owned by parallel workstream
- `shared/feature-timeline/.../TimelineScreen.kt:98`
- `shared/feature-project-detail/.../ProjectDetailScreen.kt:66`

**Step 1 (per screen):** Replace bare `Text("...", style = AppTheme.typography.title)` with `PageHeader(title = "...", subtitle = ...)` matching the `ProjectListScreen.kt:128` usage exactly.

**Step 2:** Manual visual check — every screen now has the red rule-margin hairline.

**Step 3: Commit per screen** (5 commits — keeps diffs reviewable).

### Task N2: Consolidate ProjectDetail UIs

**Files:**
- Delete: `shared/feature-project-detail/src/commonMain/kotlin/com/sketchbook/featuredetail/ProjectDetailScreen.kt`
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt` to make `Screen.ProjectDetail(id)` route push the panel into a full-window `Surface` rendering the same `DetailPanelContent`.

**Step 1:** Note that `DetailPanelContent` is currently a side-panel composable taking width as a param. Hoist it to take a `Modifier` so it can render full-screen.

**Step 2:** Update `Screen.ProjectDetail` rendering in `RootContent.kt` (search the `NavDisplay` content lambda for `is Screen.ProjectDetail`) to call the hoisted `DetailPanelContent` instead of the deleted screen.

**Step 3:** Update `JournalStateHolder.Effect.NavigateToProject` flow so it lands on the same composition. This also closes D-2 from the review.

**Step 4: Manual verification** — clicking a row, vs. clicking a journal entry, vs. deep-linking via `Screen.ProjectDetail` should all show the same 6-tab UI with inline rename + tags + Versions.

**Step 5: Commit**
```
git commit -am "refactor(detail): one ProjectDetail composition for panel + full-screen"
```

### Task N3: Single ActivityBar accent

**Files:**
- Modify: `shared/ui-shared/src/commonMain/kotlin/com/sketchbook/uishared/components/ActivityBar.kt:62-65`

**Step 1:** Both scan and sync use `accentAction`. Differentiate via cadence: scan = 1400 ms sweep, sync = 2000 ms sweep, both = `accentAction`. Already partly there (`:51`).

**Step 2:** Delete the `pinBlue` reference from this file. Grep for other `pinBlue` usages and re-evaluate each (likely OK — pin is a different semantic).

**Step 3: Commit**
```
git commit -am "ui(activity-bar): single accent for sync + scan; differentiate by cadence"
```

### Task N4: SongStrip warning hue match

**Files:**
- Modify: `shared/ui-shared/src/commonMain/kotlin/com/sketchbook/uishared/components/SongStrip.kt:88,153`

**Step 1:** Pick one severity color per finding state. Missing-sample = `accentDanger` for both glyph and border. Effort/dedup nudges = `accentWarning` for both. Don't mix.

**Step 2: Commit**
```
git commit -am "ui(songstrip): unify glyph + border hue per finding severity"
```

### Task N5: Proposal status hue

**Files:**
- Modify: `shared/feature-proposals/src/commonMain/kotlin/com/sketchbook/featureproposals/ProposalsScreen.kt:103`

**Step 1:** Replace `accentAction` for "Rejected" status with `inkMuted`. Approve and Reject must not share a hue.

**Step 2:** Also at `:137` upgrade NeedsAttention "Apply" button from `ButtonVariant.Ghost` to `ButtonVariant.Primary`.

**Step 3: Commit**
```
git commit -am "ui(proposals,needs-attention): correct emphasis tokens for status + apply"
```

### Task N6: Sync verb on Home

**Files:**
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt` near `:225`

**Step 1:** Add a sidebar "Sync" entry below "Needs Attention". On click: invoke `syncQueue.drainOnce()` (or whatever the manual-trigger entry-point is) and route to a `Screen.SyncDashboard` (or surface in the existing detail panel). Per `feedback_layer_dont_redesign`, prefer "click cycles through dirty projects in the existing UI" over a new dashboard screen.

**Step 2:** Update the sidebar caption to count `dirty=1` rows so the user has a glance-able number.

**Step 3: Manual verification** — modify a project, click sidebar "Sync", a push begins. ✓ Acceptance criterion "all four verbs reachable from Home in 1 click" now passes.

**Step 4: Commit**
```
git commit -am "ui(home): Sync verb in sidebar with dirty-row count"
```

### Task N7: PR-N wrap-up

Push and open `PR-N: stationery consistency (PageHeader, single detail UI, color tokens, Sync verb on Home)`.

---

## PR-O: Architecture cleanup + docs (1.0 tag)

**Closes:** A-1 (Nav3 in docs), A-2 (sync code in app-desktop), A-3 (materializeAt journaling), C-FC-2 (CoalesceJob unwired), C-FC-4 (.audio-id), I-FC-5/6/7 (repair journal, malformed rewind row, host_name), stale TODOs, doc stamping.

### Task O1: Update sync-versioning-design.md to declare Nav3 official

**Files:**
- Modify: `docs/plans/2026-05-05-sync-versioning-design.md`

**Step 1:** Find the section that says "in-house sealed-class `NavStack` + `MutableStateFlow<List<Screen>>`". Replace with:

> Navigation: **Compose Navigation 3** (`androidx.navigation3.*`). `Screen` is a sealed interface implementing `NavKey`; `RootContent.kt` uses `rememberNavBackStack` + `NavDisplay`. The original in-house `NavStack` plan was replaced 2026-05-05 with Nav3 because `NavKey` polymorphic serialization was already producing a cleaner deep-link story than the hand-rolled NavStack would have.

**Step 2:** Update auto-memory `project_audio_overview.md` (`C:\Users\jtfow\.claude\projects\Z--User-audio\memory\project_audio_overview.md`) line 19 to say "Navigation: Compose Navigation 3 (Nav3) with sealed-class `Screen : NavKey`".

**Step 3: Commit**
```
git commit -am "docs(sync-design): Nav3 is official; retire in-house NavStack mention"
```

### Task O2: Move Gcs/Swappable/InMemorySyncQueue to `:shared:sync`

**Files:**
- `git mv app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/repo/{Gcs,Swappable,InMemory}SyncQueue.kt shared/sync/src/jvmMain/kotlin/com/sketchbook/sync/`
- Modify: `shared/sync/build.gradle.kts` to add `api(:shared:catalog)` + `api(:shared:cloud)` + `api(:shared:repository)` to `jvmMain` if not already there.
- Modify: `app-desktop/build.gradle.kts` if any of those deps were app-desktop-only.
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt` imports.

**Step 1:** Move files with `git mv` to preserve blame.

**Step 2:** Update package declaration in each file (`com.sketchbook.desktop.repo` → `com.sketchbook.sync`).

**Step 3:** Run `./gradlew check`. Fix any remaining stale imports.

**Step 4: Commit**
```
git commit -am "refactor(sync): move queue impls out of app-desktop into :shared:sync"
```

### Task O3: Journal `materializeAt` rewinds; fix malformed rows; persist host_name

**Files:**
- Modify: `shared/catalog/src/commonMain/sqldelight/com/sketchbook/catalog/db/Catalog.sq` — add `host_name TEXT` column to snapshots
- Modify: `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/impl/SqlSnapshotRepository.kt:65-114`

**Step 1: Failing test** — rewind a project, assert (a) a `journal_entries` row with `action_type = "Rewind"` exists, (b) the new snapshot row has non-empty `manifest_path` (the manifest of the target rev) + non-`"local"` `host_id` (the original author's host) + correct `host_name`.

**Step 2:** Migration adds `host_name`. Update `recordSnapshot` to take + persist `hostName`. Update `materializeAt` to:
- Fetch the *target* rev's snapshot row before laydown.
- Insert the new rewind row with `manifest_hash = target.manifestHash`, `manifest_path = target.manifestPath`, `host_id = currentHostId`, `host_name = currentHostName`, `kind = "named"`, `label = "Rewound to rev ${target.rev}"`.
- Append a JournalEntry with `actionType = "Rewind"` + payload `{"from_rev": currentLocal, "to_rev": target.rev}`.

**Step 3:** Inject `JournalRepository` into `SqlSnapshotRepository` (constructor); update `DesktopAppGraph` provider.

**Step 4: Commit**
```
git commit -am "fix(snapshot): journal rewinds; persist host_name; well-formed rewind rows"
```

### Task O4: Wire CoalesceJob

**Files:**
- Modify: `shared/sync/src/commonMain/kotlin/com/sketchbook/sync/CoalesceJob.kt`
- Modify: `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/impl/SqlSnapshotRepository.kt`
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt`

**Step 1:** Add a `SnapshotRepository.promoteToNamed(uuid, rev, label = null)` method + corresponding SQL `UPDATE snapshots SET kind='named', label=? WHERE project_uuid=? AND rev=?`.

**Step 2:** In `CoalesceJob.run()`, every 5 minutes scan `sync_state` for projects where (a) the most recent snapshot is `kind=auto` and (b) `now - max(occurred_at) > 5 minutes` (idle). Promote.

**Step 3:** Wire in `DesktopAppGraph`: `appScope.launch { coalesceJob.run() }`.

**Step 4: Test** that a project with three `auto` saves, idle for 5+ min, has its newest auto promoted to named exactly once.

**Step 5: Commit**
```
git commit -am "feat(sync): CoalesceJob promotes idle auto saves to named"
```

### Task O5: `.audio-id` sidecar writer

**Files:**
- Modify: `shared/sync/src/jvmMain/kotlin/com/sketchbook/sync/migration/JvmFirstRunMigration.kt`
- Modify: `shared/catalog/src/jvmMain/kotlin/com/sketchbook/catalog/JvmScanner.kt`
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt`

**Step 1:** Wire `JvmFirstRunMigration.run()` from `DesktopAppGraph.init` (after catalog open, before scan).

**Step 2:** In `JvmScanner.persistOk`, after `SyncStateStore.identityFor(...)` mints/loads the uuid, write `<projectDir>/.audio-id` with content `uuid=<uuid-v7>\nschema=1\n` if missing.

**Step 3:** When `JvmScanner` finds a `.audio-id` sidecar in a directory whose path doesn't match an existing `projects.path`, treat it as a moved project: update the path, don't mint a new uuid.

**Step 4: Test** — copy a project dir to a new path, rescan, assert the catalog row's path updated and history is preserved.

**Step 5: Commit**
```
git commit -am "feat(sync): .audio-id sidecar; rename/move detection in scanner"
```

### Task O6: Journal `applyMissingSampleMatch`

**Files:**
- Modify: `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/impl/SqlRepairRepository.kt:149-168`

**Step 1:** Inject `JournalRepository`. After the SQL update, append `JournalEntry(actor = "user", actionType = "MissingSampleMapped", projectId = projectId, payloadJson = "{ \"missing\": ..., \"candidate\": ... }")`.

**Step 2:** Update the `Undo` path from L7 to also append a compensating `MissingSampleUnmapped` entry rather than deleting the original.

**Step 3: Commit**
```
git commit -am "fix(repair): journal entries for missing-sample mapping + undo"
```

### Task O7: Stale TODO sweep

**Files:** as listed in the review.

**Step 1:** Delete or update each of:
- `RootContent.kt:426` — placeholder comment is now wrong; delete.
- `InMemoryJournalRepository.kt:16` — class doc references PR-8; update or delete file if unused.
- `DesktopAppGraph.kt:67-68` — "Lock + Settings remain in-memory" — Settings are persisted, lock real; delete.
- `LeasedLockRepository.kt:34` — references nonexistent `InMemoryLockRepository`; delete.
- `SnapshotPipeline.kt:27` — "JVM application (PR-18) wires up the real watcher" — done; delete.

**Step 2:** Single commit:
```
git commit -am "chore: remove stale TODOs referenced in 2026-05-06 review"
```

### Task O8: Stamp obsolete design docs

**Files:**
- `docs/plans/2026-05-04-tauri-bundling-design.md`
- `docs/plans/2026-05-04-tauri-native-design.md`
- `docs/plans/2026-05-04-web-stationery.md`
- `docs/plans/2026-05-04-home-shelves-design.md`

**Step 1:** Add a `**Status: Superseded by […]**` line after the title of each.

**Step 2: Commit**
```
git commit -am "docs(plans): stamp obsolete designs as superseded"
```

### Task O9: Update feature-complete-design acceptance to reflect what shipped

**Files:**
- Modify: `docs/plans/2026-05-06-feature-complete-design.md`

**Step 1:** Add an "Acceptance — verified 2026-05-06 post-PR-O" section with each criterion ticked plus the file:line proving it.

**Step 2: Commit**
```
git commit -am "docs(feature-complete): record post-PR-O acceptance evidence"
```

### Task O10: PR-O wrap-up + tag v1.0.0

**Step 1:** `./gradlew check`. PASS.

**Step 2:** Push and merge `PR-O: architecture cleanup + docs (1.0 readiness)`.

**Step 3:** After merge: `git tag v1.0.0 && git push origin v1.0.0`. Per `feedback_local_build_authority`, the local build is the gate; merge with `--admin` if needed.

---

## PR-P: Arrangement Thumbnail Strip (top feature pick #1)

**Goal:** Visual arrangement glyph per project (200×40 px clip-block strip). Pure leverage of existing parser data.

### Task P1: Surface clip ranges in the parser

**Files:**
- Modify: `shared/parser-als/src/commonMain/kotlin/com/sketchbook/als/AlsParser.kt`
- Modify: `shared/parser-als/src/commonMain/kotlin/com/sketchbook/als/ParseResult.kt` (or wherever `ProjectMetadata` is)

**Step 1:** Extend the StAX walk to capture per-track `Clip { trackIndex, startBeat, lengthBeats, isWarped }`. The parser already enters `<Tracks>` subtrees; just emit a flat list.

**Step 2: Test** with a fixture `.als` (smallest one in `Projects/`), assert non-empty clip list.

**Step 3:** Add `clips: List<ClipRange>` to `ProjectMetadata`.

**Step 4: Commit**
```
git commit -am "feat(parser): emit clip ranges per track during streaming parse"
```

### Task P2: Persist + cache thumbnails

**Files:**
- Modify: `shared/catalog/src/commonMain/sqldelight/com/sketchbook/catalog/db/Catalog.sq` — `project_thumbnails` table keyed by content hash
- Create: `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/ThumbnailRepository.kt`
- Create: `shared/sync-io/src/jvmMain/kotlin/com/sketchbook/syncio/ThumbnailRenderer.kt`

**Step 1:** SQL: `CREATE TABLE project_thumbnails(content_hash TEXT PRIMARY KEY, png_bytes BLOB NOT NULL, generated_at INTEGER NOT NULL);`

**Step 2:** `ThumbnailRenderer.render(metadata: ProjectMetadata): ByteArray` — uses Compose Skia to draw a 400×80 px (2x for hi-DPI) image, one row per track, blocks colored by `track.color` if present else `inkMuted`. Write to PNG via Skia's `Image.encodeToData(EncodedImageFormat.PNG)`.

**Step 3:** Wire scan: after parse, render and `INSERT OR REPLACE` keyed by content hash so re-scans of unchanged files are no-ops.

**Step 4: Commit**
```
git commit -am "feat(thumbnail): render + cache arrangement glyphs in catalog"
```

### Task P3: Show thumbnail on project rows

**Files:**
- Modify: `shared/feature-projects/src/commonMain/kotlin/com/sketchbook/featureprojects/SongStrip.kt`

**Step 1:** Add an `Image(thumbnailBytes.toImageBitmap())` slot inside the existing row at fixed 200×40 dp on the right side. Empty placeholder = neutral `surfaceCard` swatch.

**Step 2: Commit**
```
git commit -am "ui(projects): inline arrangement thumbnail on each row"
```

### Task P4: Detail panel "Big" thumbnail

**Files:**
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt` (DetailPanelContent header)

**Step 1:** At the top of the detail panel, render the same thumbnail at 600×100 dp. Click → toggle "show track labels" overlay.

**Step 2: Commit**
```
git commit -am "ui(detail): hero thumbnail at top of project detail"
```

### Task P5: PR-P wrap-up

Push `PR-P: arrangement thumbnail strip (catalog visual)`.

---

## PR-Q: Idea Fingerprint clusters (top feature pick #2)

**Goal:** Group projects by structural similarity using existing content hashes. No audio analysis.

### Task Q1: Compute fingerprint at scan time

**Files:**
- Create: `shared/sync-io/src/jvmMain/kotlin/com/sketchbook/syncio/IdeaFingerprint.kt`
- Modify: `shared/catalog/src/commonMain/sqldelight/com/sketchbook/catalog/db/Catalog.sq`

**Step 1:** Add table:

```sql
CREATE TABLE idea_fingerprints(
    project_id INTEGER PRIMARY KEY,
    sample_hash_set TEXT NOT NULL,  -- json array of sample blake3
    plugin_chain_set TEXT NOT NULL, -- json array of plugin names+versions
    midi_clip_hashes TEXT NOT NULL, -- json array of blake3 over note tuples
    rare_score REAL NOT NULL        -- weight: rarity of components in library
);
```

**Step 2:** During scan, compute MIDI clip hash by sorting `(note, start, length, velocity)` tuples and feeding through BLAKE3 (already on classpath). Sample hashes already exist.

**Step 3: Test** — two synthetic projects with identical MIDI produce identical hashes.

**Step 4: Commit**
```
git commit -am "feat(fingerprint): compute structural fingerprint at scan time"
```

### Task Q2: Similarity edges

**Files:**
- Create: `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/SimilarityRepository.kt`
- Modify: `Catalog.sq`

**Step 1:** Add `similarity_edges(project_a, project_b, jaccard REAL, weight REAL)` table; index on both columns.

**Step 2:** After scan, recompute edges: for each project pair where any component overlaps (use sample-hash inverted index for cheap candidate generation, not all-pairs), compute weighted Jaccard with `rare_score` boosting rare components.

**Step 3:** Materialize only edges with `jaccard > 0.3` (tune later).

**Step 4: Test** — 3 synthetic projects (A,B near-dup; C unrelated): edge `A-B` exists, `A-C` and `B-C` don't.

**Step 5: Commit**
```
git commit -am "feat(fingerprint): compute and store similarity edges"
```

### Task Q3: "Similar projects" panel section

**Files:**
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt` (DetailPanelContent Overview tab)

**Step 1:** Add a "Similar ideas" section that lists up to 5 highest-Jaccard neighbors with their thumbnails (PR-P leverage). Click → switch detail panel to that project.

**Step 2: Commit**
```
git commit -am "ui(detail): Similar Ideas section in Overview"
```

### Task Q4: "Clusters" toolbar chip

**Files:**
- Modify: `shared/feature-projects/src/commonMain/kotlin/com/sketchbook/featureprojects/ProjectListScreen.kt`

**Step 1:** Add a chip in the existing toolbar — "Group by similarity". When active, the project list groups via union-find over edges; group header shows "Idea cluster of N projects".

**Step 2:** Per `feedback_color_restraint`, no new color — reuse `accentSecondary`.

**Step 3: Commit**
```
git commit -am "ui(projects): cluster-by-similarity toolbar chip"
```

### Task Q5: PR-Q wrap-up

Push `PR-Q: idea fingerprint clusters (find-similar without audio analysis)`.

---

## PR-R: Stage chip auto-inference (top feature pick #3)

**Goal:** Each project gets a `Sketch / In Progress / Mixing / Done / Stuck` chip auto-inferred from cheap heuristics; user can override.

### Task R1: Stage column + heuristic

**Files:**
- Modify: `Catalog.sq` — `ALTER TABLE projects ADD COLUMN stage_inferred TEXT; ALTER TABLE projects ADD COLUMN stage_override TEXT;`
- Create: `shared/sync-io/src/jvmMain/kotlin/com/sketchbook/syncio/StageInferrer.kt`

**Step 1:** Heuristic function:
- Track count <5, no mastering chain, no bounces nearby, edited within 30d → `Sketch`
- Track count ≥5, edited within 30d → `InProgress`
- Mastering chain (`OTT`, `Pro-L`, `Ozone`, `Limiter` somewhere in plugin list) + edited within 14d → `Mixing`
- Mastering chain + bounce file matching project name in same folder + not edited within 30d → `Done`
- Track count ≥10, no edits in 90+ days, no bounce nearby → `Stuck`
- Else → null (no chip)

**Step 2: Test** with 5 fixture metadata snapshots covering each branch.

**Step 3: Commit**
```
git commit -am "feat(stage): heuristic-based stage inference"
```

### Task R2: Stage chip on rows + filter

**Files:**
- Modify: `SongStrip.kt`
- Modify: `ProjectListScreen.kt` toolbar

**Step 1:** Small chip on each row showing inferred (or overridden) stage. Reuse existing badge style; one neutral palette token per stage (no rainbow).

**Step 2:** Filter chip in toolbar: "Stuck" / "Sketches" / "Mixing" / "Done". Multi-select.

**Step 3: Commit**
```
git commit -am "ui(stage): inline stage chip + filter toolbar"
```

### Task R3: Override

**Files:**
- Modify: `RootContent.kt` (DetailPanelContent Overview tab)

**Step 1:** Right-click (or context-menu icon) on the chip in detail header → set override; clear override resets to inferred.

**Step 2:** Persist override; journal `StageOverridden` action.

**Step 3: Commit**
```
git commit -am "feat(stage): user override + journaling"
```

### Task R4: PR-R wrap-up

Push `PR-R: stage chip auto-inference`.

---

## PR-S: Sample family tree

**Goal:** From any sample row, popover showing every other project that uses it.

### Task S1: Inverse query

**Files:**
- Modify: `shared/catalog/src/commonMain/sqldelight/com/sketchbook/catalog/db/Catalog.sq`

**Step 1:** Add query `selectProjectsUsingSample`:

```sql
selectProjectsUsingSample:
SELECT DISTINCT p.id, p.name, p.path
FROM projects p
JOIN project_samples ps ON ps.project_id = p.id
WHERE ps.sample_id = ?
ORDER BY p.last_modified DESC;
```

**Step 2:** Expose via `SampleRepository.observeProjectsUsing(sampleId)`.

**Step 3: Commit**
```
git commit -am "feat(samples): inverse query for projects-using-sample"
```

### Task S2: Popover UI

**Files:**
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt` (DetailSamplesTab)

**Step 1:** Click on a sample row → small Popup anchored to the row showing up to 10 other projects using that sample, click to navigate.

**Step 2: Commit + push** `PR-S: sample family tree popover`.

---

## PR-T: Plugin risk forecast

**Goal:** Surface "47 projects depend on Serum 1.x" only when a plugin is actually missing on the user's system.

### Task T1: Plugin presence check

**Files:**
- Create: `shared/sync-io/src/jvmMain/kotlin/com/sketchbook/syncio/PluginPresenceProbe.kt`

**Step 1:** Walk the user-configured VST/VST3/AU directories (settings already store these or we add a setting) and build a `Set<String>` of installed plugin display-names + versions.

**Step 2:** For each plugin in the catalog's `plugins` table, mark `is_installed`.

**Step 3: Commit**
```
git commit -am "feat(plugins): scan plugin folders + flag installed/missing"
```

### Task T2: "Coverage" chip on Home

**Files:**
- Modify: `RootContent.kt` (Home top bar / Highlights)

**Step 1:** Small chip: "3 plugins missing affecting 47 projects" — click → list of missing plugins → click each → list of affected projects.

**Step 2:** Surface only when count > 0.

**Step 3: Commit + push** `PR-T: plugin risk forecast`.

---

## PR-U: Snapshot diff view

**Goal:** Diff two snapshots: tracks/clips/plugins/samples added/removed.

### Task U1: Diff function

**Files:**
- Create: `shared/sync/src/commonMain/kotlin/com/sketchbook/sync/SnapshotDiff.kt`

**Step 1:** Pure function `diff(a: ProjectMetadata, b: ProjectMetadata): SnapshotDiff` with fields `tracksAdded/Removed/Renamed`, `pluginsAdded/Removed`, `samplesAdded/Removed`, `clipChanges`.

**Step 2:** Test with two synthetic metadata.

**Step 3: Commit**
```
git commit -am "feat(snapshot): structural diff between metadata snapshots"
```

### Task U2: Diff UI in Timeline

**Files:**
- Modify: `shared/feature-timeline/src/commonMain/kotlin/com/sketchbook/featuretimeline/TimelineScreen.kt`

**Step 1:** Multi-select on snapshot rows — when 2 selected, side panel shows the diff. Use small `+`/`−` glyphs in `inkPlus`/`inkMinus` (or reuse `accentDanger`/`accentSecondary` per `feedback_color_restraint`).

**Step 2: Commit + push** `PR-U: snapshot diff view`.

---

## PR-V: Bounce aggregator + in-app player

**Goal:** Match bounced WAV/MP3 files to projects; play them inline.

### Task V1: Bounce matcher

**Files:**
- Create: `shared/sync-io/src/jvmMain/kotlin/com/sketchbook/syncio/BounceMatcher.kt`
- Modify: `Catalog.sq` — `project_bounces(project_id, path, mtime, confidence)`

**Step 1:** During scan, for each `.wav`/`.mp3`/`.aiff` found in or near `Projects/`, score against every project by:
- Filename token overlap with project name (Jaccard) — 0.5 weight
- Located inside the project folder — +0.3
- mtime within 1 hour of any `.als` save mtime in that project — +0.2

**Step 2:** Persist top match per bounce file when score > 0.5.

**Step 3: Commit**
```
git commit -am "feat(bounce): heuristic match of audio files to projects"
```

### Task V2: Bounces tab + player

**Files:**
- Modify: `RootContent.kt` (DetailPanelContent — add 7th tab "Bounces")

**Step 1:** List bounces with confidence pill, hover-to-stream using JVM `javax.sound.sampled.AudioSystem` (no new dep). Click to confirm/reject the match.

**Step 2:** Confirmed/rejected matches journal `BounceMatchConfirmed`/`BounceMatchRejected`.

**Step 3: Commit + push** `PR-V: bounce aggregator + in-app player`.

---

## Memory / runtime updates after PR-O

Once PR-O merges, update auto-memory entries:
- `project_audio_overview.md` — Nav3 official, no in-house NavStack.
- Add a feedback memory: "Nav3 is the navigation stack — design doc was wrong. Updated 2026-05-06."

These memory updates are not part of any PR; they're post-merge housekeeping.

---

## Notes on execution discipline

- **No batch checkpoints within a PR.** (Per `feedback_no_batch_checkpoints`.)
- **One PR at a time.** (Per `feedback_one_pr_at_a_time`.) Don't open multiple PRs simultaneously even though the plan is sequential.
- **Local build is the gate.** When `./gradlew check` passes, merge with `--admin`; don't wait on CI. (Per `feedback_local_build_authority`.)
- **No new colors** anywhere in PRs N–V. Reuse `accentAction`, `accentSecondary`, `accentWarning`, `accentDanger`, `inkMuted`. (Per `feedback_color_restraint`.)
- **No new libraries** anywhere. (Per `feedback_no_unnecessary_libs`.)
- **Layer onto existing UI; never redesign.** Every UI addition is a chip, popover, tab, or sidebar entry on existing components. (Per `feedback_layer_dont_redesign`.)
- **Browser-verify visually as you go** — every UI-touching task in PRs L/N/P/Q/R/S/T/U/V should `./gradlew :app-desktop:run` once before commit.
