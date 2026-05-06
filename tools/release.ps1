# Local fallback for when CI can't or you want to release from your laptop.
#
# Same end result as .github/workflows/release.yml: builds the Compose Desktop
# bundle, runs Conveyor, uploads the site to gs://sketchbook-releases, creates
# a GitHub Release with notes.
#
# Prerequisites:
#   - Conveyor CLI on PATH (`conveyor --version`)
#   - JDK 21 on PATH
#   - gh CLI authenticated (`gh auth status`)
#   - gcloud configured for the release-uploader SA OR run as your user (which
#     has owner on the project anyway)
#   - Conveyor signing key at ~/.conveyor/signing.json
#
# Usage:
#   pwsh -File tools/release.ps1 -Tag v1.0.0
#   pwsh -File tools/release.ps1 -Tag v1.0.0-rc1 -Prerelease
#   pwsh -File tools/release.ps1 -Tag v1.0.0 -DryRun     # build + skip remote-mutation steps

[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string]$Tag,
    [switch]$Prerelease,
    [switch]$DryRun,
    [string]$Bucket = "sketchbook-releases"
)

$ErrorActionPreference = "Stop"

# Must match FIRST_RELEASE_BASE in .github/workflows/release.yml so first-tag
# changelog doesn't dump the entire git history.
$FirstReleaseBase = "2336c6cd9f"

$gcloudBin = "$env:LOCALAPPDATA\Google\Cloud SDK\google-cloud-sdk\bin"
if (Test-Path $gcloudBin) { $env:PATH = "$gcloudBin;$env:PATH" }

function Step($n, $msg) { Write-Host "`n=== Step ${n}: $msg ===" -ForegroundColor Cyan }
function Skip($n, $msg) { Write-Host "`n=== Step ${n} (skipped, dry-run): $msg ===" -ForegroundColor Yellow }

if (-not $Tag.StartsWith("v")) {
    Write-Error "Tag must start with 'v' (got '$Tag')."
}

Step 1 "Verify clean tree on main"
$status = git status --porcelain
if ($status) { Write-Error "Working tree dirty. Commit or stash first." }
$branch = git branch --show-current
if ($branch -ne "main") { Write-Error "Not on main (currently on '$branch')." }

# Fail fast: tagging is non-idempotent and a duplicate tag wedges the rest of
# the pipeline (gh release create errors halfway, leaving a half-tagged repo).
$existingLocal = git tag --list $Tag
if ($existingLocal) { Write-Error "Tag '$Tag' already exists locally. Delete it first if intentional: git tag -d $Tag" }
git fetch --tags --quiet
$existingRemote = git ls-remote --tags origin "refs/tags/$Tag"
if ($existingRemote) { Write-Error "Tag '$Tag' already exists on origin. Pick a different tag." }

# Conveyor signing key is required for the make-site step. Surface the error
# now rather than ~10 min into the Gradle build.
$signingKey = Join-Path $HOME ".conveyor/signing.json"
if (-not (Test-Path $signingKey)) {
    Write-Error "Conveyor signing key not found at $signingKey. Run 'conveyor keys generate' or copy from password manager."
}

if ($DryRun) {
    Skip 2 "Tag $Tag (would: git tag $Tag && git push origin $Tag)"
} else {
    Step 2 "Tag $Tag"
    git tag $Tag
    git push origin $Tag
}

Step 3 "Gradle build"
./gradlew --no-daemon :app-desktop:build

Step 4 "Conveyor make site"
conveyor make site

if ($DryRun) {
    Skip 5 "Upload site to gs://$Bucket"
} else {
    Step 5 "Upload site to gs://$Bucket"
    # Versioned binaries get long-cache + immutable; mutable manifest stays short.
    # Mirrors the split applied in release.yml.
    gsutil -m -h "Cache-Control:public, max-age=31536000, immutable" cp -r output/* "gs://$Bucket/"
    gsutil -m -h "Cache-Control:public, max-age=300" cp output/metadata.properties "gs://$Bucket/metadata.properties"
}

Step 6 "Generate changelog"
$prev = (git tag --sort=-v:refname | Where-Object { $_ -ne $Tag } | Select-Object -First 1)
if ($prev) {
    $log = git log "$prev..$Tag" --pretty='- %s (%h)'
} else {
    # First release: scope to commits since the release pipeline landed so we
    # don't dump every commit ever.
    $log = git log "$FirstReleaseBase..$Tag" --pretty='- %s (%h)'
}
$tagNoV = $Tag.Substring(1)
$notes = @"
$log

## Download

Auto-update is enabled - installed Sketchbook clients receive this version on next launch.

Fresh installs:
- macOS (Apple Silicon): https://storage.googleapis.com/$Bucket/Sketchbook-$tagNoV-macos-aarch64.dmg
- macOS (Intel): https://storage.googleapis.com/$Bucket/Sketchbook-$tagNoV-macos-amd64.dmg
- Windows (x64): https://storage.googleapis.com/$Bucket/Sketchbook-$tagNoV-windows-amd64.msi
"@

$notesPath = Join-Path $env:TEMP "sketchbook-release-notes-$Tag.md"
$notes | Set-Content $notesPath -Encoding utf8

if ($DryRun) {
    Skip 7 "Create GitHub Release (notes written to $notesPath for inspection)"
    Write-Host "`n=== Dry run complete for $Tag ===" -ForegroundColor Green
    Write-Host "Notes preview: $notesPath"
    return
}

Step 7 "Create GitHub Release"
$ghArgs = @("release", "create", $Tag, "--title", "Sketchbook $Tag", "--notes-file", $notesPath)
if ($Prerelease) { $ghArgs += "--prerelease" }
& gh @ghArgs

Write-Host "`n=== Release $Tag complete ===" -ForegroundColor Green
Write-Host "Bucket: gs://$Bucket/"
Write-Host "GitHub: https://github.com/srMarlins/sketchbook/releases/tag/$Tag"
