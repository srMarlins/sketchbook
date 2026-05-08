/*
 * Phase 0 spike entry point.
 *
 * Usage (from repo root):
 *   ./gradlew :spikes:firebase-poc:run --args="<probe> [args...]"
 *
 * Probes (see Probes.kt for details):
 *   listener-sanity <email> <password>
 *     Validates Firestore listeners work on JVM via gitlive at all. Requires:
 *       - Firebase Console → Authentication → Sign-in method → enable Email/Password
 *       - Create a test user under Authentication → Users
 *
 *   exchange-google-token <google-id-token>
 *     Validates Identity Toolkit REST exchange + JWKS verification. Get a Google ID
 *     token from https://developers.google.com/oauthplayground/ (configure with our
 *     OAuth Client ID + scopes "openid email profile") for a one-shot test.
 *
 *   storage-rest <firebase-id-token>
 *     Validates Storage REST PUT works with a Firebase ID token bearer. Pipe the
 *     output of `exchange-google-token` here.
 */
package com.sketchbook.spike.firebase

import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    runBlocking {
        runProbe(args.toList())
    }
}
