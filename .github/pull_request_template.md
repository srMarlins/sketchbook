## What

<!-- One paragraph: what this PR does, in plain language. -->

## Why

<!-- One paragraph: motivation + intent. Reference any standing rule it touches (e.g. "tightens the credential redaction rule from CLAUDE.md"). -->

## How

<!-- Bullet the key implementation choices. Mention any trade-offs. -->

## Self-review checklist

- [ ] Branch named `pr-<NN>-<slug>`.
- [ ] Conventional Commits used in every commit message.
- [ ] Aligned with the design doc section this PR implements (link above).
- [ ] Tests added for new behavior; `./gradlew check` green locally.
- [ ] No new library introduced without a `gradle/libs.versions.toml` entry + justification below.
- [ ] No new `expect/actual` introduced unless a second target consumes it.
- [ ] No `interface` introduced with only one implementation (or a test fake).
- [ ] No `--no-verify`, `--no-gpg-sign`, or hook bypass on any commit.
- [ ] Module boundaries from `docs/ai/CLAUDE.md` respected; no UI → SQLDelight reach-throughs.

## UI verification (delete if non-UI)

- [ ] `./gradlew :app-desktop:run` launched locally.
- [ ] Screenshot per state attached below: empty / loaded / error.

<!-- Drop screenshots here. Use `gh pr comment <pr> --body-file <md>` if pasting via CLI. -->

## New libraries (delete if none)

| Library | Version | Why this and not what's already in the catalog |
|---|---|---|

## Reviewer notes

<!-- Anything the reviewer should focus on, or known follow-ups deferred to a later PR. -->
