package com.sketchbook.desktop

import com.sketchbook.core.ProjectUuid
import com.sketchbook.repo.BlobCacheSettings
import com.sketchbook.repo.LibraryRoot
import com.sketchbook.repo.OnboardingPromptKind
import com.sketchbook.repo.OnboardingSkipFlags
import com.sketchbook.repo.Settings
import com.sketchbook.repo.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

class LaunchGateTest {

    @Test
    fun `null firstRunCompletedAt routes to Onboarding`() = runTest {
        val gate = LaunchGate(FakeSettings(firstRunCompletedAt = null))
        assertEquals(LaunchDecision.Onboarding, gate.resolve())
    }

    @Test
    fun `non-null firstRunCompletedAt routes to MainApp`() = runTest {
        val gate = LaunchGate(FakeSettings(firstRunCompletedAt = Clock.System.now()))
        assertEquals(LaunchDecision.MainApp, gate.resolve())
    }

    @Test
    fun `observe flips from Onboarding to MainApp when firstRunCompletedAt is set`() = runTest {
        val settings = MutableFakeSettings(firstRunCompletedAt = null)
        val gate = LaunchGate(settings)

        val first = gate.observe().first()
        assertEquals(LaunchDecision.Onboarding, first)

        settings.setFirstRunCompletedAt(Clock.System.now())

        // Re-collect — second emission should now be MainApp.
        val second = gate.observe().first()
        assertEquals(LaunchDecision.MainApp, second)
    }

    private class FakeSettings(firstRunCompletedAt: Instant?) : SettingsRepository {
        private val flow = MutableStateFlow(
            Settings(
                libraryRoots = emptyList(),
                cloudConfigured = false,
                selfContainedProjects = emptySet(),
                cacheSettings = BlobCacheSettings.Default,
                firstRunCompletedAt = firstRunCompletedAt,
            ),
        )

        override fun observe(): Flow<Settings> = flow
        override suspend fun upsertRoot(root: LibraryRoot) = Result.success(Unit)
        override suspend fun removeRoot(root: LibraryRoot) = Result.success(Unit)
        override suspend fun setCloudCredential(serviceAccountJson: String?) = Result.success(Unit)
        override suspend fun setCloudBucket(bucket: String?) = Result.success(Unit)
        override suspend fun setSelfContained(uuid: ProjectUuid, value: Boolean) = Result.success(Unit)
        override suspend fun setCacheSettings(settings: BlobCacheSettings) = Result.success(Unit)
        override suspend fun markFirstRunComplete(skipFlags: OnboardingSkipFlags) = Result.success(Unit)
        override suspend fun dismissOnboardingPrompt(kind: OnboardingPromptKind) = Result.success(Unit)
        override suspend fun setPluginFolders(folders: List<String>) = Result.success(Unit)
        override suspend fun resetFirstRun() = Result.success(Unit)
    }

    /**
     * Mutable variant — exposes a `setFirstRunCompletedAt` mutator that re-emits via the
     * internal [MutableStateFlow], so the live flow path through [LaunchGate.observe] can be
     * exercised end to end.
     */
    private class MutableFakeSettings(firstRunCompletedAt: Instant?) : SettingsRepository {
        private val flow = MutableStateFlow(
            Settings(
                libraryRoots = emptyList(),
                cloudConfigured = false,
                selfContainedProjects = emptySet(),
                cacheSettings = BlobCacheSettings.Default,
                firstRunCompletedAt = firstRunCompletedAt,
            ),
        )

        fun setFirstRunCompletedAt(value: Instant?) {
            flow.value = flow.value.copy(firstRunCompletedAt = value)
        }

        override fun observe(): Flow<Settings> = flow
        override suspend fun upsertRoot(root: LibraryRoot) = Result.success(Unit)
        override suspend fun removeRoot(root: LibraryRoot) = Result.success(Unit)
        override suspend fun setCloudCredential(serviceAccountJson: String?) = Result.success(Unit)
        override suspend fun setCloudBucket(bucket: String?) = Result.success(Unit)
        override suspend fun setSelfContained(uuid: ProjectUuid, value: Boolean) = Result.success(Unit)
        override suspend fun setCacheSettings(settings: BlobCacheSettings) = Result.success(Unit)
        override suspend fun markFirstRunComplete(skipFlags: OnboardingSkipFlags) = Result.success(Unit)
        override suspend fun dismissOnboardingPrompt(kind: OnboardingPromptKind) = Result.success(Unit)
        override suspend fun setPluginFolders(folders: List<String>) = Result.success(Unit)
        override suspend fun resetFirstRun() = Result.success(Unit)
    }
}
