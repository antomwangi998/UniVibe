package com.univibe.app.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Pull down to refresh. [refreshing] must come from the screen's real loading state, so the spinner shows for exactly
 * as long as the reload takes. The content has to scroll (a LazyColumn, for example) for the pull to start.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VibePullToRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier) { content() }
}
