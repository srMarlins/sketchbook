package com.sketchbook.cloud

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Service-account JWT signer + access-token cache. RS256 (SHA256withRSA) using the JDK's
 * built-in `java.security.Signature` — no Bouncy Castle.
 *
 * Tokens are exchanged with `https://oauth2.googleapis.com/token` (or [ServiceAccountKey.tokenUri]
 * if overridden) and cached for 50 minutes (proactive refresh ahead of the 1-hour expiry).
 */
class GcsAuth(
    private val key: ServiceAccountKey,
    private val httpClient: HttpClient,
    private val clock: Clock = Clock.System,
    private val scope: String = "https://www.googleapis.com/auth/devstorage.read_write",
) {

    private val mutex = Mutex()
    private var cached: CachedToken? = null

    private val privateKey: PrivateKey by lazy { decodePkcs8PrivateKey(key.privateKeyPem) }

    /** Sign a fresh JWT for the configured [scope]. Returns the compact `header.claims.sig` form. */
    fun signJwt(now: Instant = clock.now(), audience: String = key.tokenUri): String {
        val nowSec = now.epochSeconds
        val expSec = nowSec + 3600
        val header = """{"alg":"RS256","typ":"JWT","kid":"${escape(key.privateKeyId)}"}"""
        val claims = """{"iss":"${escape(key.clientEmail)}","scope":"${escape(scope)}","aud":"${escape(audience)}","exp":$expSec,"iat":$nowSec}"""

        val signingInput = "${b64url(header.toByteArray(Charsets.UTF_8))}.${b64url(claims.toByteArray(Charsets.UTF_8))}"
        val signature = sign(signingInput.toByteArray(Charsets.UTF_8))
        return "$signingInput.${b64url(signature)}"
    }

    /**
     * Exchange a signed JWT for a 1-hour OAuth access token. Single-shot: callers usually go
     * through [token] which caches.
     */
    suspend fun exchangeJwtForAccessToken(jwt: String = signJwt()): AccessToken {
        val response = httpClient.submitForm(
            url = key.tokenUri,
            formParameters = Parameters.build {
                append("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                append("assertion", jwt)
            },
        )
        if (!response.status.isSuccess()) {
            throw GcsAuthException("token exchange failed: ${response.status} ${response.bodyAsText()}")
        }
        val body = response.bodyAsText()
        val parsed = Json.decodeFromString(TokenExchangeResponse.serializer(), body)
        val now = clock.now()
        return AccessToken(value = parsed.accessToken, expiresAt = now.plus(parsed.expiresIn.seconds))
    }

    /** Returns a current bearer token, refreshing if within the 50-minute proactive window. */
    suspend fun token(): String = mutex.withLock {
        val current = cached
        if (current != null && clock.now() < current.refreshAt) {
            current.token.value
        } else {
            val fresh = exchangeJwtForAccessToken()
            cached = CachedToken(token = fresh, refreshAt = clock.now().plus(50.minutes))
            fresh.value
        }
    }

    private fun sign(input: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(input)
        return sig.sign()
    }

    private data class CachedToken(val token: AccessToken, val refreshAt: Instant)

    @Serializable
    private data class TokenExchangeResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long,
        @SerialName("token_type") val tokenType: String = "Bearer",
    )
}

data class AccessToken(val value: String, val expiresAt: Instant)

class GcsAuthException(message: String) : RuntimeException(message)

private val b64UrlEncoder = Base64.getUrlEncoder().withoutPadding()
private val b64Decoder = Base64.getDecoder()

internal fun b64url(bytes: ByteArray): String = b64UrlEncoder.encodeToString(bytes)

internal fun decodePkcs8PrivateKey(pem: String): PrivateKey {
    val body = pem
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replace("-----BEGIN RSA PRIVATE KEY-----", "")
        .replace("-----END RSA PRIVATE KEY-----", "")
        .replace("\\s".toRegex(), "")
    val keyBytes = b64Decoder.decode(body)
    val spec = PKCS8EncodedKeySpec(keyBytes)
    return KeyFactory.getInstance("RSA").generatePrivate(spec)
}

private fun escape(s: String): String = s
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
