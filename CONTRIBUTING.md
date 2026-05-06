# Contributing

Sketchbook is a Kotlin + Compose Multiplatform desktop app for cataloging and syncing an Ableton library. Architecture, conventions, and library policy live in `docs/ai/CLAUDE.md`. There is no standing roadmap doc — active work is whatever the user is currently in the middle of.

## Branches

- All work happens in a worktree at `.claude/worktrees/<branch>/` on a feature branch named `pr-<NN>-<slug>`. Examples: `pr-l-critical-wiring`, `pr-x-key-tempo-filters`.
- `main` is **not** protected — there is no required-reviews/required-checks ruleset. Local `./gradlew check` passing is the merge gate.
- One PR = one logical merge unit. PRs are chunky but coherent — see the active plan for cut points.

## Commits

- Conventional Commits inside the PR (`feat(catalog): …`, `fix(sync): …`, `test(parser-als): …`).
- Never `--no-verify`. Never `--no-gpg-sign`. If a hook fails, fix the hook or fix the underlying issue.
- Prefer many small commits inside a PR; the PR squash-merges at close.

## PR workflow

1. Open the PR with the template filled in (self-review checklist).
2. `./gradlew check` must be green locally before pushing. Local pass = merge gate; do not wait for CI or auto-merge.
3. For UI-touching PRs: attach a screenshot or screen-recording from a local Compose Desktop run (one image per state — empty, loaded, error).
4. Self-review the diff cold against the design-doc section the PR claims to implement.
5. Squash-merge with `gh pr merge <N> --squash --admin`. The squash message keeps the Conventional Commits format and links the design-doc section the PR implements.

## AI agents

Three AI surfaces with three roles. Each has its own guidelines under `docs/ai/`.

- **Claude Code** (`docs/ai/CLAUDE.md`) — primary implementer. Writes tasks, runs tests, captures screenshots, posts the first review.
- **Google Junie** (`docs/ai/JUNIE.md`) — second-opinion reviewer for architecture-shaped PRs (modules, schema, public APIs).
- **GitHub Copilot** (`docs/ai/COPILOT.md`) — line-level coauthor in the editor. Lower trust by default.

The human reviewer is the final gate.

## Library hygiene

- No new library without an entry in `gradle/libs.versions.toml` and a one-line justification in the PR body.
- No backwards-compat hacks. Nothing external consumes our APIs yet. Refactor freely within the PR.
- No premature abstractions. `expect/actual` only when a second target exists or is imminent. `interface` only when there are ≥2 implementations or a test fake.
- Explicitly avoided libraries: MVIKotlin, Decompose, Roborazzi, KAPT, Anvil, Realm Kotlin, Moko-resources, `androidx.lifecycle:viewmodel-compose` (Android-only — use the JetBrains KMP fork `org.jetbrains.androidx.lifecycle:*` instead), Koin, Room. Reasons live in `docs/ai/CLAUDE.md`.

## Testing

- `kotlin.test` + Kotest assertions + Turbine for Flow assertions.
- Hand-written fakes in `commonTest`. MockK only on JVM-only edges where a hand-written fake is genuinely awkward.
- TDD for parsers, hashers, signers, repository, sync orchestration: write the test, see it fail, implement, see it pass, commit.
- Power-Assert is enabled — prefer plain `assert(x == y)` over `assertEquals(x, y)`.
- Cross-module backend pipelines have integration tests under `tests/integration` (real disk, real in-memory SQLDelight, `FakeCloudBackend`).

## Visual verification (UI PRs)

For any PR that touches Compose UI:

1. Check out the worktree branch.
2. `./gradlew :app-desktop:run`.
3. Exercise the new feature end-to-end against a copy of the test library at `Z:\User\audio\Projects` — the destructive-test root.
4. Capture one screenshot per state (empty / loaded / error).
5. `gh pr comment <pr> --body-file <md>` to drop them into the PR.
6. If you see unexpected behavior, fix it in the same PR before requesting human review.
