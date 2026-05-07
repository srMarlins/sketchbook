package com.sketchbook.desktop.inbox.screenshot

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.sketchbook.core.ProjectId
import com.sketchbook.desktop.inbox.InboxContent
import com.sketchbook.featurejournal.JournalViewModel
import com.sketchbook.featureneedsattention.NeedsAttentionViewModel
import com.sketchbook.featureproposals.ProposalsViewModel
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.MacImportFinding
import com.sketchbook.repo.MissingSampleFinding
import com.sketchbook.repo.Proposal
import com.sketchbook.repo.ProposalAction
import com.sketchbook.repo.SampleCandidate
import com.sketchbook.uishared.theme.AppTheme
import io.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.time.Instant

/**
 * Roborazzi capture for the unified Inbox surface — three columns (Suggested / Issues / History)
 * side-by-side. Mirrors the canonical [com.sketchbook.featureprojects.screenshot.ProjectListScreenshots]
 * pattern: hand-built sample states feed the stateless `InboxContent`, sidestepping the three
 * Metro-DI ViewModels.
 *
 * Width 1400 / height 900 — Inbox is wider than ProjectList because three list columns share the
 * viewport plus a docked detail-pane lane (we don't open a detail here, but the threshold-based
 * layout keeps spacing realistic).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class InboxScreenshots {
    @Test
    fun loaded_state() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            setContent {
                AppTheme {
                    InboxContent(
                        proposalsState = sampleProposalsState(),
                        attentionState = sampleAttentionState(),
                        journalState = sampleJournalState(),
                        onApprove = {},
                        onReject = {},
                        onBulkApprove = {},
                        onBulkReject = {},
                        onProposalsSearch = {},
                        onSourceFilter = {},
                        onAttentionSearch = {},
                        onRepair = {},
                        onBulkRepair = {},
                        onBulkApply = {},
                        onBulkDismiss = {},
                        onJournalSearch = {},
                        onActionFilter = {},
                        onDateRange = {},
                        onUndoOne = {},
                        onBulkUndo = {},
                    )
                }
            }
            onRoot().captureRoboImage("build/roborazzi/inbox_loaded.png")
        }
}

// Frozen "now" so submittedAt / journal timestamps render with stable relative-time strings.
private val NOW = Instant.parse("2026-05-07T12:00:00Z")
private const val DAY_MS = 24L * 60 * 60 * 1000

private fun sampleProposalsState(): ProposalsViewModel.State {
    val nameById =
        mapOf(
            101L to "midnight bloom",
            102L to "paper lanterns",
            103L to "old porch",
        )
    val moveAction1 =
        ProposalAction(
            type = "MoveProject",
            args =
                JsonObject(
                    mapOf(
                        "project_id" to JsonPrimitive("101"),
                        "name" to JsonPrimitive("midnight bloom"),
                        "path" to JsonPrimitive("Users/srm/Music/Ableton/Sketches/midnight bloom.als"),
                        "to" to JsonPrimitive("Users/srm/Music/Ableton/Active/midnight bloom.als"),
                    ),
                ),
        )
    val moveAction2 =
        ProposalAction(
            type = "MoveProject",
            args =
                JsonObject(
                    mapOf(
                        "project_id" to JsonPrimitive("102"),
                        "name" to JsonPrimitive("paper lanterns"),
                        "path" to JsonPrimitive("Users/srm/Music/Ableton/Sketches/paper lanterns.als"),
                        "to" to JsonPrimitive("Users/srm/Music/Ableton/Active/paper lanterns.als"),
                    ),
                ),
        )
    val tagAction =
        ProposalAction(
            type = "SetTags",
            args =
                JsonObject(
                    mapOf(
                        "project_id" to JsonPrimitive("103"),
                        "name" to JsonPrimitive("old porch"),
                        "path" to JsonPrimitive("Users/srm/Music/Ableton/Forgotten/old porch.als"),
                    ),
                ),
        )
    val moveProposalA =
        Proposal(
            proposalId = "p-move-1",
            actor = "sketchbook",
            rationale = "Auto-organize: promote to Active (recent edits, high effort).",
            actions = listOf(moveAction1),
            submittedAt = NOW.minusMs(45 * 60 * 1000L),
        )
    val moveProposalB =
        Proposal(
            proposalId = "p-move-2",
            actor = "sketchbook",
            rationale = "Auto-organize: promote to Active.",
            actions = listOf(moveAction2),
            submittedAt = NOW.minusMs(2 * 60 * 60 * 1000L),
        )
    val tagProposal =
        Proposal(
            proposalId = "p-tag-1",
            actor = "code",
            rationale = "claude-write: tag forgotten gems for review.",
            actions = listOf(tagAction),
            submittedAt = NOW.minusMs(20 * 60 * 1000L),
        )
    val pending = listOf(moveProposalA, moveProposalB, tagProposal)
    val groups =
        listOf(
            ProposalsViewModel.ProposalGroup(
                category = ProposalsViewModel.ProposalCategory.Move,
                proposals = listOf(moveProposalA, moveProposalB),
            ),
            ProposalsViewModel.ProposalGroup(
                category = ProposalsViewModel.ProposalCategory.Tag,
                proposals = listOf(tagProposal),
            ),
        )
    return ProposalsViewModel.State(
        pending = pending,
        resolved = emptyList(),
        groups = groups,
        projectNamesById = nameById,
        loading = false,
    )
}

private fun sampleAttentionState(): NeedsAttentionViewModel.State {
    val macFinding =
        MacImportFinding(
            projectId = ProjectId(201),
            path = "Users/srm/Music/Ableton/Imported/cassette demo.als",
            name = "cassette demo",
            parentDir = "Imported",
            macPathsCount = 7,
            projectInfoMissing = false,
        )
    val macEntry = NeedsAttentionViewModel.MacEntry(finding = macFinding, isProjectBoundary = true)

    val missingFinding =
        MissingSampleFinding(
            projectId = ProjectId(202),
            projectPath = "Users/srm/Music/Ableton/Active/harbour.als",
            projectName = "harbour",
            missingPath = "Samples/Loops/Drums/909_kick_dry.wav",
            autoMatch =
                SampleCandidate(
                    path = "Users/srm/Music/Samples/Drums/909_kick_dry.wav",
                    filename = "909_kick_dry.wav",
                    sizeBytes = 124_000L,
                ),
            candidates = emptyList(),
        )
    val missingEntry =
        NeedsAttentionViewModel.MissingEntry(
            finding = missingFinding,
            isProjectBoundary = true,
        )
    val buckets =
        NeedsAttentionViewModel.MissingByConfidence(
            autoMatch = listOf(missingEntry),
        )

    return NeedsAttentionViewModel.State(
        macImports = listOf(macFinding),
        macEntries = listOf(macEntry),
        missingSamples = listOf(missingFinding),
        missingByConfidence = buckets,
        missingSamplesTotal = 1,
        loading = false,
    )
}

private fun sampleJournalState(): JournalViewModel.State {
    val today = NOW
    val yesterday = NOW.minusMs(DAY_MS)
    val rowToday1 =
        journalRow(
            ts = today.minusMs(15 * 60 * 1000L),
            projectId = 101,
            projectName = "midnight bloom",
            action =
                ActionRecord.Move(
                    pathBefore = "Users/srm/Music/Ableton/Sketches/midnight bloom.als",
                    pathAfter = "Users/srm/Music/Ableton/Active/midnight bloom.als",
                ),
        )
    val rowToday2 =
        journalRow(
            ts = today.minusMs(2 * 60 * 60 * 1000L),
            projectId = 103,
            projectName = "old porch",
            action =
                ActionRecord.SetTags(
                    before = emptyList(),
                    after = listOf("forgotten-gem", "review"),
                ),
        )
    val rowYesterday =
        journalRow(
            ts = yesterday.minusMs(4 * 60 * 60 * 1000L),
            projectId = 102,
            projectName = "paper lanterns",
            action =
                ActionRecord.Rename(
                    nameBefore = "untitled jam 03",
                    nameAfter = "paper lanterns",
                ),
        )

    val daysList =
        listOf(
            JournalViewModel.DayGroup(label = "Today", rows = listOf(rowToday1, rowToday2)),
            JournalViewModel.DayGroup(label = "Yesterday", rows = listOf(rowYesterday)),
        )
    val rowsFlat = daysList.flatMap { it.rows }
    return JournalViewModel.State(
        rows = rowsFlat,
        days = daysList,
        invertibleEntries = rowsFlat.map { it.entry },
        loading = false,
    )
}

private fun journalRow(
    ts: Instant,
    projectId: Long,
    projectName: String,
    action: ActionRecord,
): JournalViewModel.JournalRow =
    JournalViewModel.JournalRow(
        entry =
            JournalEntry(
                timestamp = ts,
                projectId = ProjectId(projectId),
                action = action,
                sequence = null,
                actor = "user",
                projectName = projectName,
            ),
        projectName = projectName,
    )

private fun Instant.minusMs(ms: Long): Instant = Instant.fromEpochMilliseconds(toEpochMilliseconds() - ms)
