package com.sketchbook.core

/**
 * Application-lifetime scope marker for Metro bindings. Lives in `shared/core` so any module
 * can mark a class `@SingleIn(AppScope::class)` without depending on `app-desktop`.
 *
 * Future scope candidates (NOT introduced in this PR; documented for context):
 *  - `CloudScope` — child of `AppScope`, lifetime tied to cloud-credential availability.
 *  - `ScreenScope` — per-`NavEntry` scope; currently handled implicitly by `lifecycle-viewmodel-navigation3`.
 */
abstract class AppScope private constructor()
