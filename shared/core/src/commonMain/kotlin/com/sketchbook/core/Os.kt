package com.sketchbook.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Canonical OS label used across the desktop graph. [wireName] is the lowercase identifier
 * persisted to JSON manifests (`HostPluginManifest.os`, `MachineEntry.os`) and the value
 * `OsProvider.Default` returns; the `@SerialName` annotations make `kotlinx.serialization`
 * encode the same string without a custom serializer.
 *
 * Promoted out of stringly-typed `os: String` parameters per #129 — call sites that need to
 * branch on OS pattern-match exhaustively over [Mac]/[Windows]/[Linux] instead of `when` over
 * literal strings (the previous shape had a literal duplicate `"ableton", "unknown" -> false`
 * + `else -> false` in `SetupNav.formatRunsOn`).
 */
@Serializable
enum class Os(
    val wireName: String,
) {
    @SerialName("darwin")
    Mac("darwin"),

    @SerialName("windows")
    Windows("windows"),

    @SerialName("linux")
    Linux("linux"),
    ;

    companion object {
        /**
         * Decode a wire string. Returns `null` for unknown values so callers can decide
         * whether to default or to surface the error — there is no "Unknown" Os variant.
         */
        fun fromWire(s: String): Os? = entries.firstOrNull { it.wireName == s }

        /**
         * Heuristic match against `os.name` (or any host-OS-name string). Falls back to
         * [Linux] when no token matches — the desktop ships only Mac/Windows binaries today,
         * so a Linux fallback is the closest match for an unknown POSIX host.
         */
        fun fromOsName(rawOsName: String?): Os {
            val raw = rawOsName?.lowercase().orEmpty()
            return when {
                raw.contains("mac") || raw.contains("darwin") -> Mac
                raw.contains("win") -> Windows
                else -> Linux
            }
        }
    }
}
