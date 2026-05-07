# Compose Desktop UI Verification — Design

Date: 2026-05-07
Status: Approved
Owner: Claude Code (autonomous UI verification capability)

## Problem

The user is not always available to visually verify UI changes Claude makes. Sketchbook is Compose Multiplatform Desktop only (`kotlin { jvm() }`, packaged via Conveyor). Claude needs a way to *see* the Composables it builds without a human in the loop.

## Solution

Use Roborazzi's experimental Compose Desktop support (`roborazzi-compose-desktop`) as Claude's eyes. Each feature module's `jvmTest` source set holds capture tests that render Composables in chosen states and write transient PNGs to `build/roborazzi/`. Claude runs the test, reads the PNG, decides, iterates. No goldens, no CI gate, no visual regression infrastructure.

This is **Roborazzi-as-eyes**, deliberately distinct from Roborazzi-as-CI-gate. The user has previously rejected screenshot tests as a class — that rejection was about golden-image regression suites, not about programmatic capture for AI verification.

## Alternatives considered

- **Arbigent** (Maestro-based UI agent) — no Compose Desktop support; Maestro targets Android/iOS/Web only. Also, arbigent is an MCP *client*, not a server, so there is no path for Claude to drive it.
- **Paparazzi** — Android only.
- **Maestro** — no Compose Desktop / JVM target.
- **Showkase** — Android KSP only.
- **AssertJ-Swing / FEST** — Compose renders as a single Skia-painted JComponent; no semantic-tree access, would require pixel-coordinate clicks. Brittle.
- **Compose Hot Reload / HotSwan** — Hot Reload helps the user, not Claude. HotSwan auto-snapshots are Android-only and IDE-bound.
- **Custom Skiko-snapshot MCP server** — viable (~200 LOC) and would test the *real running app* instead of a synthetic test harness. Deferred. Better fit for "agent driving the live app" mode, which is option 2 in the user's framing. We are explicitly choosing option 1 (static render) for now.
- **`runDesktopComposeUiTest` + reflection-based `captureToImage`** — zero deps but reinventing Roborazzi badly. Skip.
- **ComposablePreviewScanner + `@Preview` annotations** — would auto-enumerate states, but adds a KSP dep and a `@Preview` pattern not currently in the codebase. User feedback ("no unnecessary libs") rules it out for now.

## Architecture

### Components

- **`gradle/libs.versions.toml`** — adds `roborazzi` version, plugin alias, and `roborazzi-compose-desktop` library alias.
- **`build-logic/.../ScreenshotTestsConventionPlugin.kt`** (alongside the existing `kmp-compose` convention plugin) — applies `io.github.takahirom.roborazzi`, registers `roborazzi-compose-desktop` and Compose UI test deps in the module's `jvmTest` source set.
- **Per-feature module `build.gradle.kts`** — opts in with one line: `id("sketchbook.screenshot-tests")`.
- **`*Screenshots.kt` test classes** — one per screen-level Composable, in that module's `src/jvmTest/kotlin/...`. Each test calls `runDesktopComposeUiTest { setContent { Screen(state = …) }; onRoot().captureRoboImage("…png") }`.

### Data flow (per change)

1. Claude edits a Composable.
2. Claude adds or updates the matching `*Screenshots.kt` capture test (sample state authored inline).
3. `./gradlew :shared:feature-X:recordRoborazziJvm`.
4. Claude reads the resulting PNG from `build/roborazzi/`.
5. Iterate or move on. PNG is transient — gone on next `clean`.

No `compareRoborazziJvm`, no goldens, no CI step.

### PR-time deliverable (this integration PR)

Capture tests for all 5 screens — ProjectList, ProjectDetail, Timeline, Inbox, Settings — each rendered in at least one realistic state. The resulting PNGs are uploaded as GitHub PR description attachments (not committed to the repo tree) so reviewers can confirm the setup actually works end-to-end.

## Risks and mitigations

- **Compose 1.11.0-rc01 × Roborazzi compatibility.** Sketchbook is on a release candidate; Roborazzi's Skiko hooks could lag a Compose RC. Mitigation: smoke-test the convention plugin on one module before rolling to all five. If it breaks, hold the rollout until 1.11.0 stable or pin Roborazzi to a known-good RC-compatible version.
- **JUnit version mismatch.** Codebase uses `kotlin.test` (JUnit 4 API). Roborazzi is JUnit 4 compatible — no conflict expected.
- **Test runtime.** Compose UI tests boot a Skia surface (~1–3s each). Five screens × one state each ≈ sub-15s. Acceptable.

## Explicitly out of scope

- `Modifier.testTag` rollout — only needed for interaction-driven tests; this design is static-render-only.
- Goldens, `verifyRoborazziJvm`, CI gate.
- ComposablePreviewScanner / `@Preview` migration.
- Live-app driving / custom MCP server (option 2 — deferred; revisit if static capture proves insufficient).

## Decisions log

- **Static-only verification** (option 1): render Composables in chosen states, no live-app driving. Live-app option deferred.
- **Per-feature module location**: tests live next to the Composables they verify; matches existing `:shared:feature-*` `commonTest` pattern.
- **Transient PNGs**: `build/roborazzi/` is gitignored; no goldens committed; cleaned by `./gradlew clean`.
- **Convention plugin**: per-module opt-in is one line; Roborazzi config single-sourced.
- **Hand-authored tests**: no `@Preview` / KSP overhead. Approach B (ComposablePreviewScanner) explicitly rejected for now.
