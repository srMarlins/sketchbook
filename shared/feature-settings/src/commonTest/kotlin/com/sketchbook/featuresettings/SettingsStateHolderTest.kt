package com.sketchbook.featuresettings

import app.cash.turbine.test
import com.sketchbook.core.ProjectUuid
import com.sketchbook.repo.BlobCacheSettings
import com.sketchbook.repo.ExternalKind
import com.sketchbook.repo.LibraryRoot
import com.sketchbook.repo.Settings
import com.sketchbook.repo.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsStateHolderTest {

    private val initial = Settings(
        libraryRoots = listOf(
            LibraryRoot.Projects("Z:/User/audio/Projects"),
            LibraryRoot.External("S:/Splice", alias = "splice", kind = ExternalKind.Splice),
        ),
        cloudConfigured = false,
        selfContainedProjects = emptySet(),
    )

    private class FakeRepo(initial: Settings) : SettingsRepository {
        val flow = MutableStateFlow(initial)
        var lastUpsert: LibraryRoot? = null
        var lastRemove: LibraryRoot? = null
        var lastCredential: String? = null
        var lastSelfContained: Pair<ProjectUuid, Boolean>? = null
        override fun observe(): Flow<Settings> = flow
        override suspend fun upsertRoot(root: LibraryRoot): Result<Unit> {
            lastUpsert = root
            flow.value = flow.value.copy(libraryRoots = (flow.value.libraryRoots + root).distinct())
            return Result.success(Unit)
        }
        override suspend fun removeRoot(root: LibraryRoot): Result<Unit> {
            lastRemove = root
            flow.value = flow.value.copy(libraryRoots = flow.value.libraryRoots - root)
            return Result.success(Unit)
        }
        override suspend fun setCloudCredential(serviceAccountJson: String?): Result<Unit> {
            lastCredential = serviceAccountJson
            flow.value = flow.value.copy(cloudConfigured = serviceAccountJson != null)
            return Result.success(Unit)
        }
        override suspend fun setSelfContained(uuid: ProjectUuid, value: Boolean): Result<Unit> {
            lastSelfContained = uuid to value
            val updated = if (value) flow.value.selfContainedProjects + uuid else flow.value.selfContainedProjects - uuid
            flow.value = flow.value.copy(selfContainedProjects = updated)
            return Result.success(Unit)
        }
        override suspend fun setCacheSettings(settings: BlobCacheSettings): Result<Unit> {
            flow.value = flow.value.copy(cacheSettings = settings)
            return Result.success(Unit)
        }
    }

    @Test
    fun stateMirrorsRepo() = runTest {
        val holder = SettingsStateHolder(FakeRepo(initial), backgroundScope)
        holder.state.test {
            var s = awaitItem()
            while (s.libraryRoots.isEmpty()) s = awaitItem()
            assertEquals(2, s.libraryRoots.size)
            assertEquals(false, s.cloudConfigured)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun addRootRoutesToRepoAndEmitsSavedEffect() = runTest {
        val repo = FakeRepo(initial)
        val holder = SettingsStateHolder(repo, backgroundScope)
        val newRoot = LibraryRoot.UserSamples("Z:/User/audio/Samples")
        holder.effects.test {
            holder.dispatch(SettingsStateHolder.Intent.AddRoot(newRoot))
            val effect = awaitItem()
            assertTrue(effect is SettingsStateHolder.Effect.Saved)
            assertEquals(newRoot, repo.lastUpsert)
        }
    }

    @Test
    fun setCloudCredentialUpdatesRepoAndEmits() = runTest {
        val repo = FakeRepo(initial)
        val holder = SettingsStateHolder(repo, backgroundScope)
        holder.effects.test {
            holder.dispatch(SettingsStateHolder.Intent.SetCloudCredential("{\"type\": \"service_account\"}"))
            val effect = awaitItem()
            assertTrue(effect is SettingsStateHolder.Effect.Saved)
            assertEquals("cloud", effect.key)
            assertEquals("{\"type\": \"service_account\"}", repo.lastCredential)
        }
    }

    @Test
    fun toggleSelfContainedUpdatesRepoAndEmits() = runTest {
        val repo = FakeRepo(initial)
        val holder = SettingsStateHolder(repo, backgroundScope)
        val uuid = ProjectUuid("01H-test")
        holder.effects.test {
            holder.dispatch(SettingsStateHolder.Intent.ToggleSelfContained(uuid, true))
            val effect = awaitItem()
            assertTrue(effect is SettingsStateHolder.Effect.Saved)
            assertEquals(uuid to true, repo.lastSelfContained)
        }
    }
}
