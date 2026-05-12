# Design — retire `kotlin.Result<T>` from repository boundaries

**Status:** proposal, awaiting review.
**Issue:** [#128](https://github.com/srMarlins/sketchbook/issues/128).
**Author:** Claude / Jared, 2026-05-12.

## 1. Why

`kotlin.Result<T>` was not designed as a domain-API return type. The KEEP that introduced the class says so directly:

> The `Result` class is designed to capture generic failures of Kotlin functions for their latter processing and should be used in general-purpose API like futures, etc., that deal with invocation of Kotlin code blocks and must be able to represent both a successful and a failed result of execution. **The `Result` class is not designed to represent domain-specific error conditions.**
> — KEEP, [stdlib/result.md](https://github.com/Kotlin/KEEP/blob/master/proposals/stdlib/result.md)

Roman Elizarov reinforces the position after Kotlin 1.5 lifted the return-type restriction:

> `kotlin.Result` is not designed as a general-purpose replacement for domain-specific types... result types should be domain-specific, named and used clearly according to the appropriate domain.
> — Elizarov, [The reasons to include kotlin.Result are spelled out in its design document](https://elizarov.medium.com/the-reasons-to-include-kotlin-result-are-spelled-out-in-its-design-document-d7d77d79a70e)

Sketchbook's repository layer has accumulated 29 public methods that return `Result<T>` across 9 interfaces. The consequences on current main:

- **Error type is erased.** `Result.failure(e)` carries a `Throwable` — the caller has no compiler-enforced way to know whether `e` is `SketchbookError.NotFound`, `SketchbookError.Conflict`, or an `IllegalStateException` from a violated precondition. Every callsite that wants to branch reads `.exceptionOrNull()?.message` or does `is` checks against the sealed hierarchy.
- **Cancellation hazard is replicated 29 times.** Every `Result<T>`-returning suspend implementation is a `runCatching` waiting to happen. PR #149 cleaned up the literal `runCatching` violations but didn't close the door — the *return type* invites the pattern back in.
- **Consumer code is opaque.** Callsites read like `result.isSuccess`, `result.onFailure { emit(reason) }`, `result.exceptionOrNull()?.message ?: "unknown"`. The actual outcome space is invisible.
- **Doubled wrapping.** `OnboardingViewModel.finish` and `NeedsAttentionViewModel` had `runCatching { repository.method() }.onFailure { ... }` — the outer `runCatching` only fires if the call *throws*, which a `Result`-returning method shouldn't; meanwhile `.onFailure` doesn't fire for `Result.failure` because it's wrapped inside `Result.success(Result.failure(e))`. The shape invites this bug.

This refactor retires `kotlin.Result<T>` from the repository surface. It is not a clean-up project — it's a correctness fix for the four issues above, with the side benefit of a more legible API.

## 2. Decision tree

For each method, pick the **single** rule that fires first:

1. **Throw** when:
   - The failure is infrastructure (DB unavailable, disk error, network) or a programmer mistake (precondition violation), AND
   - The caller can only propagate or surface it — there's nothing to pattern-match on.
   - Throws should be typed: `SketchbookError.IoFailure`, `SketchbookError.RemoteFailure`, `IllegalStateException` for preconditions.
2. **Return `T?`** when there is *exactly one* expected "absence" case and no information to convey beyond present/absent.
3. **Return a sealed `Outcome`** when:
   - The caller must distinguish 2+ named domain branches at compile time (e.g., `Ok` / `Conflict` / `Busy`), AND
   - The compiler should enforce exhaustive handling via `when`.
4. **Never** wrap in `kotlin.Result<T>`. The class stays in stdlib for `runCatching`/continuation plumbing; it leaves the repository surface.

Notes:

- Sealed outcomes are **per-method or per-narrow-concept**, not one mega-`AppOutcome`. The shared `SketchbookError` hierarchy stays as the thrown-exception type because it's already in place and serves its purpose well.
- A sealed outcome's `Failed` variant should **not** carry a `Throwable`. If you want a cause for logging, log it inside the implementation. Embedding `Throwable` in a sealed type re-creates the type-erasure problem the sealed type was meant to solve.
- Cancellation is not a domain outcome. `CancellationException` propagates out of throwing methods naturally; sealed-outcome methods must use `runCatchingCancellable`-equivalent control flow internally so cancellation never becomes an `Outcome.Failed`.

## 3. Per-method classification

Counts and signatures from the audit; line numbers reference the interface files unless noted.

### 3.1 Throw (21 methods)

These methods convey at most one outcome class (`Unit`, the appended `JournalEntry`, etc.) and a thrown exception on failure. Callers that need to surface failure use `try/catch (SketchbookError | ...) `; callers that don't care let it propagate. **`@Throws` annotations on the interface** name the expected types (`SketchbookError.NotFound`, etc.) for documentation and Kotlin/Native compatibility.

| Interface | Method | Notes |
|---|---|---|
| `SettingsRepository` | `upsertRoot`, `removeRoot`, `setSelfContained`, `setCacheSettings`, `markFirstRunComplete`, `dismissOnboardingPrompt`, `setPluginFolders`, `resetFirstRun` | All 8 are `Unit` writes. Throw `IoFailure` on prefs/keychain error; let `IllegalArgumentException` from path validation propagate. |
| `RepairRepository` | `acknowledgeMacImport`, `applyMacPathRepair`, `dismissMissingSample`, `applyMissingSampleMatch`, `restoreMissingSampleMatch`, `restoreMacPathRepair` | 6 methods. The patcher's `Outcome` (Patched / NoChange / SkippedBusy / Failed) is already pattern-matched and journalled *internally*; the repository method itself only throws for catastrophic / unexpected failures. |
| `ProjectRepository` | `move`, `rename`, `archive`, `setTags`, `setStageOverride` | 5 methods returning the appended `JournalEntry`. Throw `SketchbookError.NotFound` when the project id is missing; otherwise let infra exceptions propagate. |
| `JournalRepository` | `append` | Returns the entry with assigned sequence. Throws on infra failure. |
| `SnapshotRepository` | `recordSnapshot`, `setSnapshotLabel` | `recordSnapshot` is idempotent on duplicate `(uuid, rev)`; throws only on infra failure. `setSnapshotLabel` throws `NotFound` for missing snapshot. |

### 3.2 Nullable (1 method)

| Interface | Method | Shape |
|---|---|---|
| `JournalRepository` | `undoLast(): JournalEntry?` | `null` means the journal had nothing to undo. Infra failures still throw. |

The previous `Result<JournalEntry>` collapsed "empty" and "infra exception" into the same failure path, which the only consumer (`JournalViewModel`) then had to disambiguate via `.exceptionOrNull()?.message`. Nullable + throws separates the cases.

### 3.3 Sealed outcome (6 methods)

These are the cases where the caller must — or *should be able to* — branch on a domain outcome. Each gets its own narrow sealed interface. Future-proofed: we model the domain branches in the type even when current consumers collapse them to a generic toast, so a later UI improvement is a `when`-branch addition rather than an interface change.

#### `SyncQueue.pushNow`

```kotlin
sealed interface PushNowOutcome {
    data object Pushed : PushNowOutcome
    data object AlreadyInFlight : PushNowOutcome
    data class Conflict(val theirRev: Long) : PushNowOutcome
}

suspend fun pushNow(uuid: ProjectUuid): PushNowOutcome
```

Throw `RemoteFailure` / `IoFailure` for transport-level errors; the sealed outcome captures the three domain branches the drain loop and "Push now" UI need.

#### `SnapshotRepository.materializeAt` + `ManifestMaterializer.materialize`

```kotlin
sealed interface MaterializeOutcome {
    data object Materialized : MaterializeOutcome
    data class WorkingTreeBusy(val paths: List<String>) : MaterializeOutcome
}

suspend fun materialize(uuid: ProjectUuid, rev: SnapshotRev): MaterializeOutcome
```

Today `ManifestMaterializer` returns `Result.failure(WorkingTreeBusyException(busy))` and the caller pattern-matches via `is`. Other failures (manifest not found, blob fetch, disk full) throw — the Rewind UI surfaces them as a generic error toast, but the "open in Live" busy case has a dedicated UI path that needs the file list.

#### `ProposalsRepository.approve` / `reject`

```kotlin
sealed interface ApproveOutcome {
    data class Approved(val proposal: Proposal) : ApproveOutcome
    data object NotFound : ApproveOutcome
    data object AlreadyDecided : ApproveOutcome
}

sealed interface RejectOutcome {
    data object Rejected : RejectOutcome
    data object NotFound : RejectOutcome
    data object AlreadyDecided : RejectOutcome
}

suspend fun approve(proposalId: String): ApproveOutcome
suspend fun reject(proposalId: String): RejectOutcome
```

Separate types because `approve` returns the updated `Proposal` (for echo in the UI effect) and `reject` returns `Unit`. Today's consumer (`ProposalsViewModel`) maps any failure to a generic `Effect.Failed(reason)`; with the sealed outcome the UI can later distinguish "this proposal is gone" from "someone else already decided it" without an interface change. Infrastructure failures (write fails, network) still throw.

#### `LockRepository.forceTake`

```kotlin
sealed interface ForceTakeOutcome {
    data object Taken : ForceTakeOutcome
    data object RaceLost : ForceTakeOutcome   // another host re-acquired between our release + acquire
}

suspend fun forceTake(uuid: ProjectUuid): ForceTakeOutcome
```

The "force-take race" case is a documented expected outcome (`LeasedLockRepository.kt:141-145`) — the user accepted "I'm racing the current holder" by clicking the button, and somebody can land their write after our release/before our acquire. That branch deserves its own UI message (e.g., "Another collaborator just took the lock"); a thrown `IllegalStateException` collapses it into the generic-error bucket. Infrastructure failures still throw.

## 4. Implementation plan

### 4.1 Single-PR strategy

The work is large (~29 interfaces + ~50 callsites + fakes + tests) but **tightly coupled**: every interface signature change is paired with every consumer. A phased migration would leave the codebase in mixed shape with `try/catch` and `Result` chains co-existing; one atomic PR is cleaner to review and revert.

Ordering inside the PR (to keep each commit compilable for `git bisect` later):

1. **Foundation commit**: Add `@Throws` annotations to interface declarations; no behavior change. The interfaces still return `Result<T>` after this commit.
2. **Sealed outcomes commit**: Introduce `PushNowOutcome`, `MaterializeOutcome` types. Add `materialize()` / `pushNow()` overloads returning the sealed types; mark old `Result<T>`-returning overloads `@Deprecated(level = ERROR)` so nothing compiles against them. *(Actually skip the deprecated path — single PR, just change the signature.)*
3. **Per-interface conversion**: Convert one interface at a time, then its impls, fakes, and callers, in commits the reviewer can scan independently:
   - `SettingsRepository` — throw
   - `RepairRepository` — throw
   - `ProjectRepository` — throw
   - `JournalRepository` — throw + nullable (`undoLast`)
   - `SnapshotRepository` + `ManifestMaterializer` (paired) — throw + `MaterializeOutcome`
   - `SyncQueue` — `PushNowOutcome`
   - `ProposalsRepository` — `ApproveOutcome` + `RejectOutcome`
   - `LockRepository` — `ForceTakeOutcome`
4. **Cleanup commit**: Remove the `failed()` / `internal fun <T> failed(error: SketchbookError): Result<T>` helper in `ProjectRepository.kt:231`. Delete any now-unused `Result`-returning extensions.

If "single PR" turns out to be too painful to review (estimate: ~80 files / ~1500 lines diff), the natural split is per-interface PRs in the order above — but commit the design doc first so each PR can link back.

### 4.2 Implementation pattern, by category

**Throw (24 methods)** — implementations already have the pattern (see `SqlJournalRepository.append` lines 52–104):

```kotlin
@Throws(SketchbookError.IoFailure::class, CancellationException::class)
override suspend fun upsertRoot(root: LibraryRoot) {
    withContext(ioDispatcher) {
        try {
            // ... existing body ...
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            throw SketchbookError.IoFailure("upsertRoot failed for ${root.path}", t)
        }
    }
}
```

Callsites simplify from:

```kotlin
runCatchingCancellable { repository.upsertRoot(LibraryRoot.Projects(path)) }
    .onFailure { emitFailure("Couldn't save Projects folder: $path") }
```

to:

```kotlin
try {
    repository.upsertRoot(LibraryRoot.Projects(path))
} catch (c: CancellationException) {
    throw c
} catch (t: Throwable) {
    emitFailure("Couldn't save Projects folder: $path")
}
```

Verbose, but the bug it fixes is real (today's `runCatchingCancellable { ... }.onFailure { ... }` never fires when the repository returns `Result.failure` because the outer Result is `Result.success(Result.failure(...))`).

**Nullable (`undoLast`)**:

```kotlin
suspend fun undoLast(): JournalEntry?
```

Callsite (`JournalViewModel`):

```kotlin
val popped = journal.undoLast() ?: run {
    _effects.tryEmit(Effect.Failed(id = "undo", reason = "nothing to undo"))
    return@launch
}
// ... use popped
```

**Sealed outcome** (`pushNow`, `materializeAt`):

Implementation uses a private `runCatchingCancellable` only to route infra failures into throws (not into the outcome):

```kotlin
override suspend fun pushNow(uuid: ProjectUuid): PushNowOutcome {
    if (uploading.value.contains(uuid)) return PushNowOutcome.AlreadyInFlight
    val outcome = runPipeline(pid, uuid) // throws on transport failure
    return when {
        outcome.isCasConflict -> PushNowOutcome.Conflict(outcome.theirRev)
        else -> PushNowOutcome.Pushed
    }
}
```

Callsite:

```kotlin
when (val o = syncQueue.pushNow(uuid)) {
    PushNowOutcome.Pushed -> _effects.tryEmit(Effect.Pushed(uuid))
    PushNowOutcome.AlreadyInFlight -> _effects.tryEmit(Effect.AlreadyPushing(uuid))
    is PushNowOutcome.Conflict -> _effects.tryEmit(Effect.Conflict(uuid, o.theirRev))
}
```

### 4.3 Tests and fakes

Fakes need three shapes of update:

- **Success-only fakes** (e.g., `FakeSettings` in `LaunchGateTest.kt:50-83`): drop `Result.success(Unit)` wrapping; methods return `Unit`.
- **Failure-injecting fakes** (`FakeSettingsRepository` in `feature-onboarding`'s commonTest): replace `return Result.failure(IllegalStateException(...))` with `throw SketchbookError.IoFailure(...)`.
- **Throwing fakes** (e.g., `FakeCloudBackend` in `tests/integration/`): already throw `SketchbookError.NotFound`; no change.

Repository unit tests that currently assert `result.isFailure` and `result.exceptionOrNull() is SketchbookError.NotFound` switch to `assertFailsWith<SketchbookError.NotFound>` — net simpler.

### 4.4 Optional: detekt rule

`detekt` doesn't ship a rule blocking `Result<T>` returns. A custom rule would be:

```kotlin
class ResultReturnTypeRule : Rule(...) {
    override fun visitNamedFunction(function: KtNamedFunction) {
        val type = function.typeReference?.text ?: return
        if (type.startsWith("Result<") && function.containingKtFile.packageFqName.startsWith("com.sketchbook.repo")) {
            report(CodeSmell(issue, Entity.from(function), "Result<T> not allowed at repository boundary"))
        }
    }
}
```

Defer to a follow-up — the PR is large enough without adding a detekt rule.

## 5. Risks and non-goals

### Risks

- **Lost-cancellation regression.** Any leftover bare `catch (t: Throwable)` in the implementations would swallow `CancellationException`. Mitigation: every catch in this PR has the `catch (c: CancellationException) { throw c }` clause; CI is the safety net for callsites we missed.
- **Behavior change at callsites that did `r.exceptionOrNull()?.message`.** Throwing methods need the same toast message, sourced from the thrown exception. We'll smoke-test the onboarding, needs-attention, and quick-capture flows in the desktop UI before merging.
- **Binary compatibility.** Not an issue — the repository surface is `internal` to the Sketchbook app modules. No published library API touches `Result<T>`.
- **PR size.** ~80 files / ~1500 LOC estimate. Reviewable but not trivial. The per-interface commit ordering above is the mitigation.

### Non-goals

- **Adopting Arrow `Either` / `Raise`.** Considered and rejected. Arrow's value is the `Raise` DSL and effect composition; we have neither a need for typed-error composition across many call sites nor the bandwidth to commit a whole module to the migration. Sealed outcomes + thrown exceptions cover the same ground with stdlib only.
- **Refactoring `SketchbookError`.** The existing sealed hierarchy (`NotFound`, `Conflict`, `IntegrityError`, `IoFailure`, `RemoteFailure`) is good. We use it as the thrown-exception type; we do not subdivide further.
- **Pipeline-internal `Result`.** `kotlin.Result` stays in stdlib and is fine inside `runCatchingCancellable` plumbing. The boundary we're moving is the *repository public surface*.
- **A migration shim.** No `@Deprecated` overloads, no parallel `XxxRepositoryV2` interface. The PR replaces signatures atomically.

## 6. Acceptance

- `grep -rn ": Result<\|suspend fun.*Result<" shared/repository/src/commonMain/kotlin/com/sketchbook/repo/` returns 0 hits.
- Every interface method that throws lists its expected `SketchbookError` subclasses in `@Throws` annotations.
- `./gradlew jvmTest spotlessCheck detekt` is green.
- Desktop smoke test of: onboarding finish (write-failure toast), repair apply (success + retry on Failed), force-take lock (race outcome surfaces a useful message), quick-capture snapshot (BusyOutcome surfaces filename list).
- Closes #128.

## 7. References

- KEEP: [proposals/stdlib/result.md](https://github.com/Kotlin/KEEP/blob/master/proposals/stdlib/result.md)
- Elizarov, [Kotlin and Exceptions](https://elizarov.medium.com/kotlin-and-exceptions-8062f589d07)
- Elizarov, [The reasons to include kotlin.Result are spelled out in its design document](https://elizarov.medium.com/the-reasons-to-include-kotlin-result-are-spelled-out-in-its-design-document-d7d77d79a70e)
- `kotlinx.coroutines` [#1814](https://github.com/Kotlin/kotlinx.coroutines/issues/1814) — `runCatching` and `CancellationException`
- Phauer, [Sealed Classes Instead of Exceptions in Kotlin](https://phauer.com/2019/sealed-classes-exceptions-kotlin/)
- Internal: [`docs/ai/CLAUDE.md`](../ai/CLAUDE.md) `runCatching` rule; PR [#149](https://github.com/srMarlins/sketchbook/pull/149) cancellation cleanup.
