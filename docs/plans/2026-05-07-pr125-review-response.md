# PR #125 review response — fix plan

**PR:** [feat: backend generalization (TrackedTree + CloudDoc) #125](https://github.com/srMarlins/sketchbook/pull/125)
**Branch:** `feat/backend-generalization-v2`
**Status:** 8 comments already addressed by `0245895`. Original plan was 8 commits; **revised to 6** after the migrator-isn't-needed pivot below.

> **2026-05-07 pivot — drop the migrator entirely.** Sketchbook hasn't shipped to production. There are no users with `<tenant>/manifests/<uuid>/...` (v=1) data in their buckets. The migrator existed to relocate v=1 → v=2; with zero v=1 data, the relocate path is dead code. Reviewer R-1 already noted that `DirectGcsBackend.manifestsPrefix` reads from the legacy path for `Project` while the migrator writes to v=2 — i.e. the migrator was already a no-op for the only kind that had any data. Action:
>
> - **Delete** `JvmCloudMigrator`, `CloudMigrator`, `MigrationCoordinator`, `MigrationDialog`, `MigrationProgress` / `MigrationStatus` / `MigrationReport`, the migration module's tests, the `<tenant>/manifests/...` legacy path branch, and `MigrationLayout`. ~1,000 lines net deleted.
> - **Unify** all kinds on the v=2 path layout in `DirectGcsBackend` (`<tenant>/trees/<kind>/<tree_id>/...`).
> - **Move** registry-seeding into `BootstrapData.seedRegistry()`: silent one-shot per-machine, gated by `Settings.cloudMigrationComplete` (renamed to `Settings.registrySeeded` for honesty).
>
> This collapses commits 3 + 4 + 7 into a single **commit 3 ("delete migrator, unify paths, move bootstrap")**. Commits 5/6/8 renumber to 4/5/6.

This doc enumerates each comment, picks an approach, and orders the work into the revised 6-commit plan.

---

## 0. Already addressed (commit `0245895`)

The first review pass (gemini-bot inline) is closed. Listed here so the rest of the plan doesn't accidentally re-do them:

| # | File / line | Fix | Verified |
|---|---|---|---|
| G-1 | `PullPoller.kt:114` "missing `delay(pollInterval)`" | False positive — `delay(pollInterval)` is at line 115 inside the loop. | ✅ |
| G-2 | `TreeRegistry.lookup` collaborators empty | Added `collaborators_json TEXT NOT NULL DEFAULT '[]'` to `tree_registry_cache`; encoded via `ListSerializer(Collaborator.serializer())`. | ✅ partially — `created_at` + `created_by_host` still missing, see R-2 below |
| G-3 | `SqlTreeJournal` O(N) snapshot existence | Added `selectTreeSnapshotByRev` PK lookup. | ✅ |
| G-4 | Migrator manifest relocation sequential | Switched to `channelFlow` + `RELOCATE_CONCURRENCY=8` chunked async. | ✅ but the chunking is fake parallelism (R-12) — re-do as Semaphore. |
| G-5 | Project registration N round-trips | Added `TreeRegistry.registerAll()` doing one CAS write. | ✅ |
| G-6 | Redundant `!installedKeys` filter | Dropped — `SetupNav.filterPending` already excludes installed. | ✅ |
| G-7 | `alreadyInstalled` always-empty filter | Frozen at first load; reprobes preserve. | ✅ |
| G-8 | VM concurrent-trigger race | Added `Mutex.withLock` around refresh + reprobe. | ⚠️ partial — `dismiss()` still outside the lock; `isReprobing=true` flip in `reprobe` still outside the lock (R-22). |

---

## 1. Issue catalog

Each item below carries a stable id (R-N) used in the commit plan in §3. Severity tags: **🔴 blocking** (correctness or durability), **🟠 high** (real bug under load / policy violation), **🟡 medium** (idiomatic / forward-compat), **🟢 nit**.

### 1.1 Cloud durability (DirectGcsBackend, ManifestMerger, SnapshotPipeline)

| id | sev | location | issue | approach |
|---|---|---|---|---|
| **R-1** | 🔴 | `DirectGcsBackend.kt:329-387` `appendManifestHead` | Timestamped manifest is PUT *before* HEAD CAS. CAS losers leave orphan timestamped objects sharing a `rev` prefix → non-deterministic `readManifest` + unbounded bucket growth. | **Swap order:** CAS HEAD first; on success then PUT the timestamped copy (must-not-exist). Loser never wrote a timestamped object. Add a regression test in `DirectGcsBackendTest`. Best practice: GCS CAS docs recommend the precondition write *be* the source of truth — derived/historical writes follow. |
| **R-2** | 🔴 | `DirectGcsBackend.kt:309-327` (`listManifests`) and `:543-554` (`listDocs`) | No `nextPageToken` handling — GCS list caps at 1000 items per page. Truncates silently after 2.7 years of daily snapshots; truncates host slices once collab lands. | **Loop on `pageToken`** until exhausted. ([GCS pagination docs](https://cloud.google.com/storage/docs/paginate-results)) Five lines per call site. |
| **R-3** | 🟠 | `DirectGcsBackend.kt:339-369` | HEAD is a full duplicate of latest manifest (doubles write cost; combined with R-1 unbounded). | **Out of scope here** — flagged in v2 of design doc. Open follow-up issue. Don't attempt in this PR. |
| **R-4** | 🟠 | `DirectGcsBackend.kt:150-242` | `putBlobResumable` is chunked, not resumable: a network blip on chunk 7/10 of 543 MB forfeits all prior progress. | **Out of scope** — defer to follow-up issue per the PR scope. The author already flagged it. Add the correct resume mechanic (`PUT $sessionUrl` with `Content-Range: bytes */<total>` + empty body to query offset, then re-PUT from there per [GCS resumable docs](https://cloud.google.com/storage/docs/performing-resumable-uploads)) in a future PR. Does not block merge. |
| **R-5** | 🟡 | `DirectGcsBackend.kt:248-267` (`stageSourceToTempFile`) and `:771-795` (`drainToTempFile`) | Temp files in `java.io.tmpdir` cross device → hardlink in materializer falls back to copy. Doubles disk I/O. | **Out of scope** — same as R-3/R-4. Open issue. |
| **R-6** | 🟡 | `Lock.kt` + `DirectGcsBackend.kt:432-465` | `LeaseLock.heartbeatSeq` exists but `refreshLock` doesn't bump it. | Either drop the field or bump on refresh. **Drop** — no consumer reads it; cheaper to remove than to lie. |
| **R-7** | 🟡 | `ManifestMerger.kt:67` | `@Suppress("UNUSED_PARAMETER") mergerHost: String` dead param. | Drop the param. Tie-break is already deterministic via `localHost ≤ remoteHost`. |
| **R-8** | 🟡 | `ManifestMerger.kt:69-70` | `local!!`/`remote!!` bang-bangs hide a pre-condition (one of the two is non-null). | Either `require(local != null \|\| remote != null)` or split `pickWinner` and call sites stop nulling. Pick the require — minimum churn. |
| **R-9** | 🟡 | `ManifestMerger.kt` retry chains | `parentRev = remote.rev` always — chain of merges loses ancestry. | Document that `parentRev` is decorative under Merge mode (one-line kdoc). Real DAG ancestry is a v1.2 timeline concern; not worth restructuring now. |
| **R-10** | 🟢 | `DirectGcsBackend.kt:237` | Resumable chunk's `412 → true` is dead code (412 only on session init). | Delete the branch; throw via the `else`. |
| **R-11** | 🟢 | `Manifest.projectUuid` extension | Throws on non-project kinds. | Add `projectUuidOrNull` companion. Defer to a follow-up if any caller needs it; otherwise leave the kdoc nudge. **Skip** — no caller motivates it now. |

### 1.2 Migration code — DELETED in commit 3

**Pivot 2026-05-07:** the migrator system is dead code (no v=1 users) and gets deleted. Most issues below resolve by deletion; concerns that touch real engineering (DI, dispatcher injection, idempotent bootstrap) move into `BootstrapData.seedRegistry()`.

| id | sev | original concern | resolution |
|---|---|---|---|
| **R-12** | 🔴 | `relocateInParallel` data race + fake parallelism + late-failure | **N/A — code deleted.** |
| **R-13** | 🟠 | `runCatching` swallows in `listLegacyManifests` | **N/A — code deleted.** |
| **R-14** | 🟠 | Blocking SqlDelight in `status()` | **Carries to BootstrapData:** `seedRegistry()` reads `selectAllProjectIdentitiesWithName()` and must `withContext(ioDispatcher)`. |
| **R-15** | 🟠 | Bad defaults on `JvmCloudMigrator` ctor | **Carries to BootstrapData:** ditto — required `hostId`, `ioDispatcher` injected via DI, no defaults. |
| **R-16** | 🟡 | `status` + `migrate` duplicate cloud calls | **N/A — only one bootstrap path now.** |
| **R-17** | 🟡 | Atomic increment+send progress | **N/A — no progress UI.** |
| **R-18** | 🟡 | UL tree-id host-prefix comment | **Carries to BootstrapData:** the seed mints `tt-ul-<hostId>`; comment links [#131](https://github.com/srMarlins/sketchbook/issues/131). |
| **R-19** | 🟡 | No outer timeout | **N/A — registry-seed is one round-trip via `registerAll`.** |
| **R-20** | 🟡 | `Failed` loses cause | **N/A — `MigrationProgress` deleted.** `BootstrapData.seedRegistry()` returns `Result<...>` carrying the real `Throwable`. |
| **R-21–R-29** | 🟠/🟡/🟢 | All MigrationCoordinator + MigrationDialog issues | **N/A — both files deleted.** |

### 1.3 BootstrapData

| id | sev | location | issue | approach |
|---|---|---|---|---|
| **R-30** | 🟡 | `BootstrapData.kt:39-41` | Factory takes `@Suppress("UNUSED_PARAMETER") cloud: CloudBackend`. CLAUDE.md / repo policy: don't ship dead params. | Drop the parameter. The desktop main loop already pulls `cloud` from `UserGraph` — `BootstrapData` doesn't need the handle. |
| **R-31** | 🟡 | `BootstrapData.kt` | Inconsistent error surface: `pullRegistry()` throws, `publishHostPluginManifest`/`registerMachine` return `Result<...>`, `userLibrarySyncEnabled()` returns `Boolean`. | Pick one — `Result<...>` everywhere. Mirrors the cloud-failure expectations of the other two. (See R-43 for why we don't go further down the "sealed result" road.) |
| **R-32** | 🟢 | `BootstrapData.kt` kdoc | Wiring example doesn't compile (missing `settings`, `clock`). | Update the kdoc to match the constructor; or delete it. **Update** — the wiring example is load-bearing for desktop main. |
| **R-33** | 🟠 | DI: `BootstrapData` | Not `@ContributesBinding(AppScope::class)` / not Metro-injected. | **Bind it.** §2 of the DI doc: every singleton service is `@SingleIn(AppScope::class) @ContributesBinding(AppScope::class) @Inject`. The deps (`TreeRegistry`, `MachineProfileStore`, `SettingsRepository`) are all bindable. |

### 1.4 PluginChecklistViewModel + Screen

| id | sev | location | issue | approach |
|---|---|---|---|---|
| **R-34** | 🟠 | `PluginChecklistViewModel.kt:34` | Not `@ContributesIntoMap(AppScope::class) @ViewModelKey @Inject`. DI doc §3.1 makes this mandatory. | **Bind it.** The VM takes `MachineProfileStore` (already bindable) — the cloud handle is per-user inside the store, not per-VM. The "cloud handle is per-user" excuse in the kdoc applies to the *store*, not the VM. |
| **R-35** | 🟠 | `PluginChecklistViewModel.kt:62-63` (`refresh`) | `alreadyInstalled = union.filter { installed }.map { toRow }` doesn't apply OS filter — Mac AU shows up on Windows. | Mirror `SetupNav.filterPending`'s `formatRunsOn` call: `filter { it.installed && SetupNav.formatRunsOn(it.format, os) }`. Promote `formatRunsOn` to public on `SetupNav`. |
| **R-36** | 🟠 | `PluginChecklistViewModel.kt:92` (`dismiss`) | Mutates `_state` outside the mutex; races refresh + reprobe. | (a) Move write inside `stateMutex.withLock`, or (b) use `_state.update { it.copy(...) }` (atomic, no mutex needed for the simple toggle). Pick (b) — same pattern fits R-21. |
| **R-37** | 🟠 | `PluginChecklistViewModel.kt:84` (`reprobe`) | The optimistic `isReprobing = true` write is *outside* the lock. | Move inside the lock OR drop the optimistic flip and just set `isReprobing` from inside the locked block at the start. Pick the first — keeps the optimistic latency, just inside the lock. |
| **R-38** | 🟠 | `PluginChecklistViewModel.kt` `viewModelScope.launch` | No `try/catch` — exceptions silently die, UI shows empty buckets forever. | Add a `loadFailed: String?` field to `PluginChecklistUiState`; wrap launch bodies in `try/catch (c: CancellationException) { throw c } catch (t: Throwable) { _state.update { it.copy(loadFailed = t.message, isReprobing = false) } }`. ([runCatching at suspend boundary anti-pattern](https://medium.com/@jatingujjar646/runcatching-with-coroutines-in-kotlin-the-hidden-cancellation-trap-92dd4ffc8d4a)) |
| **R-39** | 🟡 | `PluginChecklistViewModel.kt` `initialState` | No loading state — screen flashes "Found 0 plugins" then real data. | Add `isInitialLoad: Boolean = true`; cleared on first refresh. |
| **R-40** | 🟡 | `PluginChecklistViewModel.kt:72-86` (`reprobe`) | `recentlyInstalled = current + justInstalled` accumulates indefinitely; uninstalls don't reconcile. | Filter `recentlyInstalled` against the latest `installedKeys` set before appending: `(current.recentlyInstalled + justInstalled).filter { (it.name to it.format) in installedKeys }`. |
| **R-41** | 🟠 | `PluginChecklistViewModel.kt:71` | `reprobe(reprobeRunner: suspend () -> List<HostPluginEntry>)` — VM owns state, side-effect runner lives at the call site, screen's `onReprobe: () -> Unit` doesn't carry the runner. Hidden contract. | Inject the probe via constructor (`PluginPresenceProbe` `fun interface`). VM becomes self-contained; `reprobe()` takes no args; screen's `onReprobe: () -> Unit` matches the VM signature. |
| **R-42** | 🟢 | `PluginChecklistViewModel.kt:135-148` (`OsProvider.System`) | Re-reads `os.name` on every call; name shadows `java.lang.System`. | Stamp once into a `val` (or `lazy`); rename to `OsProvider.Default`. |
| **R-43** | 🟢 | `PluginChecklistViewModel.kt` `init { refresh() }` | I/O in constructor — first cloud call has nowhere to surface failure. | Combined with R-38: refresh inside try/catch surfaces it. Don't move to a `LaunchedEffect` in the screen — keeps the test surface simple. |
| **R-44** | 🟡 | `PluginChecklistScreen.kt` | `LazyColumn` `items(...)` calls have no `key`. | Add `key = { row -> "${row.name}|${row.format}" }`. ([Compose LazyColumn keys best practice](https://developer.android.com/develop/ui/compose/performance/bestpractices)) Add `contentType` matching the bucket. |
| **R-45** | 🟡 | `PluginChecklistScreen.kt` | `LazyColumn` has no `Modifier.weight(1f)` inside the parent `Column` — pushes the action row off-screen for long lists. | Wrap the LazyColumn `Modifier.weight(1f).fillMaxWidth()`. |
| **R-46** | 🟠 | `PluginChecklistScreen.kt` | "Re-check installed plugins" `Button` not `enabled = !state.isReprobing` — users queue N reprobes. | Add `enabled = !state.isReprobing`. |
| **R-47** | 🟠 | `PluginChecklistScreen.kt` | No error UI for `loadFailed`. | Add a small inline `Text` with the message + a Retry button when `state.loadFailed != null`. |
| **R-48** | 🟡 | `PluginChecklistScreen.kt` | Tightly coupled to `PluginChecklistViewModel`; not state-hoisted. DI doc §3.2 wants `metroViewModel<VM>()` *inside the route composable*. | Split into a `PluginChecklistRoute` (acquires VM, owns lifecycle) + `PluginChecklistScreen(state, onReprobe, onDismiss, modifier)` stateless. ([State hoisting in Compose](https://developer.android.com/develop/ui/compose/state)) Tests + `@Preview` use the stateless screen. |
| **R-49** | 🟡 | `PluginChecklistScreen.kt:34` | `collectAsState()` instead of `collectAsStateWithLifecycle()`. DI doc §3.2 specifies the latter. | Switch to `collectAsStateWithLifecycle()`. |
| **R-50** | 🟡 | `PluginChecklistScreen.kt:35` | Hardcoded `"Set up this Mac"` ignores `osProvider`. | Pass the OS string through state and render `"Set up this <Mac \| PC \| Linux box>"`. |
| **R-51** | 🟢 | `PluginChecklistScreen.kt` | Material `Button`/`Divider`/`OutlinedButton`/`Text` imported directly — repo has `ui-shared` wrappers. | Replace with `ui-shared` equivalents. |
| **R-52** | 🟢 | `PluginChecklistScreen.kt:60` | `Modifier.padding(end = 16.dp)` on a Column with nothing to its right — dead. | Delete. |
| **R-53** | 🟢 | `PluginChecklistScreen.kt:36` | `total` recomputed on every recomposition (`isReprobing` flip triggers it). | Wrap in `remember(state.pending, state.recentlyInstalled, state.alreadyInstalled) { … }` or `derivedStateOf`. |
| **R-54** | 🟢 | `PluginChecklistScreen.kt:96` | Top-level `fun toScreenRows(...)` is public for the Settings entry. | Move to internal helper / scope to `internal`. |
| **R-55** | 🟡 | `PluginChecklistViewModel.kt:92` (`dismiss`) | "Hides the screen on the next launch" — but no flag is persisted; `dismiss()` is a no-op. | Two options: (a) wire `dismiss()` to `SettingsRepository.setPluginChecklistSkipped(true)` (a new flag); or (b) remove the function and let the screen call the persistence layer directly. Pick **(b)** — simpler and matches the `SetupNav` flow where the Settings entry is the off-switch per the design doc; persistence belongs in `SettingsRepository`, not the VM. |

### 1.5 MachineProfileStore

| id | sev | location | issue | approach |
|---|---|---|---|---|
| **R-56** | 🟠 | `MachineProfileStore.kt:117` (`CloudMachineProfileStore`) | Not `@ContributesBinding(AppScope::class)` — inconsistent with `CloudTreeRegistry` (which got it right). | **Bind it.** Same `@SingleIn(AppScope::class) @ContributesBinding(AppScope::class) @Inject` pattern. |
| **R-57** | 🟠 | `MachineProfileStore.kt:212-235` (`composeHostSlice`) | Blocking SqlDelight `executeAsList` inside `suspend fun publishHostSlice`. | Take `ioDispatcher: CoroutineDispatcher` ctor param; wrap the SQL section in `withContext(ioDispatcher) { ... }`. |
| **R-58** | 🟠 | `MachineProfileStore.kt:149-173` (`composeUnion`) | Sequential `cloud.readDoc` per host (N round-trips). | `coroutineScope { refs.map { ref -> async { cloud.readDoc(ref.key) } }.awaitAll() }`. |
| **R-59** | 🟡 | `MachineProfileStore.kt:154-156, 202-204` | `runCatching { decode }.getOrNull()` / `getOrDefault(emptyList())` silently drops malformed slices. | (a) Replace `runCatching` with `try/catch (CancellationException) { throw } catch (t)` — coroutine-safe. (b) Surface decode failures via the `TreeJournal` (a new `DecodeFailed` event variant) so they show up in debugging. (c) Keep the empty-slice fallback for forward-compat. |
| **R-60** | 🟡 | `MachineProfileStore.kt:175-198` (`registerMachine`) | CAS retry loop has no backoff — herd thunder under a mass-update. | Add jittered exponential backoff: `delay((50L + Random.nextLong(50)) * (1L shl (attempt - 1).coerceAtMost(5)))`. ([Marc Brooker — Exponential Backoff and Jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/), [kotlin-retry library](https://github.com/michaelbull/kotlin-retry)) |
| **R-61** | 🟡 | `MachineProfileStore.kt:139-145` (`publishHostSlice`) | Half-CAS: read existing → write with `expected = existing?.generation`. If doc didn't exist (read returns null), `expected = null` is unconditional clobber. Two coroutines on the same host can race. | Either (a) fully unconditional (drop the read; write with `expected = null` always — kdoc says "no conflict to retry against") or (b) full CAS via `Generation.ZERO` for first write + retry on conflict. **Pick (a)** — the kdoc is right; the read is wasted I/O. |
| **R-62** | 🟢 | `MachineProfileStore.kt:248-249` | `ListSerializerKeepImport` hack. | Delete the unused `import kotlinx.serialization.builtins.ListSerializer` line and the `@Suppress("unused")` val. (`TreeRegistry` actually uses `ListSerializer` for `Collaborator`s; this file doesn't need it.) |
| **R-63** | 🟡 | `MachineProfileStore.kt:158, 213, 226` | `Pair<String, String>` map keys for `(name, format)`. | Define `internal data class PluginKey(val name: String, val format: PluginFormat)` (uses the typed format from R-65); reuse in both `composeUnion` and `composeHostSlice`. |
| **R-64** | 🟢 | `MachineProfileStore.kt` data classes | `HostPluginManifest`, `HostPluginEntry`, `MachineEntry`, `UnionedPluginManifest`, `PluginRow`, `PluginChecklistUiState` lack `@Immutable`. | Add `@Immutable` per DI doc §3.1. |
| **R-65** | 🟡 | `MachineProfileStore.kt` + `SetupNav.kt` + `OsProvider` | Stringly-typed: `format: String`, `os: String`; `SetupNav.formatRunsOn` is a giant `when` over string literals (with literal duplicate `"ableton", "unknown" -> false; else -> false`). | Promote `PluginFormat` enum from `core` through `HostPluginEntry`. Add `Os` enum (`Mac`, `Windows`, `Linux`). Wire serializers to keep wire format strings. **Larger refactor** — touches ~10 files. Worth it: the same string conversion happens in three places today. |

### 1.6 TreeRegistry + catalog SQL

| id | sev | location | issue | approach |
|---|---|---|---|---|
| **R-66** | 🟠 | `TreeRegistry.kt:175-184` (`lookup`) | Still synthesizes `createdAt = Instant.fromEpochMilliseconds(row.updated_at)` and `createdByHost = ""`. (G-2 added collaborators_json but not these.) | Add `created_at INTEGER NOT NULL` + `created_by_host TEXT NOT NULL` columns to `tree_registry_cache`. Update `upsertTreeRegistryEntry` query + `refreshCache` to populate them. Bumps the schema migration to **v11** — cleaner than amending v10 once people are on it. |
| **R-67** | 🟠 | `10.sqm` `tree_registry_cache.scope_key` | `scope_key TEXT` (nullable) — every registered tree carries non-null scope_key, and SQL NULL semantics break `selectTreeRegistryByKindScope`. | `scope_key TEXT NOT NULL DEFAULT ''` (in v11 alongside R-66). |
| **R-68** | 🟠 | `10.sqm` no FKs on `tree_*` tables | `tree_sync_state.tree_id` etc. should `REFERENCES tree_registry_cache(tree_id) ON DELETE CASCADE`. | Add FK constraints (in v11 — tables are still small enough that recreation is cheap). |
| **R-69** | 🟠 | `TreeRegistry.kt:299-316` (`refreshCache`) | Upserts current entries but never DELETEs missing ones — phantom rows after collaborator removal / tree deletion. | In the same `transaction`: `DELETE FROM tree_registry_cache WHERE tree_id NOT IN (...)`. Add a query `deleteTreeRegistryEntriesNotIn(tree_ids: Collection<String>)`. |
| **R-70** | 🟡 | `TreeRegistry.kt:170-174` | `runCatching { decode collaborators_json }.getOrDefault(emptyList())` silently drops malformed JSON. | Replace with `try/catch (CancellationException) { throw } catch (t: Throwable) { /* log + emptyList */ }`; surface decode failures via the `TreeJournal` (paired with R-59). |
| **R-71** | 🟡 | `TreeRegistry.kt:142-143` (`CloudTreeRegistry`) | `clock: Clock = Clock.System`, `ownerUserId: UserId = UserId.DEFAULT` defaults. CLAUDE.md anti-pattern. | Drop the defaults; let DI provide them. The graph already has a `Clock`. `ownerUserId` becomes a `UserGraph` binding (auth-derived). |
| **R-72** | 🟢 | `TreeRegistry.kt:195` and `MachineProfileStore.kt:177` | `var attempt = 0; while (attempt < MAX) { attempt += 1 }` — use `repeat(MAX)`. | Cosmetic; combine with R-60's backoff. |
| **R-73** | 🟢 | `TreeRegistry.kt` `Result<T>` ladder | `result.onSuccess { return Result.success(...) }.onFailure { ... }` reads like Java. JetBrains: don't use `kotlin.Result` as a return type. | This is a **larger trend** (R-78). Defer to refactor PR; mention here so the next reviewer sees we tracked it. |

### 1.7 SqlTreeJournal + UserLibraryPluginScanner + UserLibraryWorkingTree

| id | sev | location | issue | approach |
|---|---|---|---|---|
| **R-74** | 🟠 | `SqlTreeJournal.kt:188-198` | `Json { ignoreUnknownKeys = true }` + `serializer<TreeJournalEvent>()` — `ignoreUnknownKeys` skips fields, NOT discriminators. Older binary reading newer event variant throws. | Build a `SerializersModule` with `polymorphicDefaultDeserializer(TreeJournalEvent::class) { TreeJournalEvent.Unknown.serializer() }` + add `@SerialName("__unknown") object Unknown : TreeJournalEvent`. ([kotlinx.serialization polymorphism docs](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/polymorphism.md)) Pin a regression test that reads a fixture with a fabricated `type` discriminator. |
| **R-75** | 🟢 | `SqlTreeJournal.kt:208-214` | `parseSnapshotKind` returns `Auto` for `else` — bad enum string silently becomes `Auto`. | Throw `IllegalStateException("unknown snapshot_kind '$raw'")`. |
| **R-76** | 🟠 | `UserLibraryPluginScanner.kt:74` | `runCatching { parser.parsePlugins(file) }.getOrElse { emptyList() }` — catches `CancellationException`; silent zero-plugin scan on parser bug. | `try/catch (CancellationException) { throw } catch (t)` + journal a `ScanFailure` event so debugging "why is X not in the list" is possible. |
| **R-77** | 🟡 | `UserLibraryWorkingTree.kt:36` and `UserLibraryPluginScanner.kt:53` | `Files.walk(root, FileVisitOption.FOLLOW_LINKS)` without symlink-cycle protection. | NIO **does** detect cycles when `FOLLOW_LINKS` is set ([Java NIO Files.walk](https://docs.oracle.com/javase/tutorial/essential/io/walk.html)) — `FileSystemLoopException` is thrown on cycle, which we currently let bubble out of `Files.walk` and crash the scanner. Two options: (a) drop `FOLLOW_LINKS` (many UL setups don't need it), (b) add a `visitFileFailed` handler that ignores `FileSystemLoopException`. **Pick (a)** for `UserLibraryPluginScanner` (don't follow links into other plugin install dirs) and **(b)** for `UserLibraryWorkingTree` (some users intentionally symlink racks into `User Library/`). Also share the same `isSkipped(components)` predicate the project tree uses, per cross-cutting. |

### 1.8 Cross-cutting / larger refactors

| id | sev | location | issue | approach |
|---|---|---|---|---|
| **R-78** | 🟡 | `BootstrapData`, `MachineProfileStore`, `TreeRegistry`, `SqlTreeJournal` | `kotlin.Result<T>` at API boundaries (JetBrains: don't). Three return idioms in this PR (throw, `Result<T>`, raw `Boolean`). | **Refactor PR after this one** — mark in CHANGELOG. Switch to `suspend fun` that throws + sealed result types where domain-specific errors matter (`RegisterOutcome.Ok / Conflict / Failed`). Out of scope here; would touch every consumer. |
| **R-79** | 🟡 | `OsProvider`, `SetupNav.formatRunsOn`, `HostPluginEntry.format`, `MachineEntry.os` | Stringly-typed surfaces (R-65). | Same refactor as R-65. |
| **R-80** | 🟢 | `JvmCloudMigrator`, `SnapshotPipeline`, `MachineProfileStore`, `TreeRegistry` | `Pair<String,String>`, hand-rolled imperative `for + mutableMap` loops where `groupingBy { ... }.reduce { ... }` would do. | Apply selectively where it shrinks code and improves clarity (`MachineProfileStore.composeUnion` is the obvious case). Keep the imperative ones if they're actually clearer. |
| **R-81** | 🟢 | DI: per-user services constructed by `UserGraphHolder` | `UserGraph` is a hand-rolled subgraph. `MigrationCoordinator`, `BootstrapData`, `JvmCloudMigrator`, `CloudMachineProfileStore`, `PluginChecklistViewModel` all want a per-user `CloudBackend`. | The DI doc §1.1 already plans for promoting `UserGraph` to a real `@GraphExtension` once it grows past one binding — **this PR triggers that growth**. The cleanest fix is to make `UserGraph` a real `@GraphExtension(UserScope::class)` and put the per-user services in it. **Defer** — too much for this PR. Compromise: bind everything bindable in `AppScope` (R-33, R-34, R-56), keep `JvmCloudMigrator` hand-constructed in `UserGraphHolder` since it genuinely needs the per-user `CloudBackend`. |
| **R-82** | 🟡 | `Settings.cloudMigrationComplete` doc | Claims it's "cleared by `--reset-first-run`" but `resetFirstRun()` only clears the three onboarding keys. | Fix the doc on `Settings.cloudMigrationComplete` (one line). Don't actually clear the flag — the kdoc on `resetFirstRun` says other config is intentionally untouched. |
| **R-83** | 🟢 | `CloudDocKey.Prefix` | Doesn't validate against blank or `..` like `CloudDocKey` does. | Add the same `require` clauses. |

---

## 2. Refactor opportunities flagged for future PRs

Three patterns recur enough that the right answer is a focused follow-up rather than spreading the work across this PR:

1. **`kotlin.Result` → typed sealed results + thrown exceptions** (R-78). Touches every interface in `shared/repository/`, every desktop call site. JetBrains' own guidance is unambiguous; the cost is "every reviewer will keep flagging this." Open issue, ship in a branch named `refactor/result-types`.
2. **Stringly-typed `format`/`os`** (R-65, R-79). Promote `PluginFormat` and a new `Os` enum end-to-end. Touches `MachineProfileStore`, `SetupNav`, `OsProvider`, the SQLDelight queries, and the wire format (with serializers). Worth doing in one go to avoid half-states.
3. **`UserGraph` → real `@GraphExtension(UserScope::class)`** (R-81). Pre-condition is letting `JvmCloudMigrator`, `CloudMachineProfileStore`, `MigrationCoordinator` move out of `AppScope` and into the per-user subgraph. The DI doc explicitly anticipates this. Worth doing once `SwappableSyncQueue` also moves over.

---

## 3. Sequenced commit plan

Six commits. Each is rebase-friendly and can be reviewed independently. Status: **commits 1 & 2 landed**; commits 3-6 pending.

### Commit 1 — durability: order CAS HEAD before timestamped + paginate list ✅ (`046ce48`)

**Files:** `DirectGcsBackend.kt`, `DirectGcsBackendTest.kt`
**Closes:** R-1, R-2, R-10
- Swapped order in `appendManifestHead`: HEAD CAS first; timestamped only on HEAD success.
- Added regression test confirming no orphan timestamped object on CAS conflict.
- Added `pageToken` looping via shared `listAllItems(prefix)` helper.
- Deleted dead `412 → true` chunk branch.

### Commit 2 — schema v11: registry-cache columns + FK cascades + scope_key NOT NULL ✅ (`3766e01`)

**Files:** `11.sqm` (new), `Catalog.sq`, `CatalogDb.kt`, `TreeRegistry.kt`, `DesktopAppGraph.kt`, multiple test files
**Closes:** R-66, R-67, R-68, R-69, R-71 (partial)
- New `11.sqm` with recreate-and-copy idiom: adds `created_at` + `created_by_host` columns, promotes `scope_key` to NOT NULL, adds `REFERENCES tree_registry_cache(tree_id) ON DELETE CASCADE` to dependent tree_* tables.
- New `deleteTreeRegistryEntriesNotIn` query.
- `refreshCache` combines upsert + stale-row delete in one transaction.
- Dropped `clock = Clock.System` and `ownerUserId = UserId.DEFAULT` defaults on `CloudTreeRegistry`; added `@Provides` bindings in `DesktopAppGraph`.
- New tests: `lookupRoundTripsCreatedAtAndCreatedByHost`, `refreshCacheDropsPhantomEntries`, `CatalogDbMigrationWalkTest` v11 assertions.

### Commit 3 — delete migrator system, unify cloud paths on v=2, move bootstrap into BootstrapData

> **Pivot 2026-05-07.** The pre-shipped product has zero v=1 users; the migrator's relocate path is dead code. Deleting the whole migration system simplifies the codebase substantially and resolves R-12–R-29 by elimination.

**Files (delete):**
- `shared/migration/` — entire module: `CloudMigrator.kt`, `JvmCloudMigrator.kt`, `MigrationLayout`, `CloudMigratorTest.kt`, `build.gradle.kts` references in `settings.gradle.kts` + dependent modules.
- `app-desktop/.../migration/MigrationCoordinator.kt`, `app-desktop/.../ui/migration/MigrationDialog.kt`, `MigrationCoordinatorTest.kt`.

**Files (modify):**
- `shared/cloud/.../DirectGcsBackend.kt`: drop the `when (kind) { Project -> "manifests/${treeId}/" else -> "trees/${kind}/${treeId}/manifests/" }` split in `manifestsPrefix`. Same for `lockPath`. All kinds use `<tenant>/trees/<kind>/<tree_id>/...`. (Project trees have no real cloud data yet, so this is a free re-layout.)
- `shared/repository/.../SettingsRepository.kt` + `app-desktop/.../PreferencesSettingsRepository.kt`: rename `Settings.cloudMigrationComplete` → `Settings.registrySeeded`. Same persisted key (no migration), updated kdoc that no longer lies about `--reset-first-run` (closes R-82).
- `app-desktop/.../bootstrap/BootstrapData.kt`: drop `@Suppress("UNUSED_PARAMETER") cloud` factory shim (closes R-30); add `seedRegistry()` that:
  - Returns `Result<List<TreeRegistryEntry>>` early-success when `Settings.registrySeeded == true`;
  - Reads `selectAllProjectIdentitiesWithName()` inside `withContext(ioDispatcher)` (closes R-14);
  - Builds `RegisterSpec` list (project entries + UL entry with `tt-ul-<hostId>` minted id; comment links #131 — closes R-18);
  - Calls `registry.registerAll(specs)`;
  - On success, calls `settings.markRegistrySeeded()`.
- All public `BootstrapData` methods return `Result<T>` for consistency (closes R-31).
- `@SingleIn(AppScope::class) @ContributesBinding(AppScope::class) @Inject` on `BootstrapData` so it's Metro-bound (closes R-33).
- Constructor takes `ioDispatcher: CoroutineDispatcher`, `hostId: String`, `clock: Clock` (no defaults — closes R-15 carry-over).
- Update `DesktopAppGraph`: drop `MigrationCoordinator` accessor; add `BootstrapData`/`hostId` provides as needed.
- Fix kdoc wiring example to compile (closes R-32).
- New `BootstrapDataTest` covering: idempotent on re-launch when `registrySeeded`, seeds projects + UL on first run, errors propagate as `Result.failure(...)`.

**Verification:** `./gradlew :shared:cloud:jvmTest :shared:repository:jvmTest :app-desktop:jvmTest :tests:integration:jvmTest`. Manual: spin up the desktop app twice — first launch seeds, second is a no-op.

**Estimated diff:** ~1,000 lines deleted, ~250 lines added.

### Commit 4 — MachineProfileStore: bind, IO dispatch, parallel reads, jittered backoff

**Files:** `MachineProfileStore.kt`, `MachineProfileStoreTest.kt`, `DesktopAppGraph.kt`
**Closes:** R-56, R-57, R-58, R-59, R-60, R-61, R-62, R-63, R-64, R-70
- `@SingleIn(AppScope::class) @ContributesBinding(AppScope::class) @Inject` on `CloudMachineProfileStore`. Constructor: `cloud`, `catalog`, `clock`, `ioDispatcher`. No defaults.
- `composeHostSlice` → `withContext(ioDispatcher) { ... }`.
- `composeUnion`: parallel `coroutineScope { refs.map { async { cloud.readDoc(it.key) } }.awaitAll() }`; replace `runCatching{}.getOrNull()` with safe try/catch (deferred — see R-78 — but at minimum log via Kermit).
- `publishHostSlice`: drop the read; write unconditionally per the kdoc.
- `registerMachine`: `repeat(MAX) { attempt -> ... delay(backoff(attempt)) }` with jitter.
- Delete `ListSerializerKeepImport`.
- `data class PluginKey(val name: String, val format: String)` (typed format deferred to #129).
- `@Immutable` on `HostPluginManifest`, `HostPluginEntry`, `MachineEntry`, `UnionedPluginManifest`.

**Verification:** new test `composeUnion_parallelizes_reads`, `publishHostSlice_does_not_read_first`, `registerMachine_backoff_jitters`.

### Commit 5 — PluginChecklistViewModel + Screen: bind, hoist, atomic state, injected probe

**Files:** `PluginChecklistViewModel.kt`, `PluginChecklistScreen.kt`, `PluginChecklistRoute.kt` (new), `PluginChecklistViewModelTest.kt`, `SetupNav.kt`, `DesktopAppGraph.kt`
**Closes:** R-34–R-50, R-52–R-55
- VM: `@ContributesIntoMap(AppScope::class) @ViewModelKey @Inject`. Inject `PluginPresenceProbe` (fun interface; impl in `app-desktop` wrapping `JvmPluginPresenceProbe`).
- `reprobe()` takes no args; runs the injected probe.
- All mutators via `_state.update { ... }`; mutex retained for refresh+reprobe transactional coalescing.
- Add `loadFailed: String?` and `isInitialLoad: Boolean` to `PluginChecklistUiState`.
- Wrap `viewModelScope.launch` bodies in `try/catch (CancellationException) { throw } catch`.
- `OsProvider.Default` (renamed) caches OS string in `lazy`.
- Apply OS filter to `alreadyInstalled`. Promote `formatRunsOn` to public on `SetupNav`.
- Filter `recentlyInstalled` against latest `installedKeys` before append.
- Drop the `dismiss()` no-op; route handles persistence directly.
- Screen: split into stateless `PluginChecklistScreen(state, onReprobe, onDismiss, modifier)` + `PluginChecklistRoute()` doing `metroViewModel<VM>()`. `LazyColumn` keys + weight + `enabled`. `collectAsStateWithLifecycle()`. Headline uses OS string. ui-shared wrappers. `derivedStateOf` for `total`.

**Verification:** new tests for `reprobe_filtersOsAtPending_AND_alreadyInstalled`, `loadFailedSurfacesOnException`, `recentlyInstalledRebuiltOnReprobe`.

### Commit 6 — small fixes + docs

**Files:** `ManifestMerger.kt`, `Lock.kt`, `CloudDocKey.kt`, `SqlTreeJournal.kt`, `UserLibraryWorkingTree.kt`, `UserLibraryPluginScanner.kt`
**Closes:** R-6, R-7, R-8, R-9, R-74, R-75, R-76, R-77, R-83
- ManifestMerger: drop `mergerHost`, replace bang-bang with `require`, kdoc note on `parentRev` decorative.
- `LeaseLock.heartbeatSeq` removed (no consumer).
- `CloudDocKey.Prefix`: same `require(!startsWith("/")) + require(!contains(".."))` invariants as `CloudDocKey`.
- `SqlTreeJournal`: polymorphic default fallback `Unknown` variant + regression test reading a synthetic `type=__future__` payload. `parseSnapshotKind` throws on unknown.
- `UserLibraryWorkingTree`: drop `FOLLOW_LINKS` OR add `visitFileFailed { _, _: FileSystemLoopException -> SKIP_SUBTREE }`. `UserLibraryPluginScanner.scan` `runCatching` → `try/catch (CancellationException)`.
- Replace `BasicFileAttributes::class.java` with the imported-type idiom.

**Verification:** `./gradlew jvmTest spotlessCheck`.

---

## 4. Out-of-scope follow-up issues (filed)

- [#127](https://github.com/srMarlins/sketchbook/issues/127) — `DirectGcsBackend`: HEAD as pointer (R-3); resumable upload that resumes (R-4); cross-volume temp staging (R-5).
- [#128](https://github.com/srMarlins/sketchbook/issues/128) — Refactor `kotlin.Result` → sealed result types + thrown exceptions across `shared/repository` (R-78).
- [#129](https://github.com/srMarlins/sketchbook/issues/129) — Promote `PluginFormat` + introduce `Os` enum end-to-end (R-65, R-79).
- [#130](https://github.com/srMarlins/sketchbook/issues/130) — `UserGraph` → real `@GraphExtension(UserScope::class)` once `SwappableSyncQueue` migrates over (R-81).
- [#131](https://github.com/srMarlins/sketchbook/issues/131) — UL tree id convergence post-v1.2 shared sync (R-18 longer term).
- [#132](https://github.com/srMarlins/sketchbook/issues/132) — Audit pre-PR-125 `runCatching` at suspend boundaries (paired with R-76 fix).

---

## 5. Verification matrix

| Commit | Unit | Integration | Manual |
|---|---|---|---|
| 1 ✅ | `appendManifestHead_doesNotLeaveOrphan_onCASConflict`; multi-page pagination | `SyncRoundTripTest` | n/a |
| 2 ✅ | `CatalogDbMigrationWalkTest` 10→11; `lookupRoundTripsCreatedAtAndCreatedByHost`; `refreshCacheDropsPhantomEntries` | n/a | n/a |
| 3 | `BootstrapDataTest`: idempotent on `registrySeeded`, seeds projects + UL on first run, errors propagate | `:tests:integration:jvmTest` (no migration UX to break) | desktop launch twice — first seeds, second no-op |
| 4 | `composeUnion_parallelizes_reads`, `publishHostSlice_does_not_read_first`, `registerMachine_backoff_jitters` | n/a | n/a |
| 5 | `reprobe_filtersOs`, `loadFailedSurfacesOnException`, `recentlyInstalledRebuiltOnReprobe`, stateless-screen screenshot with `loadFailed` | n/a | open Settings → Plugin checklist; trigger fake-store exception |
| 6 | `unknownEventDecodesAsUnknown`, `parseSnapshotKindThrowsOnUnknown`, `userLibraryWalkSurvivesSymlinkLoop` | n/a | n/a |

Final check before pushing: `./gradlew jvmTest spotlessCheck detekt build`.

---

## 6. Estimated diff size

- Commit 1 ✅: +145 / -58 lines (`046ce48`)
- Commit 2 ✅: +403 / -23 lines (`3766e01`)
- Commit 3 (delete migrator + unify paths + bootstrap): **~1,000 lines deleted**, ~250 added (net deletion)
- Commit 4 (MachineProfileStore): ~180 lines
- Commit 5 (PluginChecklist): ~250 lines
- Commit 6 (polish): ~150 lines

Net result: the PR shrinks rather than grows. The original 7.8K-line PR drops by ~750 lines after commit 3's deletions, then adds back ~580 lines across commits 4–6. End state: a smaller, simpler diff than the version under review.
