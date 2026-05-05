# Sketchbook releases — runbook

Sketchbook ships as Mac + Windows installers built by **Conveyor** and
distributed via the public GCS bucket `gs://sketchbook-releases`. Installed
clients auto-update on launch by polling Conveyor's update metadata at
`https://storage.googleapis.com/sketchbook-releases/`.

Source code stays in the private `srMarlins/sketchbook` repo. GitHub
Releases hold human-readable changelog notes (with links to the bucket
URLs); binaries do NOT live on GitHub Releases (private repo → no
anonymous access).

## One-time bootstrap

Done exactly once per fresh setup of this distribution pipeline.

### 1. Create the public releases bucket + uploader SA + Workload Identity Federation

```powershell
pwsh -File tools/setup-releases-bucket.ps1
```

This single script does everything:

- Creates `gs://sketchbook-releases` with public-read access (separate bucket, separate IAM from the data bucket — different blast radius).
- Creates the `sketchbook-release-uploader` service account, scoped to that bucket only.
- Creates a Workload Identity Pool (`github`) + OIDC provider (`github-provider`) restricted to repos owned by `srMarlins`.
- Binds the WIF principal for `srMarlins/sketchbook` to impersonate the SA.

**No long-lived JSON key is created** — the org policy `iam.disableServiceAccountKeyCreation` forbids it, and WIF is the better practice anyway. GitHub Actions mints a short-lived OIDC token, GCP exchanges it for a 1-hour access token bound to the SA. Nothing to leak; nothing to rotate.

The provider resource path and SA email are baked into `.github/workflows/release.yml`; no GitHub Secret is needed for GCS auth.

### 2. Install Conveyor locally

For the first signing-key generation and any local-only releases:

| Platform | Command |
|---|---|
| macOS | `brew install --cask hydraulic/tap/conveyor` |
| Windows / Linux | Download zip/tarball from https://downloads.hydraulic.dev/conveyor/download.html and extract to a directory on `PATH`. |

Windows one-liner (PowerShell, not admin):

```powershell
$dest = "$env:LOCALAPPDATA\Conveyor"
New-Item -ItemType Directory -Force -Path $dest | Out-Null
Invoke-WebRequest -Uri "https://downloads.hydraulic.dev/conveyor/conveyor-22.0-windows-amd64.zip" -OutFile "$env:TEMP\conveyor.zip"
Expand-Archive -Force "$env:TEMP\conveyor.zip" -DestinationPath $dest
$bin = (Get-ChildItem $dest -Directory | Where-Object { $_.Name -like "conveyor-*" } | Select-Object -First 1).FullName + "\bin"
[Environment]::SetEnvironmentVariable("PATH", "$env:PATH;$bin", "User")
$env:PATH = "$env:PATH;$bin"
```

(Bump the `22.0` pin in lockstep with the version pinned in `.github/workflows/release.yml` so local + CI use the same Conveyor.)

Verify: `conveyor --version` prints something. (No Hydraulic Scoop bucket exists; do not search for one.)

### 3. Generate the Conveyor signing keypair

```bash
conveyor keys generate
```

This produces a `signing.json` file (default path
`~/.conveyor/signing.json`). **This is the cryptographic root of trust for
all updates ever shipped from this codebase.** Treat it like a master
password:

- Back it up to at least one offline location (encrypted USB, password
  manager attachment, etc.).
- Push its content into GitHub Secrets so CI can sign builds:

```bash
gh secret set CONVEYOR_SIGNING_KEY --body "$(cat ~/.conveyor/signing.json)"
```

If you ever lose this key, every existing installed Sketchbook client must
manually reinstall with a new signing key embedded — there is no recovery.

### 4. Smoke test

Cut a `v0.0.1` tag at the current commit:

```bash
git tag v0.0.1
git push origin v0.0.1
```

The `release` workflow runs. After ~5–10 min:
- `gs://sketchbook-releases/` should contain `Sketchbook-0.0.1-*.dmg`,
  `*.msi`, and Conveyor metadata (`metadata.json`, `download.html`,
  `index.html`).
- A GitHub Release `v0.0.1` should exist with auto-generated notes and
  download links.

## Routine release

```bash
# 1. Make sure main is what you want to release
git checkout main && git pull --ff-only

# 2. Tag it
git tag v1.0.0   # or whatever version
git push origin v1.0.0
```

That's it. The workflow:
- builds via `./gradlew :app-desktop:build`,
- runs `conveyor make site` (cross-platform),
- uploads to `gs://sketchbook-releases/`,
- creates a GitHub Release with changelog and download URLs.

Installed Sketchbook clients pick up the new version on their next
application launch.

## Local-only release (when you can't use CI)

```powershell
pwsh -File tools/release.ps1 -Tag v1.0.0
```

(Same end result as the workflow; built locally instead. See script for
prerequisites.)

## Versioning

Use [Semantic Versioning](https://semver.org/):

- `v1.0.0` — first stable.
- `v1.0.1` — bug fixes, no new behavior.
- `v1.1.0` — new features, backward-compatible.
- `v2.0.0` — breaking changes (e.g., manifest schema bump that requires
  re-scanning).

Pre-release suffixes (`v1.0.0-rc1`, `v1.0.0-beta1`) cause the workflow to
mark the GitHub Release as a prerelease.

## Recovering from a bad release

You shipped `v1.0.5` but it has a critical bug.

1. **Don't delete the bad release.** Auto-update is monotonic; clients only
   move forward.
2. **Cut a fix.** Tag `v1.0.6` with the fix.
3. Old clients on v1.0.5 receive v1.0.6 on next launch.

If `v1.0.5` is so bad that clients can't even launch (and therefore can't
auto-update), you have to manually reinstall on each affected machine. The
fix is the next release; the recovery is a manual install of the latest
working version. This is why `v0.0.1` smoke test is important — catches
launch-blocking issues before they ship to your real machines.

## Why public bucket and not a public companion repo?

Either pattern works. We chose the public-bucket approach because:
- We already had the GCP project provisioned for the data bucket. Adding a
  second bucket is one command.
- Egress costs at our scale are pennies.
- One less GitHub repo to manage.
- The public bucket is namespace-isolated from the data bucket (separate
  bucket, separate IAM, separate SA). A compromise of the release pipeline
  cannot reach project bytes.

If we ever want public discoverability ("here is a place people can browse
versions and download"), we can add a public companion repo at that point
and mirror releases there.
