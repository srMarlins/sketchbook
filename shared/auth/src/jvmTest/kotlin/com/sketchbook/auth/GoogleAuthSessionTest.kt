package com.sketchbook.auth

import com.sketchbook.core.UserId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoogleAuthSessionTest {
    @Test
    fun `init with no token emits SignedOut`() =
        runTest {
            val session =
                GoogleAuthSession(
                    tokenStore = FakeTokenStore(initial = null),
                    oauthClient = stubClient(),
                )
            assertTrue(session.state.value is AuthState.SignedOut)
        }

    @Test
    fun `signIn flows to SignedIn and persists refresh token`() =
        runTest {
            val store = FakeTokenStore()
            val session =
                GoogleAuthSession(
                    tokenStore = store,
                    oauthClient = stubClient(),
                )

            val result = session.signIn().getOrThrow()
            assertEquals("11223344", result.userId.value)
            assertEquals("alice@example.com", result.email)
            assertEquals(listOf("rt-FRESH"), store.writes)
            assertTrue(session.state.value is AuthState.SignedIn)
        }

    @Test
    fun `signOut clears state and token`() =
        runTest {
            val store = FakeTokenStore()
            val session =
                GoogleAuthSession(
                    tokenStore = store,
                    oauthClient = stubClient(),
                )
            session.signIn().getOrThrow()
            session.signOut()
            assertTrue(session.state.value is AuthState.SignedOut)
            assertEquals(1, store.clears.get())
        }

    @Test
    fun `refresh failure flips state to SignedOut and throws AuthSessionExpired`() =
        runTest {
            val store = FakeTokenStore()
            val stub = StubOAuthClient()
            val session =
                GoogleAuthSession(
                    tokenStore = store,
                    oauthClient = stub,
                )
            // signIn populates the cached refresh token + access token + expiry.
            session.signIn().getOrThrow()
            // Poison the next refresh.
            stub.refreshOutcome = OAuthClient.RefreshResult.Invalid("invalid_grant")
            // Force the cached access token to expire so the next accessToken() call must refresh.
            session.expireForTest()

            assertFailsWith<AuthSessionExpired> { session.accessToken() }
            assertTrue(session.state.value is AuthState.SignedOut)
            // sign-out via expired refresh should also wipe the persisted refresh token.
            assertEquals(1, store.clears.get())
        }
}

/** Minimal in-process stub for OAuthFlow that doesn't talk to a network. */
private class StubOAuthClient(
    var signInOutcome: OAuthTokens =
        OAuthTokens(
            accessToken = "at-FAKE",
            refreshToken = "rt-FRESH",
            idToken = "fake-google-id-token",
            expiresInSeconds = 3600,
            userId = UserId("11223344"),
            email = "alice@example.com",
        ),
    var refreshOutcome: OAuthClient.RefreshResult = OAuthClient.RefreshResult.Ok("at-REFRESHED", 3600),
) : OAuthFlow {
    override suspend fun signIn(): Result<OAuthTokens> = Result.success(signInOutcome)

    override suspend fun refresh(refreshToken: String): OAuthClient.RefreshResult = refreshOutcome
}

private fun stubClient(
    signInOutcome: OAuthTokens =
        OAuthTokens(
            accessToken = "at-FAKE",
            refreshToken = "rt-FRESH",
            idToken = "fake-google-id-token",
            expiresInSeconds = 3600,
            userId = UserId("11223344"),
            email = "alice@example.com",
        ),
    refreshOutcome: OAuthClient.RefreshResult = OAuthClient.RefreshResult.Ok("at-REFRESHED", 3600),
): OAuthFlow = StubOAuthClient(signInOutcome, refreshOutcome)
