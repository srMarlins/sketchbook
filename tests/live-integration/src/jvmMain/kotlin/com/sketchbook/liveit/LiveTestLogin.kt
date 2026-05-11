package com.sketchbook.liveit

import kotlinx.coroutines.runBlocking

/**
 * One-time interactive sign-in for live-integration tests.
 *
 * Usage:
 * ```
 * ./gradlew :tests:live-integration:liveTestLogin \
 *     -Dsketchbook.oauth.client_id=<your-desktop-oauth-client-id>.apps.googleusercontent.com
 * ```
 *
 * Opens the system browser to the Google OAuth consent screen, captures the auth code via
 * the loopback redirect handler in [com.sketchbook.auth.OAuthClient], exchanges it for a
 * Firebase ID + refresh token via Identity Toolkit, and persists the refresh token to
 * `~/.sketchbook-test/auth.json` (mode 0600). Subsequent push/pull/sweep tasks reuse the
 * cached token via `FirebaseAuthSession.tryRestore()`.
 */
fun main() =
    runBlocking {
        println("[liveTestLogin] env=${System.getProperty("sketchbook.env", "dev")} project=${LiveTestEnv.firebaseConfig().projectId}")
        println("[liveTestLogin] opening browser for Google sign-in…")
        LiveTestBootstrap.bootstrapForLogin().use { graph ->
            val result = graph.authSession.signIn()
            val signedIn =
                result.getOrElse { err ->
                    System.err.println("[liveTestLogin] FAILED: ${err.message}")
                    err.printStackTrace(System.err)
                    kotlin.system.exitProcess(1)
                }
            // signIn() flips state to SignedIn before returning — the result type
            // (AuthState.SignedIn) carries the userId, so we don't need to read state.value.
            println("[liveTestLogin] OK")
            println("  uid:   ${signedIn.userId.value}")
            println("  email: ${signedIn.email}")
            println("  cache: ${LiveTestEnv.tokenCachePath}")
            println("Refresh token cached. You can now run liveTestPush / liveTestPull / liveTestSweep.")
        }
    }
