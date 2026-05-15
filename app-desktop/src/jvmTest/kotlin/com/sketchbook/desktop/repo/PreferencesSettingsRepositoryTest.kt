package com.sketchbook.desktop.repo

import com.sketchbook.core.ProjectUuid
import com.sketchbook.repo.ExternalKind
import com.sketchbook.repo.LibraryRoot
import com.sketchbook.repo.OnboardingPromptKind
import com.sketchbook.repo.OnboardingSkipFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Paths
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip tests for [PreferencesSettingsRepository]. Each test pins a unique node under the
 * user-root prefs tree (so concurrent test runs and Gradle re-runs don't collide), exercises a
 * mutation, then constructs a *second* repository over the same node and asserts the second
 * instance reads back what the first wrote — the load-bearing property for "settings survive
 * restart".
 */
class PreferencesSettingsRepositoryTest {
    private val node: Preferences = Preferences.userRoot().node("sketchbook-test-${UUID.randomUUID()}")

    @AfterTest
    fun tearDown() {
        // removeNode() invalidates the receiver so further calls would throw; tests are the
        // only thing using this node, so removing the leaf is sufficient + safe.
        runCatching { node.removeNode() }
    }

    @Test
    fun roundtripsLibraryRoots() =
        runTest {
            val a = LibraryRoot.Projects("/a")
            val b = LibraryRoot.External(path = "/b", alias = "splice", kind = ExternalKind.Splice)
            val first = PreferencesSettingsRepository(node, Dispatchers.Unconfined)
            first.upsertRoot(a)
            first.upsertRoot(b)

            val second = PreferencesSettingsRepository(node, Dispatchers.Unconfined)
            val roots = second.observe().first().libraryRoots
            assertEquals(listOf(a, b), roots)
        }

    @Test
    fun clearsLegacyServiceAccountJsonOnInit() =
        runTest {
            // Seed the legacy key directly to mimic an upgrading user.
            node.put("cloud_credential_json", "{\"type\":\"service_account\"}")
            node.flush()
            assertEquals("{\"type\":\"service_account\"}", node.get("cloud_credential_json", null))

            PreferencesSettingsRepository(node, Dispatchers.Unconfined)

            assertEquals(null, node.get("cloud_credential_json", null))
        }

    @Test
    fun roundtripsSelfContainedSet() =
        runTest {
            val uuid = ProjectUuid("11111111-2222-3333-4444-555555555555")
            val first = PreferencesSettingsRepository(node, Dispatchers.Unconfined)
            first.setSelfContained(uuid, value = true)

            val mid = PreferencesSettingsRepository(node, Dispatchers.Unconfined)
            assertEquals(setOf(uuid), mid.observe().first().selfContainedProjects)

            first.setSelfContained(uuid, value = false)
            val after = PreferencesSettingsRepository(node, Dispatchers.Unconfined)
            assertEquals(emptySet(), after.observe().first().selfContainedProjects)
        }

    @Test
    fun `markFirstRunComplete persists timestamp and skip flags`() =
        runTest {
            val repo = PreferencesSettingsRepository(node, Dispatchers.Unconfined)
            val flags = OnboardingSkipFlags(samplesSkipped = true)

            repo.markFirstRunComplete(flags)

            val snapshot = repo.observe().first()
            assertNotNull(snapshot.firstRunCompletedAt)
            assertEquals(flags, snapshot.onboardingSkipped)

            // Survives restart.
            val reloaded = PreferencesSettingsRepository(node, Dispatchers.Unconfined)
            val reloadedSnapshot = reloaded.observe().first()
            assertNotNull(reloadedSnapshot.firstRunCompletedAt)
            assertEquals(flags, reloadedSnapshot.onboardingSkipped)
        }

    @Test
    fun `dismissOnboardingPrompt flips the matching flag`() =
        runTest {
            val repo = PreferencesSettingsRepository(node, Dispatchers.Unconfined)
            repo.markFirstRunComplete(OnboardingSkipFlags(samplesSkipped = true))

            repo.dismissOnboardingPrompt(OnboardingPromptKind.Samples)

            val snapshot = repo.observe().first()
            assertTrue(snapshot.onboardingSkipped.samplesPromptDismissed)
            assertTrue(snapshot.onboardingSkipped.samplesSkipped, "dismiss should not clobber other flags")
        }

    @Test
    fun `setPluginFolders persists list and observe re-emits`() =
        runTest {
            val repo = PreferencesSettingsRepository(node, Dispatchers.Unconfined)
            val raw = listOf("/Users/me/Plugins", "/Library/Audio/Plug-Ins/VST3")
            val expected =
                raw.map {
                    Paths
                        .get(it)
                        .toAbsolutePath()
                        .normalize()
                        .toString()
                }

            repo.setPluginFolders(raw)

            assertEquals(expected, repo.observe().first().pluginFolders)
        }

    @Test
    fun `resetFirstRun clears firstRunCompletedAt and onboarding skip flags but keeps roots`() =
        runTest {
            val repo = PreferencesSettingsRepository(node, Dispatchers.Unconfined)
            repo.upsertRoot(LibraryRoot.Projects("/foo"))
            repo.markFirstRunComplete(OnboardingSkipFlags(samplesSkipped = true))
            repo.dismissOnboardingPrompt(OnboardingPromptKind.Samples)

            repo.resetFirstRun()

            val s = repo.observe().first()
            assertNull(s.firstRunCompletedAt)
            assertFalse(s.onboardingSkipped.samplesSkipped)
            assertFalse(s.onboardingSkipped.samplesPromptDismissed)
            // Roots survive — only the onboarding gate is reset.
            assertEquals(listOf(LibraryRoot.Projects("/foo")), s.libraryRoots)
        }

    @Test
    fun removeRootDropsByPath() =
        runTest {
            val a = LibraryRoot.Projects("/a")
            val b = LibraryRoot.UserSamples("/b")
            val first = PreferencesSettingsRepository(node, Dispatchers.Unconfined)
            first.upsertRoot(a)
            first.upsertRoot(b)
            first.removeRoot(a)

            val second = PreferencesSettingsRepository(node, Dispatchers.Unconfined)
            assertEquals(listOf(b), second.observe().first().libraryRoots)
        }
}
