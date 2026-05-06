package com.sketchbook.desktop

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Runtime diagnostics for the desktop app. Two responsibilities:
 *
 *  1. **Rolling log file** under the OS data dir (`logs/sketchbook.log`). Replaces ad-hoc
 *     `System.err.println` calls so a user reporting a bug can attach a real log instead of a
 *     transient terminal blob. Rotates when the file passes [MAX_LOG_BYTES]; one prior file is
 *     retained as `sketchbook.log.1`.
 *
 *  2. **Uncaught-exception handler** that captures stack traces from any thread (Compose UI
 *     dispatcher, coroutines on `Dispatchers.Default/IO`, scanner workers) and dumps a
 *     timestamped `crash-<ts>.log` next to the rolling log. Without this, a crash inside a
 *     coroutine body inflates the JVM with a stack on stderr that nobody sees because the
 *     window already closed.
 *
 * Init is called once from `main` before the Compose `application { }` block. All log calls are
 * thread-safe (synchronized on the file path); contention is fine — the desktop logs a few
 * dozen lines per session.
 */
object Diagnostics {

    private const val MAX_LOG_BYTES: Long = 5L * 1024 * 1024

    private val tsFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    @Volatile
    private var logFile: Path? = null

    @Volatile
    private var crashDir: Path? = null

    private val writeLock = Any()

    /**
     * Initialise diagnostics. Idempotent — calling twice is a no-op. After this returns, all
     * uncaught exceptions in any thread will be captured to a crash file and the application
     * version + JVM details will be at the head of the rolling log.
     */
    fun init(dataDir: Path, appVersion: String) {
        if (logFile != null) return
        synchronized(writeLock) {
            if (logFile != null) return
            val logsDir = dataDir.resolve("logs")
            Files.createDirectories(logsDir)
            val target = logsDir.resolve("sketchbook.log")
            rotateIfNeeded(target)
            logFile = target
            crashDir = logsDir

            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                captureCrash(thread, throwable)
                previous?.uncaughtException(thread, throwable)
            }

            log("INFO", "diagnostics", "Sketchbook $appVersion starting")
            log("INFO", "diagnostics", "java=${System.getProperty("java.version")} os=${System.getProperty("os.name")} arch=${System.getProperty("os.arch")}")
        }
    }

    /** Append a single line to the rolling log. Format: `<ts> <LEVEL> <tag>: <msg>`. */
    fun log(level: String, tag: String, msg: String) {
        val target = logFile ?: return
        val line = "${tsFormatter.format(Instant.now())} $level $tag: $msg\n"
        runCatching {
            synchronized(writeLock) {
                rotateIfNeeded(target)
                Files.writeString(
                    target,
                    line,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            }
        }
        // Dev convenience: still echo to stderr so `gradle :app-desktop:run` shows lines too.
        System.err.print(line)
    }

    /** Capture a crash to a dedicated file the user can attach to a report. */
    private fun captureCrash(thread: Thread, throwable: Throwable) {
        val dir = crashDir ?: return
        val stamp = Instant.now().toString().replace(':', '-').substringBefore('.')
        val crashFile = dir.resolve("crash-$stamp.log")
        val sw = StringWriter()
        sw.append("Sketchbook crash report\n")
        sw.append("timestamp: ").append(Instant.now().toString()).append("\n")
        sw.append("thread: ").append(thread.name).append("\n")
        sw.append("java: ").append(System.getProperty("java.version")).append("\n")
        sw.append("os: ").append(System.getProperty("os.name"))
            .append(" ").append(System.getProperty("os.version") ?: "")
            .append(" ").append(System.getProperty("os.arch")).append("\n\n")
        throwable.printStackTrace(PrintWriter(sw))
        runCatching { Files.writeString(crashFile, sw.toString()) }
        log("ERROR", "diagnostics", "Uncaught on ${thread.name}: ${throwable::class.simpleName}: ${throwable.message}")
    }

    private fun rotateIfNeeded(target: Path) {
        if (!Files.exists(target)) return
        if (Files.size(target) < MAX_LOG_BYTES) return
        val rotated = target.resolveSibling("${target.fileName}.1")
        runCatching {
            Files.deleteIfExists(rotated)
            Files.move(target, rotated)
        }
    }

    /** Resolve the same per-OS data dir that `DesktopAppGraph.catalogDbPath()` uses. */
    fun resolveDataDir(): Path {
        System.getenv("SKETCHBOOK_DB_PATH")?.let { override ->
            return Paths.get(override).parent ?: Paths.get(override)
        }
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val home = Paths.get(System.getProperty("user.home"))
        val dir = when {
            os.contains("win") -> Paths.get(System.getenv("APPDATA") ?: home.toString()).resolve("Sketchbook")
            os.contains("mac") -> home.resolve("Library/Application Support/Sketchbook")
            else -> home.resolve(".local/share/sketchbook")
        }
        Files.createDirectories(dir)
        return dir
    }
}
