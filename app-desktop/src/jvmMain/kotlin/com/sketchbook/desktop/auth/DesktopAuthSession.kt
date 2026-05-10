package com.sketchbook.desktop.auth

import com.sketchbook.auth.AuthSession
import com.sketchbook.auth.AuthState
import com.sketchbook.auth.firebase.FirebaseAuthSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Desktop-flavored [AuthSession] that wraps [FirebaseAuthSession] and weaves a
 * [PrefsIdentityStore] in/out so silent restore actually emits [AuthState.SignedIn] from a
 * cached identity. The inner session deliberately doesn't fabricate a SignedIn from
 * [FirebaseAuthSession.tryRestore] — it doesn't carry the user's email — so that step lives
 * here, where the cached identity is available.
 *
 * Init flow:
 *  - Expose the persisted identity as the initial state so the UI doesn't flash SignedOut
 *    between launch and tryRestore.
 *  - Launch [FirebaseAuthSession.tryRestore] once; on success, if a cached identity exists,
 *    emit SignedIn. On failure, fall through to SignedOut.
 *  - Subscribe to the inner session's state and forward each emission, persisting the
 *    identity on SignedIn and clearing it on SignedOut.
 */
class DesktopAuthSession(
    private val inner: FirebaseAuthSession,
    private val identityStore: PrefsIdentityStore,
    scope: CoroutineScope,
) : AuthSession {
    private val cachedIdentity: AuthState.SignedIn? = identityStore.load()
    private val _state = MutableStateFlow<AuthState>(cachedIdentity ?: AuthState.SignedOut)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        // Forward inner state changes, persisting identity on transitions.
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
        // Silent restore at startup. tryRestore() never throws; it just caches tokens (or
        // clears the keyring if the stored refresh token is dead). If we have a cached
        // identity AND restore succeeds, keep _state at SignedIn (no transition needed — it
        // was already SignedIn optimistically). If restore fails and we had a cached
        // identity, fall back to SignedOut.
        scope.launch {
            val restored = inner.tryRestore()
            if (cachedIdentity != null && !restored) {
                identityStore.clear()
                _state.value = AuthState.SignedOut
            }
        }
    }

    override suspend fun signIn(): Result<AuthState.SignedIn> =
        inner.signIn().also { r ->
            r.getOrNull()?.let { identityStore.save(it) }
        }

    override suspend fun signOut() {
        inner.signOut()
        identityStore.clear()
    }

    override suspend fun accessToken(): String = inner.accessToken()
}
