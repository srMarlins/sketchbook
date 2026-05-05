# Contributing

This repo is the home of the Kotlin/Compose Multiplatform rewrite of the audio catalog + sync app. The active design doc is `docs/plans/2026-05-05-sync-versioning-design.md`. The active implementation plan is `docs/plans/2026-05-05-kotlin-rewrite-impl-plan.md`.

## Branches

- All work happens on a feature branch named `pr-<NN>-<slug>`. Examples: `pr-04-catalog-sqldelight`, `pr-12-feature-projects`.
- `main` is protected: no direct pushes, no force-push, PR + green CI + 1 review required.
- One PR = one logical merge unit. PRs are chunky but coherent — see the implementation plan for the cut points.

## Commits

- Conventional Commits inside the PR (`feat(catalog): …`, `fix(sync): …`, `test(parser-als): …`).
- Never `--no-verify`. Never `--no-gpg-sign`. If a hook fails, fix the hook or fix the underlying issue.
- Prefer many small commits inside a PR; the PR squash-merges at close.

## PR workflow

1. Open the PR with the template filled in (self-review checklist).
2. Push commits until CI is green (`./gradlew check`).
3. Request review from the AI agents (`gh pr review …`) — at minimum one Claude Code review.
4. For UI-touching PRs: attach a screenshot or screen-recording from a local Compose Desktop run (one image per state — empty, loaded, error).
5. Squash-merge at close. The squash message keeps the Conventional Commits format and links the design-doc section the PR implements.

## AI agents

Three AI surfaces with three roles. Each has its own guidelines under `docs/ai/`.

- **Claude Code** (`docs/ai/CLAUDE.md`) — primary implementer. Writes tasks, runs tests, captures screenshots, posts the first review.
- **Google Junie** (`docs/ai/JUNIE.md`) — second-opinion reviewer for architecture-shaped PRs (modules, schema, public APIs).
- **GitHub Copilot** (`docs/ai/COPILOT.md`) — line-level coauthor in the editor. Lower trust by default.

The human reviewer is the final gate.

## Library hygiene

- No new library without an entry in `gradle/libs.versions.toml` and a one-line justification in the PR body.
- No backwards-compat hacks. This is greenfield Kotlin; nothing else consumes our APIs yet. Refactor freely within the PR.
- No premature abstractions. `expect/actual` only when a second target exists or is imminent. `interface` only when there are ≥2 implementations or a test fake.
- Explicitly avoided libraries: MVIKotlin, Decompose, Roborazzi, KAPT, Anvil, Realm Kotlin, Moko-resources, `androidx.lifecycle:viewmodel-compose`, Koin, Room. Reasons live in the design doc §2.1.

## Testing

- `kotlin.test` + Kotest assertions + Turbine for Flow assertions.
- Hand-written fakes in `commonTest`. MockK only on JVM-only edges where a hand-written fake is genuinely awkward.
- TDD for parsers, hashers, signers, repository, sync orchestration: write the test, see it fail, implement, see it pass, commit.
- Power-Assert is enabled — prefer plain `assert(x == y)` over `assertEquals(x, y)`.

## Visual verification (UI PRs)

For any PR that touches Compose UI:

1. Check out the PR branch.
2. `./gradlew :app-desktop:run` (or `:app-gallery:run` for primitives).
3. Exercise the new feature end-to-end against a copy of the test library at `Z:\User\audio\Projects` — the destructive-test root.
4. Capture one screenshot per state (empty / loaded / error).
5. `gh pr comment <pr> --body-file <md>` to drop them into the PR.
6. If you see unexpected behavior, fix it in the same PR before requesting human review.
