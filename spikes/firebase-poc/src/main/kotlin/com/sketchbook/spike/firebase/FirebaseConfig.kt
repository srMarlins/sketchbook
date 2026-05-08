/*
 * Firebase project configuration for the spike.
 *
 * Public values only. The Firebase Web API key is a public client identifier —
 * it's designed to ship in browser/binary distribution. Project ID and bucket
 * name are similarly non-secret. Security comes from Auth + Rules, not from
 * keeping these values hidden.
 *
 * OAuth Client ID + Secret live in `secrets.local.kt` (gitignored), with a
 * template at `secrets.local.kt.template` for setup. Reason: even though
 * Google calls desktop OAuth client secrets "semi-public" (they have to ship
 * in the binary), keeping them out of git history avoids credential-leak
 * scanners and makes rotation cleaner if needed.
 */
package com.sketchbook.spike.firebase

object FirebaseConfig {
    /** Firebase project ID. Public identifier. */
    const val PROJECT_ID: String = "sketchbook-jtf-2026"

    /**
     * Firebase Web API key. Public client identifier; not a secret.
     * Used by Identity Toolkit (`?key=...`) and the secure-token refresh endpoint.
     */
    const val WEB_API_KEY: String = "AIzaSyB132N_cFwVnLJEff3qoMeYqEoQUNtdIR8"

    /** Cloud Storage bucket. Public. New-style `*.firebasestorage.app` naming. */
    const val STORAGE_BUCKET: String = "sketchbook-jtf-2026.firebasestorage.app"

    /** Auth domain used by Firebase Auth's OOB email links. Not used by JVM clients but recorded for completeness. */
    const val AUTH_DOMAIN: String = "sketchbook-jtf-2026.firebaseapp.com"
}
