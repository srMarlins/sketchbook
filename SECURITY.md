# Security Policy

## Reporting a vulnerability

Please report security issues privately to **srmarlins@gmail.com** with `[security]` in the subject line. Do **not** open a public GitHub issue.

A response acknowledging receipt should arrive within 5 business days. If the report is in scope (see below), expect a follow-up with a remediation timeline; out-of-scope reports get a polite no.

There is no bug bounty.

## Scope

Sketchbook is a desktop app for managing a local Ableton Live library, with optional cloud-sync to a Cloudflare R2 / Backblaze B2 bucket the user owns. Issues we'd like to hear about:

- **Local code execution** triggered by a malicious `.als` file or a malicious filename in a project root.
- **Path traversal / sandbox escape** in the library scanner, sample resolver, or `.als` parser.
- **Credential leakage** from the cloud backend (service-account JSON, presigned URLs, OAuth tokens).
- **MCP server abuse** that lets a remote model exfiltrate paths or contents from the host machine beyond what the user-approved tools should allow.
- **Update-channel attacks** against Conveyor's auto-update flow (downgrade attacks, MITM on the manifest, signature bypass).
- **CI/release-pipeline compromise** that lets an attacker push a tampered binary to the public releases bucket.

Out of scope:

- Anything requiring physical access to an unlocked machine.
- Vulnerabilities in plugins/VSTs the user has installed (those run in Live, not in Sketchbook).
- Denial-of-service via a malformed `.als` (we treat unparseable files as `parse_status='failed'`; that's by design, not a bug).
- Findings in dependencies that are already fixed in a newer version. Open a normal Dependabot PR instead.

## Disclosure timeline

We aim to ship a fix within 90 days of a confirmed in-scope report. After the fix releases, we'll credit the reporter in the release notes unless they prefer to stay anonymous. If the issue is exploitable in the wild, we may release the fix earlier and disclose details later.

## What we already do

- WAL+pragma-tuned SQLite catalog, no untrusted SQL.
- StAX streaming `.als` parser with `FEATURE_SECURE_PROCESSING` enabled and external entities disabled.
- Library scanner refuses to follow symlinks and confines walks to the canonical root path.
- `propose_batch` MCP tool enforces a closed allowlist of action types.
- Releases bucket via Workload Identity Federation, no long-lived JSON key in CI; release uploader SA has bucket-scoped access only.
- Gitleaks runs on every PR + push; Dependabot runs grouped weekly.

## What we'd like to do

These are tracked in `docs/plans/2026-05-06-1.0-release-readiness-plan.md` (PR-I) and the original 6-reviewer audit:

- Apple Developer ID / Authenticode signing on the installers.
- OS-keychain credential storage (currently plaintext `Preferences`; v1.1).
- Per-tenant blob namespacing + signed read URLs (multi-user, v1.2).
