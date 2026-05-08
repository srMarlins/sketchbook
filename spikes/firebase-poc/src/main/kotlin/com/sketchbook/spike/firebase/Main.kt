/*
 * Phase 0 spike entry point.
 *
 * Each `probe*` function answers one question from
 * docs/plans/2026-05-08-firebase-migration-design.md "Phase 0: Spike + plan":
 *
 *   1. Pattern A's FirebasePlatform token hook gates Firestore listener RPCs
 *   2. Listener offline-reconnect behaviour is acceptable
 *   3. Firebase Storage REST works with Firebase ID token bearer
 *   4. JWKS verification of Google ID token in OAuth flow
 *   5. Library version conflicts vs our pinned coroutines/serialization/ktor
 *
 * Run with `./gradlew :spikes:firebase-poc:run` once env is configured (see
 * README in this module — TODO).
 */
package com.sketchbook.spike.firebase

fun main(args: Array<String>) {
    println("firebase-poc spike — see Main.kt for which probe to run")
    println("args: ${args.joinToString()}")
    // Probe wiring lands in subsequent commits.
}
