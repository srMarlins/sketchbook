# Proposals / Needs Attention / Journal — UX redesign

Date: 2026-05-06
Status: design — approved by user; ready for implementation plan

## Problem

All three "queue" screens are read-mostly today:

- **Proposals** lists pending and resolved cards. Each pending card shows a raw `• type {jsonObject}` action dump and a single Approve/Reject pair. No batching, no preview, no human-readable summary, no effect/snackbar wiring.
- **Needs Attention** has two flat sections (Mac-imported, Missing samples). Per-row Acknowledge / Apply / Dismiss only. With 99+ findings on a real library, the user has to click each one. No filter, no bulk, no project grouping.
- **Journal** is a flat list of one-line rows with numeric project ids and no undo, even though every recorded action carries enough state to invert it.

The user is a music producer with ~1,628 chaotic Ableton projects. The unifying need is *clear the queue fast, drill into the gnarly ones.*

## Design pillars

1. **Group by what determines the action**, not by metadata. Action category for proposals; confidence bucket for missing samples; day for journal.
2. **Bulk action lives in the group header.** One click clears a sub-group; the count is in the button.
3. **Optimistic + 5-second undo snackbar** on every bulk action. No confirmation modals.
4. **Right-side detail pane** for drill-in. Stays open across row clicks.
5. **Layer onto existing components, no new color tokens, no MVI library.**

## Information architecture

### Proposals

Page header: title · total count chip · source filter chips (All / MCP / Code / User) · project search.

Body: collapsible groups by **action category**:

| Group        | Includes                              | Bulk action          |
| ------------ | ------------------------------------- | -------------------- |
| Organization | move, rename, archive/unarchive       | Approve all / Reject all |
| Metadata     | tag edits                             | Approve all / Reject all |
| Repair       | sample relinks proposed by the system | Approve all / Reject all |

Inside a group, sort by target project so consecutive rows on the same project cluster. Row content: human-readable summary (`Move *kick.als* → \`Live/Stems/\``), small actor chip (mcp/code/user), age (`3m ago`), per-row `[✓] [✗]` icon buttons. Click body → detail pane. Resolved items collapsed at the bottom (`12 resolved this session — show`).

### Needs Attention

Page header: title · total findings count · project search.

Body: two collapsible top groups.

**Mac-imported** — sub-grouped by parent folder (`/Sketches/Old Mac Imports/ · 14 projects · [Acknowledge folder]`). Each row: project name + bad-path-count badge.

**Missing samples** — three confidence buckets:

| Bucket               | Cardinality drives bulk default | Bulk action     |
| -------------------- | ------------------------------- | --------------- |
| Auto-match           | high-confidence single match    | Apply all       |
| Multiple candidates  | needs review                    | (none — Review) |
| No candidates        | unrecoverable                   | Dismiss all     |

Truncation banner at bottom of Missing samples when `missingSamplesTruncated`.

### Journal

Page header: title · action-type filter chips (All / Move / Rename / Archive / Tag / Lock / Conflict) · project search · date-range chip (Today / 7d / 30d / All).

Body: day-grouped sections (Today, Yesterday, May 4, ...). Inside each day, newest first.

Row content: action verb, project **name** (not numeric id), before→after inline (`Renamed *foo.als* → *foo-final.als*`), timestamp right-aligned, `[Undo]` button on invertible rows. Click body → detail pane.

Bulk undo lives in a sticky toolbar that appears when filters narrow the view (`[Undo 8 renames in this view]`). Lock and PushConflict rows are excluded from any bulk-undo action — they're informational records, not invertible state changes.

**Undo semantics.** Journal is append-only. Undo writes a *new* compensating entry, never deletes the original. Both stay in the timeline.

**Safety check.** Before performing an inverse action, verify on-disk state matches the entry's `pathAfter` / `nameAfter`. If it doesn't (file was moved/renamed again later), refuse with `file is no longer at the recorded location — undo skipped`. This avoids reasoning about chained-state edge cases at the cost of a few false negatives.

## Bulk action mechanics

**Trigger.** Group/sub-group header button. Count is in the label: `[Approve 22]`, `[Apply 47]`, `[Acknowledge 14]`, `[Undo 8]`. No confirmation modal.

**Execution.** Single new intent per screen: `BulkApprove(ids)`, `BulkReject(ids)`, `BulkAck(projectIds)`, `BulkApplyAutoMatch(findings)`, `BulkDismiss(findings)`, `BulkUndo(sequences)`. The state holder loops the existing per-item repo calls. Per-item failures don't abort the batch. Result: `BulkResult(successes, failures)`.

**Snackbar.** Single bottom snackbar with 5-second countdown: `Approved 22 proposals. Undo · 4s`. Click `Undo` → inverse batch fires. A second bulk action while a snackbar is live replaces it; the older one auto-commits. Partial failure variant: `Approved 20 of 22. 2 failed. View · Undo` — `View` opens the detail pane filtered to failed items.

**Optimistic UI.** Affected rows fade to ~40% opacity with a small `applying…` spinner badge on bulk fire. Repo flow updates resolve them. Undo within the window snaps them back.

## Detail pane

Right-docked, ~420 px wide, slides in over the list (list dimmed but clickable). Closes on Escape, top-right `×`, or any nav target. **Stays open as you click successive rows** — content swaps, pane doesn't close. This is the affordance for "compare a few before deciding."

| Type             | Content                                                                                                                                            | Footer                       |
| ---------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------- |
| Proposal         | Rationale, rendered action list (one human-readable line per action; `Show JSON` toggle for raw payload), submittedAt, actor chip, target project link | `[Approve]` / `[Reject]`     |
| Mac-import       | Project name, full path, labeled rows for `macPathsCount` + `projectInfoMissing`, `[Open in Finder]` (stub if not wired)                          | `[Acknowledge]`              |
| Missing sample   | Missing path (mono), all candidates (not just top 5) with size + path, each with `[Use this]`                                                      | `[Dismiss]`                  |
| Journal entry    | Before/after diff (two-column path diff for moves, tag-list diff for tag edits), timestamp, sequence, project link                                | `[Open project]` `[Undo this]` |

Empty state: quiet placeholder (`Select a row for details`) using existing `EmptyState`.

## Components

**Reused as-is.** `Surface`, `Badge`, `Tag`, `Button`, `EmptyState`, `RowItem`, `Text`, `PageHeader`, `Pill`, `LockBadge`.

**New, in `ui-shared`.**

- `CollapsibleGroupHeader` — count chip, title, trailing slot for bulk buttons, expand/collapse chevron. Replaces ad-hoc bold `Text` headers across all three screens.
- `FilterChipRow` — horizontal scroll, single- or multi-select chips. Used for source / action-type / date filters.
- `DetailPane` — right-docked container with header (title + close), scrollable body slot, sticky footer slot. Plumbed via `LocalDetailPaneController` so any screen can push content.
- `BulkUndoSnackbar` — bottom snackbar with countdown, undo button, partial-failure variant. Wraps `Surface`.

**New utility.** `humanReadable(action: ProposalAction): String` and `humanReadable(action: ActionRecord): AnnotatedString` — formats actions for row display using existing typography tokens (mono for paths, italic for filenames).

**No new color tokens.** Only existing `pinGreen` / `pinOrange` / `accentAction` / `accentSecondary`.

**No repository changes for v1.** Bulk loops existing per-item methods at the state-holder layer. State holder emits a single optimistic delta and collects results.

## Testing

- Extend `ProposalsStateHolderTest`, `NeedsAttentionStateHolderTest`, `JournalStateHolderTest` with cases for each bulk intent, partial-failure path, and undo-of-bulk.
- Extend `ProposalApplyTest` and the missing-sample integration test with one bulk-apply test per screen (real DB, real repo).
- No screenshot tests, no MVI library.
- Manual desktop verification on the golden path per screen as the work goes — no batch checkpoint.

## Out of scope

- Full undo-chain reasoning (we refuse rather than chase moved files).
- Per-action repository transactions for bulk (loop existing methods; revisit if perf bites).
- New action types in `ProposalAction` or `ActionRecord`.
- Any `Open in Finder` integration beyond a stub if it's not already wired.
