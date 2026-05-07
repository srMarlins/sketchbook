package com.sketchbook.core

/**
 * Per-signed-in-user scope. Built on `AuthState.SignedIn`, torn down on `SignedOut`. Anything
 * that requires an authenticated user (CloudBackend, sync queue, lock repository) lives here.
 *
 * See `docs/architecture/dependency-injection.md` §1 for the wider scope hierarchy.
 */
abstract class UserScope private constructor()
