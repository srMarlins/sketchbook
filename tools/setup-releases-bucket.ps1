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
#   4. Sets up Workload Identity Federation so GitHub Actions can impersonate
#      the SA via OIDC — no long-lived JSON key, restricted to repos owned by
#      $RepoOwner. (Org policy iam.disableServiceAccountKeyCreation forbids
#      key creation anyway, and WIF is the better practice.)

[CmdletBinding()]
param(
    [string]$Project = "sketchbook-jtf-2026",
    [string]$Bucket  = "sketchbook-releases",
    [string]$Region  = "US-EAST4",
    [string]$ServiceAccount = "sketchbook-release-uploader",
    [string]$RepoOwner = "srMarlins",
    [string]$Repo = "srMarlins/sketchbook",
    [string]$PoolId = "github",
    [string]$ProviderId = "github-provider"
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

# Note: we deliberately don't set --web-main-page-suffix on this bucket.
# GCS only honors that when the bucket is fronted by a custom domain (CNAME
# to c.storage.googleapis.com). For the raw storage.googleapis.com/<bucket>/
# URL, GCS always returns an XML listing. The landing page lives on GitHub
# Pages (.github/workflows/pages.yml) — the bucket only serves binaries +
# Conveyor update metadata.

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

Step 7 "Enable iamcredentials.googleapis.com (required for WIF token exchange)"
gcloud services enable iamcredentials.googleapis.com --project=$Project | Out-Host

Step 8 "Create Workload Identity Pool '$PoolId'"
$poolExists = $null -ne (gcloud iam workload-identity-pools describe $PoolId `
    --project=$Project --location=global --format="value(name)" 2>$null)
if ($poolExists) {
    Write-Host "Pool already exists, reusing."
} else {
    gcloud iam workload-identity-pools create $PoolId `
        --project=$Project --location=global `
        --display-name="GitHub Actions" | Out-Host
}

Step 9 "Create OIDC provider '$ProviderId' (restricted to $RepoOwner repos)"
$providerExists = $null -ne (gcloud iam workload-identity-pools providers describe $ProviderId `
    --project=$Project --location=global --workload-identity-pool=$PoolId `
    --format="value(name)" 2>$null)
if ($providerExists) {
    Write-Host "Provider already exists, reusing."
} else {
    gcloud iam workload-identity-pools providers create-oidc $ProviderId `
        --project=$Project --location=global --workload-identity-pool=$PoolId `
        --display-name="GitHub Provider" `
        --attribute-mapping="google.subject=assertion.sub,attribute.actor=assertion.actor,attribute.repository=assertion.repository,attribute.repository_owner=assertion.repository_owner,attribute.ref=assertion.ref" `
        --attribute-condition="assertion.repository_owner == '$RepoOwner'" `
        --issuer-uri="https://token.actions.githubusercontent.com" | Out-Host
}

Step 10 "Bind WIF principal -> $ServiceAccount (only $Repo can impersonate)"
$projectNumber = (gcloud projects describe $Project --format="value(projectNumber)")
$principal = "principalSet://iam.googleapis.com/projects/$projectNumber/locations/global/workloadIdentityPools/$PoolId/attribute.repository/$Repo"
gcloud iam service-accounts add-iam-policy-binding $saEmail `
    --project=$Project `
    --role="roles/iam.workloadIdentityUser" `
    --member=$principal | Select-Object -Last 4 | Out-Host

$providerResource = "projects/$projectNumber/locations/global/workloadIdentityPools/$PoolId/providers/$ProviderId"

Write-Host "`n=== Public Releases Bucket Setup Complete ===" -ForegroundColor Green
Write-Host "Public URL prefix: https://storage.googleapis.com/$Bucket/"
Write-Host "Release uploader:  $saEmail (CI only, WIF-impersonated)"
Write-Host "WIF provider:      $providerResource"
Write-Host ""
Write-Host "These values are already baked into .github/workflows/release.yml — no GitHub"
Write-Host "Secret needed for GCS auth. The workflow uses GitHub's OIDC token to"
Write-Host "impersonate the SA at runtime; no long-lived key exists."
Write-Host ""
Write-Host "Next steps:"
Write-Host "  1. Generate Conveyor signing key (one-time):"
Write-Host "       conveyor keys generate"
Write-Host "     and store the resulting signing.json content as the"
Write-Host "     CONVEYOR_SIGNING_KEY GitHub Secret:"
Write-Host "       gh secret set CONVEYOR_SIGNING_KEY --body `"`$(cat ~/.conveyor/signing.json)`""
Write-Host "  2. Tag a release (git tag v0.0.1 && git push --tags); the workflow takes it from there."
