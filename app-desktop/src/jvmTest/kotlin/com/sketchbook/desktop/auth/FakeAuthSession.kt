package com.sketchbook.desktop.auth

import com.sketchbook.auth.AuthSession
import com.sketchbook.auth.AuthState
import com.sketchbook.core.UserId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Test stand-in for [AuthSession]. Defaults to a `SignedIn` state so callers that need a
 * "cloud is wired" baseline don't have to set anything up. Mutate [_state] directly to test
 * sign-out transitions.
 */
class FakeAuthSession(initial: AuthState = AuthState.SignedIn(UserId("test-user"), "test@example.com")) : AuthSession {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<AuthState> = _state

    override suspend fun signIn(): Result<AuthState.SignedIn> = error("not used in tests")

    override suspend fun signOut() {
        _state.value = AuthState.SignedOut
    }

    override suspend fun accessToken(): String = "fake-access-token"
}
