# One-time setup for the PUBLIC releases bucket (gs://sketchbook-releases).
#
# Why a separate bucket from gs://sketchbook-jtf-2026:
#   - The data bucket has uniform-bucket-level access + public-access-prevention
#     ENFORCED. Releases need anonymous read access. Different security needs;
#     different buckets.
#
# What this does:
#   1. Creates gs://sketchbook-releases in US-EAST4 with public-read access.
#   2. Creates a dedicated service account for release uploads (not reusing the
#      app SA — separation of duties: the app SA can't push fake releases).
#   3. Grants that SA roles/storage.objectAdmin on the releases bucket only.
#   4. Generates a JSON key for the SA and prints it so you can paste it into
#      GitHub Secrets as SKETCHBOOK_RELEASES_SA.

[CmdletBinding()]
param(
    [string]$Project = "sketchbook-jtf-2026",
    [string]$Bucket  = "sketchbook-releases",
    [string]$Region  = "US-EAST4",
    [string]$ServiceAccount = "sketchbook-release-uploader",
    [string]$KeyDir  = "$env:APPDATA\sketchbook"
)

$ErrorActionPreference = "Stop"

$gcloudBin = "$env:LOCALAPPDATA\Google\Cloud SDK\google-cloud-sdk\bin"
if (Test-Path $gcloudBin) { $env:PATH = "$gcloudBin;$env:PATH" }

function Step($n, $msg) { Write-Host "`n=== Step ${n}: $msg ===" -ForegroundColor Cyan }

Step 1 "Set project to $Project"
gcloud config set project $Project | Out-Host

Step 2 "Create public releases bucket gs://$Bucket in $Region"
$bucketExists = $null -ne (gcloud storage buckets describe "gs://$Bucket" --format="value(name)" 2>$null)
if ($bucketExists) {
    Write-Host "Bucket already exists, reusing."
} else {
    # NO --public-access-prevention here (we WANT public reads).
    # Uniform bucket-level access still on for clean IAM.
    gcloud storage buckets create "gs://$Bucket" `
        --location=$Region `
        --default-storage-class=STANDARD `
        --uniform-bucket-level-access | Out-Host
}

Step 3 "Make bucket world-readable (allUsers as object viewer)"
gcloud storage buckets add-iam-policy-binding "gs://$Bucket" `
    --member="allUsers" `
    --role="roles/storage.objectViewer" | Select-Object -Last 4 | Out-Host

Step 4 "Set short cache header default for the auto-update manifest"
# Conveyor's metadata.json must be fresh; binaries can cache longer. We rely
# on the workflow setting Cache-Control:public,max-age=300 on upload, but a
# bucket-default is a belt-and-suspenders fallback.
# (No bucket-level default cache header on GCS — handled at upload time.)
Write-Host "Cache headers handled per-object at upload time in release.yml."

Step 5 "Create release-uploader service account '$ServiceAccount'"
$saEmail = "$ServiceAccount@$Project.iam.gserviceaccount.com"
$saExists = $null -ne (gcloud iam service-accounts describe $saEmail --format="value(email)" 2>$null)
if ($saExists) {
    Write-Host "Service account already exists, reusing."
} else {
    gcloud iam service-accounts create $ServiceAccount `
        --display-name="Sketchbook release uploader (CI only)" `
        --description="Used by GitHub Actions to push release artifacts to gs://$Bucket. Has NO access to the data bucket." | Out-Host
}

Step 6 "Grant releases-bucket-only objectAdmin"
gcloud storage buckets add-iam-policy-binding "gs://$Bucket" `
    --member="serviceAccount:$saEmail" `
    --role="roles/storage.objectAdmin" | Select-Object -Last 4 | Out-Host

Step 7 "Generate the SA key for GitHub Actions"
New-Item -ItemType Directory -Force -Path $KeyDir | Out-Null
$keyPath = "$KeyDir\release-uploader-sa.json"
if (Test-Path $keyPath) {
    Write-Host "Key already exists at $keyPath. Delete it manually if you want to rotate."
} else {
    gcloud iam service-accounts keys create $keyPath --iam-account=$saEmail | Out-Host
}

Write-Host "`n=== Public Releases Bucket Setup Complete ===" -ForegroundColor Green
Write-Host "Public URL prefix: https://storage.googleapis.com/$Bucket/"
Write-Host "Release uploader: $saEmail (CI only)"
Write-Host "SA key path:      $keyPath  (DO NOT COMMIT)"
Write-Host ""
Write-Host "Next steps:"
Write-Host "  1. Copy the contents of $keyPath into a new GitHub Secret"
Write-Host "     named SKETCHBOOK_RELEASES_SA on srMarlins/sketchbook."
Write-Host "       gh secret set SKETCHBOOK_RELEASES_SA --body `"$(Get-Content $keyPath -Raw)`""
Write-Host "  2. Generate Conveyor signing key (one-time):"
Write-Host "       conveyor keys generate"
Write-Host "     and store the resulting signing.json content as the"
Write-Host "     CONVEYOR_SIGNING_KEY GitHub Secret."
Write-Host "  3. Tag a release (`git tag v0.0.1 && git push --tags`); the workflow takes it from there."
