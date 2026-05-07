package com.sketchbook.featureonboarding

internal actual fun defaultPluginFolders(): List<String> {
    val os = System.getProperty("os.name").orEmpty()
    val home = System.getProperty("user.home").orEmpty()
    return when {
        os.startsWith("Windows", ignoreCase = true) -> {
            listOf(
                "C:/Program Files/Common Files/VST3",
                "C:/Program Files/VstPlugins",
                "C:/Program Files/Common Files/VST2",
            )
        }

        os.startsWith("Mac", ignoreCase = true) -> {
            listOf(
                "/Library/Audio/Plug-Ins/VST3",
                "/Library/Audio/Plug-Ins/VST",
                "/Library/Audio/Plug-Ins/Components",
                "$home/Library/Audio/Plug-Ins/VST3",
            )
        }

        // Linux: yabridge / Bitwig / Reaper conventions. Used as test ground for CI even
        // though Linux is not an official Sketchbook target — keeps the empty-list edge
        // case out of the OS-default-driven onboarding flow.
        else -> {
            listOf(
                "/usr/lib/vst3",
                "/usr/local/lib/vst3",
                "$home/.vst3",
            )
        }
    }
}

internal actual fun defaultProjectsRootSuggestion(): String? {
    val os = System.getProperty("os.name").orEmpty()
    val home = System.getProperty("user.home").orEmpty()
    return when {
        os.startsWith("Windows", ignoreCase = true) -> "$home/Documents/Live Projects"
        os.startsWith("Mac", ignoreCase = true) -> "$home/Music/Ableton/User Library"
        else -> null
    }
}

internal actual fun defaultSamplesRootSuggestion(): String? = null
