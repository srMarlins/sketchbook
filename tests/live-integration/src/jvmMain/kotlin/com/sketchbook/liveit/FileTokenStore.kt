package com.sketchbook.liveit

import com.sketchbook.auth.TokenStore
import com.sketchbook.auth.TokenStoreException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * File-backed [TokenStore] for live-integration tests. Persists the refresh token to a JSON
 * file under `~/.sketchbook-test/auth.json` (default; configurable via [path]).
 *
 * **Why a file, not the OS keyring like prod?** Prod uses [com.sketchbook.auth.KeyringTokenStore]
 * so the token survives across app reinstalls and integrates with the OS credential UI.
 * For local integration tests we want the token to be (a) trivially nukable (rm a file),
 * (b) visible to a sweep script for diagnostics, and (c) shippable across two-laptop setups
 * by hand-carrying the file. The keyring's value-add doesn't apply.
 *
 * **POSIX permissions.** When the file is on a POSIX filesystem we tighten to `0600` so a
 * shared-machine test box doesn't leak the refresh token to other users. On non-POSIX
 * filesystems (Windows native, FAT thumb drives, …) we silently skip the chmod — the token
 * is in the user's own home directory and gets default ACLs.
 */
class FileTokenStore(
    private val path: Path = LiveTestEnv.tokenCachePath,
) : TokenStore {
    @Serializable
    private data class Stored(
        val refreshToken: String,
    )

    private val json = Json { prettyPrint = false }

    override suspend fun read(): String? =
        withContext(Dispatchers.IO) {
            if (!Files.exists(path)) return@withContext null
            runCatching {
                val raw = Files.readString(path).trim()
                if (raw.isEmpty()) null else json.decodeFromString(Stored.serializer(), raw).refreshToken
            }.getOrNull()
        }

    override suspend fun write(refreshToken: String) =
        withContext(Dispatchers.IO) {
            try {
                Files.createDirectories(path.parent)
                val payload = json.encodeToString(Stored.serializer(), Stored(refreshToken))
                Files.writeString(
                    path,
                    payload,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
                runCatching {
                    val perms =
                        setOf(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                        )
                    Files.setPosixFilePermissions(path, perms)
                    // Tighten the parent directory too — `~/.sketchbook-test/` is ours alone.
                    val dirPerms =
                        PosixFilePermissions.fromString("rwx------")
                    Files.setPosixFilePermissions(path.parent, dirPerms)
                }
                Unit
            } catch (t: Throwable) {
                throw TokenStoreException("FileTokenStore.write failed at $path", t)
            }
        }

    override suspend fun clear() =
        withContext(Dispatchers.IO) {
            runCatching { Files.deleteIfExists(path) }
            Unit
        }
}
