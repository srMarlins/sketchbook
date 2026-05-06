package com.sketchbook.sync

import com.sketchbook.core.ProjectUuid
import com.sketchbook.core.SnapshotKind
import com.sketchbook.core.SnapshotRev
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Z3 quick-capture: pressing Ctrl/Cmd+Shift+S in the desktop UI must write a Named snapshot of
 * the current bytes regardless of the dirty flag — it's the manual escape hatch for "name this
 * take now". The use case orchestrates the call; the actual blob upload + manifest write live in
 * the pipeline implementation. We assert the use case routes through the pipeline with the
 * supplied label and the [SnapshotKind.Named] kind so blob upload + journaling happen via the
 * normal path.
 */
class ForceSnapshotUseCaseTest {

    private val uuid = ProjectUuid("01H-test-uuid")

    @Test
    fun forceSnapshotWritesANamedRowEvenIfWorkingTreeIsClean() = runTest {
        val pipeline = FakeForceSnapshotPipeline()
        val useCase = ForceSnapshotUseCase(pipeline)

        val result = useCase.invoke(uuid, label = "demo for jay")

        assertTrue(result.isSuccess, "force-snapshot result was failure: ${result.exceptionOrNull()}")
        assertEquals(1, pipeline.recordedSnapshots.size)
        assertEquals("named", pipeline.recordedSnapshots[0].kind)
        assertEquals("demo for jay", pipeline.recordedSnapshots[0].label)
        assertEquals(uuid, pipeline.recordedSnapshots[0].uuid)
    }

    @Test
    fun blankLabelIsRejectedWithoutHittingThePipeline() = runTest {
        val pipeline = FakeForceSnapshotPipeline()
        val useCase = ForceSnapshotUseCase(pipeline)

        val result = useCase.invoke(uuid, label = "   ")

        assertTrue(result.isFailure, "blank label should be rejected")
        assertEquals(0, pipeline.recordedSnapshots.size, "pipeline must not be called for blank labels")
    }
}

private class FakeForceSnapshotPipeline : ForceSnapshotPipeline {

    data class Recorded(val uuid: ProjectUuid, val kind: String, val label: String?)

    val recordedSnapshots: MutableList<Recorded> = mutableListOf()

    override suspend fun recordForcedNamed(
        uuid: ProjectUuid,
        label: String,
    ): Result<SnapshotRev> {
        recordedSnapshots += Recorded(
            uuid = uuid,
            kind = SnapshotKind.Named.name.lowercase(),
            label = label,
        )
        return Result.success(SnapshotRev(recordedSnapshots.size.toLong()))
    }
}
