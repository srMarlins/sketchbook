package com.sketchbook.auth.firebase

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdentityToolkitClientTest {
    @Test
    fun `signInWithGoogleIdToken sends correct request body and parses response`() =
        runTest {
            var capturedUrl: String? = null
            var capturedBody: String? = null

            val mockEngine =
                MockEngine { request ->
                    capturedUrl = request.url.toString()
                    capturedBody = (request.body as io.ktor.http.content.TextContent).text
                    respond(
                        content =
                            """
                            {
                              "idToken": "FAKE_FIREBASE_ID_TOKEN",
                              "refreshToken": "FAKE_REFRESH_TOKEN",
                              "localId": "FAKE_UID",
                              "expiresIn": "3600",
                              "email": "user@example.com"
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val httpClient = HttpClient(mockEngine)
            val client = IdentityToolkitClient(httpClient, webApiKey = "TEST_API_KEY")

            val result = client.signInWithGoogleIdToken("FAKE_GOOGLE_ID_TOKEN")

            assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")
            val tokens = result.getOrThrow()
            assertEquals("FAKE_FIREBASE_ID_TOKEN", tokens.idToken)
            assertEquals("FAKE_REFRESH_TOKEN", tokens.refreshToken)
            assertEquals("FAKE_UID", tokens.uid)
            assertEquals("user@example.com", tokens.email)

            val url = requireNotNull(capturedUrl)
            val body = requireNotNull(capturedBody)
            assertTrue(
                url.contains("identitytoolkit.googleapis.com/v1/accounts:signInWithIdp"),
                "expected signInWithIdp endpoint, got: $url",
            )
            assertTrue(url.contains("key=TEST_API_KEY"), "expected api key in URL")
            assertTrue(body.contains("id_token=FAKE_GOOGLE_ID_TOKEN"), "expected google ID token in postBody: $body")
            assertTrue(body.contains("providerId=google.com"), "expected providerId=google.com")
            assertTrue(body.contains("\"returnSecureToken\":true"), "expected returnSecureToken")
        }

    @Test
    fun `signInWithGoogleIdToken surfaces Identity Toolkit error envelope as failure`() =
        runTest {
            val mockEngine =
                MockEngine { _ ->
                    respond(
                        content =
                            """
                            {
                              "error": {
                                "code": 400,
                                "message": "INVALID_ID_TOKEN"
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = IdentityToolkitClient(HttpClient(mockEngine), webApiKey = "TEST_API_KEY")

            val result = client.signInWithGoogleIdToken("INVALID_TOKEN")

            assertTrue(result.isFailure, "expected failure on 400")
            val message = result.exceptionOrNull()?.message ?: ""
            assertTrue(message.contains("INVALID_ID_TOKEN"), "expected error message to surface code, got: $message")
        }

    @Test
    fun `refresh sends form-encoded body and parses securetoken response`() =
        runTest {
            var capturedBody: String? = null
            var capturedContentType: String? = null

            val mockEngine =
                MockEngine { request ->
                    // F5: refresh now uses ktor's submitForm → FormDataContent. Read the
                    // bytes back through the OutgoingContent's writer-channel.
                    capturedBody =
                        (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                            .bytes()
                            .toString(Charsets.UTF_8)
                    capturedContentType = request.body.contentType?.toString()
                    respond(
                        content =
                            """
                            {
                              "id_token": "FRESH_ID_TOKEN",
                              "refresh_token": "ROTATED_REFRESH_TOKEN",
                              "user_id": "FAKE_UID",
                              "expires_in": "3600"
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = IdentityToolkitClient(HttpClient(mockEngine), webApiKey = "TEST_API_KEY")

            val result = client.refresh("OLD_REFRESH_TOKEN")

            assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")
            val tokens = result.getOrThrow()
            assertEquals("FRESH_ID_TOKEN", tokens.idToken)
            assertEquals("ROTATED_REFRESH_TOKEN", tokens.refreshToken)
            assertEquals("FAKE_UID", tokens.uid)

            assertTrue(capturedContentType?.contains("application/x-www-form-urlencoded") == true)
            assertEquals("grant_type=refresh_token&refresh_token=OLD_REFRESH_TOKEN", capturedBody)
        }
}
