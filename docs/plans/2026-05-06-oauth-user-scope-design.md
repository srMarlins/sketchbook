# OAuth + User/Cloud DI Scope — Design

**Status:** Drafted 2026-05-06.
**Owner:** srMarlins.
**Replaces:** the "paste service-account JSON" path in Settings.

## Goal

Add real user authentication so multiple people can use the app against the
same shared GCS bucket without sharing credentials. Each user signs in with
their Google account; their data lives under a per-user prefix in the bucket.
Storage layout already anticipates this (`DirectGcsBackend` prefixes every key
with `userId/...`); this design wires the rest.

## Non-goals

- Token-vending Cloud Function. Manual `gcloud` IAM grants per signup are fine
  while the user count is small. The interfaces here leave room for a token
  vendor later — only `CloudCredentials` changes.
- Multi-account / account-switcher UI. One signed-in account at a time.
- Cross-user shared blob pool. `BlobScope.Shared` stays a *within-tenant*
  optimization.
- Quota enforcement.

## 1. DI shape

`AppScope` keeps everything that works offline:
- `SettingsRepository`
- `LibraryScanCoordinator`
- Local `CatalogDb`, `ProjectRepository`, FTS
- `AuthSession` (new)
- `UserGraphHolder` (new)

New `UserScope` as a `@GraphExtension(UserScope::class)` whose parent is
`AppScope::class`. Holds everything that requires an authenticated user:
- `UserId`
- `CloudBackend` + `CloudCredentials`
- `GcsSyncQueue`, `SyncStateStore`, sync coordinators

`AuthSession` exposes the state machine:

```kotlin
interface AuthSession {
    val state: StateFlow<AuthState>
    suspend fun signIn(): Result<UserId>
    suspend fun signOut()
}

sealed interface AuthState {
    data object SignedOut : AuthState
    data class SignedIn(val userId: UserId, val email: String) : AuthState
}
```

`UserGraphHolder` observes `AuthSession.state` and builds/tears down the
`UserGraph` on transitions. Exposes `StateFlow<UserGraph?>`.

Compose: `RootContent` provides `LocalUserGraph` from that flow. Sync UI
either reads it or shows a "Sign in to enable sync" affordance when null.

## 2. Auth flow

New `shared/auth` module — commonMain interface + jvmMain impl.

`OAuthClient` (jvmMain) implements desktop OAuth via loopback PKCE:
1. Generate `code_verifier` + `code_challenge` (S256).
2. Bind ephemeral HTTP server on `127.0.0.1:<random>/callback`.
3. Open system browser to Google's auth URL. Scopes:
   `openid email https://www.googleapis.com/auth/devstorage.read_write`.
4. Capture `?code=...`, exchange for `access_token` + `refresh_token` +
   `id_token`.
5. Parse `sub` from ID token → `UserId`. Parse `email` for display.

`TokenStore` (commonMain interface, jvmMain impl using **javakeyring** —
macOS Keychain / Windows Credential Manager / Linux libsecret). Stores the
refresh token only. Access tokens stay in memory.

On app start, `AuthSession.init()` reads the refresh token from the keychain;
if present, mints an access token without opening a browser → emits
`SignedIn` immediately.

`OAuthGcsCredentials` replaces `GcsAuth`. Same `suspend fun token(): String`
shape, but uses `AuthSession` + the refresh-token flow instead of JWT
signing. `DirectGcsBackend`'s constructor parameter becomes a
`CloudCredentials` interface so JWT-vs-OAuth is the only place that changes.

## 3. Bucket & IAM

Single bucket (e.g. `sketchbook-prod`). All objects keyed `users/<sub>/...` —
the layout `DirectGcsBackend` already uses, with `<sub>` now a real Google
subject identifier instead of `UserId.DEFAULT`.

Per-user IAM grant, run once per signup:

```
gcloud storage buckets add-iam-policy-binding gs://sketchbook-prod \
  --member="user:<email>" \
  --role="roles/storage.objectAdmin" \
  --condition='expression=resource.name.startsWith(
      "projects/_/buckets/sketchbook-prod/objects/users/<sub>/"),
    title=tenant_<sub>'
```

`tools/grant-user.ps1 <email> <sub>` wraps that. Run after each new signup.

`BlobScope.Shared` semantics unchanged — within-tenant shared pool only.

## 4. UI

`SettingsScreen` gets a "Cloud" section above the existing sync controls:
- Signed out: "Sign in with Google" button.
- Signed in: "Signed in as alice@gmail.com" + "Sign out" button.

Existing sync controls stay where they are but disable with an inline
"Sign in to enable sync" hint when `UserScope` is null.

No first-launch wall, no banner elsewhere. Settings is the one place to
manage cloud auth.

The "paste service-account JSON" UI + `setCloudCredential` /
`cloudCredentialJson` come out of `SettingsRepository` and `Settings` data
class. Migration on first launch with the new build: any existing JSON in
prefs is dropped silently — user re-signs-in via OAuth.

## 5. Error handling

| Failure | Behavior |
| --- | --- |
| OAuth callback never arrives within 5 min | `signIn()` → `Result.failure(OAuthTimeout)`. Inline error in Settings. |
| User denies consent (`error=access_denied`) | `Result.failure(OAuthCancelled)`. No error UI — treated as cancel. |
| Refresh fails with `invalid_grant` | `state` flips to `SignedOut`, snackbar "Session expired — sign in again". `UserScope` torn down cleanly. |
| 403 on a GCS write | "Your account isn't authorized for this bucket — contact your admin." in sync error UI. (Means `grant-user.ps1` hasn't been run for them.) |
| Keychain unavailable / read fails | Treat as `SignedOut`. Log once. |

## 6. Testing

- `OAuthClient` test with fake auth + token endpoints — exercises the
  loopback server end-to-end on a random port.
- `AuthSession` state-machine test: signIn → SignedIn → signOut → SignedOut,
  plus refresh-failure → SignedOut.
- `UserGraphHolder` lifecycle test: graph built on SignedIn, scope cancelled
  on SignedOut, fresh graph on next SignedIn.
- `OAuthGcsCredentials` test: caching + refresh, refresh-token failure
  surfaces correctly via the credentials API.
- One end-to-end manual test: real Google sign-in, real bucket, push one
  project end-to-end.

## 7. Dependencies

- `com.github.javakeyring:java-keyring` for OS-native refresh-token storage.
- No backend service. No new module-level deps beyond keyring.

## 8. Migration / rollout

- One PR. Ripping the JSON path and adding OAuth happens together; there is
  no transitional state where both work.
- First launch on the new build: existing `cloudCredentialJson` pref is
  ignored and removed. User clicks "Sign in with Google" once; subsequent
  launches are silent (refresh token in keychain).
- IAM grant for srMarlins runs as the first invocation of
  `tools/grant-user.ps1` after sign-in.

## 9. Future work (explicitly deferred)

- Token-vending Cloud Function — automatic onboarding, no manual `gcloud`
  step. Trigger to build it: user count past ~10 or signup friction is
  felt.
- Account-switcher UI — only if a real use case shows up.
- Quota / cost-cap enforcement at the bucket or per-user level.
