# Sketchbook GCP setup — reproducible bootstrap.
#
# What this does:
#   1. Creates a GCP project (or uses an existing one).
#   2. Links a billing account.
#   3. Enables the APIs Sketchbook needs (Cloud Storage + IAM).
#   4. Creates the GCS bucket in US-EAST4 (NYC-adjacent), Standard class,
#      uniform bucket-level access, public-access-prevention enforced.
#   5. Creates a least-privileged service account.
#   6. Grants roles/storage.objectAdmin (scoped to this single-bucket project).
#   7. Generates a service-account JSON key and saves it OUTSIDE the repo
#      at $env:APPDATA\sketchbook\gcp-sa.json.
#
# What this does NOT do (you do it once, interactively):
#   - gcloud auth login   (browser flow — only the human can authenticate)
#   - Accept any TOS prompts on first run.
#
# Usage:
#   pwsh -File tools/gcp-setup.ps1
#   pwsh -File tools/gcp-setup.ps1 -Project "sketchbook-dev" -Bucket "sketchbook-dev" -Region "US-EAST4"
#
# Re-run safe: each step is idempotent OR will report "already exists" cleanly.

[CmdletBinding()]
param(
    [string]$Project = "sketchbook-jtf-2026",
    [string]$Bucket  = "sketchbook-jtf-2026",
    [string]$Region  = "US-EAST4",
    [string]$ServiceAccount = "sketchbook-app",
    [string]$KeyDir  = "$env:APPDATA\sketchbook",
    [string]$BillingAccount = ""   # leave empty to auto-pick the first OPEN billing account
)

$ErrorActionPreference = "Stop"

# Make sure gcloud is on PATH for this session even if it isn't system-wide.
$gcloudBin = "$env:LOCALAPPDATA\Google\Cloud SDK\google-cloud-sdk\bin"
if (Test-Path $gcloudBin) { $env:PATH = "$gcloudBin;$env:PATH" }

function Step($n, $msg) { Write-Host "`n=== Step ${n}: $msg ===" -ForegroundColor Cyan }

# ---------- Preflight ----------
Step 0 "Preflight"
$activeAccount = (gcloud config get-value account 2>$null).Trim()
if (-not $activeAccount) {
    Write-Error "No active gcloud account. Run 'gcloud auth login' first."
}
Write-Host "Active account: $activeAccount"

if (-not $BillingAccount) {
    $BillingAccount = (gcloud beta billing accounts list --filter="OPEN=true" --format="value(ACCOUNT_ID)" 2>$null | Select-Object -First 1).Trim()
    if (-not $BillingAccount) { Write-Error "No OPEN billing account found. Link one in the GCP Console first." }
}
Write-Host "Billing account: $BillingAccount"

# ---------- 1. Project ----------
Step 1 "Create or use project '$Project'"
$existing = gcloud projects describe $Project --format="value(projectId)" 2>$null
if ($existing -eq $Project) {
    Write-Host "Project already exists, reusing."
} else {
    gcloud projects create $Project --name="Sketchbook" | Out-Host
}

# ---------- 2. Billing ----------
Step 2 "Link billing"
gcloud beta billing projects link $Project --billing-account=$BillingAccount | Out-Host

# ---------- 3. Default project ----------
Step 3 "Set as default"
gcloud config set project $Project | Out-Host

# ---------- 4. Enable APIs ----------
Step 4 "Enable APIs (storage, iam, iamcredentials)"
gcloud services enable storage.googleapis.com iam.googleapis.com iamcredentials.googleapis.com | Out-Host

# ---------- 5. Bucket ----------
Step 5 "Create bucket gs://$Bucket in $Region"
$bucketExists = $null -ne (gcloud storage buckets describe "gs://$Bucket" --format="value(name)" 2>$null)
if ($bucketExists) {
    Write-Host "Bucket already exists, reusing."
} else {
    gcloud storage buckets create "gs://$Bucket" `
        --location=$Region `
        --default-storage-class=STANDARD `
        --uniform-bucket-level-access `
        --public-access-prevention | Out-Host
}

# ---------- 6. Service account ----------
Step 6 "Create service account '$ServiceAccount'"
$saEmail = "$ServiceAccount@$Project.iam.gserviceaccount.com"
$saExists = $null -ne (gcloud iam service-accounts describe $saEmail --format="value(email)" 2>$null)
if ($saExists) {
    Write-Host "Service account already exists, reusing."
} else {
    gcloud iam service-accounts create $ServiceAccount `
        --display-name="Sketchbook desktop app" `
        --description="Reads/writes the Sketchbook GCS bucket." | Out-Host
}

# ---------- 7. IAM ----------
Step 7 "Grant roles/storage.objectAdmin (project-scoped; this project holds only Sketchbook's bucket)"
gcloud projects add-iam-policy-binding $Project `
    --member="serviceAccount:$saEmail" `
    --role="roles/storage.objectAdmin" `
    --condition=None | Select-Object -Last 4 | Out-Host

# ---------- 8. JSON key ----------
Step 8 "Generate service-account JSON key"
New-Item -ItemType Directory -Force -Path $KeyDir | Out-Null
$KeyPath = "$KeyDir\gcp-sa.json"
if (Test-Path $KeyPath) {
    Write-Host "Key already exists at $KeyPath. Skipping (delete it manually to rotate)."
} else {
    gcloud iam service-accounts keys create $KeyPath --iam-account=$saEmail | Out-Host
}

# ---------- Summary ----------
Write-Host "`n=== Sketchbook GCP Setup Complete ===" -ForegroundColor Green
Write-Host "Project ID  : $Project"
Write-Host "Bucket      : gs://$Bucket  ($Region)"
Write-Host "Service Acct: $saEmail"
Write-Host "Key File    : $KeyPath  (DO NOT COMMIT)"
Write-Host "`nPaste these into Sketchbook settings (feature-settings) once the desktop app exists."
