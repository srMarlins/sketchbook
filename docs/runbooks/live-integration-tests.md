# Live integration tests

End-to-end Ableton project sync tests that hit the real dev Firebase project
(`sketchbook-jtf-2026`). These cover the cross-OS round-trip — pushing a real Live project
from one machine and pulling it on another — which is the assertion no unit test or
emulator can make.

These are **local only**. They do not run in CI: the dev project credentials are not
intended for shared runners, and they write real Firestore docs + Storage blobs under your
account.

## What it tests

- A real Ableton project folder on machine A snapshots through the production
  `SnapshotPipeline` against real Firestore + Firebase Storage.
- Machine B pulls the manifest and materializes every file. The assertions check:
  1. **Bytes round-trip** — every file's SHA-256 matches the manifest entry.
  2. **`.als` reparses** — every `.als` survives gzip + XML decode via the production
     `AlsParser`.
  3. **Sample refs resolve** — every intra-project `<SampleRef>` inside each `.als`
     resolves to a real file under the materialized project root. This is the cross-OS
     guard: backslashes-vs-forward-slashes, NFC/NFD filename divergence, lost separators
     all surface here.
- A human-eye check follows: open the pulled project in Live on the receiving machine.

## One-time setup, per machine

You need a Firebase test account (the same Google account works on both your Mac and your
Windows box). Sign in once on each machine; the refresh token is cached locally and reused
by subsequent push/pull/sweep tasks.

```bash
./gradlew :tests:live-integration:liveTestLogin \
    -Dsketchbook.oauth.client_id=<your-desktop-oauth-client-id>.apps.googleusercontent.com
```

The OAuth client ID is the same one configured for the desktop app in the Firebase Console
under Authentication → Sign-in method → Google. If you don't have one, see
`docs/runbooks/firebase-deploy.md`.

Token cache lives at `~/.sketchbook-test/auth.json` (mode 0600 on POSIX). Nuke it any time
to force a re-sign-in:

```bash
rm ~/.sketchbook-test/auth.json
```

Re-run `liveTestLogin` after that.

## Scenario A — Mac → Windows

On your Mac:

```bash
./gradlew :tests:live-integration:liveTestPush \
    -PprojectDir="/Users/jaredfowler/Downloads/2026/maria Project"
```

Last two lines of stdout are machine-parseable:

```
UUID=liveit-1736534400-a1b2-maria_Project
HEAD_REV=1
```

Copy the UUID. On your Windows box:

```powershell
.\gradlew :tests:live-integration:liveTestPull `
    -Puuid=liveit-1736534400-a1b2-maria_Project `
    -PdestDir="C:\sketchbook-pulled\maria"
```

The pull task prints a per-stage summary. Exit code is `0` only if all three assertions
pass:

```
=== Live integration assertions ===
[manifest] verified=47 missing=0 mismatched=0
[als]      parsed=3 failures=0
[samples]  intraProject hit=14 miss=0  library=2  absolute=0

RESULT: PASS — open the project in Live to confirm.
```

Open `C:\sketchbook-pulled\maria\maria.als` in Ableton Live on Windows. The project should
load with all samples found and no "missing media" warnings (other than library samples
that machine doesn't have installed locally — those are reported as `library` refs and
explicitly excluded from the failure criterion, since they're machine-dependent by design).

## Scenario B — Windows → Mac

Mirror image of A. On Windows:

```powershell
.\gradlew :tests:live-integration:liveTestPush `
    -PprojectDir="C:\Users\jared\Music\Projects\my-project"
```

On Mac:

```bash
./gradlew :tests:live-integration:liveTestPull \
    -Puuid=liveit-... \
    -PdestDir="/Users/jaredfowler/sketchbook-pulled/my-project"
```

## Scenario C — Two simulated clients on one machine

Single Gradle command exercises sync coordination + locking against the real dev Firebase
project, without needing a second physical machine. One JVM spawns two `ClientHandle`s
with different `hostId`s (the dimension that makes them "different machines" in the data
model); they share the same Firebase user and graph.

```bash
./gradlew :tests:live-integration:liveTestTwoClient \
    -PtemplateDir="/Users/jaredfowler/Downloads/2026/super_minimal Project"
```

Pick a **small** project for the template — every scenario re-copies it and re-uploads it.
`super_minimal Project` or similar runs in well under a minute total.

Run a single scenario instead of all five:

```bash
./gradlew :tests:live-integration:liveTestTwoClient \
    -PtemplateDir="…" \
    -Pscenario=lockContention
```

### Scenarios

| Name | What it tests |
|---|---|
| `linearSync` | A pushes → B's Firestore listener catches the head_rev advance → B materializes → assert byte equality. Records the wall-clock listener latency (the "sub-second" Phase 3 promise). |
| `editAndResync` | After linearSync, A drops a deterministic edit file and pushes v2 → B's already-running listener catches v2 → B materializes the delta → assert edit file is present. |
| `lockContention` | A and B simultaneously call `SnapshotPipeline.run` (gated on a `CompletableDeferred` barrier so they race at the `acquireLock` call). Asserts exactly one `Saved` + one `Failed` with `lock held` in the reason. |
| `bidirectional` | A→cloud rev=1, B materializes + edits + pushes rev=2, A materializes back. Asserts both ends converge on the same byte set. |
| `lockExpiry` | A acquires the lock directly via `MetadataStore.acquireLock` with TTL=2s, waits past expiry, B then runs a normal push that succeeds (lease was expired so `acquireLock` returned `Acquired`). |

Scratch dirs land under `~/.sketchbook-test/two-client-runs/<epoch>/<scenario>/{a,b}/` —
left in place after the run for inspection. Wipe with `rm -rf ~/.sketchbook-test/two-client-runs`
when you're done.

Each scenario uses a fresh `liveit-tc-<scenario>-<epoch>-<rand>` UUID and best-effort
deletes its own tree + lock doc at the end. The standard `liveTestSweep` picks up any
strays.

## Cleanup

Test runs leave one tree doc + one lock doc + a chain of manifests + blob bytes per
scenario. The sweep task removes the Firestore docs:

```bash
./gradlew :tests:live-integration:liveTestSweep                  # dry-run
./gradlew :tests:live-integration:liveTestSweep -Papply=true     # actually delete
```

Optional safety: skip recent trees so an in-flight test on another machine isn't
disturbed.

```bash
./gradlew :tests:live-integration:liveTestSweep -Papply=true -PolderThanHours=6
```

**Storage blobs aren't deleted by the sweep** — `CloudBackend` doesn't expose a delete
method today. The blobs are content-addressed and tenant-scoped (under your user's prefix)
so they don't leak across users; they just sit unreferenced under your quota. If they pile
up, clear via:

```bash
gcloud storage rm -r gs://sketchbook-jtf-2026.firebasestorage.app/users/<your-uid>/manifests/liveit-*
gcloud storage rm -r gs://sketchbook-jtf-2026.firebasestorage.app/users/<your-uid>/blobs-private/liveit-*
```

(Shared-scope blobs may also exist if the project wasn't marked `selfContained`. Those
dedup with non-test data, so leave them alone.)

## Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| `Missing sketchbook.oauth.client_id` | Login task invoked without `-Dsketchbook.oauth.client_id=...` | Pass the flag (see "One-time setup") |
| `No cached refresh token at …` on push/pull | Either you skipped `liveTestLogin`, or the cached token expired | Re-run `liveTestLogin` |
| `Live-integration tests refuse to run against env=prod` | You set `-Dsketchbook.env=prod` | Don't. Use the dev project; prod is for real users |
| `destDir already contains files` on pull | Your pull destination isn't empty | Choose an empty/non-existent path. The assertion only makes sense against a freshly-materialized tree |
| `projectDir contains no .als files` | You pointed at the parent of an Ableton project, not the project folder itself | Point at the folder that directly contains `<projectname>.als` |
| Pull passes but Live shows "missing media" | A sample reference Live couldn't resolve | Inspect the assertion output. `library` and `absolute` refs are expected to be machine-dependent. `intraProject miss` refs are the actual sync bug |
| Live opens but the project sounds different on the other OS | Plugin chain mismatch (not a sync bug — Sketchbook only syncs the project folder, not the plugins) | Verify the same plugins (and versions) are installed on both machines |

## Where the code lives

```
tests/live-integration/
  src/jvmMain/kotlin/com/sketchbook/liveit/
    LiveTestEnv.kt              # config (env, paths, prefix)
    FileTokenStore.kt           # ~/.sketchbook-test/auth.json
    LiveTestBootstrap.kt        # real Firebase graph from cached tokens
    LiveTestLogin.kt            # main(): interactive OAuth → cache refresh token
    LiveTestPush.kt             # main(): snapshot project → live cloud
    LiveTestPull.kt             # main(): pull → materialize → assert
    LiveCloudIo.kt              # read tree doc / read head manifest / materialize blobs
    LiveProjectAssertions.kt    # manifest / parse / sample-ref checks
    LiveTestSweep.kt            # main(): delete liveit-* tree + lock docs
    LiveTestTwoClient.kt        # main(): runs the two-client scenario battery
    TwoClientHarness.kt         # two ClientHandles + seed/edit/materialize helpers
    TwoClientScenarios.kt       # linearSync, editAndResync, lockContention, …
```

The `liveit-` UUID prefix is load-bearing: the sweep task scopes deletes to it, so
hand-crafted production trees in your account are never touched.
