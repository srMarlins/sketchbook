package com.sketchbook.auth

/**
 * Persists the refresh token. Backed by the OS keychain on jvmMain
 * (Keychain / Credential Manager / libsecret via javakeyring).
 *
 * Reads return `null` if no token is present or the keystore is unavailable. Implementations
 * MUST NOT throw on read — keystore unavailability is an expected state (first launch, locked
 * keychain on Linux) and the caller treats it as `SignedOut`.
 *
 * Writes throw [TokenStoreException] on failure so a sign-in can't silently succeed without
 * persisting the refresh token.
 */
interface TokenStore {
    suspend fun read(): String?
    suspend fun write(refreshToken: String)
    suspend fun clear()
}

class TokenStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
