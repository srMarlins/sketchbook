package com.sketchbook.desktop.auth

import com.sketchbook.auth.AuthState
import com.sketchbook.core.UserId
import java.util.prefs.Preferences

/**
 * Persists the user's identity (Google `sub` + email) alongside the keychain-stored refresh
 * token so silent restore can emit [AuthState.SignedIn] before the inner [AuthSession]
 * finishes its background refresh. The refresh token itself stays in the OS keychain via
 * [com.sketchbook.auth.KeyringTokenStore].
 *
 * Keys are versioned (`*_v1`) so a future schema change can ignore stale entries instead of
 * mis-decoding them.
 */
class PrefsIdentityStore(private val node: Preferences) {

    fun load(): AuthState.SignedIn? {
        val sub = node.get(KEY_SUB, null) ?: return null
        val email = node.get(KEY_EMAIL, null) ?: return null
        return AuthState.SignedIn(userId = UserId(sub), email = email)
    }

    fun save(signedIn: AuthState.SignedIn) {
        node.put(KEY_SUB, signedIn.userId.value)
        node.put(KEY_EMAIL, signedIn.email)
        node.flush()
    }

    fun clear() {
        node.remove(KEY_SUB)
        node.remove(KEY_EMAIL)
        node.flush()
    }

    private companion object {
        const val KEY_SUB = "auth_sub_v1"
        const val KEY_EMAIL = "auth_email_v1"
    }
}
