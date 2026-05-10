package com.sketchbook.desktop.auth

import com.sketchbook.auth.AuthSession
import com.sketchbook.cloud.CloudCredentials

/**
 * Cloud-credentials adapter that pulls a fresh OAuth access token from an [AuthSession]. The
 * session handles token caching + refresh — this class is just the seam between the cloud
 * backend and the auth subsystem so [com.sketchbook.cloud.FirebaseBlobStore] never depends on
 * `shared/auth`.
 */
class OAuthCloudCredentials(
    private val authSession: AuthSession,
) : CloudCredentials {
    override suspend fun token(): String = authSession.accessToken()
}
