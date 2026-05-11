package com.sketchbook.desktop.auth

import com.sketchbook.auth.AuthSession
import com.sketchbook.cloud.CloudCredentials

/**
 * Cloud-credentials adapter. Pulls a fresh **Firebase ID token** from an [AuthSession]
 * (post-Phase-2 the active [AuthSession] is `FirebaseAuthSession`, which mints Firebase ID
 * tokens via Identity Toolkit). GCS accepts the Firebase ID token as a bearer credential,
 * so [com.sketchbook.cloud.FirebaseBlobStore] doesn't care about the token's origin — this
 * adapter exists only so the cloud module never depends on `shared/auth`.
 *
 * Token caching + refresh are owned by `AuthSession`, not this class.
 */
class FirebaseCloudCredentials(
    private val authSession: AuthSession,
) : CloudCredentials {
    override suspend fun token(): String = authSession.idToken()
}
