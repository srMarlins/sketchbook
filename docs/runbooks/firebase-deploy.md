# Firebase deploy runbook

How to deploy Firestore + Storage Security Rules (and, eventually, indexes) to the Sketchbook Firebase projects.

Background: [`docs/plans/2026-05-08-firebase-migration-design.md`](../plans/2026-05-08-firebase-migration-design.md).

## One-time setup

### Install `firebase-tools`

```bash
# macOS / Linux (recommended — npm-free, won't drift with node updates)
curl -sL https://firebase.tools | bash

# or via npm
npm install -g firebase-tools
```

Verify: `firebase --version` should print something like `13.x.x`.

### Sign in

```bash
firebase login
```

Browser flow. Sign in with the Google account that owns the Firebase projects.

### Confirm the project aliases

The repo ships with `.firebaserc` mapping two aliases:

```bash
firebase use dev      # → sketchbook-jtf-2026 (current)
firebase use prod     # → not yet provisioned
```

Running `firebase use` with no args prints the currently-active project.

## Day-to-day: deploy rules

After editing `firestore.rules` or `storage.rules`:

```bash
# Dry run — validates syntax + previews the diff, doesn't deploy
firebase deploy --only firestore:rules,storage --dry-run

# Real deploy to dev
firebase use dev
firebase deploy --only firestore:rules,storage

# Real deploy to prod (once it exists)
firebase use prod
firebase deploy --only firestore:rules,storage
```

Rules take ~30–60s to propagate after deploy. Don't run rule-dependent tests immediately after; wait or you'll get spurious failures.

## Deploying indexes

Compound queries (e.g., `where(...).orderBy(...)`) need a Firestore index. When the SDK throws `FAILED_PRECONDITION: The query requires an index`, Firestore's error message includes a clickable URL that auto-creates the index in the Console.

To capture that index in `firestore.indexes.json` so it ships with the repo (not as one-off Console clicks):

```bash
# After accepting the auto-created index in the Console:
firebase firestore:indexes > firestore.indexes.json
git add firestore.indexes.json
git commit -m "feat(firebase): index for <query>"
```

For Phase 2/3 specifically, indexes get added as queries land. The file currently ships empty (`{"indexes": [], "fieldOverrides": []}`) since no compound queries exist yet.

## Setting up `sketchbook-prod`

Required before Phase 1 closes. Mirror of the dev project:

1. **Firebase Console → Add project** → name `sketchbook-prod` (or whatever convention the dev project ends up using long-term)
2. **Authentication → Sign-in method → Google** — enable, set support email
3. **Authentication → Sign-in method → Email/Password** — leave disabled (production doesn't need it; was a spike-only enablement)
4. **Build → Firestore Database → Create database**:
   - Location: `us-central1` (same as dev for billing simplicity)
   - Production mode (rules get overwritten by our deploy)
5. **Build → Storage → Get started** — same `us-central1`, production mode
6. **Project settings → General → Add app → Web** — register a placeholder app called `sketchbook-jvm-prod` to obtain a Web API key
7. **Cloud Console → APIs & Services → Credentials**:
   - Create a Desktop OAuth client named `sketchbook-prod-desktop`
   - Configure the OAuth consent screen if prompted (External; Testing → Production-ready before public launch)

Once provisioned, update `.firebaserc`:
```json
"prod": "sketchbook-prod-xxxxx"   // replace with the real project ID
```
…and `FirebaseConfig.kt` (or wherever Phase 2's production-bound config lives) with the prod project's Web API key + bucket name.

## Deploying Cloud Functions

Sketchbook ships one HTTPS-callable Function in Phase 3: `revokeMySession`. Source lives in `functions/`.

```bash
firebase deploy --only functions
```

After deploy, the function is reachable at `https://us-central1-<projectId>.cloudfunctions.net/revokeMySession`.

**What `revokeMySession` invalidates.** Calling it server-side runs
`admin.auth().revokeRefreshTokens(uid)`, which marks every refresh token issued to that UID as
revoked. **It does NOT invalidate the caller's currently-cached Firebase ID token** — those
remain valid for up to their remaining TTL (default 1h). Sign-out clears the local cached ID
token immediately, so this only matters for cross-device propagation: another device holding a
not-yet-expired ID token will continue functioning until its next refresh attempt, which then
fails with `INVALID_REFRESH_TOKEN` and forces a re-sign-in.

**URL note.** The current client targets the 1st-gen `cloudfunctions.net` alias. 2nd-gen
functions are served from `*.run.app`; if a future deploy migrates to 2nd-gen, update
`CloudFunctionsClient.revokeMySession`'s `url =` construction.

## When things go wrong

| Symptom | Likely cause | Fix |
|---|---|---|
| `Error: HTTP Error: 403, The caller does not have permission` on deploy | You're signed into a Google account that doesn't have Editor on the Firebase project | `firebase logout && firebase login` with the right account |
| Rule deploys succeed but app still gets `PERMISSION_DENIED` | Rules taking time to propagate | Wait 60s. If still failing, check the Console's Rules tab matches what you deployed |
| `firebase deploy --only storage` fails with "no bucket configured" | Storage isn't initialized in the Firebase Console | Console → Build → Storage → Get started |
| `firebase use prod` errors with "Invalid project id" | `.firebaserc`'s `prod` alias still points at the placeholder | Update with the real prod project ID |
| Sign-out succeeds locally but other devices stay signed in | Their cached Firebase ID token hasn't expired (≤1h TTL) | Expected — `revokeMySession` invalidates refresh tokens; the cached ID token expires on its own clock |

## CI integration (Phase 3+ follow-up)

Per security-commitment #4 in the migration design, rules-unit-testing in CI is required as a
defense against accidental rule regressions. The harness will live in `tests/firestore-rules/`
(empty today — directory is referenced from `firestore.rules`'s top comment but the test files
themselves are tracked as a separate follow-up). Phase 3 ships rules + lock-doc shape changes
without the CI gate; the audit trail is `git blame` on `firestore.rules` + manual reasoning.
The aspirational gitlive-2.4.0-emulator-drift note in `firestore.indexes.json` is also a
follow-up — no CI check enforces it today.

## Cost note

Rules deploys, project provisioning, and Console-tier operations are free. Firestore + Storage usage starts billing on the Blaze plan; Spark (free) plan ceiling: 1 GB Firestore storage, 5 GB Storage. Phase 1 (just deploying rules to empty projects) stays at $0.
