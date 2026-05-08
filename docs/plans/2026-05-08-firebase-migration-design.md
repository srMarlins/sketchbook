# Firebase migration: Auth + Firestore + Storage

**Date:** 2026-05-08
**Branch:** TBD (off `main` after PR #125 merge)
**Status:** draft, awaiting approval
**Supersedes:** [`2026-05-08-poll-efficiency-design.md`](./2026-05-08-poll-efficiency-design.md) — `heads.json` polling design is replaced by Firestore real-time listeners. The v13 catalog migration (commit (1) of that plan) stays — `head_rev` / `head_gen` columns serve the same role as a local cache of what listeners push us.

## Decisions captured (2026-05-08)

- **Auth pattern:** A (token injection). Pattern B is the documented fallback if Phase 0 invalidates A's `FirebasePlatform` hook assumption.
- **Firebase projects:** `sketchbook-dev` + `sketchbook-prod`. No staging tier (emulator covers it).
- **Firebase Web API key:** baked into the binary. It's a public client identifier, not a secret.
- **Mobile / web targets:** out of scope for the next 6 months. Architecture stays KMP-ready but design optimizes for desktop.
- **No feature flag, no parallel legacy path, no migrator.** Sketchbook hasn't shipped to real users yet — we rewire directly to Firebase and delete the GCS-bucket / `grant-user.ps1` code as we go. This collapses what was a six-phase rollout into four phases.
- **Re-signup behaviour:** new Google account = new Firebase UID = fresh tenant. Old data orphaned (cleaned up by ops later).

## TL;DR

Migrate Sketchbook's cloud surface from "user-provided GCS bucket + manual `grant-user.ps1` IAM" to a fully Firebase-managed model: Firebase Auth for identity, Firestore for metadata + real-time push, Firebase Storage for blobs. Self-service onboarding (no admin in the loop), sub-second cross-machine sync via Firestore listeners, GCP-credit-friendly billing, same KMP target shape.

The plan is **smaller than it sounds** because two-thirds of our cloud code is already REST-based — we don't need a heavy Firebase SDK adoption on JVM (the SDK has gaps there anyway). And because Sketchbook hasn't shipped to real users, we **rip and replace** rather than running parallel paths behind a feature flag — which collapses the migration into four focused PRs. The real changes:

1. Add a Firebase ID token exchange step after our existing Google OAuth flow.
2. Introduce a new **`MetadataStore`** port backed by Firestore (via GitLive's `firebase-kotlin-sdk`) for listeners + per-doc CRUD.
3. Repoint blob calls at the Firebase-managed bucket using Firebase ID tokens; otherwise the `DirectGcsBackend` machinery is unchanged.

## Goals

1. **Self-service onboarding.** A new user signs in with Google in the desktop app and is fully productive within seconds — no admin runs `grant-user.ps1`, no GCP project creation, no bucket name to type.
2. **Sub-second cross-machine sync.** A push from machine A is visible on machines B and C within ~1s, via Firestore listeners — not polling.
3. **Defense-in-depth security.** Auth identity is Firebase-verified; Firestore Security Rules enforce per-user isolation server-side; blob access requires Firebase-issued tokens. No client-only trust boundaries.
4. **Bounded migration scope.** Two-thirds of the existing cloud code (`DirectGcsBackend`'s blob path, `KeyringTokenStore`, `OAuthClient`) is reused. We don't rewrite what works.
5. **Forward-compatible to multi-user collab (v1.2).** The Firestore data model is structured so adding `collaborators` is additive, not a re-shaping.
6. **Testable end-to-end.** Firebase Emulator Suite for local dev; integration tests run against a real (or emulated) Firestore instance, not just mocks.

## Non-goals

- **Mobile / web client targets.** The KMP shape stays JVM-only-shipping today. Mobile/web become possible because we're using a KMP SDK, but they're not part of this migration.
- **Replacing SQLite catalog.** Local mirror stays on SQLite. Firestore client-side persistence isn't supported on JVM (see "Critical findings"), and our SQLite catalog already plays that role better than a Firestore disk cache would.
- **Server-side functions.** No Cloud Functions in this migration. If we ever need server logic (validation, side-effects), that's a separate scope.
- **Migrating the v=3 manifest wire format.** Manifests stay in Storage as today's JSON files. Only metadata (HEAD pointers, registry, profile) moves into Firestore.
- **Per-host kill switches.** Better device revocation is a follow-up; this migration is at parity with today's "revoke OAuth on the Google account" model.
- **Compaction / archival of Firestore data.** Tree-journal subcollection growth is bounded for our scale; revisit if a single user's tree count or journal volume exceeds projections.

## Critical findings from the GitLive / firebase-java-sdk audit

The plan **depends on** these being true. They reshape what we use the SDK for and what we don't.

### Firebase Auth on JVM: the SDK's Google-sign-in path is stubbed; use REST exchange instead

Verified from source code (not docs — the docs are sparse) in `firebase-java-sdk` and `firebase-kotlin-sdk`'s `firebase-auth/src/jvmMain/`:

**Implemented on JVM (verified working):**
- `signInAnonymously()` — real HTTP POST
- `signInWithCustomToken(token)` — real HTTP POST
- `signInWithEmailAndPassword(...)` — real
- `getAccessToken(forceRefresh)` — real (caches + refreshes ID tokens)
- Auth state / ID token listeners — real
- Emulator support — real

**Stubbed on JVM (throws at runtime):**
- `signInWithCredential(...)` — `TODO()`. **This is the method gitlive's docs reference for Google sign-in.** Compiles, throws at runtime.
- `GoogleAuthProvider.getCredential(...)` — throws `NotImplementedError()`
- `sendPasswordResetEmail`, `confirmPasswordReset`, `fetchSignInMethodsForEmail`, `applyActionCode`, `setLanguageCode` — all `TODO()` stubs

So the **idiomatic gitlive approach** for Google sign-in (`signInWithCredential(GoogleAuthProvider.getCredential(idToken))`) does not work on JVM today, despite the auth module showing 80% coverage in the platform matrix (that's an aggregate across all platforms, not a JVM-specific number).

**Solution: REST token exchange + token injection.** We:

1. Keep our existing OAuth machinery (`OAuthClient` + `GoogleAuthSession` + `KeyringTokenStore` ships today, well-tested) for the Google leg.
2. Add one REST call to [Identity Toolkit `accounts:signInWithIdp`](https://docs.cloud.google.com/identity-platform/docs/reference/rest/v1/accounts/signInWithIdp) which converts the Google ID token into a Firebase ID token + refresh token, minting a Firebase UID stable per Google `sub`.
3. Hold those tokens in our own `AuthSession`. The gitlive Firestore SDK on JVM accepts a token-provider hook via `FirebasePlatform`; we feed it our token on demand.

There are three patterns for plugging this into the SDK. **We pick Pattern A (token injection) as the chosen approach** — see the dedicated [Auth pattern decision](#auth-pattern-decision-pattern-a-token-injection) section below for the side-by-side comparison and security reasoning. Pattern B (custom token via Cloud Function) is the documented fallback if Phase 0 invalidates A's load-bearing assumption. Pattern C is dismissed.

End state under either A or B:
- Google does identity (provider login UI, MFA, password reset, account recovery)
- Firebase Auth issues our app's session tokens (Firestore Rules check against Firebase UID)
- KeyringTokenStore stores the Firebase refresh token (replacing today's Google refresh token)

### Firebase Storage on JVM is essentially absent

The firebase-java-sdk lists supported products: `Firestore, Realtime Database, Cloud Functions, Remote Config, Installations, partial Auth`. Storage is **not in the list**.

**Solution:** call Firebase Storage's REST/JSON API directly with the Firebase ID token as the bearer, exactly as `DirectGcsBackend` does today against GCS. Firebase Storage *is* GCS underneath — same JSON API, same `if-generation-match` semantics, same resumable-upload protocol, same path conventions. We rename `DirectGcsBackend` → `FirebaseBlobStore`, swap the auth source, and we're done. Most of the file is unchanged.

### Firestore on JVM works, but with no client-side persistence

Firestore is well-supported in firebase-java-sdk and gitlive's wrapper. Listeners, transactions, queries — all good. **But:** [Firestore offline persistence is "supported only in Android, Apple, and web apps"](https://firebase.google.com/docs/firestore/manage-data/enable-offline). On JVM, every listener subscription is in-memory; reconnection re-fetches from the server.

**This is fine for us** because our SQLite catalog (`tree_registry_cache`, `tree_sync_state`, `tree_journal`) already plays that role and survives restart. The cache layer is **ours**, not Firestore's. Listeners write into the SQLite mirror as they fire; reads go to SQLite first; the UI binds to SQLite-backed Flows. The Firestore listener is a pump, not the storage substrate.

### gitlive `firebase-kotlin-sdk` is at v0.6.x ("alpha")

The library is widely adopted but officially "alpha" — minor version bumps occasionally break common APIs. Mitigation:

- Wrap it behind our own `MetadataStore` port. App code never imports `dev.gitlive.firebase.*`.
- Pin the version in `libs.versions.toml`. Don't bump impulsively.
- Have an integration test pinned to "real Firestore" (emulator) so SDK upgrades surface incompatibilities at PR time.

### Firebase ID tokens are valid bearer tokens for direct GCS

[Cloud Firestore REST docs](https://firebase.google.com/docs/firestore/use-rest-api) and [Firebase Storage docs](https://firebase.google.com/docs/storage/web/start) both confirm: a Firebase Auth ID token is accepted as a bearer for both Firestore and Storage REST calls. This means:

- Our `DirectGcsBackend`-style code keeps working unchanged for blobs (now pointed at the Firebase-managed bucket).
- Firestore REST is a fallback if the SDK fails us in production.
- Cross-product auth is unified: one token type, one refresh path, one revoke.

## Auth pattern decision: Pattern A (token injection)

**Decision:** Pattern A is the chosen approach. Pattern B is the documented fallback if Phase 0 invalidates A's load-bearing assumption. Pattern C is dismissed.

### Why Pattern A

Both A and B end the user's sign-in with a valid Firebase ID token, server-verified on every Firestore / Storage request. Per-user security guarantees are equivalent. The decision is about **Sketchbook's own attack surface and operational complexity**.

| Dimension | Pattern A (token injection) | Pattern B (Cloud Function + custom token) |
|---|---|---|
| Hops on sign-in | Client → Identity Toolkit | Client → Cloud Function → Identity Toolkit |
| Services Sketchbook operates | Zero | One Cloud Function |
| Sketchbook-held secrets | None (Firebase Web API key is public) | **Admin SDK private key** in Function env |
| Worst-case if Sketchbook is breached | Attacker steals client binary; can't impersonate users | Attacker leaks Admin SDK key; **mints tokens for any UID in the project** |
| Cold-start latency | 0 | First sign-in pays ~1–3s |
| Abuse controls available | Identity Toolkit defaults only | Custom: rate limit, allowlist, fraud logs |
| SDK `currentUser` populated | No (we read identity from `AuthSession`) | Yes |
| Implementation cost | ~80 lines client | ~80 lines client + ~30 lines function + deploy/monitor |

The decisive factor is the **Admin SDK private key**. With it, any process holding the key bypasses *all* Security Rules — read every user's data, write any document, mint tokens claiming any UID, delete the project. Avoiding the existence of such a key in our infrastructure is a real, durable security improvement, not just operational simplicity.

In Pattern A, the only secret a client holds is *its own* user's Firebase refresh token in OS keyring. If that's stolen, the attacker impersonates *that user only*. Compartmentalized blast radius.

In Pattern B, you have:
- Same per-user refresh-token risk (unchanged)
- **Plus** a project-wide skeleton key sitting in Cloud Function env vars / runtime
- **Plus** the supply chain of whatever code the Function runs (NPM deps, base image, CI access)

### When Pattern B becomes the right choice

Document for future-us: B's added complexity pays off when any of these become true. None apply today.

1. **Server-side abuse mitigation.** Rate-limiting signups by IP, denying disposable email domains, captcha, fraud scoring.
2. **Lifecycle hooks.** Provisioning external resources on signup (Stripe, Slack), welcome emails, etc.
3. **Existing backend.** If Sketchbook is already running services for other reasons, "add another endpoint" is cheap.
4. **Identity Toolkit response shape changes.** Hasn't happened in years; included for completeness.

### Pattern A's load-bearing assumption

A relies on gitlive's `FirebasePlatform` token hook on JVM gating the **Firestore listener RPC**'s auth header. If the hook is too narrow (only used for non-listener calls, for instance), Firestore would reject listener subscriptions and A fails.

This is the single most important question for the Phase 0 spike. Two outcomes:

- **Hook works** → ship Pattern A. Best of both worlds.
- **Hook doesn't work** → fall back to Pattern B. The `AuthSession` port stays the same; only the implementation behind it changes. Adds a Cloud Function and ~1 day of work, no architectural redo.

### Pattern C (skip SDK entirely) — dismissed

Talking to Firestore via REST + the Listen streaming RPC ourselves would have similar security to A (same token chain), no SDK dependency, but costs ~1000 lines re-implementing listeners, transactions, and query result decoding. Not worth the marginal control for our scale. Revisit only if both A and B fail us in production.

## Security commitments (independent of A vs B)

These ship as part of the migration regardless of which pattern wins. Each closes a real gap or is defense-in-depth.

1. **Verify Google's ID token signature client-side before exchange.** Today's `OAuthClient.parseSubAndEmail` base64-decodes the token body without checking the signature. Add JWKS-cached signature verification (~100 lines) before we send the token to Identity Toolkit. Identity Toolkit verifies on its end too, but client-side verification fails fast and prevents us from ever holding an unverified identity claim.

2. **Keyring entry namespacing.** Use `sketchbook.firebase.refresh_token` (separate from any pre-migration `sketchbook.google.refresh_token`). Ensures a transitional period or rollback doesn't mix token sources. Old key gets cleared on first successful Firebase sign-in.

3. **Call `auth.revokeRefreshTokens(uid)` on sign-out.** Today we revoke the Google OAuth grant. With Firebase we should *also* hit Firebase's [revoke endpoint](https://firebase.google.com/docs/auth/admin/manage-sessions#revoke_refresh_tokens) — guarantees the refresh token is unusable even if a malicious process held a copy. Closes the race window between sign-out and keyring deletion.

4. **Firestore + Storage Rules in CI.** Locked-down test suite using `@firebase/rules-unit-testing`-style harness. Every Rule path has at least one allow-test and one deny-test (different UID, no UID, wrong path). Build fails if a Rule regresses.

5. **No service account keys in the repo, ever.** Goes without saying. Enforce with a pre-commit hook that scans for Google service-account JSON shapes (`"type": "service_account"` is a deterministic marker). Fail the commit.

6. **Bake Firebase Web API key, not service-account creds, into the binary.** The Web API key is a public client identifier (not a secret) — it's fine to ship. The Admin SDK service account key is a project-wide skeleton key and **must never** ship to clients. Pattern A guarantees this; Pattern B requires care that the Function's service account is project-scoped only.

## Proposed architecture

Layered, ports-and-adapters. Domain code (sync pipeline, repositories, UI) depends on **ports**; Firebase is an adapter behind those ports.

```
                                 ┌──────────────────────────────────────────────┐
                                 │                  domain                      │
                                 │  SnapshotPipeline · TreeRegistry ·            │
                                 │  MachineProfileStore · UI ViewModels          │
                                 └──────────────────┬───────────────────────────┘
                                                    │
              ┌─────────────────────────────────────┼─────────────────────────────────────┐
              │                                     │                                     │
       ┌──────▼──────┐                       ┌──────▼──────┐                       ┌──────▼──────┐
       │ AuthSession │                       │MetadataStore│                       │  BlobStore  │
       │   (port)    │                       │   (port)    │                       │   (port)    │
       └──────┬──────┘                       └──────┬──────┘                       └──────┬──────┘
              │                                     │                                     │
       ┌──────▼─────────────┐               ┌───────▼─────────────┐               ┌───────▼──────────────┐
       │ FirebaseAuthSession│               │ FirestoreMetadataStore                │ FirebaseBlobStore     │
       │  (Google OAuth +    │               │  (gitlive SDK +      │               │  (REST against        │
       │   Identity Toolkit  │               │   SQLite mirror)     │               │   Firebase Storage    │
       │   token exchange)   │               │                      │               │   bucket)             │
       └─────────────────────┘               └──────────────────────┘               └───────────────────────┘
              │                                     │                                     │
              └────────────────────┐  shares  ┌─────┴─────┐  Firebase ID token   ┌────────┘
                                   ▼          ▼           ▼                      ▼
                           ┌─────────────────────────────────────────────────────────┐
                           │                  Firebase project                       │
                           │  Firebase Auth · Firestore · Cloud Storage (GCS bucket) │
                           │             Security Rules per service                  │
                           └─────────────────────────────────────────────────────────┘
```

### Ports

#### `AuthSession` (interface — already exists, evolves)

```kotlin
interface AuthSession {
    val state: StateFlow<AuthState>           // SignedIn | SignedOut
    suspend fun signIn(): Result<UserId>       // Google OAuth → token exchange → SignedIn
    suspend fun signOut()                      // revoke + clear keyring
    suspend fun idToken(): String              // Firebase ID token; auto-refresh on expiry
    suspend fun firebaseUid(): String          // request.auth.uid for Rules
}
```

Signature evolves: `accessToken()` becomes `idToken()` (Firebase, not Google), and the cached refresh token is now the Firebase one.

#### `MetadataStore` (interface — new)

```kotlin
interface MetadataStore {
    /** Read a single doc by path. Returns null if missing. */
    suspend fun <T : Any> readDoc(path: DocPath, type: KClass<T>): T?

    /** Write/replace a doc. Pre-condition optional (CAS via Firestore tx). */
    suspend fun <T : Any> writeDoc(path: DocPath, value: T, expected: DocVersion? = null): Result<DocVersion>

    /** Patch specific fields atomically. */
    suspend fun <T : Any> patchDoc(path: DocPath, updates: Map<String, Any?>): Result<Unit>

    /** Delete a doc. */
    suspend fun deleteDoc(path: DocPath): Result<Unit>

    /** Real-time subscription. Emits initial snapshot then deltas. */
    fun <T : Any> observeDoc(path: DocPath, type: KClass<T>): Flow<DocSnapshot<T?>>

    /** Subscribe to a whole collection (all docs under a path). */
    fun <T : Any> observeCollection(path: CollectionPath, type: KClass<T>): Flow<List<DocSnapshot<T>>>

    /** Run a transaction (read-then-write atomically). */
    suspend fun <R> transaction(block: suspend MetadataTransaction.() -> R): Result<R>
}
```

This collapses the current `CloudBackend.readDoc / writeDoc / listDocs` surface plus adds listeners and transactions.

#### `BlobStore` (interface — new, extracted from `CloudBackend`)

```kotlin
interface BlobStore {
    suspend fun headBlob(hash: BlobHash, scope: BlobScope = BlobScope.Shared): Boolean
    suspend fun putBlob(hash: BlobHash, source: RawSource, size: Long, scope: BlobScope = BlobScope.Shared)
    suspend fun getBlob(hash: BlobHash, scope: BlobScope = BlobScope.Shared): RawSource

    // Manifest operations (these are large blob-shaped objects, not Firestore docs)
    suspend fun readManifest(treeId: TrackedTreeId, kind: TrackedTreeKind, rev: Long): ManifestRead
    suspend fun listManifests(treeId: TrackedTreeId, kind: TrackedTreeKind, sinceRev: Long? = null): List<ManifestRef>
    suspend fun appendManifestHead(/* unchanged signature */): Result<Generation>
}
```

The lock methods (`acquireLock`, `refreshLock`, `releaseLock`) **move out** of `BlobStore` into `MetadataStore` — leases become Firestore docs at `/users/{uid}/locks/{tree_id}`, which lets Firestore Security Rules enforce ownership and lets transactions atomically test-and-set a lease.

`CloudBackend` as a unified interface is dissolved. The two sub-interfaces have cleaner contracts.

### Data model

#### Firestore (per-user document tree)

```
/users/{uid}                                           ← user profile root
    fields: email, created_at, settings_version
    subcollections:
      /trees/{treeId}                                   ← one doc per registered tree
          fields:
            kind: "Project" | "UserLibrary" | …
            scope_key: string
            display_name: string
            owner_user_id: string
            collaborators: array<{uid, role}>          ← v1: empty; v1.2: populated
            created_at: timestamp
            created_by_host: string
            head_rev: int                              ← was heads.json[treeId].rev
            head_gen: string                           ← was heads.json[treeId].head_gen
            head_updated_at: timestamp
            head_updated_by_host: string
        subcollections:
          /journal/{eventId}                           ← was tree_journal in catalog only
              fields: kind, host_id, rev, payload, timestamp

      /machines/{hostId}                                ← was machines.json roster
          fields: hostName, os, last_seen_at, binary_version

      /plugins/{hostId}                                 ← was plugin_manifest_<host>.json
          fields: os, computed_at, plugins: array<{name, format, installed}>

      /locks/{treeId}                                   ← was lease/<tree_id> object in GCS
          fields: holder_host, acquired_at, expires_at, lock_id
```

**Why this shape:**

- **One doc per tree, not a single registry doc.** Granular updates: a write to tree X never CAS-conflicts with a write to tree Y. Today's `registry.json` is a single hot doc; this is a shard-per-tree model that scales naturally.
- **HEAD pointer fields *on the tree doc*.** No separate `heads.json`. A push updates `head_rev` + `head_gen` + `head_updated_at` on the same doc as the registry entry, in a single Firestore transaction. Listeners on `/users/{uid}/trees` see the change immediately.
- **Subcollection for journal.** Journal events are write-heavy and append-only; subcollections give us natural pagination + TTL retention policies if we want them.
- **Locks as docs** instead of GCS objects. Atomic test-and-set via transactions; Security Rules can enforce "only the holder can refresh/release"; expiry can be checked server-side via a Rule expression.
- **Per-host plugin slices stay separate** (one doc per `hostId`). Same reason as today: no write conflicts between hosts.

#### Cloud Storage (Firebase-managed bucket)

```
gs://<sketchbook-project>.appspot.com/
    users/{uid}/blobs/{shard}/{hash}                  ← shared blobs (unchanged from today)
    users/{uid}/blobs-private/{uuid}/{shard}/{hash}   ← per-tree private (unchanged)
    users/{uid}/trees/{kind}/{treeId}/manifests/{rev:08d}-{ts}-{host}.json   ← manifest body (unchanged)
```

Layout is identical to today's. Only what changes:
- Bucket is now Firebase-managed, not user-provided. The bucket name comes from the Firebase project config baked into the client, not from `KEY_CLOUD_BUCKET` prefs.
- Auth header carries Firebase ID token, not Google OAuth token.
- The HEAD pointer object (`<tree>/manifests/HEAD`) **goes away** — Firestore replaces it. Append-manifest-head becomes: PUT manifest body to Storage, then in a Firestore tx, update tree doc `head_rev` + `head_gen`.

### Security model

Defense in depth. Three layers; all enforced server-side, none rely on client trust.

#### Layer 1: Firebase Auth identity

- Google OAuth proves the caller controls the Google account (Google's job, not ours).
- Identity Toolkit verifies the Google ID token's signature, audience, expiry — Google's responsibility.
- Firebase mints a Firebase ID token tied to a stable Firebase UID.
- All downstream API calls carry the Firebase ID token. Servers verify the token signature on every request.

#### Layer 2: Firestore Security Rules

```
rules_version = '2';
service cloud.firestore {
  match /databases/{db}/documents {

    // Per-user namespace: caller must own the path.
    match /users/{uid}/{document=**} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }

    // v1.2 collaborator path (additive, not yet active):
    // match /users/{ownerUid}/trees/{treeId} {
    //   allow read: if isOwnerOrCollaborator(ownerUid, treeId);
    //   allow write: if isOwnerOrCollaboratorWithWrite(ownerUid, treeId);
    // }
  }
}
```

This is the Firebase canonical "owner-uid" pattern — [recommended by Firebase docs](https://firebase.google.com/docs/firestore/solutions/role-based-access). UID-from-path matches `request.auth.uid` exactly; queries that don't filter by uid fail; no way to read another user's data even with a leaked token.

#### Layer 3: Firebase Storage Rules

```
rules_version = '2';
service firebase.storage {
  match /b/{bucket}/o {

    match /users/{uid}/{allPaths=**} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
  }
}
```

Same pattern, separate enforcement. Storage Rules and Firestore Rules are independent files; both must be configured. A leaked Firebase ID token (one user's) cannot reach another user's blobs.

#### What this fixes vs today

| Risk in today's design | Status after Firebase migration |
|---|---|
| Bucket name in user prefs (could be set wrong / hijacked) | Removed — bucket comes from baked Firebase config |
| `grant-user.ps1` admin runbook | Removed — Auth signup is self-service |
| Email-based IAM principal vs sub-based path mismatch | Removed — Firebase UID is stable, used both for path and Rules |
| ID token signature not validated client-side | Improved — Firebase verifies Google ID token during exchange; we never trust raw Google ID tokens client-side |
| No way to revoke a single device | Same as today (still revoke at Google level) — out of scope |

#### Threat model: what Firebase doesn't fix

- **Compromised Firebase project credentials (e.g., a leaked Service Account key on the Sketchbook side).** Mitigation: don't ship admin SDK creds to clients; only the Firebase Web API key, which is *not* a secret (it's a public identifier).
- **Stolen Firebase refresh token from OS keyring.** Same threat as today's stolen Google refresh token. Firebase's refresh tokens *can* be revoked server-side (`auth.revokeRefreshTokens(uid)`) which kills all sessions for that UID — a small improvement over today.
- **Malicious client-side modification of auth tokens.** Token signatures are server-verified on every Firebase call; modification fails.
- **Replay attacks within token lifetime.** Firebase ID tokens default to 1h; minimal window. We can shorten via custom token TTL if needed.

## Auth flow (detailed) — Pattern A

The full sign-in sequence under Pattern A. Only the parts marked **NEW** differ from today.

```
   ┌─────────────┐                                                ┌──────────────┐
   │   Desktop   │                                                │   Google     │
   │     app     │                                                │   Identity   │
   └──────┬──────┘                                                └───────┬──────┘
          │                                                                │
          │  1. signIn(): launch system browser → loopback                 │
          │ ──────────────────────────────────────────────────────────────▶│
          │                                                                │
          │  2. Google ID token + access token + refresh token             │
          │ ◀──────────────────────────────────────────────────────────────│
          │                                                                │
          │                                                  ┌─────────────┴─────────────┐
          │  3. NEW: POST identitytoolkit.googleapis.com/v1/  │  Firebase Identity        │
          │       accounts:signInWithIdp                       │   Toolkit                 │
          │       { postBody: "id_token=<G_ID>&providerId=    │                           │
          │           google.com", returnSecureToken: true }   │                           │
          │ ──────────────────────────────────────────────────▶│                           │
          │                                                    │                           │
          │  4. NEW: Firebase ID token + Firebase refresh     │                           │
          │       token + Firebase UID                         │                           │
          │ ◀──────────────────────────────────────────────────│                           │
          │                                                    └───────────────────────────┘
          │
          │  5. Persist Firebase refresh token in OS keyring (replaces Google refresh token)
          │  6. AuthState.SignedIn(uid = firebaseUid, email)
          │  7. UserGraph rebuilds: MetadataStore + BlobStore wired with Firebase ID token
          │
          │  8. Steady state: every cloud call → idToken()
          │     - Cached if not expired
          │     - On expiry: POST securetoken.googleapis.com/v1/token (refresh)
          │     - Returns new ID token; cache + return
```

The two NEW steps add ~50 lines: one `submitForm` call to the Identity Toolkit endpoint, one to refresh.

Net effect: from the user's perspective, sign-in is unchanged (system browser, Google consent, done). Internally, the token type changes and the IdP behind it changes from "raw Google" to "Firebase fronting Google."

## Module / package shape

Refactor pass needed; current shape doesn't quite fit.

```
shared/
  auth/                          ← stays. Updates inside.
    AuthSession (interface)
    FirebaseAuthSession (impl)   ← renamed from GoogleAuthSession
    KeyringTokenStore            ← unchanged
    OAuthClient                  ← still used; google-side leg of token exchange
    FirebaseIdpExchange          ← NEW: token exchange + refresh REST client

  cloud/                         ← renamed conceptually. Splits.
    blob/                        ← extracted
      BlobStore (interface)
      FirebaseBlobStore (impl)   ← renamed/forked from DirectGcsBackend (blob methods only)
    metadata/                    ← NEW
      MetadataStore (interface)
      FirestoreMetadataStore (impl)   ← gitlive SDK-backed
      FirestoreCodecs            ← @Serializable ↔ Firestore Map adapters
    DEPRECATED:
      CloudBackend.kt            ← deleted at end of migration; not used after Phase 4

  repository/                    ← stays. Internals reshape.
    TreeRegistry                 ← interface unchanged externally; impl now MetadataStore-backed
    MachineProfileStore          ← interface unchanged externally; impl now MetadataStore-backed
    SnapshotMetadataStore        ← NEW: extracts the snapshot-row-write-on-pull logic from PullPoller for cleaner testing

  sync/                          ← stays. PullPoller becomes a listener.
    SyncCoordinator              ← NEW: replaces PollScheduler. Subscribes to /users/{uid}/trees and fans out to per-tree pulls.
    SnapshotPipeline             ← unchanged externally; appendManifestHead now writes Firestore field
    PullPoller                   ← retains pollOnce(); polling loop deleted
```

The `CloudBackend` interface gets dissolved by the end. Call sites migrate to `BlobStore` (for blob ops) or `MetadataStore` (for everything else).

## Migration phases

**Four phases**, each shippable green. Sketchbook hasn't shipped to users yet, so there's no production data to preserve and no rollback constituency — we rewire directly and delete the GCS-bucket / OAuth code as we go. No feature flag, no migrator, no parallel legacy path.

### Phase 0: Spike + plan (this doc + ~200-line spike PR)

The spike's job is to **validate Pattern A's load-bearing assumption** before Phase 2 commits code. The pattern is chosen; the spike confirms it works on our exact JVM target. Goals:

1. **`FirebasePlatform` token hook gates Firestore listener RPCs.** This is the make-or-break test. Sign in via Google → REST exchange → inject Firebase ID token via `FirebasePlatform` hook → start a Firestore `onSnapshot` listener → write a doc from a second client → observe the listener fire on the first. If the listener auth fails, Pattern A is dead and we fall back to Pattern B in Phase 2.
2. **Listener offline-reconnect behaviour is acceptable.** Cycle the network on one client; verify the listener resumes cleanly on reconnect.
3. **Firebase Storage REST works with Firebase ID token bearer.** Upload a 10MB blob via the same REST shape `DirectGcsBackend` uses today.
4. **JWKS verification in client OAuth flow.** Wire up the security-commitment item #1 (Google ID token signature verification before exchange) and confirm it works against Google's published JWKS.
5. **Version-compatibility check.** No conflicts with our pinned `kotlinx-coroutines`, `kotlinx-serialization`, `ktor`. Especially watch the `kotlinx-coroutines` version — gitlive has historically pinned exact versions.

**Output:** spike branch + a "Phase 0 results" section appended to this doc:
- Pattern A confirmed (proceed) OR fallback to Pattern B documented (proceed with B; rest of plan unchanged).
- Listener reconnect behaviour characterized.
- Storage REST parity confirmed.
- Library version conflicts identified.

### Phase 1: Firebase project setup + Rules (no app code change)

- Create `sketchbook-dev` and `sketchbook-prod` Firebase projects in the Sketchbook GCP org.
- Configure Google as an Auth provider in each.
- Bake Firebase Web API key + Project ID into a build-time `firebase.config.kt` (committed to the repo — public client identifiers, not secrets).
- Author `firestore.rules` + `storage.rules` per the [Security model](#security-model) section. Deploy via `firebase deploy --only firestore:rules,storage`.
- Author `firestore.indexes.json` for the queries we'll need (collection-group queries on `/users/{uid}/trees`, etc.).
- Set up the rules-unit-testing harness in CI per security-commitment #4. Build fails on rule regression.
- **Output:** runnable dev + prod Firebase projects with empty data, Rules + indexes deployed, CI gating regressions. PR ships only the rules files + `firebase.json`.

### Phase 2: Auth + Storage rewire (rip-and-replace)

This is the biggest single PR. We swap out auth and the blob layer in one go because they share auth tokens, and there's no point shimming the in-between state when there's no user data to migrate.

**Auth side:**
- Add `FirebaseIdpExchange` REST client (~80 lines): trades Google ID token for Firebase ID + refresh token.
- Add JWKS verification of Google ID token before the exchange (security-commitment #1).
- Rename `GoogleAuthSession` → `FirebaseAuthSession`. The class internally still does Google OAuth, then exchanges. Public `AuthSession` interface unchanged.
- Update `KeyringTokenStore` to use `sketchbook.firebase.refresh_token` (security-commitment #2). Pre-Firebase keys: there are none in the wild yet, so just use the new namespace cleanly.
- Implement Firebase refresh-token revoke on sign-out (security-commitment #3).

**Blob side:**
- Rename `DirectGcsBackend` → `FirebaseBlobStore`. The class internally still does GCS REST; only the bucket name comes from `firebase.config.kt` (not prefs) and the bearer token is the Firebase ID token.
- Delete `KEY_CLOUD_BUCKET` and the cloud-bucket pref UI in Settings. The user no longer chooses a bucket.
- Delete `OAuthCloudCredentials`'s GCS-Google-token branch.

**Cleanup that lands in this PR:**
- Delete `tools/grant-user.ps1` and any docs referencing it.
- Delete the manual-IAM section of the OAuth user-scope design (mark superseded).

- Tests: `FirebaseAuthSessionTest`, `FirebaseIdpExchangeTest` (mock Identity Toolkit), `FirebaseBlobStoreTest` against emulator. Plus security-commitment regression tests #1, #2, #3.
- **Output:** sign in via Google → Firebase ID token → rest of the app sees the new bucket. Snapshot pipeline still uses CloudDocs for metadata (next phase).

### Phase 3: MetadataStore + listeners

The CloudDoc layer (`registry.json`, `machines.json`, `plugin_manifest_<host>.json`) gets dissolved into Firestore in one PR. Same logic as Phase 2: rip-and-replace, no parallel paths.

- Add gitlive SDK to `libs.versions.toml`.
- `MetadataStore` port + `FirestoreMetadataStore` adapter (gitlive-backed).
- Rewrite `CloudTreeRegistry` to use `MetadataStore`. Old methods (`fetch / register / lookup / canRead / canWrite`) keep their signatures; implementations become Firestore reads/writes.
- Rewrite `CloudMachineProfileStore` similarly. `machines.json` becomes `/users/{uid}/machines/{hostId}`. `plugin_manifest_<host>.json` becomes `/users/{uid}/plugins/{hostId}`.
- New `SyncCoordinator` subscribes to `/users/{uid}/trees` collection. On each delta where doc's `head_rev > tree_registry_cache.head_rev`, fire `pullPoller.pollOnce(...)`. Replaces the 1000-coroutine polling fan-out from `DesktopAppGraph.startBackgroundPull`.
- `SnapshotPipeline.run` post-CAS hook: Firestore transaction updating tree doc's `head_rev / head_gen / head_updated_at / head_updated_by_host`. Single round-trip.
- `PullPoller`'s `while (true) { listManifests; delay(30s) }` loop deletes. `pollOnce(...)` retained for the per-tree pull path that listeners trigger.
- Lock methods (`acquireLock / refreshLock / releaseLock`) move from `BlobStore` to `MetadataStore` — leases become `/users/{uid}/locks/{treeId}` Firestore docs. Atomic test-and-set via transactions; expiry checked in Rules.
- Delete `CloudBackend.readDoc / writeDoc / listDocs / acquireLock / refreshLock / releaseLock`. Delete `CloudDocKey` / `CloudDocKeys`. Delete `TreeRegistryDoc`, `MachinesDoc` wire types — replaced by Firestore-shaped data classes.
- Tests: `FirestoreTreeRegistryTest`, `FirestoreMetadataStoreTest`, `SyncCoordinatorTest` — all against emulator.
- **Output:** sub-second cross-machine sync. End of CloudDocs.

### Phase 4: Cleanup + final cuts

What's left after Phases 1–3 is small but worth a focused PR:

- Delete `CloudBackend.kt` (interface fully retired). The `BlobStore` and `MetadataStore` ports stand on their own.
- Delete `tools/grant-user.ps1` if not already removed.
- Delete the `2026-05-06-oauth-user-scope-design.md` plan doc references that are now untrue (the bucket / IAM model). Mark superseded by this doc.
- Pre-commit hook blocking service-account JSON shape (security-commitment #5).
- Build-time lint blocking Admin SDK imports on the client (security-commitment #6).
- Document the final cloud architecture in `docs/architecture/cloud.md` so the next reader doesn't have to follow design docs to understand what's live.
- **Output:** clean codebase, no legacy paths, security commitments enforced in CI.

## Risks & open questions

| # | Risk | Mitigation |
|---|---|---|
| R1 | gitlive SDK breaks on minor bump | Pin version; emulator integration tests catch breakage; we control all imports behind `MetadataStore` |
| R2 | Firestore JVM has a subtle bug we don't catch in tests | Phase 0 spike + read GitHub issues on gitlive/firebase-java-sdk before each version pin |
| R2b | `FirebasePlatform` token hook turns out not to gate Firestore listener RPCs (Pattern A fails) | Phase 0 spike validates this BEFORE Phase 2 commits. Fallback is Pattern B (Cloud Function + custom token), well-trodden Firebase pattern. Adds ~1 day of work, no architectural redo. |
| R2c | Future gitlive release adds `signInWithCredential` on JVM | Pure win — our token-injection or custom-token paths keep working; we can optionally migrate to the idiomatic call. Not blocking. |
| R3 | Identity Toolkit `signInWithIdp` rate-limited or flaky | Same fallback pattern as Google OAuth — exponential backoff, surface error to user. Used by countless apps; battle-tested. |
| R4 | Existing users have GCS data we need to migrate | One-shot migrator in Phase 3 + 5. Users with no data (most beta users) skip it cleanly. |
| R5 | Firebase quotas at scale (50K reads/day free tier) | At our scale (1 user, 1000 trees, listeners) free tier is enormous headroom. Numbers in cost section above. |
| R6 | We lock into Firebase | Ports/adapters insulate us. Switching to Supabase / a self-hosted Postgres + WS server later means swapping `FirestoreMetadataStore` and `FirebaseBlobStore` adapters; the rest is unchanged. |
| R7 | Firebase Storage's 5 MB-per-write listener limit (Firestore docs) | Manifests are JSON ~few KB each; tree docs are <1 KB. Plenty of headroom. Blobs go in Storage, not Firestore. |
| R8 | Service-account-style admin operations (e.g., user delete on signout) | Not in this scope. Cloud Functions can do this server-side later. |
| R9 | Onboarding existing beta-user buckets | Users may have to re-sign-in once at the cutover. Acceptable — small N, well-communicated. |

### Open questions

All answered 2026-05-08. See the [Decisions captured](#decisions-captured-2026-05-08) section at the top of this doc for the live record. Question history preserved here for future readers wondering "why was this picked":

1. **One Firebase project or per-environment?** → **dev + prod.** No staging tier; emulator covers it.
2. **Bake the Firebase API key into the binary?** → **yes.** Public client identifier, not a secret.
3. **Mobile / web target on horizon?** → **JVM-only for now, KMP-ready.** Architecture supports future mobile/web; not optimizing for it.
4. **`USE_FIREBASE` feature flag granularity?** → **none.** Sketchbook hasn't shipped, so no parallel-paths overhead. Rip and replace.
5. **Account linking strategy?** → **new Google account = new tenant.** Old data orphaned; ops cleanup later.

## Test strategy

### Unit tests (no Firebase dependency)

- All domain code (sync pipeline, repositories) tests pass against fake `MetadataStore` / `BlobStore` / `AuthSession` impls. No regression from today's coverage.
- Codec tests for Firestore ↔ kotlinx-serialization (round-trip every wire-format type).

### Integration tests against Firebase Emulator Suite

- Launched via `firebase emulators:start` in CI.
- `FirestoreMetadataStoreEmulatorTest`: real listener fires when another client writes; transactions roll back on conflict; security-rules tests via `@firebase/rules-unit-testing`-style harness.
- `FirebaseAuthEmulatorTest`: full sign-in flow with mock Google IdP; refresh path; revoke path.
- `FirebaseBlobStoreEmulatorTest`: upload/download/list/conditional-write parity with `DirectGcsBackendTest`.

### End-to-end test (manual, pre-cutover)

- Real Firebase dev project. Two desktop instances signed into the same Google account on different machines (or VMs). Push from instance A; instance B should see the listener fire and pull within ~2s. This is the smoke test for "sync works" — gates Phase 4 ship.

### Security rules tests

- Codified test suite per [Firebase rules-unit-testing patterns](https://firebase.google.com/docs/rules/unit-tests). Every Rule path has at least one allow-test and one deny-test (different UID, no UID, wrong path).
- Runs in CI on every PR. **Build fails if a Rule regresses** — non-negotiable, per security-commitment #4.
- Test matrix covers both Firestore Rules (`/users/{uid}/...`) and Storage Rules (`gs://.../users/{uid}/...`) — they're separate files with separate test harnesses.

### Security-commitment regression tests

Independent of the A-vs-B pattern, each commitment from the [Security commitments](#security-commitments-independent-of-a-vs-b) section gets a regression test:

| Commitment | Test |
|---|---|
| #1 JWKS signature verification | `OAuthClientTest`: feed a token signed by a wrong key → expect rejection. Feed a token signed by Google's real key (mock JWKS) → accept. |
| #2 Keyring namespacing | `KeyringTokenStoreTest`: write under `sketchbook.firebase.refresh_token`; verify pre-migration `sketchbook.google.refresh_token` is cleared on first Firebase sign-in. |
| #3 Revoke on sign-out | `FirebaseAuthSessionTest` with mock Firebase Admin endpoint: sign-out fires the revoke call; verify the request body contains the right UID. |
| #4 Rules in CI | Covered above. |
| #5 No service-account keys in repo | Pre-commit hook tested in `tools/pre-commit-test.sh` — synthesize a fake service-account JSON, attempt commit, expect failure. |
| #6 No Admin SDK on client | Linter check: any import of `com.google.firebase.auth.FirebaseAuth.UpdateRequest`-shaped Admin classes fails the build. (Trivial because we never add the dep, but the lint guarantees it stays that way.) |

## Cost projection

Estimates for a single user with 3 machines and a typical library (~50 MB/project × 1000 = ~50 GB raw, ~30 GB unique after dedup):

| Line item | Free tier | Our usage | Cost/mo |
|---|---|---|---|
| Firebase Auth | 50K MAU | 1 user | $0 |
| Firestore reads | 50K/day | ~3K/day (3 listeners × 1000 docs initial + steady delta) | $0 |
| Firestore writes | 20K/day | ~10/day | $0 |
| Firestore storage | 1 GB | ~5 MB (1000 small tree docs + journal) | $0 |
| Cloud Storage | 5 GB | 30 GB (25 GB billable @ $0.026/GB) | $0.65 |
| Storage egress | 1 GB/day | <300 MB/day steady state | $0 |

**Total: ~$0.65/month per typical user.**

For 5 users at the same shape: ~$3–10/month. Well covered by GCP credits, and at production scale (100 users, $30–80/mo) it's a rounding error vs. salary or any other line item.

## Decision gates

Before code starts on Phase 1:
- ☑ This doc is approved.
- ☑ Decisions captured at the top of this doc are confirmed.
- ☐ Phase 0 spike runs cleanly. Output appended to this doc.
- ☐ **Phase 0 confirms Pattern A (or explicitly switches to B) — documented in spike output.**

Before Phase 2 (auth + storage rewire) ships:
- ☐ JWKS verification of Google ID token works (security-commitment #1).
- ☐ Keyring namespacing established under `sketchbook.firebase.refresh_token` (security-commitment #2).
- ☐ Sign-out revokes Firebase refresh token (security-commitment #3).
- ☐ Storage Rules deny-tests pass for cross-user paths.

Before Phase 3 (MetadataStore + listeners) ships:
- ☐ End-to-end test passes between two real machines: push from A, listener fires on B within 2s.
- ☐ Firestore Rules pass full test matrix in CI (security-commitment #4).
- ☐ Cost dashboard exists; alerting at $50/mo on the GCP billing project.

Before Phase 4 (cleanup) closes:
- ☐ Service-account-key pre-commit hook in place (security-commitment #5).
- ☐ Admin-SDK-import build lint in place (security-commitment #6).
- ☐ `docs/architecture/cloud.md` written.
- ☐ All `CloudBackend` / `CloudDoc*` / `DirectGcsBackend` symbols removed from the codebase.

## Out of scope (deliberate non-goals — do not expand this PR series)

- **Cloud Functions for server-side validation.** May want eventually for things like "validate manifest before write" or "delete user data on account closure," but adds operational complexity. Defer.
- **Firebase App Check.** Anti-abuse measure that proves the request comes from a legit app instance. Useful at scale, premature now.
- **Firebase Performance / Crashlytics.** Already collecting our own logs. Add later if useful.
- **Mobile / web client.** Architecture supports it but UI work is its own scope.
- **B2B multi-tenancy** (orgs/teams).** Rules schema as-designed scales to single-user; teams need a different layout. Re-design when there's demand.
