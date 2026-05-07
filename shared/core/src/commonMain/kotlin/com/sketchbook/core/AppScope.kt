package com.sketchbook.core

/**
 * Application-lifetime scope marker for Metro bindings. Lives in `shared/core` so any module
 * can mark a class `@SingleIn(AppScope::class)` without depending on `app-desktop`.
 *
 * See [UserScope] for the per-signed-in-user scope built on top of this. Per-screen scopes
 * (e.g. `ScreenScope`) are handled implicitly by `lifecycle-viewmodel-navigation3` and are not
 * modelled with Metro today.
 */
abstract class AppScope private constructor()
