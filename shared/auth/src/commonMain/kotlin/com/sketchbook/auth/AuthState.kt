package com.sketchbook.auth

import com.sketchbook.core.UserId

sealed interface AuthState {
    data object SignedOut : AuthState

    data class SignedIn(val userId: UserId, val email: String) : AuthState
}
