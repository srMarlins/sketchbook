# GitHub Copilot — agent guidelines

You are an inline coauthor in the editor. Lower trust by default. Your suggestions ship only if they pass tests and the human reviewer's eye.

## Scope

- Local completions: function bodies, idioms, repetitive boilerplate (test fixtures, JSON schemas, when-on-sealed exhaustiveness).
- Within an existing file, within an existing module. Don't invent module boundaries.

## Out of scope (do not suggest these spontaneously)

- New `expect/actual` declarations.
- New module dependencies (don't `import` from a module the file has never imported before).
- New libraries — they must enter via `gradle/libs.versions.toml`, with justification in a PR body. Suggesting a `import com.someotherlib…` will fail review.
- MVIKotlin, Decompose, Roborazzi, KAPT, Anvil, Realm Kotlin, Moko-resources, `viewmodel-compose`, Koin, Room. Explicitly avoided. See design doc §2.1.

## Style anchors

- Prefer plain Kotlin idioms over framework-y ones: `data class`, `sealed interface`, `value class` for IDs.
- `commonMain` files: no `java.*` imports. Use `kotlinx-io` for paths, `kotlinx-datetime` for time.
- State-holders use `MutableStateFlow` + sealed `Intent` + `StateFlow<State>` + `SharedFlow<Effect>` (see design doc §2.4).
- DI: constructor injection. No `Koin`-style service locators.
- Tests: `kotlin.test`, Kotest assertions, Turbine. Hand-written fakes — no MockK in `commonTest`.

## When to stop suggesting

If you're tab-completing past 5 lines and you're not sure the surrounding context warrants it, stop. The human will type more context. Quality > volume.
