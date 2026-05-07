package com.sketchbook.desktop.repo

import com.sketchbook.core.ProjectUuid
import com.sketchbook.repo.ExternalKind
import com.sketchbook.repo.LibraryRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun roundtripsLibraryRoots() = runTest {
        val a = LibraryRoot.Projects("/a")
        val b = LibraryRoot.External(path = "/b", alias = "splice", kind = ExternalKind.Splice)
        val first = PreferencesSettingsRepository(node, Dispatchers.Unconfined)
        first.upsertRoot(a).getOrThrow()
        first.upsertRoot(b).getOrThrow()

        val second = PreferencesSettingsRepository(node, Dispatchers.Unconfined)
        val roots = second.observe().first().libraryRoots
        assertEquals(listOf(a, b), roots)
    }

    @Test
    fun roundtripsBucket() = runTest {
        val first = PreferencesSettingsRepository(node, Dispatchers.Unconfined)
        first.setCloudBucket("sketchbook-prod").getOrThrow()

        val second = PreferencesSettingsRepository(node, Dispatchers.Unconfined)
        val s = second.observe().first()
        assertEquals("sketchbook-prod", s.cloudBucket)
    }

    @Test
    fun clearsLegacyServiceAccountJsonOnInit() = runTest {
        // Seed the legacy key directly to mimic an upgrading user.
        node.put("cloud_credential_json", "{\"type\":\"service_account\"}")
        node.flush()
        assertEquals("{\"type\":\"service_account\"}", node.get("cloud_credential_json", null))

        PreferencesSettingsRepository(node, Dispatchers.Unconfined)

        assertEquals(null, node.get("cloud_credential_json", null))
    }

    @Test
    fun roundtripsSelfContainedSet() = runTest {
        val uuid = ProjectUuid("11111111-2222-3333-4444-555555555555")
        val first = PreferencesSettingsRepository(node, Dispatchers.Unconfined)
        first.setSelfContained(uuid, value = true).getOrThrow()

        val mid = PreferencesSettingsRepository(node, Dispatchers.Unconfined)
        assertEquals(setOf(uuid), mid.observe().first().selfContainedProjects)

        first.setSelfContained(uuid, value = false).getOrThrow()
        val after = PreferencesSettingsRepository(node, Dispatchers.Unconfined)
        assertEquals(emptySet(), after.observe().first().selfContainedProjects)
    }

    @Test
    fun removeRootDropsByPath() = runTest {
        val a = LibraryRoot.Projects("/a")
        val b = LibraryRoot.UserSamples("/b")
        val first = PreferencesSettingsRepository(node, Dispatchers.Unconfined)
        first.upsertRoot(a).getOrThrow()
        first.upsertRoot(b).getOrThrow()
        first.removeRoot(a).getOrThrow()

        val second = PreferencesSettingsRepository(node, Dispatchers.Unconfined)
        assertEquals(listOf(b), second.observe().first().libraryRoots)
    }
}
