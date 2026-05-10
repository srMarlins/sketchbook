# Phase 3 agent prompt — Sketchbook Firebase migration

Paste this entire file as the opening message to a fresh Claude Code session.

---

You are landing **Phase 3** of the Sketchbook Firebase migration in one branch. Phases 0–2 are already merged on the working branch `spike/firebase-poc`. Your job: read the design, do an honest discovery pass against the actual code (the design doc is aspirational and may not match every name), then implement Phase 3 as a series of clean, individually-buildable commits. **Do not skip discovery.**

## Repo + branch

- Working directory: `/Users/jaredfowler/Developer/sketchbook`
- Branch you must commit to: `spike/firebase-poc` (already checked out)
- Main: `main`
- Git user: Jared Fowler

## Authoritative design

Read these in full before writing code:

1. `docs/plans/2026-05-08-firebase-migration-design.md` — the migration plan. Phase 3 is at §"Phase 3: MetadataStore + listeners". The design doc was written before Phase 2 was implemented, so some names it cites (e.g. `CloudTreeRegistry`, `TreeRegistryDoc`, `MachinesDoc`, `plugin_manifest_<host>.json`) may not exist verbatim. Treat the doc as **intent**, the code as **truth** — when they disagree, infer the intent and adapt.
2. `docs/runbooks/firebase-deploy.md` — how to deploy rules/indexes/functions.
3. `firestore.rules`, `storage.rules`, `.firebaserc`, `firebase.json` — current deployed Security Rules + project aliases.

## What is already done

- **Phase 1:** Firestore + Storage Security Rules deployed to `sketchbook-jtf-2026` (dev alias) and `sketchbook-jtf-prod-2026` (prod alias). Both projects have Google Sign-In enabled + Desktop OAuth clients. `firestore.indexes.json` exists but is empty (`{"indexes": [], "fieldOverrides": []}`). Add indexes here as you write compound queries.
- **Phase 2:** Auth + Storage rewired. Highlights:
  - `shared/auth/src/jvmMain/kotlin/com/sketchbook/auth/firebase/`:
    - `FirebaseAuthSession.kt` — production `AuthSession` impl. Google OAuth → JWKS verify → Identity Toolkit exchange → Firebase ID + refresh tokens. State is a sealed `SessionTokens` flow; two narrow mutexes (sign-in vs refresh).
    - `IdentityToolkitClient.kt` — REST wrapper for `signInWithIdp` + `securetoken.googleapis.com`. Throws `IdentityToolkitException(statusCode, errorCode, rawBody)`.
    - `GoogleIdTokenVerifier.kt` — `fun interface` + `JwksGoogleIdTokenVerifier` impl using Nimbus JOSE+JWT, single `AtomicReference<Entry?>` JWKS cache.
    - `FirebaseConfig.kt` — sealed Dev/Prod; selected by `-Dsketchbook.env=prod` system property; injected via DI (not called statically from app code).
    - `FirebaseTokens.kt` — value types (`FirebaseTokens`, `VerifiedGoogleIdToken`).
  - `shared/cloud/src/jvmMain/kotlin/com/sketchbook/cloud/FirebaseBlobStore.kt` — GCS REST against the Firebase-managed bucket using Firebase ID tokens as bearer.
  - `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/DesktopAppGraph.kt` — Metro DI graph; provides `FirebaseConfig`, `IdentityToolkitClient`, `GoogleIdTokenVerifier`, `AuthSession`, `SyncQueue`.
  - `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/UserGraphHolder.kt` and `repo/SwappableSyncQueue.kt` — both observe auth state with `map { (it as? SignedIn)?.userId }.distinctUntilChanged()` so the backend rebuilds only on UID transitions.
  - `KEY_CLOUD_BUCKET` + Settings bucket UI deleted. `OAuthCloudCredentials` renamed → `FirebaseCloudCredentials`. `tools/grant-user.ps1` + `docs/plans/2026-05-06-oauth-user-scope-*.md` deleted.
- **Phase 0 spike** code is **deleted from HEAD** but lives in git history. The Pattern A1 (storage-hijack) mechanism was validated end-to-end against real Firebase. You will need to lift two spike files back into production. Source them from git:
  - `git show 8e083c8:spikes/firebase-poc/src/main/kotlin/com/sketchbook/spike/firebase/AuthStateInjector.kt`
  - `git show 8e083c8:spikes/firebase-poc/src/main/kotlin/com/sketchbook/spike/firebase/JvmFirebasePlatform.kt`
  - `git show 8e083c8:spikes/firebase-poc/src/main/kotlin/com/sketchbook/spike/firebase/Probes.kt` (the `listener-sanity` probe shows the wiring shape)

The spike's `secrets.local.kt` and `secrets.prod.local.kt` are **gitignored** and live at `spikes/firebase-poc/...` — **the directory is deleted**, so those credentials are no longer on disk. For Phase 3 testing against real Firebase you'll either need to (a) run against the emulator, or (b) ask the user to re-populate credentials. The OAuth client ID for the running app is read from `-Dsketchbook.oauth.client_id` system property (placeholder `REPLACE_ME.apps.googleusercontent.com` baked at `DesktopAppGraph.kt:OAUTH_CLIENT_ID`).

## What Phase 3 must deliver

In priority order. Do NOT bundle scope creep — if you find something that should change but isn't load-bearing for Phase 3, write a short follow-up note at the end and leave the code alone.

1. **Add gitlive dependencies to consuming modules.** Catalog entries already exist in `gradle/libs.versions.toml` (`gitlive-firebase-app`, `gitlive-firebase-auth`, `gitlive-firebase-firestore`, pinned at 2.4.0). On JVM, gitlive transitively requires `kotlinx-coroutines-swing` for `Dispatchers.Main` (firebase-java-sdk uses it for listener callbacks — discovered in Phase 0 spike, documented in design doc §"JVM Main-dispatcher dep").

2. **Pattern A1 token injection plumbing.** Lift the spike's `AuthStateInjector` + `JvmFirebasePlatform` into `shared/auth/src/jvmMain/kotlin/com/sketchbook/auth/firebase/` (or wherever fits the architecture). Wire `FirebaseAuthSession.accessToken()` so that **before** any Firestore listener call, the in-memory `FirebasePlatform` storage holds a `FirebaseUserImpl` JSON whose ID token matches what we just minted. Storage key is `"com.google.firebase.auth.FIREBASE_USER"`. This is the load-bearing trick — verified in the spike but never wired into production code.

3. **`MetadataStore` port** (new interface, commonMain in a sensibly-placed module — likely `shared/cloud` or a new `shared/metadata`). Surface roughly:
   ```kotlin
   interface MetadataStore {
       suspend fun <T> getDoc(path: DocPath, serializer: KSerializer<T>): T?
       suspend fun <T> setDoc(path: DocPath, value: T, serializer: KSerializer<T>)
       suspend fun <T> updateDoc(path: DocPath, transform: (T) -> T, serializer: KSerializer<T>)
       fun <T> observeDoc(path: DocPath, serializer: KSerializer<T>): Flow<T?>
       fun <T> observeCollection(path: CollectionPath, serializer: KSerializer<T>): Flow<List<T>>
       suspend fun acquireLock(path: DocPath, holder: String, ttl: Duration): Boolean
       suspend fun refreshLock(path: DocPath, holder: String, ttl: Duration): Boolean
       suspend fun releaseLock(path: DocPath, holder: String)
   }
   ```
   Reconcile with the existing `CloudBackend` lock surface (`shared/cloud/.../CloudBackend.kt:67` and `:80`) — locks move from `BlobStore` to `MetadataStore` per the design doc.

4. **`FirestoreMetadataStore` adapter** (jvmMain). Uses gitlive's `Firebase.firestore`. App code MUST NOT import `dev.gitlive.firebase.*` outside this adapter (design doc §"gitlive `firebase-kotlin-sdk` is at v0.6.x" — wrapping the SDK is a stated risk-mitigation).

5. **Discover what plays the `CloudTreeRegistry` / `CloudMachineProfileStore` role today.** The design doc names don't exist verbatim. Likely candidates: scan `shared/repository`, `shared/sync`, `shared/catalog` for the actual classes that own tree-registry / machine-profile state. Rewrite them to use `MetadataStore`. Old method signatures stay; impl becomes Firestore reads/writes.

6. **`SyncCoordinator`** — new component. Subscribes to `/users/{uid}/trees` collection via `MetadataStore.observeCollection`. On each delta where `doc.head_rev > tree_registry_cache.head_rev`, fires `PullPoller.pollOnce(...)`. Replaces the 30-second polling loop in `PullPoller` (the loop deletes; `pollOnce` stays). The `tree_registry_cache` table is in the catalog DB (`shared/catalog/src/commonMain/sqldelight/com/sketchbook/catalog/db/Catalog.sq`) — it gained `head_rev` + `head_gen` columns in migration v13 (recent commits).

7. **`SnapshotPipeline` post-CAS hook** (`shared/sync/.../SnapshotPipeline.kt`): after the blob CAS succeeds, run a Firestore transaction updating `head_rev` + `head_gen` + `head_updated_at` + `head_updated_by_host` on `/users/{uid}/trees/{treeId}`. Single round-trip.

8. **Delete `PullPoller`'s polling loop** (`while(true){listManifests; delay(30s)}`). Keep `pollOnce(...)` — the new `SyncCoordinator` calls it.

9. **Move locks `BlobStore` → `MetadataStore`.** Leases become `/users/{uid}/locks/{treeId}` Firestore docs. Atomic test-and-set via transactions. Expiry enforced by Security Rules (extend `firestore.rules`). Delete the lock methods from `CloudBackend.kt:67–80`.

10. **Delete the CloudDoc surface.** Whatever exists for `readDoc/writeDoc/listDocs` (search `shared/cloud/`) goes; `CloudDocKey` / `CloudDocKeys` if present; `TreeRegistryDoc` / `MachinesDoc` wire types — replace with Firestore-shaped `@Serializable` data classes that the new adapters use.

11. **`revokeMySession` Cloud Function** for security-commitment #3 Part B. Phase 2 shipped Part A (local-state clearing under mutex); this is the missing Admin-SDK piece. Function spec:
    - Trigger: HTTPS callable
    - Auth: requires a valid Firebase ID token (the caller's own)
    - Action: `admin.auth().revokeRefreshTokens(context.auth.uid)`
    - Deployment: `functions/` directory at repo root, Node runtime, deployed via `firebase deploy --only functions` (extend `firebase.json` to declare the functions source dir)
    - Client-side: `FirebaseAuthSession.signOut()` calls this function (via REST or via gitlive's `Functions` SDK) **before** clearing local state. Best-effort: on failure, log and continue with local clear — don't block sign-out on network errors.
    - Update `docs/plans/2026-05-08-firebase-migration-design.md` security-commitment #3 to reflect Part B shipping.

12. **Tests.** All Firestore-touching tests use the Firebase Emulator Suite via `firebase emulators:exec`. Add a Gradle integration-test source set if needed. New tests:
    - `FirestoreMetadataStoreTest` — CRUD + listener + lock semantics
    - `FirestoreTreeRegistryTest` (or whatever the discovered name becomes) — migration coverage
    - `SyncCoordinatorTest` — listener delta → pollOnce dispatch
    - `revokeMySession` function tests if the runtime supports them (Firebase Functions SDK has its own test harness)

## Constraints

- **No scope creep.** If something looks broken but isn't on the list above, write it in a follow-up section at the end of the design doc and move on.
- **Hold the security commitments.** See `docs/plans/2026-05-08-firebase-migration-design.md` §"Security commitments". #1 (JWKS verify), #2 (keyring namespace), #3 Part A done; #3 Part B is item 11 above; #4 (Rules-unit-testing in CI) and #5 (no service account keys) ship as part of this phase or a follow-up — your call based on time budget.
- **Idiomatic Kotlin coroutines.** No `runBlocking` in production paths. No `init { scope.launch { ... } }` side effects in classes — make startup explicit. Mutex usage: never held across user-driven I/O (browser flows, etc.). State as `StateFlow` of sealed types, not loose `var`s. The Phase 2 review (commits `9f44807` onward) sets the bar.
- **`accessToken()` → `idToken()` rename** was deferred from Phase 2. The design doc's `AuthSession` interface sketch uses `idToken()`. If you do this rename, do it as its own commit early in the phase so subsequent commits don't bury the diff. Touches: `AuthSession.kt`, `FirebaseAuthSession.kt`, `DesktopAuthSession.kt`, `FirebaseCloudCredentials.kt`, `FakeAuthSession.kt`, `SettingsViewModelTest.kt`, test fixtures.
- **Tests must compile + pass at each commit.** Use `./gradlew :shared:auth:jvmTest :shared:cloud:jvmTest :app-desktop:compileKotlinJvm` as the smoke test after each step.
- **Commits**: one logical step per commit. Match the Phase 2 commit-message style — see `git log --oneline`. Title format: `<type>(<scope>): phase 3 step N — <summary>`. Body explains why, not what.

## Firebase project context

- Dev: `sketchbook-jtf-2026` — Web API key `AIzaSyB132N_cFwVnLJEff3qoMeYqEoQUNtdIR8`, bucket `sketchbook-jtf-2026.firebasestorage.app`
- Prod: `sketchbook-jtf-prod-2026` — Web API key `AIzaSyAKqe8qY63pS9UJJAmm908ik2vkm3ZCBjA`, bucket `sketchbook-jtf-prod-2026.firebasestorage.app`
- These public values are baked into `FirebaseConfig.kt`. OAuth client IDs for the running app are loaded via `-Dsketchbook.oauth.client_id` system property — the user has the values out of band.
- `gcloud` is authenticated as `jtfowler93@gmail.com`. `firebase-tools` CLI is installed and logged in. You can deploy rules/functions yourself.

## Workflow

1. Start with discovery: read the design doc, then walk the actual code to map what exists vs what the design names. Write up findings as the first commit on the doc itself (or as a scratch note) before changing any production code.
2. Implement in the order above. Commit per step.
3. Run the full local test suite at the end: `./gradlew test compileKotlinJvm`.
4. End with a summary message to the user: what shipped, what's deferred, links to anything they need to click in a Console.

If you hit a true blocker (e.g. gitlive SDK signature mismatch, emulator config issue), stop and ask the user — don't fabricate workarounds. They are technical and can answer specific questions.
