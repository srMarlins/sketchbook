package com.sketchbook.desktop

import com.sketchbook.repo.BlobCacheSettings
import com.sketchbook.repo.OnboardingSkipFlags
import com.sketchbook.repo.Settings
import com.sketchbook.repo.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

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

    private class FakeSettings(firstRunCompletedAt: kotlin.time.Instant?) : SettingsRepository {
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
        override suspend fun upsertRoot(root: com.sketchbook.repo.LibraryRoot) = Result.success(Unit)
        override suspend fun removeRoot(root: com.sketchbook.repo.LibraryRoot) = Result.success(Unit)
        override suspend fun setCloudCredential(serviceAccountJson: String?) = Result.success(Unit)
        override suspend fun setCloudBucket(bucket: String?) = Result.success(Unit)
        override suspend fun setSelfContained(uuid: com.sketchbook.core.ProjectUuid, value: Boolean) = Result.success(Unit)
        override suspend fun setCacheSettings(settings: BlobCacheSettings) = Result.success(Unit)
        override suspend fun markFirstRunComplete(skipFlags: OnboardingSkipFlags) = Result.success(Unit)
        override suspend fun dismissOnboardingPrompt(kind: com.sketchbook.repo.OnboardingPromptKind) = Result.success(Unit)
        override suspend fun setPluginFolders(folders: List<String>) = Result.success(Unit)
    }
}
