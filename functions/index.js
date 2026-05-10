/**
 * Sketchbook Cloud Functions.
 *
 * Security commitment #3 Part B (docs/plans/2026-05-08-firebase-migration-design.md):
 * client-driven Firebase refresh-token revocation. The Admin SDK side has no public
 * client-facing endpoint, so a server-side callable is the only way to give a signed-in
 * user a "revoke my session" primitive that actually invalidates the refresh token across
 * the project. Closes the race window between sign-out (clear keyring + memory) and a
 * malicious process holding a copy of the refresh token: once `revokeRefreshTokens(uid)`
 * lands, that copy is dead.
 *
 * Function: revokeMySession
 *   Trigger: HTTPS callable
 *   Auth:    requires a valid Firebase ID token (the caller's own — no escalation;
 *            uid taken from request.auth, never from request body)
 *   Action:  admin.auth().revokeRefreshTokens(uid)
 *   Returns: { revoked: true, uid } on success; throws HttpsError on auth failure.
 *
 * Deployment: `firebase deploy --only functions` from the repo root.
 *
 * Cost: free-tier on Blaze covers ~125K invocations/month — well above a sign-out cadence.
 */

const { onCall, HttpsError } = require('firebase-functions/v2/https');
const admin = require('firebase-admin');

admin.initializeApp();

/**
 * Revoke the calling user's Firebase refresh tokens. After this returns, every existing
 * refresh token for that UID is invalidated server-side; subsequent token-refresh attempts
 * from any device fail.
 *
 * Idempotent — revoking already-revoked tokens succeeds with the same payload. The client
 * calls this best-effort during sign-out; a network failure here is logged and the local
 * keyring + in-memory state still get cleared.
 */
exports.revokeMySession = onCall(async (request) => {
  const uid = request.auth && request.auth.uid;
  if (!uid) {
    // No bearer token (or unverifiable) — refuse. Anonymous sign-outs don't need this
    // function in the first place; it exists for authenticated sign-outs.
    throw new HttpsError('unauthenticated', 'revokeMySession requires a signed-in caller');
  }
  await admin.auth().revokeRefreshTokens(uid);
  return { revoked: true, uid };
});
