package com.sketchbook.auth

import com.sketchbook.core.UserId
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration.Companion.minutes

/**
 * Desktop OAuth via loopback redirect with PKCE. Spins up an ephemeral HTTP server on
 * 127.0.0.1:<random>, opens the system browser to the auth URL, captures the code, exchanges
 * it for tokens, and shuts the server down.
 *
 * RFC 8252 permits public (desktop) clients to omit the client secret; PKCE is the proof of
 * possession. Google's token endpoint, however, requires `client_secret` even for installed-app
 * clients — it's labelled "not secret" in the Cloud Console because it ships in the binary, but
 * the endpoint rejects the exchange without it. Pass [clientSecret] from the Cloud Console
 * credential; leave null only in unit tests that mock the token endpoint.
 */
class OAuthClient(
    private val httpClient: HttpClient,
    private val clientId: String,
    private val clientSecret: String? = null,
    private val authUri: String = "https://accounts.google.com/o/oauth2/v2/auth",
    private val tokenUri: String = "https://oauth2.googleapis.com/token",
    private val scopes: List<String>,
    private val callbackTimeout: kotlin.time.Duration = 5.minutes,
    /** Test seam — production uses [java.awt.Desktop.browse]. */
    private val browserOpener: (String) -> Unit = ::openInSystemBrowser,
) : OAuthFlow {
    override suspend fun signIn(): Result<OAuthTokens> =
        runCatching {
            val verifier = randomVerifier()
            val challenge = sha256B64Url(verifier)
            val state = randomState()
            val deferredCode = CompletableDeferred<Result<String>>()
            val server = startLoopback(expectedState = state, deferredCode = deferredCode)
            try {
                val redirectUri = "http://127.0.0.1:${server.address.port}/callback"
                val authUrl =
                    buildAuthUrl(
                        redirectUri = redirectUri,
                        state = state,
                        challenge = challenge,
                    )
                browserOpener(authUrl)
                val codeResult =
                    withTimeoutOrNull(callbackTimeout) { deferredCode.await() }
                        ?: throw OAuthTimeout()
                val code = codeResult.getOrElse { throw it }
                exchangeCodeForTokens(code = code, verifier = verifier, redirectUri = redirectUri)
            } finally {
                server.stop(0)
            }
        }

    @Suppress("FunctionSignature", "FunctionExpressionBody", "MaxLineLength", "ArgumentListWrapping", "ParameterListWrapping")
    private suspend fun exchangeCodeForTokens(
        code: String,
        verifier: String,
        redirectUri: String,
    ): OAuthTokens =
        withContext(Dispatchers.IO) {
            val response =
                httpClient.submitForm(
                    url = tokenUri,
                    formParameters =
                        Parameters.build {
                            append("grant_type", "authorization_code")
                            append("code", code)
                            append("client_id", clientId)
                            if (clientSecret != null) append("client_secret", clientSecret)
                            append("code_verifier", verifier)
                            append("redirect_uri", redirectUri)
                        },
                )
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw OAuthFailed("token exchange failed: ${response.status} $body")
            }
            val parsed = JSON.decodeFromString(TokenResponse.serializer(), body)
            val rawIdToken = parsed.idToken ?: throw OAuthFailed("no id_token in response — did you request the openid scope?")
            val (sub, email) = parseSubAndEmail(rawIdToken)
            OAuthTokens(
                accessToken = parsed.accessToken,
                refreshToken =
                    parsed.refreshToken
                        ?: throw OAuthFailed("no refresh_token in token response — did you set access_type=offline?"),
                idToken = rawIdToken,
                expiresInSeconds = parsed.expiresIn,
                userId = UserId(sub),
                email = email,
            )
        }

    private fun startLoopback(
        expectedState: String,
        deferredCode: CompletableDeferred<Result<String>>,
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/callback") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            val params = parseQuery(query)
            val state = params["state"]
            val code = params["code"]
            val error = params["error"]
            val responseHtml =
                if (error != null) {
                    "<html><body><h1>Sign-in failed</h1><p>${escapeHtml(error)}</p></body></html>"
                } else {
                    "<html><body><h1>Signed in to Sketchbook</h1><p>You can close this window.</p></body></html>"
                }
            val bytes = responseHtml.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(HTTP_OK, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            when {
                error == "access_denied" -> deferredCode.complete(Result.failure(OAuthCancelled()))
                error != null -> deferredCode.complete(Result.failure(OAuthFailed("OAuth error: $error")))
                state != expectedState -> deferredCode.complete(Result.failure(OAuthFailed("state mismatch")))
                code == null -> deferredCode.complete(Result.failure(OAuthFailed("missing code")))
                else -> deferredCode.complete(Result.success(code))
            }
        }
        server.start()
        return server
    }

    private fun buildAuthUrl(
        redirectUri: String,
        state: String,
        challenge: String,
    ): String {
        val params =
            mapOf(
                "client_id" to clientId,
                "redirect_uri" to redirectUri,
                "response_type" to "code",
                "scope" to scopes.joinToString(" "),
                "state" to state,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
                "access_type" to "offline",
                "prompt" to "consent",
            )
        val query =
            params.entries.joinToString("&") { (k, v) ->
                "$k=${URLEncoder.encode(v, "UTF-8")}"
            }
        return "$authUri?$query"
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("id_token") val idToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long = 3600,
        @SerialName("token_type") val tokenType: String = "Bearer",
    )

    private companion object {
        const val HTTP_OK = 200
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    /**
     * Raw Google-issued ID token (JWT, RS256). Forwarded to Identity Toolkit's
     * `signInWithIdp` by [com.sketchbook.auth.firebase.FirebaseAuthSession] after JWKS
     * verification (required for the Identity Toolkit exchange — see security-commitment #1).
     */
    val idToken: String,
    val expiresInSeconds: Long,
    /**
     * **Unverified** until [com.sketchbook.auth.firebase.FirebaseAuthSession.signIn] runs
     * Google JWT verification and the Identity Toolkit exchange. Treat as advisory display
     * data only until that point.
     */
    val userId: UserId,
    val email: String,
)

/** Decoupling seam so tests don't need to spin up a loopback HTTP server. */
interface OAuthFlow {
    suspend fun signIn(): Result<OAuthTokens>
}

private fun openInSystemBrowser(url: String) {
    val desktop = java.awt.Desktop.getDesktop()
    desktop.browse(java.net.URI(url))
}

private fun randomVerifier(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun sha256B64Url(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

private fun randomState(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun parseQuery(raw: String): Map<String, String> =
    raw
        .split("&")
        .filter { it.isNotEmpty() }
        .mapNotNull {
            val idx = it.indexOf('=')
            if (idx < 0) null else it.substring(0, idx) to java.net.URLDecoder.decode(it.substring(idx + 1), "UTF-8")
        }.toMap()

private fun escapeHtml(s: String): String =
    s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

private fun parseSubAndEmail(idToken: String?): Pair<String, String> {
    val token = idToken ?: failOAuth("no id_token in response")
    val parts = token.split(".")
    if (parts.size < 2) failOAuth("malformed id_token")
    val payloadJson = String(Base64.getUrlDecoder().decode(parts[1].padBase64()))
    val obj = Json.parseToJsonElement(payloadJson).jsonObject
    val sub = obj["sub"]?.jsonPrimitive?.content ?: failOAuth("id_token missing sub")
    val email = obj["email"]?.jsonPrimitive?.content ?: failOAuth("id_token missing email — request the email scope")
    return sub to email
}

private fun failOAuth(message: String): Nothing = throw OAuthFailed(message)

private const val BASE64_BLOCK = 4

private fun String.padBase64(): String {
    val rem = length % BASE64_BLOCK
    return if (rem == 0) this else this + "=".repeat(BASE64_BLOCK - rem)
}
