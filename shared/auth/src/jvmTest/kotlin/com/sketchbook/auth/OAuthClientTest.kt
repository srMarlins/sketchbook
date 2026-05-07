package com.sketchbook.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class OAuthClientTest {

    // Uses real-time runBlocking, not runTest's virtual TestDispatcher: the loopback HTTP server
    // and the in-test callback HTTP request both run on real I/O threads. Under runTest, the
    // OAuthClient's `withTimeoutOrNull(callbackTimeout)` would fire immediately because virtual
    // time advances past the 5-minute timeout while the real I/O is still in flight.
    @Test
    fun `signIn round-trip exchanges code for tokens`() = runBlocking {
        // Mock Google's token endpoint: returns access+refresh+id token for any code.
        val tokenEngine = MockEngine { _ ->
            val body = """
                {
                  "access_token": "at-FAKE",
                  "refresh_token": "rt-FAKE",
                  "id_token": "${makeFakeIdToken(sub = "11223344", email = "alice@example.com")}",
                  "expires_in": 3600,
                  "token_type": "Bearer"
                }
            """.trimIndent()
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        // Real-IO scope so the loopback HTTP client can issue a network request to the
        // local java.net.httpserver — runTest's TestDispatcher would suspend forever otherwise.
        val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val client = OAuthClient(
            httpClient = HttpClient(tokenEngine),
            clientId = "test-client",
            tokenUri = "https://oauth2.googleapis.com/token",
            authUri = "https://accounts.google.com/o/oauth2/v2/auth",
            scopes = listOf("openid", "email"),
            // Inject a fake browser: as soon as the loopback server is up, it hits the callback.
            browserOpener = { url ->
                val state = Regex("state=([^&]+)").find(url)!!.groupValues[1]
                val redirectUri = Regex("redirect_uri=([^&]+)").find(url)!!.groupValues[1]
                    .replace("%3A", ":").replace("%2F", "/")
                callbackScope.launch {
                    val callbackHttp = HttpClient()
                    try {
                        callbackHttp.get("$redirectUri?code=fake-code&state=$state")
                    } finally {
                        callbackHttp.close()
                    }
                }
            },
        )

        val result = client.signIn()
        val tokens = result.getOrThrow()
        assertEquals("at-FAKE", tokens.accessToken)
        assertEquals("rt-FAKE", tokens.refreshToken)
        assertEquals("11223344", tokens.userId.value)
        assertEquals("alice@example.com", tokens.email)
    }
}

/**
 * Build an unsigned, base64url JWT with the given payload — enough for OAuthClient to parse `sub`
 * + `email`, since signature verification of Google ID tokens is not in OAuthClient's job (the
 * tokens come from the IdP's own TLS-secured endpoint).
 */
private fun makeFakeIdToken(sub: String, email: String): String {
    val header = """{"alg":"none","typ":"JWT"}"""
    val payload = """{"sub":"$sub","email":"$email","aud":"test-client","iss":"https://accounts.google.com"}"""
    fun b64(s: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
    return "${b64(header)}.${b64(payload)}.fake-sig"
}
