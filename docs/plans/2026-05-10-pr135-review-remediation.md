# PR #135 review remediation plan

**Date:** 2026-05-10
**Branch:** `feat/firebase-migration`
**Scope:** Address every comment on https://github.com/srMarlins/sketchbook/pull/135 — gemini-code-assist's review and the four owner reviews (perf, kotlin/coroutines, comprehensive, inline). Any refactor is on the table; goal is the cleanest, most performant, well-engineered solution.

> Reading order: §1 inventories every distinct issue and notes which are already addressed by recent commits (`2d82393`, `8171907`, `b02a735`, `a34bdcb`, `b282372`, `4e75866`). §2 is the ordered work plan grouped by theme. §3 is the merge gate / test plan / out-of-scope follow-ups.

---

## 1. Comment inventory + current status

Status legend: ✅ already fixed on this branch | ⚠️ partially addressed | ❌ open | 🗑️ won't fix (closed with note).

### 1.1 Critical correctness — must land before merge

| # | Issue | Source | Status |
|---|---|---|---|
| **K1** | `PullPoller.pollOnce` swallows a transient `readManifest` failure with `continue` then advances `cloud_head_rev` past the gap — **rev permanently lost**. | comprehensive C1; kotlin S5 | ❌ open |
| **K2** | `readManifest(rev)` internally calls `listManifests` — `PullPoller` ends up doing O(N²) list ops on cold catch-up. | perf #1; comprehensive C4 | ❌ open |
| **K3** | `FirebaseBlobStore.listManifests` doesn't paginate — silent truncation at 1000 manifests; `nextPageToken` ignored. | perf #6; comprehensive C5 | ❌ open |
| **K4** | `LeasedLockRepository.byUuid = mutableMapOf<>()` is **not thread-safe**; concurrent `observe(uuid)` can leak duplicate Firestore listeners. | perf #3; kotlin C3; comprehensive H/inline | ⚠️ partial — `synchronized(byUuid)` guards `get`/`resetState` (commit `a34bdcb`) ✅; **but** the doc-string contract "ConcurrentHashMap / computeIfAbsent" wasn't taken, and there is no test pinning concurrent first-observe behaviour. Acceptable as-is; mark resolved in §2 with a small test added. |
| **K5** | `LeasedLockRepository` listeners never restart on UID transitions (signed-out → first observe → listener never starts; user A→B leak). | comprehensive C2; perf #3 | ✅ commit `a34bdcb` wires `userIdFlow.drop(1).collectLatest { resetState() }` and short-circuits `startListener` when uid is null. **Remaining:** no regression test for the signed-out-then-sign-in case. Add in §2. |
| **K6** | `DesktopAuthSession` silent-restore is broken: inner `_state` stays SignedOut after `tryRestore`; collector wipes cached identity on the initial replay. | kotlin C2; comprehensive (required #1) + inline | ❌ open |
| **K7** | `revokeMySession` succeeds server-side but the gitlive SDK on this device still holds the (now-revoked) seeded `FirebaseUserImpl` — listener refresh storm after sign-out. | comprehensive C3 | ✅ commit `2d82393`: `FirebaseAuthSession.signOut` calls `sdkClearSession` → `FirebaseSdkBootstrap.clearSession` (gitlive `auth.signOut()` + `FirebaseApp.delete()` + platform storage clear). Test gap: `FirebaseAuthSessionTest` still doesn't pass `cloudFunctions` or `sdkClearSession`. Add in §2. |
| **K8** | `HttpClient(CIO)` configured with **no timeouts** — sign-out, GCS upload, every refresh path can stall forever. | perf #4 | ❌ open |
| **K9** | `JwksGoogleIdTokenVerifier.verify` does blocking `JWKSet.load` from a non-suspend interface on whatever dispatcher the caller used. Also: cache miss on rotation → up to 1h auth outage; no single-flight on the load. | perf #5/#11; kotlin S4; comprehensive H3 | ✅ commit `8171907`: interface is now `suspend`, `JwksCache.get()` hops to `Dispatchers.IO`. **Remaining:** cache-miss force-refresh + single-flight loader still aren't implemented; rotation-within-TTL is still a 1h outage. Address in §2. |

### 1.2 High-severity — should land before merge

| # | Issue | Source | Status |
|---|---|---|---|
| **H1** | `runCatching` swallows `CancellationException` across the new sync path (`SyncCoordinator`, `FirestoreMetadataStore.acquireLock/refreshLock/releaseLock`, `PullPoller`, `SnapshotPipeline.finally` + `writeTreeHeadToFirestore`, `FirebaseAuthSession.signOut` cf.revokeMySession, `SwappableSyncQueue.buildGcsQueue`). | kotlin C1, C4 | ⚠️ `writeTreeHeadToFirestore` rethrows CancellationException explicitly (commit `4e75866`). All other sites are still naked `runCatching`. |
| **H2** | `FirebaseAuthSession.currentTokens()` reads `tokens.value` **after** `refresh()` releases the mutex → `ClassCastException` if a `signOut` interleaves. Should be `AuthSessionExpired`. | perf hint; kotlin S1; comprehensive H1 | ❌ open |
| **H3** | `FirebaseAuthSession.tryRestore` / `refresh` clear the refresh token on **any** identity-toolkit failure — including network blips → user gets force-signed-out on offline launch / transient outage. | kotlin S3 | ❌ open |
| **H4** | `LeasedLockRepository.startHeartbeat` only wired from `forceTake`. `SnapshotPipeline.acquireLock` callers never get heartbeats — lease expires under 15-minute saves. Docstring claims "Heartbeats refresh on a 5-minute cadence while we hold a lease" but the contract isn't honored. | kotlin N7 | ❌ open |
| **H5** | `LeasedLockRepository.Stale` / `HeldByOther` never re-derive without a Firestore write. A natural lease expiry leaves stale UI forever. | kotlin S2 | ❌ open |
| **H6** | `SyncCoordinator.handleTreeEntry` has no per-uuid de-duplication — a second listener emission while a `pollOnce` is in flight kicks a second concurrent `pollOnce(uuid)`, racing SQL writes. | comprehensive H4 | ❌ open |
| **H7** | `SyncCoordinator` listener crash is terminal until the next UID flip — a transient network blip kills sub-second sync. | comprehensive (inline `SyncCoordinator.kt:68`); kotlin recommendations | ⚠️ commit `b282372` adds the head_rev cache but doesn't add backoff/retry. Open. |
| **H8** | `GcsSyncQueue.runPipeline` always passes `lastKnownManifest = null` → every push full-hashes the project tree and pays a HEAD-per-blob (the unchanged-file diff is bypassed). Regression vs. pre-Firebase queue. | comprehensive H5 | ❌ open |
| **H9** | Production binary still ships `GcsAuth` (jvmMain) + `ServiceAccountKey` (commonMain). The only consumer is `FirebaseBlobStoreTest`. Contradicts a load-bearing security-commitment justification of the migration. | comprehensive (required #2); inline `GcsAuth.kt:34` | ❌ open |
| **H10** | `FirebaseSdkBootstrap` reaches `FirebaseAuthSession` through `DesktopAuthSession.unwrap()` + `as? DesktopAuthSession ?: error(...)` — runtime cast couples `:shared:auth` to `app-desktop`. | comprehensive (required #3); inline `DesktopAppGraph.kt:243` | ✅ commit `2d82393` reworks DI: `FirebaseAuthGraph` data class is provided via `provideFirebaseAuthGraph`; `provideFirebaseSdkBootstrap` reads `firebaseAuthGraph.bootstrap`. No cast, no `unwrap()`. (DesktopAuthSession still has `unwrap()` but nothing in DI uses it — see §2 cleanup.) |
| **H11** | `MetadataStore` is `@SingleIn(AppScope::class)` but the gitlive process-singleton SDK carries cross-UID state — sign-in-as-different-user can hit permission-denied on the first Firestore RPC. | comprehensive (followup); inline `DesktopAppGraph.kt:249` | ✅ Commit `2d82393` adds `FirebaseSdkBootstrap.clearSession()` (called from `FirebaseAuthSession.signOut`) which `FirebaseApp.delete()`s and resets `initialized`. The next `ensureInitialized` reseeds with the new uid. Confirm with a UID-flip test in §2. |

### 1.3 Medium

| # | Issue | Source | Status |
|---|---|---|---|
| **M1** | `FirestoreMetadataStore.acquireLock` / `refreshLock` / `releaseLock` map every exception (permission denied, network down, true contention) to `false`. | perf #12 | ❌ open |
| **M2** | `FirebaseBlobStore.appendManifestHead` writes manifest twice (timestamped + HEAD pointer); with Firestore now authoritative for head, the HEAD pointer file is redundant. | perf #8; comprehensive Low | ❌ open (follow-up scope) |
| **M3** | `SyncStateStore.markCloudHead` called once per pulled snapshot inside the per-pull loop → full reactive cascade. Should be one `markCloudHead(uuid, latestRev)` after the batch. | perf #9 | ❌ open |
| **M4** | `PullPoller.pollOnce` reads manifests sequentially (50 × RTT). Bounded `Semaphore` + `async`. | perf #10 | ❌ open |
| **M5** | `FirestoreMetadataStore.observeCollection` re-emits the **full** collection on every change; no `DocumentChange` diff surface. | perf #14; comprehensive (Medium inline) | ❌ open (mitigated for perf by `b282372`'s head_rev cache; the design improvement remains) |
| **M6** | `FirebaseSdkBootstrap.ensureInitialized` fast path re-encodes JSON + `platform.store` on every Firestore RPC. | perf #15; comprehensive (Medium inline) | ❌ open |
| **M7** | `FirestoreMetadataStore.updateDoc` retry budget on contention is unbounded. | perf #16 | ❌ open |
| **M8** | `MachineDoc` / `LockDoc` / `TreeDoc` mix camelCase + snake_case wire shapes — Firestore field names are case-sensitive; future cross-runtime readers (Admin SDK in JS) will trip. | comprehensive M1 | ❌ open |
| **M9** | `OAuthFlow.refresh()` / `OAuthClient.refresh()` is dead code post-Firebase migration. | comprehensive M2 + L (inline); kotlin (n/a) | ❌ open |
| **M10** | `functions/.gitignore` lacks service-account-JSON patterns. | comprehensive M4 | ❌ open |
| **M11** | `revokeMySession` URL hardcoded as the 1st-gen `cloudfunctions.net` alias; canonical is `*.run.app`. Documentation/runbook gap on what revokeMySession actually invalidates (refresh, not ID, tokens). | comprehensive M5 | ❌ open (mostly docs) |
| **M12** | `storage.rules` has no total-storage cap per user. Known platform gap; requires a Cloud Function. | comprehensive M6 | ❌ open (track as follow-up only; out of scope of this PR) |
| **M13** | `firestore.rules` allows arbitrary shape on `locks/{treeId}`. Cheap `hasOnly` guardrail. | comprehensive M7 | ❌ open |
| **M14** | CI runs no Firestore-rules tests / no `functions/` lint / no emulator integration. | comprehensive M8 | ⚠️ tracked as deferred follow-up in design doc; not blocking. |
| **M15** | `FirestoreMetadataStore.updateDoc` `transform: suspend (T?) -> T` invites non-idempotent side effects inside a Firestore retry-loop. | comprehensive M9 | ❌ open |
| **M16** | `OAUTH_CLIENT_ID` is `REPLACE_ME...` in source; runtime override via system property. Should fail-fast in prod. | comprehensive M10 | ❌ open |
| **M17** | `SnapshotPipeline`: 3 Firestore RTTs around acquireLock (acquire + getDoc + setDoc backfill). | perf #7; kotlin S6; comprehensive (followup) | ✅ commit `b02a735` widens `acquireLock` to take `holderName` so the CAS writes the full doc; `SnapshotPipeline.run` + `LeasedLockRepository.forceTake` no longer issue the backfill setDoc. |
| **M18** | `FirestoreMetadataStore.observeDoc` / `observeCollection` open a fresh Firestore listener per subscriber. | comprehensive (medium inline) | ❌ open (consumer-side fix; document the contract) |
| **M19** | `hostIdentity()` is called 4× during graph construction; each call does disk I/O + `InetAddress.getLocalHost()`. | comprehensive (medium inline) | ❌ open |
| **M20** | `UserGraphHolder.cloudBackend` has no consumer; `SwappableSyncQueue` builds its own `FirebaseBlobStore`. | comprehensive (medium inline); kotlin N9 | ❌ open (deferred follow-up acknowledged in design doc) |
| **M21** | `provideSnapshotRepository`'s `materialize` lambda casts `SyncQueue as? SwappableSyncQueue` to reach `currentMaterializer`. | comprehensive (low/deferred); kotlin (n/a) | ❌ deferred to `CloudScope` PR (acknowledged in-code) |

### 1.4 Low / nits

| # | Issue | Source | Status |
|---|---|---|---|
| **N1** | `InMemoryMetadataStore.setDoc` is O(N) map copy per write. | perf #17 | ❌ trivial fix |
| **N2** | `IdentityToolkitClient.signInWithGoogleIdToken` / `refresh` build URL-form bodies via string concat. | perf #18; kotlin N6 | ❌ open |
| **N3** | `CloudFunctionsClient` reads `response.bodyAsText()` twice on non-OK responses. | perf #19 | ✅ already cached via `runCatching { response.bodyAsText() }` on the error path — but only on the throw path. Verify in §2. |
| **N4** | No connection-pool size tuning on shared `HttpClient(CIO)`. | perf #20 | 🗑️ defer to first measured workload. |
| **N5** | `firestore.indexes.json` is empty. | perf #21 | 🗑️ correct for current queries; document the deferral. |
| **N6** | `LeasedLockRepository.startHeartbeat` heartbeat fails permanently on transient network blip (no retry). | perf non-perf observation; kotlin N7 | ❌ open |
| **N7** | `FirebaseBlobStore.getBlob` always drains to a temp file — small blobs would benefit from a heap path. | perf non-perf observation | 🗑️ acknowledged trade-off; defer. |
| **N8** | `LeasedLockRepository.forceTake` release-then-acquire is not atomic. | kotlin S7 | 🗑️ acknowledged by class comments; user-driven semantics. Add doc note instead. |
| **N9** | `MutableStateFlow<SessionTokens>` in `FirebaseAuthSession` is unobserved — could be `AtomicReference`. | kotlin N1 | 🗑️ stylistic; defer. |
| **N10** | `InMemoryMetadataStore.observeCollection` emits on every unrelated write. | kotlin N2 | ❌ trivial — add `distinctUntilChanged`. |
| **N11** | Test naming inconsistent (backtick vs camelCase). | kotlin N3 | 🗑️ stylistic; defer (separate cleanup PR). |
| **N12** | `JvmFirebasePlatform.store` shadows the property name. | kotlin N4 | ❌ trivial rename. |
| **N13** | `DesktopAuthSession.signIn` double-saves identity. | kotlin N8 | ❌ trivial removal after K6. |
| **N14** | `JvmFirebasePlatform.getDatabasePath` could collide between same-named app launches. | comprehensive low | 🗑️ multiple JVM launches isn't a supported scenario; document only. |
| **N15** | `JvmBlobCache.getOrFetch` TOCTOU window on concurrent miss for same hash. | comprehensive low | ❌ small fix — per-hash `Mutex`. |
| **N16** | `GcsSyncQueue.recordConflictJournal` uses `Clock.System.now()` directly instead of injected clock. | comprehensive low | ❌ trivial. |
| **N17** | `KeyringTokenStore.read` silently returns null on `BackendNotSupportedException`. | comprehensive low | ❌ one-line stderr. |
| **N18** | `OAuthClient` callback handler doesn't guard against double `complete()`. | comprehensive low | 🗑️ harmless; document only. |
| **N19** | `MachineDoc.last_seen_at` exists but nothing writes it. | comprehensive low | ❌ add `TODO()` comment so a grep surfaces it. |
| **N20** | `docs/runbooks/firebase-deploy.md` doesn't mention `firebase deploy --only functions`. | comprehensive low | ❌ doc fix. |
| **N21** | `firestore.indexes.json` comment about gitlive 2.4.0 emulator drift is aspirational (no test in CI). | comprehensive low | ❌ doc fix. |
| **N22** | `LockDoc.heartbeatSeq` is documented as unused but written on every acquire/refresh. | comprehensive low | ❌ YAGNI: drop the field. |
| **N23** | `FakeCloudBackend` duplicated between `:shared:sync:commonTest` and `:tests:integration:jvmTest`. | comprehensive low | 🗑️ defer to a test-fixtures PR. |
| **N24** | `FirebaseBlobStore.listManifests` parses entire response via `parseToJsonElement`. | perf #13 | ❌ open — folded into K3. |
| **N25** | `OAuthClient.parseSubAndEmail` parses an unverified JWT payload. | comprehensive M3 | ❌ doc-comment only. |

---

## 2. Work plan

Grouped by theme; each item lists exact files + a precise change. Numbering matches §1 IDs for traceability. Order assumes the most invasive refactors (cancellation hygiene, MetadataStore signature changes) land first so subsequent fixes plug into the cleaned-up surface.

### Phase A — Foundation: cancellation hygiene + HTTP discipline

Cancellation correctness is a load-bearing precondition for several other fixes (any new retry loop, any new mutex). HTTP timeouts are a global concern that should land before we layer per-call backoff.

#### A1. [H1] Centralize `runCatching`-that-rethrows-cancellation

**Files:**
- `shared/sync/src/jvmMain/kotlin/com/sketchbook/sync/SyncCoordinator.kt:62`
- `shared/cloud/src/jvmMain/kotlin/com/sketchbook/cloud/metadata/FirestoreMetadataStore.kt:142, 179, 210` (the three `runCatching { firestore.runTransaction ... }`)
- `shared/sync/src/commonMain/kotlin/com/sketchbook/sync/PullPoller.kt:35, 43`
- `shared/sync/src/commonMain/kotlin/com/sketchbook/sync/SnapshotPipeline.kt:199` (lock-release in `finally`)
- `shared/auth/src/jvmMain/kotlin/com/sketchbook/auth/firebase/FirebaseAuthSession.kt:152, 162` (revoke + sdkClearSession)
- `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/repo/SwappableSyncQueue.kt:132-198`

**Change:** introduce a single helper in `:core` (or `:shared:sync` commonMain):

```kotlin
internal inline fun <T> Result.Companion.guarded(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (c: kotlinx.coroutines.CancellationException) { throw c }
  catch (t: Throwable) { Result.failure(t) }
```

Replace every site flagged above. Where the code uses `runCatching { ... }.getOrElse { false }`, prefer:

```kotlin
return try {
    firestore.runTransaction<Boolean> { ... }
} catch (c: CancellationException) { throw c }
  catch (t: Throwable) { false }
```

so the cancellation rethrow is local + the lock semantics (return `false` on real failure) stay readable. **For `acquireLock`/`refreshLock`** ladder a typed error too — see M1.

**Verification:** add a unit test that cancels the calling scope mid-transaction in a fake `MetadataStore` and asserts `CancellationException` propagates rather than `false` being returned.

#### A2. [K8] Configure HTTP timeouts; dedicated short-timeout client for revokeMySession

**Files:** `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt:317` (single `HttpClient(CIO)` binding) + `provideCloudFunctionsClient`.

**Change:**

```kotlin
@Provides @SingleIn(AppScope::class)
fun provideHttpClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
    expectSuccess = false
}

@Provides @Named("revokeMySession") @SingleIn(AppScope::class)
fun provideRevokeHttpClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        connectTimeoutMillis = 2_000
        requestTimeoutMillis = 5_000
    }
}
```

Inject the named client into `CloudFunctionsClient`. Sign-out tail latency capped at ~5 s on a regional outage.

**Why dedicated client:** the main client backs streaming uploads where 15 s request timeout is fine. The revoke call is a single round-trip; we want a tighter budget so a slow Cloud Function doesn't make sign-out feel laggy.

**Verification:** test that `CloudFunctionsClient.revokeMySession` against a fake `MockEngine` that delays 10 s throws within ~5 s.

---

### Phase B — Critical correctness fixes

#### B1. [K1] PullPoller: stop at the first hole; never advance past a missing rev

Currently:

```kotlin
val manifest = runCatching { cloud.readManifest(uuid, rev) }.getOrNull() ?: continue
```

is a silent rev-drop. Two coordinated changes:

**Files:**
- `shared/sync/src/commonMain/kotlin/com/sketchbook/sync/PullPoller.kt:31-64`
- `shared/sync/src/jvmMain/kotlin/com/sketchbook/sync/SyncCoordinator.kt:85-105`

**Change to `PullPoller.pollOnce`:** return type stays `List<Snapshot>` but the contract becomes "contiguous successful prefix only". On the first `readManifest` failure (after the cancellation rethrow from A1), break out of the loop; `out` carries everything pulled up to the gap.

```kotlin
val manifest = try {
    cloud.readManifest(ref)   // path-aware overload from B2
} catch (c: CancellationException) {
    throw c
} catch (t: Throwable) {
    // Stop: never advance the watermark past an unread rev. Caller's next
    // listener emission will re-issue pollOnce(uuid, sinceRev = lastSuccess)
    // and naturally retry the failed read.
    break
}
```

**SyncCoordinator** keeps its `markCloudHead` loop unchanged — by contract, anything in `pulled` is contiguous from `sinceRev + 1`, so the final `markCloudHead(uuid, pulled.last().rev.value)` (after fix M3) is safe.

**Test:** new `SyncCoordinatorTest` case: `FakeCloudBackend` returns manifests for revs 5–7 with `readManifest(6)` throwing once. Assert `cloud_head_rev` advances to 5 only, and the next emission re-pulls 6+7.

#### B2. [K2] Path-aware `readManifest` to break the O(N²)

The PR description's "Out of scope" list doesn't mention this; it's a small interface addition.

**Files:**
- `shared/cloud/src/commonMain/kotlin/com/sketchbook/cloud/CloudBackend.kt` — add overload.
- `shared/cloud/src/jvmMain/kotlin/com/sketchbook/cloud/FirebaseBlobStore.kt:295-313` — implement.
- `shared/sync/src/commonMain/kotlin/com/sketchbook/sync/PullPoller.kt:39-62` — call new overload with the `ManifestRef` from outer `listManifests`.
- `shared/sync/src/commonTest/kotlin/com/sketchbook/sync/FakeCloudBackend.kt` and `tests/integration/.../FakeCloudBackend.kt` — implement the new method (delegate).

**Interface:**

```kotlin
interface CloudBackend {
    /** Read a manifest whose Storage path is already known (returned by [listManifests]).
     *  Preferred over [readManifest] when the caller has the [ManifestRef] in hand. */
    suspend fun readManifest(ref: ManifestRef): Manifest

    /** Legacy overload — resolves the path via [listManifests] internally.
     *  O(N) per call; new code should use [readManifest] with a ref. */
    suspend fun readManifest(uuid: ProjectUuid, rev: SnapshotRev): Manifest
}
```

Implementation in `FirebaseBlobStore`:

```kotlin
override suspend fun readManifest(ref: ManifestRef): Manifest {
    val response = http.get(objectUrl(ref.path)) {
        authHeader()
        parameter("alt", "media")
    }
    if (!response.status.isSuccess) throw remoteFailure(response, "GET ${ref.path}")
    return json.decodeFromString(Manifest.serializer(), response.bodyAsText())
}
```

`PullPoller` now does **1 list + N gets** for N revs. The old `readManifest(uuid, rev)` stays for backwards-compatibility with `SnapshotRepository.materialize` paths that don't carry a ref.

#### B3. [K3] Paginate `listManifests`; switch to typed JSON; server-side `startOffset`

**File:** `shared/cloud/src/jvmMain/kotlin/com/sketchbook/cloud/FirebaseBlobStore.kt:315-332` + new types.

**Three coordinated changes:**

1. Define `@Serializable` GCS list response (replaces `parseToJsonElement` — addresses N24):
   ```kotlin
   @Serializable
   private data class GcsListPage(
       val items: List<GcsObject> = emptyList(),
       val nextPageToken: String? = null,
   )

   @Serializable
   private data class GcsObject(
       val name: String,
       val generation: String,
       val size: String? = null,
   )
   ```

2. Loop over `nextPageToken` until exhausted (cap to e.g. 50 pages = 50k objects as a circuit breaker).

3. Use GCS `startOffset` to filter `sinceRev` server-side. Manifest filenames are `<rev:08d>-<ts>-<host>.json`, so `startOffset = prefix + "%08d-".format(sinceRev.value + 1)` cuts the wire traffic.

```kotlin
override suspend fun listManifests(
    uuid: ProjectUuid,
    sinceRev: SnapshotRev?,
): List<ManifestRef> {
    val prefix = "$tenantPrefix/manifests/${uuid.value}/"
    val startOffset = sinceRev?.let { "$prefix${"%08d".format(it.value + 1)}-" }
    val out = mutableListOf<ManifestRef>()
    var pageToken: String? = null
    var pages = 0
    do {
        val response = http.get(listUrl()) {
            authHeader()
            parameter("prefix", prefix)
            if (startOffset != null) parameter("startOffset", startOffset)
            parameter("maxResults", "1000")
            if (pageToken != null) parameter("pageToken", pageToken)
        }
        if (!response.status.isSuccess) throw remoteFailure(response, "LIST $prefix")
        val page = json.decodeFromString(GcsListPage.serializer(), response.bodyAsText())
        out += page.items.mapNotNull { it.toManifestRef(prefix) }
        pageToken = page.nextPageToken
        pages++
    } while (pageToken != null && pages < 50)
    return out.sortedBy { it.rev }
}
```

**Test:** `FirebaseBlobStoreTest` adds a `MockEngine` route that returns `nextPageToken` once → assert second page is fetched + merged.

#### B4. [K6] Fix DesktopAuthSession silent restore + add a test

Two race conditions to close (see `DesktopAuthSession.kt:37-66` analysis):

**Files:**
- `shared/auth/src/jvmMain/kotlin/com/sketchbook/auth/firebase/FirebaseAuthSession.kt:83-99` (`tryRestore`)
- `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/auth/DesktopAuthSession.kt:37-65`
- New: `app-desktop/src/jvmTest/kotlin/com/sketchbook/desktop/auth/DesktopAuthSessionTest.kt`

**Approach:** the right fix is "have the inner session emit `SignedIn` itself on successful restore". `FirebaseTokens.email` was added in Phase 2 (`refresh` returns null email; `signInWithIdp` returns the email) — but for restore, neither endpoint returns email. Pull email from `PrefsIdentityStore.load()` BEFORE the inner runs `tryRestore`, then pass it in.

Cleanest shape:

```kotlin
// FirebaseAuthSession.kt
suspend fun tryRestore(emailHint: String? = null): Boolean =
    refreshMutex.withLock {
        val rt = tokenStore.read() ?: return@withLock false
        identityToolkit.refresh(rt).fold(
            onSuccess = { fresh ->
                commitTokens(fresh)
                if (fresh.refreshToken != rt) tokenStore.write(fresh.refreshToken)
                _state.value = AuthState.SignedIn(
                    userId = UserId(fresh.uid),
                    email = fresh.email ?: emailHint,
                )
                true
            },
            onFailure = { error ->
                // See B5 — only clear on terminal errors, not transient ones.
                if (error.isTerminalRefreshError()) tokenStore.clear()
                false
            },
        )
    }
```

```kotlin
// DesktopAuthSession.kt
init {
    scope.launch {
        // drop(1) skips the initial SignedOut replay; only forward real transitions.
        inner.state.drop(1).collect { innerState ->
            when (innerState) {
                is AuthState.SignedIn -> {
                    identityStore.save(innerState)
                    _state.value = innerState
                }
                AuthState.SignedOut -> {
                    identityStore.clear()
                    _state.value = AuthState.SignedOut
                }
            }
        }
    }
    scope.launch {
        val restored = inner.tryRestore(emailHint = cachedIdentity?.email)
        if (cachedIdentity != null && !restored) {
            identityStore.clear()
            _state.value = AuthState.SignedOut
        }
        // If restored: inner.state already flipped SignedIn via the collector above; nothing to do here.
    }
}
```

Bonus cleanup (N13): drop the redundant `identityStore.save(it)` in `DesktopAuthSession.signIn` — the inner-state forwarder now handles it.

**New test:** `DesktopAuthSessionTest` with a fake `FirebaseAuthSession` and `PrefsIdentityStore`. Cases:
- Cached identity + tryRestore succeeds → final state is `SignedIn(uid, email)` matching cache.
- Cached identity + tryRestore fails terminally → `SignedOut`, identity cleared.
- Cached identity + tryRestore fails transiently → `SignedOut`, identity **preserved** (see B5).
- No cached identity → optimistic `SignedOut`, tryRestore false → stays `SignedOut`.

#### B5. [H3] Branch refresh-token wipe on terminal vs transient

**Files:**
- `shared/auth/src/jvmMain/kotlin/com/sketchbook/auth/firebase/FirebaseAuthSession.kt:79-89, 187-211`
- Use `IdentityToolkitException.errorCode` (already typed — commit `c006bfa`).

Define:

```kotlin
private val TERMINAL_REFRESH_ERROR_CODES = setOf(
    "INVALID_REFRESH_TOKEN",
    "TOKEN_EXPIRED",
    "USER_DISABLED",
    "USER_NOT_FOUND",
)

private fun Throwable.isTerminalRefreshError(): Boolean =
    this is IdentityToolkitException && errorCode in TERMINAL_REFRESH_ERROR_CODES
```

Apply at the two clear-on-failure sites: `tryRestore` (B4 wires emailHint) and `refresh()`. Transient failures throw `AuthSessionExpired` for the current attempt **without** persistent state loss; the next user retry hits the same refresh-token-on-disk and recovers when connectivity returns.

**Test:** new `FirebaseAuthSessionTest` case — `identityToolkit.refresh` returns `Result.failure(IOException("offline"))` → `tokenStore.clear` is NOT called.

#### B6. [H2] Eliminate `currentTokens()` cast-after-mutex race

**File:** `shared/auth/src/jvmMain/kotlin/com/sketchbook/auth/firebase/FirebaseAuthSession.kt:178-211`

**Change:** `refresh()` already produces the fresh `FirebaseTokens` inside the mutex; return them.

```kotlin
suspend fun currentTokens(): FirebaseTokens {
    val snap = tokens.value
    if (snap is SessionTokens.Active && clock.now() < snap.expiresAt) {
        return snap.toFirebaseTokens()
    }
    return refresh()   // returns FirebaseTokens
}

private suspend fun refresh(): FirebaseTokens =
    refreshMutex.withLock {
        val snap = tokens.value
        if (snap is SessionTokens.Active && clock.now() < snap.expiresAt) {
            return@withLock snap.toFirebaseTokens()
        }
        val rt = (snap as? SessionTokens.Active)?.refreshToken
            ?: tokenStore.read()
            ?: run {
                _state.value = AuthState.SignedOut
                throw AuthSessionExpired()
            }
        val fresh = identityToolkit.refresh(rt).getOrElse { error ->
            if (error.isTerminalRefreshError()) {
                tokenStore.clear()
                tokens.value = SessionTokens.None
                _state.value = AuthState.SignedOut
            }
            throw AuthSessionExpired()
        }
        commitTokens(fresh)
        if (fresh.refreshToken != rt) tokenStore.write(fresh.refreshToken)
        fresh
    }
```

(`idToken()` becomes `currentTokens().idToken` — already the case.)

**Test:** add a multi-threaded case in `FirebaseAuthSessionTest` that calls `currentTokens()` while a concurrent `signOut()` is in flight, asserts no `ClassCastException`.

#### B7. [K9] JWKS: single-flight refresh + cache-miss force-refresh

**File:** `shared/auth/src/jvmMain/kotlin/com/sketchbook/auth/firebase/GoogleIdTokenVerifier.kt:115-144`

Three coordinated changes to `JwksCache`:

```kotlin
private class JwksCache(
    private val jwksUri: URI,
    private val clock: Clock,
    private val ttl: Duration,
    private val loader: suspend (URI) -> JWKSet,
) {
    private data class Entry(val jwks: JWKSet, val fetchedAt: Instant)
    private val ref = AtomicReference<Entry?>(null)
    private val loadMutex = Mutex()

    /** Returns the cached set if still fresh; otherwise single-flights a reload. */
    suspend fun get(): JWKSet {
        val now = clock.now()
        ref.get()?.takeIf { now < it.fetchedAt + ttl }?.let { return it.jwks }
        return refresh(now)
    }

    /** Force a reload regardless of TTL — used on `kid` cache-miss. */
    suspend fun refresh(now: Instant = clock.now()): JWKSet =
        loadMutex.withLock {
            // Re-check inside the lock — another caller may have just refreshed.
            ref.get()?.takeIf { now < it.fetchedAt + ttl }?.let { return@withLock it.jwks }
            val fresh = withContext(Dispatchers.IO) { loader(jwksUri) }
            ref.set(Entry(fresh, now))
            fresh
        }
}
```

`JwksGoogleIdTokenVerifier.verify` then becomes:

```kotlin
val jwks0 = jwksCache.get()
val kid = requireNotNull(signedJwt.header.keyID) { "JWT missing kid header" }
val jwks = jwks0.getKeyByKeyId(kid)?.let { jwks0 }
    ?: jwksCache.refresh().also {
        // Force-refresh on miss closes the ≤1h rotation window. A second miss is genuine bad data.
        requireNotNull(it.getKeyByKeyId(kid)) { "kid $kid not in Google JWKS" }
    }
val jwk = jwks.getKeyByKeyId(kid)!!
```

**Test:** swap the loader for a `Mutex`-coordinated counter; assert that 100 concurrent `verify` calls during a stale window trigger exactly 1 loader call (thundering-herd closed).

#### B8. [K7] Wire `cloudFunctions` + `sdkClearSession` in `FirebaseAuthSessionTest`

**File:** `shared/auth/src/jvmTest/kotlin/com/sketchbook/auth/firebase/FirebaseAuthSessionTest.kt`

Add a `FakeCloudFunctions` + `FakeSdkClearSession(callCount: AtomicInteger)`; test cases:
- Signed-in user calls `signOut()` → `cloudFunctions.revokeMySession` called once with the captured ID token; `sdkClearSession` called once.
- Network failure on `revokeMySession` → sign-out completes; `sdkClearSession` still called; local state is `SignedOut`.
- `sdkClearSession` throws → sign-out logs but still completes.

---

### Phase C — High-severity correctness + UX

#### C1. [K5+] Add regression test for LeasedLockRepository UID transitions

**File:** `app-desktop/src/jvmTest/kotlin/com/sketchbook/desktop/repo/LeasedLockRepositoryTest.kt`

Two new cases:
- Repo starts with `userIdFlow.value = null`; caller calls `observe(uuid)` (returns Free); flow emits `uid = "alice"`; assert the listener now starts and emissions arrive from `users/alice/locks/...`.
- Repo holds an `Ours` status for `uid = "alice"`; flow emits `uid = "bob"`; assert `byUuid` is drained, listener jobs cancelled, status reset to Free.

#### C2. [H6] Per-uuid mutex in SyncCoordinator to serialize `pollOnce` per project

**File:** `shared/sync/src/jvmMain/kotlin/com/sketchbook/sync/SyncCoordinator.kt`

```kotlin
private val perUuidMutex = mutableMapOf<ProjectUuid, Mutex>()  // guarded by itself
private fun mutexFor(uuid: ProjectUuid): Mutex =
    synchronized(perUuidMutex) { perUuidMutex.getOrPut(uuid) { Mutex() } }

private suspend fun handleTreeEntry(treeId: String, doc: TreeDoc) {
    val uuid = ProjectUuid(treeId)
    mutexFor(uuid).withLock {
        // existing body
    }
}
```

The mutex is cheap (one allocation per project ever observed); it's process-local, not Firestore-level — its job is to prevent the inner `recordSnapshot` writes from interleaving. Cancellation propagation: `Mutex.withLock` is cancellation-safe by design.

(`lastSeenHead` already short-circuits a no-op double-fire; this guards the rare case where two emissions for the same uuid each carry a genuine advance.)

#### C3. [H7] Bounded backoff + retry on SyncCoordinator listener crash

**File:** `shared/sync/src/jvmMain/kotlin/com/sketchbook/sync/SyncCoordinator.kt:50-83`

Restructure into an inner `while (isActive)` loop:

```kotlin
userId.distinctUntilChanged().collectLatest { uid ->
    if (uid == null) return@collectLatest
    val lastSeenHead = mutableMapOf<String, Long>()
    var backoff = 1.seconds
    while (currentCoroutineContext().isActive) {
        try {
            metadataStore
                .observeCollection(CollectionPath.trees(uid), TreeDoc.serializer())
                .collect { entries -> /* existing inner body */ }
            return@collectLatest                       // collect completed cleanly
        } catch (c: CancellationException) { throw c }
        catch (e: PermissionDeniedException) {        // terminal — rules deny
            System.err.println("[SyncCoordinator] permission denied for uid=$uid; not retrying: $e")
            return@collectLatest
        } catch (e: Throwable) {
            System.err.println("[SyncCoordinator] listener crashed, retrying in $backoff: $e")
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(5.minutes)
        }
    }
}
```

The terminal-vs-transient split needs a typed exception from `FirestoreMetadataStore`. Since gitlive surfaces Firebase Firestore exceptions via wrapped Kotlin exceptions, define:

```kotlin
// :shared:cloud commonMain
class MetadataStoreException(val kind: Kind, cause: Throwable?) : RuntimeException(cause) {
    enum class Kind { PermissionDenied, Unauthenticated, Network, Other }
}
```

`FirestoreMetadataStore.observeCollection` catches the gitlive exception and rethrows the typed one. Same shape applies to `LeasedLockRepository.startListener` (also flagged in inline `SyncCoordinator.kt:68` comment).

#### C4. [H4 + N6] Heartbeats on every acquire path

**Files:**
- `shared/sync/src/commonMain/kotlin/com/sketchbook/sync/SnapshotPipeline.kt:74` (after `LeaseAcquired` emit)
- `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/repo/LeasedLockRepository.kt:135-184`

**Design:** the pipeline doesn't currently *hold* a lease across heartbeat intervals — most saves complete in <1 minute. Today's 15-minute TTL gives plenty of slack. **But** the docstring promise and the long-save case (large project tree, slow upload of new samples) are still real. The right fix is to make heartbeats opt-in at the lock site and let the pipeline self-heartbeat while its lease is held.

Cleanest shape: `MetadataStore.acquireLock` already returns `Boolean`. Add a `LockLease` resource type:

```kotlin
class LockLease internal constructor(
    private val store: MetadataStore,
    private val path: DocPath,
    private val holder: String,
    private val ttl: Duration,
    private val scope: CoroutineScope,
    private val heartbeatInterval: Duration,
) : AutoCloseable {
    private val heartbeatJob = scope.launch {
        while (currentCoroutineContext().isActive) {
            delay(heartbeatInterval)
            try {
                if (!store.refreshLock(path, holder, ttl)) {
                    // Lost the lease — listener-driven UI surface flips status; abort.
                    return@launch
                }
            } catch (c: CancellationException) { throw c }
            catch (t: Throwable) {
                // Transient — keep retrying with bounded backoff before giving up.
                System.err.println("[LockLease] refresh failed (will retry): $t")
            }
        }
    }
    override fun close() {
        heartbeatJob.cancel()
    }
}
```

`SnapshotPipeline.run` wraps its `try { ... } finally { releaseLock }` with `LockLease.use { ... }` (resource-style). `LeasedLockRepository.forceTake` builds its own `LockLease` that lives until `resetState()` cancels the scope.

`startHeartbeat`'s "transient refresh failure permanently stops heartbeating" bug (N6) dissolves here — the loop retries until the lease genuinely is taken (`refreshLock` returns `false`).

#### C5. [H5] Time-driven re-derivation of `Stale` / `HeldByOther`

**File:** `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/repo/LeasedLockRepository.kt:136-162`

`combine` the Firestore listener with a 30 s ticker; recompute status on every tick AND every emission. The ticker is per-uuid (only ticks while someone is observing) and cancelled via the `listenerJob`:

```kotlin
per.listenerJob = scope.launch {
    val tickerFlow = flow {
        while (true) {
            emit(Unit)
            delay(30.seconds)
        }
    }
    combine(
        store.observeDoc(path, LockDoc.serializer()),
        tickerFlow,
    ) { lock, _ -> lock }
        .collectLatest { lock ->
            val now = clock.now()
            per.status.value = computeStatus(lock, now, hostId)
        }
}
```

`computeStatus` is the existing `when` block extracted. Ticker fires every 30 s while subscribed; status flips Stale → Stale (same value, deduped by `MutableStateFlow`) without a Firestore write.

#### C6. [H8] Thread the last manifest through `GcsSyncQueue.runPipeline`

**File:** `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/repo/GcsSyncQueue.kt:194-216`

The catalog already stores the manifest hash + path on `recordSnapshot`. Two options:

- **Option A (preferred):** read the last `Snapshot` row + fetch the manifest JSON via `cloud.readManifest(ref)` (now path-aware after B2) once at the top of `runPipeline`. Tiny RTT cost, restores the dedup.
- **Option B:** persist the full manifest body locally in a new SQLite blob column on `snapshots`. More storage, no RTT.

Go with A. Add `SnapshotRepository.latestManifestFor(uuid): ManifestRef?` helper that reads the highest-rev `Snapshot` row + reconstitutes the `ManifestRef` from its `manifest_path`. `runPipeline` calls it before building `PipelineInput`:

```kotlin
val latestRef = snapshots.latestManifestFor(uuid)
val latestManifest = latestRef?.let { runCatching { cloud.readManifest(it) }.getOrNull() }
val input = PipelineInput(
    uuid = uuid,
    tree = tree,
    lastKnownManifest = latestManifest,
    ...
)
```

**Verification:** add an integration test in `:tests:integration` — push twice without changes; assert the second push's `actuallyUploadedBytes == 0` (no HEAD-per-blob, no rehash because mtimes match).

#### C7. [H9] Move `GcsAuth` + `ServiceAccount` to test-only

**Files:**
- Move `shared/cloud/src/jvmMain/kotlin/com/sketchbook/cloud/GcsAuth.kt` → `shared/cloud/src/jvmTest/kotlin/com/sketchbook/cloud/GcsAuth.kt`
- Move `shared/cloud/src/commonMain/kotlin/com/sketchbook/cloud/ServiceAccount.kt` → `shared/cloud/src/commonTest/kotlin/com/sketchbook/cloud/ServiceAccount.kt`
- Update `FirebaseBlobStoreTest.makeBackend` to use `val credentials = CloudCredentials { "Bearer ya29.fake" }` — no RSA keypair generation, no JWT signing.
- Update `CloudCredentials.kt` comment to drop the "Used in tests" misleading note.
- Verify `GcsAuthTest` still compiles in `:shared:cloud:jvmTest`.

This is the load-bearing fix for Required Item #2 from the comprehensive review. Production binary stops shipping a JWT signer.

---

### Phase D — Lock + Firestore hygiene

#### D1. [M1] Typed errors from `acquireLock` / `refreshLock` / `releaseLock`

**File:** `shared/cloud/src/jvmMain/kotlin/com/sketchbook/cloud/metadata/FirestoreMetadataStore.kt:133-221`

Today every failure becomes `false`. Distinguish the two cases:

```kotlin
sealed interface AcquireResult {
    object Acquired : AcquireResult
    data class HeldByOther(val current: LockDoc) : AcquireResult
    data class Failed(val cause: Throwable) : AcquireResult
}
```

`MetadataStore.acquireLock` returns `AcquireResult` (breaking change to the port — touches `SnapshotPipeline`, `LeasedLockRepository`, `InMemoryMetadataStore`, both `FakeCloudBackend`s).

`SnapshotPipeline.run` distinguishes `HeldByOther` (emit `LeaseHeld`) from `Failed` (emit `Failed` with the actual error message — surfaces "permission denied" rather than hiding it as contention).

Same shape for `refreshLock` (true / lost / failed) and `releaseLock` (returns `Result<Unit>`).

#### D2. [M7 + M15] Bound `updateDoc` retries; tighten transform contract

**File:** `shared/cloud/src/jvmMain/kotlin/com/sketchbook/cloud/metadata/FirestoreMetadataStore.kt:78-96` + port.

Tighten `transform` signature: drop `suspend` so callers can't accidentally do non-idempotent side effects inside a Firestore retry loop.

```kotlin
suspend fun <T : Any> updateDoc(
    path: DocPath,
    serializer: KSerializer<T>,
    transform: (current: T?) -> T,
): T
```

Firestore retries the transaction body up to a few times; if we want to bound it on top, wrap the call:

```kotlin
suspend fun <T : Any> updateDocBounded(
    path: DocPath, serializer: KSerializer<T>,
    maxAttempts: Int = 5,
    transform: (T?) -> T,
): T {
    var lastError: Throwable? = null
    repeat(maxAttempts) {
        try { return updateDoc(path, serializer, transform) }
        catch (c: CancellationException) { throw c }
        catch (t: Throwable) { lastError = t; delay((100L * (1 shl it)).milliseconds) }
    }
    throw lastError ?: IllegalStateException("updateDoc failed")
}
```

`SnapshotPipeline.writeTreeHeadToFirestore` already retries — replace its hand-rolled loop with `updateDocBounded`.

#### D3. [M8 + N22] Canonicalize wire shapes; drop `heartbeatSeq`

**Files:**
- `shared/cloud/src/commonMain/kotlin/com/sketchbook/cloud/metadata/LockDoc.kt`
- `shared/cloud/src/commonMain/kotlin/com/sketchbook/cloud/metadata/MachineDoc.kt`
- `shared/cloud/src/commonMain/kotlin/com/sketchbook/cloud/metadata/TreeDoc.kt` (already snake_case — leave)
- `firestore.rules` (the `hasOnly` whitelist in D5 references field names)

Adopt **snake_case** uniformly via `@SerialName`:

```kotlin
@Serializable
data class LockDoc(
    @SerialName("holder")        val holder: String,
    @SerialName("holder_name")   val holderName: String,
    @SerialName("acquired_at")   val acquiredAt: Instant,
    @SerialName("expires_at")    val expiresAt: Instant,
    // heartbeatSeq removed — YAGNI per N22.
)
```

Kotlin property names can keep their preferred case; only the wire shape changes. Since we haven't shipped any data to real users (PR description: "Sketchbook hasn't shipped to real users yet"), no migration needed.

Remove the `heartbeatSeq` writes in `FirestoreMetadataStore.acquireLock` (line 151) + `refreshLock` (line 189). Removes one source of doc churn.

`MachineDoc` rename: `hostName` → `host_name`, `binary_version` already snake — leave; consistency only.

#### D4. [N19] `MachineDoc.last_seen_at` TODO comment

Add `// TODO(machine-heartbeat): nothing writes this today. Follow-up: write on session start + heartbeat. See design doc "Out of scope for Phase 3" — machine heartbeat.` so a grep surfaces it.

#### D5. [M13] `hasOnly` whitelist on `/users/{uid}/locks/{treeId}`

**File:** `firestore.rules:117-123`

```javascript
match /locks/{treeId} {
  allow read: if isOwner(uid);
  allow create, update, delete: if isOwner(uid)
                                && withinSizeLimit()
                                && request.resource.data.keys().hasOnly([
                                     'holder', 'holder_name', 'acquired_at', 'expires_at'
                                   ]);
}
```

Field name list reflects D3. Apply the same pattern to `machines/{hostId}` and `plugins/{hostId}` (optional, separate doc-shape concern). Don't touch `trees/{treeId}` whitelist yet — TreeDoc has more fields and the v1.2 collaborators array is a moving target.

**Test:** `tests/firestore-rules/` directory (referenced in `firestore.rules` line 17) is currently empty. Out of scope of this PR — track as separate follow-up under security-commitment #4. Document that the rule is unverified by CI yet (M14).

---

### Phase E — Performance + hot-path tightening

#### E1. [M3] Single `markCloudHead` per batch

**File:** `shared/sync/src/jvmMain/kotlin/com/sketchbook/sync/SyncCoordinator.kt:101-103`

```kotlin
val lastRev = pulled.maxOfOrNull { it.rev.value } ?: return
syncStateStore.markCloudHead(uuid, lastRev)
```

Replaces the `for (snap in pulled) syncStateStore.markCloudHead(uuid, snap.rev.value)` cascade. Combined with B1's contiguous-only contract, this is correct: `pulled` carries only contiguous successful reads from `sinceRev+1`, so `lastRev` is the safe watermark.

#### E2. [M4] Parallelize PullPoller manifest reads

**File:** `shared/sync/src/commonMain/kotlin/com/sketchbook/sync/PullPoller.kt:39-62`

```kotlin
val sem = Semaphore(permits = 4)
val refsSorted = refs.sortedBy { it.rev }
val results = coroutineScope {
    refsSorted.map { ref ->
        async { sem.withPermit { runCatching { cloud.readManifest(ref) } } }
    }.awaitAll()
}
// Apply contiguous-only contract from B1 over the parallel results:
val out = mutableListOf<Snapshot>()
for ((ref, result) in refsSorted.zip(results)) {
    val manifest = result.getOrNull() ?: break    // gap — stop
    val snapshot = manifest.toSnapshot()
    snapshots.recordSnapshot(snapshot, manifestPath = ref.path, manifestHash = "")
    out += snapshot
}
return out
```

50 manifests at 200 ms RTT drops from 10 s to ~2.5 s with `permits = 4`.

#### E3. [M5] `observeCollection` deltas via gitlive `documentChanges`

**File:** `shared/cloud/src/jvmMain/kotlin/com/sketchbook/cloud/metadata/FirestoreMetadataStore.kt:120-131` + port.

Extend the port with a new method (don't change existing `observeCollection` signature — current callers want full list semantics):

```kotlin
sealed interface CollectionChange<T> {
    val id: String
    data class Added<T>(override val id: String, val value: T) : CollectionChange<T>
    data class Modified<T>(override val id: String, val value: T) : CollectionChange<T>
    data class Removed<T>(override val id: String) : CollectionChange<T>
}

fun <T : Any> observeCollectionChanges(
    path: CollectionPath, serializer: KSerializer<T>,
): Flow<List<CollectionChange<T>>>
```

gitlive's `QuerySnapshot.documentChanges` returns exactly this surface. `SyncCoordinator` switches to the delta variant; the per-uuid `lastSeenHead` cache becomes redundant (replace with direct dispatch). For N=1000 trees, processing flips from O(N) per emission to O(changed).

(B-282372's `lastSeenHead` cache stays as a defense-in-depth + a no-op for the in-memory test fake which doesn't implement deltas yet. After this, `InMemoryMetadataStore` gains a real diff in `observeCollectionChanges` — N10 helps here too: ⊕ `distinctUntilChanged` on `docs` keeps the in-memory shim O(N).)

#### E4. [M6] Cache the seeded `(uid, expiresAt)` in `FirebaseSdkBootstrap`

**File:** `shared/auth/src/jvmMain/kotlin/com/sketchbook/auth/firebase/FirebaseSdkBootstrap.kt:58-107`

```kotlin
@Volatile private var lastSeededUid: String? = null
@Volatile private var lastSeededExpiresAtMillis: Long = 0L

suspend fun ensureInitialized() {
    if (initialized && platform != null) {
        val tokens = authSession.currentTokens()
        val expMillis = tokens.expiresAt.toEpochMilliseconds()
        if (tokens.uid == lastSeededUid && expMillis == lastSeededExpiresAtMillis) return
        platform?.store(FIREBASE_USER_STORAGE_KEY, AuthStateInjector.firebaseUserImplJson(tokens))
        lastSeededUid = tokens.uid
        lastSeededExpiresAtMillis = expMillis
        return
    }
    // ... slow path unchanged; sets lastSeeded* on first init.
}
```

`clearSession` resets both fields to clear-state.

#### E5. [M19] Single `hostIdentity()` binding

**File:** `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt:223-302, 459-484`

```kotlin
@Provides @SingleIn(AppScope::class)
fun provideHostIdentity(): HostIdentity = hostIdentity()
```

Inject `HostIdentity` into `provideLockRepository`, `provideSyncQueue`, etc. Single disk read + single `getLocalHost` lookup per JVM. Closes the 4× I/O hit on graph construction.

---

### Phase F — Minor + docs cleanup (cheap)

#### F1. [M9] Delete dead OAuth refresh code

**Files:**
- `shared/auth/src/commonMain/kotlin/com/sketchbook/auth/OAuthFlow.kt` — drop the `refresh` method from the interface.
- `shared/auth/src/jvmMain/kotlin/com/sketchbook/auth/OAuthClient.kt:69-91, 229-233` — delete `refresh` impl + helpers.
- Test fakes (`StubOAuthClient` in `FirebaseAuthSessionTest.kt`) — drop `refresh` override.
- `OAuthTokens.idToken` comment — rephrase "Pre-Firebase callers can ignore" → "Required for the Identity Toolkit `signInWithIdp` exchange."

#### F2. [M10] `functions/.gitignore` SA-JSON patterns

**File:** `functions/.gitignore`

```
node_modules/
*.log
.firebase/
# Service-account credentials — never commit (security-commitment #5 backstop until pre-commit hook lands)
service-account-*.json
serviceAccountKey.json
*.pem
.env
.env.*
```

#### F3. [M11] Docs: revokeMySession semantics + URL caveat

**File:** `docs/runbooks/firebase-deploy.md` (after line 105ish — the "deferred to Phase 3" paragraph).

Add:
- `firebase deploy --only functions` recipe (matches PR-description action item).
- "When things go wrong" table entry for `sign-out succeeds locally but other devices stay signed in`.
- Note that `revokeMySession` invalidates **refresh** tokens; active ID tokens remain valid for ≤ 1 h.
- Update the CI integration paragraph at the bottom — Phase 3 is shipping, deferred-tests follow-up tracked.

#### F4. [M16] Fail-fast on `OAUTH_CLIENT_ID` placeholder in prod

**File:** `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt:445-447`

```kotlin
private val OAUTH_CLIENT_ID: String = run {
    val v = System.getProperty("sketchbook.oauth.client_id")
        ?: "REPLACE_ME.apps.googleusercontent.com"
    val env = System.getProperty("sketchbook.env", "dev")
    if (env == "prod" && v == "REPLACE_ME.apps.googleusercontent.com") {
        error("OAUTH_CLIENT_ID placeholder in production build — set -Dsketchbook.oauth.client_id=...")
    }
    v
}
```

#### F5. [N2] URL-form encoding in IdentityToolkit

**File:** `shared/auth/src/jvmMain/kotlin/com/sketchbook/auth/firebase/IdentityToolkitClient.kt:73-89`

```kotlin
suspend fun refresh(refreshToken: String): Result<FirebaseTokens> =
    runRequest {
        val response: HttpResponse = httpClient.submitForm(
            url = "$secureTokenBase/v1/token",
            formParameters = parameters {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
            },
        ) {
            parameter("key", webApiKey)
        }
        // ... decode ...
    }
```

Same pattern for `signInWithGoogleIdToken` (only the `postBody` field is a serialized form string today — leave as-is unless we hit a wire-encoding problem; the entire request body is JSON, the `postBody` field is a Firebase quirk).

#### F6. [N1, N10, N12, N13, N15, N16, N17, N19, N25] Trivial polish bundle

Land together since they share no logical surface:

- **N1** `InMemoryMetadataStore.setDoc` — use `docs.update { it + (k to v) }` with a `Map.Builder` or accept the cost (fine).
- **N10** `InMemoryMetadataStore.observeCollection` — add `.distinctUntilChanged()` on the projected list flow.
- **N12** `JvmFirebasePlatform.kt:38-43` — rename property `store` → `entries` (the override is `fun store(key, value)`).
- **N13** drop `r.getOrNull()?.let { identityStore.save(it) }` in `DesktopAuthSession.signIn` (after B4).
- **N15** `JvmBlobCache.getOrFetch` — `ConcurrentHashMap<String, Mutex>` per-hash; `mutex.withLock` around the fetch.
- **N16** `GcsSyncQueue.recordConflictJournal:279` — `Clock.System.now()` → injected `clock.now()` (and thread the clock through the constructor if needed).
- **N17** `KeyringTokenStore.read:21-27` — log `System.err.println("[KeyringTokenStore] backend not supported: $e")` on the catch branch.
- **N19** add `MachineDoc.last_seen_at` TODO comment (F4 with D4).
- **N25** `OAuthTokens.userId` KDoc — add "**Unverified** until `FirebaseAuthSession.signIn` runs Google JWT verification + Identity Toolkit exchange."

---

## 3. Merge gate + test plan

### 3.1 Required before merge (mapped to the comments)

The owner-review's required-before-merge list:

| Required | Status after this plan |
|---|---|
| Silent restore broken | B4 + new `DesktopAuthSessionTest` |
| `GcsAuth` ships in prod binary | C7 move-to-test |
| `FirebaseSdkBootstrap` runtime cast | ✅ already fixed by `2d82393` (`FirebaseAuthGraph` DI binding) |

The performance + correctness deploy-blockers:

| Reviewer's top-3 | Status |
|---|---|
| `PullPoller` rev-drop / O(N²) | B1 + B2 |
| `LeasedLockRepository` listener leak + thread-safety | ✅ partial (commit `a34bdcb`) + new test in C1 |
| `HttpClient(CIO)` no timeouts | A2 |

Plus from the comprehensive review's pre-merge sequence:

| Item | Status |
|---|---|
| C1 PullPoller rev-drop | B1 |
| C2 LeasedLockRepository UID transitions | ✅ `a34bdcb` + test in C1 |
| C3 revoke + gitlive cache | ✅ `2d82393` + test in B8 |
| M10 OAUTH_CLIENT_ID assertion in prod | F4 |
| M4 functions/.gitignore SA-JSON | F2 |

### 3.2 Test additions summary

| Test file | New cases |
|---|---|
| `SyncCoordinatorTest` | Partial-pull rev-drop (B1); permission-denied terminates; transient retry recovers (C3); per-uuid serialization (C2). |
| `LeasedLockRepositoryTest` | Signed-out → uid emission starts listener (C1); UID transition cancels + clears (C1); concurrent first-observe doesn't leak listeners (K4 verification); time-driven `Stale` re-derivation (C5). |
| `FirebaseAuthSessionTest` | Wires `cloudFunctions` + `sdkClearSession`; revoke success/failure; signOut completes despite network failure (B8); `currentTokens()` race against `signOut` doesn't CCE (B6); transient refresh failure doesn't clear keyring (B5). |
| `DesktopAuthSessionTest` (NEW) | Silent restore happy path / terminal failure / transient failure (B4). |
| `FirebaseBlobStoreTest` | Pagination over `nextPageToken` (B3); `startOffset` filter (B3); `readManifest(ref)` doesn't trigger an inner list (B2). |
| `JwksGoogleIdTokenVerifierTest` | Concurrent verify single-flights the loader (B7); kid-miss triggers force-refresh (B7). |
| `SnapshotPipelineTest` | Lease heartbeats fire during a long save (C4); transient refresh on heartbeat retries (C4). |
| `InMemoryMetadataStoreTest` | `observeCollectionChanges` returns Added/Modified/Removed (E3). |
| `:tests:integration` | Round-trip push twice → second push uploads 0 bytes (C6 regression). |

### 3.3 Out of scope (tracked as follow-ups, not blockers)

Add to the design doc's "Out of scope for Phase 3" section:

- **M2** Drop GCS HEAD pointer file (Firestore is authoritative). Saves ~30% of save-path latency. Requires touching every fake + the materializer path that reads HEAD.
- **M14** Firestore-rules unit tests in CI; `functions/` lint in CI (security-commitment #4).
- **M18** Share Firestore listener subscriptions via `shareIn` at the consumer layer.
- **M20** Land `UserGraphHolder.cloudBackend` consumer wiring (or remove the holder); part of the per-user `@GraphExtension` work (M21).
- **M21** `CloudScope` Metro `@GraphExtension` to dissolve the `SwappableSyncQueue` cast and `UserGraphHolder` duplication.
- **M12** Per-user total-storage quota via Cloud Function on `finalize` events (defense vs. cost-DoS).
- **N4** Connection-pool tuning for parallel uploads (first measurement).
- **N5** `firestore.indexes.json` populated when first `where` + `orderBy` query lands.
- **N11** Test-naming convention pass (separate cleanup PR).
- **N23** Shared `:shared:cloud:testFixtures` for `FakeCloudBackend`.

### 3.4 Recommended landing order

Each phase is independently mergeable; testing between phases catches regressions early.

1. **A1, A2** — cancellation hygiene + HTTP timeouts. Foundation.
2. **B1, B2, B3** — pull path correctness + perf (deploy-blockers).
3. **B4, B5, B6, B7, B8** — auth correctness suite.
4. **C7** — `GcsAuth` move-to-test (security commitment).
5. **C1, C2, C3, C4, C5, C6** — lock + listener + dedup hardening.
6. **D1, D2, D3, D4, D5** — Firestore hygiene + wire-shape cleanup.
7. **E1, E2, E3, E4, E5** — perf tightening.
8. **F1–F6** — minor + docs (can land any time after A1).

### 3.5 What's intentionally NOT changing

These reviewer suggestions are explicitly declined:
- **N4, N5, N7, N8, N9, N14, N18, N23** — see the "won't fix / defer" tags in §1. Each has either an acknowledged trade-off or a more invasive design change that doesn't fit this PR's scope. Tracking as follow-up backlog only.

---

## 4. Risk + rollback notes

- **D1's typed `AcquireResult`** is a breaking change to the `MetadataStore` port. Every implementation (`FirestoreMetadataStore`, `InMemoryMetadataStore`, both `FakeCloudBackend`s) and consumer (`SnapshotPipeline`, `LeasedLockRepository`) updates together. Rollback = single revert.
- **D3's snake_case rename** is a wire-shape break. Sketchbook hasn't shipped, so no migration path is required — but if any developer has dev-Firestore data from local probes, they'll need to wipe the `users/{uid}/locks` collection. Document in F3's runbook addendum.
- **A2's HTTP timeouts** can surface latent slowness. Recommend a manual one-hour burn-in on dev before merging — push, pull, sign-in, sign-out, lock contention — to confirm no operation regresses past the 15 s request budget.
- **B2's path-aware `readManifest`** changes call sites in production. The legacy `readManifest(uuid, rev)` stays so any code path we missed continues to function (just at the old O(N) cost) until we find and migrate it.
- **C4's `LockLease` AutoCloseable** changes the lifecycle of heartbeats. Ensure `SnapshotPipeline`'s `try { ... } finally { releaseLock }` is converted to `LockLease.use { ... }` correctly — cancelling the pipeline scope cancels the heartbeat AND releases the lock.
