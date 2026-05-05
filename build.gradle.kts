// Root build script. Per-module config lives in convention plugins under build-logic/.
// Empty by design — adding common config here would tightly couple all subprojects.

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
