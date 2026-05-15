package com.sketchbook.featureonboarding

import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SketchbookError
import com.sketchbook.repo.BlobCacheSettings
import com.sketchbook.repo.LibraryRoot
import com.sketchbook.repo.OnboardingPromptKind
import com.sketchbook.repo.OnboardingSkipFlags
import com.sketchbook.repo.Settings
import com.sketchbook.repo.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Clock

internal class FakeSettingsRepository(
    initial: Settings =
        Settings(
            libraryRoots = emptyList(),
            selfContainedProjects = emptySet(),
        ),
    /**
     * Paths in this set will cause [upsertRoot] to throw [SketchbookError.IoFailure] without
     * mutating the settings flow. The attempt is still recorded in [attemptedUpserts].
     */
    val failingPaths: Set<String> = emptySet(),
) : SettingsRepository {
    val flow = MutableStateFlow(initial)

    /** Roots whose upsert succeeded. Mirrors the prior `upserts` field — kept for back-compat. */
    var upserts: MutableList<LibraryRoot> = mutableListOf()

    /** Every upsert call recorded, including ones that failed. */
    var attemptedUpserts: MutableList<LibraryRoot> = mutableListOf()
    var removals: MutableList<LibraryRoot> = mutableListOf()
    var pluginFolderWrites: MutableList<List<String>> = mutableListOf()
    var markCompleteCalls: MutableList<OnboardingSkipFlags> = mutableListOf()

    /** Alias matching the recording-fake naming used by the Task 7 tests. */
    val upsertedRoots: List<LibraryRoot> get() = upserts
    val pluginFoldersWrites: List<List<String>> get() = pluginFolderWrites

    override fun observe(): Flow<Settings> = flow

    override suspend fun upsertRoot(root: LibraryRoot) {
        attemptedUpserts += root
        if (root.path in failingPaths) {
            throw SketchbookError.IoFailure("simulated failure for ${root.path}")
        }
        upserts += root
        flow.value = flow.value.copy(libraryRoots = (flow.value.libraryRoots + root).distinct())
    }

    override suspend fun removeRoot(root: LibraryRoot) {
        removals += root
        flow.value = flow.value.copy(libraryRoots = flow.value.libraryRoots - root)
    }

    override suspend fun setSelfContained(
        uuid: ProjectUuid,
        value: Boolean,
    ) = Unit

    override suspend fun setCacheSettings(settings: BlobCacheSettings) = Unit

    override suspend fun markFirstRunComplete(skipFlags: OnboardingSkipFlags) {
        markCompleteCalls += skipFlags
        flow.value =
            flow.value.copy(
                firstRunCompletedAt = Clock.System.now(),
                onboardingSkipped = skipFlags,
            )
    }

    override suspend fun dismissOnboardingPrompt(kind: OnboardingPromptKind) = Unit

    override suspend fun setPluginFolders(folders: List<String>) {
        pluginFolderWrites += folders
        flow.value = flow.value.copy(pluginFolders = folders)
    }

    override suspend fun resetFirstRun() {
        flow.value =
            flow.value.copy(
                firstRunCompletedAt = null,
                onboardingSkipped = OnboardingSkipFlags(),
            )
    }
}

internal class FakeScanTrigger : ScanTrigger {
    var scanCount: Int = 0

    override fun triggerScan() {
        scanCount++
    }
}
