package com.sketchbook.featureonboarding

import com.sketchbook.core.ProjectUuid
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
     * Paths in this set will cause [upsertRoot] to return [Result.failure] without mutating the
     * settings flow. The attempt is still recorded in [attemptedUpserts].
     */
    val failingPaths: Set<String> = emptySet(),
) : SettingsRepository {
    val flow = MutableStateFlow(initial)

    /** Roots whose upsert succeeded. Mirrors the prior `upserts` field — kept for back-compat. */
    var upserts: MutableList<LibraryRoot> = mutableListOf()

    /** Every upsert call recorded, including ones that returned [Result.failure]. */
    var attemptedUpserts: MutableList<LibraryRoot> = mutableListOf()
    var removals: MutableList<LibraryRoot> = mutableListOf()
    var pluginFolderWrites: MutableList<List<String>> = mutableListOf()
    var markCompleteCalls: MutableList<OnboardingSkipFlags> = mutableListOf()

    /** Alias matching the recording-fake naming used by the Task 7 tests. */
    val upsertedRoots: List<LibraryRoot> get() = upserts
    val pluginFoldersWrites: List<List<String>> get() = pluginFolderWrites

    override fun observe(): Flow<Settings> = flow

    override suspend fun upsertRoot(root: LibraryRoot): Result<Unit> {
        attemptedUpserts += root
        if (root.path in failingPaths) {
            return Result.failure(IllegalStateException("simulated failure for ${root.path}"))
        }
        upserts += root
        flow.value = flow.value.copy(libraryRoots = (flow.value.libraryRoots + root).distinct())
        return Result.success(Unit)
    }

    override suspend fun removeRoot(root: LibraryRoot): Result<Unit> {
        removals += root
        flow.value = flow.value.copy(libraryRoots = flow.value.libraryRoots - root)
        return Result.success(Unit)
    }

    override suspend fun setCloudBucket(bucket: String?): Result<Unit> = Result.success(Unit)

    override suspend fun setSelfContained(
        uuid: ProjectUuid,
        value: Boolean,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun setCacheSettings(settings: BlobCacheSettings): Result<Unit> = Result.success(Unit)

    override suspend fun markFirstRunComplete(skipFlags: OnboardingSkipFlags): Result<Unit> {
        markCompleteCalls += skipFlags
        flow.value =
            flow.value.copy(
                firstRunCompletedAt = Clock.System.now(),
                onboardingSkipped = skipFlags,
            )
        return Result.success(Unit)
    }

    override suspend fun dismissOnboardingPrompt(kind: OnboardingPromptKind): Result<Unit> = Result.success(Unit)

    override suspend fun setPluginFolders(folders: List<String>): Result<Unit> {
        pluginFolderWrites += folders
        flow.value = flow.value.copy(pluginFolders = folders)
        return Result.success(Unit)
    }

    override suspend fun resetFirstRun(): Result<Unit> {
        flow.value =
            flow.value.copy(
                firstRunCompletedAt = null,
                onboardingSkipped = OnboardingSkipFlags(),
            )
        return Result.success(Unit)
    }
}

internal class FakeScanTrigger : ScanTrigger {
    var scanCount: Int = 0

    override fun triggerScan() {
        scanCount++
    }
}
