# Rotate the Sketchbook GCS service-account key.
#
# What this does:
#   1. Lists existing keys for the SA so you can see what you're rotating.
#   2. Creates a new JSON key, saved to %APPDATA%\sketchbook\gcp-sa.json
#      (overwriting the existing one — back it up first if you want a
#      transition window).
#   3. Optionally deletes the OLD key by ID (pass -DeleteOldKeyId).
#
# When to run:
#   - Routine: once a year.
#   - Immediately if a machine the key was on is lost/stolen/sold.
#   - After any suspected compromise.
#   - Before adding a new contributor (rotate so the old key dies cleanly).
#
# Usage:
#   pwsh -File tools/rotate-gcp-key.ps1                          # create new key
#   pwsh -File tools/rotate-gcp-key.ps1 -DeleteOldKeyId <KEY_ID> # rotate + delete old

[CmdletBinding()]
param(
    [string]$Project        = "sketchbook-jtf-2026",
    [string]$ServiceAccount = "sketchbook-app",
    [string]$KeyDir         = "$env:APPDATA\sketchbook",
    [string]$DeleteOldKeyId = ""
)

$ErrorActionPreference = "Stop"

$gcloudBin = "$env:LOCALAPPDATA\Google\Cloud SDK\google-cloud-sdk\bin"
if (Test-Path $gcloudBin) { $env:PATH = "$gcloudBin;$env:PATH" }

$saEmail = "$ServiceAccount@$Project.iam.gserviceaccount.com"
$keyPath = "$KeyDir\gcp-sa.json"
$backupPath = "$KeyDir\gcp-sa.previous.json"

Write-Host "=== Existing keys for $saEmail ===" -ForegroundColor Cyan
gcloud iam service-accounts keys list --iam-account=$saEmail | Out-Host

if (Test-Path $keyPath) {
    Write-Host "`n=== Backing up current key to $backupPath ===" -ForegroundColor Cyan
    Copy-Item $keyPath $backupPath -Force
}

Write-Host "`n=== Creating new key at $keyPath ===" -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path $KeyDir | Out-Null
gcloud iam service-accounts keys create $keyPath --iam-account=$saEmail | Out-Host

if ($DeleteOldKeyId) {
    Write-Host "`n=== Deleting old key $DeleteOldKeyId ===" -ForegroundColor Yellow
    gcloud iam service-accounts keys delete $DeleteOldKeyId --iam-account=$saEmail --quiet | Out-Host
}

Write-Host "`n=== Verify keys after rotation ===" -ForegroundColor Cyan
gcloud iam service-accounts keys list --iam-account=$saEmail | Out-Host

Write-Host "`n=== Rotation complete ===" -ForegroundColor Green
Write-Host "New key: $keyPath"
Write-Host "Backup of previous: $backupPath"
Write-Host ""
Write-Host "Next steps:"
Write-Host "  1. Copy the new key to any other machines that need it."
Write-Host "  2. Run a smoke-test in Sketchbook to confirm the new key works."
Write-Host "  3. After confirming, delete the old key:"
Write-Host "     gcloud iam service-accounts keys list --iam-account=$saEmail"
Write-Host "     gcloud iam service-accounts keys delete <KEY_ID> --iam-account=$saEmail"
