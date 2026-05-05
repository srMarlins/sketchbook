package com.sketchbook.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.charsets.Charsets
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Instant
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GcsAuthTest {

    private fun fixtureKey(): Pair<ServiceAccountKey, java.security.PublicKey> {
        val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val pair = gen.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
        val key = ServiceAccountKey(
            type = "service_account",
            projectId = "sketchbook-test",
            privateKeyId = "kid-1",
            privateKeyPem = pem,
            clientEmail = "test@sketchbook-test.iam.gserviceaccount.com",
        )
        return key to pair.public
    }

    @Test
    fun signJwtProducesVerifiableSignature() = runTest {
        val (key, publicKey) = fixtureKey()
        val auth = GcsAuth(key, HttpClient(MockEngine { error("not called") }))

        val jwt = auth.signJwt(now = Instant.parse("2026-05-05T12:00:00Z"))

        val parts = jwt.split('.')
        assertEquals(3, parts.size)

        // Header decodes to expected JSON.
        val header = String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8)
        assertTrue(header.contains("\"alg\":\"RS256\""))
        assertTrue(header.contains("\"kid\":\"kid-1\""))

        // Claims decode and contain the right fields.
        val claims = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
        assertTrue(claims.contains("\"iss\":\"test@sketchbook-test.iam.gserviceaccount.com\""))
        assertTrue(claims.contains("\"aud\":\"https://oauth2.googleapis.com/token\""))
        assertTrue(claims.contains("\"exp\":"))
        assertTrue(claims.contains("\"iat\":"))

        // Signature verifies against the matching public key.
        val signingInput = "${parts[0]}.${parts[1]}".toByteArray(Charsets.UTF_8)
        val sig = Base64.getUrlDecoder().decode(parts[2])
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(publicKey)
            update(signingInput)
        }
        assertTrue(verifier.verify(sig), "JWT signature should verify against the issuing public key")
    }

    @Test
    fun exchangeJwtPostsCorrectFormBody() = runTest {
        val (key, _) = fixtureKey()
        var capturedBody: String? = null
        var capturedUrl: String? = null
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            capturedBody = request.body.toByteArray().toString(Charsets.UTF_8)
            respond(
                content = """{"access_token":"ya29.test","expires_in":3600,"token_type":"Bearer"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val auth = GcsAuth(key, HttpClient(engine))

        val token = auth.token()

        assertEquals("ya29.test", token)
        assertEquals("https://oauth2.googleapis.com/token", capturedUrl)
        val body = assertNotNull(capturedBody)
        assertTrue("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer" in body, "got: $body")
        assertTrue("assertion=" in body)
    }

    @Test
    fun cachedTokenReusedWithinRefreshWindow() = runTest {
        val (key, _) = fixtureKey()
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(
                content = """{"access_token":"ya29.call$calls","expires_in":3600,"token_type":"Bearer"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val auth = GcsAuth(key, HttpClient(engine))

        val first = auth.token()
        val second = auth.token()
        val third = auth.token()

        assertEquals(first, second)
        assertEquals(first, third)
        assertEquals(1, calls)
    }
}
