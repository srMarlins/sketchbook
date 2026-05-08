package com.sketchbook.cloud

import com.sketchbook.core.BlobHash
import com.sketchbook.core.Manifest
import com.sketchbook.core.ManifestFile
import com.sketchbook.core.ManifestStats
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.TrackedTreeId
import com.sketchbook.core.TrackedTreeKind
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
import kotlinx.io.Buffer
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Instant

class DirectGcsBackendTest {
    private fun makeBackend(
        handle: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): DirectGcsBackend {
        // Stub auth so tests don't generate real RSA keys for every assertion.
        val key =
            ServiceAccountKey(
                type = "service_account",
                projectId = "sk-test",
                privateKeyId = "kid",
                privateKeyPem = newRsaPem(),
                clientEmail = "sa@sk-test.iam.gserviceaccount.com",
            )
        val tokenEngine =
            MockEngine {
                respond(
                    """{"access_token":"ya29.fake","expires_in":3600,"token_type":"Bearer"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        val credentials: CloudCredentials = GcsAuth(key, HttpClient(tokenEngine))

        val backendEngine = MockEngine { request -> handle(request) }
        return DirectGcsBackend(
            http = HttpClient(backendEngine),
            credentials = credentials,
            bucket = "sk-bucket",
            userId = UserId("test"),
        )
    }

    @Test
    fun headBlobReturnsTrueOn200FalseOn404() =
        runTest {
            val hash = BlobHash("b3:" + "a".repeat(64))
            val backend =
                makeBackend { request ->
                    assertEquals(HttpMethod.Head, request.method)
                    assertTrue(request.url.encodedPath.contains("/b/sk-bucket/o/"))
                    // Hash hex starts with "aa..." → shard prefix "aa". Full hash + tenant prefix
                    // appear in the encoded path; we don't assert the exact encoding (Ktor's
                    // encodeURLPath leaves `:` unencoded in some versions).
                    assertTrue("users" in request.url.encodedPath)
                    assertTrue("test" in request.url.encodedPath)
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
    fun putBlobSendsContentAndIfMatchZero() =
        runTest {
            val hash = BlobHash("b3:" + "b".repeat(64))
            val payload = "hello".toByteArray()

            var seen: HttpRequestData? = null
            val backend =
                makeBackend { request ->
                    seen = request
                    respond("", HttpStatusCode.OK)
                }
            backend.putBlob(hash, byteArrayRawSource(payload), payload.size.toLong())

            val req = checkNotNull(seen)
            assertEquals(HttpMethod.Post, req.method)
            assertTrue(req.url.toString().startsWith("https://storage.googleapis.com/upload/storage/v1/b/sk-bucket/o"))
            assertEquals("media", req.url.parameters["uploadType"])
            assertEquals("users/test/blobs/bb/b3:${"b".repeat(64)}", req.url.parameters["name"])
            assertEquals("0", req.headers["x-goog-if-generation-match"])
            assertEquals("Bearer ya29.fake", req.headers[HttpHeaders.Authorization])
            assertEquals(payload.toList(), req.body.toByteArray().toList())
        }

    @Test
    fun putBlobTreats412AsAlreadyPresent() =
        runTest {
            val hash = BlobHash("b3:" + "c".repeat(64))
            val backend = makeBackend { _ -> respond("", HttpStatusCode.PreconditionFailed) }
            // Should not throw — content-addressed put is idempotent against an existing object.
            backend.putBlob(hash, byteArrayRawSource(byteArrayOf(1, 2, 3)), 3)
        }

    @Test
    fun appendManifestHeadCASMismatchReturnsConflict() =
        runTest {
            val manifest = manifestFixture()
            val backend =
                makeBackend { request ->
                    val isHeadWrite = request.url.parameters["name"]?.endsWith("/HEAD") == true
                    if (isHeadWrite) {
                        respond("", HttpStatusCode.PreconditionFailed)
                    } else {
                        respond("", HttpStatusCode.OK)
                    }
                }
            val result =
                backend.appendManifestHead(
                    treeId = manifest.treeId,
                    kind = TrackedTreeKind.Project,
                    expectedHead = Generation("42"),
                    manifest = manifest,
                )
            assertTrue(result.isFailure)
            val err = result.exceptionOrNull()
            assertTrue(err is com.sketchbook.core.SketchbookError.Conflict, "expected Conflict, got $err")
        }

    /**
     * Regression: HEAD CAS conflict must clean up the timestamped body this writer just put
     * down, so two racing writers don't leave both bodies in `manifests/` listing at the
     * same rev. Body-first ordering: timestamped goes first (so a HEAD CAS that succeeds is
     * always backed by a confirmed body); a HEAD CAS-loser then DELETEs its own orphan.
     */
    @Test
    fun appendManifestHeadCASMismatchDeletesOrphanTimestamped() =
        runTest {
            val manifest = manifestFixture()
            val seenWrites = mutableListOf<String>()
            val seenDeletes = mutableListOf<String>()
            val backend =
                makeBackend { request ->
                    when (request.method) {
                        HttpMethod.Post -> {
                            val name = request.url.parameters["name"].orEmpty()
                            seenWrites += name
                            if (name.endsWith("/HEAD")) {
                                respond("", HttpStatusCode.PreconditionFailed)
                            } else {
                                respond("", HttpStatusCode.OK, headersOf("x-goog-generation", "10"))
                            }
                        }

                        HttpMethod.Delete -> {
                            seenDeletes += request.url.encodedPath
                            respond("", HttpStatusCode.NoContent)
                        }

                        else -> {
                            error("unexpected method ${request.method}")
                        }
                    }
                }
            val result =
                backend.appendManifestHead(
                    treeId = manifest.treeId,
                    kind = TrackedTreeKind.Project,
                    expectedHead = Generation("42"),
                    manifest = manifest,
                )
            assertTrue(result.isFailure)
            val err = result.exceptionOrNull()
            assertTrue(err is com.sketchbook.core.SketchbookError.Conflict, "expected Conflict, got $err")
            assertEquals(2, seenWrites.size, "expected timestamped + HEAD writes, saw $seenWrites")
            assertTrue(!seenWrites.first().endsWith("/HEAD"), "expected timestamped first, saw ${seenWrites.first()}")
            assertTrue(seenWrites.last().endsWith("/HEAD"), "expected HEAD second, saw ${seenWrites.last()}")
            assertEquals(1, seenDeletes.size, "expected orphan cleanup DELETE, saw $seenDeletes")
        }

    /**
     * Regression: GCS object-list responses cap at 1000 items per page; the pagination loop
     * must follow `nextPageToken` until the response omits it. Without this, daily-snapshot
     * projects silently truncate at ~2.7 years of history.
     */
    @Test
    fun listManifestsFollowsPageTokenAcrossMultiplePages() =
        runTest {
            val prefix = "users/test/trees/project/proj/manifests"
            val pageOne =
                """
                {
                  "kind": "storage#objects",
                  "nextPageToken": "TOKEN-2",
                  "items": [
                    { "name": "$prefix/00000001-2026-05-05T12-00-00Z-mac.json", "generation": "10" },
                    { "name": "$prefix/00000002-2026-05-05T12-00-01Z-mac.json", "generation": "11" }
                  ]
                }
                """.trimIndent()
            val pageTwo =
                """
                {
                  "kind": "storage#objects",
                  "items": [
                    { "name": "$prefix/00000003-2026-05-05T12-00-02Z-mac.json", "generation": "12" }
                  ]
                }
                """.trimIndent()
            val pageTokensSeen = mutableListOf<String?>()
            val backend =
                makeBackend { request ->
                    pageTokensSeen += request.url.parameters["pageToken"]
                    val token = request.url.parameters["pageToken"]
                    val body =
                        if (token == null) {
                            pageOne
                        } else {
                            assertEquals("TOKEN-2", token)
                            pageTwo
                        }
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val refs =
                backend.listManifests(
                    treeId = TrackedTreeId("proj"),
                    kind = TrackedTreeKind.Project,
                    sinceRev = null,
                )
            assertEquals(listOf(1L, 2L, 3L), refs.map { it.rev })
            assertEquals(listOf<String?>(null, "TOKEN-2"), pageTokensSeen)
        }

    @Test
    fun appendManifestHeadHappyPathReturnsNewGeneration() =
        runTest {
            val manifest = manifestFixture()
            val backend =
                makeBackend { request ->
                    val isHead = request.url.parameters["name"]?.endsWith("/HEAD") == true
                    respond(
                        "",
                        HttpStatusCode.OK,
                        headersOf("x-goog-generation", "100"),
                    )
                }
            val result =
                backend.appendManifestHead(
                    treeId = manifest.treeId,
                    kind = TrackedTreeKind.Project,
                    expectedHead = Generation.ZERO,
                    manifest = manifest,
                )
            assertEquals(Generation("100"), result.getOrNull())
        }

    /**
     * Regression for #127 / R-3: HEAD must be a small pointer (~100 bytes), not a duplicate of
     * the full manifest body. Capture the bytes both writes send and assert the HEAD payload
     * decodes as a [ManifestHeadPointer] referencing the timestamped object — and is much
     * shorter than the full manifest.
     */
    @Test
    fun appendManifestHeadWritesPointerNotFullManifestBody() =
        runTest {
            val manifest = manifestFixture()
            val capturedBodies = mutableMapOf<String, ByteArray>()
            val backend =
                makeBackend { request ->
                    val name = request.url.parameters["name"].orEmpty()
                    val body = request.body.toByteArray()
                    capturedBodies[name] = body
                    respond("", HttpStatusCode.OK, headersOf("x-goog-generation", "10"))
                }
            backend.appendManifestHead(
                treeId = manifest.treeId,
                kind = TrackedTreeKind.Project,
                expectedHead = Generation.ZERO,
                manifest = manifest,
            )

            val headEntry = capturedBodies.entries.single { it.key.endsWith("/HEAD") }
            val timestampedEntry = capturedBodies.entries.single { !it.key.endsWith("/HEAD") }

            // HEAD body must decode as the v=3 pointer.
            val pointer =
                ManifestHeadPointer.decodeOrNull(
                    json =
                        kotlinx.serialization.json.Json {
                            encodeDefaults = true
                            ignoreUnknownKeys = true
                        },
                    bytes = headEntry.value,
                )
            assertTrue(pointer != null, "HEAD body did not decode as pointer: ${headEntry.value.decodeToString()}")
            assertEquals(manifest.rev.value, pointer.rev)
            assertEquals(timestampedEntry.key, pointer.manifestPath)
            // Tiny pointer (a couple hundred bytes max) vs the full manifest blob.
            assertTrue(
                headEntry.value.size < timestampedEntry.value.size,
                "expected HEAD pointer to be smaller than timestamped manifest: " +
                    "${headEntry.value.size} vs ${timestampedEntry.value.size}",
            )
        }

    /**
     * Back-compat: a v=2 HEAD body (the full Manifest doc, no `manifest_path` field) must NOT
     * decode as a [ManifestHeadPointer]. Readers can use this null result to fall back to
     * decoding as Manifest.
     */
    @Test
    fun manifestHeadPointerDecodeOrNullReturnsNullForLegacyV2Manifest() {
        val manifest = manifestFixture()
        val v2HeadBody =
            kotlinx.serialization.json.Json
                .encodeToString(Manifest.serializer(), manifest)
                .toByteArray()
        val pointer =
            ManifestHeadPointer.decodeOrNull(
                json =
                    kotlinx.serialization.json.Json {
                        encodeDefaults = true
                        ignoreUnknownKeys = true
                    },
                bytes = v2HeadBody,
            )
        // The Manifest schema doesn't have a `manifest_path` field; the pointer decoder must
        // recognize this and return null so the caller falls back to Manifest decoding.
        assertEquals(null, pointer)
    }

    /**
     * Version gate: a pointer-shaped body with a `v` other than the current
     * [ManifestHeadPointer.HEAD_POINTER_VERSION] must decode to null. Belt-and-suspenders
     * against future-shape leakage when [ignoreUnknownKeys] is on — the caller can then choose
     * between fallback decoding or surfacing the version mismatch.
     */
    @Test
    fun manifestHeadPointerDecodeOrNullReturnsNullForUnsupportedVersion() {
        val unsupportedPointerJson =
            """{"v":4,"rev":7,"manifest_path":"trees/project/x/manifests/00000007-ts-host.json"}"""
                .toByteArray()
        val pointer =
            ManifestHeadPointer.decodeOrNull(
                json =
                    kotlinx.serialization.json.Json {
                        encodeDefaults = true
                        ignoreUnknownKeys = true
                    },
                bytes = unsupportedPointerJson,
            )
        assertEquals(null, pointer)
    }

    @Test
    fun acquireLockHandlesHeldCase() =
        runTest {
            val uuid = ProjectUuid("01HZQX5N3M8F9G2K7B1A6Y4WCE")
            val existing =
                LeaseLock(
                    ownerHostId = "macstudio",
                    ownerHostName = "MacStudio",
                    acquiredAt = Instant.parse("2026-05-05T12:00:00Z"),
                    expiresAt = Instant.parse("2026-05-05T12:05:00Z"),
                )
            var call = 0
            val backend =
                makeBackend { request ->
                    call++
                    when (call) {
                        1 -> {
                            respond("", HttpStatusCode.PreconditionFailed)
                        }

                        // initial CAS write
                        2 -> {
                            respond(
                                kotlinx.serialization.json.Json
                                    .encodeToString(LeaseLock.serializer(), existing),
                                HttpStatusCode.OK,
                                headersOf("x-goog-generation", "55"),
                            )
                        }

                        else -> {
                            fail("unexpected call $call")
                        }
                    }
                }
            val result = backend.acquireLock(TrackedTreeId(uuid.value), TrackedTreeKind.Project, existing.copy(ownerHostId = "windowspc"))
            assertTrue(result is LeaseAcquireResult.Held)
            assertEquals(Generation("55"), result.generation)
            assertEquals("macstudio", result.held.ownerHostId)
        }

    private fun manifestFixture(): Manifest =
        Manifest(
            treeId = TrackedTreeId("01HZQX5N3M8F9G2K7B1A6Y4WCE"),
            kind = TrackedTreeKind.Project,
            rev = SnapshotRev(1),
            parentRev = null,
            timestamp = Instant.parse("2026-05-05T12:00:00Z"),
            hostId = "macstudio",
            hostName = "MacStudio",
            snapshotKind = SnapshotKind.Auto,
            files =
                mapOf(
                    "Project.als" to
                        ManifestFile(
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

    private fun byteArrayRawSource(bytes: ByteArray): kotlinx.io.RawSource =
        object : kotlinx.io.RawSource {
            private var consumed = false

            override fun readAtMostTo(
                sink: Buffer,
                byteCount: Long,
            ): Long {
                if (consumed) return -1
                sink.write(bytes)
                consumed = true
                return bytes.size.toLong()
            }

            override fun close() {}
        }
}
