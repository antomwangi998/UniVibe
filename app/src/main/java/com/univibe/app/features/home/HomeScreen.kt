package com.univibe.app.features.home

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import com.univibe.app.ui.components.PhotoImage
import com.univibe.app.data.media.PhotoStore
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.univibe.app.ui.components.rememberBottomClearance
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.LocalFloatingBarVisible
import com.univibe.app.ui.components.FloatingBarClearance
import com.univibe.app.data.model.Post
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Block
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.model.Student
import com.univibe.app.ui.components.CampusPostCard
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.StoryRing
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.UniVibeLockup
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onOpenEvents: () -> Unit = {},
    onOpenComments: (String) -> Unit = {},
    onOpenStudent: (String) -> Unit = {},
    onCreatePost: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val composerFocus = remember { FocusRequester() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            VibeTopBar(title = "UniVibe", titleContent = { UniVibeLockup() })
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, bottom = rememberBottomClearance()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item(key = "stories") { StoriesRow(state.visibleStories) }
                item(key = "composer") {
                    Composer(
                        fullName = state.me?.name ?: "Aisha Njoki",
                        publishing = state.publishing,
                        error = state.publishError,
                        postedCount = state.postedCount,
                        focusRequester = composerFocus,
                        onOpenEvents = onOpenEvents,
                        onPost = { text, media, path -> viewModel.publish(text, media, path) },
                    )
                }
                item(key = "filters") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        FeedFilter.entries.forEach { filter ->
                            InterestChip(filter.name, selected = state.filter == filter, onClick = { viewModel.setFilter(filter) })
                        }
                    }
                }
                when {
                    state.loading -> item(key = "loading") { LoadingSkeleton() }
                    state.error != null -> item(key = "error") { ErrorState(state.error!!, onRetry = viewModel::load) }
                    state.visiblePosts.isEmpty() -> item(key = "empty") {
                        EmptyState(
                            icon = Icons.Outlined.DynamicFeed,
                            title = "Nothing here yet",
                            message = "Follow more students and communities to fill your feed.",
                        )
                    }
                    else -> items(state.visiblePosts, key = { it.id }) { post ->
                        CampusPostCard(
                            post = post,
                            onLike = { viewModel.toggleLike(post.id) },
                            onComment = { onOpenComments(post.id) },
                            onAuthorClick = { onOpenStudent(post.author.id) },
                            onSave = { viewModel.toggleSave(post.id) },
                            onMore = { viewModel.openPostMenu(post) },
                        )
                    }
                }
            }
        }

        // Opens the full create-post screen. Rides just above the floating bar and drops down when it hides.
        val barVisible = LocalFloatingBarVisible.current
        val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val fabBottom by animateDpAsState(
            targetValue = if (barVisible) FloatingBarClearance + navInset + 8.dp else navInset + Spacing.md,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
            label = "fabBottom",
        )
        FloatingActionButton(
            onClick = onCreatePost,
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = Spacing.md, bottom = fabBottom),
        ) { Icon(Icons.Outlined.Edit, contentDescription = "Write a post") }
    }

    val context = LocalContext.current
    LaunchedEffect(state.notice) {
        state.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeNotice()
        }
    }

    state.menuPost?.let { post ->
        PostActionsSheet(
            post = post,
            isOwn = post.author.id == state.me?.id,
            onDismiss = viewModel::closePostMenu,
            onBlock = viewModel::blockAuthor,
            onReport = viewModel::reportPost,
        )
    }
}

private val ReportReasons = listOf("Spam", "Harassment or bullying", "Inappropriate content", "False information", "Something else")

@Composable
private fun PostActionsSheet(post: Post, isOwn: Boolean, onDismiss: () -> Unit, onBlock: () -> Unit, onReport: (String) -> Unit) {
    var choosingReason by remember(post.id) { mutableStateOf(false) }
    VibeBottomSheet(onDismiss = onDismiss) {
        when {
            isOwn -> Text("Editing and deleting your posts is coming soon.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = Spacing.md))
            choosingReason -> {
                Text("Why are you reporting this?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = Spacing.sm))
                ReportReasons.forEach { reason -> SheetOption(Icons.Outlined.Flag, reason) { onReport(reason) } }
            }
            else -> {
                SheetOption(Icons.Outlined.Flag, "Report post") { choosingReason = true }
                SheetOption(Icons.Outlined.Block, "Block ${post.author.name}", destructive = true, onClick = onBlock)
            }
        }
    }
}

@Composable
private fun SheetOption(icon: ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(Spacing.md))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

/**
 * Inline composer. Typing, Post, and each shortcut are separate controls; the card itself is not a button.
 */
@Composable
private fun Composer(
    fullName: String,
    publishing: Boolean,
    error: String?,
    postedCount: Int,
    focusRequester: FocusRequester,
    onOpenEvents: () -> Unit,
    onPost: (text: String, hasMedia: Boolean, imagePath: String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var attached by rememberSaveable { mutableStateOf<String?>(null) }
    var attachedPath by rememberSaveable { mutableStateOf<String?>(null) }
    var seenPosts by rememberSaveable { mutableStateOf(postedCount) }

    // Clear the draft after a successful post.
    LaunchedEffect(postedCount) {
        if (postedCount != seenPosts) {
            text = ""
            attached = null
            attachedPath = null
            seenPosts = postedCount
        }
    }

    // System photo picker: no storage permission needed.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = PhotoStore.import(context, uri, "post")
            if (path != null) {
                PhotoStore.delete(attachedPath)
                attached = "Photo"; attachedPath = path
            } else Toast.makeText(context, "Couldn't use that image.", Toast.LENGTH_SHORT).show()
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { PhotoStore.delete(attachedPath); attachedPath = null; attached = "Video" }
    }

    val tooLong = text.length > MAX_POST_LENGTH
    val hasContent = text.isNotBlank() || attached != null
    val firstName = fullName.substringBefore(' ')

    VibeCard {
        Row(verticalAlignment = Alignment.Top) {
            StudentAvatar(fullName, size = 40.dp, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.width(Spacing.sm + 4.dp))
            VibeTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = "What's on your mind, $firstName?",
                singleLine = false,
                isError = tooLong || error != null,
                supportingText = error ?: if (text.isNotEmpty()) "${text.length}/$MAX_POST_LENGTH" else null,
            )
        }

        attached?.let { kind ->
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = Spacing.sm),
            ) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (kind == "Photo") Icons.Outlined.Image else Icons.Outlined.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("$kind attached", style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { PhotoStore.delete(attachedPath); attachedPath = null; attached = null }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Remove $kind", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        attachedPath?.let { path ->
            Box(Modifier.padding(top = Spacing.sm).size(96.dp).clip(RoundedCornerShape(16.dp))) {
                PhotoImage(path, contentDescription = "Selected photo preview")
            }
        }

        AnimatedVisibility(visible = hasContent) {
            Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), horizontalArrangement = Arrangement.End) {
                VibeButton(
                    text = "Post",
                    onClick = { onPost(text, attached != null, attachedPath) },
                    enabled = !tooLong,
                    loading = publishing,
                    compact = true,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ComposerAction(Icons.Outlined.Image, "Photo") {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            ComposerAction(Icons.Outlined.Videocam, "Video") {
                videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }
            ComposerAction(Icons.Outlined.Event, "Event", onOpenEvents)
            ComposerAction(Icons.Outlined.Poll, "Poll") {
                Toast.makeText(context, "Polls are coming soon", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun ComposerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StoriesRow(stories: List<Student>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        item(key = "you") {
            Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(72.dp).border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape).padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(58.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Add, contentDescription = "Add to your story", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                Text("Your story", style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            }
        }
        items(stories, key = { it.id }) { student ->
            Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                StoryRing(ringSize = 72.dp) {
                    StudentAvatar(student.name, size = 58.dp, accent = student.accent, online = student.online)
                }
                Text(
                    student.name.substringBefore(' '),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
