package com.sketchbook.auth.firebase

/**
 * Firebase project configuration. Public client identifiers — Web API key, project ID, and
 * bucket name are designed to ship in browser/binary distribution. Security comes from Auth
 * + Security Rules, not from keeping these values hidden.
 *
 * Two environments. Selected at startup via the `sketchbook.env` system property:
 *
 *   -Dsketchbook.env=dev      → [Dev]   (default)
 *   -Dsketchbook.env=prod     → [Prod]
 *
 * Desktop OAuth client ID + secret are NOT bundled here — they live in the existing
 * `auth.properties` resource (loaded by `OAuthClient`). Keeping them out of source keeps
 * automated secret-scanners quiet and makes rotation cleaner.
 */
sealed interface FirebaseConfig {
    /** Firebase project ID. */
    val projectId: String

    /** Firebase Web API key (public; identifies the Firebase project to Identity Toolkit + secure-token). */
    val webApiKey: String

    /** Cloud Storage bucket — new-style `*.firebasestorage.app` naming. */
    val storageBucket: String

    /** Auth domain used by Firebase Auth's OOB email links. Recorded for completeness; not used by JVM clients. */
    val authDomain: String

    data object Dev : FirebaseConfig {
        override val projectId: String = "sketchbook-jtf-2026"
        override val webApiKey: String = "AIzaSyB132N_cFwVnLJEff3qoMeYqEoQUNtdIR8"
        override val storageBucket: String = "sketchbook-jtf-2026.firebasestorage.app"
        override val authDomain: String = "sketchbook-jtf-2026.firebaseapp.com"
    }

    data object Prod : FirebaseConfig {
        override val projectId: String = "sketchbook-jtf-prod-2026"
        override val webApiKey: String = "AIzaSyAKqe8qY63pS9UJJAmm908ik2vkm3ZCBjA"
        override val storageBucket: String = "sketchbook-jtf-prod-2026.firebasestorage.app"
        override val authDomain: String = "sketchbook-jtf-prod-2026.firebaseapp.com"
    }

    companion object {
        const val ENV_PROPERTY: String = "sketchbook.env"

        fun active(): FirebaseConfig =
            when (System.getProperty(ENV_PROPERTY, "dev").lowercase()) {
                "prod", "production" -> Prod
                else -> Dev
            }
    }
}
