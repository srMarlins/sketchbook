package com.sketchbook.uishared.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sketchbook.uishared.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 5-second undo snackbar state. The composable [BulkUndoSnackbar] observes [current] +
 * [secondsRemaining] and renders / dismisses accordingly.
 *
 * A second [show] while one snackbar is live commits the in-flight one (fires its `onExpire`)
 * and starts a fresh countdown — most-recent-wins. [undo] cancels the countdown and fires
 * `onUndo` instead of `onExpire`.
 *
 * `onUndo` is optional: when `null`, the snackbar renders informationally (no Undo button) and
 * [undo] is a no-op. Used by needs-attention bulk effects, which v1 cannot meaningfully revert.
 */
class BulkUndoSnackbarState(private val scope: CoroutineScope) {
    data class Visible(
        val message: String,
        val onUndo: (() -> Unit)?,
        val onExpire: (() -> Unit)?,
    )

    private val _current = MutableStateFlow<Visible?>(null)
    val current: StateFlow<Visible?> = _current.asStateFlow()

    private val _secondsRemaining = MutableStateFlow(0)
    val secondsRemaining: StateFlow<Int> = _secondsRemaining.asStateFlow()

    private var job: Job? = null

    fun show(message: String, onUndo: (() -> Unit)? = null, onExpire: (() -> Unit)? = null) {
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
        val undoFn = v.onUndo ?: return
        job?.cancel()
        _current.value = null
        undoFn()
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
        elevation = 8.dp,
        padding = PaddingValues(AppTheme.spacing.sm),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ProvideContentColor(AppTheme.colors.inkPrimary) {
                Text(v.message, style = AppTheme.typography.body, modifier = Modifier.weight(1f))
            }
            ProvideContentColor(AppTheme.colors.inkMuted) {
                Text("${seconds}s", style = AppTheme.typography.caption)
            }
            if (v.onUndo != null) {
                Button(onClick = { state.undo() }, variant = ButtonVariant.Ghost) {
                    Text("Undo")
                }
            }
        }
    }
}
