# Claude Code — agent guidelines

You are the primary implementer on Sketchbook (Kotlin + Compose Multiplatform desktop). Architecture, module graph, state-holder pattern, dispatcher injection, library list, testing setup, coroutine idioms — all derivable from `settings.gradle.kts`, `gradle/libs.versions.toml`, and a representative file in each module. Read those before writing; don't ask for what you can grep.

This file is for things you *can't* observe: war stories and don't-rules that are easy to violate without noticing. Worktree rule, branch protection, merge gate, batch-checkpoint policy etc. live in auto-memory and load on every session.

## Things you'd only know from a war story

- **`.als` parser must stream.** A DOM parse blew RAM to 25 GB on a 543 MB project. Use StAX + the free-subtree pattern (null out subtree references after each `<Track>` / `<DeviceChain>`). Test with a heap cap.
- **Hardlink, never symlink, sample blobs into project working trees.** Ableton hates symlinks. Cross-volume falls back to copy.
- **No `Dispatchers.IO` hard-coding.** Inject `@IODispatcher` / `@DefaultDispatcher` via Metro so tests can swap them. Easy to write `withContext(Dispatchers.IO)` without thinking.
- **No JVM-only types in `commonMain`** (`java.io.File`, `java.time.*`). Compiles fine in `jvmMain`, silently breaks any future non-JVM target. Use `kotlinx-io` paths and `kotlinx-datetime` instants.
- **No MockK in `commonTest`.** Hand-written fakes only — MockK's KMP edges bite.
- **GCS scope is `devstorage.read_write`, not `cloud-platform`.** The JWT scope is the second IAM gate; narrow it.
- **GCS IAM is bucket-scoped, not project-scoped.** `roles/storage.objectAdmin` + `roles/storage.legacyBucketReader` on the data bucket only. Project-level bindings are wrong.
- **`runCatching` swallows `CancellationException`.** Don't wrap a suspend call in `runCatching` — `Throwable` catches the cancellation signal, the coroutine completes "successfully," and structured concurrency unwinds silently. Use `runCatchingCancellable` (`shared/core/.../Coroutines.kt`) or `try { ... } catch (c: CancellationException) { throw c } catch (t: Throwable) { ... }` at suspend boundaries. Bare `runCatching` is fine when the lambda contains only non-suspend code (file I/O, parsing, system properties).

## State holders + DI

State holders are KMP `ViewModel`s contributed with `@ContributesIntoMap(AppScope::class) @ViewModelKey @Inject` and acquired per screen with `metroViewModel<VM>()`. Full DI policy in `docs/architecture/dependency-injection.md`.

## Cloud + release gotchas

The flows live in `shared/cloud/` and `docs/runbooks/release.md`. The "you'd only know if you'd been bitten" rules:

- **Two buckets, two SAs, never cross the streams.** Data → `gs://sketchbook-jtf-2026` (`sketchbook-app` SA, JSON key, local only). Releases → `gs://sketchbook-releases` (`sketchbook-release-uploader` SA, WIF-impersonated in CI, no JSON key exists). Release-uploader SA must NOT appear in app code; app SA must NOT appear in release code.
- **Never log the SA JSON, the `private_key`, or the access token.** Redact (`token = ya29.****`) in any auth-handling Kermit statement.
- **Don't add a `credentials_json` fallback for release CI.** It would fail anyway — `iam.disableServiceAccountKeyCreation` blocks key creation for that SA. WIF is the right answer.
- **Test fixtures may generate ephemeral RSA keypairs in-memory.** PEM string literals (`-----BEGIN PRIVATE KEY-----`) in source are fine; *embedded actual key material* is not. Gitleaks will block; never test the alarm.
- **`CONVEYOR_SIGNING_KEY` is the crypto root** of every auto-update ever shipped. Never echo, never log, never write to disk inside the repo. Lost = manual reinstall on every client.
- **Don't change `app.site.base-url`** in `conveyor.conf` without a trampoline release. Once an installer ships with a base URL, that's where it polls forever.
- **GitHub Releases hold notes only**, never binary assets. The source repo is private; auto-update fetches anonymously from the public bucket.
- **Bad-release recovery is forward-only.** Cut the next version; never delete a published release — auto-update is monotonic.

## When to stop and ask

- You're about to introduce something not derivable from existing code (new library, new dispatcher, new module-boundary direction).
- A "small" change touches more than one module's public API.
- A test you can't reproduce locally is failing in CI.

Ask, don't guess.
