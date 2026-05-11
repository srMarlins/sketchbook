package com.sketchbook.auth.firebase

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Identity Toolkit REST client. Two endpoints:
 *
 *   1. POST `identitytoolkit.googleapis.com/v1/accounts:signInWithIdp`
 *      Trades a verified Google ID token for a Firebase ID + refresh token. Identity Toolkit
 *      verifies the Google ID token's signature server-side; we ALSO verify it client-side
 *      first (security-commitment #1) so a tampered token never crosses the wire.
 *
 *   2. POST `securetoken.googleapis.com/v1/token`
 *      Exchanges a refresh token for a fresh ID token. Called when the cached ID token nears
 *      expiry (~60s of slack means the next request never fires with a just-expired token).
 *
 * Both endpoints take the public Firebase Web API key as a `?key=` query param — see
 * [FirebaseConfig.webApiKey].
 *
 * Also exposes [revoke], which clears the refresh token grant Google holds for our OAuth
 * client (security-commitment #3). Note that revoke hits Google's OAuth endpoint, not
 * Identity Toolkit's — the Firebase refresh token IS the Google refresh token under the
 * covers for Google-sign-in identities.
 */
class IdentityToolkitClient(
    private val httpClient: HttpClient,
    private val webApiKey: String,
    private val clock: Clock = Clock.System,
    private val identityToolkitBase: String = "https://identitytoolkit.googleapis.com",
    private val secureTokenBase: String = "https://securetoken.googleapis.com",
) {
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

    suspend fun refresh(refreshToken: String): Result<FirebaseTokens> =
        runRequest {
            // submitForm URL-encodes the form parameters, which matters for refresh tokens
            // that contain characters outside the unreserved set (`+`, `/`, etc). The previous
            // string-concat path silently double-encoded `%`-prefixed tokens and refused the
            // refresh (N2).
            val response: HttpResponse =
                httpClient.submitForm(
                    url = "$secureTokenBase/v1/token",
                    formParameters =
                        parameters {
                            append("grant_type", "refresh_token")
                            append("refresh_token", refreshToken)
                        },
                ) {
                    parameter("key", webApiKey)
                }
            val payload = decodeOrThrow(response, RefreshTokenResponse.serializer())
            FirebaseTokens(
                idToken = payload.idToken,
                refreshToken = payload.refreshToken,
                uid = payload.userId,
                expiresAt = clock.now() + payload.expiresIn.toLong().seconds,
                email = null,
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
        serializer: KSerializer<T>,
    ): T {
        val text = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            val parsed =
                runCatching {
                    JSON.decodeFromString(IdentityToolkitErrorEnvelope.serializer(), text).error
                }.getOrNull()
            throw IdentityToolkitException(
                statusCode = response.status.value,
                errorCode = parsed?.message,
                rawBody = text,
            )
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
        @SerialName("expiresIn") val expiresIn: String,
        @SerialName("email") val email: String? = null,
    )

    @Serializable
    private data class RefreshTokenResponse(
        @SerialName("id_token") val idToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("user_id") val userId: String,
        @SerialName("expires_in") val expiresIn: String,
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

/**
 * Typed failure from [IdentityToolkitClient]. Callers can branch on [errorCode] to decide
 * recovery (e.g. `INVALID_REFRESH_TOKEN` → force re-auth; `TOO_MANY_ATTEMPTS_TRY_LATER`
 * → backoff). [rawBody] is preserved for logging when the wire envelope doesn't match the
 * expected `{error: {code, message}}` shape.
 *
 * See <https://cloud.google.com/identity-platform/docs/error-codes> for Identity Toolkit's
 * documented error-code vocabulary.
 */
class IdentityToolkitException(
    val statusCode: Int,
    val errorCode: String?,
    val rawBody: String,
) : RuntimeException(
        buildString {
            append("Identity Toolkit ")
            append(statusCode)
            if (errorCode != null) {
                append(": ")
                append(errorCode)
            } else {
                append(": ")
                append(rawBody.take(MAX_BODY_PREVIEW))
            }
        },
    ) {
    private companion object {
        const val MAX_BODY_PREVIEW = 200
    }
}
