package com.sketchbook.auth

import com.github.javakeyring.BackendNotSupportedException
import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [TokenStore] backed by the OS keychain via javakeyring. Keychain access is blocking I/O on
 * desktop OSes (it can prompt the user on macOS), so every call hops to [Dispatchers.IO].
 *
 * `serviceName` namespaces the entry inside the keychain — production uses
 * `"com.sketchbook.refresh"`. Tests pass a unique name per test so they don't collide on a
 * shared dev workstation.
 */
class KeyringTokenStore(private val serviceName: String, private val accountName: String) : TokenStore {

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val keyring = Keyring.create()
            keyring.getPassword(serviceName, accountName)
        }.getOrNull()
    }

    override suspend fun write(refreshToken: String) {
        withContext(Dispatchers.IO) {
            try {
                Keyring.create().setPassword(serviceName, accountName, refreshToken)
            } catch (e: BackendNotSupportedException) {
                throw TokenStoreException("OS keychain not available", e)
            } catch (e: PasswordAccessException) {
                throw TokenStoreException("keychain write failed: ${e.message}", e)
            }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            runCatching {
                Keyring.create().deletePassword(serviceName, accountName)
            }
        }
    }
}
