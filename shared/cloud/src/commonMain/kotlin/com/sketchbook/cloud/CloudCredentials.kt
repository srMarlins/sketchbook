package com.sketchbook.cloud

/**
 * Provider-agnostic bearer-token source for [FirebaseBlobStore]. v1 has one production impl:
 *  - `FirebaseCloudCredentials` (app-desktop) — wraps an `AuthSession` and threads the
 *    Firebase ID token through. Production binary ships only this implementation.
 *
 * The previous `GcsAuth` JWT signer (service-account based) moved to `jvmTest` once the
 * Firebase migration landed — production now authenticates GCS reads/writes with the
 * Firebase ID token, not a service-account JWT. Keeping the signer out of the production
 * binary closes a load-bearing security-commitment from the migration plan: a desktop binary
 * should never ship code that can mint a long-lived service-account credential.
 */
fun interface CloudCredentials {
    suspend fun token(): String
}
