<#
.SYNOPSIS
  Grant a user IAM permission on the Sketchbook GCS bucket, scoped to their tenant prefix.

.DESCRIPTION
  Sketchbook uses a single shared bucket with per-user object-name prefixes (`users/<sub>/...`).
  This script adds an IAM Condition that lets the given Google identity read/write only their
  own prefix. Run this once per new signup, after the user has signed in at least once and you
  have their Google `sub` (numeric subject identifier from the ID token — surface it via the
  app's logs or read from the bucket's `users/<sub>/` prefix that appears on first sync).

.PARAMETER Email
  The user's Google account email address.

.PARAMETER Sub
  The user's Google `sub` claim — a numeric string.

.PARAMETER Bucket
  The GCS bucket name. Defaults to "sketchbook-prod".

.EXAMPLE
  ./tools/grant-user.ps1 -Email alice@example.com -Sub 117449212344556677889
#>
param(
  [Parameter(Mandatory = $true)] [string] $Email,
  [Parameter(Mandatory = $true)] [string] $Sub,
  [string] $Bucket = "sketchbook-prod"
)

$ErrorActionPreference = "Stop"

$expression = @"
resource.name.startsWith("projects/_/buckets/$Bucket/objects/users/$Sub/")
"@

# strip newlines for the IAM condition body
$expression = $expression -replace "`r?`n", " "

$conditionTitle = "tenant_$Sub"

gcloud storage buckets add-iam-policy-binding "gs://$Bucket" `
  --member="user:$Email" `
  --role="roles/storage.objectAdmin" `
  --condition="expression=$expression,title=$conditionTitle"

Write-Host "Granted $Email objectAdmin on gs://$Bucket scoped to users/$Sub/"
