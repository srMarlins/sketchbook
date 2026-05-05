# Claude Code — agent guidelines

You are the primary implementer on this repo. The Python tree is the parity reference; the Kotlin tree under `shared/` + `app-desktop/` is the real product.

## Authoritative documents — read these before writing code

1. `docs/plans/2026-05-05-sync-versioning-design.md` — the architecture. If a PR contradicts the design doc, raise the contradiction; do not silently diverge.
2. `docs/plans/2026-05-05-kotlin-rewrite-impl-plan.md` — the PR-by-PR roadmap. Each PR has Goal / Files / Tasks / Acceptance / Test plan. Follow the tasks in order.
3. `CONTRIBUTING.md` — branch naming, commits, PR workflow.

## Module boundaries (strict, acyclic)

```
core ── parser-als
core ── catalog ── repository ── sync ── sync-io
core ── ui-shared ── feature-* ── app-desktop
core ── cloud ── sync
core ── actions ── repository
core ── mcp-server ── repository
```

- `core` is leaf: domain models, value classes, errors. Pure Kotlin, no platform deps.
- `ui-shared` depends on `core` only. No data flow.
- `repository` is THE seam. Features and sync engine both write through it; nothing reaches past it into SQLDelight or `cloud`.
- `sync` and `actions` go through `repository` so journal entries get emitted; they never touch `catalog` directly.
- State-holders live in `feature-*` modules in `commonMain`. `app-desktop` is a thin shell.

If a task asks you to import something that breaks one of these arrows, stop and raise it in the PR.

## State-holder pattern (canonical)

Every screen has one state-holder following design-doc §2.4. Sealed `Intent`, data class `State`, sealed `Effect`, `state: StateFlow<State>`, `accept(intent)`, `effects: SharedFlow<Effect>`. Compose collects state, dispatches intents, observes effects via `LaunchedEffect` once at screen root. No MVI library — just plain Kotlin.

## Repository as the only data seam

- UI never touches SQLDelight types. Domain types only.
- Writes return a result type (e.g. `Result<JournalEntry>` or `Either`); errors are sealed `AudioError`.
- Repository owns dispatcher selection (DB writes on `IODispatcher`).

## Metro DI

- One root `@DependencyGraph(AppScope::class)` per app shell (`app-desktop`, future `app-mobile`, `app-mcp`).
- Modules contribute via `@ContributesTo(AppScope::class)` interfaces.
- Per-screen lifetimes via `@GraphExtension`. State-holder scopes die with their screen.
- Never inject a concrete impl across module boundaries — inject the interface.

## Testing

- `kotlin.test` runner, Kotest assertions, Turbine for Flow.
- Hand-written fakes in `commonTest`. No MockK in `commonMain`/`commonTest`.
- TDD for: parsers, hashers, SigV4, repository, sync orchestration, state-holders.
- Run `./gradlew :module:check` after every change you intend to commit. Don't claim "tests pass" without running them.
- Power-Assert is on — write `assert(x == y)` rather than `assertEquals`.

## Coroutines

- Inject dispatchers via Metro (`@IODispatcher`, `@DefaultDispatcher`). Never hard-code `Dispatchers.IO`.
- Hot views: `Flow.stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)`.
- Watcher-style sources: `channelFlow { … awaitClose { } }`.
- Long-running work belongs in a repository-scoped coroutine, not a state-holder scope.

## Avoid these libraries (and why)

- **MVIKotlin / Decompose** — adds vocabulary on top of plain Kotlin/Flow we don't need.
- **Roborazzi** — visual review happens in `app-gallery` + PR screenshots; no need for snapshot tests.
- **KAPT / Anvil** — Metro covers DI; KAPT pulls heavy JVM-only annotation processing.
- **Realm Kotlin / Room** — SQLDelight wins for FTS5 + KMP.
- **Koin** — Metro is compile-time-checked.
- **Moko-resources / `viewmodel-compose`** — not needed in v1; would couple us to Android-isms.

If the design doc adds an exception, follow it. Otherwise, do not introduce these.

## Non-obvious rules from prior conversations

- **`.als` parser must stream.** A DOM parse blew RAM to 25 GB on a 543 MB project. Use StAX `XMLInputFactory` + the free-subtree pattern (null out subtree references after each `<Track>` / `<DeviceChain>`). Tested with a heap cap.
- **No batch checkpoints during plan execution.** Drive through all tasks within a PR. The user reviews at PR boundaries, not between tasks.
- **Layer onto existing UI; never redesign.** When the Python `web/` is still alive (parity period), additions go on as small chips/details on existing components. The stationery aesthetic is intentional.
- **App owns the scan; DB is the source of truth.** Never ship CLI-only flows for routine work. The desktop app runs scan/backfill on launch with a progress bar.
- **No unnecessary libraries.** The user has explicitly rejected MVI libs, screenshot tests, and navigation frameworks. Plain Kotlin StateFlow + sealed-class intents.

## PR workflow

1. Branch off `main` as `pr-<NN>-<slug>`.
2. Implement task-by-task per the plan. Many small commits.
3. `./gradlew check` must be green locally before pushing.
4. UI PR? Run `:app-desktop:run` (or `:app-gallery:run`), capture screenshots per state, attach via `gh pr comment <pr> --body-file <md>`.
5. Self-review: walk the diff cold against the design-doc section the PR claims to implement.
6. `gh pr review <pr> --comment` with concrete `file:line` citations for any concerns; approve only after tests pass and design alignment is confirmed.
7. Squash-merge.

## When to stop and ask

- A plan task references a file/module that doesn't exist yet and isn't created in this PR.
- A test you can't reproduce locally is failing in CI.
- The design doc and the plan disagree on a structural choice.
- A "small" change touches more than one module's public API.

Ask, don't guess.
