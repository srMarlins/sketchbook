package com.sketchbook.featureonboarding

internal expect fun defaultPluginFolders(): List<String>

/** Suggested Projects root for the current OS, or null on unknown platforms. */
internal expect fun defaultProjectsRootSuggestion(): String?

/** Suggested User Samples root for the current OS, or null when there's no obvious convention. */
internal expect fun defaultSamplesRootSuggestion(): String?
