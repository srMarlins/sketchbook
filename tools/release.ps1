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

[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string]$Tag,
    [switch]$Prerelease,
    [string]$Bucket = "sketchbook-releases"
)

$ErrorActionPreference = "Stop"

$gcloudBin = "$env:LOCALAPPDATA\Google\Cloud SDK\google-cloud-sdk\bin"
if (Test-Path $gcloudBin) { $env:PATH = "$gcloudBin;$env:PATH" }

function Step($n, $msg) { Write-Host "`n=== Step ${n}: $msg ===" -ForegroundColor Cyan }

if (-not $Tag.StartsWith("v")) {
    Write-Error "Tag must start with 'v' (got '$Tag')."
}

Step 1 "Verify clean tree on main"
$status = git status --porcelain
if ($status) { Write-Error "Working tree dirty. Commit or stash first." }
$branch = git branch --show-current
if ($branch -ne "main") { Write-Error "Not on main (currently on '$branch')." }

Step 2 "Tag $Tag"
git tag $Tag
git push origin $Tag

Step 3 "Gradle build"
./gradlew --no-daemon :app-desktop:build

Step 4 "Conveyor make site"
conveyor make site

Step 5 "Upload site to gs://$Bucket"
gsutil -m -h "Cache-Control:public, max-age=300" cp -r output/* "gs://$Bucket/"

Step 6 "Generate changelog"
$prev = (git tag --sort=-v:refname | Where-Object { $_ -ne $Tag } | Select-Object -First 1)
if ($prev) {
    $log = git log "$prev..$Tag" --pretty='- %s (%h)'
} else {
    $log = git log --pretty='- %s (%h)'
}
$tagNoV = $Tag.Substring(1)
$notes = @"
$log

## Download

Auto-update is enabled — installed Sketchbook clients receive this version on next launch.

Fresh installs:
- macOS (Apple Silicon): https://storage.googleapis.com/$Bucket/Sketchbook-$tagNoV-macos-aarch64.dmg
- macOS (Intel): https://storage.googleapis.com/$Bucket/Sketchbook-$tagNoV-macos-amd64.dmg
- Windows (x64): https://storage.googleapis.com/$Bucket/Sketchbook-$tagNoV-windows-amd64.msi
"@

$notesPath = Join-Path $env:TEMP "sketchbook-release-notes-$Tag.md"
$notes | Set-Content $notesPath -Encoding utf8

Step 7 "Create GitHub Release"
$ghArgs = @("release", "create", $Tag, "--title", "Sketchbook $Tag", "--notes-file", $notesPath)
if ($Prerelease) { $ghArgs += "--prerelease" }
& gh @ghArgs

Write-Host "`n=== Release $Tag complete ===" -ForegroundColor Green
Write-Host "Bucket: gs://$Bucket/"
Write-Host "GitHub: https://github.com/srMarlins/sketchbook/releases/tag/$Tag"
