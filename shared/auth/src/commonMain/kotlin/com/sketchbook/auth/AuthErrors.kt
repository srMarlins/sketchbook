package com.sketchbook.auth

sealed class AuthException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class OAuthCancelled : AuthException("Sign-in cancelled by user")

class OAuthTimeout : AuthException("OAuth callback did not arrive in time")

class OAuthFailed(message: String, cause: Throwable? = null) : AuthException(message, cause)

class AuthSessionExpired(cause: Throwable? = null) : AuthException("Refresh token rejected — sign in again", cause)
