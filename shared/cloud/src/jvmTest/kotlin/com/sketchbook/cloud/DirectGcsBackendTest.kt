package com.sketchbook.cloud

import com.sketchbook.core.BlobHash
import com.sketchbook.core.Manifest
import com.sketchbook.core.ManifestFile
import com.sketchbook.core.ManifestStats
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.UserId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.io.Buffer
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class DirectGcsBackendTest {

    private fun makeBackend(handle: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData): DirectGcsBackend {
        // Stub auth so tests don't generate real RSA keys for every assertion.
        val key = ServiceAccountKey(
            type = "service_account",
            projectId = "sk-test",
            privateKeyId = "kid",
            privateKeyPem = newRsaPem(),
            clientEmail = "sa@sk-test.iam.gserviceaccount.com",
        )
        val tokenEngine = MockEngine {
            respond(
                """{"access_token":"ya29.fake","expires_in":3600,"token_type":"Bearer"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val auth = GcsAuth(key, HttpClient(tokenEngine))

        val backendEngine = MockEngine { request -> handle(request) }
        return DirectGcsBackend(
            http = HttpClient(backendEngine),
            auth = auth,
            bucket = "sk-bucket",
            userId = UserId("default"),
        )
    }

    @Test
    fun headBlobReturnsTrueOn200FalseOn404() = runTest {
        val hash = BlobHash("b3:" + "a".repeat(64))
        val backend = makeBackend { request ->
            assertEquals(HttpMethod.Head, request.method)
            assertTrue(request.url.encodedPath.contains("/b/sk-bucket/o/"))
            // Hash hex starts with "aa..." → shard prefix "aa". Full hash + tenant prefix
            // appear in the encoded path; we don't assert the exact encoding (Ktor's
            // encodeURLPath leaves `:` unencoded in some versions).
            assertTrue("default" in request.url.encodedPath)
            assertTrue("blobs" in request.url.encodedPath)
            assertTrue("aa" in request.url.encodedPath)
            assertTrue("a".repeat(64) in request.url.encodedPath)
            assertEquals("Bearer ya29.fake", request.headers[HttpHeaders.Authorization])
            respond("", HttpStatusCode.OK)
        }
        assertEquals(true, backend.headBlob(hash))

        val backend2 = makeBackend { _ -> respond("", HttpStatusCode.NotFound) }
        assertEquals(false, backend2.headBlob(hash))
    }

    @Test
    fun putBlobSendsContentAndIfMatchZero() = runTest {
        val hash = BlobHash("b3:" + "b".repeat(64))
        val payload = "hello".toByteArray()

        var seen: HttpRequestData? = null
        val backend = makeBackend { request ->
            seen = request
            respond("", HttpStatusCode.OK)
        }
        backend.putBlob(hash, byteArrayRawSource(payload), payload.size.toLong())

        val req = checkNotNull(seen)
        assertEquals(HttpMethod.Post, req.method)
        assertTrue(req.url.toString().startsWith("https://storage.googleapis.com/upload/storage/v1/b/sk-bucket/o"))
        assertEquals("media", req.url.parameters["uploadType"])
        assertEquals("default/blobs/bb/b3:${"b".repeat(64)}", req.url.parameters["name"])
        assertEquals("0", req.headers["x-goog-if-generation-match"])
        assertEquals("Bearer ya29.fake", req.headers[HttpHeaders.Authorization])
        assertEquals(payload.toList(), req.body.toByteArray().toList())
    }

    @Test
    fun putBlobTreats412AsAlreadyPresent() = runTest {
        val hash = BlobHash("b3:" + "c".repeat(64))
        val backend = makeBackend { _ -> respond("", HttpStatusCode.PreconditionFailed) }
        // Should not throw — content-addressed put is idempotent against an existing object.
        backend.putBlob(hash, byteArrayRawSource(byteArrayOf(1, 2, 3)), 3)
    }

    @Test
    fun appendManifestHeadCASMismatchReturnsConflict() = runTest {
        val manifest = manifestFixture()
        val backend = makeBackend { request ->
            // First call: timestamped manifest write — return 200.
            // Second call: HEAD CAS write — return 412.
            val isHeadWrite = request.url.parameters["name"]?.endsWith("/HEAD") == true
            if (isHeadWrite) {
                respond("", HttpStatusCode.PreconditionFailed)
            } else {
                respond("", HttpStatusCode.OK)
            }
        }
        val result = backend.appendManifestHead(
            uuid = ProjectUuid(manifest.projectUuid.value),
            expectedHead = Generation("42"),
            manifest = manifest,
        )
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(err is com.sketchbook.core.SketchbookError.Conflict, "expected Conflict, got $err")
    }

    @Test
    fun appendManifestHeadHappyPathReturnsNewGeneration() = runTest {
        val manifest = manifestFixture()
        val backend = makeBackend { request ->
            val isHead = request.url.parameters["name"]?.endsWith("/HEAD") == true
            respond(
                "",
                HttpStatusCode.OK,
                headersOf("x-goog-generation", "100"),
            )
        }
        val result = backend.appendManifestHead(
            uuid = ProjectUuid(manifest.projectUuid.value),
            expectedHead = Generation.ZERO,
            manifest = manifest,
        )
        assertEquals(Generation("100"), result.getOrNull())
    }

    @Test
    fun acquireLockHandlesHeldCase() = runTest {
        val uuid = ProjectUuid("01HZQX5N3M8F9G2K7B1A6Y4WCE")
        val existing = LeaseLock(
            ownerHostId = "macstudio",
            ownerHostName = "MacStudio",
            acquiredAt = Instant.parse("2026-05-05T12:00:00Z"),
            expiresAt = Instant.parse("2026-05-05T12:05:00Z"),
        )
        var call = 0
        val backend = makeBackend { request ->
            call++
            when (call) {
                1 -> respond("", HttpStatusCode.PreconditionFailed) // initial CAS write
                2 -> respond(
                    kotlinx.serialization.json.Json.encodeToString(LeaseLock.serializer(), existing),
                    HttpStatusCode.OK,
                    headersOf("x-goog-generation", "55"),
                )
                else -> fail("unexpected call $call")
            }
        }
        val result = backend.acquireLock(uuid, existing.copy(ownerHostId = "windowspc"))
        assertTrue(result is LeaseAcquireResult.Held)
        assertEquals(Generation("55"), result.generation)
        assertEquals("macstudio", result.held.ownerHostId)
    }

    private fun manifestFixture(): Manifest = Manifest(
        version = 1,
        projectUuid = ProjectUuid("01HZQX5N3M8F9G2K7B1A6Y4WCE"),
        rev = SnapshotRev(1),
        parentRev = null,
        timestamp = Instant.parse("2026-05-05T12:00:00Z"),
        hostId = "macstudio",
        hostName = "MacStudio",
        kind = SnapshotKind.Auto,
        files = mapOf(
            "Project.als" to ManifestFile(
                hash = BlobHash("b3:" + "1".repeat(64)),
                size = 1024,
                mtime = Instant.parse("2026-05-05T11:59:00Z"),
            ),
        ),
        stats = ManifestStats(fileCount = 1, totalBytes = 1024, newBytes = 1024),
    )

    private fun newRsaPem(): String {
        val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val pair = gen.generateKeyPair()
        return "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
    }

    private fun byteArrayRawSource(bytes: ByteArray): kotlinx.io.RawSource = object : kotlinx.io.RawSource {
        private var consumed = false
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            if (consumed) return -1
            sink.write(bytes)
            consumed = true
            return bytes.size.toLong()
        }
        override fun close() {}
    }
}
