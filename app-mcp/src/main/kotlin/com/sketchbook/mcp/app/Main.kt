package com.sketchbook.mcp.app

/**
 * Stdio MCP entry point. Wired to a real `ProjectRepository` + `FileProposalsWriter` in PR-18
 * once Metro graph composition lands. For now `main` parses the system properties (`AUDIO_ROOT`)
 * and logs that wiring is pending — keeps the module compiling and `gradle :app-mcp:run`
 * exercising the entry path end-to-end.
 */
fun main() {
    val root = System.getenv("AUDIO_ROOT")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("user.dir")
    System.err.println("sketchbook MCP server: AUDIO_ROOT=$root")
    System.err.println("(repository wiring lands in PR-18 — server is reachable but read-only stub for now)")
}
