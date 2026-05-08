# Phase 0 spike: Firebase migration

Throwaway module that validates the assumptions of [`docs/plans/2026-05-08-firebase-migration-design.md`](../../docs/plans/2026-05-08-firebase-migration-design.md). The whole `spikes/firebase-poc/` directory is deletable once findings are appended to the design doc and Phase 1 starts.

## What's in here

| File | Purpose |
|---|---|
| `IdentityToolkitClient.kt` | REST exchange Google ID token → Firebase ID token (the canonical desktop-Firebase auth pattern). Phase 2 production code lifts this directly. |
| `GoogleIdTokenVerifier.kt` | Client-side JWKS verification of Google ID tokens (security-commitment #1). Lift directly. |
| `JvmFirebasePlatform.kt` | The JVM implementation of `com.google.firebase.FirebasePlatform` (storage / log hook). Spike: in-memory. Phase 2: file-backed + keyring. |
| `Probes.kt` | Three probes the spike runs interactively. See "Running probes" below. |
| `FirebaseConfig.kt` | Public values from the dev Firebase project (`sketchbook-jtf-2026`). All committed. |
| `secrets.local.kt.template` | Template for the gitignored `secrets.local.kt` (OAuth client ID + secret). |
| `Main.kt` | CLI entry point. `./gradlew :spikes:firebase-poc:run --args="<probe> [args...]"` |

Tests are minimal by design: `IdentityToolkitClient` is wire-format mock-tested. `GoogleIdTokenVerifier` is verified by running probe 2 against real tokens (full JWKS test setup is heavy for a spike).

## One-time setup

1. Copy `secrets.local.kt.template` → `secrets.local.kt`, fill in OAuth Client ID + Secret from the dev Firebase project's GCP console.
2. In the Firebase Console, **temporarily** enable Email/Password auth and create a test user (any email + password). Used for Probe 1 only. Disable when done.

## Running probes

### Probe 1: Firestore listener sanity (the load-bearing one)

```bash
./gradlew :spikes:firebase-poc:run --args="listener-sanity test@example.com hunter2"
```

Validates Firestore + listeners work on JVM via gitlive at all. Initializes Firebase, signs in via Email/Password, writes a doc, listens for it on a second flow, expects the listener to fire. Output ends with:

- `SUCCESS: listener fired` → Firestore-on-JVM works. Pattern A1/A2 token injection becomes engineering, not viability.
- `FAILED: listener did not receive a snapshot within 15s` → load-bearing failure; reroute to Pattern C (Firestore REST + manual Listen RPC) or Pattern B (Cloud Function + custom token).

### Probe 2: Identity Toolkit token exchange

```bash
./gradlew :spikes:firebase-poc:run --args="exchange-google-token <GOOGLE_ID_TOKEN>"
```

Get a Google ID token from [Google's OAuth Playground](https://developers.google.com/oauthplayground/):
1. ⚙️ icon → "Use your own OAuth credentials" → paste the Desktop OAuth Client ID + Secret
2. Step 1: select scope `https://www.googleapis.com/auth/userinfo.email` and `openid`
3. Step 2: exchange auth code for tokens
4. Copy the `id_token` value (the long JWT)

The probe verifies the token's signature against Google's JWKS, then trades it via Identity Toolkit for a Firebase ID + refresh token.

### Probe 3: Firebase Storage REST upload

```bash
./gradlew :spikes:firebase-poc:run --args="storage-rest <FIREBASE_ID_TOKEN>"
```

Pipe a Firebase ID token from probe 2. PUTs a 1 MB blob to the spike's bucket. Validates that the same REST shape `DirectGcsBackend` uses today works against the Firebase-managed bucket with a Firebase ID token bearer.

## Running tests

```bash
./gradlew :spikes:firebase-poc:test
```
