package com.sketchbook.cloud

import com.sketchbook.core.BlobHash
import com.sketchbook.core.Manifest
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SketchbookError
import com.sketchbook.core.SnapshotRev
import com.sketchbook.core.UserId
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.writeFully
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readAtMostTo
import kotlinx.io.readByteArray
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * [CloudBackend] over Firebase Storage REST (`firebasestorage.googleapis.com/v0/b/{bucket}/o`).
 * Small blobs (≤ [RESUMABLE_THRESHOLD]) use a single POST (`uploadType=media`); larger blobs
 * use the GOOG-protocol resumable-upload path (X-Goog-Upload-* headers). Reads use `?alt=media`.
 *
 * **CAS note:** `x-goog-if-generation-match` headers are sent on writes for GCS-compat, but
 * Firebase Storage REST ignores them. Concurrent-write safety for manifests relies on the
 * Firestore lock at `/users/{uid}/locks/{treeId}` (see [com.sketchbook.cloud.metadata.MetadataStore]).
 *
 * **Auth:** [credentials] returns a Firebase ID token (issued by Identity Toolkit; default 1h
 * TTL). Firebase Storage REST API accepts Firebase ID tokens as bearer credentials. The GCS
 * JSON API (`storage.googleapis.com`) does NOT — it requires OAuth2 access tokens.
 *
 * **Bucket:** the bucket name is the Firebase project's auto-provisioned Cloud Storage bucket
 * (e.g. `sketchbook-jtf-2026.firebasestorage.app`), supplied by DI from
 * [com.sketchbook.auth.firebase.FirebaseConfig.storageBucket]. Users no longer pick a bucket
 * — the per-user namespace is the `users/{uid}/` prefix below, enforced by Storage Security
 * Rules. See `storage.rules`.
 *
 * **Tenant prefix:** every key is prefixed with `users/<userId.value>/` so Security Rules
 * `match /users/{uid}/{allPaths=**}` and (later) v1.2 collaborator-aware reads resolve
 * cleanly. `userId` is the Firebase Auth UID (`request.auth.uid`).
 */
class FirebaseBlobStore(
    private val http: HttpClient,
    private val credentials: CloudCredentials,
    private val bucket: String,
    private val userId: UserId,
    private val json: Json = ManifestJson,
) : CloudBackend {
    private val tenantPrefix = "users/${userId.value}"

    override suspend fun headBlob(
        hash: BlobHash,
        scope: BlobScope,
    ): Boolean {
        val path = blobPath(hash, scope)
        // Firebase Storage REST API doesn't support HEAD. GET without ?alt=media returns
        // metadata JSON (or 404); we check the status and discard the body.
        val response = http.get(objectUrl(path)) { authHeader() }
        return when (response.status) {
            HttpStatusCode.OK -> {
                response.bodyAsBytes() // discard metadata JSON
                true
            }
            HttpStatusCode.NotFound -> {
                response.bodyAsBytes()
                false
            }
            else -> throw remoteFailure(response, "GET-meta $path")
        }
    }

    override suspend fun putBlob(
        hash: BlobHash,
        source: RawSource,
        size: Long,
        scope: BlobScope,
    ) {
        val path = blobPath(hash, scope)
        if (size <= RESUMABLE_THRESHOLD) {
            putBlobSingle(path, source, size)
        } else {
            putBlobResumable(path, source, size)
        }
    }

    /**
     * v1 single-PUT for small blobs. Already streams via [StreamingSourceContent]; the JVM only
     * ever holds [STREAM_CHUNK_BYTES] at a time and the SocketChannel pulls bytes as fast as it
     * can write to the wire. Suitable up to a few MB; larger blobs use [putBlobResumable] which
     * can recover from network blips and isn't subject to GCS's single-request size cap.
     */
    private suspend fun putBlobSingle(
        path: String,
        source: RawSource,
        size: Long,
    ) {
        val response =
            http.post(uploadUrl()) {
                authHeader()
                parameter("uploadType", "media")
                parameter("name", path)
                // Firebase Storage ignores x-goog-if-generation-match; sent for GCS-compat only.
                headers { append("x-goog-if-generation-match", "0") }
                contentType(ContentType.Application.OctetStream)
                setBody(StreamingSourceContent(source, size))
            }
        when (response.status) {
            HttpStatusCode.OK -> Unit
            HttpStatusCode.PreconditionFailed -> Unit // already present — content-addressed
            else -> throw remoteFailure(response, "PUT $path")
        }
    }

    /**
     * GCS resumable upload session for blobs above [RESUMABLE_THRESHOLD]. Three phases:
     *
     *   1. **Stage** — drain the [source] to a temp file once. The source is consumed exactly
     *      once (most callers pass us a stream-once Source from the local FS), and the temp
     *      file gives us a seekable backing store for chunk PUTs.
     *   2. **Initiate** — POST to `?uploadType=resumable` with the precondition header. The
     *      server returns a session URL in the `Location` header.
     *   3. **Chunks** — PUT [RESUMABLE_CHUNK_BYTES]-sized slices of the temp file with
     *      `Content-Range: bytes <start>-<end>/<total>`. Non-final chunks return 308 Resume
     *      Incomplete; the final chunk returns 200/201.
     *
     * On any failure we propagate; future work can add retry-with-resume where we re-issue the
     * failing chunk after querying the server for the current upload offset (PUT with empty
     * body + `Content-Range: bytes <asterisk-slash><total>`). Not in v1 because the desktop is
     * single-process and the user can simply retry the snapshot.
     */
    private suspend fun putBlobResumable(
        path: String,
        source: RawSource,
        size: Long,
    ) {
        val tmp = stageSourceToTempFile(source)
        try {
            val sessionUrl =
                initiateResumableSession(path, size)
                    ?: return // precondition already satisfied (412 on init)
            var offset = 0L
            while (offset < size) {
                val chunkEnd = minOf(offset + RESUMABLE_CHUNK_BYTES, size) - 1
                val complete = uploadResumableChunk(sessionUrl, tmp, offset, chunkEnd, size)
                offset = chunkEnd + 1
                if (complete) break
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    /**
     * POST the resumable upload session-init request. Returns the session URL on success, or
     * `null` if the precondition rejected the upload (412 — a content-addressable blob that
     * already exists). Throws on any other failure.
     *
     * Firebase Storage uses the GOOG upload protocol (X-Goog-Upload-* headers), distinct from
     * GCS JSON API's X-Upload-Content-* headers. The session URL is returned in the
     * X-Goog-Upload-URL response header.
     */
    private suspend fun initiateResumableSession(
        path: String,
        size: Long,
    ): String? {
        val response =
            http.post(uploadUrl()) {
                authHeader()
                // Firebase Storage uses the X-Goog-Upload-Protocol header to select resumable
                // mode — NOT the uploadType=resumable query param (which is GCS JSON API only).
                parameter("name", path)
                headers {
                    append("x-goog-if-generation-match", "0")
                    append("X-Goog-Upload-Protocol", "resumable")
                    append("X-Goog-Upload-Command", "start")
                    append("X-Goog-Upload-Header-Content-Type", "application/octet-stream")
                    append("X-Goog-Upload-Header-Content-Length", size.toString())
                }
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
        return when (response.status) {
            HttpStatusCode.OK -> {
                // Firebase Storage returns the session URL in X-Goog-Upload-URL;
                // fall back to Location for GCS-compat responses.
                response.headers["X-Goog-Upload-URL"]
                    ?: response.headers[HttpHeaders.Location]
                    ?: throw SketchbookError.IntegrityError("missing upload URL on resumable session init for $path")
            }

            HttpStatusCode.PreconditionFailed -> {
                null
            }

            // already present, content-addressed → noop
            else -> {
                throw remoteFailure(response, "POST resumable-init $path")
            }
        }
    }

    /**
     * PUT one chunk of the temp-file-backed body to the resumable [sessionUrl]. Returns true if
     * this was the final chunk (server responded 200/201), false if the upload should continue
     * (308 Resume Incomplete).
     *
     * Firebase Storage GOOG protocol uses X-Goog-Upload-Offset + X-Goog-Upload-Command
     * instead of the GCS Content-Range header. The last chunk must carry "upload, finalize".
     */
    private suspend fun uploadResumableChunk(
        sessionUrl: String,
        file: Path,
        start: Long,
        endInclusive: Long,
        total: Long,
    ): Boolean {
        val isLast = endInclusive == total - 1
        val length = endInclusive - start + 1
        val response =
            http.put(sessionUrl) {
                headers {
                    append("X-Goog-Upload-Offset", start.toString())
                    append("X-Goog-Upload-Command", if (isLast) "upload, finalize" else "upload")
                }
                contentType(ContentType.Application.OctetStream)
                setBody(FileRangeContent(file, start, length))
            }
        return when (response.status.value) {
            200, 201 -> true

            308 -> false

            412 -> true

            // already exists — content-addressed
            else -> throw remoteFailure(response, "PUT chunk $start-$endInclusive of $total")
        }
    }

    /**
     * Drain [source] into a temp file. Used by both the resumable-upload path and the GET path
     * to keep blob bytes off the heap. The file is the caller's responsibility to delete.
     */
    private fun stageSourceToTempFile(source: RawSource): Path {
        val tmp = Files.createTempFile("sketchbook-upload-", ".bin")
        try {
            Files.newOutputStream(tmp, StandardOpenOption.WRITE).use { out ->
                val buffered = source.buffered()
                val chunk = ByteArray(STREAM_CHUNK_BYTES)
                while (true) {
                    val read = buffered.readAtMostTo(chunk, 0, chunk.size)
                    if (read <= 0) break
                    out.write(chunk, 0, read)
                }
            }
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw t
        } finally {
            source.close()
        }
        return tmp
    }

    override suspend fun getBlob(
        hash: BlobHash,
        scope: BlobScope,
    ): RawSource {
        val path = blobPath(hash, scope)
        val response =
            http.get(objectUrl(path)) {
                authHeader()
                parameter("alt", "media")
            }
        if (!response.status.isSuccess) throw remoteFailure(response, "GET $path")
        // Stream the response body into a temp file, then hand the caller a RawSource backed by
        // that file. Avoids materializing potentially-hundreds-of-MB of blob into the heap and
        // lets the materializer hardlink/copy from a real on-disk file. The file is unlinked
        // when the source is closed (POSIX) — on Windows the JDK keeps the handle valid for
        // reading even after the unlink, so this is safe across both.
        return drainToTempFile(response.bodyAsChannel(), prefix = "sketchbook-blob-")
    }

    override suspend fun readManifest(ref: ManifestRef): Manifest {
        val response =
            http.get(objectUrl(ref.path)) {
                authHeader()
                parameter("alt", "media")
            }
        if (!response.status.isSuccess) throw remoteFailure(response, "GET ${ref.path}")
        return json.decodeFromString(Manifest.serializer(), response.bodyAsText())
    }

    override suspend fun readManifest(
        uuid: ProjectUuid,
        rev: SnapshotRev,
    ): Manifest {
        // Legacy overload. The on-disk path encodes timestamp+host so we resolve via list — O(N).
        // Prefer the `(ref)` overload when the caller has the ref already.
        val refs = listManifests(uuid, sinceRev = SnapshotRev(rev.value - 1))
        val target =
            refs.firstOrNull { it.rev == rev.value }
                ?: throw SketchbookError.NotFound("no manifest at uuid=$uuid rev=${rev.value}")
        return readManifest(target)
    }

    override suspend fun listManifests(
        uuid: ProjectUuid,
        sinceRev: SnapshotRev?,
    ): List<ManifestRef> {
        val prefix = "$tenantPrefix/manifests/${uuid.value}/"
        // Server-side filter: manifest filenames are `<rev:08d>-...` so a `startOffset` past
        // the zero-padded sinceRev cuts the wire traffic instead of fetching-then-filtering.
        val startOffset =
            sinceRev?.let { prefix + (it.value + 1).toString().padStart(8, '0') + "-" }
        val names = mutableListOf<String>()
        var pageToken: String? = null
        var pages = 0
        // Pages-per-call ceiling acts as a circuit breaker — 50 × 1000 manifest cap is
        // multiple orders of magnitude past any realistic project's history.
        do {
            val response =
                http.get(listUrl()) {
                    authHeader()
                    parameter("prefix", prefix)
                    if (startOffset != null) parameter("startOffset", startOffset)
                    parameter("maxResults", "1000")
                    if (pageToken != null) parameter("pageToken", pageToken)
                }
            if (!response.status.isSuccess) throw remoteFailure(response, "LIST $prefix")
            val page = json.decodeFromString(GcsListPage.serializer(), response.bodyAsText())
            for (item in page.items) {
                val rel = item.name.removePrefix(prefix)
                if (rel == "HEAD") continue
                if (rel.substringBefore('-').toLongOrNull() == null) continue
                names += item.name
            }
            pageToken = page.nextPageToken
            pages++
        } while (pageToken != null && pages < LIST_MAX_PAGES)

        // Firebase Storage list responses only include `name` and `bucket` per item — no
        // `generation`. Fetch per-item metadata (single GET per object) to populate it.
        // Note: Firebase Storage ignores x-goog-if-generation-match, so generation does not
        // enforce CAS here; concurrent-write safety comes from the Firestore lock instead.
        val out = mutableListOf<ManifestRef>()
        for (name in names) {
            val rel = name.removePrefix(prefix)
            val rev = rel.substringBefore('-').toLongOrNull() ?: continue
            val meta = fetchObjectMetadata(name) ?: continue
            out += ManifestRef(rev = rev, path = name, generation = Generation(meta.generation))
        }
        return out.sortedBy { it.rev }
    }

    // Firebase Storage list endpoint returns only name + bucket per item (no generation).
    @Serializable
    private data class GcsListPage(
        val items: List<GcsListItem> = emptyList(),
        val nextPageToken: String? = null,
    )

    // Minimal list-response item — only `name` is guaranteed by Firebase Storage REST API.
    @Serializable
    private data class GcsListItem(val name: String)

    // Full metadata response from single-object GET (includes generation, size, etc.).
    @Serializable
    private data class GcsObject(
        val name: String,
        val generation: String,
        val size: String? = null,
    )

    private suspend fun fetchObjectMetadata(path: String): GcsObject? {
        val response = http.get(objectUrl(path)) { authHeader() }
        return when (response.status) {
            HttpStatusCode.OK -> json.decodeFromString(GcsObject.serializer(), response.bodyAsText())
            HttpStatusCode.NotFound -> { response.bodyAsBytes(); null }
            else -> throw remoteFailure(response, "GET-meta $path")
        }
    }

    override suspend fun appendManifestHead(
        uuid: ProjectUuid,
        expectedHead: Generation?,
        manifest: Manifest,
    ): Result<Generation> {
        // Two writes: the timestamped manifest (idempotent, content-addressable filename) and
        // the HEAD pointer (CAS). v1 PR-6 stores the HEAD as the manifest itself at a stable
        // path; the JSON file is overwritten on each new rev. PR-9 may add a separate atomic
        // pointer file.
        val timestamped = manifestPath(uuid, manifest.rev.value, manifest.timestamp.toString(), manifest.hostId)
        val headPath = headPath(uuid)
        val body = json.encodeToString(Manifest.serializer(), manifest).toByteArray(Charsets.UTF_8)

        // Timestamped object — must not exist (rev is unique).
        run {
            val resp =
                http.post(uploadUrl()) {
                    authHeader()
                    parameter("uploadType", "media")
                    parameter("name", timestamped)
                    headers { append("x-goog-if-generation-match", "0") }
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            if (resp.status != HttpStatusCode.OK && resp.status != HttpStatusCode.PreconditionFailed) {
                return Result.failure(remoteFailure(resp, "PUT $timestamped"))
            }
        }

        // HEAD pointer write. x-goog-if-generation-match is sent for GCS-compat but Firebase
        // Storage ignores it — actual write ordering is serialized by the Firestore lock.
        val resp =
            http.post(uploadUrl()) {
                authHeader()
                parameter("uploadType", "media")
                parameter("name", headPath)
                if (expectedHead != null) {
                    headers { append("x-goog-if-generation-match", expectedHead.raw) }
                }
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        return when (resp.status) {
            HttpStatusCode.OK -> {
                // Firebase Storage returns the generation in the JSON response body, not in a
                // header. Parse it from the `generation` field in the upload metadata response.
                val gen =
                    resp.headers["x-goog-generation"]
                        ?: runCatching {
                            json.decodeFromString(GcsObject.serializer(), resp.bodyAsText()).generation
                        }.getOrNull()
                        ?: return Result.failure(SketchbookError.IntegrityError("missing generation on HEAD write"))
                Result.success(Generation(gen))
            }

            HttpStatusCode.PreconditionFailed -> {
                Result.failure(SketchbookError.Conflict("HEAD generation mismatch on uuid=$uuid"))
            }

            else -> {
                Result.failure(remoteFailure(resp, "PUT $headPath"))
            }
        }
    }

    // Lock methods deleted in Phase 3 — leases now live as Firestore docs at
    // /users/{uid}/locks/{treeId} via MetadataStore. The pre-Phase-3
    // <user>/locks/<uuid>.lock GCS object path is dead.

    // ---- internals ----

    private suspend fun HttpRequestBuilder.authHeader() {
        // Firebase Storage REST API uses `Authorization: Firebase {id-token}`.
        // bearerAuth() sends `Authorization: Bearer {token}` — Firebase Storage accepts both.
        bearerAuth(credentials.token())
    }

    private fun objectUrl(path: String): String {
        // Firebase Storage REST API expects the full object name (including slashes) encoded
        // as a SINGLE path segment — slashes must be %2F, colons %3A, etc. Ktor's
        // encodeURLPath() preserves slashes as path separators, which breaks the /o/{object}
        // format. java.net.URLEncoder gives RFC-3986-compatible percent-encoding.
        val encoded = java.net.URLEncoder.encode(path, "UTF-8")
        return "https://firebasestorage.googleapis.com/v0/b/$bucket/o/$encoded"
    }

    private fun uploadUrl(): String = "https://firebasestorage.googleapis.com/v0/b/$bucket/o"

    private fun listUrl(): String = uploadUrl()

    private fun blobPath(
        hash: BlobHash,
        scope: BlobScope,
    ): String {
        val shard = hash.hex.substring(0, 2)
        return when (scope) {
            BlobScope.Shared -> "$tenantPrefix/blobs/$shard/${hash.value}"
            is BlobScope.Private -> "$tenantPrefix/blobs-private/${scope.uuid.value}/$shard/${hash.value}"
        }
    }

    private fun manifestPath(
        uuid: ProjectUuid,
        rev: Long,
        timestamp: String,
        host: String,
    ): String = "$tenantPrefix/manifests/${uuid.value}/${rev.toString().padStart(8, '0')}-${timestamp.replace(':', '-')}-$host.json"

    private fun headPath(uuid: ProjectUuid): String = "$tenantPrefix/manifests/${uuid.value}/HEAD"

    private suspend fun remoteFailure(
        resp: HttpResponse,
        op: String,
    ): SketchbookError.RemoteFailure {
        val bodySnippet = runCatching { resp.bodyAsText() }.getOrNull()
        return SketchbookError.RemoteFailure(
            status = resp.status.value,
            body = bodySnippet,
            message = "$op failed: ${resp.status}",
        )
    }
}

internal val ManifestJson =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }

private val HttpStatusCode.isSuccess: Boolean get() = value in 200..299

/**
 * Pagination circuit-breaker for [FirebaseBlobStore.listManifests]. 50 pages × 1000 manifests
 * per page = 50k objects; well past any plausible project history. A higher ceiling would let
 * a misconfigured rules-allow + a long-tail prefix DoS the catch-up path.
 */
private const val LIST_MAX_PAGES = 50

/** 64 KB chunks — large enough to keep the wire saturated, small enough to keep heap bounded. */
private const val STREAM_CHUNK_BYTES = 64 * 1024

/**
 * Switch to Firebase Storage GOOG-protocol resumable upload above this size. 8 MB keeps
 * single-PUT under the Firebase Storage per-request limit while letting the resumable path
 * handle anything that could plausibly stall on a consumer connection.
 */
private const val RESUMABLE_THRESHOLD: Long = 8L * 1024 * 1024

/**
 * Per-chunk size for resumable uploads. GCS requires non-final chunks to be a multiple of
 * 256 KB; 8 MB is a common sweet spot — large enough to amortize per-request overhead across
 * the wire, small enough that a transient connection failure forfeits at most 8 MB of progress.
 */
private const val RESUMABLE_CHUNK_BYTES: Long = 8L * 1024 * 1024

/**
 * Ktor `OutgoingContent` that streams a kotlinx.io [RawSource] into the request body without
 * ever materializing it in memory. `contentLength` is set so the precondition path on the
 * server (and any chunked-transfer fallback) sees the declared size up front.
 */
private class StreamingSourceContent(
    private val source: RawSource,
    override val contentLength: Long,
) : OutgoingContent.WriteChannelContent() {
    override val contentType: ContentType = ContentType.Application.OctetStream

    override suspend fun writeTo(channel: ByteWriteChannel) {
        val chunk = ByteArray(STREAM_CHUNK_BYTES)
        var totalSent = 0L
        try {
            val buffered = source.buffered()
            while (true) {
                val read = buffered.readAtMostTo(chunk, 0, chunk.size)
                if (read == -1) break
                channel.writeFully(chunk, 0, read)
                totalSent += read
            }
            channel.flushAndClose()
        } finally {
            source.close()
        }
        require(totalSent == contentLength) {
            "size mismatch: declared=$contentLength, actually streamed=$totalSent"
        }
    }
}

/**
 * Streams [length] bytes starting at [offset] of [file] into a Ktor request body. Used by the
 * resumable-upload path so each chunk PUT reads its slice straight off disk in 64 KB
 * sub-chunks instead of holding the chunk in memory.
 */
private class FileRangeContent(
    private val file: Path,
    private val offset: Long,
    override val contentLength: Long,
) : OutgoingContent.WriteChannelContent() {
    override val contentType: ContentType = ContentType.Application.OctetStream

    override suspend fun writeTo(channel: ByteWriteChannel) {
        val length = contentLength
        Files.newByteChannel(file, StandardOpenOption.READ).use { fch ->
            fch.position(offset)
            val buffer = java.nio.ByteBuffer.allocate(STREAM_CHUNK_BYTES)
            var remaining = length
            while (remaining > 0) {
                buffer.clear()
                if (buffer.limit() > remaining) buffer.limit(remaining.toInt())
                val read = fch.read(buffer)
                if (read <= 0) break
                buffer.flip()
                val arr = ByteArray(read)
                buffer.get(arr)
                channel.writeFully(arr, 0, read)
                remaining -= read
            }
            require(remaining == 0L) { "short read on $file: wrote ${length - remaining} of $length bytes" }
        }
        channel.flushAndClose()
    }
}

/**
 * Drain a Ktor [ByteReadChannel] into a temp file, returning a [RawSource] that streams from
 * the file and deletes it on close. Used by `getBlob` so a 543 MB download doesn't sit on the
 * heap waiting for the caller to consume it.
 */
private suspend fun drainToTempFile(
    channel: ByteReadChannel,
    prefix: String,
): RawSource {
    val tmp = Files.createTempFile(prefix, ".bin")
    try {
        // Ktor's JVM `toInputStream` adapter (in ktor-io-jvm) gives us a blocking
        // InputStream view over the channel. Streaming chunk-by-chunk into the temp file
        // means a 543 MB blob never lands on the heap as a single allocation.
        channel.toInputStream().use { input ->
            Files.newOutputStream(tmp, StandardOpenOption.WRITE).use { out ->
                val chunk = ByteArray(STREAM_CHUNK_BYTES)
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    out.write(chunk, 0, read)
                }
            }
        }
    } catch (t: Throwable) {
        runCatching { Files.deleteIfExists(tmp) }
        throw t
    }
    return TempFileRawSource(tmp)
}

private class TempFileRawSource(
    private val path: Path,
) : RawSource {
    private val stream = Files.newInputStream(path, StandardOpenOption.READ)
    private val chunk = ByteArray(STREAM_CHUNK_BYTES)
    private var closed = false

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (closed) return -1
        val target = minOf(byteCount, chunk.size.toLong()).toInt()
        val read = stream.read(chunk, 0, target)
        if (read == -1) return -1L
        sink.write(chunk, 0, read)
        return read.toLong()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { stream.close() }
        runCatching { Files.deleteIfExists(path) }
    }
}
