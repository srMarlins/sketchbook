package com.sketchbook.sync.migration

import com.sketchbook.core.ProjectUuid
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

class JvmAudioIdSidecar : AudioIdSidecar {

    override fun read(projectDir: String): ProjectUuid? {
        val path = sidecarPath(projectDir)
        if (!Files.isRegularFile(path)) return null
        val raw = runCatching { Files.readString(path, StandardCharsets.UTF_8) }.getOrNull() ?: return null
        return parseUuid(raw)
    }

    override fun write(projectDir: String, uuid: ProjectUuid): Boolean {
        val dir = Paths.get(projectDir)
        if (!Files.isDirectory(dir)) return false
        val path = sidecarPath(projectDir)
        return runCatching {
            Files.writeString(
                path,
                uuid.value + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            true
        }.getOrElse { e ->
            if (e is IOException) false else throw e
        }
    }

    override fun exists(projectDir: String): Boolean =
        Files.isRegularFile(sidecarPath(projectDir))

    private fun sidecarPath(projectDir: String): Path =
        Paths.get(projectDir).resolve(AUDIO_ID_SIDECAR_NAME)

    private fun parseUuid(raw: String): ProjectUuid? {
        val trimmed = raw
            .removePrefix("﻿") // strip UTF-8 BOM if present
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return null
        return runCatching { ProjectUuid(trimmed) }.getOrNull()
    }
}
