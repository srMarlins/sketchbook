package com.sketchbook.liveit

import com.sketchbook.auth.firebase.FirebaseConfig
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Config resolver for the live-integration tasks. Reads everything from system properties
 * (the build.gradle.kts forwards `-P` Gradle props as `live.*` system props and any
 * `sketchbook.*` props verbatim).
 *
 * Kept deliberately tiny — there's no DI graph here, just a flat object that each `main()`
 * reaches into.
 */
object LiveTestEnv {
    /**
     * Where the cached refresh token lives. Mode 0600 (POSIX) when written by [FileTokenStore].
     * Outside the repo root so it never accidentally lands in git; runbook reminds the user.
     */
    val tokenCachePath: Path =
        Paths.get(System.getProperty("user.home"), ".sketchbook-test", "auth.json")

    /**
     * Prefix used for every test-minted [com.sketchbook.core.ProjectUuid]. Load-bearing for
     * [LiveTestSweepKt] — sweep scopes deletes to `liveit-*` so a stray test never touches a
     * real user project.
     */
    const val TEST_UUID_PREFIX = "liveit-"

    /**
     * Picks dev vs prod via the same `sketchbook.env` system property the prod auth path
     * uses. Defaults to dev. We refuse to run against prod from here — live-integration
     * tests write real Firestore docs + Storage blobs, and prod is for real users.
     */
    fun firebaseConfig(): FirebaseConfig {
        val env = System.getProperty("sketchbook.env", "dev")
        check(env == "dev") {
            "Live-integration tests refuse to run against env=$env. " +
                "Set -Dsketchbook.env=dev (or leave unset — dev is the default)."
        }
        return FirebaseConfig.active()
    }

    /** `-PprojectDir=...` or `SKETCHBOOK_LIVE_PROJECT_DIR` env var. */
    fun pushProjectDir(): Path {
        val raw =
            System.getProperty("live.projectDir")
                ?: System.getenv("SKETCHBOOK_LIVE_PROJECT_DIR")
                ?: error(
                    "Missing projectDir. Pass `-PprojectDir=\"<abs-path-to-Live-project>\"` " +
                        "or set SKETCHBOOK_LIVE_PROJECT_DIR.",
                )
        val path = Paths.get(raw)
        check(path.isAbsolute) { "projectDir must be absolute: $raw" }
        return path
    }

    /** `-PdisplayName=...` or derived from the directory name. */
    fun pushDisplayName(projectDir: Path): String =
        System.getProperty("live.displayName")
            ?: projectDir.fileName?.toString()
            ?: "live-integration-push"

    /** `-PtreeIdSuffix=...` — optional human-readable suffix appended to the test UUID. */
    fun pushTreeIdSuffix(): String? = System.getProperty("live.treeIdSuffix")?.takeIf { it.isNotBlank() }

    /** `-Puuid=...` or `SKETCHBOOK_LIVE_PULL_UUID`. */
    fun pullUuid(): String {
        val v =
            System.getProperty("live.uuid")
                ?: System.getenv("SKETCHBOOK_LIVE_PULL_UUID")
                ?: error("Missing uuid. Pass `-Puuid=liveit-...` or set SKETCHBOOK_LIVE_PULL_UUID.")
        check(v.startsWith(TEST_UUID_PREFIX)) {
            "Refusing to pull a non-test UUID (prefix must be '$TEST_UUID_PREFIX'): $v"
        }
        return v
    }

    /** `-PdestDir=...` or `SKETCHBOOK_LIVE_PULL_DEST`. Must be empty or non-existent. */
    fun pullDestDir(): Path {
        val raw =
            System.getProperty("live.destDir")
                ?: System.getenv("SKETCHBOOK_LIVE_PULL_DEST")
                ?: error("Missing destDir. Pass `-PdestDir=\"<abs-path>\"` or set SKETCHBOOK_LIVE_PULL_DEST.")
        val path = Paths.get(raw)
        check(path.isAbsolute) { "destDir must be absolute: $raw" }
        return path
    }

    /** Sweep config: `-PolderThanHours=<N>` and `-Papply=true` to actually delete. */
    fun sweepOlderThanHours(): Long = System.getProperty("live.olderThanHours")?.toLongOrNull() ?: 0L

    fun sweepApply(): Boolean = System.getProperty("live.apply")?.equals("true", ignoreCase = true) == true

    /**
     * OAuth client ID. Mirrors the desktop app's loader — the placeholder is a build
     * sentinel; real value comes via `-Dsketchbook.oauth.client_id=...`.
     */
    fun oauthClientId(): String {
        val v = System.getProperty("sketchbook.oauth.client_id") ?: ""
        check(v.isNotBlank() && !v.startsWith("REPLACE_ME")) {
            "Missing sketchbook.oauth.client_id. Pass `-Dsketchbook.oauth.client_id=<your-desktop-oauth-client-id>`. " +
                "Use the same desktop OAuth client ID configured in the Firebase Console for this project."
        }
        return v
    }

    /**
     * OAuth client secret. Google's token endpoint requires this even for Desktop/PKCE flows.
     * Loaded from `-Dsketchbook.oauth.client_secret=...`; null if not set (exchange omits it,
     * which will fail with Google but is acceptable for unit tests).
     */
    fun oauthClientSecret(): String? = System.getProperty("sketchbook.oauth.client_secret")?.takeIf { it.isNotBlank() }
}
