package com.sketchbook.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * NavStack: a `StateFlow<List<Screen>>` plus `push` / `pop` / `replace`. No navigation library —
 * the user explicitly rejected those. The list's last element is the visible screen.
 *
 * `push` writes a new entry; `pop` discards the tail (no-op if only one entry remains so the
 * back button can never strand the user on an empty stack); `replace` swaps the tail.
 */
class RootStateHolder(initial: Screen = Screen.Projects) {

    private val _stack = MutableStateFlow(listOf(initial))
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()
    val current: Screen get() = _stack.value.last()

    fun push(screen: Screen) {
        _stack.update { it + screen }
    }

    fun pop(): Boolean {
        var popped = false
        _stack.update { current ->
            if (current.size <= 1) current
            else {
                popped = true
                current.dropLast(1)
            }
        }
        return popped
    }

    fun replace(screen: Screen) {
        _stack.update { it.dropLast(1) + screen }
    }

    fun reset(screen: Screen) {
        _stack.update { listOf(screen) }
    }
}
