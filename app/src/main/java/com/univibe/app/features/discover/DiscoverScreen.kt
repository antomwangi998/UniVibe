package com.univibe.app.features.discover

import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.rememberBottomClearance
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import android.widget.Toast
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.VibeBottomSheet
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.model.Student
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.ProfileDiscoveryCard
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun DiscoverScreen(
    onOpenStudent: (String) -> Unit = {},
    onOpenComments: (String) -> Unit = {},
    peopleViewModel: DiscoverViewModel = viewModel(),
    viewModel: DiscoverContentViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // One scroll position per list, kept while the student moves between categories.
    val forYouList = rememberLazyListState()
    val trendingDay = rememberLazyListState()
    val trendingWeek = rememberLazyListState()
    val videosList = rememberLazyListState()
    val topicList = rememberLazyListState()
    val photosGrid = rememberLazyStaggeredGridState()
    val actions = remember(viewModel, onOpenComments, onOpenStudent) {
        PostActions(
            onLike = viewModel::toggleLike, onSave = viewModel::toggleSave, onComment = onOpenComments,
            onAuthor = onOpenStudent, onMore = viewModel::openMenu, onShared = viewModel::noteShared,
            onExplain = viewModel::explain, onVote = viewModel::vote,
        )
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeNotice()
        }
    }

    Column(Modifier.fillMaxSize()) {
        VibeTopBar("Discover")
        CategoryChips(state.category, viewModel::select)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.category) {
                DiscoverCategory.People -> PeopleDeck(onOpenStudent, peopleViewModel)
                DiscoverCategory.ForYou -> DiscoverPostList(
                    list = state.list(ListKey.ForYou), listState = forYouList,
                    emptyTitle = "Nothing to show yet", emptyMessage = "Follow a few students or post something, and Discover will fill in.",
                    showReason = true, actions = actions,
                    onRetry = { viewModel.load(ListKey.ForYou, force = true) },
                    onLoadMore = { viewModel.loadMore(ListKey.ForYou) }, onRetryMore = { viewModel.retryMore(ListKey.ForYou) },
                )
                DiscoverCategory.Trending -> {
                    val key = ListKey.Trending(state.window)
                    DiscoverPostList(
                        list = state.list(key),
                        listState = if (state.window == com.univibe.app.data.repository.TrendingWindow.Day) trendingDay else trendingWeek,
                        emptyTitle = "Nothing is trending yet", emptyMessage = "Posts that get attention from a few different students show up here.",
                        showReason = false, actions = actions,
                        onRetry = { viewModel.load(key, force = true) },
                        onLoadMore = { viewModel.loadMore(key) }, onRetryMore = { viewModel.retryMore(key) },
                        header = { TrendingWindowChips(state.window, viewModel::setWindow) },
                    )
                }
                DiscoverCategory.Photos -> DiscoverPhotoGrid(
                    list = state.list(ListKey.Photos), gridState = photosGrid,
                    onOpen = { onOpenComments(it.id) },
                    onRetry = { viewModel.load(ListKey.Photos, force = true) },
                    onLoadMore = { viewModel.loadMore(ListKey.Photos) }, onRetryMore = { viewModel.retryMore(ListKey.Photos) },
                )
                DiscoverCategory.Videos -> DiscoverPostList(
                    list = state.list(ListKey.Videos), listState = videosList,
                    emptyTitle = "No videos yet", emptyMessage = "Videos from students on your campus will show up here.",
                    showReason = false, actions = actions,
                    onRetry = { viewModel.load(ListKey.Videos, force = true) },
                    onLoadMore = { viewModel.loadMore(ListKey.Videos) }, onRetryMore = { viewModel.retryMore(ListKey.Videos) },
                )
                DiscoverCategory.Topics -> {
                    val open = state.openTopic
                    if (open == null) {
                        TopicList(state.topics, onOpen = viewModel::openTopic, onRetry = { viewModel.loadTopics(force = true) })
                    } else {
                        val key = ListKey.Topic(open)
                        DiscoverPostList(
                            list = state.list(key), listState = topicList,
                            emptyTitle = "No posts in #$open", emptyMessage = "There's nothing here you can see right now.",
                            showReason = false, actions = actions,
                            onRetry = { viewModel.load(key, force = true) },
                            onLoadMore = { viewModel.loadMore(key) }, onRetryMore = { viewModel.retryMore(key) },
                            header = { TopicHeader(open, onBack = viewModel::closeTopic, onMute = { viewModel.muteTopic(open) }) },
                        )
                    }
                }
            }
        }
    }
    state.menuPost?.let { post -> DiscoverPostMenu(post, onDismiss = viewModel::closeMenu, vm = viewModel) }
}

/** The student deck (swipe to connect), unchanged: it is the People category. */
@Composable
private fun PeopleDeck(onOpenStudent: (String) -> Unit, viewModel: DiscoverViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    var request by remember { mutableStateOf<Boolean?>(null) }

    Column(Modifier.fillMaxSize()) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null -> ErrorState(state.error!!, onRetry = viewModel::load)
            else -> {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.padding(bottom = Spacing.sm),
                ) {
                    items(DiscoverFilter.entries, key = { it.name }) { f ->
                        InterestChip(f.label, selected = state.filter == f, onClick = { viewModel.setFilter(f) })
                    }
                }
                val top = state.deck.firstOrNull()
                if (top == null) {
                    EmptyState(
                        icon = VibeIcons.Groups,
                        title = if (state.remaining.isEmpty()) "You're all caught up" else "No matches for this filter",
                        message = if (state.remaining.isEmpty()) "Check back soon for more students who share your interests."
                        else "Try another filter to see more students.",
                        actionLabel = if (state.remaining.isEmpty()) "Refresh" else "Show everyone",
                        onAction = { if (state.remaining.isEmpty()) viewModel.load() else viewModel.setFilter(DiscoverFilter.All) },
                    )
                } else {
                    Column(
                        Modifier.fillMaxSize().padding(start = Spacing.md, end = Spacing.md, bottom = rememberBottomClearance()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            key(top.id) {
                                SwipeableCard(
                                    student = top,
                                    request = request,
                                    onOpen = { onOpenStudent(top.id) },
                                    onDecision = { liked ->
                                        request = null
                                        viewModel.decide(liked)
                                    },
                                )
                            }
                        }
                        ProgressRow(state.reviewed, state.total, onInfo = { viewModel.showInfo(true) })
                        Row(
                            Modifier.padding(bottom = Spacing.md),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RoundAction(VibeIcons.Close, "Pass on ${top.name}", filled = false, enabled = request == null) { request = false }
                            RoundAction(VibeIcons.HeartFilled, "Connect with ${top.name}", filled = true, enabled = request == null) { request = true }
                        }
                    }
                }
            }
        }
    }

    if (state.showInfo) {
        VibeBottomSheet(onDismiss = { viewModel.showInfo(false) }) {
            Text("How discovery works", style = MaterialTheme.typography.titleLarge)
            Text(
                "Only verified students who chose to appear in Discover are shown. A connection only happens when both of you say yes. " +
                    "You can turn discovery off any time in Profile ▸ Privacy.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
}

@Composable
private fun ProgressRow(reviewed: Int, total: Int, onInfo: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${minOf(reviewed + 1, total)} / $total",
                style = MaterialTheme.typography.labelLarge,
            )
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else reviewed / total.toFloat() },
                modifier = Modifier.weight(1f).padding(horizontal = Spacing.sm + 4.dp).height(6.dp).clip(CircleShape),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            IconButton(onClick = onInfo, modifier = Modifier.size(32.dp)) {
                Icon(VibeIcons.Info, contentDescription = "About discovery", modifier = Modifier.size(20.dp))
            }
        }
        Text(
            "Only students who opted in to discovery appear here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwipeableCard(student: Student, request: Boolean?, onOpen: () -> Unit, onDecision: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val enter = remember { Animatable(0.94f) }
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }

    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) }

    suspend fun flyOut(liked: Boolean) {
        offsetX.animateTo(if (liked) widthPx * 1.4f else -widthPx * 1.4f, tween(220))
        onDecision(liked)
    }

    LaunchedEffect(request) { request?.let { flyOut(it) } }

    ProfileDiscoveryCard(
        student = student,
        onClick = onOpen,
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .graphicsLayer {
                rotationZ = offsetX.value / widthPx * 12f
                scaleX = enter.value
                scaleY = enter.value
            }
            .pointerInput(student.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (abs(offsetX.value) > widthPx * 0.3f) {
                                flyOut(offsetX.value > 0)
                            } else {
                                offsetX.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium))
                            }
                        }
                    },
                    onDragCancel = { scope.launch { offsetX.animateTo(0f, spring()) } },
                ) { change, dragAmount ->
                    change.consume()
                    scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                }
            },
    )
}

@Composable
private fun RoundAction(icon: ImageVector, description: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "actionPress",
    )
    Surface(
        shape = CircleShape,
        color = if (filled) colors.primary else colors.surface,
        contentColor = if (filled) colors.onPrimary else colors.onSurface,
        border = if (filled) null else BorderStroke(1.5.dp, colors.outline),
        shadowElevation = if (filled) 6.dp else 0.dp,
        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        IconButton(onClick = onClick, enabled = enabled, interactionSource = interaction, modifier = Modifier.size(72.dp)) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(32.dp))
        }
    }
}
