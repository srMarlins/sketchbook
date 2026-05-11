package com.sketchbook.auth.firebase

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Thin Ktor wrapper around Firebase Cloud Functions HTTPS-callable endpoints. The full
 * callable protocol is small enough to call directly via REST — we don't depend on
 * gitlive's Firebase Functions SDK (which on JVM is partially stubbed similar to Auth).
 *
 * **Endpoint shape:** every callable function lives at
 *   `https://<region>-<projectId>.cloudfunctions.net/<functionName>`
 * Default region is `us-central1` (matches the Firebase project's Cloud Storage region —
 * see runbooks/firebase-deploy.md).
 *
 * **Wire format** ([callable spec](https://firebase.google.com/docs/functions/callable-reference)):
 *   POST → `{ "data": { ... } }` body, `Authorization: Bearer <Firebase ID token>` header.
 *   200 OK → `{ "result": { ... } }` body.
 *
 * Phase 3 ships one callable: [revokeMySession]. Future callables (per-host kill switch,
 * server-side abuse hooks) plug into the same class.
 */
class CloudFunctionsClient(
    private val httpClient: HttpClient,
    private val projectId: String,
    private val region: String = "us-central1",
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
        },
) {
    /**
     * Call `revokeMySession` — invalidates every Firebase refresh token issued to the
     * caller's UID. Security-commitment #3 Part B; the client side complement to Phase 2's
     * local keyring + in-memory clear. Caller must pass their current Firebase ID token;
     * the function reads `request.auth.uid` from it (never from the request body), so
     * there's no UID escalation surface.
     *
     * Best-effort: the function may return an HttpsError on transient failure (timeout,
     * already-signed-out auth, region outage). [signOut] callers should treat all failure
     * modes the same — log + continue with local state clearing.
     *
     * Returns the parsed result body on success; throws on HTTP failure.
     */
    suspend fun revokeMySession(idToken: String): RevokeMySessionResult {
        val url = "https://$region-$projectId.cloudfunctions.net/revokeMySession"
        val response =
            httpClient.post(url) {
                bearerAuth(idToken)
                contentType(ContentType.Application.Json)
                setBody("""{"data":{}}""")
                // Sign-out is a single round-trip; tighten budgets so a slow region or
                // hung function doesn't drag the user-visible sign-out flow.
                timeout {
                    connectTimeoutMillis = 2_000
                    requestTimeoutMillis = 5_000
                }
            }
        if (response.status.value !in 200..299) {
            throw CloudFunctionException(
                statusCode = response.status.value,
                rawBody = runCatching { response.bodyAsText() }.getOrNull(),
            )
        }
        val envelope = json.decodeFromString(CallableEnvelope.serializer(), response.bodyAsText())
        return envelope.result
    }

    @Serializable
    private data class CallableEnvelope(
        @SerialName("result") val result: RevokeMySessionResult,
    )
}

@Serializable
data class RevokeMySessionResult(
    @SerialName("revoked") val revoked: Boolean,
    @SerialName("uid") val uid: String,
)

class CloudFunctionException(
    val statusCode: Int,
    val rawBody: String?,
) : RuntimeException("callable function failed: HTTP $statusCode${rawBody?.let { " body=${it.take(200)}" } ?: ""}")
