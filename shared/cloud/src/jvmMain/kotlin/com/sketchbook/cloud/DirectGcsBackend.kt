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
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPath
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Google Cloud Storage backend over the JSON API. Conditional writes use
 * `x-goog-if-generation-match`; reads use `?alt=media`. v1 single-PUT only — resumable upload
 * lands in PR-9 alongside the snapshot pipeline that needs it.
 *
 * **Tenant prefix:** every key is prefixed with `<userId.value>/` per design §3.2 so v1.2
 * multi-user can share a bucket.
 */
class DirectGcsBackend(
    private val http: HttpClient,
    private val auth: GcsAuth,
    private val bucket: String,
    private val userId: UserId = UserId.DEFAULT,
    private val json: Json = ManifestJson,
) : CloudBackend {

    override suspend fun headBlob(hash: BlobHash, scope: BlobScope): Boolean {
        val path = blobPath(hash, scope)
        val response = http.head(objectUrl(path)) { authHeader() }
        return when (response.status) {
            HttpStatusCode.OK -> true
            HttpStatusCode.NotFound -> false
            else -> throw remoteFailure(response, "HEAD $path")
        }
    }

    override suspend fun putBlob(hash: BlobHash, source: RawSource, size: Long, scope: BlobScope) {
        val bytes = source.buffered().readAllBytes()
        require(bytes.size.toLong() == size) {
            "size mismatch: declared=$size, actual=${bytes.size}"
        }
        val path = blobPath(hash, scope)
        val response = http.post(uploadUrl()) {
            authHeader()
            parameter("uploadType", "media")
            parameter("name", path)
            // x-goog-if-generation-match: 0 → "must not exist". Idempotent: an existing
            // identical blob returns 412 which we treat as success (content-addressed).
            headers { append("x-goog-if-generation-match", "0") }
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }
        when (response.status) {
            HttpStatusCode.OK -> Unit
            HttpStatusCode.PreconditionFailed -> Unit // already present
            else -> throw remoteFailure(response, "PUT $path")
        }
    }

    override suspend fun getBlob(hash: BlobHash, scope: BlobScope): RawSource {
        val path = blobPath(hash, scope)
        val response = http.get(objectUrl(path)) {
            authHeader()
            parameter("alt", "media")
        }
        if (!response.status.isSuccess) throw remoteFailure(response, "GET $path")
        return ByteArrayRawSource(response.bodyAsBytes())
    }

    override suspend fun readManifest(uuid: ProjectUuid, rev: SnapshotRev): Manifest {
        // Mainline manifests are listed; the on-disk path includes timestamp+host so we have to
        // resolve via list. Most callers invoke this only after listManifests; PR-9 callers use
        // the ref directly.
        val refs = listManifests(uuid, sinceRev = SnapshotRev(rev.value - 1))
        val target = refs.firstOrNull { it.rev == rev.value }
            ?: throw SketchbookError.NotFound("no manifest at uuid=$uuid rev=${rev.value}")
        val response = http.get(objectUrl(target.path)) {
            authHeader()
            parameter("alt", "media")
        }
        if (!response.status.isSuccess) throw remoteFailure(response, "GET ${target.path}")
        return json.decodeFromString(Manifest.serializer(), response.bodyAsText())
    }

    override suspend fun listManifests(uuid: ProjectUuid, sinceRev: SnapshotRev?): List<ManifestRef> {
        val prefix = "${userId.value}/manifests/${uuid.value}/"
        val response = http.get(listUrl()) {
            authHeader()
            parameter("prefix", prefix)
        }
        if (!response.status.isSuccess) throw remoteFailure(response, "LIST $prefix")
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val items = body["items"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { it.toManifestRef(prefix) }
            .filter { sinceRev == null || it.rev > sinceRev.value }
            .sortedBy { it.rev }
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
            val resp = http.post(uploadUrl()) {
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

        // HEAD pointer — CAS via expectedHead.
        val resp = http.post(uploadUrl()) {
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
                val gen = resp.headers["x-goog-generation"]
                    ?: return Result.failure(SketchbookError.IntegrityError("missing x-goog-generation on HEAD write"))
                Result.success(Generation(gen))
            }
            HttpStatusCode.PreconditionFailed ->
                Result.failure(SketchbookError.Conflict("HEAD generation mismatch on uuid=$uuid"))
            else -> Result.failure(remoteFailure(resp, "PUT $headPath"))
        }
    }

    override suspend fun acquireLock(uuid: ProjectUuid, lock: LeaseLock): LeaseAcquireResult {
        val path = lockPath(uuid)
        val body = json.encodeToString(LeaseLock.serializer(), lock).toByteArray(Charsets.UTF_8)
        val resp = http.post(uploadUrl()) {
            authHeader()
            parameter("uploadType", "media")
            parameter("name", path)
            headers { append("x-goog-if-generation-match", "0") }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return when (resp.status) {
            HttpStatusCode.OK -> {
                val gen = resp.headers["x-goog-generation"]
                    ?: throw SketchbookError.IntegrityError("missing x-goog-generation on lock write")
                LeaseAcquireResult.Acquired(Generation(gen))
            }
            HttpStatusCode.PreconditionFailed -> {
                val existing = http.get(objectUrl(path)) {
                    authHeader()
                    parameter("alt", "media")
                }
                val existingLock = json.decodeFromString(LeaseLock.serializer(), existing.bodyAsText())
                val gen = existing.headers["x-goog-generation"]!!
                LeaseAcquireResult.Held(existingLock, Generation(gen))
            }
            else -> throw remoteFailure(resp, "PUT $path")
        }
    }

    override suspend fun refreshLock(
        uuid: ProjectUuid,
        lock: LeaseLock,
        expected: Generation,
    ): LeaseRefreshResult {
        val path = lockPath(uuid)
        val body = json.encodeToString(LeaseLock.serializer(), lock).toByteArray(Charsets.UTF_8)
        val resp = http.post(uploadUrl()) {
            authHeader()
            parameter("uploadType", "media")
            parameter("name", path)
            headers { append("x-goog-if-generation-match", expected.raw) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return when (resp.status) {
            HttpStatusCode.OK -> {
                val gen = resp.headers["x-goog-generation"]
                    ?: throw SketchbookError.IntegrityError("missing x-goog-generation on lock refresh")
                LeaseRefreshResult.Refreshed(Generation(gen))
            }
            HttpStatusCode.PreconditionFailed -> LeaseRefreshResult.Stale
            else -> throw remoteFailure(resp, "REFRESH $path")
        }
    }

    override suspend fun releaseLock(uuid: ProjectUuid, expected: Generation) {
        val path = lockPath(uuid)
        val resp = http.delete(objectUrl(path)) {
            authHeader()
            headers { append("x-goog-if-generation-match", expected.raw) }
        }
        if (resp.status != HttpStatusCode.NoContent && resp.status != HttpStatusCode.NotFound) {
            throw remoteFailure(resp, "DELETE $path")
        }
    }

    // ---- internals ----

    private suspend fun HttpRequestBuilder.authHeader() {
        bearerAuth(auth.token())
    }

    private fun objectUrl(path: String): String =
        "https://storage.googleapis.com/storage/v1/b/$bucket/o/${path.encodeURLPath()}"

    private fun uploadUrl(): String =
        "https://storage.googleapis.com/upload/storage/v1/b/$bucket/o"

    private fun listUrl(): String =
        "https://storage.googleapis.com/storage/v1/b/$bucket/o"

    private fun blobPath(hash: BlobHash, scope: BlobScope): String {
        val shard = hash.hex.substring(0, 2)
        return when (scope) {
            BlobScope.Shared -> "${userId.value}/blobs/$shard/${hash.value}"
            is BlobScope.Private -> "${userId.value}/blobs-private/${scope.uuid.value}/$shard/${hash.value}"
        }
    }

    private fun manifestPath(uuid: ProjectUuid, rev: Long, timestamp: String, host: String): String =
        "${userId.value}/manifests/${uuid.value}/${rev.toString().padStart(8, '0')}-${timestamp.replace(':', '-')}-$host.json"

    private fun headPath(uuid: ProjectUuid): String =
        "${userId.value}/manifests/${uuid.value}/HEAD"

    private fun lockPath(uuid: ProjectUuid): String =
        "${userId.value}/locks/${uuid.value}.lock"

    private fun JsonElement.toManifestRef(prefix: String): ManifestRef? {
        val obj = this.jsonObject
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val gen = obj["generation"]?.jsonPrimitive?.contentOrNull ?: return null
        val rel = name.removePrefix(prefix)
        // Filename shape: `<rev:08d>-<timestamp>-<host>.json` or `HEAD`.
        if (rel == "HEAD") return null
        val rev = rel.substringBefore('-').toLongOrNull() ?: return null
        return ManifestRef(rev = rev, path = name, generation = Generation(gen))
    }

    private suspend fun remoteFailure(resp: HttpResponse, op: String): SketchbookError.RemoteFailure {
        val bodySnippet = runCatching { resp.bodyAsText() }.getOrNull()
        return SketchbookError.RemoteFailure(
            status = resp.status.value,
            body = bodySnippet,
            message = "$op failed: ${resp.status}",
        )
    }
}

internal val ManifestJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    prettyPrint = false
}

private val HttpStatusCode.isSuccess: Boolean get() = value in 200..299

private fun Source.readAllBytes(): ByteArray = readByteArray()

private class ByteArrayRawSource(private val bytes: ByteArray) : RawSource {
    private var consumed = false
    override fun readAtMostTo(sink: kotlinx.io.Buffer, byteCount: Long): Long {
        if (consumed) return -1
        sink.write(bytes)
        consumed = true
        return bytes.size.toLong()
    }
    override fun close() {}
}
