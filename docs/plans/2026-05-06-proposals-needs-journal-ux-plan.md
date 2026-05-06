# Proposals / Needs Attention / Journal — UX implementation plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make the Proposals, Needs Attention, and Journal screens useful at scale (99+ items): grouped by what determines the user's action, bulk-actionable from group headers, with a 5-second undo snackbar and a right-side detail pane for drill-in.

**Architecture:** Add four small shared components (`CollapsibleGroupHeader`, `FilterChipRow`, `BulkUndoSnackbar`, `DetailPane`) and a `humanReadable` formatter. Extend each feature's `StateHolder` with bulk intents that loop existing per-item repository calls — no new repository surface. Refactor each `Screen.kt` to a header + grouped sections + per-row override + detail-pane slot, mirroring the `ProjectListScreen` detail-panel pattern already wired in `RootContent.kt`. Journal undo synthesises inverse actions through the existing `ProjectRepository.move/rename/archive/setTags` methods, which already append their own compensating journal entries.

**Tech Stack:** Kotlin Multiplatform · Compose Multiplatform · existing `ui-shared` paper-feel design tokens · Turbine + kotlinx.coroutines.test for state-holder tests · existing `tests/integration` JVM module for end-to-end coverage.

**Driving design doc:** `docs/plans/2026-05-06-proposals-needs-journal-ux-design.md`

**Reuses-not-adds (per `feedback_no_unnecessary_libs.md`):** plain Kotlin `StateFlow` + sealed-class intents; no MVI library; no screenshot tests; no animation libraries beyond what Compose ships.

**Visual restraint (per `feedback_color_restraint.md`):** zero new color tokens. Reuse `pinGreen`, `pinOrange`, `accentAction`, `accentSecondary`, `tintBlue/Rose/Sage/Cream`, ink/rule tokens from `AppColors`.

**Layering rule (per `feedback_layer_dont_redesign.md`):** existing components stay; new components are additive. Don't touch `ProjectListScreen`, `NotebookSidebar`, `PaperPage`, `PageHeader` internals.

**Testing rule (per `feedback_app_owns_scan.md` / `feedback_no_batch_checkpoints.md`):** drive through every task; verify visually in the desktop app at the end. Per-task: write the failing test, watch it fail, implement, watch it pass, commit.

---

## Phase 0 — Foundation utilities

These are the dependencies for every screen. Build them first, in order. Each lives in `:shared:ui-shared` (commonMain) unless noted.

### Task 0.1: `humanReadable(ProposalAction)` formatter

**Files:**
- Create: `shared/uishared/src/commonMain/kotlin/com/sketchbook/uishared/format/HumanReadable.kt`
- Test: `shared/uishared/src/commonTest/kotlin/com/sketchbook/uishared/format/HumanReadableProposalActionTest.kt`

The ProposalAction wire format is `(type: String, args: JsonObject)`. Render the seven catalogued action types (`move`, `rename`, `archive`, `unarchive`, `set_tags`, `apply_sample_match`, `dismiss_sample`) as a one-line `AnnotatedString`. Unknown types render as `<type> <args>` so we never lose information.

**Step 1: Write the failing test**

```kotlin
package com.sketchbook.uishared.format

import com.sketchbook.repo.ProposalAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class HumanReadableProposalActionTest {

    private fun obj(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test fun renderMove() {
        val a = ProposalAction("move", obj("from" to "Sketches/foo.als", "to" to "Live/foo.als"))
        assertEquals("Move foo.als → Live/", humanReadable(a).text)
    }

    @Test fun renderRename() {
        val a = ProposalAction("rename", obj("from" to "wip.als", "to" to "wip-v2.als"))
        assertEquals("Rename wip.als → wip-v2.als", humanReadable(a).text)
    }

    @Test fun renderArchive() {
        val a = ProposalAction("archive", obj("project" to "Old Sketch"))
        assertEquals("Archive Old Sketch", humanReadable(a).text)
    }

    @Test fun renderSetTags() {
        val a = ProposalAction("set_tags", obj("project" to "foo", "tags" to "techno,wip"))
        assertEquals("Tag foo: techno, wip", humanReadable(a).text)
    }

    @Test fun renderApplySampleMatch() {
        val a = ProposalAction("apply_sample_match", obj(
            "missing" to "/Volumes/old/k.wav",
            "candidate" to "Library/Drums/k.wav",
        ))
        assertEquals("Relink k.wav → Library/Drums/", humanReadable(a).text)
    }

    @Test fun unknownTypeRendersTypeAndArgs() {
        val a = ProposalAction("invent", obj("x" to "1"))
        assertEquals("invent {\"x\":\"1\"}", humanReadable(a).text)
    }
}
```

**Step 2: Run, confirm fail**

```
./gradlew :shared:ui-shared:jvmTest --tests com.sketchbook.uishared.format.HumanReadableProposalActionTest
```

Expected: `Unresolved reference: humanReadable`.

**Step 3: Implement**

```kotlin
// shared/uishared/src/commonMain/kotlin/com/sketchbook/uishared/format/HumanReadable.kt
package com.sketchbook.uishared.format

import androidx.compose.ui.text.AnnotatedString
import com.sketchbook.repo.ProposalAction
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

fun humanReadable(action: ProposalAction): AnnotatedString {
    fun s(key: String): String? =
        (action.args[key] as? JsonPrimitive)?.contentOrNull
    val text = when (action.type) {
        "move" -> {
            val from = s("from").orEmpty()
            val to = s("to").orEmpty()
            "Move ${filenameOf(from)} → ${parentDirOf(to)}/"
        }
        "rename" -> "Rename ${s("from").orEmpty()} → ${s("to").orEmpty()}"
        "archive" -> "Archive ${s("project").orEmpty()}"
        "unarchive" -> "Unarchive ${s("project").orEmpty()}"
        "set_tags" -> "Tag ${s("project").orEmpty()}: ${s("tags").orEmpty().split(',').joinToString(", ") { it.trim() }}"
        "apply_sample_match" -> {
            val missing = s("missing").orEmpty()
            val candidate = s("candidate").orEmpty()
            "Relink ${filenameOf(missing)} → ${parentDirOf(candidate)}/"
        }
        "dismiss_sample" -> "Dismiss missing sample ${filenameOf(s("missing").orEmpty())}"
        else -> "${action.type} ${action.args}"
    }
    return AnnotatedString(text)
}

private fun filenameOf(path: String): String =
    path.substringAfterLast('/').ifEmpty { path }

private fun parentDirOf(path: String): String {
    val idx = path.lastIndexOf('/')
    return if (idx <= 0) "" else path.substring(0, idx)
}
```

**Step 4: Run, confirm pass**

```
./gradlew :shared:ui-shared:jvmTest --tests com.sketchbook.uishared.format.HumanReadableProposalActionTest
```

Expected: PASS.

**Step 5: Commit**

```bash
git add shared/uishared/src/commonMain/kotlin/com/sketchbook/uishared/format/HumanReadable.kt \
        shared/uishared/src/commonTest/kotlin/com/sketchbook/uishared/format/HumanReadableProposalActionTest.kt
git commit -m "feat(uishared): humanReadable(ProposalAction) formatter"
```

---

### Task 0.2: `humanReadable(ActionRecord)` for journal rows

**Files:**
- Modify: `shared/uishared/src/commonMain/kotlin/com/sketchbook/uishared/format/HumanReadable.kt`
- Test: `shared/uishared/src/commonTest/kotlin/com/sketchbook/uishared/format/HumanReadableActionRecordTest.kt`

Each `ActionRecord` variant has typed `before` / `after`. Render before→after inline. Lock and PushConflict are informational and render their causal context.

**Step 1: Write the failing test**

```kotlin
package com.sketchbook.uishared.format

import com.sketchbook.repo.ActionRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class HumanReadableActionRecordTest {

    @Test fun moveRendersFromTo() {
        val a = ActionRecord.Move(pathBefore = "Sketches/foo.als", pathAfter = "Live/foo.als")
        assertEquals("Moved foo.als — Sketches/ → Live/", humanReadable(a).text)
    }

    @Test fun renameRendersBothNames() {
        val a = ActionRecord.Rename(nameBefore = "wip.als", nameAfter = "final.als")
        assertEquals("Renamed wip.als → final.als", humanReadable(a).text)
    }

    @Test fun archivedRendersDirection() {
        val a = ActionRecord.Archive(wasArchived = false, isArchived = true)
        assertEquals("Archived", humanReadable(a).text)
    }

    @Test fun unarchivedRendersDirection() {
        val a = ActionRecord.Archive(wasArchived = true, isArchived = false)
        assertEquals("Unarchived", humanReadable(a).text)
    }

    @Test fun setTagsRendersDiff() {
        val a = ActionRecord.SetTags(before = listOf("a"), after = listOf("a", "b"))
        assertEquals("Tags: a → a, b", humanReadable(a).text)
    }

    @Test fun forceTakeLockRendersPriorOwner() {
        val a = ActionRecord.ForceTakeLock(priorOwnerHostName = "studio-mac", priorExpiresAtMs = null)
        assertEquals("Force-took lock from studio-mac", humanReadable(a).text)
    }

    @Test fun pushConflictRendersRevs() {
        val a = ActionRecord.PushConflict(ourRev = 5, theirRev = 7)
        assertEquals("Push conflict (ours rev 5 vs theirs rev 7)", humanReadable(a).text)
    }
}
```

**Step 2: Run, confirm fail.**

**Step 3: Implement** — append to `HumanReadable.kt`:

```kotlin
import com.sketchbook.repo.ActionRecord

fun humanReadable(action: ActionRecord): AnnotatedString {
    val text = when (action) {
        is ActionRecord.Move -> "Moved ${filenameOf(action.pathAfter)} — ${parentDirOf(action.pathBefore)}/ → ${parentDirOf(action.pathAfter)}/"
        is ActionRecord.Rename -> "Renamed ${action.nameBefore} → ${action.nameAfter}"
        is ActionRecord.Archive -> if (action.isArchived) "Archived" else "Unarchived"
        is ActionRecord.SetTags -> "Tags: ${action.before.joinToString(", ").ifEmpty { "(none)" }} → ${action.after.joinToString(", ").ifEmpty { "(none)" }}"
        is ActionRecord.ForceTakeLock -> "Force-took lock from ${action.priorOwnerHostName ?: "unknown host"}"
        is ActionRecord.PushConflict -> "Push conflict (ours rev ${action.ourRev} vs theirs rev ${action.theirRev})"
    }
    return AnnotatedString(text)
}
```

**Step 4: Run, confirm pass.**

**Step 5: Commit:**

```bash
git add shared/uishared/src/commonMain/kotlin/com/sketchbook/uishared/format/HumanReadable.kt \
        shared/uishared/src/commonTest/kotlin/com/sketchbook/uishared/format/HumanReadableActionRecordTest.kt
git commit -m "feat(uishared): humanReadable(ActionRecord) for journal rows"
```

---

### Task 0.3: `CollapsibleGroupHeader` component

**Files:**
- Create: `shared/uishared/src/commonMain/kotlin/com/sketchbook/uishared/components/CollapsibleGroupHeader.kt`

No state-holder logic to test. This is a pure visual component — verify by manual desktop run at the end of the phase.

**Step 1: Implement**

```kotlin
package com.sketchbook.uishared.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sketchbook.uishared.theme.AppTheme

@Composable
fun CollapsibleGroupHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = AppTheme.spacing.xs, horizontal = AppTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        ProvideContentColor(AppTheme.colors.inkMuted) {
            Text(if (expanded) "▾" else "▸", style = AppTheme.typography.mono)
        }
        ProvideContentColor(AppTheme.colors.inkPrimary) {
            Text(title, style = AppTheme.typography.bodyEmphasis)
        }
        Badge(color = AppTheme.colors.tintCream) {
            ProvideContentColor(AppTheme.colors.inkSecondary) {
                Text(count.toString(), style = AppTheme.typography.caption)
            }
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (actions != null) actions()
        }
    }
}
```

**Step 2: Commit:**

```bash
git add shared/uishared/src/commonMain/kotlin/com/sketchbook/uishared/components/CollapsibleGroupHeader.kt
git commit -m "feat(uishared): CollapsibleGroupHeader component"
```

---

### Task 0.4: `FilterChipRow` component

**Files:**
- Create: `shared/uishared/src/commonMain/kotlin/com/sketchbook/uishared/components/FilterChipRow.kt`

Renders a horizontal row of chips. Selected chip is filled with `accentSoft`; unselected is bordered.

**Step 1: Implement**

```kotlin
package com.sketchbook.uishared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.sketchbook.uishared.theme.AppTheme

data class FilterChipOption<T>(val value: T, val label: String, val count: Int? = null)

@Composable
fun <T> FilterChipRow(
    options: List<FilterChipOption<T>>,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (opt in options) {
            val isSelected = opt.value == selected
            val shape = RoundedCornerShape(AppTheme.spacing.cornerSmall)
            val bg = if (isSelected) AppTheme.colors.accentSoft else AppTheme.colors.surfaceCard
            val fg = if (isSelected) AppTheme.colors.inkPrimary else AppTheme.colors.inkSecondary
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(bg)
                    .border(1.dp(), AppTheme.colors.ruleLine, shape)
                    .clickable { onSelected(opt.value) }
                    .padding(horizontal = AppTheme.spacing.sm, vertical = AppTheme.spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProvideContentColor(fg) {
                    Text(opt.label, style = AppTheme.typography.body)
                    if (opt.count != null) Text("(${opt.count})", style = AppTheme.typography.caption)
                }
            }
        }
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(toFloat())
```

(That `Int.dp()` extension is just to keep the file self-contained — drop it if there's already `androidx.compose.ui.unit.dp` imported.)

**Step 2: Commit:**

```bash
git add shared/uishared/src/commonMain/kotlin/com/sketchbook/uishared/components/FilterChipRow.kt
git commit -m "feat(uishared): FilterChipRow component"
```

---

### Task 0.5: `BulkUndoSnackbar` component

**Files:**
- Create: `shared/uishared/src/commonMain/kotlin/com/sketchbook/uishared/components/BulkUndoSnackbar.kt`
- Test: `shared/uishared/src/commonTest/kotlin/com/sketchbook/uishared/components/BulkUndoSnackbarStateTest.kt`

Two parts: a stateless composable plus a small `BulkUndoSnackbarState` holder that owns the countdown. The state is testable in isolation; the composable just renders state + remaining seconds.

**Step 1: Write the failing test**

```kotlin
package com.sketchbook.uishared.components

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BulkUndoSnackbarStateTest {

    @Test fun showStartsFiveSecondCountdown() = runTest {
        val state = BulkUndoSnackbarState(this)
        state.show("Approved 22 proposals", onUndo = {})
        assertEquals(5, state.secondsRemaining.value)
        advanceTimeBy(2_500)
        assertEquals(3, state.secondsRemaining.value)
    }

    @Test fun expiresAfterFiveSecondsAndCallsOnExpire() = runTest {
        var expired = false
        val state = BulkUndoSnackbarState(this)
        state.show("x", onUndo = {}, onExpire = { expired = true })
        advanceTimeBy(5_100)
        assertNull(state.current.value)
        assertTrue(expired)
    }

    @Test fun secondShowReplacesFirstAndCommitsIt() = runTest {
        var firstExpired = false
        val state = BulkUndoSnackbarState(this)
        state.show("first", onUndo = {}, onExpire = { firstExpired = true })
        advanceTimeBy(2_000)
        state.show("second", onUndo = {})
        assertTrue(firstExpired)
        assertEquals("second", state.current.value?.message)
        assertEquals(5, state.secondsRemaining.value)
    }

    @Test fun undoFiresCallbackAndDismisses() = runTest {
        var undone = false
        val state = BulkUndoSnackbarState(this)
        state.show("x", onUndo = { undone = true })
        state.undo()
        assertTrue(undone)
        assertNull(state.current.value)
    }
}
```

**Step 2: Run, confirm fail.**

**Step 3: Implement**

```kotlin
package com.sketchbook.uishared.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sketchbook.uishared.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BulkUndoSnackbarState(private val scope: CoroutineScope) {
    data class Visible(
        val message: String,
        val onUndo: () -> Unit,
        val onExpire: (() -> Unit)?,
    )
    private val _current = MutableStateFlow<Visible?>(null)
    val current: StateFlow<Visible?> = _current
    private val _secondsRemaining = MutableStateFlow(0)
    val secondsRemaining: StateFlow<Int> = _secondsRemaining
    private var job: Job? = null

    fun show(message: String, onUndo: () -> Unit, onExpire: (() -> Unit)? = null) {
        commitInFlight()
        _current.value = Visible(message, onUndo, onExpire)
        _secondsRemaining.value = 5
        job = scope.launch {
            repeat(5) {
                delay(1_000)
                _secondsRemaining.value = _secondsRemaining.value - 1
            }
            val v = _current.value
            _current.value = null
            v?.onExpire?.invoke()
        }
    }

    fun undo() {
        val v = _current.value ?: return
        job?.cancel()
        _current.value = null
        v.onUndo()
    }

    private fun commitInFlight() {
        val v = _current.value ?: return
        job?.cancel()
        _current.value = null
        v.onExpire?.invoke()
    }
}

@Composable
fun BulkUndoSnackbar(state: BulkUndoSnackbarState, modifier: Modifier = Modifier) {
    val current by state.current.collectAsState()
    val seconds by state.secondsRemaining.collectAsState()
    val v = current ?: return
    Surface(
        color = AppTheme.colors.surfaceCard,
        elevation = androidx.compose.ui.unit.Dp(8f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
            modifier = Modifier.fillMaxWidth().padding(AppTheme.spacing.sm),
        ) {
            ProvideContentColor(AppTheme.colors.inkPrimary) {
                Text(v.message, style = AppTheme.typography.body, modifier = Modifier.weight(1f))
            }
            ProvideContentColor(AppTheme.colors.inkMuted) {
                Text("${seconds}s", style = AppTheme.typography.caption)
            }
            Button(onClick = { state.undo() }, variant = ButtonVariant.Ghost) {
                Text("Undo")
            }
        }
    }
}
```

**Step 4: Run, confirm pass.**

**Step 5: Commit.**

```bash
git add shared/uishared/src/commonMain/kotlin/com/sketchbook/uishared/components/BulkUndoSnackbar.kt \
        shared/uishared/src/commonTest/kotlin/com/sketchbook/uishared/components/BulkUndoSnackbarStateTest.kt
git commit -m "feat(uishared): BulkUndoSnackbar with 5s countdown + tests"
```

---

### Task 0.6: `DetailPane` container

**Files:**
- Create: `shared/uishared/src/commonMain/kotlin/com/sketchbook/uishared/components/DetailPane.kt`

A right-docked panel with header (title + close), scrollable body, sticky footer slot. Width fixed at 420 dp. The `ProjectListScreen` already uses an analogous pattern; we want a reusable widget so the three queue screens don't reinvent it.

**Step 1: Implement**

```kotlin
package com.sketchbook.uishared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.theme.AppTheme

@Composable
fun DetailPane(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable (() -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .width(420.dp)
            .fillMaxHeight()
            .background(AppTheme.colors.surfaceCard),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AppTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        ) {
            ProvideContentColor(AppTheme.colors.inkPrimary) {
                Text(title, style = AppTheme.typography.bodyEmphasis, modifier = Modifier.weight(1f))
            }
            Box(modifier = Modifier.clickable(onClick = onDismiss).padding(AppTheme.spacing.xs)) {
                ProvideContentColor(AppTheme.colors.inkMuted) { Text("✕") }
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(AppTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
            ) { body() }
        }
        if (footer != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppTheme.colors.surfaceSunken)
                    .padding(AppTheme.spacing.md),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
            ) { footer() }
        }
    }
}

@Composable
fun DetailPaneEmpty(message: String = "Select a row for details", modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(420.dp)
            .fillMaxHeight()
            .background(AppTheme.colors.surfaceCard),
        contentAlignment = Alignment.Center,
    ) {
        ProvideContentColor(AppTheme.colors.inkMuted) {
            Text(message, style = AppTheme.typography.body)
        }
    }
}
```

**Step 2: Commit.**

```bash
git add shared/uishared/src/commonMain/kotlin/com/sketchbook/uishared/components/DetailPane.kt
git commit -m "feat(uishared): DetailPane right-docked container"
```

---

## Phase 1 — Proposals

### Task 1.1: Bulk + filter intents on `ProposalsStateHolder`

**Files:**
- Modify: `shared/feature-proposals/src/commonMain/kotlin/com/sketchbook/featureproposals/ProposalsStateHolder.kt`
- Test: `shared/feature-proposals/src/commonTest/kotlin/com/sketchbook/featureproposals/ProposalsStateHolderTest.kt`

Add:
- `Intent.BulkApprove(ids: List<String>)` and `Intent.BulkReject(ids: List<String>)`.
- `Intent.SetSourceFilter(SourceFilter)` and `Intent.SetSearch(String)`.
- `State.sourceFilter`, `State.search`, `State.groups: List<ProposalGroup>` (computed).
- `Effect.BulkApproved(ids, failures)` and `Effect.BulkRejected(ids, failures)` — drives the snackbar in `RootContent`.

Action category derivation — given a proposal with N actions, classify by the *first* action's type for v1 (proposals are usually homogeneous; if not, the first action wins):

| Action types in proposal              | Category     |
| ------------------------------------- | ------------ |
| move / rename / archive / unarchive   | Organization |
| set_tags                              | Metadata     |
| apply_sample_match / dismiss_sample   | Repair       |
| anything else                         | Organization (fallback) |

**Step 1: Write the failing test (extend the existing test class — load it first, append):**

```kotlin
@Test fun bulkApprovesAllSpecifiedIds() = runTest {
    val repo = InMemoryProposalsRepository(initial = listOf(p("a"), p("b"), p("c")))
    val holder = ProposalsStateHolder(repo, backgroundScope)
    holder.dispatch(ProposalsStateHolder.Intent.BulkApprove(listOf("a", "b")))
    holder.effects.test {
        val effect = awaitItem()
        assertTrue(effect is ProposalsStateHolder.Effect.BulkApproved)
        assertEquals(listOf("a", "b"), effect.successIds)
        assertEquals(emptyList<String>(), effect.failureIds)
    }
}

@Test fun bulkRejectIsolatesFailuresButContinues() = runTest {
    val repo = InMemoryProposalsRepository(
        initial = listOf(p("a"), p("b")),
        failOn = setOf("b"),
    )
    val holder = ProposalsStateHolder(repo, backgroundScope)
    holder.dispatch(ProposalsStateHolder.Intent.BulkReject(listOf("a", "b")))
    holder.effects.test {
        val effect = awaitItem()
        assertTrue(effect is ProposalsStateHolder.Effect.BulkRejected)
        assertEquals(listOf("a"), effect.successIds)
        assertEquals(listOf("b"), effect.failureIds)
    }
}

@Test fun stateGroupsByActionCategory() = runTest {
    val repo = InMemoryProposalsRepository(initial = listOf(
        p("mv1", actions = listOf(action("move"))),
        p("tg1", actions = listOf(action("set_tags"))),
        p("rp1", actions = listOf(action("apply_sample_match"))),
    ))
    val holder = ProposalsStateHolder(repo, backgroundScope)
    holder.state.test {
        var s = awaitItem()
        while (s.groups.isEmpty()) s = awaitItem()
        assertEquals(
            setOf("Organization", "Metadata", "Repair"),
            s.groups.map { it.label }.toSet(),
        )
    }
}

@Test fun searchFiltersByProjectArg() = runTest {
    // Filter by `args.project` when present; otherwise by any string value
    // ...
}
```

(Helper builders `p(...)` and `action(...)` — add at the top of the test class. The existing tests already use `InMemoryProposalsRepository`; if no such fake exists, create one in `shared/repository/src/commonMain/kotlin/com/sketchbook/repo/impl/InMemoryProposalsRepository.kt` with a `failOn` set for forced-failure tests.)

**Step 2: Run, confirm fail.**

**Step 3: Implement** — extend `ProposalsStateHolder`:

```kotlin
enum class ProposalCategory(val label: String) {
    Organization("Organization"),
    Metadata("Metadata"),
    Repair("Repair"),
}

data class ProposalGroup(
    val category: ProposalCategory,
    val label: String,
    val proposals: List<Proposal>,
)

enum class SourceFilter { All, Mcp, Code, User }

// Extend State:
data class State(
    val pending: List<Proposal> = emptyList(),
    val resolved: List<Proposal> = emptyList(),
    val loading: Boolean = false,
    val sourceFilter: SourceFilter = SourceFilter.All,
    val search: String = "",
    val groups: List<ProposalGroup> = emptyList(),
)

// Add intents BulkApprove/BulkReject/SetSourceFilter/SetSearch.
// In dispatch, BulkApprove loops `state.value.pending.find { it.proposalId == id }`,
// then `executor?.apply(...)` and `repository.approve(id)` — collect successes vs. failures
// and emit `Effect.BulkApproved(successIds, failureIds)`.
//
// Source filter and search live in a separate MutableStateFlow that's combined with the
// repository flow:
//
//   val state: StateFlow<State> = combine(repository.observe(), filtersFlow) { proposals, filters ->
//       val visible = proposals
//           .filter { matchesSource(it, filters.source) }
//           .filter { matchesSearch(it, filters.search) }
//       val pending = visible.filter { it.status == ProposalStatus.Pending }
//       val groups = pending.groupBy(::categoryOf).map { (cat, list) ->
//           ProposalGroup(cat, cat.label, list.sortedBy { primaryProjectKey(it) })
//       }.sortedBy { it.category.ordinal }
//       State(pending = pending, resolved = visible.filter { it.status != ProposalStatus.Pending },
//             groups = groups, sourceFilter = filters.source, search = filters.search,
//             loading = false)
//   }.stateIn(...)

private fun categoryOf(p: Proposal): ProposalCategory {
    val firstType = p.actions.firstOrNull()?.type
    return when (firstType) {
        "set_tags" -> ProposalCategory.Metadata
        "apply_sample_match", "dismiss_sample" -> ProposalCategory.Repair
        else -> ProposalCategory.Organization
    }
}
```

**Step 4: Run, confirm pass.**

**Step 5: Commit.**

```bash
git add shared/feature-proposals/src/commonMain/kotlin/com/sketchbook/featureproposals/ProposalsStateHolder.kt \
        shared/feature-proposals/src/commonTest/kotlin/com/sketchbook/featureproposals/ProposalsStateHolderTest.kt \
        shared/repository/src/commonMain/kotlin/com/sketchbook/repo/impl/InMemoryProposalsRepository.kt
git commit -m "feat(proposals): bulk approve/reject, source filter, action-category grouping"
```

---

### Task 1.2: Refactor `ProposalsScreen` to grouped sections + detail-pane slot

**Files:**
- Modify: `shared/feature-proposals/src/commonMain/kotlin/com/sketchbook/featureproposals/ProposalsScreen.kt`

Add a `detailPane` slot parameter, mirroring `ProjectListScreen`. Use `PageHeader`, `FilterChipRow`, `CollapsibleGroupHeader`, and `Surface` rows. Keep per-row `[✓] [✗]` ghost icon buttons. Click a row body → dispatches `Intent.OpenDetail(id)` (new — add to state holder if not already).

**Layout sketch:**

```kotlin
@Composable
fun ProposalsScreen(
    holder: ProposalsStateHolder,
    modifier: Modifier = Modifier,
    detailPane: @Composable ((proposalId: String, dismiss: () -> Unit) -> Unit)? = null,
) {
    val state by holder.state.collectAsState()
    var openId by remember { mutableStateOf<String?>(null) }
    val expanded = remember { mutableStateMapOf<ProposalCategory, Boolean>() }

    Row(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight().background(AppTheme.colors.surfacePage).padding(AppTheme.spacing.md)) {
            PageHeader(
                title = "Proposals",
                subtitle = "${state.pending.size} pending",
                actions = {
                    FilterChipRow(
                        options = listOf(
                            FilterChipOption(SourceFilter.All, "All"),
                            FilterChipOption(SourceFilter.Mcp, "MCP"),
                            FilterChipOption(SourceFilter.Code, "Code"),
                            FilterChipOption(SourceFilter.User, "User"),
                        ),
                        selected = state.sourceFilter,
                        onSelected = { holder.dispatch(ProposalsStateHolder.Intent.SetSourceFilter(it)) },
                    )
                },
            )
            // search row...
            LazyColumn(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
                state.groups.forEach { group ->
                    val isOpen = expanded[group.category] ?: true
                    item(key = "h-${group.category}") {
                        CollapsibleGroupHeader(
                            title = group.label,
                            count = group.proposals.size,
                            expanded = isOpen,
                            onToggle = { expanded[group.category] = !isOpen },
                            actions = {
                                Button(onClick = {
                                    holder.dispatch(ProposalsStateHolder.Intent.BulkApprove(group.proposals.map { it.proposalId }))
                                }, variant = ButtonVariant.Primary) {
                                    Text("Approve ${group.proposals.size}")
                                }
                                Button(onClick = {
                                    holder.dispatch(ProposalsStateHolder.Intent.BulkReject(group.proposals.map { it.proposalId }))
                                }, variant = ButtonVariant.Ghost) {
                                    Text("Reject")
                                }
                            },
                        )
                    }
                    if (isOpen) {
                        items(group.proposals, key = { "p-${it.proposalId}" }) { p ->
                            ProposalRow(p, onOpen = { openId = p.proposalId }, holder = holder)
                        }
                    }
                }
                // "12 resolved this session — show" footer (collapsed by default)
            }
        }
        if (openId != null && detailPane != null) {
            detailPane(openId!!) { openId = null }
        } else {
            DetailPaneEmpty()
        }
    }
}

@Composable
private fun ProposalRow(p: Proposal, onOpen: () -> Unit, holder: ProposalsStateHolder) {
    Surface(color = AppTheme.colors.surfacePanel, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
            Tag(label = p.actor)
            Column(modifier = Modifier.weight(1f)) {
                Text(humanReadable(p.actions.first()), style = AppTheme.typography.body)
                if (p.actions.size > 1) {
                    Text("+ ${p.actions.size - 1} more", style = AppTheme.typography.caption)
                }
            }
            Text(relativeTime(p.submittedAt), style = AppTheme.typography.caption)
            Button(onClick = { holder.dispatch(ProposalsStateHolder.Intent.Approve(p.proposalId)) }, variant = ButtonVariant.Ghost) { Text("✓") }
            Button(onClick = { holder.dispatch(ProposalsStateHolder.Intent.Reject(p.proposalId)) }, variant = ButtonVariant.Ghost) { Text("✗") }
        }
    }
}
```

(The `relativeTime(Instant)` helper is small; copy the pattern from `RootContent.kt`'s `relativeFromInstant`.)

**Step 1:** No state-holder behavior to test here — this is composition. Skip directly to implementation; verify visually at the end of the phase.

**Step 2: Commit.**

```bash
git add shared/feature-proposals/src/commonMain/kotlin/com/sketchbook/featureproposals/ProposalsScreen.kt
git commit -m "feat(proposals): grouped sections, bulk action headers, detail-pane slot"
```

---

### Task 1.3: Proposal detail-pane content

**Files:**
- Create: `shared/feature-proposals/src/commonMain/kotlin/com/sketchbook/featureproposals/ProposalDetailPane.kt`

Composable that takes `(proposalId: String, holder: ProposalsStateHolder, onDismiss: () -> Unit)` and renders the full proposal: rationale, action list with `Show JSON` toggle, submitted-at, actor chip, target-project link. Footer: `[Approve]` `[Reject]`.

```kotlin
@Composable
fun ProposalDetailPane(
    proposalId: String,
    holder: ProposalsStateHolder,
    onDismiss: () -> Unit,
) {
    val state by holder.state.collectAsState()
    val p = (state.pending + state.resolved).firstOrNull { it.proposalId == proposalId }
    if (p == null) { DetailPaneEmpty("Proposal not found"); return }
    DetailPane(
        title = "Proposal · ${p.proposalId}",
        onDismiss = onDismiss,
        body = {
            // rationale, actor, submittedAt
            // action list with per-row Show JSON toggle
            // ...
        },
        footer = {
            Button(onClick = { holder.dispatch(ProposalsStateHolder.Intent.Reject(proposalId)) }, variant = ButtonVariant.Ghost) { Text("Reject") }
            Button(onClick = { holder.dispatch(ProposalsStateHolder.Intent.Approve(proposalId)) }, variant = ButtonVariant.Primary) { Text("Approve") }
        },
    )
}
```

**Commit:**

```bash
git add shared/feature-proposals/src/commonMain/kotlin/com/sketchbook/featureproposals/ProposalDetailPane.kt
git commit -m "feat(proposals): detail pane content"
```

---

### Task 1.4: Wire the snackbar + detail pane in `RootContent`

**Files:**
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt`

In `RootContent`:
- `val proposalsSnackbar = remember { BulkUndoSnackbarState(coroutineScope) }`
- Collect `proposalsHolder.effects`: on `BulkApproved(ids, _)`, call `proposalsSnackbar.show("Approved ${ids.size} proposals", onUndo = { proposalsHolder.dispatch(BulkReject(ids)) })`. Similar for `BulkRejected`.
- In the `Screen.Proposals -> ...` entry, pass `detailPane = { id, dismiss -> ProposalDetailPane(id, proposalsHolder, dismiss) }`.
- Render `BulkUndoSnackbar(proposalsSnackbar, modifier = ...)` as an overlay aligned to the bottom of the right pane.

**Commit:**

```bash
git add app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt
git commit -m "feat(proposals): wire detail pane + bulk-undo snackbar in RootContent"
```

---

### Task 1.5: Manual verification — proposals

Run `./gradlew :app-desktop:run`. Drive the golden path:
1. Navigate to Proposals.
2. Confirm grouping headers render (Organization / Metadata / Repair) with counts.
3. Click `[Approve N]` on a group. Confirm rows fade and snackbar appears with countdown.
4. Click `Undo` within 5s. Confirm rows return.
5. Click a row → confirm detail pane opens on the right and shows human-readable actions.
6. Click another row → confirm pane content swaps without re-mounting.

If anything is off, file a follow-up task and iterate. No commit unless code changes.

---

## Phase 2 — Needs Attention

### Task 2.1: Bulk intents + sub-grouping on `NeedsAttentionStateHolder`

**Files:**
- Modify: `shared/feature-needs-attention/src/commonMain/kotlin/com/sketchbook/featureneedsattention/NeedsAttentionStateHolder.kt`
- Test: `shared/feature-needs-attention/src/commonTest/kotlin/com/sketchbook/featureneedsattention/NeedsAttentionStateHolderTest.kt`

Add intents `BulkAck(projectIds: List<ProjectId>)`, `BulkApplyAutoMatch(findings: List<MissingSampleFinding>)`, `BulkDismiss(findings: List<MissingSampleFinding>)`. Add effects `BulkApplied(successKeys, failureKeys)` etc.

Add to `State`:
- `macImportsByFolder: Map<String /* parentDir */, List<MacImportFinding>>`
- `missingByConfidence: MissingByConfidence` containing three lists: `autoMatch`, `multiCandidate`, `noCandidate`.

Classification:
- `auto-match` ↔ `f.autoMatch != null`
- `multi-candidate` ↔ `f.autoMatch == null && f.candidates.size >= 1`
- `no-candidate` ↔ `f.autoMatch == null && f.candidates.isEmpty()`

**Step 1: Failing test (new methods)**

```kotlin
@Test fun stateBucketsMissingByConfidence() = runTest {
    val auto = missingFinding.copy(missingPath = "a", autoMatch = SampleCandidate("hit", "a", 0))
    val multi = missingFinding.copy(missingPath = "b", candidates = listOf(SampleCandidate("c1", "b", 0), SampleCandidate("c2", "b", 0)))
    val none = missingFinding.copy(missingPath = "c")
    val repo = FakeRepo(RepairFindings(emptyList(), listOf(auto, multi, none), 3, false))
    val holder = NeedsAttentionStateHolder(repo, backgroundScope)
    holder.state.test {
        var s = awaitItem()
        while (s.missingByConfidence.autoMatch.isEmpty()) s = awaitItem()
        assertEquals(1, s.missingByConfidence.autoMatch.size)
        assertEquals(1, s.missingByConfidence.multiCandidate.size)
        assertEquals(1, s.missingByConfidence.noCandidate.size)
    }
}

@Test fun bulkApplyAutoMatchAppliesAll() = runTest {
    // ...
}

@Test fun bulkAckRoutesEachProject() = runTest {
    // ...
}
```

**Step 2-5:** Implement, run, commit.

```bash
git commit -m "feat(needs-attention): bulk intents + confidence/folder sub-grouping"
```

---

### Task 2.2: Refactor `NeedsAttentionScreen` + detail-pane slot

**Files:**
- Modify: `shared/feature-needs-attention/src/commonMain/kotlin/com/sketchbook/featureneedsattention/NeedsAttentionScreen.kt`
- Create: `shared/feature-needs-attention/src/commonMain/kotlin/com/sketchbook/featureneedsattention/NeedsAttentionDetailPane.kt`

Use `PageHeader`, `CollapsibleGroupHeader`, `Surface`. Three sub-groups under Missing samples; per-folder sub-groups under Mac-imported. Each sub-group has the right bulk button. Add `detailPane` slot.

The detail pane variants live in `NeedsAttentionDetailPane.kt` — pick by what kind of finding is open (`MacImportFinding` vs `MissingSampleFinding`).

**Commit:**

```bash
git commit -m "feat(needs-attention): grouped sections, bulk headers, detail pane"
```

---

### Task 2.3: Wire snackbar + detail pane in `RootContent`

Same shape as Task 1.4. New `BulkUndoSnackbarState` for needs-attention. Bulk-undo for `BulkApplied` is more nuanced — for v1 the snackbar's `onUndo` simply re-dispatches the per-finding `Dismiss` for the just-applied set (which un-relinks). Document the limitation in a one-line code comment.

**Commit:**

```bash
git commit -m "feat(needs-attention): wire detail pane + snackbar in RootContent"
```

---

### Task 2.4: Manual verification — needs attention

Drive the desktop app with a fixture that produces 99+ missing samples. Confirm bucket counts, bulk Apply, bulk Dismiss, and folder-level Acknowledge all work and the snackbar appears.

---

## Phase 3 — Journal

### Task 3.1: Inject `ProjectRepository` for project-name resolution

**Files:**
- Modify: `shared/feature-journal/src/commonMain/kotlin/com/sketchbook/featurejournal/JournalStateHolder.kt`
- Modify: `shared/feature-journal/src/commonTest/kotlin/com/sketchbook/featurejournal/JournalStateHolderTest.kt`
- Modify: `app-desktop/src/jvmMain/kotlin/com/sketchbook/desktop/RootContent.kt` (constructor wiring)

Currently `JournalStateHolder(repository, scope, limit)`. Add `private val projects: ProjectRepository`. Rendered `JournalEntry`s become a new `JournalRow(entry, projectName)` data class so the UI doesn't have to look up names per-recomposition.

The state's `entries` list becomes `List<JournalRow>`. Resolve names by snapshotting the latest `projects.observeProjects()` emission and keeping a `Map<ProjectId, String>`. Names that don't resolve render as `project #{id}`.

**Step 1: Failing test:** assert `state.rows[0].projectName == "Foo"` when the project repo has `ProjectId(7) -> "Foo"`.

**Step 2-5:** Implement and commit.

```bash
git commit -m "feat(journal): resolve project names in state holder"
```

---

### Task 3.2: Filter / search / date-range state

**Files:**
- Modify: `JournalStateHolder.kt` and its test.

Add intents `SetActionTypeFilter(ActionTypeFilter)`, `SetSearch(String)`, `SetDateRange(DateRange)`. Add corresponding state fields. Visible `rows` is filtered server-side.

Enums:
- `ActionTypeFilter { All, Move, Rename, Archive, Tag, Lock, Conflict }`
- `DateRange { Today, Last7Days, Last30Days, AllTime }`

**Step 1: Failing tests** for each filter axis.

**Step 2-5:** Implement, commit.

```bash
git commit -m "feat(journal): filter / search / date-range state"
```

---

### Task 3.3: Single + bulk undo

**Files:**
- Modify: `JournalStateHolder.kt` and its test.

Add intents `Undo(sequence: Long)`, `BulkUndo(sequences: List<Long>)`. Implementation:

```kotlin
private suspend fun undoOne(entry: JournalEntry): Result<Unit> = when (val a = entry.action) {
    is ActionRecord.Move -> projects.move(entry.projectId, parentDirOf(a.pathBefore))
        .map {}
    is ActionRecord.Rename -> projects.rename(entry.projectId, a.nameBefore).map {}
    is ActionRecord.Archive -> projects.archive(entry.projectId, a.wasArchived).map {}
    is ActionRecord.SetTags -> projects.setTags(entry.projectId, a.before).map {}
    is ActionRecord.ForceTakeLock -> Result.failure(IllegalArgumentException("not invertible"))
    is ActionRecord.PushConflict -> Result.failure(IllegalArgumentException("not invertible"))
}
```

**Safety check.** Before calling `move` or `rename`, fetch `projects.observeProject(id).first()` and verify the current path/name matches `pathAfter` / `nameAfter`. If not, return `Result.failure(IllegalStateException("file is no longer at the recorded location — undo skipped"))`.

`BulkUndo` loops `undoOne` and emits `Effect.BulkUndone(successSequences, failureSequences)`.

**Step 1: Failing tests** — invertible types succeed, non-invertible fail, safety check rejects when on-disk state diverges, bulk undo collects partial failures.

**Step 2-5:** Implement, commit.

```bash
git commit -m "feat(journal): single + bulk undo with on-disk safety check"
```

---

### Task 3.4: Refactor `JournalScreen` + detail-pane slot

**Files:**
- Modify: `shared/feature-journal/src/commonMain/kotlin/com/sketchbook/featurejournal/JournalScreen.kt`
- Create: `shared/feature-journal/src/commonMain/kotlin/com/sketchbook/featurejournal/JournalDetailPane.kt`

Use `PageHeader`, `FilterChipRow` (action types), date-range chips, search field, day-grouped sections via `CollapsibleGroupHeader` (today/yesterday/older). Each row: action verb, project name, before→after via `humanReadable(action)`, timestamp, `[Undo]` button (hidden for non-invertible).

Sticky toolbar appears when the filter narrows the view: `[Undo N renames in this view]`.

Detail pane: full diff, sequence number, `[Open project]` (existing nav effect), `[Undo this]` for invertible.

**Commit:**

```bash
git commit -m "feat(journal): grouped sections, before→after rendering, undo buttons, detail pane"
```

---

### Task 3.5: Wire snackbar + detail pane in `RootContent`

Same pattern. `Effect.BulkUndone` → snackbar `Undid 8 renames. Undo · 5s` whose `onUndo` re-applies the *original* actions (via `BulkApprove`-equivalent — actually simpler: dispatch the *forward* moves/renames again using the same repo methods). For v1, simplest: `onUndo` is a no-op snackbar (just inform the user; cannot redo). Add a comment noting this and revisit.

**Commit:**

```bash
git commit -m "feat(journal): wire detail pane + bulk-undo snackbar"
```

---

### Task 3.6: Manual verification — journal

Drive the desktop app: rename a project, observe a Move/Rename appear in Journal with proper before→after rendering. Click `[Undo]` and verify the file reverts. Drive bulk filter (e.g., select Today + Rename) and `[Undo all]`. Confirm safety check fires when undoing an out-of-date entry.

---

## Phase 4 — Integration tests

### Task 4.1: Bulk approve in `ProposalApplyTest`

**Files:**
- Modify: `tests/integration/src/jvmTest/kotlin/com/sketchbook/integration/ProposalApplyTest.kt`

Add `bulkApproveAppliesEachActionAndAdvancesAllToApproved` — submit three proposals across two action categories, dispatch `BulkApprove` with all three ids, assert the catalog reflects all three changes and all three repo rows transition to Approved.

**Commit:**

```bash
git commit -m "test(integration): bulk approve in ProposalApplyTest"
```

---

### Task 4.2: Bulk apply + dismiss for missing samples

**Files:**
- Modify: an existing test in `tests/integration/.../*Repair*Test.kt`, or create one if missing.

Stage a project with 3 auto-match findings, dispatch `BulkApplyAutoMatch`, assert all three `project_samples` rows update.

**Commit:**

```bash
git commit -m "test(integration): bulk apply auto-match for missing samples"
```

---

### Task 4.3: Journal undo round-trip

**Files:**
- Create: `tests/integration/src/jvmTest/kotlin/com/sketchbook/integration/JournalUndoTest.kt`

Steps in test:
1. Rename project A from `foo.als` to `bar.als`.
2. Assert journal has a Rename entry.
3. Dispatch `JournalStateHolder.Intent.Undo(sequence)`.
4. Assert project name is back to `foo.als`.
5. Assert journal now has *two* entries (the original Rename, plus the compensating one).

Then test the safety case:
6. Rename A again to `baz.als`.
7. Try to undo the *first* Rename entry (foo→bar).
8. Assert failure with `file is no longer at the recorded location`.

**Commit:**

```bash
git commit -m "test(integration): journal undo round-trip + safety check"
```

---

## Phase 5 — Final manual verification

Run `./gradlew :app-desktop:run`. Drive a full session:

1. Drop the dev fixtures into a library root with: 30 mock proposals (mix of categories), 100+ missing samples (~half auto-match, some multi-candidate), and recent journal entries.
2. **Proposals**: filter by source, search by project name, bulk-approve a category, undo, drill into one and approve from the pane.
3. **Needs Attention**: bulk-Apply auto-matches, expand a folder under Mac-imported, bulk-Acknowledge it, dismiss a multi-candidate via the pane.
4. **Journal**: filter to Today + Rename, see only the recent renames, click Undo on one, watch it revert. Try the bulk Undo on the filter view.

Look for: stuck snackbars, snackbars not stacking right, detail pane state not clearing on screen change, group counts off, performance issues with 100+ rows.

If anything is broken, file a follow-up task and iterate. No PR until this passes.

---

## Phase 6 — One PR (per `feedback_one_pr_at_a_time.md`)

```bash
git push -u origin <branch>
gh pr create --title "Proposals/Needs Attention/Journal: grouped + bulk + undo + detail pane" \
  --body "$(cat <<'EOF'
## Summary
- Group queue items by what determines the action (action category / confidence / day)
- Bulk Approve / Reject / Apply / Acknowledge / Dismiss / Undo from group headers
- 5-second undo snackbar on every bulk action
- Right-side detail pane with full per-item context
- Journal undo round-trips through ProjectRepository's existing mutators

## Test plan
- [ ] State-holder unit tests pass (proposals, needs-attention, journal)
- [ ] Integration tests pass: ProposalApplyTest, missing-sample bulk apply, JournalUndoTest
- [ ] Manual desktop verification of all three screens with 100+ row fixture
EOF
)"
```

Per `feedback_local_build_authority.md`: when the local build passes, merge with `--admin`.
