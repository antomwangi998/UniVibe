package com.univibe.app.navigation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow

/** Tapping the tab you are already on: the shell fires this, the tab's list scrolls back to the top. */
object TabReselect {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<String> = _events.asSharedFlow()
    fun fire(route: String) { _events.tryEmit(route) }
}

/** A list state that jumps back to the top when the tab for [route] is tapped again. */
@Composable
fun rememberTabListState(route: String): LazyListState {
    val state = rememberLazyListState()
    LaunchedEffect(state, route) {
        TabReselect.events.collect { fired ->
            if (fired == route) {
                // From far down, hop most of the way first so the scroll back is quick rather than a long animation.
                if (state.firstVisibleItemIndex > 8) state.scrollToItem(8)
                state.animateScrollToItem(0)
            }
        }
    }
    return state
}
