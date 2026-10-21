package com.univibe.app.features.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.univibe.app.data.model.Post
import com.univibe.app.data.repository.DiscoverReason
import com.univibe.app.data.repository.DiscoverTopic
import com.univibe.app.data.repository.TrendingWindow
import com.univibe.app.ui.components.CampusPostCard
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.PhotoImage
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibePullToRefresh
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.rememberBottomClearance
import com.univibe.app.ui.components.sharePost
import com.univibe.app.ui.theme.Spacing

/** The category chips under the title. Tapping the selected one reloads it. */
@Composable
internal fun CategoryChips(selected: DiscoverCategory, onSelect: (DiscoverCategory) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier.padding(bottom = Spacing.sm),
    ) {
        items(DiscoverCategory.entries, key = { it.name }) { c ->
            InterestChip(c.label, selected = selected == c, onClick = { onSelect(c) })
        }
    }
}

private fun postLabel(post: Post) = DiscoverReason.label(post.discoverReason)

/** Posts in the same card the Home feed uses, with the real reason (if any) above it. */
@Composable
internal fun DiscoverPostList(
    list: PostListState,
    listState: LazyListState,
    emptyTitle: String,
    emptyMessage: String,
    showReason: Boolean,
    actions: PostActions,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onRetryMore: () -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, list.posts.size) { if (nearEnd) onLoadMore() }
    val context = LocalContext.current
    VibePullToRefresh(refreshing = list.refreshing, onRefresh = onRetry) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = rememberBottomClearance())) {
        if (header != null) item(key = "header") { header() }
        if (list.stale && list.posts.isNotEmpty()) item(key = "stale") {
            Text(
                "Couldn't refresh. Showing earlier results.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            )
        }
        when {
            list.loading -> item(key = "loading") { Box(Modifier.padding(Spacing.md)) { LoadingSkeleton() } }
            list.error != null -> item(key = "error") { Box(Modifier.padding(Spacing.md)) { ErrorState(list.error, onRetry = onRetry) } }
            list.posts.isEmpty() -> item(key = "empty") {
                EmptyState(
                    icon = VibeIcons.Discover, title = emptyTitle, message = emptyMessage,
                    modifier = Modifier.padding(Spacing.md), actionLabel = "Refresh", onAction = onRetry,
                )
            }
            else -> items(list.posts, key = { it.id }) { post ->
                Column {
                    val reason = if (showReason) postLabel(post) else null
                    if (reason != null) {
                        Text(
                            reason,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = Spacing.md, top = Spacing.sm),
                        )
                    }
                    CampusPostCard(
                        post = post,
                        onLike = { actions.onLike(post.id) },
                        onComment = { actions.onComment(post.id) },
                        onAuthorClick = { actions.onAuthor(post.author.id) },
                        onSave = { actions.onSave(post.id) },
                        onMore = { actions.onMore(post) },
                        onShare = {
                            if (sharePost(context, post)) actions.onShared(post.id)
                            else actions.onExplain("Only campus-wide posts can be shared outside UniVibe.")
                        },
                        onVote = { index -> actions.onVote(post.id, index) },
                        onRepost = { actions.onRepost(post.id) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
                }
            }
        }
        if (list.loadingMore) item(key = "more") {
            Box(Modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        if (list.moreFailed) item(key = "moreFailed") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TextButton(onClick = onRetryMore) { Text("Couldn't load more. Tap to retry") } }
        }
        if (list.endReached && list.posts.isNotEmpty()) item(key = "end") {
            Text(
                "You're all caught up",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
    }
}

/** Everything a post card can do, so the list composables do not each take eight lambdas. */
internal class PostActions(
    val onLike: (String) -> Unit,
    val onSave: (String) -> Unit,
    val onComment: (String) -> Unit,
    val onAuthor: (String) -> Unit,
    val onMore: (Post) -> Unit,
    val onShared: (String) -> Unit,
    val onExplain: (String) -> Unit,
    val onVote: (String, Int) -> Unit,
    val onRepost: (String) -> Unit = {},
)

@Composable
internal fun TrendingWindowChips(window: TrendingWindow, onSelect: (TrendingWindow) -> Unit) {
    Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        TrendingWindow.entries.forEach { w -> InterestChip(w.label, selected = window == w, onClick = { onSelect(w) }) }
    }
}

/** Photos as a grid that keeps each picture's own proportions. Tapping a photo opens its post. */
@Composable
internal fun DiscoverPhotoGrid(
    list: PostListState,
    gridState: LazyStaggeredGridState,
    onOpen: (Post) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onRetryMore: () -> Unit,
) {
    val nearEnd by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, list.posts.size) { if (nearEnd) onLoadMore() }
    when {
        list.loading -> Box(Modifier.padding(Spacing.md)) { LoadingSkeleton() }
        list.error != null -> Box(Modifier.padding(Spacing.md)) { ErrorState(list.error, onRetry = onRetry) }
        list.posts.isEmpty() -> EmptyState(
            icon = VibeIcons.Photo, title = "No photos yet", message = "Photos from students on your campus will show up here.",
            modifier = Modifier.padding(Spacing.md), actionLabel = "Refresh", onAction = onRetry,
        )
        else -> VibePullToRefresh(refreshing = list.refreshing, onRefresh = onRetry) { LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.sm, end = Spacing.sm, bottom = rememberBottomClearance()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalItemSpacing = Spacing.xs,
        ) {
            items(list.posts, key = { it.id }) { post ->
                val path = post.thumbPaths.firstOrNull() ?: post.imagePaths.firstOrNull() ?: post.imagePath
                if (path != null) {
                    // Keep the picture's own shape, within limits so one very tall or wide photo cannot dominate.
                    val ratio = (post.mediaRatios.firstOrNull() ?: 1f).coerceIn(0.75f, 1.5f)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(ratio)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpen(post) }
                            .semantics { contentDescription = "Photo by ${post.author.name}. Open post." },
                    ) { PhotoImage(path, contentDescription = null) }
                }
            }
            if (list.moreFailed) item(key = "moreFailed") { TextButton(onClick = onRetryMore) { Text("Retry") } }
        } }
    }
}

@Composable
internal fun TopicList(
    topics: TopicsState,
    onOpen: (String) -> Unit,
    onRetry: () -> Unit,
) {
    VibePullToRefresh(refreshing = topics.refreshing, onRefresh = onRetry) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = rememberBottomClearance())) {
        when {
            topics.loading -> item(key = "loading") { Box(Modifier.padding(Spacing.md)) { LoadingSkeleton() } }
            topics.error != null -> item(key = "error") { Box(Modifier.padding(Spacing.md)) { ErrorState(topics.error, onRetry = onRetry) } }
            topics.topics.isEmpty() -> item(key = "empty") {
                EmptyState(
                    icon = VibeIcons.Discover, title = "No topics yet",
                    message = "A topic appears once a few students are talking about the same #hashtag.",
                    modifier = Modifier.padding(Spacing.md), actionLabel = "Refresh", onAction = onRetry,
                )
            }
            else -> items(topics.topics, key = { it.tag }) { t -> TopicRow(t) { onOpen(t.tag) } }
        }
    }
    }
}

@Composable
private fun TopicRow(topic: DiscoverTopic, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("#${topic.tag}", style = MaterialTheme.typography.titleMedium)
        Text(
            "${topic.posts} ${if (topic.posts == 1) "post" else "posts"} · ${topic.authors} students",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun TopicHeader(tag: String, onBack: () -> Unit, onMute: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("‹ Topics") }
        Text("#$tag", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onMute) { Text("Mute") }
    }
}

@Composable
internal fun DiscoverPostMenu(post: Post, onDismiss: () -> Unit, vm: DiscoverContentViewModel) {
    val tag = firstHashtag(post)
    VibeBottomSheet(onDismiss = onDismiss) {
        MenuRow(VibeIcons.Close, "Not interested") { vm.notInterested() }
        MenuRow(VibeIcons.Block, "Hide posts from ${post.author.name}") { vm.hideAuthor() }
        if (tag != null) MenuRow(VibeIcons.Close, "Mute #$tag") { vm.muteTopic(tag) }
        MenuRow(VibeIcons.Report, "Report: spam") { vm.report("Spam") }
        MenuRow(VibeIcons.Report, "Report: harassment or hate") { vm.report("Harassment or hate") }
        MenuRow(VibeIcons.Report, "Report: something else") { vm.report("Something else") }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
