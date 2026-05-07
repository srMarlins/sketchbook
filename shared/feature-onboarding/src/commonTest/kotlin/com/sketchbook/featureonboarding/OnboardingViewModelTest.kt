package com.sketchbook.featureonboarding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
    fun initialStateIsWelcomeWithDefaultPluginFolders() = runTest(mainDispatcher) {
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
    fun continueFromWelcomeMovesToProjectsRootsAndDisablesContinue() = runTest(mainDispatcher) {
        val vm = newVm()
        vm.dispatch(OnboardingIntent.Continue)
        val s = vm.state.value
        assertEquals(OnboardingStep.ProjectsRoots, s.steps[s.currentIndex])
        assertFalse(s.canContinue, "ProjectsRoots requires at least one entry")
    }

    @Test
    fun addProjectsRootEnablesCanContinueAndDedupes() = runTest(mainDispatcher) {
        val vm = newVm()
        vm.dispatch(OnboardingIntent.Continue) // Welcome -> ProjectsRoots
        vm.dispatch(OnboardingIntent.AddProjectsRoot("Z:/Projects"))
        assertTrue(vm.state.value.canContinue)
        assertEquals(listOf("Z:/Projects"), vm.state.value.projectsRoots)

        vm.dispatch(OnboardingIntent.AddProjectsRoot("Z:/Projects"))
        assertEquals(listOf("Z:/Projects"), vm.state.value.projectsRoots, "duplicates should be dropped")
    }

    @Test
    fun removeProjectsRootDisablesCanContinue() = runTest(mainDispatcher) {
        val vm = newVm()
        vm.dispatch(OnboardingIntent.Continue)
        vm.dispatch(OnboardingIntent.AddProjectsRoot("Z:/Projects"))
        assertTrue(vm.state.value.canContinue)
        vm.dispatch(OnboardingIntent.RemoveProjectsRoot("Z:/Projects"))
        assertTrue(vm.state.value.projectsRoots.isEmpty())
        assertFalse(vm.state.value.canContinue)
    }

    @Test
    fun continueFromProjectsRootsMovesToSampleRootsWhichIsOptional() = runTest(mainDispatcher) {
        val vm = newVm()
        vm.dispatch(OnboardingIntent.Continue)
        vm.dispatch(OnboardingIntent.AddProjectsRoot("Z:/Projects"))
        vm.dispatch(OnboardingIntent.Continue)
        val s = vm.state.value
        assertEquals(OnboardingStep.SampleRoots, s.steps[s.currentIndex])
        assertTrue(s.canContinue, "SampleRoots is optional, canContinue should be true")
    }

    @Test
    fun addAndRemoveSampleRoots() = runTest(mainDispatcher) {
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
    fun addAndRemovePluginFolders() = runTest(mainDispatcher) {
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
    fun usePluginDefaultsResetsPluginFolders() = runTest(mainDispatcher) {
        val vm = newVm()
        vm.dispatch(OnboardingIntent.RemovePluginFolder(vm.state.value.pluginFolders.first()))
        vm.dispatch(OnboardingIntent.AddPluginFolder("C:/Strange"))
        vm.dispatch(OnboardingIntent.UsePluginDefaults)
        assertEquals(defaultPluginFolders(), vm.state.value.pluginFolders)
    }

    @Test
    fun skipIsNoOpOnWelcome() = runTest(mainDispatcher) {
        val vm = newVm()
        val before = vm.state.value
        vm.dispatch(OnboardingIntent.Skip)
        assertEquals(before, vm.state.value)
    }

    @Test
    fun skipIsNoOpOnProjectsRoots() = runTest(mainDispatcher) {
        val vm = newVm()
        vm.dispatch(OnboardingIntent.Continue) // -> ProjectsRoots
        val before = vm.state.value
        vm.dispatch(OnboardingIntent.Skip)
        assertEquals(before, vm.state.value)
    }

    @Test
    fun skipAdvancesOnSampleRootsWithoutClearingEntered() = runTest(mainDispatcher) {
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
    fun skipAdvancesOnPluginFoldersWithoutClearingEntered() = runTest(mainDispatcher) {
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
    fun skipIsNoOpOnDone() = runTest(mainDispatcher) {
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

    @Test
    fun continueAtDoneIsNoOp() = runTest(mainDispatcher) {
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
