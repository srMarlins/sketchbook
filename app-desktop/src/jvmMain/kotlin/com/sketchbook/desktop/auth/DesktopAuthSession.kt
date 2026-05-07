package com.sketchbook.desktop.auth

import com.sketchbook.auth.AuthSession
import com.sketchbook.auth.AuthState
import com.sketchbook.auth.GoogleAuthSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Desktop-flavored [AuthSession] that wraps [GoogleAuthSession] and weaves a
 * [PrefsIdentityStore] in/out so silent restore actually emits [AuthState.SignedIn] from a
 * cached identity (the `shared/auth` session deliberately doesn't re-fetch userinfo on
 * restore — see `GoogleAuthSession`'s docstring).
 *
 * Init flow:
 *  - Optimistically expose the persisted identity as the initial state so the UI doesn't
 *    flash SignedOut between launch and the inner session's background restore.
 *  - Subscribe to the inner session's state and forward each emission, persisting on
 *    SignedIn and clearing on SignedOut.
 */
class DesktopAuthSession(
    private val inner: GoogleAuthSession,
    private val identityStore: PrefsIdentityStore,
    scope: CoroutineScope,
) : AuthSession {

    private val _state = MutableStateFlow<AuthState>(loadInitial())
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        scope.launch {
            inner.state.collectLatest { innerState ->
                when (innerState) {
                    is AuthState.SignedIn -> {
                        identityStore.save(innerState)
                        _state.value = innerState
                    }
                    AuthState.SignedOut -> {
                        identityStore.clear()
                        _state.value = AuthState.SignedOut
                    }
                }
            }
        }
        // If we have a persisted identity AND the inner session restores a refresh token, the
        // inner session will emit SignedIn shortly via its own scope-launched tryRestore. No
        // action needed here — we just expose the persisted identity as an optimistic initial
        // value so the UI doesn't flash "SignedOut" between launch and restore.
    }

    private fun loadInitial(): AuthState = identityStore.load() ?: AuthState.SignedOut

    override suspend fun signIn(): Result<AuthState.SignedIn> = inner.signIn().also { r ->
        r.getOrNull()?.let { identityStore.save(it) }
    }

    override suspend fun signOut() {
        inner.signOut()
        identityStore.clear()
    }

    override suspend fun accessToken(): String = inner.accessToken()
}
