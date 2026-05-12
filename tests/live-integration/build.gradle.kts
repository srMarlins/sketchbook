plugins {
    id("kmp-library")
    alias(libs.plugins.kotlin.serialization)
}

// Live-cloud integration tests. Unlike `:tests:integration` (which uses InMemoryMetadataStore
// + FakeCloudBackend), this module talks to a real Firebase project (default: dev,
// `sketchbook-jtf-2026`). Entry points are `main()` classes invoked via the Gradle tasks below
// — they are NOT JUnit @Test methods, so a plain `./gradlew test` never reaches Firebase.
//
// One-time setup per machine:
//   ./gradlew :tests:live-integration:liveTestLogin \
//       -Dsketchbook.oauth.client_id=<your-desktop-client-id>.apps.googleusercontent.com
//
// Per-scenario (Mac → Windows round-trip):
//   # On Mac
//   ./gradlew :tests:live-integration:liveTestPush \
//       -PprojectDir="/Users/.../My Project Folder"
//   # → prints UUID=liveit-<epoch>-<rand> ; HEAD_REV=<n>
//
//   # On Windows
//   .\gradlew :tests:live-integration:liveTestPull `
//       -Puuid=liveit-... -PdestDir="C:\sketchbook-pulled\my-project"
//   # → runs manifest+parse+sampleRef assertions; exit 0 if clean
//
// Cleanup:
//   ./gradlew :tests:live-integration:liveTestSweep
kotlin {
    sourceSets {
        commonMain.dependencies {
            // empty — KMP requires commonMain; entries live in jvmMain
        }
        jvmMain.dependencies {
            implementation(project(":shared:core"))
            implementation(project(":shared:cloud"))
            implementation(project(":shared:auth"))
            implementation(project(":shared:sync"))
            implementation(project(":shared:sync-io"))
            implementation(project(":shared:parser-als"))
            implementation(libs.kotlinx.coroutines.core)
            // Required so Firebase's Firestore SDK can launch coroutines on Dispatchers.Main
            // at init time (FirebaseAuthCredentialsProvider registers an IdToken listener).
            // Without this, any access to FirestoreMetadataStore.firestore throws
            // "Module with the Main dispatcher is missing".
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.io.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
        }
    }
}

// JavaExec tasks against the jvmMain runtime classpath. JavaExec is the right tool here
// (not the Gradle `test` task) because (a) these are scripts, not @Test methods, and (b)
// `test` task UP-TO-DATE caching is dangerous for code that talks to real cloud — Gradle
// would happily skip a "test" task that already passed once even when we want a fresh run.
val jvmMainOutput = kotlin.targets.getByName("jvm").compilations.getByName("main").output.allOutputs
val jvmRuntimeClasspath = configurations.named("jvmRuntimeClasspath")

// Forward `-P` Gradle project properties and any `sketchbook.*` system properties to the
// JavaExec processes. Property forwarding is the cheap way to feed config in without
// inventing yet another env-var convention; `sketchbook.*` system props are what the prod
// auth path already reads (e.g. `sketchbook.oauth.client_id`, `sketchbook.env`).
fun JavaExec.wireLiveEnv() {
    classpath(jvmMainOutput, jvmRuntimeClasspath)
    standardInput = System.`in`
    // Forward project properties as -Plive.* system properties so jvmMain code can read them
    // via System.getProperty("live.projectDir") etc. without coupling to Gradle types.
    project.properties.forEach { (k, v) ->
        if (v is String) systemProperty("live.$k", v)
    }
    // Forward sketchbook.* system props (e.g. sketchbook.oauth.client_id, sketchbook.env).
    System.getProperties().forEach { k, v ->
        val key = k.toString()
        if (key.startsWith("sketchbook.")) systemProperty(key, v.toString())
    }
    // Always-on so cloud HTTP / Firestore listener threads finish their callbacks before the
    // JVM exits.
    isIgnoreExitValue = false
}

tasks.register<JavaExec>("liveTestLogin") {
    group = "live-integration"
    description = "One-time interactive Google sign-in for live-cloud tests. " +
        "Caches refresh token at ~/.sketchbook-test/auth.json. " +
        "Requires -Dsketchbook.oauth.client_id=<your-desktop-oauth-client-id>."
    mainClass.set("com.sketchbook.liveit.LiveTestLoginKt")
    wireLiveEnv()
}

tasks.register<JavaExec>("liveTestPush") {
    group = "live-integration"
    description = "Snapshot a real Ableton project folder to the live Firebase project. " +
        "Required: -PprojectDir=\"<abs-path-to-Live-project-folder>\". " +
        "Optional: -PdisplayName=\"<label>\", -PtreeIdSuffix=\"<suffix-after-liveit->\". " +
        "Prints UUID=... and HEAD_REV=... on success."
    mainClass.set("com.sketchbook.liveit.LiveTestPushKt")
    wireLiveEnv()
}

tasks.register<JavaExec>("liveTestPull") {
    group = "live-integration"
    description = "Pull a UUID from the live Firebase project, materialize to destDir, run " +
        "manifest-bytes + .als-reparse + sample-ref-resolve assertions. " +
        "Required: -Puuid=<liveit-...>, -PdestDir=\"<abs-path-to-empty-dir>\"."
    mainClass.set("com.sketchbook.liveit.LiveTestPullKt")
    wireLiveEnv()
}

tasks.register<JavaExec>("liveTestTwoClient") {
    group = "live-integration"
    description = "Single-process two-simulated-client integration test. Spawns two ClientHandles " +
        "with different hostIds against the live dev Firebase project and runs a battery of " +
        "sync + lock scenarios. Required: -PtemplateDir=\"<abs-path-to-small-Live-project>\". " +
        "Optional: -Pscenario=<name|all> (default all). Names: linearSync, collectionListener, " +
        "editAndResync, lockContention, bidirectional, lockExpiry."
    mainClass.set("com.sketchbook.liveit.LiveTestTwoClientKt")
    wireLiveEnv()
}

tasks.register<JavaExec>("liveTestSweep") {
    group = "live-integration"
    description = "Delete liveit-* test trees + their manifests/blobs/locks from the live " +
        "Firebase project. Scoped to the signed-in UID. Optional: -PolderThanHours=<N> " +
        "(default 0 → delete all). Dry-run by default; pass -Papply=true to actually delete."
    mainClass.set("com.sketchbook.liveit.LiveTestSweepKt")
    wireLiveEnv()
}
