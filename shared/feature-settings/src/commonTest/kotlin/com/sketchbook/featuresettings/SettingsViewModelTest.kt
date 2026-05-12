package com.sketchbook.featuresettings

import app.cash.turbine.test
import com.sketchbook.auth.AuthSession
import com.sketchbook.auth.AuthState
import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.UserId
import com.sketchbook.repo.BlobCacheSettings
import com.sketchbook.repo.ExternalKind
import com.sketchbook.repo.LibraryRoot
import com.sketchbook.repo.OnboardingPromptKind
import com.sketchbook.repo.OnboardingSkipFlags
import com.sketchbook.repo.Settings
import com.sketchbook.repo.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest fun setUpMain() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest fun tearDownMain() {
        Dispatchers.resetMain()
    }

    private val initial =
        Settings(
            libraryRoots =
                listOf(
                    LibraryRoot.Projects("Z:/User/audio/Projects"),
                    LibraryRoot.External("S:/Splice", alias = "splice", kind = ExternalKind.Splice),
                ),
            selfContainedProjects = emptySet(),
        )

    private class FakeRepo(
        initial: Settings,
    ) : SettingsRepository {
        val flow = MutableStateFlow(initial)
        var lastUpsert: LibraryRoot? = null
        var lastRemove: LibraryRoot? = null
        var lastSelfContained: Pair<ProjectUuid, Boolean>? = null

        override fun observe(): Flow<Settings> = flow

        override suspend fun upsertRoot(root: LibraryRoot) {
            lastUpsert = root
            flow.value = flow.value.copy(libraryRoots = (flow.value.libraryRoots + root).distinct())
        }

        override suspend fun removeRoot(root: LibraryRoot) {
            lastRemove = root
            flow.value = flow.value.copy(libraryRoots = flow.value.libraryRoots - root)
        }

        override suspend fun setSelfContained(
            uuid: ProjectUuid,
            value: Boolean,
        ) {
            lastSelfContained = uuid to value
            val updated = if (value) flow.value.selfContainedProjects + uuid else flow.value.selfContainedProjects - uuid
            flow.value = flow.value.copy(selfContainedProjects = updated)
        }

        override suspend fun setCacheSettings(settings: BlobCacheSettings) {
            flow.value = flow.value.copy(cacheSettings = settings)
        }

        override suspend fun markFirstRunComplete(skipFlags: OnboardingSkipFlags) {
            flow.value =
                flow.value.copy(
                    firstRunCompletedAt = Clock.System.now(),
                    onboardingSkipped = skipFlags,
                )
        }

        override suspend fun dismissOnboardingPrompt(kind: OnboardingPromptKind) {
            val current = flow.value.onboardingSkipped
            val updated =
                when (kind) {
                    OnboardingPromptKind.Samples -> current.copy(samplesPromptDismissed = true)
                }
            flow.value = flow.value.copy(onboardingSkipped = updated)
        }

        override suspend fun setPluginFolders(folders: List<String>) {
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

    private class FakeAuthSession(
        initial: AuthState = AuthState.SignedOut,
    ) : AuthSession {
        private val _state = MutableStateFlow(initial)
        override val state: StateFlow<AuthState> = _state
        var lastSignIn: Result<AuthState.SignedIn>? = null
        var signOuts = 0

        override suspend fun signIn(): Result<AuthState.SignedIn> {
            val r =
                lastSignIn ?: Result.success(
                    AuthState.SignedIn(UserId("test"), "test@example.com"),
                )
            if (r.isSuccess) _state.value = r.getOrThrow()
            return r
        }

        override suspend fun signOut() {
            signOuts++
            _state.value = AuthState.SignedOut
        }

        override suspend fun idToken(): String = "fake"
    }

    @Test
    fun stateMirrorsRepo() =
        runTest(mainDispatcher) {
            val vm = SettingsViewModel(FakeRepo(initial), FakeAuthSession())
            vm.state.test {
                var s = awaitItem()
                while (s.libraryRoots.isEmpty()) s = awaitItem()
                assertEquals(2, s.libraryRoots.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun addRootRoutesToRepoAndEmitsSavedEffect() =
        runTest(mainDispatcher) {
            val repo = FakeRepo(initial)
            val vm = SettingsViewModel(repo, FakeAuthSession())
            val newRoot = LibraryRoot.UserSamples("Z:/User/audio/Samples")
            vm.effects.test {
                vm.dispatch(SettingsViewModel.Intent.AddRoot(newRoot))
                val effect = awaitItem()
                assertTrue(effect is SettingsViewModel.Effect.Saved)
                assertEquals(newRoot, repo.lastUpsert)
            }
        }

    @Test
    fun toggleSelfContainedUpdatesRepoAndEmits() =
        runTest(mainDispatcher) {
            val repo = FakeRepo(initial)
            val vm = SettingsViewModel(repo, FakeAuthSession())
            val uuid = ProjectUuid("01H-test")
            vm.effects.test {
                vm.dispatch(SettingsViewModel.Intent.ToggleSelfContained(uuid, true))
                val effect = awaitItem()
                assertTrue(effect is SettingsViewModel.Effect.Saved)
                assertEquals(uuid to true, repo.lastSelfContained)
            }
        }

    @Test
    fun signInIntentFlipsStateToSignedIn() =
        runTest(mainDispatcher) {
            val auth = FakeAuthSession()
            val vm = SettingsViewModel(FakeRepo(initial), auth)
            vm.state.test {
                var s = awaitItem()
                while (s.auth !is AuthState.SignedOut) s = awaitItem()
                vm.dispatch(SettingsViewModel.Intent.SignIn)
                advanceUntilIdle()
                var next = awaitItem()
                while (next.auth !is AuthState.SignedIn) next = awaitItem()
                val signedIn = next.auth
                check(signedIn is AuthState.SignedIn)
                assertEquals("test@example.com", signedIn.email)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun signOutIntentFlipsStateToSignedOut() =
        runTest(mainDispatcher) {
            val auth =
                FakeAuthSession(
                    initial = AuthState.SignedIn(UserId("u1"), "u1@example.com"),
                )
            val vm = SettingsViewModel(FakeRepo(initial), auth)
            vm.state.test {
                var s = awaitItem()
                while (s.auth !is AuthState.SignedIn) s = awaitItem()
                vm.dispatch(SettingsViewModel.Intent.SignOut)
                advanceUntilIdle()
                var next = awaitItem()
                while (next.auth !is AuthState.SignedOut) next = awaitItem()
                assertEquals(1, auth.signOuts)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
