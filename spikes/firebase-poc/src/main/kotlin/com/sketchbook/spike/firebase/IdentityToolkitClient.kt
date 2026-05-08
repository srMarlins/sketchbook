/*
 * Identity Toolkit REST client.
 *
 * Two endpoints:
 *
 *   1. POST identitytoolkit.googleapis.com/v1/accounts:signInWithIdp
 *      Trades a verified Google ID token for a Firebase ID token + refresh token.
 *      Identity Toolkit verifies the Google ID token's signature server-side; we ALSO verify
 *      it client-side first (security-commitment #1) to fail fast on tampered tokens.
 *
 *   2. POST securetoken.googleapis.com/v1/token
 *      Exchanges a refresh token for a fresh ID token. Called when the cached ID token nears
 *      expiry (default 1h TTL); ~60s of slack means the next request never fires with a
 *      just-expired token.
 *
 * Both endpoints take the public Firebase Web API key as a `?key=` query param. Not a secret;
 * see FirebaseConfig.WEB_API_KEY.
 */
package com.sketchbook.spike.firebase

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class IdentityToolkitClient(
    private val httpClient: HttpClient,
    private val webApiKey: String = FirebaseConfig.WEB_API_KEY,
    private val clock: Clock = Clock.System,
    private val identityToolkitBase: String = "https://identitytoolkit.googleapis.com",
    private val secureTokenBase: String = "https://securetoken.googleapis.com",
) {
    /**
     * Trade a Google ID token for a Firebase ID + refresh token. The Google ID token must be
     * one issued to *this app's* Google OAuth client (the audience must match), already
     * client-side-verified via [GoogleIdTokenVerifier]. The Identity Toolkit endpoint also
     * verifies the signature on its end — we do it client-side first so we don't ship a
     * tampered token across the network.
     */
    suspend fun signInWithGoogleIdToken(googleIdToken: String): Result<FirebaseTokens> =
        runRequest {
            val response: HttpResponse =
                httpClient.post("$identityToolkitBase/v1/accounts:signInWithIdp") {
                    parameter("key", webApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        JSON.encodeToString(
                            SignInWithIdpRequest.serializer(),
                            SignInWithIdpRequest(
                                postBody = "id_token=$googleIdToken&providerId=google.com",
                                requestUri = "http://localhost",
                                returnSecureToken = true,
                            ),
                        ),
                    )
                }
            val payload = decodeOrThrow(response, SignInWithIdpResponse.serializer())
            FirebaseTokens(
                idToken = payload.idToken,
                refreshToken = payload.refreshToken,
                uid = payload.localId,
                expiresAt = clock.now() + payload.expiresIn.toLong().seconds,
                email = payload.email,
            )
        }

    /**
     * Mint a fresh Firebase ID token from a refresh token. Used on token nearing expiry.
     * The secure-token endpoint speaks form-encoded, not JSON.
     */
    suspend fun refresh(refreshToken: String): Result<FirebaseTokens> =
        runRequest {
            val response: HttpResponse =
                httpClient.post("$secureTokenBase/v1/token") {
                    parameter("key", webApiKey)
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("grant_type=refresh_token&refresh_token=$refreshToken")
                }
            val payload = decodeOrThrow(response, RefreshTokenResponse.serializer())
            FirebaseTokens(
                idToken = payload.idToken,
                refreshToken = payload.refreshToken,
                uid = payload.userId,
                expiresAt = clock.now() + payload.expiresIn.toLong().seconds,
                email = null, // refresh response doesn't include email; caller already has it cached
            )
        }

    private suspend fun <T> runRequest(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Result.failure(t)
        }

    private suspend fun <T> decodeOrThrow(
        response: HttpResponse,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T {
        val text = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            // Identity Toolkit error shape: { "error": { "code": 400, "message": "INVALID_..." } }
            val message = runCatching { JSON.decodeFromString(IdentityToolkitErrorEnvelope.serializer(), text).error.message }.getOrNull()
            error("Identity Toolkit ${response.status.value}: ${message ?: text}")
        }
        return JSON.decodeFromString(serializer, text)
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    private data class SignInWithIdpRequest(
        @SerialName("postBody") val postBody: String,
        @SerialName("requestUri") val requestUri: String,
        @SerialName("returnSecureToken") val returnSecureToken: Boolean,
    )

    @Serializable
    private data class SignInWithIdpResponse(
        @SerialName("idToken") val idToken: String,
        @SerialName("refreshToken") val refreshToken: String,
        @SerialName("localId") val localId: String,
        @SerialName("expiresIn") val expiresIn: String, // string seconds, per Google's wire format
        @SerialName("email") val email: String? = null,
    )

    @Serializable
    private data class RefreshTokenResponse(
        @SerialName("id_token") val idToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("user_id") val userId: String,
        @SerialName("expires_in") val expiresIn: String, // string seconds, per Google
    )

    @Serializable
    private data class IdentityToolkitErrorEnvelope(
        @SerialName("error") val error: IdentityToolkitError,
    )

    @Serializable
    private data class IdentityToolkitError(
        @SerialName("code") val code: Int,
        @SerialName("message") val message: String,
    )
}
