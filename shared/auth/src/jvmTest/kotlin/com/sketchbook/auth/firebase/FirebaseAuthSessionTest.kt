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
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Smoke tests for [FirebaseAuthSession]'s orchestration:
 *
 *   OAuthFlow.signIn() → GoogleIdTokenVerifier.verify() → IdentityToolkitClient.signInWithIdp()
 *
 * Wire-format tests for Identity Toolkit calls live in [IdentityToolkitClientTest].
 */
class FirebaseAuthSessionTest {
    @Test
    fun `signIn verifies Google ID token then exchanges for Firebase tokens`() =
        runTest {
            val store = FakeTokenStore()
            val session = newSession(store = store, exchangeResponse = EXCHANGE_OK)

            val signedIn = session.signIn().getOrThrow()

            assertEquals("FAKE_FIREBASE_UID", signedIn.userId.value)
            assertEquals("alice@example.com", signedIn.email)
            // The persisted refresh token is the FIREBASE one, not the Google one.
            assertEquals(listOf("FAKE_FIREBASE_REFRESH"), store.writes)
            assertTrue(session.state.value is AuthState.SignedIn)
        }

    @Test
    fun `accessToken returns the Firebase ID token from the exchange`() =
        runTest {
            val session = newSession(exchangeResponse = EXCHANGE_OK)
            session.signIn().getOrThrow()

            assertEquals("FAKE_FIREBASE_ID_TOKEN", session.accessToken())
        }

    @Test
    fun `accessToken refreshes when cached token has expired`() =
        runTest {
            val clock = ControllableClock()
            val responses = ResponseQueue(EXCHANGE_OK, REFRESH_OK)
            val session = newSession(clock = clock, responses = responses)
            session.signIn().getOrThrow()

            // Move past the cached expiry; next accessToken hits the refresh endpoint.
            clock.advance(2.hours)

            assertEquals("FRESH_FIREBASE_ID_TOKEN", session.accessToken())
        }

    @Test
    fun `signOut clears state and persisted refresh token`() =
        runTest {
            val store = FakeTokenStore()
            val session = newSession(store = store, exchangeResponse = EXCHANGE_OK)
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
                newSession(
                    store = store,
                    verifier = AlwaysRejectVerifier,
                    exchangeResponse = EXCHANGE_OK,
                )

            val result = session.signIn()

            assertTrue(result.isFailure)
            // We never crossed the wire to Identity Toolkit, so the store has no writes.
            assertEquals(emptyList(), store.writes)
            assertTrue(session.state.value is AuthState.SignedOut)
        }

    @Test
    fun `expired refresh token flips state to SignedOut and throws`() =
        runTest {
            val clock = ControllableClock()
            val store = FakeTokenStore()
            val responses = ResponseQueue(EXCHANGE_OK, REFRESH_FAIL)
            val session = newSession(clock = clock, store = store, responses = responses)
            session.signIn().getOrThrow()
            clock.advance(2.hours)

            assertFailsWith<AuthSessionExpired> { session.accessToken() }
            assertTrue(session.state.value is AuthState.SignedOut)
            assertEquals(1, store.clears.get())
        }

    @Test
    fun `tryRestore mints a fresh ID token from a stored refresh token`() =
        runTest {
            val store = FakeTokenStore(initial = "STORED_REFRESH")
            val session = newSession(store = store, exchangeResponse = REFRESH_OK)

            session.tryRestore()

            assertEquals("FRESH_FIREBASE_ID_TOKEN", session.accessToken())
        }

    @Test
    fun `tryRestore clears the keyring when refresh fails`() =
        runTest {
            val store = FakeTokenStore(initial = "DEAD_REFRESH")
            val session = newSession(store = store, exchangeResponse = REFRESH_FAIL)

            session.tryRestore()

            assertEquals(1, store.clears.get())
        }

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    private fun newSession(
        clock: Clock = Clock.System,
        store: FakeTokenStore = FakeTokenStore(),
        verifier: GoogleIdTokenVerifier = AlwaysAcceptVerifier,
        oauthClient: OAuthFlow = StubOAuthClient,
        exchangeResponse: MockResponseFixture? = null,
        responses: ResponseQueue = ResponseQueue(exchangeResponse ?: EXCHANGE_OK),
    ): FirebaseAuthSession {
        val http = HttpClient(MockEngine { _ -> responses.next().respondVia(this) })
        return FirebaseAuthSession(
            tokenStore = store,
            oauthClient = oauthClient,
            identityToolkit = IdentityToolkitClient(http, webApiKey = "TEST_KEY", clock = clock),
            googleIdTokenVerifier = verifier,
            clock = clock,
        )
    }

    /** A clock the test moves forward manually — replaces the previous `expireForTest()` hack. */
    private class ControllableClock(
        private var now: Instant = Instant.fromEpochSeconds(1_700_000_000),
    ) : Clock {
        override fun now(): Instant = now

        fun advance(by: Duration) {
            now += by
        }
    }

    /** Sequences mock HTTP responses so a test can simulate exchange→refresh→… */
    private class ResponseQueue(
        vararg responses: MockResponseFixture,
    ) {
        private val queue = ArrayDeque(responses.toList())

        fun next(): MockResponseFixture = queue.removeFirstOrNull() ?: error("No more queued responses")
    }

    private data class MockResponseFixture(
        val body: String,
        val status: HttpStatusCode,
    ) {
        fun respondVia(scope: MockRequestHandleScope): HttpResponseData =
            scope.respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
    }

    private object StubOAuthClient : OAuthFlow {
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

    /**
     * Bypasses the real Nimbus-backed verifier so unit tests don't need RSA keys or network
     * access. End-to-end JWKS verification is exercised against real Google in the spike runs
     * (and, follow-up, against the Firebase Auth emulator).
     */
    private val AlwaysAcceptVerifier: GoogleIdTokenVerifier =
        GoogleIdTokenVerifier {
            Result.success(
                VerifiedGoogleIdToken(
                    sub = "google-sub",
                    email = "alice@example.com",
                    emailVerified = true,
                    issuer = "https://accounts.google.com",
                    audience = "ignored",
                    expiresAt = Instant.DISTANT_FUTURE,
                ),
            )
        }

    private val AlwaysRejectVerifier: GoogleIdTokenVerifier =
        GoogleIdTokenVerifier { Result.failure(IllegalStateException("untrusted issuer")) }

    private companion object {
        val EXCHANGE_OK =
            MockResponseFixture(
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

        val REFRESH_OK =
            MockResponseFixture(
                body =
                    """
                    {
                      "id_token": "FRESH_FIREBASE_ID_TOKEN",
                      "refresh_token": "FAKE_FIREBASE_REFRESH",
                      "user_id": "FAKE_FIREBASE_UID",
                      "expires_in": "3600"
                    }
                    """.trimIndent(),
                status = HttpStatusCode.OK,
            )

        val REFRESH_FAIL =
            MockResponseFixture(
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

        @Suppress("unused")
        private val unused: Duration = 60.seconds
    }
}
