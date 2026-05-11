package com.sketchbook.cloud

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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
) : CloudCredentials {
    private val mutex = Mutex()
    private var cached: CachedToken? = null

    private val privateKey: PrivateKey by lazy { decodePkcs8PrivateKey(key.privateKeyPem) }

    /** Sign a fresh JWT for the configured [scope]. Returns the compact `header.claims.sig` form. */
    fun signJwt(
        now: Instant = clock.now(),
        audience: String = key.tokenUri,
    ): String {
        val nowSec = now.epochSeconds
        val expSec = nowSec + 3600
        // Build header/claims as JsonObjects so kotlinx.serialization handles every escape
        // (control chars, surrogate pairs, non-BMP) instead of the previous hand-rolled escape
        // that only covered \" and \\. SA-file inputs are trusted today, but the JWT spec
        // requires strict JSON and the brittle path is one upstream change away from emitting
        // invalid tokens that the OAuth endpoint silently rejects.
        val header: JsonObject =
            buildJsonObject {
                put("alg", "RS256")
                put("typ", "JWT")
                put("kid", key.privateKeyId)
            }
        val claims: JsonObject =
            buildJsonObject {
                put("iss", key.clientEmail)
                put("scope", scope)
                put("aud", audience)
                put("exp", JsonPrimitive(expSec))
                put("iat", JsonPrimitive(nowSec))
            }
        val headerBytes = compactJson.encodeToString(JsonObject.serializer(), header).toByteArray(Charsets.UTF_8)
        val claimsBytes = compactJson.encodeToString(JsonObject.serializer(), claims).toByteArray(Charsets.UTF_8)
        val signingInput = "${b64url(headerBytes)}.${b64url(claimsBytes)}"
        val signature = sign(signingInput.toByteArray(Charsets.UTF_8))
        return "$signingInput.${b64url(signature)}"
    }

    /**
     * Exchange a signed JWT for a 1-hour OAuth access token. Single-shot: callers usually go
     * through [token] which caches.
     */
    suspend fun exchangeJwtForAccessToken(jwt: String = signJwt()): AccessToken {
        val response =
            httpClient.submitForm(
                url = key.tokenUri,
                formParameters =
                    Parameters.build {
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
    override suspend fun token(): String =
        mutex.withLock {
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

    private data class CachedToken(
        val token: AccessToken,
        val refreshAt: Instant,
    )

    @Serializable
    private data class TokenExchangeResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long,
        @SerialName("token_type") val tokenType: String = "Bearer",
    )
}

data class AccessToken(
    val value: String,
    val expiresAt: Instant,
)

class GcsAuthException(
    message: String,
) : RuntimeException(message)

private val b64UrlEncoder = Base64.getUrlEncoder().withoutPadding()
private val b64Decoder = Base64.getDecoder()

internal fun b64url(bytes: ByteArray): String = b64UrlEncoder.encodeToString(bytes)

internal fun decodePkcs8PrivateKey(pem: String): PrivateKey {
    val body =
        pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
    val keyBytes = b64Decoder.decode(body)
    val spec = PKCS8EncodedKeySpec(keyBytes)
    return KeyFactory.getInstance("RSA").generatePrivate(spec)
}

private val compactJson =
    Json {
        encodeDefaults = false
        prettyPrint = false
    }
