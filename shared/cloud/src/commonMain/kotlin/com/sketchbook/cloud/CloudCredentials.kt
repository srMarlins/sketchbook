package com.sketchbook.cloud

/**
 * Provider-agnostic bearer-token source for [FirebaseBlobStore]. v1 has two impls:
 *  - `GcsAuth` (jvmMain) — service-account JWT signer. Used in tests.
 *  - `FirebaseCloudCredentials` (app-desktop) — wraps an `AuthSession` and threads its access
 *    token through. Used in production.
 */
fun interface CloudCredentials {
    suspend fun token(): String
}
