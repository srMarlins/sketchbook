package com.sketchbook.auth.firebase

import com.sketchbook.auth.AuthSessionExpired
import com.sketchbook.auth.AuthState
import com.sketchbook.auth.FakeTokenStore
import com.sketchbook.auth.OAuthClient
import com.sketchbook.auth.OAuthFlow
import com.sketchbook.auth.OAuthTokens
import com.sketchbook.core.UserId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Smoke tests for [FirebaseAuthSession]. The orchestration under test:
 *
 *   OAuthFlow.signIn() → GoogleIdTokenVerifier.verify() → IdentityToolkitClient.signInWithIdp()
 *
 * Wire-format tests for the Identity Toolkit calls themselves live in
 * [IdentityToolkitClientTest]. Here we focus on the auth-session behavior:
 * sign-in mints + persists Firebase tokens, sign-out clears them, and refresh
 * failure flips state.
 */
class FirebaseAuthSessionTest {
    @Test
    fun `signIn verifies Google ID token then exchanges for Firebase tokens`() =
        runTest {
            val store = FakeTokenStore()
            val identityToolkit =
                IdentityToolkitClient(
                    httpClient = mockExchangeOk(),
                    webApiKey = "TEST_KEY",
                )
            val session =
                FirebaseAuthSession(
                    tokenStore = store,
                    oauthClient = stubOAuth(),
                    identityToolkit = identityToolkit,
                    googleIdTokenVerifier = AlwaysAcceptVerifier(),
                )

            val signedIn = session.signIn().getOrThrow()

            assertEquals("FAKE_FIREBASE_UID", signedIn.userId.value)
            assertEquals("alice@example.com", signedIn.email)
            // Persisted refresh token is the FIREBASE one, not the Google one.
            assertEquals(listOf("FAKE_FIREBASE_REFRESH"), store.writes)
            assertTrue(session.state.value is AuthState.SignedIn)
        }

    @Test
    fun `accessToken returns the Firebase ID token from the exchange`() =
        runTest {
            val session =
                FirebaseAuthSession(
                    tokenStore = FakeTokenStore(),
                    oauthClient = stubOAuth(),
                    identityToolkit = IdentityToolkitClient(mockExchangeOk(), "TEST_KEY"),
                    googleIdTokenVerifier = AlwaysAcceptVerifier(),
                )
            session.signIn().getOrThrow()

            assertEquals("FAKE_FIREBASE_ID_TOKEN", session.accessToken())
        }

    @Test
    fun `signOut clears state and persisted refresh token`() =
        runTest {
            val store = FakeTokenStore()
            val session =
                FirebaseAuthSession(
                    tokenStore = store,
                    oauthClient = stubOAuth(),
                    identityToolkit = IdentityToolkitClient(mockExchangeOk(), "TEST_KEY"),
                    googleIdTokenVerifier = AlwaysAcceptVerifier(),
                )
            session.signIn().getOrThrow()

            session.signOut()

            assertTrue(session.state.value is AuthState.SignedOut)
            assertEquals(1, store.clears.get())
        }

    @Test
    fun `signIn fails fast when Google ID token verification fails`() =
        runTest {
            val store = FakeTokenStore()
            val session =
                FirebaseAuthSession(
                    tokenStore = store,
                    oauthClient = stubOAuth(),
                    identityToolkit = IdentityToolkitClient(mockExchangeOk(), "TEST_KEY"),
                    googleIdTokenVerifier = AlwaysRejectVerifier(),
                )

            val result = session.signIn()

            assertTrue(result.isFailure)
            // We never crossed the wire to Identity Toolkit, so the store has no writes.
            assertEquals(emptyList(), store.writes)
            assertTrue(session.state.value is AuthState.SignedOut)
        }

    @Test
    fun `accessToken refresh failure flips state to SignedOut and throws`() =
        runTest {
            val store = FakeTokenStore()
            // First call: exchange succeeds. Second call (refresh): fails with 400.
            val responses = mutableListOf<MockResponse>()
            responses += MockResponse.exchangeOk()
            responses += MockResponse.refreshFail()
            val client = HttpClient(MockEngine { responses.removeAt(0).respond(this) })
            val session =
                FirebaseAuthSession(
                    tokenStore = store,
                    oauthClient = stubOAuth(),
                    identityToolkit = IdentityToolkitClient(client, "TEST_KEY"),
                    googleIdTokenVerifier = AlwaysAcceptVerifier(),
                )
            session.signIn().getOrThrow()
            session.expireForTest()

            assertFailsWith<AuthSessionExpired> { session.accessToken() }
            assertTrue(session.state.value is AuthState.SignedOut)
            assertEquals(1, store.clears.get())
        }

    // ---------------------------------------------------------------------------------------
    // Test fixtures
    // ---------------------------------------------------------------------------------------

    private fun stubOAuth(): OAuthFlow =
        object : OAuthFlow {
            override suspend fun signIn(): Result<OAuthTokens> =
                Result.success(
                    OAuthTokens(
                        accessToken = "fake-google-access",
                        refreshToken = "fake-google-refresh",
                        idToken = "fake-google-id-token",
                        expiresInSeconds = 3600,
                        userId = UserId("google-sub"),
                        email = "alice@example.com",
                    ),
                )

            override suspend fun refresh(refreshToken: String): OAuthClient.RefreshResult =
                OAuthClient.RefreshResult.Ok("fake-google-access-2", 3600)
        }

    private fun mockExchangeOk(): HttpClient =
        HttpClient(
            MockEngine { _ ->
                respond(
                    content =
                        """
                        {
                          "idToken": "FAKE_FIREBASE_ID_TOKEN",
                          "refreshToken": "FAKE_FIREBASE_REFRESH",
                          "localId": "FAKE_FIREBASE_UID",
                          "expiresIn": "3600",
                          "email": "alice@example.com"
                        }
                        """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

    private class MockResponse private constructor(
        val body: String,
        val status: HttpStatusCode,
    ) {
        suspend fun respond(scope: io.ktor.client.engine.mock.MockRequestHandleScope) =
            scope.respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )

        companion object {
            fun exchangeOk() =
                MockResponse(
                    body =
                        """
                        {
                          "idToken": "FAKE_FIREBASE_ID_TOKEN",
                          "refreshToken": "FAKE_FIREBASE_REFRESH",
                          "localId": "FAKE_FIREBASE_UID",
                          "expiresIn": "3600",
                          "email": "alice@example.com"
                        }
                        """.trimIndent(),
                    status = HttpStatusCode.OK,
                )

            fun refreshFail() =
                MockResponse(
                    body =
                        """
                        {
                          "error": {
                            "code": 400,
                            "message": "TOKEN_EXPIRED"
                          }
                        }
                        """.trimIndent(),
                    status = HttpStatusCode.BadRequest,
                )
        }
    }

    /**
     * Test seam — bypasses the real Nimbus-backed verifier so unit tests don't need RSA keys
     * or network access. End-to-end JWKS verification is exercised in the spike runs and (a
     * follow-up) by FirebaseAuthSessionIntegrationTest against the Firebase Auth emulator.
     */
    private open class AlwaysAcceptVerifier : GoogleIdTokenVerifier(expectedAudience = "ignored") {
        override fun verify(idToken: String): Result<VerifiedGoogleIdToken> {
            // We can't pass a fake Instant easily without a Clock injected here; use a far-future
            // value so any subsequent expiry check passes.
            val now = Clock.System.now()
            return Result.success(
                VerifiedGoogleIdToken(
                    sub = "google-sub",
                    email = "alice@example.com",
                    emailVerified = true,
                    issuer = "https://accounts.google.com",
                    audience = "ignored",
                    expiresAt = now + 3600.seconds,
                ),
            )
        }
    }

    private class AlwaysRejectVerifier : GoogleIdTokenVerifier(expectedAudience = "ignored") {
        override fun verify(idToken: String): Result<VerifiedGoogleIdToken> =
            Result.failure(IllegalStateException("untrusted issuer"))
    }
}

@Suppress("unused")
private val NowSeed: Instant = Clock.System.now()
