package com.sketchbook.featureonboarding

import com.sketchbook.repo.LibraryRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest fun setUpMain() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest fun tearDownMain() {
        Dispatchers.resetMain()
    }

    private fun newVm(
        repo: FakeSettingsRepository = FakeSettingsRepository(),
        scan: FakeScanTrigger = FakeScanTrigger(),
    ): OnboardingViewModel = OnboardingViewModel(repo, scan)

    @Test
    fun initialStateIsWelcomeWithDefaultPluginFolders() =
        runTest(mainDispatcher) {
            val vm = newVm()
            val s = vm.state.value
            assertEquals(0, s.currentIndex)
            assertEquals(OnboardingStep.Welcome, s.steps[s.currentIndex])
            assertTrue(s.canContinue, "Welcome should be continuable")
            assertEquals(defaultPluginFolders(), s.pluginFolders)
            assertTrue(s.projectsRoots.isEmpty())
            assertTrue(s.sampleRoots.isEmpty())
        }

    @Test
    fun continueFromWelcomeMovesToProjectsRootsAndDisablesContinue() =
        runTest(mainDispatcher) {
            val vm = newVm()
            vm.dispatch(OnboardingIntent.Continue)
            val s = vm.state.value
            assertEquals(OnboardingStep.ProjectsRoots, s.steps[s.currentIndex])
            assertFalse(s.canContinue, "ProjectsRoots requires at least one entry")
        }

    @Test
    fun addProjectsRootEnablesCanContinueAndDedupes() =
        runTest(mainDispatcher) {
            val vm = newVm()
            vm.dispatch(OnboardingIntent.Continue) // Welcome -> ProjectsRoots
            vm.dispatch(OnboardingIntent.AddProjectsRoot("Z:/Projects"))
            assertTrue(vm.state.value.canContinue)
            assertEquals(listOf("Z:/Projects"), vm.state.value.projectsRoots)

            vm.dispatch(OnboardingIntent.AddProjectsRoot("Z:/Projects"))
            assertEquals(listOf("Z:/Projects"), vm.state.value.projectsRoots, "duplicates should be dropped")
        }

    @Test
    fun removeProjectsRootDisablesCanContinue() =
        runTest(mainDispatcher) {
            val vm = newVm()
            vm.dispatch(OnboardingIntent.Continue)
            vm.dispatch(OnboardingIntent.AddProjectsRoot("Z:/Projects"))
            assertTrue(vm.state.value.canContinue)
            vm.dispatch(OnboardingIntent.RemoveProjectsRoot("Z:/Projects"))
            assertTrue(
                vm.state.value.projectsRoots
                    .isEmpty(),
            )
            assertFalse(vm.state.value.canContinue)
        }

    @Test
    fun continueFromProjectsRootsMovesToSampleRootsWhichIsOptional() =
        runTest(mainDispatcher) {
            val vm = newVm()
            vm.dispatch(OnboardingIntent.Continue)
            vm.dispatch(OnboardingIntent.AddProjectsRoot("Z:/Projects"))
            vm.dispatch(OnboardingIntent.Continue)
            val s = vm.state.value
            assertEquals(OnboardingStep.SampleRoots, s.steps[s.currentIndex])
            assertTrue(s.canContinue, "SampleRoots is optional, canContinue should be true")
        }

    @Test
    fun addAndRemoveSampleRoots() =
        runTest(mainDispatcher) {
            val vm = newVm()
            vm.dispatch(OnboardingIntent.AddSampleRoot("S:/Splice"))
            vm.dispatch(OnboardingIntent.AddSampleRoot("S:/Splice")) // dedupe
            assertEquals(listOf("S:/Splice"), vm.state.value.sampleRoots)
            vm.dispatch(OnboardingIntent.AddSampleRoot("U:/UserSamples"))
            assertEquals(listOf("S:/Splice", "U:/UserSamples"), vm.state.value.sampleRoots)
            vm.dispatch(OnboardingIntent.RemoveSampleRoot("S:/Splice"))
            assertEquals(listOf("U:/UserSamples"), vm.state.value.sampleRoots)
        }

    @Test
    fun addAndRemovePluginFolders() =
        runTest(mainDispatcher) {
            val vm = newVm()
            val before = vm.state.value.pluginFolders
            vm.dispatch(OnboardingIntent.AddPluginFolder("C:/CustomVst3"))
            assertEquals(before + "C:/CustomVst3", vm.state.value.pluginFolders)
            vm.dispatch(OnboardingIntent.AddPluginFolder("C:/CustomVst3")) // dedupe
            assertEquals(before + "C:/CustomVst3", vm.state.value.pluginFolders)
            vm.dispatch(OnboardingIntent.RemovePluginFolder("C:/CustomVst3"))
            assertEquals(before, vm.state.value.pluginFolders)
        }

    @Test
    fun usePluginDefaultsResetsPluginFolders() =
        runTest(mainDispatcher) {
            val vm = newVm()
            vm.dispatch(
                OnboardingIntent.RemovePluginFolder(
                    vm.state.value.pluginFolders
                        .first(),
                ),
            )
            vm.dispatch(OnboardingIntent.AddPluginFolder("C:/Strange"))
            vm.dispatch(OnboardingIntent.UsePluginDefaults)
            assertEquals(defaultPluginFolders(), vm.state.value.pluginFolders)
        }

    @Test
    fun skipIsNoOpOnWelcome() =
        runTest(mainDispatcher) {
            val vm = newVm()
            val before = vm.state.value
            vm.dispatch(OnboardingIntent.Skip)
            assertEquals(before, vm.state.value)
        }

    @Test
    fun skipIsNoOpOnProjectsRoots() =
        runTest(mainDispatcher) {
            val vm = newVm()
            vm.dispatch(OnboardingIntent.Continue) // -> ProjectsRoots
            val before = vm.state.value
            vm.dispatch(OnboardingIntent.Skip)
            assertEquals(before, vm.state.value)
        }

    @Test
    fun skipAdvancesOnSampleRootsWithoutClearingEntered() =
        runTest(mainDispatcher) {
            val vm = newVm()
            vm.dispatch(OnboardingIntent.Continue) // -> ProjectsRoots
            vm.dispatch(OnboardingIntent.AddProjectsRoot("Z:/Projects"))
            vm.dispatch(OnboardingIntent.Continue) // -> SampleRoots
            vm.dispatch(OnboardingIntent.AddSampleRoot("S:/Splice"))
            vm.dispatch(OnboardingIntent.Skip)
            val s = vm.state.value
            assertEquals(OnboardingStep.PluginFolders, s.steps[s.currentIndex])
            assertEquals(listOf("S:/Splice"), s.sampleRoots, "skip must not clear entered samples")
        }

    @Test
    fun skipAdvancesOnPluginFoldersWithoutClearingEntered() =
        runTest(mainDispatcher) {
            val vm = newVm()
            vm.dispatch(OnboardingIntent.Continue) // -> ProjectsRoots
            vm.dispatch(OnboardingIntent.AddProjectsRoot("Z:/Projects"))
            vm.dispatch(OnboardingIntent.Continue) // -> SampleRoots
            vm.dispatch(OnboardingIntent.Continue) // -> PluginFolders
            val foldersBefore = vm.state.value.pluginFolders
            vm.dispatch(OnboardingIntent.Skip)
            val s = vm.state.value
            assertEquals(OnboardingStep.Done, s.steps[s.currentIndex])
            assertEquals(foldersBefore, s.pluginFolders, "skip must not clear plugin folders")
        }

    @Test
    fun skipIsNoOpOnDone() =
        runTest(mainDispatcher) {
            val vm = newVm()
            vm.dispatch(OnboardingIntent.Continue) // -> ProjectsRoots
            vm.dispatch(OnboardingIntent.AddProjectsRoot("Z:/Projects"))
            vm.dispatch(OnboardingIntent.Continue) // -> SampleRoots
            vm.dispatch(OnboardingIntent.Continue) // -> PluginFolders
            vm.dispatch(OnboardingIntent.Continue) // -> Done
            val before = vm.state.value
            assertEquals(OnboardingStep.Done, before.steps[before.currentIndex])
            vm.dispatch(OnboardingIntent.Skip)
            assertEquals(before, vm.state.value)
        }

    // ---------------- Task 7: SkipAllUseDefaults + Finish ----------------

    @Test
    fun skipAllFromSampleRootsPreservesEnteredDataAndJumpsToDone() =
        runTest(mainDispatcher) {
            val vm = newVm()
            vm.dispatch(OnboardingIntent.Continue) // -> ProjectsRoots
            vm.dispatch(OnboardingIntent.AddProjectsRoot("/foo"))
            vm.dispatch(OnboardingIntent.Continue) // -> SampleRoots
            vm.dispatch(OnboardingIntent.AddSampleRoot("/bar"))
            vm.dispatch(OnboardingIntent.SkipAllUseDefaults)
            val s = vm.state.value
            assertEquals(OnboardingStep.Done, s.steps[s.currentIndex])
            assertEquals(listOf("/foo"), s.projectsRoots)
            assertEquals(listOf("/bar"), s.sampleRoots)
            assertTrue(s.pluginFolders.isNotEmpty(), "OS defaults should remain")
            assertTrue(s.canContinue)
        }

    @Test
    fun skipAllUseDefaultsDoesNotPersistAnything() =
        runTest(mainDispatcher) {
            val repo = FakeSettingsRepository()
            val scan = FakeScanTrigger()
            val vm = newVm(repo, scan)
            vm.dispatch(OnboardingIntent.Continue)
            vm.dispatch(OnboardingIntent.AddProjectsRoot("/foo"))
            vm.dispatch(OnboardingIntent.SkipAllUseDefaults)
            advanceUntilIdle()
            assertEquals(emptyList(), repo.attemptedUpserts.toList())
            assertEquals(emptyList(), repo.pluginFolderWrites.toList())
            assertEquals(emptyList(), repo.markCompleteCalls.toList())
            assertEquals(0, scan.scanCount)
        }

    @Test
    fun skipAllFillsPluginFoldersWhenEmpty() =
        runTest(mainDispatcher) {
            val vm = newVm()
            // Strip out every default plugin folder so we can prove SkipAll re-fills.
            vm.state.value.pluginFolders.toList().forEach {
                vm.dispatch(OnboardingIntent.RemovePluginFolder(it))
            }
            assertTrue(
                vm.state.value.pluginFolders
                    .isEmpty(),
            )
            vm.dispatch(OnboardingIntent.Continue)
            vm.dispatch(OnboardingIntent.AddProjectsRoot("/foo"))
            vm.dispatch(OnboardingIntent.SkipAllUseDefaults)
            assertEquals(defaultPluginFolders(), vm.state.value.pluginFolders)
        }

    @Test
    fun skipAllPreservesCustomPluginFolders() =
        runTest(mainDispatcher) {
            val vm = newVm()
            vm.state.value.pluginFolders.toList().forEach {
                vm.dispatch(OnboardingIntent.RemovePluginFolder(it))
            }
            vm.dispatch(OnboardingIntent.AddPluginFolder("C:/MyVst3"))
            vm.dispatch(OnboardingIntent.Continue)
            vm.dispatch(OnboardingIntent.AddProjectsRoot("/foo"))
            vm.dispatch(OnboardingIntent.SkipAllUseDefaults)
            assertEquals(listOf("C:/MyVst3"), vm.state.value.pluginFolders)
        }

    @Test
    fun finishPersistsEachEnteredRootWithCorrectSubtype() =
        runTest(mainDispatcher) {
            val repo = FakeSettingsRepository()
            val scan = FakeScanTrigger()
            val vm = newVm(repo, scan)
            vm.dispatch(OnboardingIntent.Continue) // -> ProjectsRoots
            vm.dispatch(OnboardingIntent.AddProjectsRoot("/projects/A"))
            vm.dispatch(OnboardingIntent.AddProjectsRoot("/projects/B"))
            vm.dispatch(OnboardingIntent.Continue) // -> SampleRoots
            vm.dispatch(OnboardingIntent.AddSampleRoot("/samples/X"))
            vm.dispatch(OnboardingIntent.Continue) // -> PluginFolders
            vm.dispatch(OnboardingIntent.Continue) // -> Done
            vm.dispatch(OnboardingIntent.Finish)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    LibraryRoot.Projects("/projects/A"),
                    LibraryRoot.Projects("/projects/B"),
                    LibraryRoot.UserSamples("/samples/X"),
                ),
                repo.upserts.toList(),
            )
            assertEquals(1, repo.pluginFolderWrites.size)
            assertTrue(repo.pluginFolderWrites.single().isNotEmpty(), "defaults landed")
            assertEquals(1, repo.markCompleteCalls.size)
            assertEquals(false, repo.markCompleteCalls.single().samplesSkipped)
            assertEquals(1, scan.scanCount)
        }

    @Test
    fun finishMarksSamplesSkippedTrueWhenNoSampleRootsEntered() =
        runTest(mainDispatcher) {
            val repo = FakeSettingsRepository()
            val vm = newVm(repo)
            vm.dispatch(OnboardingIntent.Continue)
            vm.dispatch(OnboardingIntent.AddProjectsRoot("/foo"))
            vm.dispatch(OnboardingIntent.SkipAllUseDefaults)
            vm.dispatch(OnboardingIntent.Finish)
            advanceUntilIdle()
            assertEquals(1, repo.markCompleteCalls.size)
            assertEquals(true, repo.markCompleteCalls.single().samplesSkipped)
        }

    @Test
    fun finishIsFailSoftWhenAnUpsertReturnsFailure() =
        runTest(mainDispatcher) {
            val repo = FakeSettingsRepository(failingPaths = setOf("/projects/B"))
            val scan = FakeScanTrigger()
            val vm = newVm(repo, scan)
            vm.dispatch(OnboardingIntent.Continue) // -> ProjectsRoots
            vm.dispatch(OnboardingIntent.AddProjectsRoot("/projects/A"))
            vm.dispatch(OnboardingIntent.AddProjectsRoot("/projects/B"))
            vm.dispatch(OnboardingIntent.AddProjectsRoot("/projects/C"))
            vm.dispatch(OnboardingIntent.Continue) // -> SampleRoots
            vm.dispatch(OnboardingIntent.Continue) // -> PluginFolders
            vm.dispatch(OnboardingIntent.Continue) // -> Done
            vm.dispatch(OnboardingIntent.Finish)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    LibraryRoot.Projects("/projects/A"),
                    LibraryRoot.Projects("/projects/B"),
                    LibraryRoot.Projects("/projects/C"),
                ),
                repo.attemptedUpserts.toList(),
                "every upsert should have been attempted",
            )
            assertEquals(
                listOf(
                    LibraryRoot.Projects("/projects/A"),
                    LibraryRoot.Projects("/projects/C"),
                ),
                repo.upserts.toList(),
                "B failed and didn't land; A and C still made it through",
            )
            assertEquals(1, repo.pluginFolderWrites.size)
            assertEquals(1, repo.markCompleteCalls.size)
            assertEquals(1, scan.scanCount)
        }

    @Test
    fun finishTriggersScanExactlyOnceEvenWhenCalledTwice() =
        runTest(mainDispatcher) {
            val repo = FakeSettingsRepository()
            val scan = FakeScanTrigger()
            val vm = newVm(repo, scan)
            vm.dispatch(OnboardingIntent.Continue)
            vm.dispatch(OnboardingIntent.AddProjectsRoot("/foo"))
            vm.dispatch(OnboardingIntent.SkipAllUseDefaults)
            vm.dispatch(OnboardingIntent.Finish)
            advanceUntilIdle()
            vm.dispatch(OnboardingIntent.Finish)
            advanceUntilIdle()
            assertEquals(1, scan.scanCount)
            assertEquals(1, repo.markCompleteCalls.size, "Finish must be idempotent")
        }

    @Test
    fun continueAtDoneIsNoOp() =
        runTest(mainDispatcher) {
            val vm = newVm()
            vm.dispatch(OnboardingIntent.Continue) // -> ProjectsRoots
            vm.dispatch(OnboardingIntent.AddProjectsRoot("Z:/Projects"))
            vm.dispatch(OnboardingIntent.Continue) // -> SampleRoots
            vm.dispatch(OnboardingIntent.Continue) // -> PluginFolders
            vm.dispatch(OnboardingIntent.Continue) // -> Done
            val before = vm.state.value
            vm.dispatch(OnboardingIntent.Continue)
            assertSame(before, vm.state.value, "Continue at Done must be a no-op")
        }
}
