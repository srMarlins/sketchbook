package com.sketchbook.desktop.auth

import com.sketchbook.auth.AuthSession
import com.sketchbook.auth.AuthState
import com.sketchbook.auth.firebase.FirebaseAuthSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
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
        // Forward inner state changes, persisting identity on transitions. drop(1) skips the
        // initial SignedOut replay — without it, observing the inner's initial value would
        // wipe the optimistically-loaded cached identity before tryRestore had a chance to
        // flip the inner state to SignedIn (B4 / K6).
        scope.launch {
            inner.state.drop(1).collectLatest { innerState ->
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
        // Silent restore at startup. tryRestore() in B4 now flips the inner state to SignedIn
        // on success (passing emailHint from the cached identity so the secureToken refresh
        // response — which doesn't echo email — still yields a fully-populated SignedIn).
        // If we have a cached identity AND restore fails, fall back to SignedOut. B5 keeps
        // the keyring populated on transient failures so the next attempt can recover.
        scope.launch {
            val restored = inner.tryRestore(emailHint = cachedIdentity?.email)
            if (cachedIdentity != null && !restored) {
                identityStore.clear()
                _state.value = AuthState.SignedOut
            }
            // If restored: inner.state already flipped SignedIn via the collector above; the
            // optimistic _state.value still matches the cached identity. Nothing to do here.
        }
    }

    override suspend fun signIn(): Result<AuthState.SignedIn> = inner.signIn()

    override suspend fun signOut() {
        inner.signOut()
        identityStore.clear()
    }

    override suspend fun idToken(): String = inner.idToken()

    /**
     * Expose the wrapped [FirebaseAuthSession] for components that need its richer surface
     * (e.g. `FirebaseSdkBootstrap` needs `currentTokens()`, which isn't on the [AuthSession]
     * interface). The DI graph never injects a raw `FirebaseAuthSession`; consumers cast their
     * injected `AuthSession` to `DesktopAuthSession` and call `unwrap()`.
     */
    fun unwrap(): FirebaseAuthSession = inner
}
